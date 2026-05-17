# 0013 — Disable Open Session in View (OSIV)

**Status:** Accepted  
**Date:** 2026-04-16

---

## Context

Spring Boot defaults `spring.jpa.open-in-view: true`, keeping the Hibernate session (and
the JDBC connection) open for the entire HTTP request. That convenience hides N+1
queries fired during JSON serialization and pins a DB connection across non-DB work
(e.g. the HTTP call to trade-route-planner), which exhausts the 30-slot HikariCP pool
under load — especially combined with virtual threads (ADR-0012).

---

## Decision

Disable OSIV in `starport-registry`:

```yaml
spring:
  jpa:
    open-in-view: false
```

Every entity association is `FetchType.LAZY`. `ReservationRepository.findById` declares
an `@EntityGraph` listing all five relations so the full reservation graph loads in one
JOIN. Entity → domain mapping happens inside `@Transactional` methods
(`JpaStarportRepository.confirmReservation`). `toModel()` on `StarportEntity` /
`CustomerEntity` deliberately skips lazy collections that the reservation flow does not
need.

---

## Why

- **No silent N+1.** Lazy access outside a transaction throws
  `LazyInitializationException` immediately — design errors surface at the call site, not
  as production latency.
- **Connections released early.** Reservation flow holds a connection for `TX1` only,
  drops it during the route-planning HTTP call, reacquires for `TX2`. 30 connections
  serve hundreds of concurrent virtual threads.
- **Clean domain objects.** No Hibernate proxies leak past the repository layer.
- **Deterministic.** Controllers, async handlers, and tests all behave the same.

---

## Alternatives

- **Keep OSIV on** — silent N+1, pool exhaustion under load.
- **Eagerly fetch everything** — over-fetches on every query; worse than `@EntityGraph`.
- **Open-in-view + DTO projections only** — half-measure; the trap is still armed for
  any future getter call.

---

## References

- [Vlad Mihalcea — OSIV anti-pattern](https://vladmihalcea.com/the-open-session-in-view-anti-pattern/)
- [Spring Boot OSIV warning](https://docs.spring.io/spring-boot/docs/current/reference/htmlsingle/#data.sql.jpa-and-spring-data.open-entity-manager-in-view)
- ADR-0012 — Virtual Threads (why connection release matters)
- [plan-obsluga-setek-requestow.md](../plany/plan-obsluga-setek-requestow.md) — HikariCP sizing
