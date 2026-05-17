# 0032 — Log Collector: Grafana Alloy (replaces Promtail)

**Status:** Accepted
**Date:** 2026-05-17

---

## Context

ADR-0005 fixed the stack as PLG + Tempo but did not pin the log collector. We were
running Promtail; Grafana Labs has since moved Promtail to maintenance and consolidated
all its agents into **Grafana Alloy**.

---

## Decision

Replace the `promtail` sidecar with `alloy` in `infra/docker/docker-compose.yml`
(`grafana/alloy:latest`, debug UI on `:12345`) and ship the pipeline as
`infra/docker/alloy/config.alloy` — the same Docker discovery → relabel → Loki write
flow, with identical labels (`container`, `stream`, `service`) so dashboards and LogQL
queries stay unchanged. The old `infra/docker/promtail/` directory is deleted.

---

## Why

- **Promtail is on a maintenance branch.** New collector features (OTLP-native log
  handling, unified config) land only in Alloy.
- **One agent for future signals.** Adding Pyroscope profiles or OTel-style metric
  processing later is a `+N components` change in the same file — no second sidecar.
- **Cut is collector-only.** Loki, Grafana provisioning, every LogQL query, and the
  "Logs for this span" button keep working without modification.
- **`latest` tag** matches `prom/prometheus:latest` already in the same compose file;
  no production target requires pinning.

---

## Alternatives

- **Stay on Promtail** — zero-change but the migration cost grows with each new rule.
- **OpenTelemetry Collector** — vendor-neutral, but its Loki story is less polished
  than Alloy's native `loki.source.docker` + `loki.write`.
- **Fluent Bit** — fast and mature, but no first-class Grafana integration.

---

## References

- ADR-0005 — Observability Stack (this ADR refines the collector choice only).
- Grafana Alloy docs — https://grafana.com/docs/alloy/latest/
