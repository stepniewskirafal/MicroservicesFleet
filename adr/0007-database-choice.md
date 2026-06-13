# 0007 — Database Choice

**Status:** Accepted
**Date:** 2026-02-28

---

## Context

Starport Registry needs a relational engine that supports JSONB (for the outbox payload
and headers), full ACID (for the transactional outbox), first-class Flyway migrations
tested against the real engine, and a small Docker image for local dev and Testcontainers.

---

## Decision

Use **PostgreSQL 16** with Spring Data JPA (Hibernate) and Flyway. The `postgres:16-alpine`
image runs in Docker Compose and in `PostgreSQLContainer` for tests — identical engine
and version. Hibernate `ddl-auto: none` (Flyway owns the schema); `open-in-view: false`;
sequences use the `pooled-lo` optimiser. FKs are intentionally omitted on test-friendly
tables (see `V1` comments) to allow `INSERT` without ordering dependencies.

```yaml
spring:
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/starports}
  jpa:
    open-in-view: false
    hibernate.ddl-auto: none
    properties.hibernate.id.optimizer.pooled.preferred: pooled-lo
  flyway: { enabled: true, locations: classpath:db/migration }
```

---

## Why

- **Native JSONB** — `event_outbox.payload_json` / `headers_json` store arbitrary event
  data and Kafka headers, indexed via `ix_event_outbox_status_created`.
- **Real ACID** — outbox row and domain row commit atomically; no dual-write risk.
- **Testcontainers parity** — same image as production; no H2 dialect translation bugs.
- **Rich types** — `TIMESTAMPTZ`, `NUMERIC(14,2)`, `RETURNING id` all work natively.

---

## Alternatives

- **H2 in-memory** — no JSONB, no `TIMESTAMPTZ` / `IDENTITY` / `RETURNING` in compat mode.
- **MySQL 8** — `JSON` type lacks `@>`/`->>` operators; `RETURNING` support is patchy.
- **MongoDB** — no JPA, no easy multi-doc transactions locally, relational model fits poorly.

---

## References

- ADR-0010 — Resilience Patterns (Transactional Outbox)
- ADR-0013 — `open-in-view: false`
- ADR-0018 — Flyway Migration Policy
- PostgreSQL JSONB — https://www.postgresql.org/docs/current/datatype-json.html
- Testcontainers PostgreSQL — https://testcontainers.com/modules/postgresql/
