# 0030 — Metrics Naming & Cardinality Discipline (Micrometer + Observation API)

**Status:** Accepted
**Date:** 2026-04-17

---

## Context

ADR-0005 picked the PLG stack and declared that "custom business metrics must be
expressible as Micrometer `Counter`, `Timer`, and `DistributionSummary`". It did not
standardise **what those metrics are called, what tags they carry, or how
high-cardinality data (reservation IDs, route IDs) is kept out of Prometheus**.

Getting this wrong is expensive:

- Inconsistent names (`reservations.created` in service A, `reservation_created_total` in
  service B) break dashboards and make PromQL queries unreadable.
- A single `reservationId` tag in a Prometheus label blows up cardinality — a hundred
  thousand reservations become a hundred thousand distinct time series, eating GB of RAM
  on the Prometheus side.
- Mixing tags that should be on the metric with information that belongs only in the
  trace produces a weaker story in both places: dashboards can't group by something that
  was hidden in baggage, traces miss a low-cardinality dimension that would have enabled
  filtering.

This ADR captures the conventions that are already in the code so they stay consistent as
the fleet grows.

---

## Decision

### 1. Namespace + dot-notation naming

Metric names follow `<namespace>.<subsystem>.<outcome>[.unit]`:

```
reservations.created.total
reservations.hold.released
reservations.fees.calculated.amount       (DistributionSummary, baseUnit "cr")
reservations.fees.calculated.hours        (DistributionSummary, baseUnit "hours")
reservations.route.plan.success
reservations.route.plan.errors
reservations.inbox.poll.duration
reservations.inbox.poll.batch.size        (DistributionSummary, baseUnit "events")
reservations.outbox.dead.letter

routes.planned.count
routes.risk.score                          (DistributionSummary)
routes.eta.hours                           (DistributionSummary)

telemetry.messages.received
telemetry.messages.invalid
telemetry.anomalies.detected
```

Namespace is the **business domain**, not the service name. `reservations.*` is the
concept, owned today by starport-registry; if it ever moved, the metric keeps its name.

Dot-notation is the Micrometer canonical form; the Prometheus exporter translates it to
`reservations_created_total` automatically — **do not** write the Prometheus form in
code.

### 2. Three metric types, pick the right one

| Type                  | Use for                                             | Example                                 |
|---|---|---|
| `Counter`             | Monotonically increasing events                     | `reservations.created.total`            |
| `Timer`               | How long something took (rate + histogram + percentiles) | `reservations.hold.allocate`            |
| `DistributionSummary` | Non-time numerical distributions (amounts, sizes)   | `reservations.fees.calculated.amount`   |
| `Gauge`               | Current value of a queriable quantity               | (not used today — candidates: outbox backlog size) |

The base unit is declared on `DistributionSummary` (`baseUnit("cr")`, `baseUnit("hours")`)
so Grafana panels render `50 cr` instead of `50`.

### 3. Micrometer Observation API for operations that span time

The `MeterRegistry.counter(...)` API is fine for "something happened". For "something is
happening and I want latency, errors, spans, and metrics together" — use
`Observation.createNotStarted(name, observationRegistry)`:

```java
// starport-registry/.../service/holdreservation/CreateHoldReservationService.java
Observation.createNotStarted("reservations.hold.allocate", observationRegistry)
        .lowCardinalityKeyValue("starport", command.destinationStarportCode())
        .lowCardinalityKeyValue("shipClass", command.shipClass().name())
        .observe(() -> doAllocate(command));
```

This gives, in one call:

- A Prometheus histogram keyed on the low-cardinality tags.
- A Tempo span named `reservations.hold.allocate` with the same tags.
- A tracing context (`traceId`, `spanId`) correlated with logs (ADR-0017).
- Automatic error tagging (exceptions become `outcome=ERROR`).

Rule: **if the operation has latency you care about, use `Observation`, not a raw
`Timer`**. If it is an atomic event (outbox row written, message dropped), use a
`Counter`.

### 4. Two cardinality tiers, strictly separated

```java
Observation.createNotStarted("reservations.outbox.append", observationRegistry)
        .lowCardinalityKeyValue("binding", bindingName)                 // goes to Prometheus + Tempo
        .lowCardinalityKeyValue("eventType", eventType)
        .highCardinalityKeyValue("reservationId", String.valueOf(id))   // Tempo only — NEVER Prometheus
        .observe(...);
```

- `lowCardinalityKeyValue` — becomes a Prometheus label **and** a trace tag.
- `highCardinalityKeyValue` — becomes a trace tag only; never exported as a Prometheus
  label.

Rules:

- Low-cardinality values must be **bounded** and **small**. Good: `shipClass` (6 values),
  `starport` code (tens), `outcome` (`SUCCESS`/`ERROR`). Bad: anything user-provided or
  ID-shaped.
- High-cardinality values belong only in traces. `reservationId`, `routeId`, `outboxId`.
- Low-cardinality **must** equal `≤ ~50 unique values per dimension**; crossing that
  threshold is a code smell even if technically bounded.

The established low-cardinality vocabulary across the fleet:

```
starport        — starport code (~10 values)
shipClass       — enum (6 values)
outcome         — SUCCESS / ERROR / REJECTED / TIMEOUT (< 10 values)
eventType       — ReservationConfirmed / TariffCalculated / etc. (< 10)
binding         — Spring Cloud Stream binding name (< 20)
errorType       — domain / infrastructure / circuit_open (< 10)
reason          — why a hold was released (< 10)
severity        — WARNING / CRITICAL (2)
```

New tags must fit this shape or not be added.

### 5. SLO buckets are configuration, not code

Histogram buckets are configured per-metric in `application.yml` (ADR-0005). The code
only produces the `Observation`; operators decide what "fast" means:

```yaml
# starport-registry/src/main/resources/application.yml:148-155
management:
  metrics:
    distribution:
      percentiles-histogram:
        reservations.hold.allocate: true
        reservations.fees.calculate: true
        reservations.route.plan: true
      slo:
        reservations.hold.allocate: 10ms, 50ms, 100ms, 500ms, 1s, 2s
        reservations.fees.calculate: 1ms, 5ms, 10ms, 50ms, 100ms
        reservations.route.plan: 50ms, 100ms, 200ms, 500ms, 1s, 2s, 5s
```

Why this matters:

- SLO buckets drive Prometheus histogram storage — each bucket is one time series per
  tag combination.
- Too many buckets multiplied by too many tags = Prometheus OOM.
- Tuning is an operations decision (what latency band matters to users), not a code
  decision.

### 6. Observability regressions are a test failure

ADR-0029 establishes `TestObservationRegistry`. Every critical metric has a test:

```java
TestObservationRegistryAssert.assertThat(registry)
    .hasObservationWithNameEqualTo("reservations.fees.calculate")
    .hasBeenStarted()
    .hasBeenStopped()
    .hasLowCardinalityKeyValue("shipClass", "C");
```

A PR that renames `reservations.fees.calculate` to `fees.calculate` breaks this test —
and that is the **point**. Metric names are contracts with dashboards and alerts; they
change only with the same discipline as public API contracts.

---

## How the codebase enforces this

1. **Namespace consistency** — a grep across all three services for
   `meterRegistry.counter(` / `createNotStarted(` shows exclusively `reservations.*`,
   `routes.*`, or `telemetry.*`. No freelancing.
2. **Tag method choice** — the `highCardinalityKeyValue` calls are restricted to the
   outbox append (`reservationId`) and publisher (`outboxId`) — i.e., exactly the places
   where a unique business key genuinely aids trace navigation.
3. **Unit declarations** — `DistributionSummary.builder(...).baseUnit("cr")` in
   `FeeCalculatorService` and peers. Units never appear as magic strings in code.
4. **YAML owns SLO** — no `percentiles-histogram: true` hard-coded in Java (the
   Micrometer API supports it; we deliberately do not use it). This keeps
   operations-facing tuning out of compile units.
5. **Observability tests** — `*ObservabilityTest.java` classes exist alongside the
   services they cover. They run under surefire (unit speed) with
   `TestObservationRegistry`, not a real backend.

---

## Consequences

### Benefits

- **Dashboards stay coherent.** A new Grafana panel uses the same naming prefix everyone
  else does; template variables (`shipClass`, `starport`) work across panels without
  per-panel translation.
- **Prometheus cardinality stays bounded.** ID-shaped values never become labels;
  Prometheus memory is proportional to the product of documented low-cardinality
  dimensions, not traffic.
- **Trace navigation stays powerful.** `reservationId` is available in Tempo on every
  span, so clicking a slow exemplar in Prometheus → pivoting to the trace → seeing the
  specific reservation is a one-click path (ADR-0017).
- **Metric contracts are tested.** Rename / drop a span → a test fails. Alerts and
  dashboards stop silently decaying.
- **Units are visible.** `baseUnit("cr")`, `baseUnit("hours")` — Grafana renders them;
  operators never squint at an unlabelled bar chart.

### Trade-offs

- **Two-tier tag API is easy to misuse.** Calling `lowCardinalityKeyValue("id", id)` is
  a one-line mistake with a cardinality-explosion consequence. Mitigation: tests assert
  the tag level; code review watches for `id`-shaped tag names.
- **Observation pattern is verbose.** Five-line observation blocks around every
  operation. For simple counters, a raw `Counter` is still fine; overuse of
  `Observation` is a style choice, not a rule.
- **SLO config is operations-owned, code is developer-owned.** Changing histogram buckets
  does not require a code change — good for tuning, bad for accidentally-deleted buckets
  going unnoticed. Mitigation: `application.yml` is reviewed like code; alerting depends
  on specific buckets existing.
- **Dot-notation vs Prometheus underscore translation is invisible.** A developer who
  greps for `reservations_created_total` (the Prometheus name) finds nothing. Onboarding
  hazard; documented here.
- **No explicit cardinality budget.** The "≤ ~50 unique values per dimension" rule is a
  heuristic. Enforcing it mechanically would require a Prometheus recording rule that
  alerts on `count(count by (__name__, <label>) (...))` — future work.

### Explicit non-decisions

- **Gauge usage.** Not used today. Candidates: `event_outbox` `PENDING` backlog size,
  Eureka-registered-instances per service. Adding a gauge is a judgement call; the lack
  of one today is not a policy statement.
- **Percentiles in metric names.** We do not publish `*_p99` style metric names. The
  histogram buckets carry the distribution; percentiles are computed by Grafana /
  PromQL.
- **Custom `MeterFilter`.** No project-wide filter to reject high-cardinality tags.
  Conservative choice; adding one is a future defensive option.
- **Histograms on Counters.** Counters are incrementable events, not distributions —
  `percentiles-histogram` on a counter does nothing useful.

---

## Alternatives Considered

1. **Flat metric names without namespaces** (`hold_allocate_latency`). Rejected —
   naming clashes as the fleet grows; Grafana templating breaks.
2. **Underscore-separated names** (`reservations_hold_allocate`). Rejected —
   Micrometer prefers dots; Prometheus exporter converts automatically.
3. **No `Observation` API, use raw `Timer` + manual trace span.** Rejected because it
   duplicates instrumentation at every call site (start timer, start span, stop timer,
   stop span, copy tags).
4. **Put everything in baggage, use no labels.** Rejected — low-cardinality grouping
   (by starport, by shipClass) is the single most useful dashboard dimension; removing
   it would push every question into a per-trace search.
5. **`Tags.of("...")` constant classes per service.** Considered. Not adopted because
   the tag vocabulary is small enough to memorise; a helper class would reduce typos but
   add indirection. Revisit if tag variants grow beyond ~15.

---

## References

- ADR-0005 — Observability Stack (why PLG + Micrometer)
- ADR-0011 — Architecture Guardrails (contract tests pin metric names)
- ADR-0017 — Tracing Propagation (how spans get the same IDs as metrics exemplars)
- ADR-0029 — Acceptance Test Fixtures (`TestObservationRegistry` usage)
- Micrometer Observation API —
  https://micrometer.io/docs/observation
- Prometheus: Histograms and Summaries —
  https://prometheus.io/docs/practices/histograms/
- OpenTelemetry semantic conventions for metrics —
  https://opentelemetry.io/docs/specs/semconv/general/metrics/
