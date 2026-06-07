# 0037 — Tail sampling via OTel Collector (traces sampled, logs full)

**Status:** Accepted
**Date:** 2026-06-13
**Supersedes:** 0035, 0036 (application-log transport + sampling); revises 0017 trace export path

---

## Context

Trace sampling was head-based at 100% (`management.tracing.sampling.probability:
1.0`) — every trace lands in Tempo, which does not scale. We want to keep the
*interesting* traces (errors, slow requests) at 100% and shed the bulk of normal
traffic. That decision depends on properties of the **whole** trace (final
status, total duration), which only exist once the trace is complete — i.e.
**tail** sampling, not head.

Head-style per-traceId hashing (the `probabilistic_sampler attribute_source:
traceID` used for logs in ADR-0035) cannot express "keep 100% of errors": when
the hash is computed the error hasn't happened yet. And two independent samplers
(one on traces, one on logs) diverge — a trace kept by a latency policy can have
its logs hashed into the drop bucket, leaving a trace in Tempo with no logs in
Loki. That breaks debugging exactly when it matters.

---

## Decision

1. **OTel Collector (contrib) becomes the central hub.** Apps push OTLP (traces
   and logs) to the collector; the collector exports to Tempo and Loki.
2. **Traces are tail-sampled** in `tail_sampling` with three OR policies:
   `status_code: [ERROR]` (100% of errors), `latency: threshold_ms: 2000` (100%
   of slow), `probabilistic: 1` (1% of the rest). `decision_wait: 10s` covers
   ~3s end-to-end latency + the default ~5s `BatchSpanProcessor` flush + margin.
3. **Logs are NOT sampled — 100% to Loki.** `tail_sampling` is traces-only and
   the logs pipeline cannot read its decision, so a shared cross-signal decision
   is not achievable with stock components. Keeping all logs is the only design
   that guarantees *trace in Tempo ⇒ its logs in Loki, with no gaps*. Logs in
   Loki are cheap (label index + compression); the expensive signal (traces) is
   the one we shed.
4. **Apps stop deciding sampling.** `probability` stays `1.0` (all spans to the
   collector). `OTEL_BSP_SCHEDULE_DELAY=1000` is set best-effort, but Spring Boot
   drives tracing via actuator (NOT the OTel SDK autoconfigure) and likely ignores
   it — so `decision_wait` is sized for the default ~5s flush, not 1s.
5. **Alloy is retained for infra logs only.** Its OTLP receiver, severity
   filters and per-traceId sampler (ADR-0035) are removed; it keeps scraping
   Docker stdout for non-OTLP containers (kafka, postgres, tempo, loki, grafana,
   prometheus, kafka-ui, otel-collector).
6. **Metrics unchanged** — Prometheus still scrapes `/actuator/prometheus`.
7. **Loki bumped 2.9.4 → 3.x** for the native OTLP endpoint (`/otlp/v1/logs`);
   the deprecated standalone `loki` exporter is avoided in favour of `otlphttp`.

---

## How the codebase enforces this

- `infra/docker/otel-collector/collector.yaml` — receivers, `tail_sampling`
  policies, `otlphttp/tempo` + `otlphttp/loki` exporters, separate traces/logs
  pipelines, `memory_limiter` first in both.
- `infra/docker/docker-compose.observability.yml` — `otel-collector` service on
  `app` + `observability` networks, `mem_limit: 1g` + `GOMEMLIMIT`; `loki`
  image `3.x`; Alloy healthcheck no longer probes :4318.
- `infra/docker/docker-compose.apps.yml` — every app exports to
  `otel-collector:4318` for both traces and logs; `OTEL_BSP_SCHEDULE_DELAY=1000`.
- `infra/docker/alloy/config.alloy` — infra-only pipeline (OTLP/sampler removed).

---

## Why these numbers

- **`decision_wait: 10s`.** Must exceed the time for the last span of a trace to
  reach the collector: max e2e (~3s) + `BatchSpanProcessor` flush. The flush is
  the default ~5s, because Spring Boot's actuator-managed tracing likely ignores
  `OTEL_BSP_SCHEDULE_DELAY` (it does not use the OTel SDK autoconfigure that reads
  it). 3s + 5s + ~2s margin = 10s. Too short → late spans arrive after the
  decision and become orphaned (trace in Tempo missing spans). Too long → more RAM.
- **`memory_limiter` + `mem_limit`.** `tail_sampling` holds every in-flight
  trace's spans in RAM for `decision_wait`. RAM ≈ RPS × spans/trace ×
  decision_wait × ~1.5 KB/span × ~2 (Go GC + index). At 1000 RPS / 10 spans / 10s
  ≈ 300 MB → limits set at `limit_mib: 768`, `GOMEMLIMIT: 768MiB`, container
  `mem_limit: 1g` (ordering: limiter < GOMEMLIMIT ≤ mem_limit). `num_traces:
  100000` caps the in-memory trace map (≈10k at 1000 RPS × 10s, ~10× headroom).

---

## Alternatives

- **Keep head sampling + per-traceId log hashing (ADR-0035).** Cannot keep 100%
  of errors (decision precedes the error) and diverges between signals. Rejected
  for the stated requirement.
- **Sample logs in the collector to mirror the trace decision.** Not possible
  with stock processors — the logs pipeline has no access to the tail decision.
- **Alloy forwards traces to Tempo (status quo) / direct app→Tempo.** No place to
  run policy-based sampling that sees the whole trace. The collector is the only
  component that assembles a full trace before deciding.

---

## Consequences

- New service + new failure mode: collector OOM drops the in-memory buffer
  (= lost traces) — mitigated by `memory_limiter` and limits above. For prod,
  add a load-balancing layer so spans of one trace land on one collector.
- Logs cost does not drop (100% retained). Accepted: correctness of correlation
  over storage savings; an opt-in volume-reduction block is commented in
  `collector.yaml` if needed (with its caveat).
- A new ~10s tail latency before traces appear in Tempo (was near-real-time).
- **Log stream label changes `service` → `service_name`.** Native OTLP→Loki maps
  resource attribute `service.name` to the `service_name` label. The
  `distributed_tracing` log panels and the Alloy infra relabel were updated to
  query/emit `service_name` so both app and infra logs share one convention.
- Loki 3.x upgrade — schema is already `v13`, so structured metadata / OTLP work
  out of the box; watch first-boot logs for config migration warnings.

---

## References

- ADR-0005 — Observability Stack
- ADR-0017 — Distributed Tracing Propagation
- ADR-0032 — Log Collector: Grafana Alloy
- ADR-0035 — OTLP logs + deterministic trace-keyed sampling (app-log path superseded here)
- ADR-0036 — Dual log sinks (Loki sampling superseded here)
- OTel — `tailsamplingprocessor` (policies: status_code, latency, probabilistic)
- Loki 3.0 — native OTLP ingestion (`/otlp/v1/logs`)
