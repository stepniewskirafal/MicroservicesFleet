# 0036 — Dual log sinks: full firehose on stdout, 10% sampled in Loki

**Status:** Accepted
**Date:** 2026-06-07
**Related:** 0035 (OTLP sampling), 0032 (Alloy)

---

## Context

ADR-0035 has Alloy drop ~90% of INFO/DEBUG (deterministic 10% per traceId)
before they reach Loki. That raises an obvious worry: *are those logs lost?*

They are not — because each app's `logback-spring.xml` writes every record to
**two independent appenders**, and only one of them is sampled. So far this was
an incidental detail buried in the logback comment and ADR-0035 point 5. This
ADR makes the two-sink, two-tier strategy an explicit, named decision so nobody
"fixes" it by deleting the console appender to stop the "duplication".

---

## Decision

Every app keeps **both** appenders on the root logger:

```
<root level="INFO">
    <appender-ref ref="CONSOLE"/>   <!-- full firehose → stdout -->
    <appender-ref ref="OTEL"/>      <!-- OTLP → Alloy → Loki (sampled) -->
</root>
```

Two parallel, independent sinks per log line:

| Sink | Path | Volume | Cost | Lifetime |
|------|------|--------|------|----------|
| **stdout** (CONSOLE) | container stdout, read via `docker logs` | **100%** | cheap, unindexed | container lifetime (dev) |
| **Loki** (OTEL→OTLP→Alloy) | sampled per ADR-0035 | INFO/DEBUG **10%**, WARN/ERROR + untraced **100%** | expensive, indexed | Loki retention |

Alloy never touches app stdout — `discovery.relabel` drops our apps from the
Docker scrape (ADR-0035), so the two paths never overlap and Loki is not double-fed.

A log "dropped" by Alloy is discarded in memory (no dead-letter, no spill) — but
the **same line still exists on stdout at 100%**. Dropped-from-Loki ≠ lost.

---

## Why

- **Loki is the expensive tier.** Indexed storage + per-stream cardinality +
  retention all cost. Put only the *valuable* subset there: all errors, all
  whole-traces that survive sampling, and a 10% representative sample of INFO/
  DEBUG chatter — enough for dashboards and trend search, not the full flood.
- **stdout is the cheap firehose.** Plain text, no index, no cardinality. Keep
  100% for break-glass debugging of a specific request that Loki happened to
  sample out.
- **Decouples "what we emit" from "what we keep searchable."** Apps log freely;
  the retention/cost decision lives entirely in Alloy, not in app code.
- **Sampling is non-destructive at the source.** Because the console copy is
  independent, raising/lowering `sampling_percentage` never risks the firehose.

---

## Consequences

- **Dev retention is the container's lifetime.** `docker compose down` (or
  removing a container) wipes stdout. Acceptable for local work; the full record
  is there while the stack is up.
- **Prod must collect stdout separately** with real retention/rotation (log
  driver or a sidecar/agent) if the 100% firehose is to outlive a container.
  This ADR only guarantees the *emit*, not durable storage of the cheap tier.
- **Debugging a sampled-out request** means reaching for `docker logs`, not Loki.
  Errors and whole sampled-in traces are still complete in Loki.
- **Two writes per line** — negligible CPU; both appenders are async-friendly.

---

## Alternatives

- **Single sink (OTLP→Loki only, drop CONSOLE).** A sampled-out line is then
  gone everywhere — no break-glass. Rejected: loses the firehose safety net.
- **Console only (no Loki).** No central search, dashboards, or cross-service
  correlation. Rejected.
- **Sample in the app (don't emit the 90%).** Removes the stdout copy too and
  bakes the rate into app code. Rejected: couples cost policy to apps and kills
  break-glass.

---

## References

- ADR-0035 — OTLP push + deterministic trace-keyed sampling
- ADR-0032 — Log Collector: Grafana Alloy
- `*/src/main/resources/logback-spring.xml` — the two appenders
- `infra/docker/alloy/CONFIG-EXPLAINED.md` — line-by-line Alloy walkthrough
- `infra/docker/alloy/VERIFY-LOGGING.md` — how to verify the split
