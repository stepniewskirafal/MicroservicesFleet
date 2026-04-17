# 0024 — DTO / Domain / Entity Separation with Manual Mapping

**Status:** Accepted
**Date:** 2026-04-17

---

## Context

`starport-registry` is the only persistent service in the fleet (ADR-0018), and it
follows the Layered architecture (ADR-0001). The layered style needs a policy for how
data crosses each layer: a JPA entity loaded from the database should not end up as a
JSON payload returned to an HTTP client, and a JSON request should not be handed to a
`EntityManager.persist()` call verbatim.

There are three common failure modes:

1. **Entity-as-everything** — one class serves as JPA entity, service-layer model, and
   REST DTO. Jackson starts serialising Hibernate proxies; `LazyInitializationException`
   leaks through the controller; adding a computed field requires a DB migration.
2. **Reflection-driven mapping libraries** — MapStruct, ModelMapper, Dozer. Powerful,
   but add annotation processors, Lombok/records interaction quirks, and opaque
   stack traces on mapping bugs.
3. **Hand-written mapping without a convention** — five different mapper classes with
   different naming, some bidirectional, some one-way, half using constructors and
   half using setters.

This ADR picks the third path but makes the convention explicit so it does not drift.

---

## Decision

### Three classes per aggregate

For every aggregate root that crosses multiple layers:

| Layer       | Class type             | Example                 | Shape                | Framework deps              |
|---|---|---|---|---|
| API input   | Java record            | `ReservationCreateRequest` | Jakarta validation annotations | `jakarta.validation.*`     |
| API output  | Java record            | `ReservationResponse`   | Nested records, no proxies | none                        |
| Service command / domain | Java record (commands) or Lombok class (domain) | `ReserveBayCommand` / `Reservation` | Plain types, enums | `lombok.*`                 |
| JPA entity  | Lombok class           | `ReservationEntity`     | JPA annotations, lazy relations | `jakarta.persistence.*`, `hibernate.*` |

Naming:

- Requests: `*Request`, `*CreateRequest`, `*UpdateRequest`.
- Responses: `*Response`.
- Commands: `*Command` (input to a use case).
- Domain: the plain noun — `Reservation`, `Customer`, `Ship`.
- Entities: `*Entity`.

The `*Entity` suffix is **mandatory** for JPA classes. A `Reservation` and a
`ReservationEntity` are intentionally distinct types; a developer importing the wrong one
gets a compile error, not a runtime surprise.

### Records vs Lombok — a pragmatic split

- **Records** for all API DTOs (in and out) and all service commands.
  - Rationale: these types are **read-once**, crossed layer boundaries, serialised,
    and never mutated. Records are immutable by construction and generate
    `equals`/`hashCode`/`toString`.
  - `@Builder` (Lombok) is allowed on records where fluent construction is useful
    (many-field cases, test fixtures).
- **Lombok classes** for JPA entities and mutable domain types.
  - Rationale: JPA requires a no-arg constructor, settable fields (for Hibernate's
    dirty-checking), and bidirectional associations that records cannot express.
  - Mutable domain classes (`Reservation` — `setFeeCharged`, `confirm`) use Lombok
    because they model state machines where records' `with*`-style copying would
    produce a proliferation of new instances per transition.

The split is not "records are better" — it is "records for frozen data, classes for
state machines and ORM-managed objects".

### Manual mapping — `ReservationMapper`, `ReservationWebMapper`, ...

Every mapping is a plain Java method. No MapStruct, no ModelMapper, no reflection.

Two mappers per aggregate when both sides need conversion:

- `ReservationMapper` — `@Component` that converts `ReservationEntity ↔ Reservation`
  (infrastructure ↔ domain). Lives in the repository layer.
- `ReservationWebMapper` — `@Component` that converts
  `ReservationCreateRequest → ReserveBayCommand` and `Reservation → ReservationResponse`
  (API ↔ service). Lives in the controller layer.

A mapper has exactly one responsibility: copy fields. No validation, no DB access, no
side-effects, no logging.

### Transaction boundaries own the mapping

`ReservationMapper.toDomain(entity)` dereferences lazy relations (via `@EntityGraph`;
ADR-0013). It must be called **inside** the `@Transactional` method — `OSIV = false`
means outside a transaction those lazy accesses will throw. The mapping produces a plain
domain object that is safe to return past the transaction boundary.

This is non-negotiable: if a method returns a Hibernate-managed object to the controller,
OSIV=false plus Jackson serialization will break the call. The mapper is the gate.

### Defensive checks live in mappers

Mappers validate `null` inputs and fail fast:

```java
public Reservation toDomain(ReservationEntity entity) {
    Objects.requireNonNull(entity, "entity");
    ...
}
```

This is the **only** place defensive programming against nulls is encouraged. Domain
class constructors and service methods trust their callers; mappers are at the boundary
and earn the extra guard.

---

## How the codebase enforces this

### 1. Record DTO with validation

```java
// starport-registry/src/main/java/com/galactic/starport/controller/ReservationCreateRequest.java
public record ReservationCreateRequest(
        @NotBlank String customerCode,
        @NotBlank String shipCode,
        @NotBlank String shipClass,
        @NotNull @Future Instant startAt,
        @NotNull @Future Instant endAt,
        boolean requestRoute,
        String originPortId) {}
```

### 2. Record response with nested record

```java
// starport-registry/src/main/java/com/galactic/starport/controller/ReservationResponse.java
@Builder
public record ReservationResponse(
        Long reservationId,
        String starportCode,
        Integer bayNumber,
        Instant startAt,
        Instant endAt,
        BigDecimal feeCharged,
        Route route) {
    @Builder
    public record Route(String routeCode, Double etaHours, Double riskScore) {}
}
```

Nested records keep the response self-contained; a client reads one record definition and
knows the entire surface.

### 3. Record command

```java
// starport-registry/src/main/java/com/galactic/starport/service/ReserveBayCommand.java
@Builder
public record ReserveBayCommand(
        String startStarportCode,
        String destinationStarportCode,
        String customerCode,
        String shipCode,
        String shipClass,
        Instant startAt,
        Instant endAt,
        boolean requestRoute) {}
```

`@Builder` makes tests readable (`ReserveBayCommand.builder().startAt(t).endAt(t2).build()`)
while the record keeps it immutable.

### 4. Lombok domain class

```java
// starport-registry/src/main/java/com/galactic/starport/service/Reservation.java
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Reservation {
    private Long id;
    private Starport starport;
    private DockingBay dockingBay;
    private Customer customer;
    private Ship ship;
    private Instant startAt;
    private Instant endAt;
    private BigDecimal feeCharged;
    private ReservationStatus status;
    private Route route;

    public enum ReservationStatus { HOLD, CONFIRMED, CANCELLED }
}
```

Mutable because the service layer transitions `status` and sets `feeCharged` after
computation (ADR-0020). The `*Entity` is what Hibernate sees; this `Reservation` is what
the service layer works with.

### 5. JPA entity

```java
// starport-registry/src/main/java/com/galactic/starport/repository/ReservationEntity.java
@Entity
@Table(name = "reservation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ReservationEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private StarportEntity starportEntity;
    // ... 4 more lazy relations (ADR-0013)

    @Enumerated(EnumType.STRING)
    private ReservationStatus status;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist  void prePersist()  { this.createdAt = Instant.now(); }
    @PreUpdate   void preUpdate()   { this.updatedAt = Instant.now(); }

    public void confirmReservation(Long id, BigDecimal fee, Route route) {
        this.feeCharged = fee;
        this.status = ReservationStatus.CONFIRMED;
        ...
    }
}
```

Note: `@NoArgsConstructor(access = PROTECTED)` — JPA needs it, nothing else should.
`@Builder` helps tests construct entities directly (bypassing the mapper) when exercising
the repository layer.

### 6. Infrastructure ↔ domain mapper

```java
// starport-registry/.../repository/ReservationMapper.java
@Component
public class ReservationMapper {

    public Reservation toDomain(ReservationEntity entity) {
        Objects.requireNonNull(entity, "entity");
        return Reservation.builder()
                .id(entity.getId())
                .starport(mapStarport(entity.getStarportEntity()))
                .dockingBay(mapDockingBay(entity.getDockingBay()))
                .customer(mapCustomer(entity.getCustomer()))
                .ship(mapShip(entity.getShip()))
                .startAt(entity.getStartAt())
                .endAt(entity.getEndAt())
                .feeCharged(entity.getFeeCharged())
                .status(entity.getStatus())
                .route(mapRoute(entity.getRoute()))
                .build();
    }

    private Customer mapCustomer(CustomerEntity e) { ... }
    private Ship     mapShip(ShipEntity e)         { ... }
    // etc.
}
```

Called from inside the `@Transactional` method in `JpaStarportRepository` (ADR-0013),
where the lazy relations are already loaded via `@EntityGraph`.

### 7. API ↔ service mapper

```java
// starport-registry/.../controller/ReservationWebMapper.java
@Component
public class ReservationWebMapper {

    public ReserveBayCommand toCommand(String starportCode, ReservationCreateRequest req) {
        return ReserveBayCommand.builder()
                .startStarportCode(starportCode)
                .destinationStarportCode(req.originPortId())
                .customerCode(req.customerCode())
                .shipCode(req.shipCode())
                .shipClass(req.shipClass())
                .startAt(req.startAt())
                .endAt(req.endAt())
                .requestRoute(req.requestRoute())
                .build();
    }

    public ReservationResponse toResponse(Reservation domain) { ... }
}
```

Pure field copy. No DB access, no observations, no exceptions beyond NPE on null input.

### 8. Package layout

```
com.galactic.starport.controller     — *Request, *Response, *WebMapper
com.galactic.starport.service        — *Command, Reservation, Customer, Ship, Starport
com.galactic.starport.repository     — *Entity, *Mapper, *Repository
```

The mapper sits in the layer that *owns* the target type:

- `ReservationWebMapper` converts to `ReserveBayCommand` — it lives in the web layer
  (the caller).
- `ReservationMapper` converts to `Reservation` — it lives in the repository layer
  (also the caller). Using the domain package would give the domain a dependency on
  `*Entity`; unacceptable.

---

## Consequences

### Benefits

- **No Hibernate proxies in the HTTP response.** Jackson sees a plain `Reservation` (or
  `ReservationResponse`), not a lazy proxy. `OSIV=false` (ADR-0013) stays safe.
- **Layer boundaries are visible.** A field added to `ReservationEntity` is not
  automatically exposed in the API — someone must edit the mapper, which is reviewable.
- **Stack traces are readable.** A null in a mapper fails with a line number, not a
  generic `MappingException` from a reflection library.
- **No code-generator surprises.** No `target/generated-sources/annotations/...MapperImpl.java`
  file to debug; what you read is what runs.
- **Records give cheap immutability.** API DTOs and commands are impossible to mutate
  after construction; concurrent code cannot corrupt them.

### Trade-offs

- **Boilerplate.** A `Reservation` has ten fields; `ReservationMapper.toDomain()` is ten
  lines of `.builder().x(entity.getX())`. MapStruct would write this for us.
- **Two changes for one field.** Adding a field to the reservation requires editing
  both the entity and the mapper (plus tests). Worth it to keep the boundary explicit;
  annoying on a green-field change.
- **Nested-record builder duplication.** `ReservationResponse.Route` and
  `ReservationResponse` both carry `@Builder`. Minor noise.
- **Mappers are trusted.** A bug that skips a field in `toDomain()` silently truncates
  data. Mitigation: `*RepositoryTest` integration tests round-trip entities through
  `save → find → toDomain` and assert every field.
- **No reverse entity mapping.** `Reservation → ReservationEntity` is intentionally not
  provided, because entity construction happens in JPA, not from a domain object. If a
  feature needed this (rare), the mapping would be written new; we do not pre-bake it.
- **Lombok on domain classes leaks `toString` / `equals` behaviour.** `@Getter @Setter`
  generates nothing; but some future annotation (`@ToString`) could accidentally include
  lazy collections. A code-review check; ArchUnit cannot catch this.

---

## Alternatives Considered

1. **MapStruct.** Great fit in larger systems. Rejected here because: (a) the mapper
   surface is small — four aggregates, two mappers each, eight classes total; (b) records
   + MapStruct interaction has historical quirks (e.g., `@Builder`-generated
   constructors); (c) runtime-generated code complicates stack traces and debugging for
   this team's current fluency level.
2. **One mapper per pair** (e.g. `ReservationEntityToDomainMapper` +
   `ReservationDomainToApiResponseMapper`). Rejected — four classes per aggregate; too
   granular at this scale.
3. **Single universal class** (entity is the DTO is the domain model). Rejected as the
   archetypal anti-pattern. Kills testability, forces JPA annotations on the REST
   payload, and makes it impossible to evolve API and schema independently.
4. **Projection DTOs with Spring Data** (`List<ReservationProjection> find(...)`). Viable
   for read paths and already partially used (`*RepositoryTest` patterns). Not expanded
   to the write path because the mapper gives richer type safety and null-guarding.
5. **Lombok `@Value` for domain classes instead of `@Getter @Setter`.** `@Value` produces
   a final, immutable class — closer to a record. Rejected because the `Reservation`
   domain class needs to transition state (`HOLD → CONFIRMED`) in place; `@Value` would
   force a clone per transition and would not be ORM-friendly.

---

## References

- ADR-0001 — Architecture Styles (Layered in starport-registry)
- ADR-0006 — Testing Strategy (round-trip tests exercise mappers)
- ADR-0011 — Architecture Rules (ArchUnit: enforce directional dependencies between
  controller / service / repository)
- ADR-0013 — OSIV disabled (why mappers must run inside a transaction)
- ADR-0015 — API Error Model (DTOs live in controller package for co-location with
  `GlobalExceptionHandler`)
- ADR-0018 — Flyway (`*Entity` ↔ migration-defined schema; `ddl-auto: validate` in tests
  catches entity/column drift)
- ADR-0020 — Concurrent Reservation Safety (uses `Reservation.status` state machine)
- ADR-0023 — Validation Strategy (DTOs carry `@Valid` annotations)
- Vlad Mihalcea — *High-Performance Java Persistence*, Ch. 7 "JPA vs. JDBC"
