# 0001 — Architecture Styles per Service

**Status:** Proposed — 2025-09-29

## Context

The assignment explicitly requires using three distinct architecture styles across the microfleet: **Layered**, **Hexagonal (Ports & Adapters)**, and **Pipes & Filters**. Each service must run at least two instances, integrate with HTTP-based service discovery, and provide observability and tests from day one.

## Decision

* **Service A — Starport Registry:** **Layered architecture**
  API → Application → Domain → Infrastructure (Spring Data JPA).
* **Service B — Trade Route Planner:** **Hexagonal (Ports & Adapters)**
  Core domain behind ports; adapters for REST (in/out), Postgres, external HTTP clients (embargo/astro data).
* **Service C — Telemetry Pipeline:** **Pipes & Filters**
  Stateless filters connected as a chain over Kafka (Spring Cloud Stream).

## Consequences

**Benefits**

* **Clarity of responsibilities and boundaries.**
  Each service exhibits a crisp, style-appropriate separation of concerns, improving readability and easing onboarding.
* **Style-aligned testability.**

  * A (Layered): straightforward slice tests (Web/JPA), plus domain unit tests.
  * B (Hexagonal): domain core unit tests without Spring; adapter tests isolate IO; easy contract testing at ports.
  * C (P&F): filter-level unit tests and end-to-end stream tests with Testcontainers/Kafka.
* **Educational value and architectural literacy.**
  The codebase serves as a living catalog of three patterns, useful for mentoring and future design discussions.
* **Operational fitness and scalability.**

  * A: horizontal scaling is trivial; JPA + DB pooling well understood.
  * B: pure domain core + pluggable adapters enables swapping integrations with minimal blast radius.
  * C: back-pressure and throughput tuning are naturally addressed via Kafka partitions and filter parallelism.
* **Observability that maps to structure.**
  Traces align with layer/port/filter boundaries, helping pinpoint latency sources (e.g., domain vs. adapter vs. specific filter).
* **Reduced coupling across the fleet.**
  Synchronous dependencies are kept where they add value (A→B), while event-driven processing is isolated in C.

**Trade-offs / Risks**

* **Heterogeneous internal standards.**
  Three styles mean three sets of conventions (naming, package layout, testing patterns). Without guardrails, drift is likely.
* **Cognitive overhead for contributors.**
  Engineers must understand and respect different rules per service (e.g., no domain → adapter leaks in B, stateless filters in C).
* **Inconsistent velocity across services.**
  Teams familiar with Layered may move faster on A; B initially slows due to extra abstraction (ports, adapters) until patterns are internalized.
* **Duplication of cross-cutting approaches.**
  Resilience, metrics, and logging must be tuned per style; boilerplate can creep unless centralized libraries/conventions are used.
* **Complexity in CI/CD and quality gates.**
  ArchUnit rules differ per service; failing one set of rules may not imply a problem in others, requiring nuanced pipelines and documentation.
* **Operational nuances differ per service.**

  * A: DB migrations/versioning (Flyway) must be coordinated with releases.
  * B: adapter lifecycle/credentials rotation adds ops surface.
  * C: Kafka capacity planning (partitions, retention, DLQ) and consumer lag monitoring become critical.
* **Debugging requires style-specific tooling.**

  * A: focus on SQL plans, JPA caching, transaction boundaries.
  * B: port contract verification and adapter fault injection.
  * C: stream replay, dead-letter analysis, partition skew, and filter timing.
* **Performance tuning paths diverge.**
  A relies on DB/query tuning; B optimizes pure domain logic and adapter batching; C optimizes per-filter latency and batch/linger settings.
* **Onboarding and documentation burden.**
  To avoid misuse (e.g., leaking infrastructure concepts into B’s core), the repo must include clear “how to add a feature” guides per style.
* **Risk of architectural erosion.**
  Without automated checks, teams might bypass ports in B, build stateful filters in C, or let A’s controllers absorb business logic.

**Mitigations / Guardrails**

* **ArchUnit rules per service** enforcing allowed dependencies and package boundaries.
* **Project templates and checklists** (per style) for new features, tests, and adapters/filters.
* **Shared libraries for cross-cutting concerns** (logging, tracing, metrics, error handling) to reduce duplication.
* **Quality gates in CI**: run style-specific ArchUnit suites, coverage thresholds, and mutation tests (optional) on the domain core in B.
* **Operational runbooks**: DB migration runbook (A), adapter credential rotation (B), Kafka scaling & DLQ triage (C).

## Alternatives Considered

* **Single layered monolith** — rejected: violates assignment constraints and reduces the educational/architectural aims.
* **All services layered** — rejected: insufficient style diversity for the assignment goals.
* **All services hexagonal** — rejected: overkill for the streaming pipeline; Pipes & Filters better models C’s flow.

## Implementation

* Define **package conventions** and **ArchUnit** rules per service:

  * **A (Layered):** `..api..` → `..application..` → `..domain..` → `..infrastructure..` with no back-references; no direct `api → infrastructure`.
  * **B (Hexagonal):** `..domain..` (no Spring), `..application..` (use cases/ports), `..adapters..` (IO); forbid adapters depending on each other via domain.
  * **C (P&F):** `..filters..` are stateless; message contracts in `..model..`; forbid persistence in filters (use sinks).
* Add **README diagrams** per service showing intended dependency flow.
* Include **example tests** (slice/unit/stream) demonstrating the style’s best practices.

## References

* Eric Evans — *Domain-Driven Design*
* Alistair Cockburn — *Ports & Adapters*
* Classic *Pipes & Filters* pattern literature and Spring Cloud Stream docs

---
