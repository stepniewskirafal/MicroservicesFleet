# 0013 — Disabling Open Session in View (OSIV) in starport-registry

**Status:** Accepted  
**Date:** 2026-04-16

---

## Context

Spring Boot enables `spring.jpa.open-in-view` by default (`true`). This means the Hibernate
`Session` (and therefore the JDBC connection) stays open for the **entire HTTP request** — from
the moment the controller receives the request until the response is fully serialized to the
client.

The intent is convenience: lazy-loaded associations can be transparently fetched in the view
layer (controller, JSON serializer) without an explicit transaction.

In practice this is known as the **OSIV anti-pattern** because:

1. **Silent N+1 queries** — Lazy associations accessed during JSON serialization fire individual
   `SELECT` statements outside any `@Transactional` boundary. With 5 lazy relations on
   `ReservationEntity`, serializing a list of 20 reservations can trigger 100 extra queries
   that are invisible in service-layer code.

2. **Connection holding** — The JDBC connection is held for the full request duration, including
   time spent on non-DB work (route planning HTTP call, fee calculation). With a HikariCP pool
   of 30 connections and 60 Tomcat threads, OSIV means a slow downstream call holds a DB
   connection hostage, leading to pool exhaustion under load.

3. **Unpredictable behavior** — Whether data is fetched depends on when/where the getter is
   called. Moving code from a controller to an async handler silently breaks lazy loading with
   `LazyInitializationException`.

---

## Decision

Disable OSIV in starport-registry:

```yaml
# starport-registry/src/main/resources/application.yml:33
spring:
  jpa:
    open-in-view: false
```

All data access must happen within explicit `@Transactional` boundaries. Lazy associations
accessed outside a transaction will immediately throw `LazyInitializationException` — making
N+1 problems a compile-time-visible design error rather than a silent runtime performance bug.

---

## How the codebase enforces this

### 1. Every entity relation is `FetchType.LAZY` (explicit)

`ReservationEntity` has **5 lazy `@ManyToOne` associations** — none is eagerly fetched:

```java
// ReservationEntity.java:39-51,68
@ManyToOne(fetch = FetchType.LAZY, optional = false)
private StarportEntity starportEntity;

@ManyToOne(fetch = FetchType.LAZY, optional = false)
private DockingBayEntity dockingBay;

@ManyToOne(fetch = FetchType.LAZY, optional = false)
private CustomerEntity customer;

@ManyToOne(fetch = FetchType.LAZY, optional = false)
private ShipEntity ship;

@ManyToOne(fetch = FetchType.LAZY, optional = true, cascade = CascadeType.ALL)
private RouteEntity route;
```

Other entities follow the same pattern:
- `DockingBayEntity.starport` — `@ManyToOne(fetch = FetchType.LAZY)` (:36)
- `ShipEntity.customer` — `@ManyToOne(fetch = FetchType.LAZY)` (:36)
- `StarportEntity.dockingBays` — `@OneToMany(fetch = FetchType.LAZY)` (:54)
- `CustomerEntity.ships` — `@OneToMany(fetch = FetchType.LAZY)` (:46)

With OSIV on, any getter call in the controller would silently fetch these. With OSIV off,
the code **must** fetch them within a transaction or get `LazyInitializationException`.

### 2. `@EntityGraph` on `findById` — single query for all associations

Instead of relying on lazy loading, the repository declares an `@EntityGraph` that fetches
**all 5 relations in one JOIN query**:

```java
// ReservationRepository.java:9-10
@EntityGraph(attributePaths = {"starportEntity", "dockingBay", "customer", "ship", "route"})
Optional<ReservationEntity> findById(Long id);
```

This generates a single SQL `SELECT` with `LEFT JOIN` for all five relations — **zero N+1
queries**. Without OSIV, this is the only way to load the full reservation graph, and it
forces the developer to be explicit about what data is needed.

### 3. `ReservationMapper` — entity-to-domain conversion inside the transaction

`JpaStarportRepository.confirmReservation()` calls the mapper **inside** a `@Transactional`
method:

```java
// JpaStarportRepository.java:34-38
@Transactional
public Optional<Reservation> confirmReservation(Long reservationId, BigDecimal calculatedFee, Route route) {
    return reservationRepository.findById(reservationId).map(entity -> {
        entity.confirmReservation(reservationId, calculatedFee, route);
        return reservationMapper.toDomain(entity);  // <-- within @Transactional
    });
}
```

`ReservationMapper.toDomain()` accesses all 5 lazy relations (`entity.getCustomer()`,
`entity.getShip()`, `entity.getStarportEntity()`, `entity.getDockingBay()`,
`entity.getRoute()`). This works because:

1. The `@EntityGraph` on `findById` already fetched them in the initial query (no proxy).
2. Even if it hadn't, the Hibernate session is open within the `@Transactional` scope.

The mapper produces a plain domain object (`Reservation`) with no Hibernate proxies — safe to
use anywhere after the transaction closes.

### 4. `@OneToMany` collections use `@Fetch(FetchMode.SELECT)` and are not accessed

`StarportEntity.dockingBays` and `CustomerEntity.ships` are mapped as lazy collections:

```java
// StarportEntity.java:54-56
@OneToMany(mappedBy = "starport", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
@Fetch(FetchMode.SELECT)
private List<DockingBayEntity> dockingBays = new ArrayList<>();
```

The `toModel()` methods on these entities deliberately **skip** the collection:

```java
// StarportEntity.java:58-65
public Starport toModel() {
    return Starport.builder()
            .id(this.id)
            .code(this.code)
            .name(this.name)
            .description(this.description)
            .build();          // no .dockingBays() — intentionally omitted
}
```

```java
// CustomerEntity.java:70-78
public Customer toModel() {
    return Customer.builder()
            .id(this.id)
            .customerCode(this.customerCode)
            .name(this.name)
            .createdAt(this.createdAt)
            .updatedAt(this.updatedAt)
            .build();          // no .ships() — intentionally omitted
}
```

With OSIV off, touching `.getDockingBays()` outside a transaction would throw
`LazyInitializationException`. The code avoids this by design — collections are simply not
needed for the reservation flow.

### 5. Connection release — critical for virtual threads + HikariCP pool

This decision interacts with two other architectural choices:

- **Virtual threads** (ADR-0012): Thousands of concurrent requests on virtual threads. If OSIV
  held a connection for each request's full lifetime (including the `routePlanner` HTTP call in
  `ReservationCalculationService`), the 30-connection HikariCP pool would be exhausted by 30
  concurrent requests — negating the benefit of virtual threads.

- **HikariCP pool of 30** (tuned in `plan-obsluga-setek-requestow.md`): With OSIV off, the
  connection is acquired at `@Transactional` entry and released at exit. The reservation flow
  has two transactional phases:

  ```
  Request ──► [TX1: createHoldReservation] ──► conn released
          ──► [no TX: fee calc + route planning (HTTP)] ──► no conn held
          ──► [TX2: confirmReservation] ──► conn released
  ```

  The HTTP call to trade-route-planner (potentially 2-3s) does **not** hold a DB connection.
  This is why 30 connections can serve hundreds of concurrent requests.

---

## Consequences

### Benefits

- **No hidden N+1 queries** — Every data fetch is explicit in the repository layer. Silent
  performance degradation cannot occur.
- **JDBC connections released early** — Connections return to the pool between transactional
  boundaries, enabling high concurrency with a small pool.
- **Clean architecture enforcement** — Domain objects (`Reservation`, `Starport`, etc.) are
  plain POJOs. No Hibernate proxies leak outside the repository layer.
- **Deterministic behavior** — Code works the same in controllers, async handlers, event
  listeners, and tests.

### Trade-offs

- **`LazyInitializationException` as a guard** — Forgetting to fetch a relation in the
  repository layer causes an immediate runtime exception. This is a feature, not a bug — it
  surfaces the problem early instead of silently degrading performance.
- **More explicit data fetching** — Developers must use `@EntityGraph`, `JOIN FETCH` in JPQL,
  or projections. This requires more upfront thought but produces better SQL.

---

## References

- [Vlad Mihalcea — The OSIV Anti-Pattern](https://vladmihalcea.com/the-open-session-in-view-anti-pattern/)
- [Spring Boot OSIV warning](https://docs.spring.io/spring-boot/docs/current/reference/htmlsingle/#data.sql.jpa-and-spring-data.open-entity-manager-in-view)
- [ADR-0012 — Virtual Threads](0012-virtual-threads.md) — why connection release matters
- [plan-obsluga-setek-requestow.md](../plany/plan-obsluga-setek-requestow.md) — HikariCP pool sizing
