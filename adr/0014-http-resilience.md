# 0014 — HTTP Resilience: Timeouts + Circuit Breaker + Fail-Fast

**Status:** Accepted  
**Date:** 2026-04-17

---

## Context

`starport-registry` (A) calls `trade-route-planner` (B) synchronously during the
reservation flow (ADR-0004 explains *why* synchronously). Without protection, a slow B
instance pins virtual-thread carriers and HikariCP slots within seconds; a crashing B
propagates 5xx straight to clients. With two planner replicas behind Eureka, a single
bad instance would fail ~50% of calls with no second chance, so one retry is warranted —
the load balancer picks a fresh instance per attempt.

---

## Decision

Three-part contract on every A→B HTTP call:

1. **Aggressive timeouts** — connect 200 ms, read 2 000 ms (configured via
   `SimpleClientHttpRequestFactory` in `RestClientConfig`).
2. **Resilience4j circuit breaker** (`trade-route-planner`): `COUNT_BASED` window 10,
   minimum 5 calls, 50% failure threshold, 10 s open state, 3 calls in half-open.
3. **Fail-fast fallback** — `routeUnavailableFallback` throws
   `RouteUnavailableException`, which `GlobalExceptionHandler` maps to **HTTP 409**
   (→ ADR-0015). No cached route.

One retry (`@Retry max-attempts: 2`, 100 ms wait) on `RouteUnavailableException` sits
outside the breaker — `@Retry` wraps `@CircuitBreaker`, so the fallback funnels every
failure into the retryable exception and the second attempt lands on a fresh
load-balanced instance.

```java
@Retry(name = "trade-route-planner")
@CircuitBreaker(name = "trade-route-planner", fallbackMethod = "routeUnavailableFallback")
public Route calculateRoute(ReserveBayCommand command) { ... }
```

---

## Why

- **Bounded latency.** Worst case 2 s per call; a stuck B cannot drain A's carriers or
  DB pool.
- **Fast shedding under outage.** After 6/10 failures the breaker opens; subsequent
  calls return instantly via fallback — no network round-trip.
- **Bounded retry.** Exactly one extra attempt routes around a single bad replica
  without piling on an overloaded B. A read-timeout where B already processed the
  request can duplicate the route event (acceptable: planning is sub-second; a
  fully-safe retry would need an idempotency key — out of scope).
- **Health exposed.** `register-health-indicator: true` surfaces breaker state at
  `/actuator/health` for alerting.

---

## Alternatives

- **No breaker, 30 s timeout** — one stuck B exhausts A within seconds.
- **No retry at all** — leaves a single bad replica failing ~50% of calls until the
  breaker or Eureka eviction reacts; one retry recovers immediately.
- **`@Retry(maxAttempts=3+)`** — amplifies load on an already-degraded B; one extra
  attempt is the sweet spot for two replicas.
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
