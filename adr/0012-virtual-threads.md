# 0012 — Java 21 Virtual Threads (Project Loom)

**Status:** Accepted  
**Date:** 2026-04-16

---

## Context

All services run on Java 21. The bottleneck identified in
[plan-obsluga-setek-requestow.md](../plany/plan-obsluga-setek-requestow.md) is thread
starvation: 200 Tomcat OS threads spending most of their time blocked on I/O (DB queries,
HTTP to trade-route-planner, Kafka). Each platform thread costs ~1 MB stack; scaling
beyond a few hundred concurrent requests required either rewriting to WebFlux (too
invasive) or adopting virtual threads.

---

## Decision

Enable virtual threads on every application service:

```yaml
spring:
  threads:
    virtual:
      enabled: true
```

This makes Tomcat, `@Async`, `@Scheduled`, and Spring Cloud Stream consumers all use
virtual threads. `starport-registry` additionally uses an explicit
`Executors.newVirtualThreadPerTaskExecutor()` in `ReservationCalculationService` to fan
out fee calculation and route planning concurrently. Tomcat `threads.max: 60` now
sizes the **carrier** pool, while `max-connections: 2000` admits requests. Eureka-server
is left on platform threads — no concurrency need.

---

## Why

- **Imperative code, reactive scalability.** Thousands of concurrent requests with
  ordinary blocking code; no WebFlux rewrite.
- **~1 KB stacks** vs ~1 MB — vastly higher concurrency in the same heap.
- **One-line switch** at the Tomcat level; no per-controller change.
- **HikariCP is the natural throttle.** Only `maximum-pool-size` virtual threads can
  hold a DB connection at once.

---

## Alternatives

- **Spring WebFlux + R2DBC** — rewrite cost too high; reactive cognitive tax.
- **Larger platform pool (500 threads)** — ~500 MB just for stacks, more context
  switching, still bounded.
- **Kotlin coroutines** — would force a language migration.

---

## Caveats

- `synchronized` blocks pin the carrier — use `ReentrantLock` if contention matters.
- Avoid heavyweight `ThreadLocal` values (multiplied across all virtual threads).

---

## References

- [JEP 444 — Virtual Threads](https://openjdk.org/jeps/444)
- [Spring Boot 3.2 virtual threads](https://spring.io/blog/2023/09/09/all-together-now-spring-boot-3-2-graalvm-native-images-java-21-and-virtual)
- [plan-obsluga-setek-requestow.md](../plany/plan-obsluga-setek-requestow.md) — bottleneck analysis
