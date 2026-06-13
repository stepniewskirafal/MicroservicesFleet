# 0005 — Observability Stack: PLG + Tempo (OTLP)

**Status:** Accepted — 2026-02-28

> **Note:** Apps now export OTLP to the **OTel Collector** (`otel-collector:4318`), not directly to Tempo/Loki; tail sampling lives in the Collector. The current authority on the trace/log pipeline is **ADR-0037**. This ADR remains the authority on the *stack choice* (Prometheus + Loki + Grafana + Tempo).

---

## Context

All three services must emit traces, metrics, and logs from the first commit, correlated in a single UI. The stack is Spring Boot 3.x / Micrometer first, runs locally via Docker Compose, and must avoid SaaS accounts or vendor agents. Custom business metrics (fees, durations, risk scores, outbox latency) and configurable SLO histogram buckets are required.

---

## Decision

Adopt the **PLG stack — Prometheus + Loki + Grafana — with Grafana Tempo for traces**. Metrics are pulled by Prometheus from `/actuator/prometheus`; traces and logs are pushed via **OTLP** to the OTel Collector, which fans out to Tempo and Loki (pipeline owned by → ADR-0037). Core compose services: `prometheus`, `grafana`, `tempo`, `loki`, `otel-collector`. Log collector (infra stdout) → ADR-0032.

```yaml
management:
  otlp.tracing.endpoint: ${OTEL_EXPORTER_OTLP_TRACES_ENDPOINT:http://otel-collector:4318/v1/traces}
  tracing.sampling.probability: 1.0   # all spans to the collector; tail sampling decides there (ADR-0037)
  metrics.distribution:
    percentiles-histogram:
      starport.reservation.reserve.time: true
    slo:
      starport.reservation.reserve.time: 50ms,100ms,250ms,500ms,1s,2s,5s
```

Actuator endpoint exposure → ADR-0027. Metric naming/cardinality conventions (low-cardinality tags on metrics; high-cardinality on traces) → ADR-0030.

---

## Why

- **Single pane of glass.** Grafana correlates Tempo traces ↔ Prometheus histograms ↔ Loki logs by `traceId`.
- **Native Micrometer / OTLP.** No sidecars or vendor agents; `micrometer-tracing-bridge-otel` exports spans over OTLP to the collector.
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

- ADR-0037 — Tail sampling via OTel Collector (current trace/log pipeline authority)
- ADR-0032 — Log Collector: Grafana Alloy (infra stdout)
- ADR-0030 — Metrics Naming & Cardinality
- ADR-0033 — Exemplars
- Micrometer Observation API — https://micrometer.io/docs/observation
- Grafana Tempo — https://grafana.com/oss/tempo/
