# 0017 — Distributed Tracing Propagation (W3C + OTel Baggage + Outbox)

**Status:** Accepted
**Date:** 2026-04-17

---

## Context

A single user request to `POST /api/v1/starports/{code}/reservations` touches at minimum:

1. `starport-registry` (layered controller → service → repository → outbox).
2. A synchronous HTTP call to `trade-route-planner` (ADR-0014).
3. A delayed Kafka publish from the outbox to `telemetry-pipeline`, which then enriches and
   re-publishes.

Without trace propagation each hop shows up in Tempo as a disconnected span, and
`reservationId` is unrecoverable from a P99 alert. Observability (ADR-0005) needs three
things to work end-to-end:

1. **Trace context** (`traceId` / `spanId`) must flow across HTTP and Kafka boundaries so
   one trace has all spans.
2. **Business correlation** (`reservationId`, `routeId`) must travel with the trace so an
   operator can pivot from a Prometheus exemplar to every span and log line for that
   reservation.
3. **Async boundaries** (outbox) must not lose context even though the producing thread has
   long terminated by the time the scheduler publishes.

---

## Decision

### Propagation format

Use **W3C Trace Context** (`traceparent` / `tracestate` headers) — the OpenTelemetry
default. No B3 configuration is set, so the default path is taken.

### Business correlation

Use **OTel baggage** for two fields:

```yaml
management:
  tracing:
    baggage:
      remote-fields: [reservationId, routeId]
      correlation.fields: [reservationId, routeId]
```

Baggage travels with the trace context on both HTTP and Kafka hops and is also copied to
MDC so it appears in log lines. Configured identically in `starport-registry` and
`trade-route-planner`.

### Log correlation

Every service logs with the pattern:

```yaml
logging.pattern.level: "[${spring.application.name:},%X{traceId:-},%X{spanId:-}] "
```

Micrometer tracing automatically writes `traceId` / `spanId` to MDC so `%X{...}` resolves
on every log line. `reservationId` / `routeId` are additionally in MDC via
`baggage.correlation.fields`.

### Async hop: outbox header injection

For the outbox (ADR-0010), trace context is captured **at append time** on the producing
thread and stored in `event_outbox.headers_json`. When the poller later publishes the row,
it extracts those headers and creates a new span as a consumer of the original. This
preserves the parent-child span relationship across the gap between the DB commit and the
Kafka publish (up to `app.poll-interval-ms` = 30 s later).

Mechanism: `Micrometer Observation` + `SenderContext` + `ReceiverContext`, not hand-coded
header copying — so upgrading the tracing library does not require code changes in the
outbox.

### Sampling

`management.tracing.sampling.probability: ${TRACE_SAMPLING:1.0}` — 100 % sampling is the
local/demo default. Production should set `TRACE_SAMPLING=0.1` (or lower) via environment
variable; the code does not change.

---

## How the codebase enforces this

### 1. Tracing bridge dependency (parent POM)

```xml
<!-- pom.xml:46-53 -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-bom</artifactId>
    <version>1.16.0</version>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-bom</artifactId>
    <version>1.46.0</version>
</dependency>
```

Each service pulls `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp`
(`starport-registry/pom.xml:185+`), delivering W3C Trace Context by default and OTLP export
to Tempo (ADR-0005).

### 2. Baggage config

```yaml
# starport-registry/src/main/resources/application.yml:160-170
management:
  tracing:
    sampling.probability: ${TRACE_SAMPLING:1.0}
    baggage:
      remote-fields:     [reservationId, routeId]
      correlation.fields: [reservationId, routeId]
```

Identical block in `trade-route-planner/application.yml:70-80`. `telemetry-pipeline` has
only the sampling line — it is a consumer-only service for business purposes, but trace
context still propagates automatically via Spring Cloud Stream Kafka auto-instrumentation.

### 3. HTTP propagation — automatic

The `@LoadBalanced RestClient` (ADR-0014) is auto-instrumented by micrometer tracing. Every
outbound request receives the caller's `traceparent` header, and every inbound request in
`trade-route-planner` extracts it into a child span — no code in the adapters mentions
tracing.

### 4. Outbox append — capture context into headers

```java
// starport-registry/.../service/outbox/OutboxAppender.java:30-48
SenderContext<Map<String, Object>> senderContext =
        new SenderContext<>(headers, Kind.PRODUCER);

Observation.createNotStarted(
        "outbox.append",
        () -> senderContext,
        observationRegistry
).observe(() -> {
    // Micrometer injects `traceparent` and `tracestate` into `headers`
    // before the OutboxWriter persists the row.
    outboxWriter.save(binding, eventType, messageKey, payload, headers);
});
```

The `SenderContext` is the key: it signals to micrometer-tracing that this observation is
producing a message and the propagation headers must be injected into the supplied map.

### 5. Outbox publish — restore context from headers

```java
// starport-registry/.../service/outbox/InboxPublisher.java:95-105
ReceiverContext<Map<String, String>> receiverContext =
        new ReceiverContext<>(Kind.CONSUMER, row.headers());

Observation.createNotStarted("outbox.publish", () -> receiverContext, observationRegistry)
        .observe(() -> streamBridge.send(row.binding(), msg));
```

Micrometer extracts `traceparent` from the stored headers and uses it as the **parent** of
the new publish span. Tempo then renders one continuous trace: controller → outbox.append →
(30 s gap) → outbox.publish → Kafka consumer in telemetry-pipeline.

### 6. Kafka propagation — automatic

Spring Cloud Stream's Kafka binder auto-propagates `traceparent` via Kafka message headers
when observability is enabled on the binder. No manual header copy is needed in
`StreamBridge.send()` — the outbox headers are only used for the DB-to-publish gap, not for
the Kafka hop itself.

### 7. MDC propagation to logs

```yaml
# All three services, application.yml
logging:
  pattern:
    level: "[${spring.application.name:},%X{traceId:-},%X{spanId:-}] "
```

Every log line is prefixed with `[service,traceId,spanId]`, enabling Grafana Loki to join
logs to traces via Grafana's "Logs for this span" feature (ADR-0005).

---

## Consequences

### Benefits

- **One continuous trace** for every reservation, even across the outbox gap. An operator
  clicking a P99 exemplar in a Prometheus histogram lands on a single Tempo trace spanning
  HTTP + DB + Kafka hops.
- **Search by business key.** `reservationId=12345` in baggage means Loki logs, Tempo
  spans, and Prometheus exemplar traces all share the same label — one query in Grafana
  finds everything.
- **Zero bespoke tracing code in business logic.** `SenderContext` / `ReceiverContext` live
  inside the outbox infrastructure; domain services are pure Java.
- **Backend-agnostic.** W3C Trace Context is the industry default; swapping Tempo for
  Jaeger or a SaaS tracer requires only changing the OTLP endpoint.

### Trade-offs

- **Baggage size is unbounded in principle.** Today two short IDs (`reservationId`,
  `routeId`) — each Kafka message carries a few hundred extra bytes of headers. A runaway
  addition of large baggage values could bloat every message. Mitigation: baggage fields
  are an explicit allowlist in `application.yml`.
- **100 % sampling is expensive.** Fine for local / demo; in production
  `TRACE_SAMPLING=0.1` is the intended override. Without that, Tempo storage grows
  linearly with traffic.
- **Outbox poll interval delays span timing.** The gap between the append span and the
  publish span can be up to `app.poll-interval-ms` (default 30 s). The trace is still
  correct, but a human reader sees a "stretched" trace. This is a feature, not a bug: it
  visualises the outbox latency directly.
- **B3 compatibility not set.** If a future caller sends only `X-B3-TraceId` headers, they
  will not be recognised. Enable `management.tracing.propagation.type: [W3C, B3]` if
  needed — single-line change.

---

## Alternatives Considered

1. **B3 propagation only.** Rejected — W3C is the default and the forward-compatible
   choice; B3 is a Zipkin legacy format.
2. **Manual header injection in every Kafka producer.** Rejected — tedious and drift-prone.
   Observation API + Spring Cloud Stream auto-instrumentation is already a first-class
   feature in the stack.
3. **Carry `reservationId` only as a metric tag, not in baggage.** Rejected — tags are
   low-cardinality by construction (ADR-0005); baggage is the right channel for
   high-cardinality correlation.
4. **Don't try to preserve trace context across the outbox gap.** Rejected — that is the
   exact place where a silent break in the trace would be most harmful to an operator
   debugging a slow reservation.

---

## References

- ADR-0005 — Observability Stack (Tempo, Loki, Prometheus correlation)
- ADR-0010 — Resilience Patterns (transactional outbox — the async gap)
- ADR-0016 — Kafka Topology (where traces flow through)
- W3C Trace Context — https://www.w3.org/TR/trace-context/
- Micrometer Observation API — https://micrometer.io/docs/observation
- OpenTelemetry Baggage — https://opentelemetry.io/docs/specs/otel/baggage/api/
