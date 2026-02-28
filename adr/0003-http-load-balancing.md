# 0003 — HTTP Load Balancing Approach

**Status:** Accepted — 2026-02-28
**Deciders:** Team
**Technical Story:** Requirement — each service must run ≥2 instances and HTTP calls between services must balance across healthy instances without a central proxy.

---

## Context and Problem Statement

Starport Registry (A) calls Trade Route Planner (B) synchronously over HTTP to plan a route during reservation. Both services run in multiple instances registered in Eureka. How should the caller pick which instance to send a request to? Should load balancing be done client-side, server-side via a shared proxy, or delegated to the platform?

---

## Decision Drivers

* Tight Spring Boot 3.x / Spring Cloud integration is a project-wide constraint.
* No extra infrastructure components beyond what the observability/messaging stack already brings.
* Each service already registers itself in Eureka; the registry should be exploited for routing.
* Resilience policies (retries, circuit breakers) must be owned by individual callers, not a shared proxy, to avoid a single point of failure.
* Simple `lb://service-name` URL syntax keeps business code free of host/port concerns.

---

## Considered Options

1. **Spring Cloud LoadBalancer (client-side)** — dependency on `spring-cloud-starter-loadbalancer`; `@LoadBalanced WebClient`; `lb://trade-route-planner` URI scheme.
2. **NGINX / Envoy reverse proxy (server-side)** — single upstream proxy that receives requests from services and forwards to healthy instances.
3. **Kubernetes Service DNS + kube-proxy (L4)** — rely on K8s ClusterIP services; balancing done transparently by the platform.

---

## Decision Outcome

**Chosen option: Spring Cloud LoadBalancer (client-side).**

It is the only option that integrates directly with the Eureka registry already in use, requires zero extra infrastructure, and fits naturally into the Spring Boot 3.x / Spring Cloud 2025 BOM used across the project. Each service maintains its own resilience policies independently.

### Positive Consequences

* **Zero additional infrastructure.** No NGINX, Envoy, or Istio service to configure, deploy, or monitor.
* **Live registry integration.** The LoadBalancer pulls instance lists directly from Eureka, respecting heartbeat health without additional polling.
* **Readable URIs.** `lb://trade-route-planner` in `WebClient` keeps the caller free of host/port concerns.
* **Per-caller resilience.** Timeouts, retries (idempotent operations only), and future circuit breakers (Resilience4j) are configured independently per caller — no shared SPOF.
* **Micrometer instrumentation.** HTTP client metrics and traces are propagated transparently via the `@LoadBalanced WebClient`.
* **Strategy extensibility.** Spring Cloud LoadBalancer supports pluggable strategies (round-robin by default; weighted or zone-aware when needed).

### Negative Consequences

* **Client responsibility.** Each service must configure sensible timeouts, limited retries, and backoff. Missing or inconsistent policies across services risk cascading failures.
* **Stale instance cache.** There is a short window during which the local cache may hold an evicted instance; callers must handle connection errors and retry on another instance.
* **No advanced L7 routing.** Canary splits, header-based routing, or traffic mirroring require additional tooling (e.g., gateway or mesh) not chosen here.
* **Increased boilerplate if services multiply.** Each new outbound HTTP client needs a `@LoadBalanced WebClient.Builder` bean and timeout configuration.

---

## Pros and Cons of the Options

### Option 1 — Spring Cloud LoadBalancer (client-side) ✅

* Good, because it integrates natively with Eureka and the Spring Cloud 2025 BOM.
* Good, because it adds zero runtime infrastructure.
* Good, because `lb://` URI scheme keeps callers concise and testable (stub with WireMock).
* Good, because individual callers own their resilience contracts.
* Bad, because policies must be replicated and kept consistent across all callers.
* Bad, because no built-in traffic-shaping features (canary, shadow).

### Option 2 — NGINX / Envoy reverse proxy (server-side)

* Good, because one place to tune balancing, timeouts, and retries.
* Good, because advanced L7 routing is available out of the box.
* Bad, because adds another infrastructure component to deploy and monitor.
* Bad, because the proxy itself becomes a potential SPOF unless made HA.
* Bad, because decouples caller from registry — proxy must be kept in sync with Eureka separately.

### Option 3 — Kubernetes Service DNS + kube-proxy (L4)

* Good, because balancing is fully transparent — callers use plain DNS names.
* Good, because works automatically in K8s with no extra libraries.
* Bad, because not the target runtime for local development and demos (Docker Compose is primary).
* Bad, because L4 balancing lacks health-check granularity; pods may receive traffic while Spring context is still starting.
* Bad, because introduces a strong K8s dependency for a feature Spring Cloud covers natively.

---

## Implementation

* Declare `spring-cloud-starter-loadbalancer` in `starport-registry/pom.xml` (already present).
* Expose a `@LoadBalanced WebClient.Builder` bean in each service that makes outbound HTTP calls.
* Use `lb://trade-route-planner` (or the target service's `spring.application.name`) as the base URL.
* Configure connect/read timeouts on the underlying `ReactorClientHttpConnector`; keep retry count ≤ 1 on idempotent requests.
* Add Micrometer HTTP client observations for latency and error-rate alerting.

---

## References

* ADR-0002 — Service Discovery Mechanism
* Spring Cloud LoadBalancer — https://docs.spring.io/spring-cloud-commons/docs/current/reference/html/#spring-cloud-loadbalancer
* Resilience4j — https://resilience4j.readme.io/
