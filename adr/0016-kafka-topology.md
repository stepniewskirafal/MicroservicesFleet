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
`<purpose><-in|-out>-<ordinal>` (e.g. `reservationCreated-out-0`).

**Consumer retry** — every consumer declares:

```yaml
consumer:
  max-attempts: 3
  back-off-initial-interval: 1000
```

Default multiplier (2.0). After 3 attempts the message is dropped today (DLQ topic
planned — see Caveats). The transactional outbox (ADR-0010) is the producer-side DLQ
analogue: `event_outbox.status = FAILED` after `app.max-attempts: 10`, tracked by
`reservations.outbox.dead.letter`.

**Partitioning** — `starport.reservations` is 3-partitioned, keyed by `reservationId`
in the outbox publisher so all events for one reservation stay in order.
`StreamBridgeRouteEventPublisher` sets `KafkaHeaders.KEY` on every send.

**Auto-create** — `autoCreateTopics: true` everywhere. `autoAddPartitions: true` only
on consumers of externally-produced topics (telemetry-pipeline); `false` on producers
so they cannot silently grow partition counts.

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

- **No DLQ topic configured yet.** Exhausted retries are dropped (logged with trace
  ID). Follow-up: `enableDlq: true`, `dlqName: <topic>.dlq` per binding.
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
