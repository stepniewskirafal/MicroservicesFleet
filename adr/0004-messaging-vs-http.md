# 0004 — Messaging vs HTTP: Hybrid

**Status:** Accepted — 2026-02-28

---

## Context

The three-service fleet (Starport Registry A, Trade Route Planner B, Telemetry Pipeline C) needs a clear rule for when to use synchronous HTTP versus asynchronous Kafka. Reservation creation in A needs B's route in the same response, but domain events (`ReservationConfirmed`, `TariffCalculated`, `RouteChanged`) must survive transient receiver downtime and not block A's transaction.

---

## Decision

**HTTP for synchronous request-response; Kafka (via Transactional Outbox, ADR-0010) for domain events.**

| Interaction | Style | Why |
|---|---|---|
| A → B: plan route during reservation | HTTP (`lb://trade-route-planner`) | Caller needs `routeCode`, `eta`, `risk` in the same response. |
| A → C, B → C: publish domain events | Kafka via Outbox | Fire-and-forget; receiver must not block A's tx. |
| C → B: re-plan on anomaly (future) | HTTP | Targeted command with an expected result. |

Outbox: events saved transactionally to `event_outbox`, then a poller (`app.poll-interval-ms`, default 30 s) publishes via `StreamBridge`.

---

## Why

- **Controlled user-facing latency.** Reservation returns route in one round-trip.
- **Loose coupling for analytics.** C can be down or slow without affecting A or B.
- **Durability for events.** Outbox guarantees at-least-once delivery, even if Kafka is briefly unavailable.
- **Clear rule.** "Need an answer now? HTTP. Broadcasting a fact? Kafka."

---

## Alternatives

- **HTTP only** — tight temporal coupling; C ends up in the critical path of every reservation; no durable event log.
- **Kafka only** — request-response over Kafka needs correlation IDs and reply topics; latency becomes unpredictable for SLA-bound calls.

---

## References

- ADR-0002 — Service Discovery: Eureka
- ADR-0003 — HTTP Load Balancing
- ADR-0010 — Resilience Patterns (Transactional Outbox)
- Hohpe & Woolf — *Enterprise Integration Patterns*
