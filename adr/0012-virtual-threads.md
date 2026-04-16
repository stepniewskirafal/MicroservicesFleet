# 0012 — Java 21 Virtual Threads (Project Loom)

**Status:** Accepted  
**Date:** 2026-04-16

---

## Context

All services in the fleet run on Java 21. The main performance bottleneck identified in
[plan-obsluga-setek-requestow.md](../plany/plan-obsluga-setek-requestow.md) was thread starvation:
200 default Tomcat OS threads competing for a limited HikariCP connection pool, with most threads
spending the majority of their time blocked on I/O (database queries, HTTP calls to
trade-route-planner, Kafka publishing).

Traditional OS threads are expensive (~1 MB stack each). A pool of 200 threads consumes ~200 MB
of memory just for stacks, and context-switching between them adds CPU overhead. Scaling beyond
a few hundred concurrent requests required either:

1. Reactive programming (WebFlux) — rejected as too invasive (see plan document).
2. Virtual threads (Project Loom) — lightweight, JVM-managed threads that unmount from the
   carrier OS thread when blocked on I/O.

---

## Decision

Enable virtual threads across all application services via:

```yaml
spring:
  threads:
    virtual:
      enabled: true
```

### What this changes

When `spring.threads.virtual.enabled: true` is set in Spring Boot 3.2+:

- **Tomcat** handles each HTTP request on a virtual thread instead of a platform thread from
  `server.tomcat.threads.max` pool. Thousands of concurrent requests can be served without
  increasing the OS thread count.
- **`@Async`**, **`@Scheduled`**, and Spring task executors automatically use virtual threads.
- **Spring Cloud Stream** Kafka consumers run on virtual threads.

### Where it is enabled

| Service              | Config file                                              | Virtual threads |
|----------------------|----------------------------------------------------------|:---------------:|
| starport-registry    | `starport-registry/src/main/resources/application.yml:11`    | yes             |
| trade-route-planner  | `trade-route-planner/src/main/resources/application.yml:5`   | yes             |
| telemetry-pipeline   | `telemetry-pipeline/src/main/resources/application.yml:5`    | yes             |
| eureka-server        | `eureka-server/src/main/resources/application.yml`           | no (not needed) |

Eureka-server serves only the Eureka dashboard and registry API with minimal concurrency
requirements — virtual threads provide no benefit there.

### Real usage: explicit virtual thread executor in starport-registry

Beyond the Tomcat-level integration, `ReservationCalculationService` explicitly uses a
virtual-thread-per-task executor to parallelize fee calculation and route planning:

```java
// starport-registry/.../ReservationCalculationService.java:19
private static final ExecutorService VIRTUAL_EXECUTOR =
        Executors.newVirtualThreadPerTaskExecutor();

@Override
public ReservationCalculation calculate(Long reservationId, ReserveBayCommand command) {
    // Fire fee + route computation concurrently on virtual threads
    CompletableFuture<BigDecimal> feeFuture =
            CompletableFuture.supplyAsync(() -> feeCalculator.calculateFee(command), VIRTUAL_EXECUTOR);

    CompletableFuture<Route> routeFuture =
            CompletableFuture.supplyAsync(() -> routePlanner.calculateRoute(command), VIRTUAL_EXECUTOR);

    BigDecimal calculatedFee = feeFuture.join();
    Route route = routeFuture.join();

    return new ReservationCalculation(reservationId, calculatedFee, route);
}
```

**Why this works well with virtual threads:** Both `feeCalculator` and `routePlanner` perform
blocking I/O (DB query and HTTP call respectively). On a platform thread, `join()` would hold an
OS thread hostage. On a virtual thread, the JVM unmounts the virtual thread from the carrier
during the blocking call, freeing the carrier for other work.

### Interaction with Tomcat thread pool tuning

starport-registry also configures Tomcat limits:

```yaml
server:
  tomcat:
    threads:
      max: 60
      min-spare: 15
    accept-count: 200
    max-connections: 2000
```

With virtual threads enabled, `threads.max` limits the **carrier thread pool** (OS threads that
actually execute virtual threads), not the number of concurrent requests. The `max-connections`
and `accept-count` settings still govern TCP-level admission. This means:

- Up to 2000 connections can be accepted simultaneously.
- Each is handled by a virtual thread that is scheduled onto one of 60 carrier threads.
- When a virtual thread blocks on I/O, its carrier thread is freed to run another virtual thread.

### Thread-safety validation

`ParallelRoutePlannerTest` validates that `PlanRouteService` is safe under concurrent access:

- JUnit 5 `@Execution(CONCURRENT)` with `@RepeatedTest(20)` — tests run in parallel on JUnit's
  thread pool.
- Internal concurrency test fires 40 simultaneous requests via `Executors.newFixedThreadPool(40)`
  and asserts no data corruption or counter drift.
- Atomic counter test verifies that Micrometer metrics are incremented correctly by 30 concurrent
  threads.

---

## Consequences

### Benefits

- **Scalability without complexity:** Thousands of concurrent requests handled with imperative
  blocking code — no reactive refactoring needed.
- **Lower memory footprint:** Virtual threads use ~1 KB stack (vs ~1 MB for OS threads), enabling
  far higher concurrency within the same JVM heap.
- **Simpler concurrency model:** `CompletableFuture` + virtual executor in
  `ReservationCalculationService` reads like sequential code but executes concurrently.
- **No code changes for Tomcat:** A single YAML property switches the entire request-handling
  model.

### Risks and caveats

- **`synchronized` blocks pin carrier threads:** Any `synchronized` block or `Object.wait()` call
  pins the virtual thread to its carrier, negating the benefit. Use `ReentrantLock` instead where
  possible. This project avoids `synchronized` in service code.
- **Thread-local abuse:** Large `ThreadLocal` values are multiplied by the number of virtual
  threads (potentially thousands). This project uses `ThreadLocalRandom` (safe — it is
  lightweight) but avoids storing heavy objects in thread-locals.
- **JDBC driver compatibility:** The PostgreSQL JDBC driver (42.x) is compatible with virtual
  threads. HikariCP connection pool acts as the natural throttle — only `maximum-pool-size`
  virtual threads can hold a DB connection at a time, preventing database overload.
- **Debugging:** Thread dumps show thousands of virtual threads, which can be noisy. Use
  `jcmd <pid> Thread.dump_to_file -format=json` for structured output.

---

## Alternatives Considered

1. **Reactive stack (Spring WebFlux + R2DBC):** Would require rewriting all services to
   non-blocking style. Much higher development cost, steeper learning curve, harder to debug.
   Virtual threads achieve similar scalability with imperative code.

2. **Large platform thread pool (e.g., 500 Tomcat threads):** Higher memory usage (~500 MB for
   stacks alone), more context switching, and still bounded by the thread count. Virtual threads
   scale better.

3. **Kotlin coroutines:** Would require a language migration. Virtual threads achieve the same
   goal while staying in pure Java.

---

## References

- [JEP 444: Virtual Threads](https://openjdk.org/jeps/444) (finalized in Java 21)
- [Spring Boot 3.2 Virtual Threads support](https://spring.io/blog/2023/09/09/all-together-now-spring-boot-3-2-graalvm-native-images-java-21-and-virtual)
- [plan-obsluga-setek-requestow.md](../plany/plan-obsluga-setek-requestow.md) — bottleneck
  analysis that motivated this decision
