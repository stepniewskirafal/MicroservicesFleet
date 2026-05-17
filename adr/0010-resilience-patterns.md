# 0010 — Resilience Patterns

**Status:** Accepted
**Date:** 2026-02-28

---

## Context

Starport Registry publishes business-critical events (`ReservationConfirmed`,
`TariffCalculated`, `RouteChanged`) to Kafka and may call Trade Route Planner over HTTP.
A lost event breaks downstream billing/telemetry; a Kafka outage must not fail
reservations; a Trade Route Planner outage must not cascade.

---

## Decision

Use the **Transactional Outbox Pattern with a polling relay** for events. The
`event_outbox` row is written in the same PostgreSQL transaction as the domain state
(via `OutboxWriter` with `Propagation.MANDATORY`). A `@Scheduled` poller reads
`PENDING` rows, publishes via Spring Cloud Stream `StreamBridge`, and transitions
status to `SENT` / `FAILED`. OTel trace context is injected into `headers_json` at
append time so consumers continue the trace.

```yaml
app:
  poll-interval-ms: 30000   # delay between cycles
  batch-size: 50            # rows per cycle
  max-attempts: 10          # FAILED after this many retries
```

For the HTTP call to Trade Route Planner: `requestRoute=false` short-circuits entirely.
When a real HTTP call replaces the in-process simulator, wrap it with 2s connect / 5s
read timeouts, 1 retry, and a fallback to `Optional.empty()` → controller responds 409.

**Known gap (multi-instance):** the current poller is single-threaded
(`spring.task.scheduling.pool.size: 1`); running two service replicas can double-publish.
Fix is `SELECT … FOR UPDATE SKIP LOCKED` on the polling query.

---

## Why

- **At-least-once delivery.** DB commit guarantees eventual Kafka publish, even if
  the broker was down at commit time.
- **No dual-write.** Outbox row and domain row share one ACID transaction.
- **Observable.** `status` and `attempts` columns make dead events queryable.
- **No new infrastructure.** Reuses existing PostgreSQL — no Debezium, no Kafka Streams.

---

## Alternatives

- **Direct Kafka publish in domain transaction** — classic dual-write; Kafka becomes
  the critical path of every reservation.
- **Kafka transactions (exactly-once)** — does not solve DB↔Kafka dual-write without
  a saga; high operational complexity.
- **CDC with Debezium** — lower latency but requires Kafka Connect, logical replication,
  and a transformation layer for semantic events. Overkill for three services.

---

## References

- ADR-0004 — When to Use Messaging vs HTTP
- ADR-0007 — Database Choice
- Transactional Outbox — https://microservices.io/patterns/data/transactional-outbox.html
