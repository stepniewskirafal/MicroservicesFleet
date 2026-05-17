# 0006 — Testing Strategy

**Status:** Accepted
**Date:** 2026-02-28

---

## Context

Starport Registry needs fast unit feedback on domain logic, real-engine confidence
for JPA/Flyway/outbox, stable HTTP contracts, and machine-enforced architecture rules.
Line coverage alone does not prove tests actually assert behaviour.

---

## Decision

Use a five-layer test pyramid, split between Surefire (fast) and Failsafe (Testcontainers):

| Layer | Tool | Naming | Runner |
|---|---|---|---|
| Unit | JUnit 5 + jqwik | `*Test.java`, `*Properties.java` | Surefire |
| Contract | `@WebMvcTest` + MockMvc | `*ContractTest.java` | Failsafe |
| Repository | Testcontainers (PostgreSQL) | `*RepositoryTest.java` | Failsafe |
| E2E | Testcontainers + `TestRestTemplate` | `*E2ETest.java` | Failsafe |
| Architecture | ArchUnit | (own class) | Surefire |

PIT mutation testing runs on the unit layer with `STRONGER` mutators and an 80% kill
threshold; invoked explicitly via `mvn pitest:mutationCoverage` (not in default `verify`).
Unit tests run in parallel (JUnit 5 concurrent mode, 4 threads per core); tests that
truncate shared tables use `@ResourceLock("DB_TRUNCATE")`. `TestObservationRegistry`
asserts expected spans and low-cardinality tags so metrics/traces can't be silently removed.

---

## Why

- **Each layer catches a different defect class at the right speed** — sub-second unit
  feedback, contract tests without DB, real-engine tests for migrations and JPA.
- **ArchUnit makes ADR-0001 layer rules machine-enforceable** — drift fails the build.
- **PIT prevents coverage theatre** — tests that run without asserting fail the gate.
- **Testcontainers = production parity** — same `postgres:16-alpine` as runtime, no H2 dialect bugs.

---

## Alternatives

- **Unit tests only** — fastest, but no contract/wiring/migration confidence.
- **Integration tests only** — high confidence, but slow feedback kills iteration.

---

## References

- ADR-0001 — Architecture Styles per Service
- ADR-0011 — Architecture Rules & Guardrails (ArchUnit)
- Testcontainers — https://testcontainers.com/
- PIT — https://pitest.org/
