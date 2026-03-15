# How does `telemetry-pipeline` communicate with other modules?

## Data flow diagram

```
[External Producer]
        │
        │  JSON → topic: telemetry.raw
        ▼
  ┌─────────────────────────────────┐
  │       KAFKA BROKER (9092/9093)  │
  └────────────────┬────────────────┘
                   │ consumes (group: telemetry-pipeline)
                   ▼
  ┌─────────────────────────────────────────────────────┐
  │              telemetry-pipeline                      │
  │  RawTelemetry                                        │
  │     → ValidationFilter  (rejects NULL, bad types)    │
  │     → EnrichmentFilter  (adds shipClass, sector,     │
  │                          thresholds from SensorThresholds) │
  │     → AggregationFilter (rolling avg, stdDev, max)   │
  │     → AnomalyDetectionFilter (CRITICAL / WARNING)    │
  │                                                      │
  │  Result: AnomalyAlert (or null → nothing published)  │
  └────────────────┬────────────────────────────────────┘
                   │ publishes (if anomaly)
                   │  JSON → topic: telemetry.alerts
                   ▼
  ┌─────────────────────────────────┐
  │       KAFKA BROKER              │
  └─────────────────────────────────┘
        │
        ▼
  [Downstream consumers: starport-registry, trade-route-planner, ...]
```

---

## 1. Asynchronous communication — Kafka (primary)

| Direction | Kafka Topic | Format | Description |
|---|---|---|---|
| **Input** | `telemetry.raw` | JSON (`RawTelemetry`) | Raw sensor readings from ships |
| **Output** | `telemetry.alerts` | JSON (`AnomalyAlert`) | Anomaly alerts (CRITICAL / WARNING) |

- Mechanism: **Spring Cloud Stream** with **Kafka binder**
- Functional bean: `Function<RawTelemetry, AnomalyAlert> telemetryPipeline`
- Spring Cloud Stream automatically binds the function to both topics
- Retry: 3 attempts, backoff 1 s (`max-attempts: 3`, `back-off-initial-interval: 1000`)
- `null` returned from the function = **no message is published** (filtering)

---

## 2. Service Discovery — Eureka

- The service **registers** with Eureka Server (`http://eureka:8761/eureka`)
- This allows other services to find it by the name `telemetry-pipeline`
- Heartbeat every 10 s, expiration after 30 s
- When scaling, two instances are launched: `telemetry-pipeline-1:8090`, `telemetry-pipeline-2:8091` — both in the same Kafka consumer group (partition-level load balancing)

---

## 3. Communication with `starport-registry` — CURRENTLY static (TODO)

In `EnrichmentFilter` there is a hardcoded ship registry:

```java
// Static ship registry — in production this would come from Starport Registry via HTTP or cache
private static final Map<String, ShipInfo> SHIP_REGISTRY = Map.of(
    "SHIP-001", new ShipInfo("Corvette", "Alpha-Centauri"),
    ...
);
```

> ⚠️ The comment in the code explicitly states that in production, ship data should be fetched from **`starport-registry` via HTTP or cache**. Currently this is dummy data.

---

## 4. Observability — monitoring stack

| Component | Protocol | What it sends |
|---|---|---|
| **Prometheus** | HTTP scrape (`/actuator/prometheus`) | Metrics: `telemetry.messages.received`, `telemetry.messages.invalid`, `telemetry.anomalies.detected{severity=WARNING/CRITICAL}` |
| **Tempo / Zipkin** | OTLP HTTP (`http://tempo:4318/v1/traces`) | Distributed traces |
| **Loki / Promtail** | Log scraping from Docker | Application logs |
| **Grafana** | UI | Visualization of metrics, logs, and traces |

---

## Summary

```
telemetry-pipeline does NOT communicate via HTTP with other business services.
All domain communication happens through Kafka (event-driven).
```

- **Kafka** → input/output of domain data (asynchronously)
- **Eureka** → registration/discovery (synchronously at startup)
- **Prometheus/Tempo/Loki** → metrics, tracing, logs (observability)
- **`starport-registry`** → planned HTTP/cache, currently a static mock



# How do we know where Kafka sends events and in what order the filters operate?

---

## PART 1 — How do we know which Kafka topic is the input?

### Step 1: `application.yml` — function declaration and bindings

```yaml
spring:
  cloud:
    function:
      definition: telemetryPipeline   # ← (1) tells Spring Cloud Stream: "register this function"

    stream:
      bindings:
        telemetryPipeline-in-0:       # ← (2) NAMING CONVENTION: {functionName}-in-{index}
          destination: telemetry.raw  # ← (3) this is the Kafka topic that is READ from
          group: telemetry-pipeline   # ← (4) consumer group
          content-type: application/json

        telemetryPipeline-out-0:      # ← {functionName}-out-{index}
          destination: telemetry.alerts  # ← topic to which the result is WRITTEN
          content-type: application/json
```

### Key: Spring Cloud Stream naming convention

```
{beanName}-in-{index}   → INPUT (subscribe to Kafka topic)
{beanName}-out-{index}  → OUTPUT (publish to Kafka topic)
```

**Bean `telemetryPipeline`** is defined in `PipelineConfiguration.java`:
```java
@Bean
public Function<RawTelemetry, AnomalyAlert> telemetryPipeline(...) { ... }
//              ↑                ↑
//        input type        output type
```

Spring Cloud Stream sees:
- `telemetryPipeline-in-0` → reads from `telemetry.raw`, deserializes JSON → `RawTelemetry`
- `telemetryPipeline-out-0` → serializes `AnomalyAlert` to JSON → writes to `telemetry.alerts`

---

## PART 2 — In what order are the filters invoked?

The order is **explicit and hard-coded** — coded directly in the lambda in `PipelineConfiguration.java`:

```java
@Bean
public Function<RawTelemetry, AnomalyAlert> telemetryPipeline(
        ValidationFilter validationFilter,
        EnrichmentFilter enrichmentFilter,
        AggregationFilter aggregationFilter,
        AnomalyDetectionFilter anomalyDetectionFilter) {

    return raw -> {
        // STEP 1 ──────────────────────────────────────────────
        var validated = validationFilter.apply(raw);
        if (validated == null) {
            return null;   // ← STOP: message is dropped, nothing goes to Kafka
        }

        // STEP 2 ──────────────────────────────────────────────
        var enriched = enrichmentFilter.apply(validated);

        // STEP 3 ──────────────────────────────────────────────
        var aggregated = aggregationFilter.apply(enriched);

        // STEP 4 ──────────────────────────────────────────────
        return anomalyDetectionFilter.apply(aggregated);
        // ↑ if null (no anomaly) → Spring Cloud Stream does NOT publish anything
    };
}
```

### Visually:

```
[Kafka: telemetry.raw]
         │
         │  JSON deserialization → RawTelemetry
         ▼
┌─────────────────────┐
│  ValidationFilter   │  ← checks: null? missing shipId? bad sensorType? NaN?
└─────────┬───────────┘
          │  null → DROP (no publish to Kafka)
          │  ok   → ValidatedTelemetry
          ▼
┌─────────────────────┐
│  EnrichmentFilter   │  ← adds: shipClass, currentSector, thresholds (lower/upper threshold)
└─────────┬───────────┘
          │  EnrichedTelemetry
          ▼
┌─────────────────────┐
│  AggregationFilter  │  ← computes: rollingAvg, rollingStdDev, max (5-min window, in-memory)
└─────────┬───────────┘
          │  AggregatedTelemetry
          ▼
┌──────────────────────────┐
│  AnomalyDetectionFilter  │  ← detects: CRITICAL (threshold exceeded) / WARNING (3σ)
└─────────┬────────────────┘
          │  null → no anomaly, nothing goes to Kafka
          │  AnomalyAlert → serialization to JSON
          ▼
[Kafka: telemetry.alerts]
```

---

## PART 3 — Data types between filters (contract)

```
RawTelemetry         → { shipId, sensorType(String), value, timestamp, metadata }
     ↓ ValidationFilter
ValidatedTelemetry   → { shipId, sensorType(SensorType enum), value, timestamp, metadata }
     ↓ EnrichmentFilter
EnrichedTelemetry    → { + shipClass, currentSector, lowerThreshold, upperThreshold }
     ↓ AggregationFilter
AggregatedTelemetry  → { + rollingAvg, rollingStdDev, max, windowSampleCount }
     ↓ AnomalyDetectionFilter
AnomalyAlert         → { shipId, sensorType, severity, description, currentValue,
                          threshold, rollingAvg, shipClass, currentSector, detectedAt }
```

---

## Quick summary

| Question | Answer |
|---|---|
| How does Spring know which topic to read? | Naming convention: `telemetryPipeline-in-0` → `destination: telemetry.raw` in YML |
| What determines the filter order? | Explicit sequence of `apply()` calls in the lambda in `PipelineConfiguration.java` |
| What happens with a bad message? | `ValidationFilter` returns `null` → function returns `null` → Spring Cloud Stream publishes nothing |
| What happens when there is no anomaly? | `AnomalyDetectionFilter` returns `null` → same as above, no publish |


# Is the `telemetryPipeline` bean essential? Can it be done differently?

---

## Short answer

**Yes — the bean is essential**, but it does not have to be a `@Bean` in a `@Configuration` class.
Spring Cloud Stream accepts **several different ways** to register a function.

---

## Approach 1 (current) — `@Bean` in `@Configuration`

```java
@Configuration
public class PipelineConfiguration {

    @Bean
    public Function<RawTelemetry, AnomalyAlert> telemetryPipeline(...) {
        return raw -> {
            var validated = validationFilter.apply(raw);
            if (validated == null) return null;
            var enriched   = enrichmentFilter.apply(validated);
            var aggregated = aggregationFilter.apply(enriched);
            return anomalyDetectionFilter.apply(aggregated);
        };
    }
}
```

✅ **Pros**: full control over composition, easy unit testing of the lambda, explicit order.
❌ **Cons**: everything in one place — gets crowded with multiple pipelines.

---

## Approach 2 — `@Component` implementing `Function<I, O>`

```java
@Component("telemetryPipeline")   // ← bean name = key for binding in YML
public class TelemetryPipelineFunction
        implements Function<RawTelemetry, AnomalyAlert> {

    private final ValidationFilter      validationFilter;
    private final EnrichmentFilter      enrichmentFilter;
    private final AggregationFilter     aggregationFilter;
    private final AnomalyDetectionFilter anomalyDetectionFilter;

    // constructor with @Autowired / constructor injection

    @Override
    public AnomalyAlert apply(RawTelemetry raw) {
        var validated = validationFilter.apply(raw);
        if (validated == null) return null;
        var enriched   = enrichmentFilter.apply(validated);
        var aggregated = aggregationFilter.apply(enriched);
        return anomalyDetectionFilter.apply(aggregated);
    }
}
```

✅ **Pros**: more object-oriented, OCP — each class has a single responsibility.
⚠️ **Note**: the name `@Component("telemetryPipeline")` must match `definition: telemetryPipeline` in YML.

---

## Approach 3 — Composition via `andThen()` (functional style)

Spring Cloud Stream supports **function chaining** through `definition`:

```yaml
spring:
  cloud:
    function:
      definition: validate|enrich|aggregate|detectAnomalies
    stream:
      bindings:
        validate-in-0:
          destination: telemetry.raw
        detectAnomalies-out-0:
          destination: telemetry.alerts
```

```java
@Bean public Function<RawTelemetry, ValidatedTelemetry>   validate()        { ... }
@Bean public Function<ValidatedTelemetry, EnrichedTelemetry> enrich()        { ... }
@Bean public Function<EnrichedTelemetry, AggregatedTelemetry> aggregate()    { ... }
@Bean public Function<AggregatedTelemetry, AnomalyAlert>  detectAnomalies() { ... }
```

The `|` (pipe) operator tells Spring Cloud Stream: **compose these functions into a pipeline**.
This is equivalent to `validate.andThen(enrich).andThen(aggregate).andThen(detectAnomalies)`.

✅ **Pros**: each function is a separate, testable bean. Purely functional style.
❌ **Cons**: harder to handle `null` (short-circuit) between steps — requires using `Optional` or separate filtering mechanisms.

---

## Approach 4 — `StreamListener` (old, deprecated)

```java
// ❌ DEPRECATED since Spring Cloud Stream 3.x — DO NOT use in new projects
@StreamListener("telemetry.raw")
@SendTo("telemetry.alerts")
public AnomalyAlert handle(RawTelemetry raw) { ... }
```

---

## Comparison of all approaches

| Approach | Bean registration | Null flexibility | Testability | Style |
|---|---|---|---|---|
| `@Bean` lambda (current) | `@Configuration` | ✅ full | ✅ easy | functional |
| `@Component` implements `Function` | `@Component` | ✅ full | ✅ easy | object-oriented |
| Pipe `validate\|enrich\|...` in YML | `@Bean` per step | ⚠️ difficult | ✅ very easy | purely functional |
| `@StreamListener` | annotation | ✅ full | ⚠️ difficult | ❌ deprecated |

---

## What is REALLY essential?

Not the `@Bean` itself — the essential things are **three things together**:

```
1. Bean name            →  must match the key in application.yml
                            (definition: telemetryPipeline)

2. Generic type         →  Function<RawTelemetry, AnomalyAlert>
                            Spring Cloud Stream uses the types for JSON deserialization/serialization

3. Binding in YML       →  telemetryPipeline-in-0 / telemetryPipeline-out-0
                            connects the function to the physical Kafka topic
```

Without any of these three — the binding will not work.

---

## Decision shortcut

```
Have a single pipeline with null-checks between steps?
  → Approach 1 or 2 (current in the project) ✅

Want to test each step completely independently?
  → Approach 3 (pipe in YML) ✅

Writing new code on an old Spring Cloud Stream 2.x project?
  → Approach 2 (@Component) ✅

See @StreamListener in the code?
  → Refactor to Function<I,O> ⚠️
```


# How does Spring Cloud Stream connect YML with the Java bean?

---

## Starting point — two files must "talk" using the same name

```yaml
# application.yml
spring:
  cloud:
    function:
      definition: telemetryPipeline   # ← STRING: bean name
    stream:
      bindings:
        telemetryPipeline-in-0:       # ← STRING: {same name}-in-0
          destination: telemetry.raw
        telemetryPipeline-out-0:      # ← STRING: {same name}-out-0
          destination: telemetry.alerts
```

```java
// PipelineConfiguration.java
@Bean
public Function<RawTelemetry, AnomalyAlert> telemetryPipeline(...) { ... }
//                                          ↑
//                             method name = bean name in the Spring context
```

**Key: `@Bean` without a specified name = the method name is the bean name.**
Spring registers this bean under the key `"telemetryPipeline"` in the `ApplicationContext`.

---

## Step by step — what happens at startup

### STEP 1: Spring Boot scans the context and registers beans

```
ApplicationContext registers:
  "validationFilter"       → ValidationFilter
  "enrichmentFilter"       → EnrichmentFilter
  "aggregationFilter"      → AggregationFilter
  "anomalyDetectionFilter" → AnomalyDetectionFilter
  "telemetryPipeline"      → Function<RawTelemetry, AnomalyAlert>  ← lambda
```

Dependencies are injected through the bean method's constructor parameters:

```java
@Bean
public Function<RawTelemetry, AnomalyAlert> telemetryPipeline(
        ValidationFilter validationFilter,      // ← Spring injects the "validationFilter" bean
        EnrichmentFilter enrichmentFilter,      // ← Spring injects the "enrichmentFilter" bean
        AggregationFilter aggregationFilter,    // ← Spring injects the "aggregationFilter" bean
        AnomalyDetectionFilter anomalyDetectionFilter) { // ← same as above
    ...
}
```

**Bean creation order:**
Spring sees that `telemetryPipeline` DEPENDS on 4 filters → creates them FIRST,
only then creates `telemetryPipeline`. This is standard DI.

---

### STEP 2: `FunctionCatalog` scans beans and looks for Function/Consumer/Supplier

`FunctionCatalog` (spring-cloud-function) iterates over all beans in the context
and collects those whose type is `Function`, `Consumer`, or `Supplier`:

```
Found:
  "validationFilter"       → Function<RawTelemetry, ValidatedTelemetry>
  "enrichmentFilter"       → Function<ValidatedTelemetry, EnrichedTelemetry>
  "aggregationFilter"      → Function<EnrichedTelemetry, AggregatedTelemetry>
  "anomalyDetectionFilter" → Function<AggregatedTelemetry, AnomalyAlert>
  "telemetryPipeline"      → Function<RawTelemetry, AnomalyAlert>
```

---

### STEP 3: `definition: telemetryPipeline` — FunctionCatalog looks up by NAME

```yaml
spring:
  cloud:
    function:
      definition: telemetryPipeline
```

`FunctionCatalog.lookup("telemetryPipeline")` does literally:

```java
// simplified pseudocode from FunctionCatalog
Object bean = applicationContext.getBean("telemetryPipeline");
//                                        ↑
//                          looks up by the STRING from definition
```

Found the bean → stores it as the "active function" for the pipeline.

---

### STEP 4: Parsing binding keys — the `{name}-in-{index}` convention

`BindingServiceProperties` parses the keys from YML:

```
"telemetryPipeline-in-0"
 └── split("-in-")  → ["telemetryPipeline", "0"]
     function: "telemetryPipeline"
     direction: INPUT
     index: 0
     destination: "telemetry.raw"

"telemetryPipeline-out-0"
 └── split("-out-") → ["telemetryPipeline", "0"]
     function: "telemetryPipeline"
     direction: OUTPUT
     index: 0
     destination: "telemetry.alerts"
```

Spring verifies: does the function `"telemetryPipeline"` exist in `FunctionCatalog`?
→ YES → the binding is valid.

---

### STEP 5: KafkaMessageChannelBinder creates physical channels

```
INPUT binding:
  KafkaConsumer(
    topic         = "telemetry.raw",          ← from destination
    consumerGroup = "telemetry-pipeline",     ← from group
    maxAttempts   = 3,                        ← from consumer.max-attempts
    backoff       = 1000ms
  )
  → connects to internal MessageChannel "telemetryPipeline-in-0"

OUTPUT binding:
  KafkaProducer(
    topic = "telemetry.alerts"               ← from destination
  )
  → connects to internal MessageChannel "telemetryPipeline-out-0"
```

---

### STEP 6: MessageConverter — JSON ↔ Java (how does it know the types?)

```java
// Spring does this via reflection on the "telemetryPipeline" bean:
Method beanMethod = PipelineConfiguration.class
                        .getMethod("telemetryPipeline", ...);

Type returnType = beanMethod.getGenericReturnType();
// → ParameterizedType: Function<RawTelemetry, AnomalyAlert>

ResolvableType resolvable = ResolvableType.forType(returnType);
Class<?> inputType  = resolvable.getGeneric(0).resolve(); // → RawTelemetry.class
Class<?> outputType = resolvable.getGeneric(1).resolve(); // → AnomalyAlert.class
```

Jackson receives `RawTelemetry.class` and knows how to deserialize the JSON:

```
Kafka bytes:
{"shipId":"SHIP-001","sensorType":"TEMPERATURE","value":42.5,"timestamp":"..."}
                    ↓  Jackson.readValue(bytes, RawTelemetry.class)
RawTelemetry { shipId="SHIP-001", sensorType="TEMPERATURE", value=42.5, ... }
                    ↓  telemetryPipeline.apply(raw)
AnomalyAlert { shipId="SHIP-001", severity=CRITICAL, ... }
                    ↓  Jackson.writeValueAsBytes(anomalyAlert)
Kafka bytes: {"shipId":"SHIP-001","severity":"CRITICAL",...}
```

---

## The entire chain in a single view

```
application.yml                           Java / Spring
─────────────────────────────────────────────────────────────────────
definition: telemetryPipeline    →  applicationContext.getBean("telemetryPipeline")
                                     = @Bean method named telemetryPipeline()

telemetryPipeline-in-0           →  FunctionCatalog: function="telemetryPipeline"
  destination: telemetry.raw         direction=INPUT, index=0
  group: telemetry-pipeline      →  KafkaConsumer(topic="telemetry.raw",
  content-type: application/json      group="telemetry-pipeline")
                                  →  Jackson deserializes to RawTelemetry.class
                                     (type from Method.getGenericReturnType() generics[0])

telemetryPipeline-out-0          →  FunctionCatalog: function="telemetryPipeline"
  destination: telemetry.alerts      direction=OUTPUT, index=0
  content-type: application/json →  KafkaProducer(topic="telemetry.alerts")
                                  →  Jackson serializes AnomalyAlert to JSON
                                     (type from Method.getGenericReturnType() generics[1])
```

---

## Why is the `telemetryPipeline` bean created last?

Spring automatically detects the dependency graph through the `@Bean` method parameters:

```java
@Bean
public Function<RawTelemetry, AnomalyAlert> telemetryPipeline(
        ValidationFilter validationFilter,       // ← depends on this bean
        EnrichmentFilter enrichmentFilter,       // ← depends on this bean
        AggregationFilter aggregationFilter,     // ← depends on this bean
        AnomalyDetectionFilter anomalyDetectionFilter) { // ← depends on this bean
```

Spring builds a DAG (directed acyclic graph) of dependencies:

```
telemetryPipeline
    ├── validationFilter       (created as 1st)
    ├── enrichmentFilter       (created as 2nd)
    ├── aggregationFilter      (created as 3rd)
    └── anomalyDetectionFilter (created as 4th)
                               (telemetryPipeline created as 5th)
```

There is no need to declare this manually anywhere — Spring figures it out on its own.
