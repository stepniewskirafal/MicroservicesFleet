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
`logging.pattern.level: "[${spring.application.name:},%X{traceId:-},%X{spanId:-}] "`
on every service.

**Outbox hop (the hard part)** — at append time, `OutboxAppender` wraps the persist call
in a Micrometer `Observation` with a `SenderContext`, which injects `traceparent` into
`event_outbox.headers_json`. The poller's `InboxPublisher` reads those headers back into
a `ReceiverContext` so the publish span becomes a child of the original — Tempo renders
one continuous trace across the 30 s outbox gap.

**HTTP & Kafka hops** — automatic via micrometer-tracing + Spring Cloud Stream
auto-instrumentation. No bespoke code in adapters.

**Sampling** — 100% locally; production overrides via `TRACE_SAMPLING=0.1`.

---

## Why

- **One continuous trace per reservation**, including the outbox gap. A P99 exemplar
  click in Prometheus lands on a complete Tempo trace.
- **Search by business key.** `reservationId=12345` joins logs (Loki), spans (Tempo),
  and metric exemplars (Prometheus) in a single Grafana query.
- **Zero tracing code in business logic.** `SenderContext` / `ReceiverContext` live in
  the outbox infrastructure; domain services stay pure Java.
- **Backend-agnostic.** Swapping Tempo for Jaeger or a SaaS tracer = change the OTLP
  endpoint.

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
- **100% sampling is expensive in production** — set `TRACE_SAMPLING=0.1` or lower.
- **Outbox poll interval stretches traces** — append→publish gap up to 30 s. Correct,
  but visually long.
- **B3 propagation not enabled.** Add `management.tracing.propagation.type: [W3C, B3]`
  if a B3-only caller appears.

---

## References

- ADR-0005 — Observability Stack
- ADR-0010 — Transactional Outbox
- ADR-0016 — Kafka Topology
- [W3C Trace Context](https://www.w3.org/TR/trace-context/)
- [Micrometer Observation API](https://micrometer.io/docs/observation)
- [OpenTelemetry Baggage](https://opentelemetry.io/docs/specs/otel/baggage/api/)
