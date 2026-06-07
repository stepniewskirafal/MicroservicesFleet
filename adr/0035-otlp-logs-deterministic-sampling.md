# 0035 — Logs via OTLP push + deterministic trace-keyed sampling

**Status:** Accepted
**Date:** 2026-05-27
**Supersedes:** 0034

---

## Context

ADR-0034 sampled non-error logs at 10% inside Alloy with `stage.sampling`, which
rolls each line independently. That gives the right aggregate volume but rips
individual traces apart: 20 INFO logs from one traceId end up as ~2 logs in
Loki, not "all-or-nothing". You can't reassemble what happened during a single
request, and the set of traceIds present in Loki has no relation to the set of
traceIds present in Tempo (different hash functions, different sampling rolls).

We need: same 10% volume, but **deterministic per traceId** — and ideally
**consistent** with the trace head-sampler so a trace sampled in Tempo always
has its logs in Loki, and vice versa.

---

## Decision

1. **Apps push logs as OTLP** via `opentelemetry-logback-appender-1.0` (added
   to parent POM, also declared locally in `eureka-server` which doesn't
   inherit gt-parent — ADR-0025). Spring Boot 3.5 auto-installs the
   `OpenTelemetryAppender` when the artifact is on the classpath; we just
   reference it in each `logback-spring.xml`.
2. **Logs endpoint** = Alloy on `:4318` (HTTP), wired via
   `management.otlp.logging.endpoint` per app and
   `OTEL_EXPORTER_OTLP_LOGS_ENDPOINT` in docker-compose.
3. **Alloy pipeline**:
   - `otelcol.receiver.otlp` (gRPC + HTTP) fans the same batch out to two
     complementary `otelcol.processor.filter` branches keyed on
     `severity_number`: `warn_up` keeps ≥13 (WARN/ERROR/FATAL) and bypasses the
     sampler; `info_down` keeps <13 (INFO/DEBUG/TRACE) and flows to →
   - `otelcol.processor.probabilistic_sampler` with `mode = "equalizing"`,
     `attribute_source = "traceID"`, `sampling_percentage = 10`,
     `fail_closed = false` →
   - `otelcol.exporter.loki` → `loki.write`.
   - Two complementary filters rather than `otelcol.connector.routing`: each
     record matches exactly one branch (no duplication), and `filter` ships in
     the base Alloy image without the extra connector wiring.
4. **Infra containers** (kafka, postgres, loki, tempo, grafana, prometheus,
   kafka-ui) still go through the Docker stdout scrape path — they don't speak
   OTLP. The `discovery.relabel` now `drop`s our apps instead of gating them.
5. **stdout from apps stays** for `docker logs` debugging, but Alloy no longer
   scrapes it.

---

## Why

- **Determinism.** `mode = "equalizing"` derives a per-trace randomness value
  (from W3C `tracestate` `rv:`/`th:` if the upstream sampler set them, otherwise
  from the traceId itself) using OTel's consistent probability sampling scheme.
  The same traceId always maps to the same keep/drop decision. Whole traces in
  or out, never half.
- **Consistency with trace head-sampling (best-effort).** If the trace
  head-sampler also emits consistent-probability `tracestate`, both samplers at
  the same `p` agree on a trace. Note: today `management.tracing.sampling.
  probability` defaults to `1.0` (all traces to Tempo) and the default OTel SDK
  sampler does not write `rv:`/`th:`, so the logs sampler falls back to hashing
  the traceId — still deterministic per trace, but not provably the *same* roll
  as Tempo. Revisit if we move trace sampling below 100%.
- **Logs without a traceId are kept, not dropped.** Startup, `@Scheduled` and
  other non-request logs carry no traceId. `fail_closed = false` lets them pass
  (100%) instead of being silently discarded as "missing randomness" — the
  default `fail_closed = true` would drop all boot/background INFO+DEBUG.
- **OTLP unifies transport.** Traces already go OTLP to Tempo; logs now do the
  same to Alloy. Single appender model, no dependency on Docker socket for our
  apps, easier to lift to a real collector (or remote) later.
- **Bypass on severity, not regex.** Routing reads OTel `severity_number`
  (structured) rather than regex-matching a `level=` label parsed from JSON.
  Less brittle to log format changes.

---

## Alternatives

- **Keep stdout scrape, swap `stage.sampling` for hash-of-traceId templating.**
  ~20 lines of `stage.template`/`stage.drop`. Wins on simplicity but uses a
  different hash than the OTel SDK head-sampler — sampled-in traces may still
  have orphan logs. Rejected: solves only half the problem.
- **Tail-sampling in OTel Collector.** Buffers the full trace before deciding.
  Adds memory + latency, requires complete trace assembly, overkill for
  rate-shedding INFO chatter. Worth revisiting if we later need policy-based
  sampling (e.g., always keep traces containing a 5xx span).

---

## Consequences

- New dependency: `io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0`
  (managed via `opentelemetry-instrumentation-bom:2.10.0`). Removed:
  `logstash-logback-encoder` (no longer needed, no JSON parser to feed).
- App restart loses buffered, not-yet-flushed log batches from memory — in dev
  acceptable; for prod we'd configure a BatchLogProcessor with persistence or
  a sidecar collector.
- Alloy now has a healthcheck and apps `depends_on: alloy: service_healthy`
  to avoid startup losses on cold start.
- `level` is no longer a Loki stream label — querying by level becomes
  `{service_name="…"} | severity_text="ERROR"` (line filter on OTel attribute)
  instead of `{service="…",level="ERROR"}`. Existing dashboards may need
  updating.

---

## References

- ADR-0005 — Observability Stack
- ADR-0032 — Log Collector: Grafana Alloy
- ADR-0034 — Log Sampling at the Collector (superseded by this)
- OTel — Consistent Probability Sampling spec
  (W3C tracestate `th:`/`rv:` fields, `probabilistic_sampler` `mode=equalizing`)
- Spring Boot — `management.otlp.logging.*` (auto-config of `OpenTelemetryAppender`)
