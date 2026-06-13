# 0016 — Kafka Topics, Consumer Retry, and DLQ Posture

**Status:** Accepted  
**Date:** 2026-04-17

---

## Context

Three services produce and consume domain events (ADR-0004). Without conventions,
topics collide across teams, poison messages block partitions forever, exhausted
retries vanish silently, and auto-created topics start single-partitioned even when the
producer uses keys. This ADR pins the naming, retry, and partitioning rules currently
in force.

---

## Decision

**Topic naming** — two namespaces by producer ownership:

| Prefix        | Owner                                  | Examples                                     |
|---------------|----------------------------------------|----------------------------------------------|
| `starport.*`  | starport-registry, trade-route-planner | `starport.reservations`, `starport.route-planned` |
| `telemetry.*` | telemetry-pipeline                     | `telemetry.enriched-reservations`, `telemetry.alerts` |

Dotted kebab-case after the prefix. The prefix encodes ownership, not data shape.

**Binding names** — Spring Cloud Stream convention
`<purpose><-in|-out>-<ordinal>` (e.g. `reservationConfirmed-out-0` →
`starport.reservations`). The name matches the event type `ReservationConfirmed`; the
destination topic is the wire contract.

**Consumer retry** — every consumer declares:

```yaml
consumer:
  max-attempts: 3
  back-off-initial-interval: 1000
```

Default multiplier (2.0). telemetry-pipeline's three consumers set `enableDlq: true`
with `dlqName: <topic>.dlq`, so a payload that fails conversion/deserialization (before
the function runs) lands on a `.dlq` topic after the retries instead of vanishing.
Filter exceptions inside the pipeline are handled, counted and dropped by
`PipelineBuilder`. The transactional outbox (→ ADR-0010) is the producer-side DLQ
analogue: `event_outbox.status = FAILED` after `app.max-attempts: 10`.

**Partitioning** — `reservationConfirmed-out-0` declares `partitionCount: 3` with
`partitionKeyExpression: headers['kafka_messageKey']`, so all events for one reservation
stay in order. `InboxPublisher` sets `KafkaHeaders.KEY` (the reservation key) on every
outbox send.

**Auto-create** — `autoCreateTopics: true` everywhere. `autoAddPartitions: true` on
telemetry-pipeline (consumer of externally-produced topics) **and** on starport-registry,
because its `reservationConfirmed-out-0` producer needs `partitionCount: 3` but a
telemetry consumer may auto-create `starport.reservations` with 1 partition first;
without it the producer binding fails provisioning. trade-route-planner keeps `false`.

**Consumer group** — telemetry-pipeline's three bindings share group
`telemetry-pipeline`. Different destinations, same group → multiple instances rebalance
cleanly per ADR-0008.

---

## Why

- **Discoverable.** Three `application.yml` files contain the whole topic map.
- **Bounded retry.** A poison message blocks a partition for ~7 s, not forever.
- **Scalable from day one.** 3 partitions on `starport.reservations` lets 3 consumer
  instances split the load immediately.
- **Local-dev friendly.** `docker compose up` Just Works — no
  `kafka-topics.sh --create`.

---

## Alternatives

- **One topic per service** — couples all event types; consumers must filter through
  noise.
- **One topic per event** (`reservation.created.v1`, ...) — too many topics at this
  size; revisit if event types proliferate.
- **Single `domain-events` topic** — every consumer subscribes to everything;
  cardinality explodes for telemetry-pipeline.
- **Schema Registry + Avro** — overkill for three services; JSON + `eventType`
  discriminator suffices.

---

## Caveats

- **DLQ on consumers only.** telemetry-pipeline routes poison messages to `<topic>.dlq`;
  the starport-registry producer side relies on the outbox `FAILED` status instead. No
  automated DLQ replay/drain job exists yet.
- **Replication factor not pinned.** Broker default (1) is fine locally; production
  must set ≥3 with `requiredAcks: all` and `min.insync.replicas: 2`.
- **No Kafka ACLs.** `starport.*` ownership is convention, not enforcement.

---

## References

- ADR-0004 — Messaging vs HTTP
- ADR-0008 — Deployment Topology
- ADR-0010 — Resilience Patterns (transactional outbox)
- ADR-0017 — Tracing Propagation (headers on Kafka messages)
- ADR-0019 — Producer/Consumer Programming Model
- [Spring Cloud Stream Kafka Binder](https://docs.spring.io/spring-cloud-stream/docs/current/reference/html/spring-cloud-stream-binder-kafka.html)
