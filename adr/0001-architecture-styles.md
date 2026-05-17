# 0001 — Architecture Styles per Service

**Status:** Accepted — 2025-09-29 (ratified 2026-02-28 — all three services now implement their assigned style)

---

## Context

The assignment explicitly requires three distinct architecture styles across the microfleet: **Layered**, **Hexagonal (Ports & Adapters)**, and **Pipes & Filters**. Each service must run at least two instances, integrate with HTTP-based service discovery, and provide observability and tests from day one.

---

## Decision

Assign one style per service:

- **Service A — Starport Registry:** **Layered** — API → Application → Domain → Infrastructure (Spring Data JPA).
- **Service B — Trade Route Planner:** **Hexagonal (Ports & Adapters)** — core domain behind ports; adapters for REST (in/out), Postgres, external HTTP clients (embargo/astro data).
- **Service C — Telemetry Pipeline:** **Pipes & Filters** — stateless filters chained over Kafka (Spring Cloud Stream).

Boundaries are enforced per-service via ArchUnit (no `api → infrastructure` in A; no Spring in B's domain; no persistence inside C's filters).

---

## Why

- **Style fits the service.** Synchronous CRUD-like work in A maps cleanly to layers; B's pluggable external integrations want ports; C's streaming flow is literally pipes and filters.
- **Style-aligned testing.** Slice tests (A), pure-domain unit tests + adapter contract tests (B), filter unit tests + Kafka Testcontainers end-to-end (C).
- **Traces map to structure.** Latency sources land on layer/port/filter boundaries, easing root-cause analysis.
- **Educational value.** The repo is a living catalog of three patterns.

---

## Alternatives

- **Single layered monolith** — violates assignment constraints; no style diversity.
- **All services layered** — insufficient diversity; pushes streaming work into an awkward shape.
- **All services hexagonal** — overkill for C; P&F models the streaming flow more honestly.

---

## References

- Eric Evans — *Domain-Driven Design*
- Alistair Cockburn — *Ports & Adapters*
- Spring Cloud Stream — Pipes & Filters reference
