# 0023 — Validation Strategy: Jakarta Bean Validation + Chain-of-Responsibility Rules

**Status:** Accepted
**Date:** 2026-04-17

---

## Context

Input validation in a reservation service has two very different concerns that keep
tempting engineers to merge them:

1. **Request syntax** — "is this JSON parseable, are the required fields present, are
   dates in a valid format, are strings non-blank?" Answerable without touching the
   database or the domain. Should fail fast before any business logic runs.
2. **Business rules** — "does the starport with this code exist, is the ship's class
   compatible with the bay, is `startAt` strictly before `endAt` *and* not in the past,
   is the customer allowed to reserve?" Needs database access, domain knowledge, and
   produces meaningful domain exceptions.

Mixing them is the classic anti-pattern: controllers that run `@Valid` **and** call
`starportRepository.findById()`; or services that re-check `@NotBlank` on a field the
controller supposedly already validated; or worst of all, validators that throw
`ConstraintViolationException` from deep inside the persistence layer.

This ADR separates the two with a clear boundary and assigns a specific technology to
each.

---

## Decision

### Two layers, two technologies

| Layer         | Tool                               | What it rejects                      | HTTP status              |
|---|---|---|---|
| Request syntax | Jakarta Bean Validation (`@Valid`) | Missing / malformed / blank fields   | 400 / 422 (ADR-0015)     |
| Business rules | `ReserveBayCommandValidationRule` chain | Non-existent starport, bad time window, other domain invariants | 404 / 409 / 422 (ADR-0015) |

**Bean Validation is framework-level and runs at the controller boundary.**
**The rules chain is domain-level and runs inside `ReserveBayValidationService`.**

No rule may be enforced in both places. If a rule needs DB access, it lives in the chain.
If it is syntactic, it lives on the DTO.

### Bean Validation conventions

- Every request body is a **Java record** annotated on the fields, not on getter-style
  methods.
- The controller parameter is annotated `@Valid`. Spring wires the validation; failures
  surface as `MethodArgumentNotValidException` → `GlobalExceptionHandler` →
  `VALIDATION_FAILED` + HTTP 422 (ADR-0015).
- Standard annotations are preferred; the project does **not** introduce custom
  `@Constraint` types today. A custom constraint would be justified only for cross-field
  rules (`startAt < endAt`) — and even those currently live in the rule chain instead.

### Chain-of-Responsibility rules

- Every rule implements `ReserveBayCommandValidationRule` (a functional interface taking
  a `ReserveBayCommand` and returning a `ValidationOutcome` / throwing a specific
  domain exception).
- Each rule is a single `@Component`, `@Order(N)`-ed so the sequence is explicit.
- `ReserveBayValidationService` is `@Service`-annotated, autowires `List<...Rule>`
  (Spring sorts by `@Order`), and runs them sequentially, short-circuiting on the first
  failure.
- A rule **must throw a typed domain exception** — `StarportNotFoundException`,
  `InvalidReservationTimeException`, etc. — not a generic `IllegalArgumentException`.
  The exception maps to the correct HTTP status via ADR-0015.

### Ordering is deliberate

Rules run **cheapest-first**:

```
@Order(0)  ReservationTimeValidationRule       — no I/O, purely on the command
@Order(10) StartStarportValidationRule         — DB lookup
@Order(20) DestinationStarportValidationRule   — DB lookup
```

Cheap rules fail fast without hitting the DB; expensive rules run only once we know the
cheap ones passed. Reversing the order would not be "wrong" but would waste DB
round-trips on obviously-bad requests.

### Domain invariants (defensive constructors) — explicitly *not* used today

The codebase does **not** run validation in domain-class constructors. `Reservation`,
`Customer`, `Ship` etc. trust their callers. The chain of rules + Bean Validation catches
invalid input at the application boundary; by the time a `Reservation` is constructed,
the data is already validated.

This is a **pragmatic choice**, not a recommendation for new services. It trades safety
(a bug that sneaks past the chain can produce an invalid domain object) for a simpler
persistence model (`ReservationEntity` needs a default constructor for Hibernate; adding
constructor validation would require dual invariant-check paths).

Mitigation: unit tests on the domain classes assert basic invariants, and the integration
tests (ADR-0006) catch missed validations end-to-end.

---

## How the codebase enforces this

### 1. Request DTO with Jakarta annotations

```java
// starport-registry/src/main/java/com/galactic/starport/controller/ReservationCreateRequest.java:8-17
public record ReservationCreateRequest(
        @NotBlank String customerCode,
        @NotBlank String shipCode,
        @NotBlank String shipClass,
        @NotNull @Future Instant startAt,
        @NotNull @Future Instant endAt,
        boolean requestRoute,
        String originPortId) {}
```

- Records force immutability; no setters to accidentally mutate after validation.
- `@NotBlank` distinguishes "null or empty or whitespace" from `@NotNull`.
- `@Future` rejects past timestamps at the boundary — the rule chain does not re-check
  this.
- `requestRoute` is primitive `boolean` (defaults to `false` when absent) — a deliberate
  choice; using `Boolean` would require a `@NotNull` we do not want.

### 2. Controller applies `@Valid`

```java
// starport-registry/src/main/java/com/galactic/starport/controller/ReservationController.java:33-40
@PostMapping("/{code}/reservations")
public ResponseEntity<ReservationResponse> create(
        @PathVariable @NotBlank String code,
        @Valid @RequestBody ReservationCreateRequest req) { ... }
```

Path variable is validated too (`@NotBlank String code`) — Spring runs parameter
validation via `MethodValidationPostProcessor`.

### 3. Rule chain — `ReserveBayCommandValidationRule`

```java
// starport-registry/src/main/java/com/galactic/starport/service/validation/ReservationTimeValidationRule.java:13-39
@Component
@Order(0)
public class ReservationTimeValidationRule implements ReserveBayCommandValidationRule {

    @Override
    public void validate(ReserveBayCommand cmd) {
        if (cmd.startAt().isAfter(cmd.endAt()) || cmd.startAt().equals(cmd.endAt())) {
            throw new InvalidReservationTimeException(
                    "startAt must be strictly before endAt",
                    cmd.startAt(), cmd.endAt());
        }
    }
}
```

No Spring autowiring; pure logic on the command. Fast.

```java
// starport-registry/.../service/validation/StartStarportValidationRule.java:15-37
@Component
@Order(10)
@RequiredArgsConstructor
public class StartStarportValidationRule implements ReserveBayCommandValidationRule {

    private final StarportRepositoryFacade starportRepository;

    @Override
    public void validate(ReserveBayCommand cmd) {
        starportRepository.findByCode(cmd.startStarportCode())
                .orElseThrow(() -> new StarportNotFoundException(cmd.startStarportCode()));
    }
}
```

Needs the DB; lives *after* the cheap rule. Throws the domain exception directly.

### 4. Orchestration — `ReserveBayValidationService`

```java
// starport-registry/.../service/validation/ReserveBayValidationService.java:24-79
@Service
@RequiredArgsConstructor
public class ReserveBayValidationService {

    private final List<ReserveBayCommandValidationRule> rules;  // Spring injects sorted by @Order

    public void validate(ReserveBayCommand command) {
        for (ReserveBayCommandValidationRule rule : rules) {
            rule.validate(command);   // throws on first failure
        }
    }
}
```

Adding a new rule is: one new `@Component` with a unique `@Order`. No change to the
service.

### 5. Exception → HTTP mapping

```java
// GlobalExceptionHandler.java (see ADR-0015)
@ExceptionHandler(MethodArgumentNotValidException.class)     → 422 VALIDATION_FAILED
@ExceptionHandler(StarportNotFoundException.class)           → 404 STARPORT_NOT_FOUND
@ExceptionHandler(InvalidReservationTimeException.class)     → 422 INVALID_RESERVATION_TIME
@ExceptionHandler(NoDockingBaysAvailableException.class)     → 409 NO_DOCKING_BAYS_AVAILABLE
```

The layer boundary is visible in the status code: 422 for "valid JSON but wrong values",
404 for "references something that does not exist", 409 for "conflicts with server
state".

### 6. Contract tests pin the behaviour

`*ContractTest.java` (ADR-0006) assert the exact status codes and error bodies for each
path. A refactor that merges or skips a rule lights up the contract test immediately.

---

## Consequences

### Benefits

- **Clear responsibility.** A reviewer knows whether a new check belongs on the DTO or in
  the chain without debate.
- **Fast rejection path.** Malformed JSON or missing fields never reach the DB — `@Valid`
  short-circuits at Spring's parameter binding.
- **Domain-meaningful errors.** Business failures come back with specific error codes
  (`ROUTE_UNAVAILABLE`, `STARPORT_NOT_FOUND`), not generic 400s.
- **Testable in isolation.** A rule is a class with no more dependencies than it needs.
  A `ReservationTimeValidationRule` test is three lines; a `StartStarportValidationRule`
  test is a mock repo + one assertion.
- **Extensible without ceremony.** New rule = new `@Component` + new `@Order`. No
  service-level wiring.

### Trade-offs

- **Two places to look.** A new engineer asking "where is the validation?" must know both
  the DTO annotations and the rule chain. Documented here.
- **`@Order` drift.** Adding a rule at position 15 means re-reading the existing orders.
  An alternative is to use string constants (`Order.CHEAP_CHECKS`, `Order.DB_CHECKS`) —
  not adopted yet; watch for this if the chain grows past ~10 rules.
- **Domain invariants are not enforced in constructors.** `new Reservation(...)` with
  bogus data will happily construct. Mitigated by rule chain + integration tests; a
  source of subtle bugs if validation is ever forgotten upstream.
- **No custom `@Constraint` for cross-field rules.** `startAt < endAt` is a rule, not a
  DTO annotation. Could be a `@ValidTimeRange` class annotation; not done because the
  rule chain is where cross-field time logic already lives and duplicating it would
  risk divergence.
- **`Boolean` vs `boolean` subtlety.** A primitive `requestRoute` silently defaults to
  `false` if JSON omits the field. Intentional: "if you did not say you wanted a route,
  you do not get one." A future API might need the distinction; a flip to `Boolean` with
  `@NotNull` is a deliberate breaking change.

---

## Alternatives Considered

1. **Single big validator method** that does both syntactic and business checks. Rejected
   — entangles fast checks with DB I/O; makes unit testing harder; produces a single
   catch-all exception type that violates ADR-0015's error mapping.
2. **Custom `@Constraint` annotations for everything** (`@ValidStarportCode`,
   `@ValidShipClass`). Rejected — constraints that need DB lookups are discouraged in
   Jakarta validation because they tie validation to I/O at unpredictable points.
3. **Hibernate Validator's group sequences**. Would let us declare cheap vs expensive
   groups on the DTO. Technically viable; rejected because the chain-of-rules pattern is
   more explicit and does not require group-aware controllers.
4. **Validation inside the domain constructor** (all-invariants-in-domain approach).
   Rejected for pragmatism: JPA requires a no-arg constructor, Lombok `@Builder` would
   need to re-validate on every build, and the business rule chain would still be needed
   for rules that require DB access. The domain classes stay permissive; the chain is
   the gate.
5. **Spring's `Validator` SPI** (`implements Validator`). Rejected in favour of the
   Chain of Responsibility — `Validator` returns errors in a `BindingResult`, but the
   project's error model expects domain exceptions (ADR-0015).

---

## References

- ADR-0006 — Testing Strategy (contract tests lock in error shapes)
- ADR-0011 — Architecture Guardrails (ArchUnit can forbid `findById` calls from
  controllers, ensuring business rules do not leak out of the service layer)
- ADR-0015 — API Error Response Model (typed error codes)
- ADR-0020 — Concurrent Reservation Safety (validation runs before the pessimistic lock)
- Jakarta Bean Validation 3.0 —
  https://beanvalidation.org/3.0/spec/
- Spring MVC method validation —
  https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-validation.html
