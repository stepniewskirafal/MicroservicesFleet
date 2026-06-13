# 0017 — Distributed Tracing Propagation (W3C + OTel Baggage + Outbox)

**Status:** Accepted  
**Date:** 2026-04-17

---

## Context

A single `POST /api/v1/starports/{code}/reservations` crosses three boundaries:
synchronous HTTP to trade-route-planner (ADR-0014), a delayed Kafka publish from the
outbox (ADR-0010), and a Kafka consume in telemetry-pipeline. Without propagation each
hop is a disconnected span, and `reservationId` cannot be recovered from a P99 alert.

---

## Decision

**Format** — W3C Trace Context (`traceparent` / `tracestate`), OpenTelemetry default.

**Business correlation** — OTel baggage carries `reservationId` and `routeId` across HTTP
and Kafka, and is also written to MDC for logs:

```yaml
management:
  tracing:
    sampling.probability: ${TRACE_SAMPLING:1.0}
    baggage:
      remote-fields:     [reservationId, routeId]
      correlation.fields: [reservationId, routeId]
```

**Log pattern** —
`logging.pattern.correlation: "[${spring.application.name:},%X{traceId:-},%X{spanId:-}] "`
on every service.

**Outbox hop (the hard part)** — at append time, `OutboxAppender` wraps the persist call
in a Micrometer `Observation` with a `SenderContext`, which injects `traceparent` into
`event_outbox.headers_json`. The poller's `InboxPublisher` reads those headers back into
a `ReceiverContext` so the publish span becomes a child of the original — Tempo renders
one continuous trace across the 30 s outbox gap.

**HTTP & Kafka hops** — instrumented, but NOT free. Two prerequisites that are easy to miss:

1. **Kafka** — the Spring Cloud Stream Kafka binder does **not** observe by default. Every Kafka
   service must set `spring.cloud.stream.kafka.binder.enable-observation: true`, otherwise the
   binder neither injects (producer) nor extracts (consumer) `traceparent` and every hop starts a
   new root trace. The outbox relay must also strip the persisted append-time `traceparent` before
   re-sending (`InboxPublisher#businessHeaders`) so the live producer span injects a fresh context
   and the consumer chains to the publish hop, not the stale append span.
2. **HTTP (RestClient)** — build the client from Boot's `RestClientBuilderConfigurer`
   (`RestClientConfig`), not the bare `RestClient.builder()` static factory; the latter skips the
   `ObservationRestClientCustomizer` and the outbound call goes out untraced.

**Export path** — apps push OTLP spans to `otel-collector:4318` (the central hub), which
tail-samples and forwards to Tempo. Apps do **not** export to Tempo directly. Sampling is
no longer head-based at the app: `probability` stays `1.0` and the collector decides
(→ ADR-0037).

---

## Why

- **One continuous trace per reservation**, including the outbox gap. A P99 exemplar
  click in Prometheus lands on a complete Tempo trace.
- **Search by business key.** `reservationId=12345` joins logs (Loki), spans (Tempo),
  and metric exemplars (Prometheus) in a single Grafana query.
- **Zero tracing code in business logic.** `SenderContext` / `ReceiverContext` live in
  the outbox infrastructure; domain services stay pure Java.
- **Backend-agnostic.** Apps target the collector only; swapping Tempo for Jaeger or a
  SaaS tracer is a collector exporter change, not an app change (→ ADR-0037).

---

## Alternatives

- **B3 only** — Zipkin legacy; W3C is the modern default.
- **Manual header injection per producer** — tedious and drift-prone; Observation API
  already does it.
- **`reservationId` as a metric tag only** — tags must stay low-cardinality (ADR-0005);
  baggage is the correct channel.
- **Drop trace context across the outbox gap** — silently breaks traces exactly where
  operators most need them.

---

## Caveats

- **Baggage is unbounded by design.** Allowlist in `application.yml` is the only guard.
- **Sampling moved to the collector.** Apps emit 100% of spans; the collector
  tail-samples (errors + slow at 100%, rest 1%). Head-based `TRACE_SAMPLING` at the app
  is no longer the knob (→ ADR-0037).
- **Outbox poll interval stretches traces** — append→publish gap up to 30 s. Correct,
  but visually long.
- **B3 propagation not enabled.** Add `management.tracing.propagation.type: [W3C, B3]`
  if a B3-only caller appears.

---

## References

- ADR-0005 — Observability Stack
- ADR-0010 — Transactional Outbox
- ADR-0016 — Kafka Topology
- ADR-0037 — Tail sampling via OTel Collector (current export + sampling authority)
- [W3C Trace Context](https://www.w3.org/TR/trace-context/)
- [Micrometer Observation API](https://micrometer.io/docs/observation)
- [OpenTelemetry Baggage](https://opentelemetry.io/docs/specs/otel/baggage/api/)
