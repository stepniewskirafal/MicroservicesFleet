# 0022 — Pipes & Filters Conventions — telemetry-pipeline

**Status:** Accepted
**Date:** 2026-04-17

---

## Context

ADR-0001 assigns `telemetry-pipeline` the Pipes & Filters style; ADR-0019 pins the
wiring (`@Bean Function<>`). Without filter-level conventions, a stream pipeline
silently degrades into "tangled Kafka consumers": mutable shared state, side-effects
that depend on ordering, god-DTOs that grow ad-hoc fields per stage.

---

## Decision

1. **A filter is `java.util.function.Function<IN, OUT>`** — no custom `Filter`
   interface. Composition is `f1.andThen(f2)`.
2. **`null` return = drop.** Spring Cloud Stream skips publishing on `null`. Do not
   throw to filter (throwing triggers a retry — ADR-0016 — which is for failures, not
   rejection).
3. **Filters are stateless** — constructor takes config + observability handles; no
   mutated fields. **The one exception is `AggregationFilter`,** which holds a bounded,
   self-expiring `ConcurrentMap<String, WindowState>` (Welford's algorithm). Any other
   stateful filter requires a new ADR.
4. **Messages are records, growing monotonically:**
   `RawTelemetry → ValidatedTelemetry → EnrichedTelemetry → AggregatedTelemetry →
   AnomalyAlert`. New stage = new record. Never mutate or reuse an upstream type.
5. **Side effects stay at the edges.** The terminal `Function` return is published by
   the binder; no filter calls Kafka or DB directly. No persistence layer here
   (ADR-0018).
6. **Every filter emits Micrometer counters** with low-cardinality labels (ADR-0005).
   High-cardinality data (`shipId`, `alertId`) goes in trace baggage (ADR-0017).

Composition goes through a small `PipelineBuilder.stage(...)` chain rather than raw
`andThen`, so the null short-circuit lives in one place (`stage` returns `null` early
when its input is `null`) instead of being re-implemented per filter. The terminal
`Function` is the binder-bound `@Bean`:

```java
@Bean
Function<RawTelemetry, AnomalyAlert> telemetryPipeline(
        ValidationFilter validation, EnrichmentFilter enrichment,
        AggregationFilter aggregation, AnomalyDetectionFilter anomaly) {
    return PipelineBuilder.start(validation)   // RawTelemetry → ValidatedTelemetry, null = drop
            .stage(enrichment)
            .stage(aggregation)
            .stage(anomaly)                    // null → no alert published
            .build();
}
```

---

## Why

- **Every stage is unit-testable** with a plain `Function` + `SimpleMeterRegistry`.
- **Topology changes are local** — new filter = new class + one composition edit.
- **Typed records catch contract drift at compile time** — no god-DTO with mostly-null
  fields.
- **Null-as-drop is cheap** — no exceptions for legitimate filtering.
- **`ConcurrentMap` over `synchronized`** in `AggregationFilter` avoids pinning carrier
  threads under virtual threads (ADR-0012).

---

## Known gaps

- Null contract is not statically enforced (no `JSpecify` yet).
- No back-pressure inside the lambda — all stages run lockstep on one consumer thread.
  Per-stage executor is a later option.
- Aggregation state is in-process; lost on restart / rebalance. Fine for alerting, not
  for billing.

---

## Alternatives

- **Custom `Filter<IN, OUT>` interface** — invites a "pipeline context" param that
  destroys composability.
- **Kafka Streams DSL** — more powerful (rocksdb state, interactive queries); overkill
  while state fits in memory.
- **Reactor `Flux<>` in a `Function`** — conflicts with binder default binding
  semantics; complicates tests.
- **Rich god-DTO carrying all fields** — contracts degrade to documentation.
- **Publishing from inside a filter** — destroys replay and testability; alerts leave
  via a downstream consumer on `telemetry.alerts`.

---

## References

- ADR-0001 — Architecture Styles
- ADR-0005 — Observability (cardinality)
- ADR-0012 — Virtual Threads (no `synchronized`)
- ADR-0016 — Kafka Topology (retry vs filter semantics)
- ADR-0019 — Kafka Programming Model
- Hohpe & Woolf — *Enterprise Integration Patterns*, Pipes and Filters
- Welford's algorithm —
  https://en.wikipedia.org/wiki/Algorithms_for_calculating_variance#Welford's_online_algorithm
