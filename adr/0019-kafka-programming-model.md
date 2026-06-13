# 0019 — Kafka Programming Model: StreamBridge + Functional Beans

**Status:** Accepted
**Date:** 2026-04-17

---

## Context

Spring Cloud Stream offers three producer styles (`@Bean Supplier<>`, `StreamBridge`,
raw `KafkaTemplate`) and two consumer styles (`@Bean Function<>`, `@KafkaListener`).
Mixing them within or across services produces inconsistent wiring, hidden pollers, and
binding-name drift between annotations and YAML.

---

## Decision

**Producers: `StreamBridge` everywhere.** Domain events are transactional, not
scheduled, so a `Supplier<>` poller is the wrong shape. `StreamBridge` honours
`spring.cloud.stream.bindings`, so topic routing stays in YAML.

**Consumers: functional `@Bean Function<IN, OUT>` everywhere.** Returning a payload
publishes to `-out-0`; returning `null` filters. This is exactly the Pipes & Filters
composition `telemetry-pipeline` needs (→ ADR-0001, ADR-0022). Stages are composed via a
`PipelineBuilder` (`.stage(...)` wraps each filter with a Timer, then `andThen`).

```java
// starport-registry — outbox flush
streamBridge.send(outboxEvent.binding(), msg);   // binding name is data, not code

// telemetry-pipeline — functional composition
@Bean
public Function<RawTelemetry, AnomalyAlert> telemetryPipeline(
        ValidationFilter v, EnrichmentFilter e, AggregationFilter a, AnomalyDetectionFilter an,
        MeterRegistry meterRegistry) {
    return PipelineBuilder.<RawTelemetry>create(meterRegistry)
            .stage("validation", v).stage("enrichment", e)
            .stage("aggregation", a).stage("anomaly", an).build();
}
```

Bindings are activated declaratively via
`spring.cloud.function.definition: telemetryPipeline;reservationPipeline;routePipeline`.

No `KafkaTemplate`, `@KafkaListener`, or deprecated `@EnableBinding` in business code.

---

## Why

- **One publisher idiom across the fleet** — every producer is `streamBridge.send(...)`.
- **Topic routing is configuration** — moving a producer to a new topic is a YAML edit.
- **Functional consumers compose** — `andThen` is literally the pipe.
- **No hidden pollers** — `Supplier<>` would run on a scheduler; StreamBridge publishes
  synchronously from the outbox poller or HTTP handler.

---

## Alternatives

- **`@Bean Supplier<>` producers** — schedule-driven; forces queue-and-drain bridge for
  transactional events.
- **Raw `KafkaTemplate`** — bypasses bindings; couples every call site to a topic
  literal.
- **`@KafkaListener` consumers** — splits routing across annotations and YAML; breaks
  functional composition.
- **Mixed model** — cognitive cost of "which style where?" outweighs any per-case win.

---

## References

- ADR-0001 — Architecture Styles
- ADR-0010 — Transactional Outbox (why producers flow through a poller)
- ADR-0016 — Kafka Topology (binding → topic map)
- ADR-0022 — Pipes & Filters conventions
- Spring Cloud Stream functional model —
  https://docs.spring.io/spring-cloud-stream/docs/current/reference/html/spring-cloud-stream.html#spring_cloud_function
