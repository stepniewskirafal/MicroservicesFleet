# 0021 — Hexagonal Conventions — trade-route-planner

**Status:** Accepted
**Date:** 2026-04-17

---

## Context

ADR-0001 assigns `trade-route-planner` the Hexagonal style but does not specify package
layout, port naming, or domain-purity rules. Hexagonal is a family of dialects — a code
review team needs a single concrete one to reject PRs against.

---

## Decision

Four top-level packages under `com.galactic.traderoute`:

```
domain/        pure Java + records. No Spring imports.
port/in/       *UseCase interfaces (driving) — one method each
port/out/      *Publisher / *Gateway / *Repository (driven) — role names, not tech
application/   @Service implementations of in-ports; depend on out-ports only
adapter/in/rest, adapter/out/kafka — the only place frameworks may live
config/        @Configuration beans for cross-cutting concerns
```

Inner layers (`domain`, `port`, `application`) must not import from `adapter/`. ArchUnit
will enforce this (ADR-0011); reviewers enforce it until then.

Rules:

- In-port naming: `*UseCase`, one method per use case.
- Out-port naming: role-describing (`RouteEventPublisher`, not `KafkaEventPublisher`).
- Domain records, no setters. Lombok `@Builder` allowed; `@Data`/`@Setter` forbidden on
  domain types (mutability breaks event semantics).
- Controllers depend on in-port interfaces, never on `@Service` classes directly.
- Outbound adapters encapsulate transport (binding name, headers, partition key).
- Each application-service entry point wraps work in a Micrometer `Observation`
  (ADR-0005).

---

## Why

- **Testability** — application services unit-test with mock out-ports, no Spring
  context.
- **Swappable adapters** — replacing Kafka with HTTP webhook is one new adapter +
  `@Primary`; no domain change.
- **Reading order** — `port/in` → `application` → `port/out` walks a new engineer
  through the service in 10 minutes; adapters are read only when "how exactly do we
  talk to Kafka?" matters.
- **Obvious dependency direction** — `grep import` across `domain/` reveals the entire
  external surface.

---

## Alternatives

- **Onion / Clean Architecture vocabulary** (`entities/`, `gateways/`) — equivalent in
  substance; ADR-0001 picked hexagonal vocabulary, keep it.
- **Package-by-feature** (`planroute/{in,out,...}`) — premature for one use case;
  revisit at 5+.
- **Skip in-ports, inject services into controllers** — couples controller to
  implementation; loses interface-based test seams.
- **No adapter layer; controller calls Kafka directly** — defeats the point of
  hexagonal.

---

## References

- ADR-0001 — Architecture Styles
- ADR-0005 — Observability (`Observation` in application services)
- ADR-0011 — ArchUnit guardrails (will encode this ADR)
- ADR-0015 — API Error Response Model
- ADR-0019 — Kafka Programming Model
- Cockburn — *Hexagonal Architecture* — https://alistair.cockburn.us/hexagonal-architecture/
