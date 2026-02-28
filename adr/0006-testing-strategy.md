# 0006 — Testing Strategy

**Status:** Accepted — 2026-02-28
**Deciders:** Team
**Technical Story:** Requirement — unit, integration, and architecture tests must be present from day one; quality gates must prevent architectural drift and logic regressions.

---

## Context and Problem Statement

The project must ship with high confidence that business rules are correct, layers are not violated, and the HTTP/Kafka contracts are stable. Tests must run fast during local development and provide a safety net in CI. Given the Layered architecture of Starport Registry and its Kafka + PostgreSQL dependencies, which combination of testing techniques gives the best coverage-to-feedback-speed trade-off?

---

## Decision Drivers

* Domain logic (fee calculation, validation chain, route planning) must be testable without Spring context or real infrastructure.
* HTTP API contracts must be verifiable without a running database or Kafka broker.
* PostgreSQL schema migrations (Flyway), JPA mappings, and the Outbox table must be exercised against a real engine.
* Kafka publishing and outbox polling must be verifiable end-to-end.
* Architecture layer rules must be enforced automatically to prevent drift.
* Tests must not serialise unnecessarily — parallel execution is expected.
* Mutation testing must validate that unit tests are actually detecting bugs, not just achieving line coverage.

---

## Considered Options

1. **Unit tests only** — fast, no infrastructure, but no confidence in wiring, contracts, or DB behaviour.
2. **Integration tests only (Testcontainers)** — high confidence, but slow feedback; impractical for rapid iteration on domain logic.
3. **Multi-layer test pyramid: unit + contract + integration + architecture + mutation** — chosen approach.

---

## Decision Outcome

**Chosen option: Multi-layer test pyramid with five distinct layers.**

| Layer | Scope | Tool | Naming convention | Runner |
|---|---|---|---|---|
| Unit | Pure domain logic, services, mappers | JUnit 5 + jqwik | `*Test.java`, `*Properties.java` | `maven-surefire-plugin` |
| Contract | HTTP API shape, status codes, response structure | `@WebMvcTest` + MockMvc + Mockito | `*ContractTest.java` | `maven-failsafe-plugin` |
| Repository / Integration | JPA mappings, Flyway migrations, outbox | Testcontainers (PostgreSQL) | `*RepositoryTest.java` | `maven-failsafe-plugin` |
| E2E (acceptance) | Full stack over HTTP, DB truncate-and-seed | Testcontainers (PostgreSQL) + `TestRestTemplate` | `*E2ETest.java` | `maven-failsafe-plugin` |
| Architecture | Layer dependency rules per service style | ArchUnit | (separate test class) | `maven-surefire-plugin` |

Mutation testing (PIT) runs against the unit layer with `STRONGER` mutators and an 80 % kill threshold.

### Positive Consequences

* **Fast unit feedback.** Domain logic tests run in parallel (JUnit 5 concurrent mode, 4 threads × core count) without Spring context startup — typically sub-second per class.
* **Stable API contracts.** `@WebMvcTest` slices load only the web layer; Mockito stubs the service. Contract tests catch serialisation regressions, missing fields, and wrong status codes without a database.
* **Real-engine confidence.** Testcontainers spins up `postgres:16-alpine` for `RepositoryTest` and `E2ETest` suites, ensuring Flyway migrations and JPA column mappings are valid.
* **Architecture drift prevention.** ArchUnit rules encode the allowed dependency directions for the Layered style (`controller → service → repository`); violations fail the build in CI.
* **Mutation quality gate.** PIT verifies that at least 80 % of injected mutations are killed, preventing coverage theatre (tests that run but do not assert).
* **Observability instrumented tests.** `TestObservationRegistry` asserts that expected `Observation` spans are created with correct low-cardinality tags (e.g., `starport`, `shipClass`), ensuring metrics and traces are not accidentally removed.
* **Property-based edge cases.** jqwik generates boundary inputs for fee calculation and validation rules without manual enumeration.

### Negative Consequences

* **Testcontainers startup time.** Each `RepositoryTest` / `E2ETest` class may wait 5–15 s for PostgreSQL to be ready; parallel container start is limited by Docker resources.
* **Two test runners.** `maven-surefire-plugin` (unit) and `maven-failsafe-plugin` (integration) must both be configured and invoked; `mvn verify` is the full-suite command, `mvn test` runs only unit tests.
* **Kafka E2E gap.** Full end-to-end Kafka publishing tests (outbox → broker → consumer) use Testcontainers Kafka but are currently limited to the acceptance layer; a dedicated `*KafkaIT.java` layer may be added later.
* **PIT run time.** Mutation testing is slow (minutes); it is not part of the default `mvn verify` cycle — it requires an explicit `mvn pitest:mutationCoverage` invocation.
* **Parallel isolation requirement.** Tests that share the PostgreSQL container must use `@ResourceLock("DB_TRUNCATE")` to prevent data races; missing locks cause flaky test runs.

---

## Pros and Cons of the Options

### Option 1 — Unit tests only

* Good, because extremely fast; no infrastructure required.
* Bad, because no contract verification — API changes can silently break consumers.
* Bad, because Flyway migration errors are caught only at runtime.
* Bad, because Kafka and JPA wiring is untested.

### Option 2 — Integration tests only

* Good, because high real-world confidence.
* Bad, because slow: every change triggers a Testcontainers round-trip.
* Bad, because domain logic bugs are harder to isolate in a full-stack context.
* Bad, because architectural violations are not caught systematically.

### Option 3 — Multi-layer pyramid ✅

* Good, because each layer catches a different class of defect at the appropriate speed.
* Good, because unit tests provide sub-second feedback on domain logic.
* Good, because contract tests protect the HTTP API surface without database overhead.
* Good, because ArchUnit makes architectural decisions machine-enforceable.
* Good, because mutation testing validates test quality, not just line coverage.
* Bad, because more test infrastructure to maintain.
* Bad, because developers must know which layer to use for each scenario.

---

## Implementation

### Surefire (unit, parallel)

```xml
<plugin>
  <artifactId>maven-surefire-plugin</artifactId>
  <configuration>
    <includes><include>**/*Test.java</include><include>**/*Properties.java</include></includes>
    <excludes>
      <exclude>**/*E2ETest.java</exclude>
      <exclude>**/*RepositoryTest.java</exclude>
      <exclude>**/*ContractTest.java</exclude>
    </excludes>
    <parallel>classes</parallel>
    <threadCount>4</threadCount>
    <perCoreThreadCount>true</perCoreThreadCount>
    <properties>
      <configurationParameters>
        junit.jupiter.execution.parallel.enabled=true
        junit.jupiter.execution.parallel.mode.default=concurrent
      </configurationParameters>
    </properties>
  </configuration>
</plugin>
```

### Failsafe (integration)

```xml
<plugin>
  <artifactId>maven-failsafe-plugin</artifactId>
  <configuration>
    <includes>
      <include>**/*E2ETest.java</include>
      <include>**/*RepositoryTest.java</include>
      <include>**/*ContractTest.java</include>
    </includes>
  </configuration>
</plugin>
```

### Key conventions

* `BaseAcceptanceTest` — shared `PostgreSQLContainer` (`@ServiceConnection`), `TestRestTemplate`, `JdbcTemplate` DSL for seeding and truncating data.
* Observability tests use `TestObservationRegistry` from `micrometer-observation-test`.
* `@ResourceLock("DB_TRUNCATE")` on any test that truncates shared tables.
* PIT `mutationThreshold=80` with `STRONGER` mutators; `timestampedReports=false` for stable CI diffs.

---

## References

* ADR-0001 — Architecture Styles per Service
* ADR-0011 — Architecture Rules & Guardrails (ArchUnit)
* JUnit 5 parallel execution — https://junit.org/junit5/docs/current/user-guide/#writing-tests-parallel-execution
* Testcontainers — https://testcontainers.com/
* ArchUnit — https://www.archunit.org/
* PIT Mutation Testing — https://pitest.org/
* jqwik — https://jqwik.net/
