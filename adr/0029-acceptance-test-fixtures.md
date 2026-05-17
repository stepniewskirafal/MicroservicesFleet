# 0029 — Acceptance Test Fixtures: Shared Base, Truncate-per-Test, Parallel Isolation

**Status:** Accepted
**Date:** 2026-04-17

---

## Context

ADR-0006 defines the test pyramid and ADR-0025 splits surefire / failsafe, but neither
answers how acceptance tests get a deterministic starting state without paying the
Testcontainers startup tax per class, how they run in parallel without stomping on each
other, or how observability is asserted without a real Tempo. Without conventions these
fragment into per-class Postgres containers, H2 shortcuts, and flaky truncation races.

---

## Decision

One `BaseAcceptanceTest` per service owns a **static** `PostgreSQLContainer` (one DB per
JVM, not per class), exposes a tight seed DSL, and tests truncate-then-seed in
`@BeforeEach`.

```java
// starport-registry/src/test/java/.../BaseAcceptanceTest.java
@SpringBootTest(webEnvironment = RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
public abstract class BaseAcceptanceTest {
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired protected TestRestTemplate rest;
    @Autowired protected JdbcTemplate jdbc;

    protected void truncateDomainTables() {
        jdbc.execute("TRUNCATE TABLE route, reservation, docking_bay, starport, "
                   + "ship, customer RESTART IDENTITY CASCADE");
    }
    // + seedDefaultReservationFixture(...), postReservation(...), reservationsCount(...)
}
```

Parallel isolation uses JUnit 5 `@ResourceLock`:

```java
@ResourceLock(value = "DB_TRUNCATE", mode = READ_WRITE)   // exclusive
class ReservationLifecycleE2ETest extends BaseAcceptanceTest { ... }

@ResourceLock(value = "DB_TRUNCATE", mode = READ)         // shared
class ReadOnlyDashboardTest extends BaseAcceptanceTest { ... }
```

Observability is asserted with Micrometer's `TestObservationRegistry` — in-memory, no
exporter, no network:

```java
TestObservationRegistryAssert.assertThat(registry)
    .hasObservationWithNameEqualTo("reservations.fees.calculate")
    .hasLowCardinalityKeyValue("shipClass", "C");
```

---

## Why

- **Startup cost paid once.** ~5 s Postgres boot amortised across the whole test JVM; a
  30-class suite runs in ~90 s instead of ~300 s.
- **`@ServiceConnection`** removes the old `DynamicPropertySource` boilerplate — Spring
  derives URL / user / pass from the container.
- **`@ResourceLock` is a declarative contract.** Failure mode is "serialised", not
  "data corruption" — no flakes under parallel load.
- **Observability regressions caught at test time.** Renaming a span or flipping a tag
  to high-cardinality (ADR-0030) breaks a test, not a dashboard at 3 AM.
- **Real Postgres + real Flyway** (ADR-0007, ADR-0018) means repository tests hit the
  same engine and migrations as production.

---

## Alternatives

- **Container per test class** — 5 s × N classes makes CI unbearable.
- **`@Sql` scripts** — fine for trivial seeds, unmaintainable for conditional fixtures.
- **H2 / HSQLDB in-memory** — rejected by ADR-0007; dialect drift hides real Flyway bugs.
- **Savepoint rollback per test** — Postgres identity sequences are not transactional,
  so generated IDs leak across tests; truncate-per-class is simpler.

---

## References

- ADR-0006 — Testing Strategy (pyramid)
- ADR-0007 — Database Choice (Postgres + Flyway)
- ADR-0018 — Flyway + No-FK (why `TRUNCATE` works)
- ADR-0025 — Maven Build Topology (surefire / failsafe)
- ADR-0030 — Metrics Naming & Cardinality (what observability tests assert)
- Testcontainers `@ServiceConnection` — https://docs.spring.io/spring-boot/reference/testing/testcontainers.html#testing.testcontainers.service-connections
- Micrometer `TestObservationRegistry` — https://docs.micrometer.io/micrometer/reference/observation/testing.html
