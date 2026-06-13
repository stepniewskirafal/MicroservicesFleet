# 0030 — Metrics Naming & Cardinality Discipline (Micrometer + Observation API)

**Status:** Accepted
**Date:** 2026-04-17

---

## Context

ADR-0005 picked the PLG stack but did not standardise metric names, tags, or how
high-cardinality IDs (`reservationId`, `routeId`) are kept out of Prometheus. Without a
convention, names drift across services, ID-shaped labels explode cardinality, and the
trace/metric split becomes incoherent.

---

## Decision

**Naming.** `<domain>.<subsystem>.<outcome>` in Micrometer dot-notation; the Prometheus
exporter translates dots to underscores automatically. Namespace is the business domain
(`reservations`, `routes`, `telemetry`), **not** the service name — so the metric
survives the code moving services.

**Types.** `Counter` for monotonic events, `Timer` for latency, `DistributionSummary`
for non-time numerical distributions (declare `baseUnit("cr")`, `baseUnit("hours")` so
Grafana shows units), `Gauge` for current queriable values.

**Use the Observation API for anything with latency** — it produces a histogram, a
Tempo span, traceId/spanId log correlation, and automatic error tagging in one call:

```java
Observation.createNotStarted("reservations.outbox.append", observationRegistry)
    .lowCardinalityKeyValue("binding", bindingName)             // Prometheus + Tempo
    .lowCardinalityKeyValue("eventType", eventType)
    .highCardinalityKeyValue("reservationId", String.valueOf(id)) // Tempo only
    .observe(() -> doAppend());
```

**Two strict cardinality tiers.** `lowCardinalityKeyValue` → Prometheus label + trace
tag, must be bounded (≤ ~50 values). `highCardinalityKeyValue` → trace tag only, never
exported to Prometheus. Established low-cardinality vocabulary: `starport`, `shipClass`,
`outcome`, `eventType`, `binding`, `errorType`, `reason`, `severity`. ID-shaped values
(`reservationId`, `originPortId`, `destinationPortId`) are always high-cardinality —
e.g. `MicrometerRouteMetricsAdapter` puts port IDs on the span, never on a Prometheus
label.

**`starport` is whitelisted, not trusted.** A `starport` tag comes from a request path
variable, so an unbounded value could explode cardinality. `StarportTagSanitizer`
(`StarportCodeAllowlist`) loads the known starport codes on `ApplicationReadyEvent` and
maps any unknown code to the literal `"other"` before it becomes the `starport` label on
the `reservations` counter (`starport`, `shipClass`, `outcome`). Tags are fail-closed by
construction, not by reviewer vigilance.

**SLO histogram buckets live in `application.yml`, not code** — operations tunes what
"fast" means without a rebuild (ADR-0005):

```yaml
management.metrics.distribution.slo:
  reservations.hold.allocate: 5ms,10ms,25ms,50ms,100ms,250ms,500ms,1s
  reservations.fees.calculate: 1ms,5ms,10ms,50ms,100ms
  reservations.outbox.append:  1ms,5ms,10ms,25ms,50ms,100ms,250ms,500ms
```

**Metric names are contracts, tested.** Every critical observation has a
`TestObservationRegistry` assertion (ADR-0029) so a rename or a tag-tier flip fails
in CI, not silently on a dashboard.

---

## Why

- **Coherent dashboards.** Shared namespace + tag vocabulary means Grafana template
  variables (`shipClass`, `starport`) work across panels.
- **Bounded Prometheus cardinality.** ID-shaped values cannot become labels; memory is
  proportional to documented dimensions, not traffic.
- **Powerful trace navigation.** `reservationId` is in Tempo on every span; click a
  slow Prometheus exemplar → land on the exact reservation (ADR-0017).
- **Observation API is one-stop.** Hand-rolling `Timer` + manual span + manual tag copy
  at every call site duplicates instrumentation; `Observation` does it once.

---

## Alternatives

- **Flat names without namespaces** — clashes as the fleet grows; Grafana templating
  breaks.
- **Underscore names in code** — Micrometer prefers dots; the exporter handles
  translation.
- **Raw `Timer` + manual span** — duplicates instrumentation at every call site.
- **Per-service `Tags.of(...)` constant classes** — small vocabulary doesn't justify
  the indirection; revisit if tag variants grow past ~15.

---

## References

- ADR-0005 — Observability Stack (PLG + Micrometer)
- ADR-0017 — Tracing Propagation (metric exemplars ↔ trace IDs)
- ADR-0029 — Acceptance Test Fixtures (`TestObservationRegistry` usage)
- Micrometer Observation API — https://micrometer.io/docs/observation
- Prometheus histograms & summaries — https://prometheus.io/docs/practices/histograms/
