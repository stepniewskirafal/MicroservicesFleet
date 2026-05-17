# 0005 — Observability Stack: PLG + Tempo (OTLP)

**Status:** Accepted — 2026-02-28

---

## Context

All three services must emit traces, metrics, and logs from the first commit, correlated in a single UI. The stack is Spring Boot 3.x / Micrometer first, runs locally via Docker Compose, and must avoid SaaS accounts or vendor agents. Custom business metrics (fees, durations, risk scores, outbox latency) and configurable SLO histogram buckets are required.

---

## Decision

Adopt the **PLG stack — Prometheus + Loki + Grafana — with Grafana Tempo for traces**, all reached via **OTLP**. Five compose services: `prometheus`, `grafana`, `tempo`, `loki`, and `zipkin` (kept as a secondary trace sink during transition). Log collector is covered by ADR-0032.

```yaml
management:
  opentelemetry:
    tracing.export.otlp.endpoint: http://tempo:4318/v1/traces
  metrics.distribution:
    percentiles-histogram:
      starport.reservation.reserve.time: true
    slo:
      starport.reservation.reserve.time: 50ms,100ms,250ms,500ms,1s,2s,5s
  tracing.sampling.probability: 1.0
  endpoints.web.exposure.include: health,info,metrics,prometheus
```

Conventions: low-cardinality tags on Prometheus metrics only; high-cardinality (e.g. `reservationId`) goes to `highCardinalityKeyValue` on the trace.

---

## Why

- **Single pane of glass.** Grafana correlates Tempo traces ↔ Prometheus histograms ↔ Loki logs by `traceId`.
- **Native Micrometer / OTLP.** No sidecars or vendor agents; `micrometer-tracing-bridge-otel` ships spans directly.
- **Open-source, zero lock-in.** No API keys, no per-host billing; runs offline.
- **SLO buckets via config.** Histogram boundaries change without recompiling.
- **Exemplars enabled** (`--enable-feature=exemplar-storage`) — drill from a slow P99 sample to the exact trace.

---

## Alternatives

- **ELK + Jaeger** — heavy (Elasticsearch ≥ 1 GB RAM), SSPL licence requires legal review, weaker Micrometer story.
- **Datadog / New Relic (SaaS)** — great UX but requires cloud accounts and per-host billing; blocks offline dev.
- **Zipkin + Prometheus only** — minimal, but logs stay in container stdout with no trace correlation.

---

## References

- ADR-0004 — Messaging vs HTTP
- ADR-0032 — Log Collector: Grafana Alloy
- Micrometer Observation API — https://micrometer.io/docs/observation
- Grafana Tempo — https://grafana.com/oss/tempo/
- Prometheus exemplars — https://prometheus.io/docs/practices/exemplars/
