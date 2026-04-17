# 0014 — HTTP Resilience: Timeouts, Circuit Breaker, Fail-Fast Fallback

**Status:** Accepted
**Date:** 2026-04-17

---

## Context

Starport Registry (A) calls Trade Route Planner (B) synchronously over HTTP during the
reservation flow (`ReservationCalculationService` → `TradeRoutePlannerHttpAdapter.calculateRoute`).
ADR-0004 explains *why* this call is synchronous. This ADR specifies *how* that call is protected
against partial failures of Service B.

Two failure modes must be handled:

1. **Slow / hung B instance** — the call must not tie up the caller's thread or HikariCP
   connection for seconds. With the Tomcat/virtual-thread concurrency tuning (ADR-0012) and a
   30-slot DB pool (ADR-0013), a 30 s read timeout would let ~30 stuck requests exhaust all
   carrier-thread headroom and DB connections within seconds.
2. **B instance crashing repeatedly** — repeated calls to a failing instance should stop being
   attempted quickly so the caller can degrade gracefully rather than propagate 5xx to the
   client.

Retrying is deliberately **not** part of this ADR: the call is not idempotent from B's
perspective (route planning performs side-effects — metrics, downstream Kafka publish in some
modes), and client-side retries would amplify load on an already-struggling B. Spring Cloud
LoadBalancer already re-selects a different instance on connection failure.

---

## Decision

Adopt a three-part resilience contract for every HTTP call from A to B:

1. **Aggressive timeouts** (connect 200 ms, read 2 000 ms).
2. **Resilience4j circuit breaker** (`COUNT_BASED`, window 10, 50 % failure threshold,
   10 s open state).
3. **Fail-fast fallback** — when the circuit is open or the call exhausts its timeout, throw
   `RouteUnavailableException`, which `GlobalExceptionHandler` maps to **HTTP 409 Conflict**
   for the upstream client. No cached route is returned.

No automatic retry is configured on the client side.

---

## How the codebase enforces this

### 1. Timeouts — `RestClientConfig`

```java
// starport-registry/src/main/java/com/galactic/starport/config/RestClientConfig.java:16-20
@Bean
@LoadBalanced
public RestClient.Builder restClientBuilder(
        @Value("${downstream.http.connect-timeout-ms:200}") long connectTimeoutMs,
        @Value("${downstream.http.read-timeout-ms:800}") long readTimeoutMs) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
    factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
    return RestClient.builder().requestFactory(factory);
}
```

Effective values in `application.yml:207-208`:

```yaml
downstream:
  http:
    connect-timeout-ms: 200
    read-timeout-ms: 2000
```

The `@LoadBalanced` annotation wires Spring Cloud LoadBalancer (ADR-0003) into the RestClient,
so `http://trade-route-planner/...` URLs are resolved via Eureka at call time.

### 2. Circuit breaker — `TradeRoutePlannerHttpAdapter`

```java
// starport-registry/.../service/routeplanner/TradeRoutePlannerHttpAdapter.java:49-59
@CircuitBreaker(name = "trade-route-planner", fallbackMethod = "routeUnavailableFallback")
public Route calculateRoute(ReserveBayCommand command) {
    return restClient.post()
            .uri(baseUrl + "/routes/plan")
            .body(new PlanRouteRequest(command.originStarport(), command.destinationStarport()))
            .retrieve()
            .body(Route.class);
}
```

Configuration in `application.yml:189-203`:

```yaml
resilience4j:
  circuitbreaker:
    instances:
      trade-route-planner:
        register-health-indicator: true
        failure-rate-threshold: 50
        wait-duration-in-open-state: 10s
        sliding-window-size: 10
        minimum-number-of-calls: 5
        permitted-number-of-calls-in-half-open-state: 3
        sliding-window-type: COUNT_BASED
  timelimiter:
    instances:
      trade-route-planner:
        timeout-duration: 3s
```

State machine:

| Metric observed                                  | Transition           |
|---|---|
| ≥5 calls, ≥50 % fail in last 10                  | `CLOSED → OPEN`      |
| 10 s elapsed in `OPEN`                           | `OPEN → HALF_OPEN`   |
| ≥50 % of next 3 calls succeed                    | `HALF_OPEN → CLOSED` |
| ≥50 % of next 3 calls fail                       | `HALF_OPEN → OPEN`   |

The 10 s recovery window is short by design: the goal is degradation, not prolonged outage —
if B recovers within seconds, A should attempt the next call rather than stay cold for
minutes.

### 3. Fail-fast fallback — no cached route

```java
// starport-registry/.../service/routeplanner/TradeRoutePlannerHttpAdapter.java:61-67
private Route routeUnavailableFallback(ReserveBayCommand command, Throwable t) {
    log.warn("Circuit breaker open for trade-route-planner, route unavailable: {} -> {}",
             command.originStarport(), command.destinationStarport(), t.toString());
    throw new RouteUnavailableException(
            "Route planning service is currently unavailable");
}
```

`GlobalExceptionHandler.handleRouteUnavailable()` maps this to **HTTP 409 Conflict**, not 500.
The caller knows the reservation cannot be completed and can decide whether to retry the
reservation at the business level. See ADR-0015 for the full exception → status mapping table.

### 4. Why no `@Retry`

An earlier design considered `@Retry(name = "trade-route-planner", maxAttempts = 2)`. It was
rejected because:

- **Duplicate side effects.** Route planning updates Micrometer counters and (when enabled)
  publishes `routePlanned` events to Kafka. A client-side retry would double-count.
- **LoadBalancer already rebalances.** On a connection-refused failure, Spring Cloud
  LoadBalancer rotates to another instance transparently — without involving `@Retry`.
- **Amplifies overload.** If B is failing because it is overloaded, retries multiply the
  load and accelerate the failure. The circuit breaker's slow recovery is the correct
  response.

---

## Consequences

### Benefits

- **Bounded latency.** The worst-case call takes `max(connect=200 ms, read=2 s) = 2 s` before
  failing. A stuck B instance cannot block A threads beyond that.
- **Fast shedding under outage.** After the 6th failure in a 10-call window, the circuit
  opens and subsequent calls fail **immediately** (no network round-trip) with the fallback.
- **No retry amplification.** One call, one attempt.
- **Exposed health.** `register-health-indicator: true` makes the circuit state visible at
  `/actuator/health` — useful for alerting (see ADR-0005).

### Trade-offs

- **Short outages propagate as 409.** A B restart of 30 s will trip the breaker and cause a
  burst of 409s to reservation clients. This is acceptable: the user can retry at the
  business level, and reservation creation without a route (`requestRoute=false`) still
  works.
- **No graceful degradation to cached route.** There is no cache of recent routes to serve
  from. A future ADR could introduce one if user experience requires it.
- **Timeout tuning is environment-sensitive.** 2 s read timeout is tight for a call that
  sometimes runs fee calculation on a cold JVM in B. If P99 latency creeps above 2 s, the
  timeout must be raised *or* the root cause in B must be addressed — do not silently raise
  it without understanding why.

---

## Alternatives Considered

1. **No circuit breaker, long timeout (30 s).** Rejected — single stuck B would exhaust A's
   carrier threads and DB connections within seconds.
2. **`@Retry` with 2 attempts.** Rejected — duplicates side effects; LoadBalancer already
   handles instance failover for connection errors.
3. **Bulkhead (`@Bulkhead`) instead of circuit breaker.** Considered as a complement, not a
   replacement. May be added later to cap concurrent outbound calls independently of Tomcat
   concurrency.
4. **WebClient (reactive) instead of RestClient (blocking).** Rejected in the virtual-threads
   era (ADR-0012) — blocking RestClient on a virtual thread gets the same scheduling benefits
   as WebClient without the reactive-programming cognitive tax.

---

## References

- ADR-0003 — HTTP Load Balancing (Spring Cloud LoadBalancer provides instance failover)
- ADR-0004 — Messaging vs HTTP (why this call is synchronous in the first place)
- ADR-0012 — Virtual Threads (why short timeouts matter for pool sizing)
- ADR-0015 — API Error Response Model (how `RouteUnavailableException` becomes HTTP 409)
- Resilience4j CircuitBreaker — https://resilience4j.readme.io/docs/circuitbreaker
- Michael Nygard — *Release It!* — Circuit Breaker and Fail Fast patterns
