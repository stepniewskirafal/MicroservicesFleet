> **Superseded by ADR-0035 — kept for historical context only.**

# 0034 — Log Sampling at the Collector (Alloy)

**Status:** Superseded by [ADR-0035](0035-otlp-logs-deterministic-sampling.md)
**Date:** 2026-05-25

---

## Context

Log volume from the Spring Boot apps was dominated by INFO/DEBUG lines that
nobody queries unless something breaks — meanwhile Loki ingest happily charges
storage and ingestion budget for every line. We want to keep the full fidelity
that matters for incident response (errors and warnings) but stop paying for
the 90% of healthy chatter.

The collector is **Grafana Alloy** (ADR-0032), which already tails Docker
stdout for every container. The OpenTelemetry Collector in this repo handles
metrics only — it has no logs pipeline — so the sampling decision lives
naturally in Alloy.

---

## Decision

1. **App logs go out as JSON** via `logstash-logback-encoder` (added to the
   parent POM, picked up by every module). A shared `logback-spring.xml` in
   each module activates the encoder outside the `test` profile.
2. **Alloy parses level from the JSON** and promotes it to a stream label.
3. **Sample at the collector**, not in the app:
   - **ERROR / WARN / FATAL → 100%**
   - **INFO / DEBUG / TRACE → 10%** (via `stage.match` + nested `stage.sampling`)
   - **Infra containers (kafka, postgres, loki, …) → 100%** — gated by an
     `app="true"` relabel that only matches our compose services.

Files touched: `pom.xml`, `infra/docker/alloy/config.alloy`, and one
`logback-spring.xml` per app module.

---

## Why

- **JSON over regex.** Parsing `%5p` out of a Logback text line works until
  someone tweaks `logging.pattern.console` and the regex silently fails,
  flipping every error into the "non-error → drop 90%" bucket. JSON gives the
  parser a stable contract and surfaces MDC fields (`traceId`, `spanId`,
  `reservationId`, `routeId`) for free.
- **Collector-side sampling, not app-side.** Apps stay dumb: they log
  everything, the collector decides what to keep. Switching the rate or
  excluding a noisy logger is a config reload, not a redeploy.
- **`app="true"` gate.** Infra containers don't emit our JSON. Without the
  gate their non-JSON lines would parse as `level=""`, match the negation
  selector, and get sampled — silently dropping 90% of Kafka/Postgres warnings.

---

## Alternatives

- **Sample in Logback (`SamplingTurboFilter`/custom appender).** Per-process,
  no cross-replica coordination, harder to tune at runtime.
- **OTLP logs through the OTel Collector.** Would require a logs pipeline +
  Loki exporter in the collector and rewiring every app — high cost for the
  same outcome.
- **Drop levels at the app (`logging.level.root=WARN`).** Loses the INFO
  context entirely; we want the 10% sample for trend visibility.

---

## Consequences

- New stream label `level` adds a small cardinality bump
  (≤5 services × ≤2 replicas × 6 levels ≈ 60 streams). Well below Loki limits.
- Test-profile output stays as Spring Boot's default text pattern — readable
  in IDE / surefire output.
- Dead `management.otlp.logging.endpoint` (pointing at Tempo, which doesn't
  accept logs) was removed from `starport-registry` and `telemetry-pipeline`
  application.yml plus six matching env vars in docker-compose.

---

## References

- ADR-0005 — Observability Stack
- ADR-0032 — Log Collector: Grafana Alloy
- Alloy docs — `loki.process`, `stage.json`, `stage.sampling`
