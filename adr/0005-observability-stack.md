# 0005 — Observability Stack

**Status:** Accepted — 2026-02-28
**Deciders:** Team
**Technical Story:** Requirement — observability (traces, metrics, logs) must be in place from day zero across all services.

---

## Context and Problem Statement

All three services must expose traces, metrics, and logs from the first commit. The team needs to correlate a slow reservation request with its database queries, Kafka outbox flush, and route planning call to Trade Route Planner. Which observability backend best fits a Spring Boot 3.x / Micrometer-first stack, is runnable locally via Docker Compose, and requires minimal custom integration code?

---

## Decision Drivers

* Spring Boot 3.x ships with Micrometer Observation API and Micrometer Tracing as first-class features — the stack must capitalise on this.
* Local development uses Docker Compose; no managed cloud accounts or SaaS licenses should be required to run the full stack.
* Traces must be correlated with metrics and logs in a single UI (Grafana).
* Custom business metrics (fee amounts, reservation durations, route risk scores, outbox latency) must be expressible as Micrometer `Counter`, `Timer`, and `DistributionSummary`.
* SLO histograms (50 ms / 100 ms / 250 ms / 500 ms / 1 s / 2 s / 5 s buckets) must be configurable without code changes.
* The OTel ecosystem must be reachable via OTLP HTTP — no vendor-specific agents or sidecars.

---

## Considered Options

1. **PLG stack — Prometheus + Loki + Grafana with Tempo for traces (OpenTelemetry/OTLP)** — self-hosted, open-source, Docker Compose friendly.
2. **ELK stack — Elasticsearch + Logstash + Kibana with Jaeger for traces** — mature, feature-rich, but heavyweight and licence-evolving.
3. **Datadog / New Relic (SaaS)** — managed, rich UX, but requires cloud account, API keys, and per-host billing unsuitable for a local demo.
4. **Zipkin only (traces) + Prometheus (metrics) without centralised log aggregation** — minimal, but logs are siloed to container stdout with no correlation.

---

## Decision Outcome

**Chosen option: PLG stack (Prometheus + Loki + Grafana) with Grafana Tempo for distributed tracing, all via OTLP.**

The stack is fully open-source, trivially composable in Docker Compose (`docker-compose.yml` in `starport-registry/docker/`), and integrates with Micrometer natively. A single Grafana UI provides traces (Tempo), metrics (Prometheus), and logs (Loki) with trace-to-log and metric-to-trace correlation.

Zipkin is kept as a secondary trace sink for compatibility during the transition period.

### Positive Consequences

* **Single pane of glass.** Grafana dashboards correlate `reservationId` traces with Prometheus histograms and Loki log lines in one UI.
* **Zero vendor lock-in.** All backends are open-source; no API keys, no billing.
* **OTLP-first.** `micrometer-tracing-bridge-otel` exports spans to Tempo at `http://tempo:4318/v1/traces`. Adding a new exporter (e.g., Jaeger) requires only an environment variable change.
* **SLO buckets via config.** `management.metrics.distribution.slo.*` in `application.yml` drives Prometheus histogram buckets without code changes.
* **Exemplars.** Prometheus is launched with `--enable-feature=exemplar-storage`; spans referenced from histogram samples enable drill-down from a slow P99 bucket to the exact trace.
* **Business metrics.** `DistributionSummary` for fee amounts (`reservations.fees.calculated.amount`), billing hours, and route risk; `Timer` observations on `reserveBay`, `feecalculator`, `holdReservation`, and outbox append operations.
* **Observability-as-code.** Grafana dashboards and Prometheus scrape configs are version-controlled under `starport-registry/docker/`.

### Negative Consequences

* **Operational complexity.** Five infrastructure services (Prometheus, Grafana, Tempo, Loki, Zipkin) must all be started and kept healthy locally.
* **Resource usage.** The full observability stack adds several hundred MB RAM to the local Docker environment.
* **Loki log correlation.** Structured JSON logging with `traceId`/`spanId` fields must be enabled explicitly; plain text logs lose correlation context.
* **OTLP log export is experimental.** `management.opentelemetry.logging.export.otlp.endpoint` is a new feature; log export pipelines may need tuning as the Spring Boot / Micrometer API stabilises.
* **Cardinality discipline required.** High-cardinality labels (e.g., per-reservation IDs) must be kept in `highCardinalityKeyValue` observations only — not in Prometheus metric labels — to avoid memory explosion in Prometheus.

---

## Pros and Cons of the Options

### Option 1 — PLG + Tempo (OTLP) ✅

* Good, because all open-source, Docker Compose runnable, vendor-neutral.
* Good, because native Micrometer integration; no extra agent or sidecar.
* Good, because Grafana unifies traces, metrics, and logs with exemplar correlation.
* Bad, because five additional containers increase local resource usage.
* Bad, because teams must learn Grafana/Loki/Tempo configuration.

### Option 2 — ELK + Jaeger

* Good, because battle-tested in enterprise environments.
* Good, because Elasticsearch full-text search is powerful for log analysis.
* Bad, because Elasticsearch is resource-heavy (1–2 GB RAM minimum).
* Bad, because Elastic licence changes (SSPL) require legal review.
* Bad, because Jaeger integration with Micrometer requires additional bridging.

### Option 3 — Datadog / New Relic (SaaS)

* Good, because excellent UX, AI-assisted root cause analysis, low setup overhead.
* Bad, because requires cloud account and per-host licensing — blocks local offline development.
* Bad, because vendor lock-in for a demo/educational project.

### Option 4 — Zipkin + Prometheus (no log aggregation)

* Good, because minimal footprint (two containers).
* Good, because Zipkin is natively supported by Micrometer.
* Bad, because logs remain in container stdout — no structured correlation with traces.
* Bad, because no log search or alerting on log patterns.

---

## Implementation

### Infrastructure (Docker Compose)

| Service | Image | Port | Role |
|---|---|---|---|
| `prometheus` | `prom/prometheus:v2.54.1` | 9090 | Metrics scrape + SLO storage |
| `grafana` | `grafana/grafana:10.4.10` | 3000 | Unified dashboard UI |
| `tempo` | `grafana/tempo:2.4.2` | 3200 / 4318 | Distributed trace backend (OTLP HTTP) |
| `loki` | `grafana/loki:3.1.2` | 3100 | Log aggregation |
| `zipkin` | `openzipkin/zipkin:2.24` | 9411 | Secondary trace sink (compat) |

### Spring Boot configuration (key excerpts from `application.yml`)

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

### Instrumentation conventions

* Use `Observation.createNotStarted(name, registry)` for all significant operations.
* Low-cardinality tags only on Prometheus metrics (e.g., `shipClass`, `starport`).
* High-cardinality values (e.g., `reservationId`) in `highCardinalityKeyValue` — stored only in traces, not metrics.
* `DistributionSummary` for monetary amounts (`pln`) and durations (`hours`).

---

## References

* ADR-0004 — When to Use Messaging vs HTTP
* Micrometer Observation API — https://micrometer.io/docs/observation
* Grafana Tempo — https://grafana.com/oss/tempo/
* Prometheus exemplars — https://prometheus.io/docs/practices/exemplars/
* OpenTelemetry OTLP — https://opentelemetry.io/docs/specs/otlp/
