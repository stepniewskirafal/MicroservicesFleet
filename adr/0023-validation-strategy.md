# 0023 — Validation: Jakarta Bean Validation + Chain-of-Responsibility Rules

**Status:** Accepted
**Date:** 2026-04-17

---

## Context

Reservation input has two kinds of checks that keep getting confused: **request syntax**
(JSON shape, required fields, formats — answerable without the DB) and **business rules**
(does the starport exist, is the time window valid, is the ship compatible — needs the DB
and domain knowledge). Mixing them produces controllers that call repositories and
services that re-check `@NotBlank`.

---

## Decision

Two layers, two technologies, no overlap:

| Layer            | Tool                                       | Rejects                                | HTTP            |
|---|---|---|---|
| Request syntax   | Jakarta Bean Validation (`@Valid` on records) | Missing / malformed / blank fields  | 400 / 422       |
| Business rules   | `ReserveBayCommandValidationRule` chain    | Unknown starport, bad window, etc.     | 404 / 409 / 422 |

Each rule is a `@Component` with `@Order(N)`; `ReserveBayValidationService` autowires
`List<…Rule>` (Spring sorts by `@Order`) and runs them sequentially, short-circuiting on
the first throw. Rules throw **typed domain exceptions**
(`StarportNotFoundException`, `InvalidReservationTimeException`, …) that
`GlobalExceptionHandler` (ADR-0015) maps to status codes.

Order is **cheapest-first**: pure-command checks (`@Order(0)`) before DB lookups
(`@Order(10+)`), so obviously bad requests never hit the database.

Domain constructors do **not** validate — `Reservation`/`Ship` trust their callers. The
boundary chain + integration tests are the gate. Pragmatic concession to JPA's no-arg
constructor requirement.

---

## Why

- **Single responsibility per check.** Reviewers know whether a new check belongs on the
  DTO or in the chain without debate.
- **Fast-fail before I/O.** `@Valid` rejects malformed JSON before any rule runs; cheap
  rules reject bad commands before any DB round-trip.
- **Domain-meaningful errors.** Business failures surface as typed exceptions →
  specific error codes, not generic 400s.
- **Trivially extensible.** New rule = new `@Component` + new `@Order`. No service edit.

---

## Alternatives

- **Single big validator method** — entangles fast checks with DB I/O, breaks ADR-0015's
  typed-error model.
- **Custom `@Constraint` for everything (e.g. `@ValidStarportCode`)** — Jakarta
  discourages constraints that need DB lookups; ties validation to unpredictable I/O.
- **Hibernate validator group sequences** — viable but less explicit than a rule chain
  and requires group-aware controllers.
- **Validation in domain constructors** — JPA's no-arg constructor + Lombok `@Builder`
  fight this; chain would still be needed for DB-dependent rules.
- **Spring `Validator` SPI** — returns `BindingResult`; our error model wants thrown
  domain exceptions.

---

## References

- ADR-0006 — Testing Strategy (contract tests pin error shapes)
- ADR-0011 — Architecture Rules (ArchUnit forbids repository calls from controllers)
- ADR-0015 — API Error Response Model
- ADR-0020 — Concurrent Reservation Safety (validation runs before the pessimistic lock)
