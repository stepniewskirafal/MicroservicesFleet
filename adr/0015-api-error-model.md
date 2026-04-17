# 0015 — API Error Response Model & Versioning Policy

**Status:** Accepted
**Date:** 2026-04-17

---

## Context

Starport Registry and Trade Route Planner both expose REST endpoints. Clients (and other
services, e.g. A → B) need a consistent way to:

1. **Distinguish business failures from framework errors.** A `422 Unprocessable Entity`
   caused by a docking-bay conflict is very different from a malformed JSON request, even
   though both could be returned as 400 by default.
2. **Recognise breaking API changes.** Without URI versioning, adding a required field to
   `POST /reservations` silently breaks old clients.
3. **Parse error bodies programmatically.** Clients need machine-readable error codes
   (`ROUTE_UNAVAILABLE`, `STARPORT_NOT_FOUND`), not just prose.

Spring 6 introduced `ProblemDetail` (RFC 7807) as the idiomatic error model. This ADR
captures the choice **not** to adopt it yet, and the minimal conventions the services follow
instead.

---

## Decision

### Error response body

- **Framework exceptions** (validation, malformed JSON, generic 5xx) — return a flat
  `Map<String, String>` with keys `error` and optionally `details`.
- **Domain exceptions** (business rule violations) — return the same shape, with `error`
  containing a **stable machine-readable code** in `SCREAMING_SNAKE_CASE` and `details`
  containing a human-readable reason.
- **Route rejection (Service B)** — returns a typed record
  `RouteRejectedResponse(error, reason, details)` to capture the structured reason.

The fields are deliberately limited so a future migration to `ProblemDetail` is additive:
`error` maps to `type`/`title`, `details` maps to `detail`. No client code needs to change
beyond adding new field parsing.

### Exception → HTTP status mapping (authoritative)

| Exception                             | Service   | Status | `error` value                |
|---|---|---|---|
| `MethodArgumentNotValidException`     | A, B      | 422    | `VALIDATION_FAILED`          |
| `HttpMessageNotReadableException`     | A, B      | 400    | `MALFORMED_REQUEST`          |
| `NoDockingBaysAvailableException`     | A         | 409    | `NO_DOCKING_BAYS_AVAILABLE`  |
| `InvalidReservationTimeException`     | A         | 422    | `INVALID_RESERVATION_TIME`   |
| `StarportNotFoundException`           | A         | 404    | `STARPORT_NOT_FOUND`         |
| `CustomerNotFoundException`           | A         | 404    | `CUSTOMER_NOT_FOUND`         |
| `ShipNotFoundException`               | A         | 404    | `SHIP_NOT_FOUND`             |
| `RouteUnavailableException`           | A         | 409    | `ROUTE_UNAVAILABLE`          |
| `RouteRejectionException`             | B         | 422    | (per-rejection code)         |
| Uncaught `RuntimeException`           | A, B      | 500    | `INTERNAL_ERROR`             |

**409 vs 422** — 409 is used when the request is well-formed but conflicts with server state
(no bays, route unavailable). 422 is used when the payload is syntactically valid JSON but
semantically invalid (bad dates, failed bean validation).

### Versioning

- **Public, externally consumed endpoints** are prefixed with `/api/v1/`.
  - `starport-registry` — `ReservationController` is mapped to `/api/v1/starports`.
- **Internal, service-to-service endpoints** are **not** versioned.
  - `trade-route-planner` — `RoutePlannerController` is mapped to `/routes` and is only
    called by `starport-registry` via Eureka/LoadBalancer.

Rationale: internal APIs share a release cycle with their sole caller and can be versioned
by coordinated deploy. Public APIs must support breaking changes via v2 without moving
clients.

---

## How the codebase enforces this

### 1. `GlobalExceptionHandler` (starport-registry)

```java
// starport-registry/src/main/java/com/galactic/starport/controller/GlobalExceptionHandler.java:20+
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NoDockingBaysAvailableException.class)
    public ResponseEntity<Map<String, String>> handleNoDockingBays(NoDockingBaysAvailableException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "NO_DOCKING_BAYS_AVAILABLE",
                             "details", ex.getMessage()));
    }

    @ExceptionHandler(RouteUnavailableException.class)
    public ResponseEntity<Map<String, String>> handleRouteUnavailable(RouteUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "ROUTE_UNAVAILABLE",
                             "details", ex.getMessage()));
    }
    // ... similar handlers for every domain exception
}
```

### 2. `RoutePlannerExceptionHandler` (trade-route-planner)

```java
// trade-route-planner/.../adapter/in/rest/RoutePlannerExceptionHandler.java:15+
@RestControllerAdvice
public class RoutePlannerExceptionHandler {

    @ExceptionHandler(RouteRejectionException.class)
    public ResponseEntity<RouteRejectedResponse> handleRouteRejection(RouteRejectionException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new RouteRejectedResponse(ex.errorCode(), ex.reason(), ex.details()));
    }
}
```

`RouteRejectedResponse` is a Java record so the type system documents the contract. Clients
in `starport-registry` (`TradeRoutePlannerHttpAdapter`) can deserialize it without
ambiguity.

### 3. Versioned public endpoint

```java
// starport-registry/.../controller/ReservationController.java:21
@RestController
@RequestMapping("/api/v1/starports")
public class ReservationController { ... }
```

### 4. Unversioned internal endpoint

```java
// trade-route-planner/.../adapter/in/rest/RoutePlannerController.java:17
@RestController
@RequestMapping("/routes")
public class RoutePlannerController {

    @PostMapping("/plan")
    public RoutePlanResponse plan(@Valid @RequestBody PlanRouteRequest request) { ... }
}
```

### 5. Telemetry-pipeline has no `@ControllerAdvice`

`telemetry-pipeline` is Kafka-driven (Pipes & Filters, ADR-0001) and exposes no business
REST endpoints — only actuator endpoints. Error handling there is via the Spring Cloud
Stream consumer retry / DLQ pipeline (see ADR-0016).

---

## Consequences

### Benefits

- **Stable client contracts.** Clients match on the string codes in `error`, not on HTTP
  status alone. A `409` could mean several things; `ROUTE_UNAVAILABLE` is unambiguous.
- **Minimal surface area.** Two fields, one shape — easy to document, easy to mock in
  contract tests (`*ContractTest.java`, ADR-0006).
- **Future-proof.** Migration to RFC 7807 `ProblemDetail` adds fields (`type`, `title`,
  `status`, `detail`, `instance`) without renaming or removing any; clients parsing the
  current shape remain compatible.
- **Clear v1 boundary on public API.** Adding v2 is a new package / controller, not a
  cross-cutting rewrite.

### Trade-offs

- **Not RFC 7807 today.** Clients integrating with RFC-7807-native tooling (Spring
  RestClient error decoder, some OpenAPI generators) need a custom decoder. The value of
  switching is low while there are only two public endpoints.
- **Error codes are enforced by convention, not type.** A refactor could silently rename
  `ROUTE_UNAVAILABLE` to `route_unavailable` and break clients. Mitigation: contract tests
  (`*ContractTest.java`) pin the exact string literal.
- **Versioning policy split.** Public = versioned, internal = not — engineers must know
  which side of the boundary they are on. Documented here and enforced by code review.
- **No `Map<String, String>` schema in OpenAPI yet.** Springdoc is not configured
  (see the candidate-ADR backlog); OpenAPI would help pin down the error shape.

---

## Alternatives Considered

1. **Adopt `ProblemDetail` now.** Rejected as premature — only two public endpoints today,
   and migration is additive. Will be reconsidered when a third consumer lands or when
   Springdoc is adopted.
2. **Return plain strings.** Rejected — impossible for clients to distinguish business
   errors from framework errors without parsing prose.
3. **Header-based API versioning (`Accept: application/vnd.starport.v1+json`).** Rejected —
   obscure to callers, breaks browser testing, and has no upside over URI versioning at
   this scale.
4. **Global `/v1/` prefix for *all* services.** Rejected for internal APIs — adds ceremony
   without protecting against breakage the single caller is already coordinating.

---

## References

- ADR-0006 — Testing Strategy (contract tests pin error shapes)
- ADR-0014 — HTTP Resilience (source of the `ROUTE_UNAVAILABLE` error)
- RFC 7807 — Problem Details for HTTP APIs — https://datatracker.ietf.org/doc/html/rfc7807
- Spring 6 `ProblemDetail` —
  https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html
