# 0019 — Kafka Programming Model: StreamBridge for Producers, Functional Beans for Consumers

**Status:** Accepted
**Date:** 2026-04-17

---

## Context

Spring Cloud Stream offers three ways to publish to Kafka and two ways to consume. Choosing
different mechanisms in different services creates learning-curve cost with little upside,
and mixing them within a single service confuses the binding configuration. This ADR pins
down the chosen model for the fleet.

The three producer styles on offer:

1. **`@Bean Supplier<>`** — a Spring Cloud Stream function that the binder polls and
   publishes on a fixed schedule.
2. **`StreamBridge.send(binding, payload)`** — an imperative helper that publishes
   on-demand to a named binding.
3. **Raw `KafkaTemplate.send(topic, payload)`** — bypasses Spring Cloud Stream entirely.

The two consumer styles:

1. **`@Bean Function<IN, OUT>` / `Consumer<IN>`** — the binder invokes the function on each
   inbound message. Return value (if any) is published on the corresponding `-out-0`
   binding.
2. **`@KafkaListener`-annotated methods** — direct Spring Kafka consumers, sidestepping
   Spring Cloud Stream.

Each has a sweet spot; picking the wrong one here leads to: hidden threads publishing at
unpredictable times (Supplier), business code littered with binder-specific types
(KafkaTemplate), or a per-method wiring table that drifts out of sync with
`spring.cloud.stream.bindings` (KafkaListener).

---

## Decision

### Producers: `StreamBridge` — always

- **Why StreamBridge, not `@Bean Supplier<>`:** domain events are published in response to
  a request/transaction, not on a schedule. A `Supplier<>` is driven by a poller; it fits
  sources like "emit a heartbeat every 10 s" but forces awkward queue-and-drain bridging
  for transactional events.
- **Why StreamBridge, not raw `KafkaTemplate`:** StreamBridge respects the
  `spring.cloud.stream.bindings` map, so the topic destination for each binding is owned by
  one configuration block. Moving a producer from one topic to another is a YAML edit.
- **Why not `@EnableBinding`:** deprecated since Spring Cloud Stream 3.x. A grep confirms
  no legacy annotation-based binding anywhere in the codebase.

### Consumers: functional `@Bean Function<>` — always

- **Why `Function<IN, OUT>`:** each pipeline stage is a pure function. Returning a non-null
  payload publishes to the `-out-0` binding; returning `null` filters the message out. This
  is a natural fit for the Pipes & Filters architecture in `telemetry-pipeline`
  (ADR-0001).
- **Why not `@KafkaListener`:** would bypass the `spring.cloud.function.definition`
  declarative registration and split routing across annotations and YAML. Harder to audit
  which methods are wired to which topics.

---

## How the codebase enforces this

### 1. starport-registry — StreamBridge for outbox flush

```java
// starport-registry/.../service/outbox/InboxPublisher.java:105
streamBridge.send(outboxEvent.getBinding(), msg);
```

The binding name is **data**, not code — it is stored on each outbox row
(`event_outbox.binding` column, ADR-0010). A single publish path serves every event type;
adding a new event type only requires adding a new binding in
`application.yml` and writing to the outbox with that binding name. No new `StreamBridge`
call site.

Three bindings serve the event stream today:

| Binding                    | Destination topic         |
|---|---|
| `reservationCreated-out-0` | `starport.reservations`   |
| `tariffCalculated-out-0`   | `starport.tariffs`        |
| `routeChanged-out-0`       | `starport.route-changes`  |

### 2. trade-route-planner — StreamBridge directly from the adapter

```java
// trade-route-planner/.../adapter/out/kafka/StreamBridgeRouteEventPublisher.java:20-36
public void publish(RoutePlannedEvent event) {
    Message<RoutePlannedEvent> message = MessageBuilder.withPayload(event)
            .setHeader(KafkaHeaders.KEY, event.routeId())
            .build();
    streamBridge.send("routePlanned-out-0", message);
}
```

No outbox here because `trade-route-planner` has no database (ADR-0018). A missed publish
on broker outage is **acceptable** for this service — the produced event is derived from
an HTTP response that has already been returned to the caller. A future change (persisting
routes) would require introducing an outbox on this side too.

### 3. telemetry-pipeline — functional `Function<>` beans

```java
// telemetry-pipeline/.../pipeline/EventPipelineConfiguration.java:31+
@Bean
public Function<ReservationCreatedEvent, EnrichedReservationEvent> reservationPipeline(
        MeterRegistry meterRegistry) {
    return event -> enricher.enrich(event); // return null to drop
}

@Bean
public Function<RoutePlannedEvent, EnrichedRouteEvent> routePipeline(
        MeterRegistry meterRegistry) {
    return event -> classifier.classify(event); // risk LOW / MEDIUM / HIGH
}
```

```java
// telemetry-pipeline/.../pipeline/PipelineConfiguration.java:47+
@Bean
public Function<RawTelemetry, AnomalyAlert> telemetryPipeline(
        ValidationFilter validation,
        EnrichmentFilter enrichment,
        AggregationFilter aggregation,
        AnomalyDetectionFilter anomaly) {

    return validation
            .andThen(enrichment)
            .andThen(aggregation)
            .andThen(anomaly::apply);   // null means no alert — filter out
}
```

The function composition `a.andThen(b).andThen(c)` is literally the Pipes & Filters pattern
— a fact that ADR-0001 requires this service to demonstrate.

### 4. Binding registration is declarative only

```yaml
# telemetry-pipeline/src/main/resources/application.yml:11-12
spring:
  cloud:
    function:
      definition: telemetryPipeline;reservationPipeline;routePipeline
```

The `definition` list activates all three `Function<>` beans as stream functions. Removing
a name from the list disables the pipeline without deleting the bean — handy for
feature-flagging a filter without a code change.

### 5. No `KafkaTemplate`, no `@KafkaListener`, no `@EnableBinding`

```
grep -r "KafkaTemplate" src  → no matches in business code
grep -r "@KafkaListener" src → no matches
grep -r "@EnableBinding" src → no matches
```

(Spring Kafka `KafkaTemplate` may exist as a transitive dependency in Spring Cloud Stream
internals, but it is not used directly by any service.)

---

## Consequences

### Benefits

- **One publisher API across the fleet.** Everyone who needs to publish calls
  `StreamBridge.send(...)`. Fewer idioms to learn.
- **Topic routing is configuration.** Moving a producer to a new topic is a YAML change,
  not a code change.
- **Functional consumers are composable.** Adding a new filter stage in
  `telemetry-pipeline` is literally `pipe.andThen(newStage)`.
- **No hidden pollers.** `Supplier<>` beans would run on a scheduler; using StreamBridge
  means publish is always synchronous with the calling thread (or the outbox poller).
- **Refactoring-safe binding names.** `application.yml` lists every binding; an undefined
  name used in `streamBridge.send("wrongName", ...)` fails loudly at runtime.

### Trade-offs

- **StreamBridge is synchronous.** A publish during a request blocks the caller until the
  broker ACKs. Mitigated in starport-registry by publishing from the outbox poller (not
  the request thread) and by Kafka default `acks=1`. `trade-route-planner` does pay this
  latency per HTTP request — accepted because broker RTT is negligible on the local
  network.
- **No back-pressure in starport-registry's publisher.** The outbox fills up transparently
  if Kafka is down — this is the whole point (ADR-0010) but an operator must monitor
  `event_outbox.status = PENDING` count and alert when it diverges.
- **Functional model is less familiar.** Engineers who know `@KafkaListener` need to learn
  that returning a value from a `Function<>` bean publishes it — surprising until
  internalised.
- **Binding name drift.** `streamBridge.send("routePlanned-out-0", ...)` uses a string
  literal; a typo becomes a silent no-op at runtime. Contract tests cover the known
  happy paths.

### Explicit non-decisions

- **Kafka exactly-once semantics (`transactional.id`, idempotent producer).** Not enabled.
  The outbox gives at-least-once; consumers must be idempotent. This is ADR-0010's concern,
  not this ADR's.
- **Batch publishes.** Not configured. `StreamBridge.send()` publishes one record at a
  time. At current throughput, batching is not necessary.

---

## Alternatives Considered

1. **`@Bean Supplier<>` for producers.** Rejected — schedule-based publishing does not
   model transactional domain events; it forces a queue-and-drain bridge between the
   domain method and the Supplier.
2. **Raw `KafkaTemplate`.** Rejected — bypasses the `spring.cloud.stream.bindings` mapping
   and couples every publisher site to a topic name literal. Loses the YAML-owned routing
   benefit.
3. **`@KafkaListener` for consumers.** Rejected — duplicates the binding configuration
   across annotations and YAML. Loses the functional-composition benefit that
   Pipes & Filters relies on.
4. **Mixed model: Supplier for some producers, StreamBridge for others.** Rejected —
   the cognitive cost of "which style goes where?" outweighs any per-case optimisation.

---

## References

- ADR-0001 — Architecture Styles (Pipes & Filters for telemetry-pipeline)
- ADR-0004 — Messaging vs HTTP
- ADR-0010 — Transactional Outbox (why producers flow through a poller, not direct sends)
- ADR-0016 — Kafka Topology (binding → topic map)
- Spring Cloud Stream — Functional programming model —
  https://docs.spring.io/spring-cloud-stream/docs/current/reference/html/spring-cloud-stream.html#spring_cloud_function
- StreamBridge —
  https://docs.spring.io/spring-cloud-stream/docs/current/reference/html/spring-cloud-stream.html#_streambridge_and_dynamic_destinations
