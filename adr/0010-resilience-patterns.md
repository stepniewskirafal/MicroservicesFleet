# 0010 — Resilience Patterns

**Status:** Accepted
**Date:** 2026-02-28

---

## Context

Starport Registry publishes a business-critical event (`ReservationConfirmed` →
`starport.reservations`) to Kafka and calls Trade Route Planner over HTTP during the
reservation flow. A lost event breaks downstream telemetry; a Kafka outage must not fail
reservations; a Trade Route Planner outage must not cascade.

---

## Decision

Use the **Transactional Outbox Pattern with a polling relay** for events. The
`event_outbox` row is written in the same PostgreSQL transaction as the domain state
(via `OutboxWriter` with `Propagation.MANDATORY`). A `@Scheduled` poller reads
`PENDING` rows, publishes via Spring Cloud Stream `StreamBridge`, and transitions
status to `SENT` / `FAILED`. Trace context is propagated across the append→publish gap
via Micrometer `SenderContext`/`ReceiverContext` (→ ADR-0017).

```yaml
app:
  poll-interval-ms: 5000   # delay between cycles
  batch-size: 200          # rows per cycle
  max-attempts: 10         # FAILED after this many retries
```

The HTTP call to Trade Route Planner is live and protected by a timeout + circuit
breaker + fail-fast fallback (→ ADR-0014); failure surfaces as HTTP 409 (→ ADR-0015).

**Known gap (multi-instance):** the poller does not yet use row-level locking; running
two service replicas can double-publish. Fix is `SELECT … FOR UPDATE SKIP LOCKED` on the
polling query.

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
