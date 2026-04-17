# 0029 — Acceptance Test Fixtures: BaseAcceptanceTest, Testcontainers, Parallel Isolation

**Status:** Accepted
**Date:** 2026-04-17

---

## Context

ADR-0006 defines the testing pyramid (unit / contract / repository / E2E / architecture)
and ADR-0025 splits unit from integration across Maven's surefire / failsafe. That answers
"what kinds of tests exist" and "when are they run". It does **not** answer the equally
important operational questions:

1. How does every acceptance test get a **fresh, deterministic starting state** without
   paying a Docker-startup tax per test class?
2. How does the test suite run **in parallel** (ADR-0011 wants the fast feedback) without
   tests stomping on each other's rows?
3. How do tests verify **Micrometer Observation** spans without a real tracing backend?
4. What's the **idiomatic seed-and-assert** rhythm so a new test reads the same as every
   existing one?

Without explicit conventions these decisions fragment: one test starts its own Postgres
container, another uses an H2 shortcut, a third relies on `@Sql` scripts, a fourth
truncates in `@AfterEach` and collides with its neighbour running concurrently. The cost
is flaky tests on green builds and a 10-minute test run that should be three.

---

## Decision

### 1. One shared `BaseAcceptanceTest` — one Postgres for the whole JVM

```java
// starport-registry/src/test/java/com/galactic/starport/BaseAcceptanceTest.java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
public abstract class BaseAcceptanceTest {

    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("app")
            .withUsername("test")
            .withPassword("test");

    @Autowired protected TestRestTemplate rest;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected ObjectMapper objectMapper;

    // ... helpers (see §3)
}
```

Key elements:

- `@ServiceConnection` (Spring Boot 3.1+) replaces the old
  `DynamicPropertySource` boilerplate. Spring derives `spring.datasource.url`, username,
  password from the container automatically.
- **`static` field** — one container per JVM (test runner). Hundreds of tests share the
  same Postgres process; startup cost is paid once.
- `@SpringBootTest(webEnvironment = RANDOM_PORT)` — each test class gets an isolated
  HTTP port. Needed for parallel test classes.
- `@ActiveProfiles("test")` — enables `hibernate.ddl-auto: validate` (ADR-0018) and
  Eureka-off (`eureka.client.enabled: false`) for tests.

### 2. Tests truncate, not create

Each test class starts from **truncated tables** and seeds just what it needs. No test
runs DDL; Flyway (ADR-0018) does that once at container boot.

```java
// BaseAcceptanceTest.java:167
protected void truncateDomainTables() {
    jdbc.execute("TRUNCATE TABLE route, reservation, docking_bay, starport, ship, customer " +
                 "RESTART IDENTITY CASCADE");
}
```

`RESTART IDENTITY` resets sequences so assertions on generated IDs (`reservationId = 1`)
remain stable across runs.

`CASCADE` is effectively a no-op here because the schema has **no FKs** (ADR-0018). It is
retained defensively in case a future migration introduces FKs — the truncate must not
start failing silently.

### 3. Helper DSL — seed, POST, count

The base class exposes a tight vocabulary so tests read like specifications:

| Helper                                             | Purpose                                       |
|---|---|
| `seedDefaultReservationFixture(code, overrides)`  | `INSERT` starport + bay + customer + ship, ready for a reservation. |
| `postReservation(code, payload)`                   | `POST /api/v1/starports/{code}/reservations` with `TestRestTemplate`. |
| `reservationsCount(code)`                          | `SELECT COUNT(*)` — convenient assertion.     |
| `makePayload(overrides)`                           | Build a JSON map with sensible defaults, override fields for the specific case. |

```java
// BaseAcceptanceTest.java:107-157
protected Long seedDefaultReservationFixture(String destinationCode, Map<String, Object> overrides) {
    Long customerId = jdbc.queryForObject(
        "INSERT INTO customer (customer_code, name) VALUES (?, ?) RETURNING id",
        Long.class, "CUST-001", "Default Customer");
    Long shipId = jdbc.queryForObject(
        "INSERT INTO ship (ship_code, ship_class, customer_id) VALUES (?, ?, ?) RETURNING id",
        Long.class, "SHIP-001", "C", customerId);
    // ... starport + bay + route inserts
    return customerId;
}
```

`RETURNING id` keeps the seed idempotent and avoids `SELECT last_insert_id()` round
trips. This is PostgreSQL-specific — fine, ADR-0007 pins Postgres.

### 4. Parallel isolation via `@ResourceLock`

JUnit 5 runs test classes in parallel (ADR-0025 surefire config). Two classes both
calling `truncateDomainTables()` at the same time produce a race: class A truncates,
class B seeds, class A asserts on empty tables — flake.

The fix:

```java
// Any test class that truncates
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock(value = "DB_TRUNCATE", mode = ResourceAccessMode.READ_WRITE)
class ReservationLifecycleE2ETest extends BaseAcceptanceTest { ... }

// Tests that only read
@Execution(ExecutionMode.CONCURRENT)
@ResourceLock(value = "DB_TRUNCATE", mode = ResourceAccessMode.READ)
class ReadOnlyDashboardTest extends BaseAcceptanceTest { ... }
```

`ResourceAccessMode.READ_WRITE` is exclusive; `READ` shares. A write-test and any number
of read-tests can never run simultaneously; multiple read-tests run fully concurrent.

### 5. Observability verified with `TestObservationRegistry`

Metrics and traces are not tested against a real Prometheus / Tempo (ADR-0005). Instead:

```java
// FeeCalculatorServiceObservabilityTest.java:29+
private final TestObservationRegistry registry = TestObservationRegistry.create();

@Test
void records_observation_for_fee_calculation() {
    var service = new FeeCalculatorService(registry, meterRegistry);
    service.calculateFee(command);

    TestObservationRegistryAssert.assertThat(registry)
        .hasObservationWithNameEqualTo("reservations.fees.calculate")
        .hasBeenStarted()
        .hasBeenStopped()
        .hasLowCardinalityKeyValue("shipClass", "C");
}
```

- **In-memory registry** — no OTel exporter, no network I/O.
- **Fluent assertions** — `hasLowCardinalityKeyValue` / `hasHighCardinalityKeyValue`
  verify the tag discipline from ADR-0030.
- **Coverage** — a PR that accidentally drops a span or flips a tag to high-cardinality
  fails its observability test immediately.

### 6. Concurrent execution is the default, not the exception

At least seven test classes declare `@Execution(ExecutionMode.CONCURRENT)` explicitly:

- `ReservationServiceMetricsTest`
- `FeeCalculatorServiceTest`
- `CreateHoldReservationServiceObservabilityTest`
- `FeeCalculatorServiceObservabilityTest`
- `InboxPublisherObservabilityTest`
- `OutboxAppenderObservabilityTest`
- `ReservationServiceTest`

These are all **unit-style** classes that don't touch the shared Postgres (mocks or
`TestObservationRegistry` only). Their `@Execution(CONCURRENT)` is a signal: **"I can
run in parallel with anything, including other instances of myself."** Without the
annotation they would still run concurrently at class level (surefire config) but JUnit
would serialise methods — slower.

---

## How the codebase enforces this

1. **Single base class.** All acceptance tests extend `BaseAcceptanceTest`. Grepping
   `class .*E2ETest extends` finds no exceptions.
2. **No ad-hoc `@Testcontainers` declarations.** Only `BaseAcceptanceTest` instantiates
   the container. A test starting its own Postgres would fail review immediately.
3. **`@ResourceLock("DB_TRUNCATE")` is the only cross-class synchronisation.** Tests that
   do not mention the lock are forbidden from calling `truncateDomainTables()`.
4. **`TestObservationRegistry` is the sanctioned tracing assertion tool.** No test
   starts a real OTel exporter; no integration test asserts on Tempo.
5. **Seed helpers live only on the base.** Tests do not duplicate `INSERT INTO customer`
   across files — adding a field is a one-place change.

---

## Consequences

### Benefits

- **Startup cost paid once.** A ~5 s Postgres spin-up amortises across dozens of test
  classes in the same JVM. A 30-class E2E suite runs in ~90 s instead of ~300 s.
- **Tests read like scenarios.** `seedDefaultReservationFixture("ABC", Map.of("shipClass", "B"))`
  expresses intent; a new engineer understands the setup without reading schema DDL.
- **Parallelism without flakes.** `@ResourceLock` is a declarative contract; the locking
  failure mode is "serialised", not "data corruption".
- **Observability regressions caught in tests.** A counter rename or span deletion
  breaks a test, not a dashboard at 3 AM.
- **Real Postgres, real Flyway.** Repository tests exercise the same engine and
  migrations as production (ADR-0007).

### Trade-offs

- **One container per JVM, not per test.** A test that corrupts the container (rare)
  pollutes the rest of the run. Mitigation: tests call `truncateDomainTables()` in
  `@BeforeEach`, which hard-resets state.
- **`@ResourceLock` is a read-the-manual feature.** A new contributor who adds a
  truncating test without the lock introduces a flake that only fires under parallel
  load. Mitigation: PR template reminder; consider a custom annotation
  `@WritesToSharedDb` that always implies the lock.
- **`TestObservationRegistry` is in-memory.** It asserts the span existed with the right
  tags, but not that the OTel exporter would serialise it correctly. That gap is
  acceptable because the exporter is a library concern we do not modify.
- **`RETURNING id` ties seed helpers to PostgreSQL.** Portability cost — fine given
  ADR-0007.
- **No jqwik property tests yet.** ADR-0006 mentions `*Properties.java` in the surefire
  include list, but the repository currently uses `@ParameterizedTest` +
  `@MethodSource` instead (e.g. `FeeCalculatorServiceTest`). The jqwik dependency is
  available; adopting it is a follow-up.
- **`TestRestTemplate` instead of `WebTestClient`.** Sync and simple; a `WebTestClient`
  would buy reactive-friendly fluent assertions but nothing else.
- **Eureka disabled in tests.** Cleaner isolation, but means no test exercises the
  registration → load-balancer path end-to-end. That path is covered manually in
  Compose smoke runs.

---

## Alternatives Considered

1. **Container per test class** (`@Testcontainers` on every class). Rejected — 5 s ×
   N classes = unacceptable CI time.
2. **`@Sql` scripts instead of a DSL.** Works for trivial seeds; becomes unmaintainable
   when a test needs conditional data (`if shipClass == C, add bay type X`).
3. **H2 or HSQLDB in-memory.** Rejected by ADR-0007 — dialect differences hide real
   Flyway bugs.
4. **In-process Kafka embedded broker.** We use the Spring Cloud Stream test binder
   instead for unit-ish tests. Acceptance tests that exercise Kafka run against the
   Compose broker in manual smoke runs; dedicated Kafka E2E is a known gap (ADR-0006).
5. **Whole-database snapshot + rollback per test.** PostgreSQL supports savepoints;
   could be fast. Rejected because `@Transactional`-wrapped tests rollback implicitly
   but do not rollback Flyway-committed sequence values (identity generation is not
   transactional). Truncate-per-class is simpler.

---

## References

- ADR-0006 — Testing Strategy (pyramid)
- ADR-0007 — Database Choice (Postgres + Flyway)
- ADR-0011 — Architecture Guardrails (parallel exec)
- ADR-0017 — Tracing Propagation (what `TestObservationRegistry` asserts)
- ADR-0018 — Flyway + No-FK (why TRUNCATE works without CASCADE)
- ADR-0025 — Maven Build Topology (surefire / failsafe split)
- ADR-0030 — Metrics Naming & Cardinality (tag discipline asserted by observability
  tests)
- Testcontainers @ServiceConnection —
  https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.testing.testcontainers.service-connections
- JUnit 5 Parallel Execution —
  https://junit.org/junit5/docs/current/user-guide/#writing-tests-parallel-execution
- Micrometer TestObservationRegistry —
  https://docs.micrometer.io/micrometer/reference/observation/testing.html
