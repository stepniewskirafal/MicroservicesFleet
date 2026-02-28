# 0004 — When to Use Messaging vs HTTP

**Status:** Accepted — 2026-02-28
**Deciders:** Team
**Technical Story:** Define the communication boundary between synchronous request-response calls and asynchronous event-driven messaging across the three-service fleet.

---

## Context and Problem Statement

The fleet consists of three services: Starport Registry (A), Trade Route Planner (B), and Telemetry Pipeline (C). Services need to exchange data and trigger behaviour in each other. Two fundamentally different communication styles are available: synchronous HTTP and asynchronous messaging via Kafka. Choosing the wrong style for a given interaction creates coupling, latency, or reliability problems. How do we decide which style to use for which interaction?

---

## Decision Drivers

* Reservation creation in A requires a route from B as part of the same user-facing response — the caller expects a result immediately.
* Events like `ReservationConfirmed`, `TariffCalculated`, and `RouteChanged` must survive transient receiver downtime without losing data.
* Telemetry Pipeline (C) must be decoupled from A and B — it should not be a required dependency for a reservation to succeed.
* At-least-once delivery and durability requirements for domain events (audit trail, downstream enrichment).
* The Outbox Pattern is adopted (see ADR-0010) to guarantee that domain events are never lost even if Kafka is temporarily unavailable at publish time.

---

## Considered Options

1. **Synchronous HTTP only** — all inter-service calls are REST over HTTP with Spring Cloud LoadBalancer.
2. **Asynchronous Kafka messaging only** — all interactions go through Kafka topics; services are fully decoupled.
3. **Hybrid: HTTP for synchronous request-response, Kafka for domain events** — chosen approach.

---

## Decision Outcome

**Chosen option: Hybrid — HTTP for synchronous request-response, Kafka (via Outbox) for domain events.**

The rule is:

| Interaction | Style | Rationale |
|---|---|---|
| A → B: plan route during reservation | HTTP (`lb://trade-route-planner`) | Caller needs the result (routeCode, eta, risk) in the same HTTP response. |
| A → C, B → C: publish domain events | Kafka via Transactional Outbox | Fire-and-forget; receiver must not block or break the transaction. |
| C → B: re-plan suggestion on anomaly | HTTP (future) | Explicit command that expects a result; modelled as a targeted call. |

### Positive Consequences

* **User-facing latency is controlled.** The reservation endpoint can return a complete response (including route) in a single round-trip.
* **Loose coupling for analytics and enrichment.** C subscribes to Kafka topics independently; A and B are unaffected by C's downtime or slow processing.
* **Durability for domain events.** The Outbox Pattern (see ADR-0010) persists events in the same transaction as the domain state change, ensuring at-least-once delivery to Kafka.
* **Clear communication contracts.** Engineers know: "if you need an answer now, use HTTP; if you're broadcasting a fact, use Kafka."
* **Independent scaling.** Kafka consumers in C can lag and catch up without affecting A's throughput.

### Negative Consequences

* **Temporal coupling for A → B.** If B is unavailable, the reservation fails (no route). A circuit breaker and fallback (route-not-requested mode) can mitigate this.
* **Dual technology surface.** Teams must understand both HTTP/WebClient and Spring Cloud Stream / Kafka producer semantics.
* **Event schema governance.** Kafka messages require explicit schema management to avoid breaking consumers when payloads evolve.
* **Outbox polling latency.** Domain events are not published instantly; there is a configurable polling delay (`app.poll-interval-ms`, default 30 s) between commit and Kafka delivery.

---

## Pros and Cons of the Options

### Option 1 — Synchronous HTTP only

* Good, because simple mental model: one style for everything.
* Good, because no Kafka infrastructure needed.
* Bad, because all receivers must be available at call time — tight temporal coupling.
* Bad, because Telemetry Pipeline (C) must be in the critical path of every reservation.
* Bad, because no durable event log; replaying past events requires a separate solution.

### Option 2 — Asynchronous Kafka messaging only

* Good, because full decoupling; producers don't care when or whether consumers process messages.
* Good, because natural audit log via Kafka topic retention.
* Bad, because request-response patterns over Kafka are complex (correlation IDs, reply topics, timeouts).
* Bad, because the route needed for a reservation response cannot easily be returned in the same user request.
* Bad, because latency becomes unpredictable for interactions that have SLA expectations.

### Option 3 — Hybrid: HTTP + Kafka ✅

* Good, because each interaction uses the style that fits its semantics.
* Good, because synchronous interactions retain predictable latency; async interactions retain durability.
* Good, because the Outbox Pattern removes the dual-write problem for Kafka publishing.
* Bad, because two communication paradigms increase cognitive complexity.
* Bad, because schema drift on Kafka topics requires explicit versioning discipline.

---

## Implementation

* **HTTP (A → B):** `WebClient` with `@LoadBalanced` builder, `lb://trade-route-planner` base URL, connect timeout 2 s, read timeout 5 s, single retry on 5xx.
* **Kafka producers (A):** Spring Cloud Stream bindings: `reservationCreated-out-0` → `starport.reservations`, `tariffCalculated-out-0` → `starport.tariffs`, `routeChanged-out-0` → `starport.route-changes`.
* **Outbox:** Events saved transactionally to `event_outbox` table; a scheduler polls every `app.poll-interval-ms` ms and publishes via `StreamBridge`.
* **Consumer (C):** Subscribes to `starport.reservations`, `starport.tariffs`, etc. via Spring Cloud Stream.
* **Event schema:** JSON payloads versioned via `eventType` field (`ReservationConfirmed`, `TariffCalculated`, etc.); breaking changes require a new `eventType` value or a consumer-side compatibility adapter.

---

## References

* ADR-0002 — Service Discovery Mechanism
* ADR-0003 — HTTP Load Balancing Approach
* ADR-0010 — Resilience Patterns (Transactional Outbox)
* Enterprise Integration Patterns — Gregor Hohpe & Bobby Woolf
* Spring Cloud Stream — https://docs.spring.io/spring-cloud-stream/docs/current/reference/html/
