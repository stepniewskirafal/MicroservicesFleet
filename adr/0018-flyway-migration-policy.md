# 0018 — Flyway Migration Policy & No-Foreign-Keys-by-Design

**Status:** Accepted
**Date:** 2026-04-17

---

## Context

Only `starport-registry` persists domain data (ADR-0007). We need rules for schema
evolution, for tolerating a mutable seed-data migration (`V2__test_data.sql`), and for
deciding whether referential integrity lives in the DB or in the service layer.

---

## Decision

Flyway is the single source of DDL truth. Hibernate runs with `ddl-auto: none` in
production and `validate` in tests (catches entity/column drift). Versioned migrations
only (`V<NN>__*.sql`), no `R__*`.

```yaml
spring:
  flyway:
    enabled: true
    validate-on-migrate: false   # V2/V5 seed data is allowed to drift
```

- Structural migrations (V1, V3, V4, V6) are **immutable** once merged. Any change ships
  as V<N+1>.
- Only seed migrations (V2, V5) may be edited, and changes must be additive +
  idempotent (`INSERT ... WHERE NOT EXISTS`).
- **No foreign keys.** `starport_id`, `customer_id`, etc. are plain `BIGINT` columns.
  The service layer validates existence and throws `*NotFoundException` (ADR-0015) on
  miss.
- Flyway is starport-registry-only. `trade-route-planner` and `telemetry-pipeline` have
  no DB.

---

## Why

- **No-FK lets `BaseAcceptanceTest` `TRUNCATE` any subset in any order** — FKs would
  force `CASCADE` or constraint-disable gymnastics (ADR-0006).
- **Domain boundary owns correctness** — invalid IDs surface as HTTP 404, not a DB 500.
- **Splitting a table to its own DB later is trivial** — no cross-DB constraint to
  unwind.
- **`validate-on-migrate: false` keeps older environments unblocked** when V2 grows new
  demo rows; the immutability rule for structural migrations is enforced by review.

---

## Alternatives

- **Liquibase** — no gain over Flyway for pure SQL; extra DSL to learn.
- **`ddl-auto: update`** — Hibernate-inferred DDL diverges from hand-written (indexes,
  cascades); migrations become opaque.
- **FKs enabled** — defeats `BaseAcceptanceTest` isolation; insert-order coupling
  everywhere.
- **`EXCLUDE USING gist` on reservations** — possible but requires `btree_gist` and
  range types; revisit if overlap query becomes a bottleneck.
- **`validate-on-migrate: true` with immutable V2** — would force a separate seed
  profile. Viable, not urgent.

---

## References

- ADR-0006 — Testing Strategy (Testcontainers + Flyway replay)
- ADR-0007 — Database Choice
- ADR-0010 — Outbox (V3)
- Flyway validation — https://documentation.red-gate.com/fd/validate-on-migrate-184127496.html
