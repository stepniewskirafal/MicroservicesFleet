# 0021 — Hexagonal (Ports & Adapters) Implementation Conventions — trade-route-planner

**Status:** Accepted
**Date:** 2026-04-17

---

## Context

ADR-0001 assigns `trade-route-planner` the Hexagonal (Ports & Adapters) style. That ADR
names the style; it does not pin down what the package layout, port naming, adapter
wiring, and domain purity rules actually look like in this code. Hexagonal is a
family of conventions — a code review team needs a single, concrete dialect to reject PRs
against.

This ADR is that concrete dialect. It is written from the perspective of an engineer
opening a PR: "where does my class go, what may it depend on, and what must it look like?"

---

## Decision

### Package layout — four top-level packages

Under `com.galactic.traderoute`:

```
domain/        — pure Java model. No framework imports. Records preferred.
  model/         RouteRequest, PlannedRoute, RoutePlannedEvent
port/          — interfaces that cross the hex boundary.
  in/            driving ports (called by inbound adapters)
  out/           driven ports (implemented by outbound adapters)
application/   — use-case services; implement in-ports, call out-ports.
adapter/       — the only place framework code may live.
  in/rest/       REST controller, request/response DTOs, exception handler
  out/kafka/     Spring Cloud Stream publisher
config/        — @Configuration beans for cross-cutting concerns.
```

The inner layers (`domain`, `port`, `application`) must not import anything from
`adapter`. The outer layers may depend inward freely. ADR-0011 will encode this as an
ArchUnit rule once the test classes are authored.

### Port naming

- In-ports (driving): `*UseCase` — e.g. `PlanRouteUseCase`. One method per use case.
  The controller calls these; the application service implements them.
- Out-ports (driven): `*Publisher`, `*Gateway`, `*Repository` — role-describing nouns,
  not tech-specific. `RouteEventPublisher` describes the role ("publish an event"), not
  the transport ("KafkaEventPublisher"). Tech choice is an adapter concern.

Each port interface lives in its own file in `port/in/` or `port/out/`. Grouping multiple
unrelated methods on one port violates the ISP (Interface Segregation Principle) and
couples unrelated adapters.

### Domain purity

- **No Spring on the inside.** `grep 'org.springframework' domain/ port/ application/` must
  return nothing. Only `jakarta.*` (validation), `java.*`, `lombok.*`, and project types
  may be imported.
- **Records for value objects.** `RouteRequest`, `PlannedRoute`, `RoutePlannedEvent` are
  Java records — immutable by construction, no getters to maintain, `equals`/`hashCode`
  free.
- **Lombok `@Builder` is allowed** on records (useful when records have many fields and
  tests want named construction). Other Lombok annotations (`@Data`, `@Setter`) are
  **forbidden** on domain types — mutability breaks thread safety and domain-event
  semantics.

### Application services

- Implement one or more `*UseCase` in-ports.
- Are `@Service`-annotated (Spring lives here, not in `domain/` or `port/`).
- Hold dependencies on out-ports only. They must not know which transport the out-port
  uses.
- Wrap every business operation in a Micrometer `Observation` (ADR-0005) for traceability.

### Inbound adapters — REST

- Live in `adapter/in/rest/`.
- Depend only on in-ports (`*UseCase`) — **never** on application services directly.
- Translate between HTTP DTOs and domain types. HTTP types must not leak into the
  application service signature.
- Use `@RestControllerAdvice` in the same package for error mapping (ADR-0015).

### Outbound adapters — Kafka

- Live in `adapter/out/kafka/`.
- Implement an out-port (`*Publisher`) — the application service depends on the interface,
  not the class.
- Encapsulate transport concerns: header injection, partition key, binding name, message
  building.
- One adapter per out-port; do not stuff two unrelated port implementations into one
  class.

### Wiring

- Spring scans the whole `com.galactic.traderoute` package, so `@Service` and `@Component`
  beans are discovered automatically.
- No explicit `@Configuration`-class "wiring" for ports → application → adapters. Each
  side is a bean and the injection happens by interface.
- `config/ObservationConfig.java` holds cross-cutting aspects (`ObservedAspect`) — one
  `@Configuration` class per concern, not a monolithic "AppConfig".

---

## How the codebase enforces this

### 1. In-port: `PlanRouteUseCase`

```java
// trade-route-planner/src/main/java/com/galactic/traderoute/port/in/PlanRouteUseCase.java:6-8
public interface PlanRouteUseCase {
    PlannedRoute planRoute(RouteRequest request);
}
```

Single method, single responsibility, domain types only. `RouteRequest` and `PlannedRoute`
are records in `domain/model/`.

### 2. Out-port: `RouteEventPublisher`

```java
// trade-route-planner/src/main/java/com/galactic/traderoute/port/out/RouteEventPublisher.java:5-7
public interface RouteEventPublisher {
    void publish(RoutePlannedEvent event);
}
```

The name says *what* ("publish route events"), not *how* (Kafka). The application service
does not mention Kafka anywhere.

### 3. Application service: `PlanRouteService`

```java
// trade-route-planner/src/main/java/com/galactic/traderoute/application/PlanRouteService.java
@Service
@RequiredArgsConstructor
public class PlanRouteService implements PlanRouteUseCase {

    private final RouteEventPublisher eventPublisher;   // out-port
    private final MeterRegistry meterRegistry;
    private final ObservationRegistry observationRegistry;

    @Override
    public PlannedRoute planRoute(RouteRequest request) {
        return Observation.createNotStarted("routes.plan", observationRegistry)
                .observe(() -> {
                    PlannedRoute planned = compute(request);
                    eventPublisher.publish(toEvent(planned));
                    meterRegistry.counter("routes.planned.count").increment();
                    return planned;
                });
    }
}
```

Dependencies: one out-port + two observability facades. No Kafka types, no HTTP types, no
JPA. If this class had to be unit-tested without Spring, a plain mock for
`RouteEventPublisher` is all that is needed.

### 4. Inbound REST adapter

```java
// trade-route-planner/src/main/java/com/galactic/traderoute/adapter/in/rest/RoutePlannerController.java:16-28
@RestController
@RequestMapping("/routes")
@RequiredArgsConstructor
@Slf4j
public class RoutePlannerController {

    private final PlanRouteUseCase planRouteUseCase;  // in-port, not service class

    @PostMapping("/plan")
    public PlanRouteResponse plan(@Valid @RequestBody PlanRouteRequest request) {
        PlannedRoute planned = planRouteUseCase.planRoute(
                new RouteRequest(request.origin(), request.destination()));
        return PlanRouteResponse.from(planned);
    }
}
```

The field type is `PlanRouteUseCase`, not `PlanRouteService`. Swapping the implementation
(stub, in-memory, fault-injecting) in a test is a one-line `@MockBean`.

### 5. Outbound Kafka adapter

```java
// trade-route-planner/src/main/java/com/galactic/traderoute/adapter/out/kafka/StreamBridgeRouteEventPublisher.java
@Component
@RequiredArgsConstructor
public class StreamBridgeRouteEventPublisher implements RouteEventPublisher {

    private static final String BINDING = "routePlanned-out-0";
    private final StreamBridge streamBridge;

    @Override
    public void publish(RoutePlannedEvent event) {
        Message<RoutePlannedEvent> msg = MessageBuilder.withPayload(event)
                .setHeader(KafkaHeaders.KEY, event.routeId())
                .build();
        streamBridge.send(BINDING, msg);
    }
}
```

All Kafka concerns (binding name, header, partition key) are here and only here. Moving
to RabbitMQ or a second broker is a new adapter + an `@Primary` wiring — zero changes to
the application service.

### 6. Domain record

```java
// trade-route-planner/src/main/java/com/galactic/traderoute/domain/model/PlannedRoute.java
@Builder
public record PlannedRoute(String routeId, double etaHours, double riskScore) {}
```

Three fields, no methods, no setters, thread-safe by construction.

---

## Consequences

### Benefits

- **Testability.** Every application service can be unit-tested with pure mocks of its
  out-ports, no Spring context. `@WebMvcTest` can slice the REST adapter with an
  `@MockBean` of the use case.
- **Swappable adapters.** Replacing Kafka with an HTTP webhook requires one new adapter
  and a `@Primary` annotation. No domain code changes.
- **Clean reading order.** A new engineer reads
  `port/in/*` → `application/*` → `port/out/*` to understand the service in 10 minutes.
  Adapter code is for when the question is "how exactly do we talk to Kafka?".
- **Dependency direction is obvious.** `grep` for `import` across `domain/` reveals the
  domain's external surface in seconds.

### Trade-offs

- **More files per feature.** A single use case touches at least: in-port + use-case
  service + out-port + REST DTO + adapter + domain record + event. For trivial
  CRUD-shaped services this is overkill; Layered (as in `starport-registry`) is a better
  fit.
- **Port-interface ceremony.** Every out-bound call goes through an interface — yes, even
  when there is only one implementation. The payoff is in testability and change tolerance;
  the cost is keystrokes.
- **ArchUnit rules not yet written.** ADR-0011 plans them; until they land, the layering
  is enforced by review only.
- **Lombok on records is unfamiliar.** `@Builder` on a record is legal and useful, but
  uncommon in public examples; engineers may hesitate to copy the pattern.

---

## Alternatives Considered

1. **"Onion" / "Clean architecture" naming** (`entities/`, `use_cases/`, `gateways/`,
   `controllers/`). Equivalent in substance, different vocabulary. Hexagonal vocabulary
   is what ADR-0001 chose; keeping it consistent avoids a dialect war.
2. **Package-by-feature** (`planroute/{in,out,application,domain}`). Considered for
   larger services; premature for one use case. Will revisit when there are 5+ use cases.
3. **Skip in-ports (`*UseCase`) and inject services directly into controllers.** Common
   short-cut. Rejected because it couples the controller to a specific `@Service`
   implementation and makes integration tests indirectly depend on Spring DI.
4. **No adapter layer, REST controller calls Kafka directly.** Rejected for the same
   reasons hexagonal exists — transport concerns leak into business code and become
   untestable.

---

## References

- ADR-0001 — Architecture Styles per Service
- ADR-0005 — Observability Stack (`Observation` in application services)
- ADR-0011 — Architecture Rules & Guardrails (ArchUnit rules enforce this ADR)
- ADR-0014 — HTTP Resilience (what the REST adapter must honour)
- ADR-0015 — API Error Response Model (how the REST adapter maps exceptions)
- ADR-0019 — Kafka Programming Model (adapter/out/kafka conventions)
- Alistair Cockburn — *Hexagonal Architecture* —
  https://alistair.cockburn.us/hexagonal-architecture/
- Vaughn Vernon — *Implementing Domain-Driven Design*, ch. 4 (Architecture)
