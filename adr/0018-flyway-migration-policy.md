# 0018 — Flyway Migration Policy & No-Foreign-Keys-by-Design

**Status:** Accepted
**Date:** 2026-04-17

---

## Context

Only `starport-registry` persists domain data (ADR-0007). Schema evolution requires a
policy that covers:

1. **Migration lifecycle** — how new columns, tables, and indexes are added between releases
   without manual DBA intervention.
2. **Checksum tolerance** — `V2__test_data.sql` is intentionally mutable (new seed rows are
   added for local demos), and earlier releases produced a different checksum for V2. Later
   migrations (V5+) must still run against existing databases where V2's checksum has drifted.
3. **Referential integrity** — whether the database enforces FKs or the application layer
   owns referential correctness.

The choice is not neutral: FKs buy safety at the cost of insert-order coupling across tests,
migrations, and data-seeding scripts. The team has chosen to trade FKs for ease of test
data management (see "No-FK by design" below).

---

## Decision

### 1. Flyway is the single source of DDL truth

- `spring.jpa.hibernate.ddl-auto: none` in production config. Hibernate never generates,
  updates, or validates the schema against entities at runtime — only Flyway does.
- **Test profile** uses `ddl-auto: validate` — Hibernate checks that entity mappings match
  the Flyway-applied schema. This catches entity/column drift in the integration test
  suite (ADR-0006).
- No `R__*` repeatable migrations. Every change is versioned.

### 2. Migration naming

Versioned migrations use the Spring / Flyway default: `V<NN>__<snake_case_description>.sql`.
Current migrations in `starport-registry/src/main/resources/db/migration/`:

| Version | Filename                         | Purpose                                        |
|---|---|---|
| V1  | `V1__starport_basic_model.sql`       | Core tables: starport, docking_bay, customer, ship, reservation, route |
| V2  | `V2__test_data.sql`                  | Seed data: 2 starports, 1 customer, 1 ship, 1 bay |
| V3  | `V3__create_event_outbox.sql`        | Outbox table + status index (ADR-0010)         |
| V4  | `V4__reservation_indexes.sql`        | Performance indexes for bay-time and bay-class lookups |
| V5  | `V5__expand_test_data.sql`           | Additional seed data for load tests (uses `INSERT … WHERE NOT EXISTS`) |
| V6  | `V6__lookup_indexes.sql`             | Indexes on `starport.code`, `customer.customer_code`, `ship.ship_code` |

### 3. `validate-on-migrate: false`

```yaml
# starport-registry/src/main/resources/application.yml:90-94
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
    validate-on-migrate: false
```

This is the **deliberate** choice. Flyway normally refuses to run newer migrations if the
checksum of an applied older migration has changed. The seed-data migration V2 is allowed
to change in minor ways across releases (adding demo rows for a new feature), so turning
validation off keeps environments with an older V2 unblocked.

**Constraints imposed by this policy** (enforced by code review, not tooling):

- Only `V2__test_data.sql` and `V5__expand_test_data.sql` (seed-data migrations) may have
  their content changed after release. Their changes must be **additive and idempotent**
  (`INSERT ... WHERE NOT EXISTS`).
- **Structural migrations (V1, V3, V4, V6) are immutable once merged.** Any structural
  change ships as a new V<N+1>.
- This is documented in PR templates; there is no automated guard today.

### 4. No foreign keys by design

```sql
-- V1__starport_basic_model.sql:26
starport_id  BIGINT,  -- references starport.id (no FK by design)

-- V1__starport_basic_model.sql:56-59
starport_id    BIGINT,  -- references starport.id     (no FK)
docking_bay_id BIGINT,  -- references docking_bay.id  (no FK)
customer_id    BIGINT,  -- references customer.id     (no FK)
ship_id        BIGINT,  -- references ship.id         (no FK)
```

Rationale:

- **Test data setup becomes insert-order independent.** `BaseAcceptanceTest`
  (ADR-0006) can `TRUNCATE` any subset of tables and re-seed in any order — impossible
  with FKs without disabling constraints or cascading deletes.
- **The domain boundary owns correctness.** `ReservationService.createHoldReservation()`
  resolves `starportId`, `customerId`, etc., before insert; an invalid ID produces a
  `*NotFoundException` (ADR-0015) with a 404 response rather than a DB-level 500.
- **Futureproof for evolution.** If two tables are ever split into separate databases (per
  microservice-per-DB), the absence of FKs means no cross-DB constraint to untangle.

The trade-off is real: orphan rows are **technically possible**. Mitigation is that every
write path goes through a `@Transactional` service method that validates existence, and
ArchUnit (ADR-0011, planned) prevents controllers from calling repositories directly —
`INSERT` statements outside the service layer simply cannot happen.

### 5. Flyway is a starport-registry-only concern

- `trade-route-planner` has no database; its domain state is purely in-memory /
  configuration-driven. No `db/migration` directory, no Flyway config.
- `telemetry-pipeline` is stream-processing; state lives in Kafka topics (ADR-0016). No
  database, no Flyway.

This respects the "database-per-service" principle without dogma: services that don't need
a database don't get one.

---

## How the codebase enforces this

1. **Production runtime:** `application.yml:35` — `hibernate.ddl-auto: none #validate` —
   Hibernate is a client of the Flyway-managed schema, never an owner.
2. **Test runtime:** `application-test.yml:3` — `hibernate.ddl-auto: validate` — catches
   entity/column mismatch before merge.
3. **CI integration:** `@DataJpaTest` and `@SpringBootTest` both start `PostgreSQLContainer`
   (ADR-0007), which triggers Flyway at context startup. Any migration syntax error or
   checksum problem fails the test suite immediately.
4. **Seed-data idempotency:** V5 uses `INSERT ... SELECT ... WHERE NOT EXISTS` guards so
   re-applying it against a partially-seeded database does not fail on primary-key
   conflicts.

---

## Consequences

### Benefits

- **Every environment runs the same DDL.** Local, CI Testcontainers, and production all
  replay the same versioned migrations.
- **Migrations are reviewable.** A PR touching `db/migration/V<N>__*.sql` is a red flag in
  code review and signals a schema change that needs thought.
- **Tests can seed freely.** The no-FK design means `TRUNCATE … CASCADE` is never needed,
  and `INSERT` order is never a test flakiness source.
- **Fast CI.** Testcontainers + Flyway migrations complete in <5 s for a fresh schema.

### Trade-offs

- **Orphan-row risk is real.** A bug that deletes a `customer` but leaves orphan
  `reservations` is not caught by the DB — only by the service layer's pre-checks. Unit
  and integration tests cover this.
- **`validate-on-migrate: false` hides real corruption.** A structural migration
  accidentally mutated after release would silently apply on some environments and not
  others. Mitigation: code review rule (only V2, V5 are mutable); future
  mitigation: a pre-commit check that hashes structural migrations.
- **Schema changes require a redeploy.** Flyway runs at application startup. Rolling
  deploys need a migration that is backwards-compatible with the previous version's code
  — no `DROP COLUMN` in a single release.
- **No FK means bad data can exist.** If external SQL (a DBA patch, a data-fix script)
  inserts invalid foreign-key-like values, the application will fail on first read with a
  cryptic error. Less of an issue in a dev/demo environment; a real production system
  would revisit this choice.
- **Seed data coupled to migrations.** `V2` and `V5` mix "demo data" into the migration
  lifecycle. Cleaner would be a separate seed profile, but in a demo project that would
  be premature ceremony.

---

## Alternatives Considered

1. **Liquibase.** Rejected — no gain over Flyway for pure SQL migrations; one more DSL to
   learn (YAML/XML change sets). Flyway is simpler when team fluency is already SQL.
2. **`ddl-auto: update`.** Rejected — Hibernate's inferred DDL is not identical to hand-
   written DDL (index naming, column order, cascade defaults); migrations become opaque.
3. **FKs enabled.** Rejected — tests would need `TRUNCATE … CASCADE` and `DISABLE FOREIGN
   KEY CHECKS` in seed steps, which defeats the test-isolation goals of
   `BaseAcceptanceTest`.
4. **Separate seed-data tool (e.g., Jeddict, dbunit).** Rejected — another tool to run in
   CI and onboard; the idempotent `INSERT WHERE NOT EXISTS` pattern is sufficient.
5. **`validate-on-migrate: true` with immutable `V2`.** Considered — would require moving
   demo data out of the versioned stream into a profile-gated seeder. Viable, not urgent.

---

## References

- ADR-0006 — Testing Strategy (Testcontainers + Flyway replay)
- ADR-0007 — Database Choice (PostgreSQL + Flyway + `ddl-auto: none`)
- ADR-0010 — Resilience Patterns (V3 outbox table)
- Flyway Callbacks & Validation —
  https://documentation.red-gate.com/fd/validate-on-migrate-184127496.html
