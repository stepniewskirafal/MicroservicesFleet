# 0015 — API Error Response Model & Versioning

**Status:** Accepted  
**Date:** 2026-04-17

---

## Context

Clients need to distinguish business failures from framework errors (`422` for a docking
conflict vs `400` for bad JSON), parse error bodies programmatically, and recognise
breaking API changes. Spring 6 ships `ProblemDetail` (RFC 7807), but with only two public
endpoints today the migration is not yet worth it.

---

## Decision

**Error body shape** — flat `Map<String, String>` with `error` (stable
`SCREAMING_SNAKE_CASE` code) and `details` (human-readable). Route rejections from B use
a typed `RouteRejectedResponse(error, reason, details)` record. Both fields map cleanly
onto a future `ProblemDetail` migration (`error → title`, `details → detail`).

**Exception → status mapping** (authoritative):

| Exception                          | Status | `error`                     |
|------------------------------------|--------|-----------------------------|
| `MethodArgumentNotValidException`  | 422    | `VALIDATION_FAILED`         |
| `HttpMessageNotReadableException`  | 400    | `MALFORMED_REQUEST`         |
| `NoDockingBaysAvailableException`  | 409    | `NO_DOCKING_BAYS_AVAILABLE` |
| `InvalidReservationTimeException`  | 422    | `INVALID_RESERVATION_TIME`  |
| `StarportNotFoundException`        | 404    | `STARPORT_NOT_FOUND`        |
| `CustomerNotFoundException`        | 404    | `CUSTOMER_NOT_FOUND`        |
| `ShipNotFoundException`            | 404    | `SHIP_NOT_FOUND`            |
| `RouteUnavailableException`        | 409    | `ROUTE_UNAVAILABLE`         |
| `RouteRejectionException` (B)      | 422    | per-rejection code          |
| Uncaught `RuntimeException`        | 500    | `INTERNAL_ERROR`            |

**409 vs 422** — 409 = well-formed request, conflicts with server state. 422 = valid
JSON, semantically invalid.

**Versioning** — public endpoints prefixed `/api/v1/`
(`ReservationController → /api/v1/starports`). Internal service-to-service endpoints are
**unversioned** (`RoutePlannerController → /routes`) because they share a release cycle
with their sole caller.

`telemetry-pipeline` has no `@ControllerAdvice` — it is Kafka-driven (ADR-0001); error
handling is via the consumer retry / DLQ pipeline (ADR-0016).

---

## Why

- **Stable contracts.** Clients match on `error` codes; `409 + ROUTE_UNAVAILABLE` is
  unambiguous in a way that bare `409` is not.
- **Minimal surface.** Two fields, one shape; trivial to assert in
  `*ContractTest.java`.
- **Future-proof.** Migrating to `ProblemDetail` is additive — no client breaks.
- **Clear v1 boundary.** Adding v2 is a new package, not a cross-cutting rewrite.

---

## Alternatives

- **Adopt `ProblemDetail` now** — premature with only two public endpoints; revisit
  when Springdoc lands.
- **Plain string error bodies** — clients can't distinguish failure classes without
  parsing prose.
- **Header-based versioning (`Accept: vnd.starport.v1+json`)** — obscure, breaks
  browser testing, no upside at this scale.
- **Version internal APIs too** — ceremony without benefit; the single caller
  coordinates.

---

## References

- ADR-0006 — Testing Strategy (contract tests pin error shapes)
- ADR-0014 — HTTP Resilience (source of `ROUTE_UNAVAILABLE`)
- [RFC 7807](https://datatracker.ietf.org/doc/html/rfc7807)
- [Spring 6 `ProblemDetail`](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html)
