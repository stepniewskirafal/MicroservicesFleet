# 0014 — HTTP Resilience: Timeouts + Circuit Breaker + Fail-Fast

**Status:** Accepted  
**Date:** 2026-04-17

---

## Context

`starport-registry` (A) calls `trade-route-planner` (B) synchronously during the
reservation flow (ADR-0004 explains *why* synchronously). Without protection, a slow B
instance pins virtual-thread carriers and HikariCP slots within seconds; a crashing B
propagates 5xx straight to clients. Retries are not appropriate — route planning has
side effects (metrics, Kafka publish in some modes), and Spring Cloud LoadBalancer
already rotates instances on connection failure.

---

## Decision

Three-part contract on every A→B HTTP call:

1. **Aggressive timeouts** — connect 200 ms, read 2 000 ms (configured via
   `SimpleClientHttpRequestFactory` in `RestClientConfig`).
2. **Resilience4j circuit breaker** (`trade-route-planner`): `COUNT_BASED` window 10,
   minimum 5 calls, 50% failure threshold, 10 s open state, 3 calls in half-open.
3. **Fail-fast fallback** — `routeUnavailableFallback` throws
   `RouteUnavailableException`, which `GlobalExceptionHandler` maps to **HTTP 409**
   (see ADR-0015). No cached route.

No client-side retry is configured.

```java
@CircuitBreaker(name = "trade-route-planner", fallbackMethod = "routeUnavailableFallback")
public Route calculateRoute(ReserveBayCommand command) { ... }
```

---

## Why

- **Bounded latency.** Worst case 2 s per call; a stuck B cannot drain A's carriers or
  DB pool.
- **Fast shedding under outage.** After 6/10 failures the breaker opens; subsequent
  calls return instantly via fallback — no network round-trip.
- **No retry amplification.** Side effects do not double-fire; an already-overloaded B
  is not piled on.
- **Health exposed.** `register-health-indicator: true` surfaces breaker state at
  `/actuator/health` for alerting.

---

## Alternatives

- **No breaker, 30 s timeout** — one stuck B exhausts A within seconds.
- **`@Retry(maxAttempts=2)`** — duplicates side effects; LoadBalancer already handles
  connection-level failover.
- **WebClient (reactive)** — no benefit over blocking RestClient on virtual threads
  (ADR-0012), at the cost of reactive complexity.
- **Bulkhead instead of breaker** — complementary, not a replacement; may be added
  later.

---

## References

- ADR-0003 — HTTP Load Balancing
- ADR-0004 — Messaging vs HTTP
- ADR-0012 — Virtual Threads
- ADR-0015 — API Error Model (RouteUnavailable → 409)
- [Resilience4j CircuitBreaker](https://resilience4j.readme.io/docs/circuitbreaker)
