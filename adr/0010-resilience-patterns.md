# 0010 — Resilience Patterns

**Status:** Accepted — 2026-02-28
**Deciders:** Team
**Technical Story:** Domain events must never be lost due to Kafka broker unavailability. Reservation creation must not fail silently. The service must degrade gracefully when Trade Route Planner (B) is unreachable.

---

## Context and Problem Statement

Starport Registry publishes domain events to Kafka (`ReservationConfirmed`, `TariffCalculated`, `RouteChanged`) and calls Trade Route Planner over HTTP. Two failure modes must be addressed: (1) Kafka is temporarily unavailable when a reservation is confirmed — the event must not be silently dropped; (2) Trade Route Planner is unavailable — the reservation can still proceed without a route if the caller opted out, but the calling chain must not cascade-fail. How should the service be made resilient to these infrastructure outages?

---

## Decision Drivers

* Domain events are business-critical: a lost `ReservationConfirmed` event breaks downstream billing and telemetry.
* Kafka publishing must not participate in the reservation database transaction (dual-write problem).
* The Outbox table shares the same PostgreSQL transaction as the domain write — if the DB commit succeeds, the event is guaranteed to be eventually delivered.
* Trade Route Planner is an optional dependency: `requestRoute=false` in `ReserveBayCommand` must bypass the HTTP call entirely.
* Polling-based outbox retry must be configurable (`app.poll-interval-ms`, `app.batch-size`, `app.max-attempts`).
* All resilience mechanisms must be observable: outbox status (`PENDING` / `SENT` / `FAILED`) and attempt counts are stored in the DB and queryable.

---

## Considered Options

1. **Transactional Outbox Pattern with polling relay** — write the event to `event_outbox` in the same DB transaction as the domain state; a background scheduler polls and publishes to Kafka.
2. **Direct Kafka publish inside the domain transaction (dual-write)** — call `KafkaTemplate.send()` within the same `@Transactional` method as the DB write.
3. **Kafka Transactions (exactly-once semantics)** — use Kafka transactional producers coordinated with the DB transaction via a saga or two-phase commit.
4. **Change Data Capture (CDC) with Debezium** — read the DB transaction log; Debezium emits Kafka events from WAL entries; no application-level outbox.

---

## Decision Outcome

**Chosen option: Transactional Outbox Pattern with polling relay.**

The Outbox Pattern is the simplest approach that solves the dual-write problem without requiring Kafka transactions or CDC infrastructure. The `event_outbox` table is written in the same PostgreSQL transaction as the domain state. A `@Scheduled` poller reads `PENDING` rows and publishes via Spring Cloud Stream `StreamBridge`. Status transitions (`PENDING → SENT / FAILED`) and `attempts` are tracked in the DB.

### Positive Consequences

* **At-least-once delivery guaranteed.** If the DB transaction commits, the outbox row exists and will eventually be published even if Kafka was down at commit time.
* **No dual-write problem.** The outbox row and the domain row share the same ACID transaction — both commit or both roll back.
* **Observable.** `event_outbox.status` and `attempts` columns allow operational monitoring: dead events are visible as `FAILED` rows with `attempts >= max-attempts`.
* **Configurable retry.** `app.poll-interval-ms` (default 30 s), `app.batch-size` (50), and `app.max-attempts` (10) are externalised; no code changes needed to tune retry behaviour.
* **Partial JSONB flexibility.** `payload_json JSONB` and `headers_json JSONB` store arbitrary event payloads and Kafka headers (including OTel trace propagation headers) without a fixed schema per event type.
* **No additional infrastructure.** The outbox is a table in the existing PostgreSQL instance — no CDC agent, no Kafka Streams application, no saga orchestrator.

### Negative Consequences

* **Polling latency.** Events are not published immediately; there is up to `poll-interval-ms` delay between DB commit and Kafka delivery.
* **Polling load on the DB.** The scheduler queries `event_outbox` every `poll-interval-ms`; at high throughput this creates read load. The `ix_event_outbox_status_created` index mitigates this.
* **At-least-once, not exactly-once.** If the poller publishes to Kafka but crashes before marking the row `SENT`, the event will be re-published. Consumers must be idempotent.
* **Scheduler single-instance assumption.** The current implementation uses a single `@Scheduled` thread (`spring.task.scheduling.pool.size: 1`). Running two instances of the service could result in duplicate publishes of the same outbox row (both pick the same `PENDING` row). A distributed lock or row-level `SELECT … FOR UPDATE SKIP LOCKED` is needed for true multi-instance safety.
* **FAILED rows require ops intervention.** Events that exhaust `max-attempts` are stuck as `FAILED`; an alerting rule and a manual reprocessing runbook are required.

### Resilience for the HTTP call to Trade Route Planner

* `requestRoute=false` short-circuits the HTTP call entirely — no resilience needed for that path.
* `requestRoute=true`: the current implementation calls the in-process `RoutePlannerService` (which simulates the route locally). When a real HTTP call to Service B is introduced, it must be wrapped with:
  * **Connect timeout:** 2 s
  * **Read timeout:** 5 s
  * **Retry:** 1 retry on connection failure (idempotent GET/POST route plan)
  * **Fallback:** return `Optional.empty()` (no route) and let the controller respond `409 Conflict` rather than propagating a 5xx.

---

## Pros and Cons of the Options

### Option 1 — Transactional Outbox + polling relay ✅

* Good, because at-least-once delivery without dual-write risk.
* Good, because observable (status + attempts columns).
* Good, because no additional infrastructure beyond existing PostgreSQL.
* Good, because configurable retry without code changes.
* Bad, because polling latency; not suitable if sub-second event delivery is required.
* Bad, because duplicate publish risk in multi-instance deployment without `SKIP LOCKED`.

### Option 2 — Direct Kafka publish in domain transaction (dual-write)

* Good, because simple; no extra table or scheduler.
* Bad, because dual-write problem: if Kafka publish succeeds but DB rolls back (or vice versa), state and events diverge.
* Bad, because Kafka is now in the critical path of every reservation — an outage fails all reservations.
* Bad, because no retry or delivery guarantee without additional tooling.

### Option 3 — Kafka Transactions (exactly-once)

* Good, because exactly-once semantics remove the duplicate-publish concern.
* Bad, because requires Kafka transactional producer + consumer group configuration.
* Bad, because does not solve the DB ↔ Kafka dual-write problem without a saga or XA transactions.
* Bad, because significantly higher operational complexity and debugging surface.

### Option 4 — CDC with Debezium

* Good, because zero application code for event publishing; events are derived from WAL.
* Good, because very low latency (WAL streaming is near real-time).
* Bad, because adds Debezium connector infrastructure (Kafka Connect cluster or embedded).
* Bad, because requires PostgreSQL logical replication (`wal_level=logical`); additional DB setup.
* Bad, because event payload is a raw DB row diff — semantic event contracts require a separate transformation layer.
* Bad, because overkill for a three-service demo project.

---

## Implementation

### Outbox table (V3__create_event_outbox.sql)

```sql
CREATE TABLE event_outbox (
    id            BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    event_type    VARCHAR(100)  NOT NULL,
    binding       VARCHAR(200)  NOT NULL,
    message_key   VARCHAR(200),
    payload_json  JSONB,
    headers_json  JSONB,
    status        VARCHAR(16)   NOT NULL CHECK (status IN ('PENDING','SENT','FAILED')),
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    sent_at       TIMESTAMPTZ,
    attempts      INTEGER       NOT NULL DEFAULT 0
);
CREATE INDEX ix_event_outbox_status_created ON event_outbox (status, created_at);
```

### Outbox write (in-transaction)

```java
// OutboxWriter — PROPAGATION.MANDATORY ensures this runs inside the caller's transaction
@Transactional(propagation = Propagation.MANDATORY)
public void save(String binding, String eventType, String messageKey,
                 Map<String, Object> payload, Map<String, Object> headers) {
    outboxEventRepositoryFacade.saveEvent(binding, eventType, messageKey, payload, headers);
}
```

### Polling relay (application.yml)

```yaml
app:
  poll-interval-ms: 30000   # ms between polling cycles
  batch-size: 50            # rows per cycle
  max-attempts: 10          # FAILED after this many retries
```

### OTel trace propagation

Trace context (`traceparent`, `tracestate`) is injected into `headers_json` at outbox append time via `Propagator` and `Tracer`, so consumers can continue the trace across the async boundary.

### Future: multi-instance safety

Replace the current `SELECT … WHERE status='PENDING' … LIMIT batch-size` query with:
```sql
SELECT … FOR UPDATE SKIP LOCKED
```
to prevent two service instances from processing the same outbox rows concurrently.

---

## References

* ADR-0004 — When to Use Messaging vs HTTP
* ADR-0007 — Database Choice (PostgreSQL + Flyway)
* Transactional Outbox Pattern — https://microservices.io/patterns/data/transactional-outbox.html
* Enterprise Integration Patterns — Gregor Hohpe & Bobby Woolf
* Debezium CDC — https://debezium.io/
