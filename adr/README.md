# Architecture Decision Records

This directory collects the **ADRs** (Architecture Decision Records) for the
MicroservicesFleet repository. Each ADR captures one architecturally significant decision,
why it was made, what it forces the code to look like, and which trade-offs were accepted.

The layout follows a light variant of [Michael Nygard's ADR template][nygard]:
`Context → Decision → Consequences → Alternatives → References`, with an additional
"How the codebase enforces this" section tying the decision back to file paths and
configuration blocks in the repo.

[nygard]: https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions.html

---

## How to read an ADR

- **Status** — `Proposed`, `Accepted`, `Deprecated`, or `Superseded by ADR-XXXX`.
- **Date** — when the status was last set (ISO 8601).
- **Context** — the problem, constraints, and forces in play.
- **Decision** — what we chose, as short and concrete as possible.
- **How the codebase enforces this** — line-numbered pointers to the implementation, so the
  ADR does not silently drift from the code.
- **Consequences** — benefits *and* trade-offs. "No trade-offs" means the ADR is
  incomplete.
- **Alternatives Considered** — options rejected and why.

When opening a PR that introduces or changes an architectural decision, add a new ADR
rather than editing an old one. Old ADRs are history — they may be marked `Superseded`
when replaced, but their content remains to explain why the older choice existed.

---

## Index

| #    | Title                                                                        | Status                   | Date       |
|---|---|---|---|
| [0000](0000-template.md) | ADR Template                                                     | —                        | —          |
| [0001](0001-architecture-styles.md) | Architecture Styles per Service (Layered / Hexagonal / Pipes & Filters) | Accepted       | 2025-09-29 |
| [0002](0002-service-discovery-choice.md) | Service Discovery Mechanism (Eureka)                      | Accepted                 | 2025-09-29 |
| [0003](0003-http-load-balancing.md) | HTTP Load Balancing (Spring Cloud LoadBalancer)               | Accepted                 | 2026-02-28 |
| [0004](0004-messaging-vs-http.md)   | Messaging vs HTTP (hybrid: HTTP sync, Kafka async)            | Accepted                 | 2026-02-28 |
| [0005](0005-observability-stack.md) | Observability Stack (Prometheus + Grafana + Tempo + Loki)     | Accepted                 | 2026-02-28 |
| [0006](0006-testing-strategy.md)    | Testing Strategy (unit + contract + integration + ArchUnit + PIT) | Accepted             | 2026-02-28 |
| [0007](0007-database-choice.md)     | Database Choice (PostgreSQL 16 + JPA + Flyway)                | Accepted                 | 2026-02-28 |
| [0008](0008-deployment-topology.md) | Deployment Topology (Docker Compose + scaling)                | Accepted                 | 2026-02-28 |
| [0009](0009-configuration-management.md) | Configuration Management (env-var overrides + Spring profiles) | Accepted            | 2026-02-28 |
| [0010](0010-resilience-patterns.md) | Resilience Patterns (Transactional Outbox)                    | Accepted                 | 2026-02-28 |
| [0011](0011-arch-rules-guardrails.md) | Architecture Rules & Guardrails (ArchUnit + Spotless + PIT) | Accepted                 | 2026-02-28 |
| [0012](0012-virtual-threads.md)     | Java 21 Virtual Threads (Project Loom)                        | Accepted                 | 2026-04-16 |
| [0013](0013-open-in-view-false.md)  | Disabling Open Session in View (OSIV)                         | Accepted                 | 2026-04-16 |
| [0014](0014-http-resilience.md)     | HTTP Resilience: Timeouts, Circuit Breaker, Fail-Fast Fallback | Accepted                | 2026-04-17 |
| [0015](0015-api-error-model.md)     | API Error Response Model & Versioning Policy                  | Accepted                 | 2026-04-17 |
| [0016](0016-kafka-topology.md)      | Kafka Topic Topology, Consumer Retry, DLQ Strategy            | Accepted                 | 2026-04-17 |
| [0017](0017-tracing-propagation.md) | Distributed Tracing Propagation (W3C + OTel Baggage + Outbox) | Accepted                 | 2026-04-17 |
| [0018](0018-flyway-migration-policy.md) | Flyway Migration Policy & No-Foreign-Keys-by-Design       | Accepted                 | 2026-04-17 |
| [0019](0019-kafka-programming-model.md) | StreamBridge for Producers, Functional Beans for Consumers | Accepted                | 2026-04-17 |

---

## Map: ADRs by concern

**Architecture & service boundaries**
ADR-0001 (styles), ADR-0002 (discovery), ADR-0008 (topology).

**Inter-service communication**
ADR-0003 (HTTP LB), ADR-0004 (messaging vs HTTP), ADR-0014 (HTTP resilience),
ADR-0015 (error model), ADR-0016 (Kafka topics), ADR-0019 (Kafka programming model).

**Data layer**
ADR-0007 (PostgreSQL), ADR-0010 (outbox), ADR-0013 (OSIV off),
ADR-0018 (Flyway policy + no-FK).

**Concurrency & performance**
ADR-0012 (virtual threads), ADR-0013 (OSIV off — pool sizing).

**Observability & operations**
ADR-0005 (PLG + Tempo), ADR-0017 (trace propagation), ADR-0009 (configuration).

**Quality & process**
ADR-0006 (testing strategy), ADR-0011 (ArchUnit + Spotless + PIT).

---

## Map: ADRs by service

**starport-registry (Layered — Service A)**
ADR-0007 (PostgreSQL), ADR-0010 (outbox), ADR-0012 (virtual threads), ADR-0013 (OSIV off),
ADR-0014 (HTTP resilience → B), ADR-0015 (error model), ADR-0018 (Flyway + no-FK),
ADR-0019 (StreamBridge producer).

**trade-route-planner (Hexagonal — Service B)**
ADR-0014 (inbound resilience contract), ADR-0015 (RouteRejectedResponse),
ADR-0019 (StreamBridge producer).

**telemetry-pipeline (Pipes & Filters — Service C)**
ADR-0016 (consumer retry), ADR-0019 (functional consumers).

**Cross-cutting**
ADR-0001, ADR-0002, ADR-0003, ADR-0004, ADR-0005, ADR-0006, ADR-0008, ADR-0009, ADR-0011,
ADR-0017.

---

## Known gaps (candidate ADRs for future work)

These decisions are implemented implicitly or are unresolved questions. They are tracked
here so they are not forgotten:

- **No authentication / authorisation on REST endpoints.** All endpoints are public.
  Acceptable for a demo; a production-targeted ADR would document the chosen security
  model (JWT, OAuth2, mTLS).
- **No OpenAPI publishing.** Springdoc is not configured. The REST contract is implicit in
  controller signatures; a future ADR would cover API documentation generation.
- **Kafka DLQ topics are not yet enabled** (only consumer-side `max-attempts`). ADR-0016
  describes the intent and operational posture.
- **Schema registry for Kafka events.** ADR-0016 keeps JSON + `eventType` discriminator for
  now; if the number of event types or cross-team consumers grows, a Schema Registry ADR
  would replace that.
- **Production-grade Kafka broker replication.** Single-broker Compose runs use
  replication factor 1. Production deployment needs a separate ADR (or extension of
  ADR-0008) covering broker HA.
- **Idempotency keys on HTTP POSTs.** Not implemented. Reservation creation is idempotent
  per `reservationId` at the DB level, but no `Idempotency-Key` header contract exists.

---

## Related documents

- `../README.md` — repository overview.
- `../plany/plan-obsluga-setek-requestow.md` — concurrency / throughput plan that feeds
  ADR-0012 and ADR-0013.
- `../starport-registry/inbox_outbox_pattern_15_minute_spring_boot_talk.md` — supplementary
  learning notes on the outbox (ADR-0010).
