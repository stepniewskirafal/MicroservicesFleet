# 0011 — Architecture Rules & Guardrails

**Status:** Accepted
**Date:** 2026-02-28

---

## Context

ADR-0001 assigns a distinct architecture style to each service (Layered, Hexagonal,
Pipes & Filters). Without automated enforcement, drift is inevitable: controllers
call repositories directly, domain code imports Spring, filters accumulate state.
Code review alone is inconsistent.

---

## Decision

Enforce three orthogonal guardrails, all as Maven plugins (no external services):

- **ArchUnit** — encodes ADR-0001 layer/dependency rules as JUnit 5 tests, run in
  Surefire. Examples: Layered forbids `controller → repository`; Hexagonal forbids
  `domain → org.springframework..` and `adapter → adapter`; Pipes & Filters requires
  stateless filters.
- **Spotless** with `palantirJavaFormat` + `removeUnusedImports`. `spotless:apply`
  reformats locally; `spotless:check` fails CI on unformatted files. The `-Pfast`
  profile skips it for rapid local dev.
- **PIT** with `STRONGER` mutators and `mutationThreshold=80`, scoped to unit tests
  (E2E/Repository/Contract excluded). Run explicitly via `mvn pitest:mutationCoverage`
  — not part of default `mvn verify` because of its CPU cost.

**Known gap:** `archunit-junit5` is on the classpath; rules are authored per service as
its implementation lands. `telemetry-pipeline` has `PipesAndFiltersArchitectureTest`;
the Layered (starport-registry) and Hexagonal (trade-route-planner) rule classes are
still pending.

---

## Why

- **Architecture violations fail the build.** ArchUnit produces a red Surefire run
  with a descriptive message — not a missed review comment.
- **No format debates.** Spotless removes style as a PR topic.
- **Catches coverage theatre.** PIT detects tests that execute code without asserting.
- **Local = CI.** All three run from the same Maven commands developers already use.

---

## Alternatives

- **SonarQube** — rich dashboards but requires a running server; free tier limits
  branch analysis.
- **PMD / Checkstyle** — not architecture-aware; cannot express layer rules.
- **Manual code review only** — inconsistent under time pressure; violations accumulate.

---

## References

- ADR-0001 — Architecture Styles per Service
- ADR-0006 — Testing Strategy
- ArchUnit — https://www.archunit.org/
- Spotless — https://github.com/diffplug/spotless/tree/main/plugin-maven
- PIT — https://pitest.org/
