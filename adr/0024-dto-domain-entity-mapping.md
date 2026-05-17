# 0024 — DTO / Domain / Entity Separation with Manual Mapping

**Status:** Accepted
**Date:** 2026-04-17

---

## Context

`starport-registry` is the only persistent service (ADR-0018) and follows the layered
style (ADR-0001). Data crosses three layers: REST, service, JPA. The classic failure
modes are entity-as-everything (Jackson serialises Hibernate proxies, `OSIV=false` blows
up), reflection-driven mappers (MapStruct/ModelMapper — opaque stack traces, codegen
surprises), and hand-mapping without a convention (drift across five different mapper
styles).

---

## Decision

Three (sometimes four) class types per aggregate, each in its own layer:

| Layer        | Type                       | Example                    |
|---|---|---|
| API input    | Java record + Jakarta validation | `ReservationCreateRequest` |
| API output   | Java record (nested records OK) | `ReservationResponse`   |
| Service      | Record (commands) / Lombok class (domain) | `ReserveBayCommand`, `Reservation` |
| Persistence  | Lombok `@Entity` class     | `ReservationEntity`        |

**Naming is mandatory**: `*Request` / `*Response` / `*Command` / `*Entity`; domain is the
plain noun. The `*Entity` suffix prevents a developer importing the wrong `Reservation`
at compile time.

**Records vs Lombok**: records for frozen data crossing boundaries (DTOs, commands),
Lombok classes for ORM-managed types and mutable domain state machines
(`HOLD → CONFIRMED`). Not "records are better" — different jobs.

**Manual mapping** via plain `@Component` mappers; no MapStruct/ModelMapper/reflection.
Two mappers per aggregate:

- `ReservationMapper` (repository layer) — `Entity ↔ Reservation`. Called **inside**
  the `@Transactional` method so lazy associations (loaded via `@EntityGraph`, ADR-0013)
  resolve before crossing the transaction boundary.
- `ReservationWebMapper` (controller layer) — `Request → Command`, `Reservation →
  Response`.

Each mapper only copies fields and `Objects.requireNonNull`s its input. No DB access, no
logging, no side-effects. Mappers live in the package of the *target* type's caller
(repository mapper in repo package, web mapper in controller package) so the domain
package never depends on `*Entity`.

---

## Why

- **No Hibernate proxies escape the service layer.** Jackson sees plain domain objects;
  `OSIV=false` (ADR-0013) stays safe.
- **Layer boundaries are visible.** A new entity field is not auto-exposed in the API —
  the mapper edit is reviewable.
- **Readable stack traces.** A null in a mapper has a line number; not a generic
  `MappingException` from a reflection library.
- **No codegen to debug.** What you read is what runs.
- **Records give cheap immutability** for DTOs and commands; mutation impossible after
  construction.

---

## Alternatives

- **MapStruct** — great in larger systems; here the surface is small (~8 classes), and
  record + `@Builder` interactions have historical quirks.
- **One mapper per direction** (`EntityToDomain` + `DomainToResponse`) — four mappers per
  aggregate, too granular.
- **Entity = DTO = domain** — the archetypal anti-pattern; forces JPA annotations into
  the REST payload and ties API evolution to schema.
- **Spring Data projections only** — used for read paths already; not expanded to writes
  because the mapper gives better null-guarding and type safety.
- **Lombok `@Value` for domain** — produces immutable classes; rejected because
  `Reservation` mutates state in place, and `@Value` is not ORM-friendly anyway.

---

## References

- ADR-0001 — Architecture Styles (Layered)
- ADR-0011 — Architecture Rules (ArchUnit enforces directional dependencies)
- ADR-0013 — OSIV disabled (why mappers must run inside the transaction)
- ADR-0015 — API Error Model
- ADR-0018 — Flyway (entity vs migration-defined schema)
- ADR-0023 — Validation Strategy (`@Valid` on the request DTOs)
