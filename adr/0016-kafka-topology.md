# 0016 — Kafka Topic Topology, Consumer Retry, and DLQ Strategy

**Status:** Accepted
**Date:** 2026-04-17

---

## Context

The fleet publishes and consumes domain events across three services (ADR-0004). As topics,
consumer groups, and retry policies accumulate, ad-hoc decisions create long-tail problems:

- A topic named `reservations` clashes with an unrelated `reservations` topic another team
  may create later.
- A consumer without `max-attempts` will retry forever on a poison message, blocking the
  partition.
- A consumer without a DLQ (dead-letter queue) discards messages that exhaust retries,
  making post-mortem forensics impossible.
- Topics auto-created with broker defaults may start with a single partition, silently
  capping throughput even when the producer publishes with a partition key.

This ADR documents the naming convention, per-binding configuration, and retry/DLQ posture
that is in force across the three services.

---

## Decision

### Naming convention

Topics use **two top-level namespaces** based on the *producer* service:

| Prefix        | Owner              | Semantics                                                 |
|---|---|---|
| `starport.*`  | starport-registry, trade-route-planner | Facts about starports, reservations, tariffs, routes |
| `telemetry.*` | telemetry-pipeline | Derived / enriched events and alerts                      |

Topics within a namespace use **dotted kebab-case** after the namespace: `starport.reservations`,
`starport.route-planned`, `telemetry.enriched-reservations`, `telemetry.alerts`.

The prefix encodes ownership, not the nature of the data. A future service reading
`starport.reservations` does not own the topic — it is a guest of the registry.

### Binding naming (Spring Cloud Stream)

Bindings use `<purpose><-in|-out>-<ordinal>` — e.g. `reservationCreated-out-0`,
`routePipeline-in-0`. The ordinal (`-0`) is the Spring Cloud Stream convention for the first
destination of that function.

### Consumer retry contract

Every consumer binding declares:

```yaml
consumer:
  max-attempts: 3
  back-off-initial-interval: 1000
```

After 3 attempts a message is dropped (today) or routed to a DLQ topic
`<source-topic>.dlq` (planned — see Consequences). Exponential back-off (multiplier) is
intentionally left at default (`2.0`) to match the Spring default; adjusting it requires a
follow-up ADR because it affects aggregate consumer latency under partial broker outage.

### Topic auto-creation policy

- `autoCreateTopics: true` everywhere — we let the broker create topics on first publish so
  that Compose-based local runs do not require pre-provisioning.
- `autoAddPartitions` — **`true`** in consumers of externally-produced topics
  (telemetry-pipeline), **`false`** in producers (starport-registry, trade-route-planner).
  The consumer adjusts partition count on its side if the producer requests more partitions;
  the producer does not silently grow partitions after the topic exists.
- **Replication factor** — not set in any `application.yml`. Broker default applies
  (1 in local Compose; must be ≥3 in production — captured as a production-readiness TODO).

---

## How the codebase enforces this

### 1. Topic inventory

**starport-registry** (`application.yml:62-77`):

```yaml
spring:
  cloud:
    stream:
      bindings:
        reservationCreated-out-0: { destination: starport.reservations,  producer: { partitionCount: 3 } }
        tariffCalculated-out-0:   { destination: starport.tariffs }
        routeChanged-out-0:       { destination: starport.route-changes }
      kafka:
        binder:
          brokers: ${KAFKA_BROKERS:localhost:9092}
          autoCreateTopics: true
          autoAddPartitions: false
```

**trade-route-planner** (`application.yml:18-27`):

```yaml
spring:
  cloud:
    stream:
      bindings:
        routePlanned-out-0: { destination: starport.route-planned }
      kafka:
        binder: { autoCreateTopics: true, autoAddPartitions: false }
```

**telemetry-pipeline** (`application.yml:22-63`):

```yaml
spring:
  cloud:
    stream:
      bindings:
        telemetryPipeline-in-0:  { destination: telemetry.raw,            group: telemetry-pipeline, consumer: { max-attempts: 3, back-off-initial-interval: 1000 } }
        telemetryPipeline-out-0: { destination: telemetry.alerts }
        reservationPipeline-in-0:  { destination: starport.reservations,  group: telemetry-pipeline, consumer: { max-attempts: 3, back-off-initial-interval: 1000 } }
        reservationPipeline-out-0: { destination: telemetry.enriched-reservations }
        routePipeline-in-0:  { destination: starport.route-planned,       group: telemetry-pipeline, consumer: { max-attempts: 3, back-off-initial-interval: 1000 } }
        routePipeline-out-0: { destination: telemetry.enriched-routes }
      kafka:
        binder: { autoCreateTopics: true, autoAddPartitions: true }
```

### 2. Partition keys

Producers use Kafka's `messageKey` header to place related events on the same partition:

```java
// trade-route-planner/.../adapter/out/kafka/StreamBridgeRouteEventPublisher.java:26
Message<RoutePlannedEvent> message = MessageBuilder.withPayload(event)
        .setHeader(KafkaHeaders.KEY, event.routeId())
        .build();
streamBridge.send("routePlanned-out-0", message);
```

`starport.reservations` is partitioned to 3 (`partitionCount: 3`) and keyed by `reservationId`
inside the outbox publisher, guaranteeing that all events for one reservation are processed
in order by the consumer.

### 3. Consumer group cohabitation

All three telemetry-pipeline bindings share the same consumer group (`telemetry-pipeline`).
This is **intentional** — running multiple telemetry-pipeline instances (ADR-0008) causes
Kafka to rebalance partitions across instances, and keeping one group means a partition is
consumed by exactly one instance. Bindings are different destinations, so they do not
compete for the same partitions.

### 4. Outbox DLQ analogue (starport-registry)

For the producer side, the transactional outbox (ADR-0010) provides a database-native DLQ:
events that exceed `app.max-attempts: 10` are marked `FAILED` in `event_outbox.status` and
tracked by the Micrometer counter `reservations.outbox.dead.letter` (see
`InboxPublisher.java:144`). Operators can query failed rows and reprocess them with a manual
script.

### 5. No `@EnableBinding`

A `grep` across the codebase finds no legacy `@EnableBinding` usage. All bindings are
declared either via the functional model (`@Bean Function<>` in telemetry-pipeline) or via
imperative `StreamBridge.send()` (starport-registry, trade-route-planner). See ADR-0019 for
the producer/consumer model split.

---

## Consequences

### Benefits

- **Discoverable topology.** A new engineer can grep `spring.cloud.stream.bindings` in three
  files and see the complete topic map.
- **Bounded retry.** A poison message occupies a partition for at most ~3 s (1 000 ms + 2 000 ms
  + 4 000 ms with default multiplier = 7 s in the worst case) before being skipped. It does
  not stop the consumer forever.
- **Scalable by default.** `starport.reservations` is 3-partitioned from day one; scaling
  the consumer group to 3 instances distributes load immediately.
- **Local dev friendly.** `autoCreateTopics: true` means `docker compose up` works without
  a manual `kafka-topics.sh --create` step.

### Trade-offs and known gaps

- **No DLQ topic configured yet.** Messages that exhaust `max-attempts` are **discarded**
  silently by the framework. Mitigation today: structured logs include the failure and trace
  ID. Mitigation tomorrow: enable `enableDlq: true` and `dlqName: <topic>.dlq` per binding —
  tracked as a follow-up, not yet in `application.yml`. The outbox (ADR-0010) already
  provides a DLQ equivalent for the producer path.
- **Broker default replication.** A production deployment must pin replication factor ≥3
  per topic (e.g., via `kafka.binder.requiredAcks: all` and `min.insync.replicas: 2` on the
  broker). Local Compose runs a single broker, so this is deferred.
- **Back-off not exponential in practice.** Spring Cloud Stream defaults to multiplier `2.0`,
  but this is not documented in `application.yml`; someone reading only the YAML may assume
  linear back-off. Future: make the multiplier explicit.
- **Implicit topic ownership.** The `starport.*` prefix documents ownership but is not
  enforced. A rogue service could publish to `starport.reservations`. Kafka ACLs would
  enforce this; not configured in the demo setup.

---

## Alternatives Considered

1. **One topic per service (`a-events`, `b-events`, `c-events`).** Rejected — couples all
   event types to a single topic and partition plan; any consumer interested in one event
   type must filter through all others.
2. **Topic per event (`reservation.created.v1`, `reservation.cancelled.v1`).** Rejected at
   this size — three event types per service would grow to dozens quickly. The current
   grouping by aggregate (`starport.reservations` carries both created and cancelled events)
   is a better fit until the number of event types grows.
3. **Schema Registry + Avro.** Rejected for now — JSON with a `eventType` discriminator
   field (ADR-0004) is sufficient for three services. Schema Registry is the logical next
   step when cross-team contracts proliferate.
4. **Route everything through a single "domain-events" topic.** Rejected — forces every
   consumer to subscribe to everything, explodes cardinality for the telemetry pipeline,
   and makes partition keys semantically overloaded.

---

## References

- ADR-0004 — Messaging vs HTTP (why Kafka at all)
- ADR-0008 — Deployment Topology (Kafka KRaft broker in Compose)
- ADR-0010 — Resilience Patterns (Transactional Outbox — DLQ analogue on producer side)
- ADR-0017 — Distributed Tracing Propagation (trace headers on Kafka messages)
- ADR-0019 — Kafka Producer / Consumer Programming Model (StreamBridge + functional beans)
- Spring Cloud Stream Kafka Binder —
  https://docs.spring.io/spring-cloud-stream/docs/current/reference/html/spring-cloud-stream-binder-kafka.html
