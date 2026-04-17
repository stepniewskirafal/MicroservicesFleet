# 0022 — Pipes & Filters Implementation Conventions — telemetry-pipeline

**Status:** Accepted
**Date:** 2026-04-17

---

## Context

ADR-0001 assigns `telemetry-pipeline` the **Pipes & Filters** style. ADR-0019 says *how*
stages are wired into Spring Cloud Stream as `@Bean Function<>`. This ADR pins down the
things that make the "filter" side of that contract work in practice:

- the filter interface signature,
- the semantics of a null return,
- the statefulness rule (and where it must be broken),
- the message model,
- the composition pattern,
- per-filter observability.

The point of having conventions here is that a stream pipeline can silently become
something worse than a monolith: mutable shared state hidden behind concurrent method
calls, filters with side-effects that depend on ordering, and data types that grow in
every filter as each one adds ad-hoc fields. Without rules, the Pipes & Filters style
degrades into "tangled Kafka consumers".

---

## Decision

### 1. A filter is a `Function<IN, OUT>` (no custom interface)

No bespoke `Filter<IN, OUT>` interface. Each filter is a Spring `@Component` (or
plain class — see below) that implements `java.util.function.Function`. Composition is
`f1.andThen(f2)` — the JDK already provides the pipe.

This avoids a situation where someone adds a `context` parameter to a custom interface
and pulls the whole fleet of filters into a god-object.

### 2. Null means "drop"

```java
public ValidatedTelemetry apply(RawTelemetry raw) {
    if (raw == null || raw.shipId() == null) return null;   // dropped here
    ...
}
```

Returning `null` from `apply` signals **"this message does not flow downstream"**. The
Spring Cloud Stream binder interprets a `null` return as "do not publish" for output
bindings — this is Spring's documented behaviour, not our invention.

Rules:

- Do not throw to drop a message. Throwing causes a consumer retry (ADR-0016) — that is
  for *failures*, not for *legitimate filter rejection*.
- Every filter that may return `null` documents it in the record's Javadoc and the class
  Javadoc.
- Downstream filters must null-check before dereferencing. The pipeline assembly
  (`PipelineConfiguration`) short-circuits on `null` (`if (validated == null) return
  null;`) so individual filters need not guard the whole chain.

### 3. Filters are stateless — *with one explicit exception*

A filter must not mutate its own fields after construction. The constructor may receive
configuration (`SensorThresholdProperties`) or observability handles (`MeterRegistry`);
everything else must be function-local.

**The one exception is `AggregationFilter`.** Aggregation across a time window is, by
definition, stateful. The filter keeps a `ConcurrentMap<String, WindowState>` where the
key is `shipId + sensorType` and the value is a thread-safe accumulator (Welford's
algorithm for running mean/variance, an `AtomicLong` counter, an expiration timestamp).

This is called out explicitly rather than hidden because:

1. It violates the default rule and must be visible in code review.
2. The state is **bounded and expiring**. Old windows are evicted on every `apply`, so
   the map does not grow unboundedly.
3. It is thread-safe by construction (`ConcurrentMap` + `AtomicLong` + immutable
   `WindowState` snapshots). There is no `synchronized` block (which would pin the
   carrier thread under virtual threads — see ADR-0012).

Any *other* filter gaining state requires a new ADR superseding or amending this one.

### 4. Messages are Java records, and they grow monotonically

The message shape at each stage is a Java record. Each subsequent stage produces a record
with the original fields **plus** anything the stage computed. The data only accumulates;
it is never rewritten or dropped silently.

```
RawTelemetry              (5 fields)
  → ValidatedTelemetry    (5 fields; shipId normalised, sensorType as enum)
  → EnrichedTelemetry     (+ shipClass, sector, thresholdRange fields)
  → AggregatedTelemetry   (+ rollingAvg, rollingStdDev, windowStart, windowEnd)
  → AnomalyAlert          (only the alert-relevant subset; this is a terminal type)
```

New filter → new record type. Re-using an earlier record and mutating it across the
pipeline is forbidden — it defeats the invariant that each stage has a stable input/output
contract.

### 5. Side effects stay at the edges

- **Publishing to Kafka** is not done inside a filter. The Spring Cloud Stream binder
  publishes whatever the terminal `Function` returns (ADR-0019). A filter that wants to
  "also publish" would turn a pipeline stage into a branching topology and hide
  observability.
- **DB writes** — there are none; `telemetry-pipeline` is stateless at the persistence
  layer (ADR-0018 notes it has no Flyway).
- **Counters & logs** — allowed and encouraged (see §6). They are observations, not
  side-effects on domain data.

### 6. Every filter reports Micrometer counters

Each filter constructor receives a `MeterRegistry` and increments per-decision counters:

- `telemetry.messages.received` / `telemetry.messages.invalid` (ValidationFilter)
- `telemetry.anomalies.detected{severity=WARNING|CRITICAL}` (AnomalyDetectionFilter)
- (extend for new filters)

These are low-cardinality (ADR-0005 § "cardinality discipline"). High-cardinality
information (`shipId`, `alertId`) belongs in baggage / traces (ADR-0017), not metric
labels.

---

## How the codebase enforces this

### 1. Filter as `Function<>` — ValidationFilter

```java
// telemetry-pipeline/src/main/java/com/galactic/telemetry/filter/ValidationFilter.java
@Component
@RequiredArgsConstructor
public class ValidationFilter implements Function<RawTelemetry, ValidatedTelemetry> {

    private final MeterRegistry meterRegistry;

    @Override
    public ValidatedTelemetry apply(RawTelemetry raw) {
        meterRegistry.counter("telemetry.messages.received").increment();
        if (raw == null || raw.shipId() == null || raw.sensorType() == null) {
            meterRegistry.counter("telemetry.messages.invalid").increment();
            return null;
        }
        return new ValidatedTelemetry(
                raw.shipId(), SensorType.of(raw.sensorType()),
                raw.value(), raw.timestamp(), raw.metadata());
    }
}
```

No mutable fields. Only `final` injected deps. Null return on invalid input.

### 2. Stateful exception — AggregationFilter

```java
// telemetry-pipeline/src/main/java/com/galactic/telemetry/filter/AggregationFilter.java
@Component
public class AggregationFilter implements Function<EnrichedTelemetry, AggregatedTelemetry> {

    private final ConcurrentMap<String, WindowState> windows = new ConcurrentHashMap<>();
    private final Duration window = Duration.ofMinutes(5);

    @Override
    public AggregatedTelemetry apply(EnrichedTelemetry in) {
        String key = in.shipId() + "|" + in.sensorType();
        WindowState state = windows.compute(key, (k, existing) ->
                existing == null || existing.isExpired() ? WindowState.fresh(window) : existing);
        state.record(in.value());       // atomic Welford step
        return state.snapshot(in);      // returns an immutable record
    }
}
```

The comment on the class (`// Intentionally stateful — see ADR-0022`) reminds readers.

### 3. Pipeline composition

```java
// telemetry-pipeline/src/main/java/com/galactic/telemetry/pipeline/PipelineConfiguration.java:47-65
@Bean
public Function<RawTelemetry, AnomalyAlert> telemetryPipeline(
        ValidationFilter validation,
        EnrichmentFilter enrichment,
        AggregationFilter aggregation,
        AnomalyDetectionFilter anomaly) {

    return raw -> {
        ValidatedTelemetry v = validation.apply(raw);
        if (v == null) return null;
        EnrichedTelemetry e = enrichment.apply(v);
        AggregatedTelemetry a = aggregation.apply(e);
        return anomaly.apply(a);    // may return null → no alert published
    };
}
```

A lambda is chosen over `validation.andThen(enrichment).andThen(aggregation).andThen(anomaly)`
because the short-circuit on `v == null` is explicit. `andThen` would NPE on a null
intermediate value — a rough edge worth trading four extra lines for.

### 4. Records with monotonic growth

```java
// telemetry-pipeline/src/main/java/com/galactic/telemetry/model/EnrichedTelemetry.java
public record EnrichedTelemetry(
        String shipId, SensorType sensorType, double value,
        Instant timestamp, Map<String, String> metadata,
        String shipClass, String sector,
        double thresholdLow, double thresholdHigh) {}
```

Nine fields — the original five plus four enrichment fields. The downstream
`AggregatedTelemetry` adds rolling-statistics fields. The upstream records are never
modified.

### 5. Configuration-driven thresholds

```java
// telemetry-pipeline/src/main/java/com/galactic/telemetry/config/SensorThresholdProperties.java:9-39
@ConfigurationProperties(prefix = "telemetry.thresholds")
public record SensorThresholdProperties(Map<SensorType, Range> ranges) {
    public Range rangeFor(SensorType type) {
        return ranges.getOrDefault(type, DEFAULTS.get(type));
    }
}
```

Overriding thresholds per environment is an `application.yml` edit (`application.yml:72-92`),
no code change — the filter depends on the properties bean, not on hard-coded numbers.

### 6. No DB, no KafkaTemplate, no @KafkaListener

```
grep -r "KafkaTemplate"     — no hits
grep -r "@KafkaListener"    — no hits
grep -r "@Repository"       — no hits
```

Consistent with ADR-0018 (no Flyway here) and ADR-0019 (functional model only).

---

## Consequences

### Benefits

- **Every stage is unit-testable.** `ValidationFilter` + a `SimpleMeterRegistry` mock is
  a 10-line test.
- **Topology changes are local.** Adding a new filter is: new class + composition edit in
  one place. Observability (counters) and binder wiring (ADR-0019) follow automatically.
- **Data contracts are explicit.** Each stage has a typed input and typed output record;
  drift is caught at compile time.
- **Null-as-drop is cheap.** No exceptions fly across the pipeline for legitimate
  filtering; retries are reserved for true transient failures.

### Trade-offs

- **Record proliferation.** Five records for one pipeline (Raw → Validated → Enriched →
  Aggregated → AnomalyAlert). For a team used to passing one god-DTO around, this feels
  verbose until the type-safety benefits land on a PR.
- **Null-return idiom is subtle.** `null` is meaningful, but Java tools do not enforce it
  with `@Nullable`; we could adopt `JSpecify` / `checker-framework` later.
- **Stateful `AggregationFilter` is a rule-bender.** It is correct today; any additional
  stateful filter needs explicit sign-off.
- **No back-pressure inside the lambda.** Under a storm of messages all stages run in
  lockstep on the same consumer thread; if enrichment is slow it slows validation too.
  Spring Cloud Stream partition-level concurrency mitigates this; a per-stage executor is
  a later option if a single filter becomes a bottleneck.
- **Aggregation state is in-process.** If the service restarts or the consumer group
  rebalances, windows are lost. Acceptable for a telemetry alerting pipeline; not
  acceptable for billing — captured here so a future use case does not reuse this pattern
  blindly.

---

## Alternatives Considered

1. **Custom `Filter<IN, OUT>` interface.** Rejected — `java.util.function.Function` is
   already perfect. A custom interface invites a "pipeline context" parameter that turns
   filters into non-composable units.
2. **Kafka Streams DSL** (`KStream.filter(...).mapValues(...).peek(...)`). More powerful
   (rocksdb-backed state, interactive queries). Rejected for the current scope — the
   team is already fluent in Spring Cloud Stream, and the single stateful filter fits in
   memory. Revisit when state needs durability or windowing spans hours.
3. **Reactor `Flux<>` inside one consumer method.** Rejected — reactive types in
   `Function<>` signatures conflict with Spring Cloud Stream's default binding semantics
   and complicate testing.
4. **"Rich DTO" carrying all fields from the start.** Rejected — every filter would have
   to read mostly-null fields, and contracts would degrade into documentation rather
   than compiled types.
5. **Publishing from inside a filter** (e.g. "send a Slack alert from
   `AnomalyDetectionFilter`"). Rejected — side-effects in filters destroy replay and
   testability. Alerts go out of the pipeline through a downstream consumer on
   `telemetry.alerts`.

---

## References

- ADR-0001 — Architecture Styles (assigns Pipes & Filters to this service)
- ADR-0005 — Observability Stack (metric cardinality rules)
- ADR-0012 — Virtual Threads (why `ConcurrentMap` over `synchronized`)
- ADR-0016 — Kafka Topology (retry is for failure, not for filtering)
- ADR-0017 — Tracing Propagation (high-cardinality fields via baggage)
- ADR-0019 — Kafka Programming Model (functional Bean registration)
- Gregor Hohpe & Bobby Woolf — *Enterprise Integration Patterns*, Pipes and Filters
- Welford's algorithm for running variance —
  https://en.wikipedia.org/wiki/Algorithms_for_calculating_variance#Welford's_online_algorithm
