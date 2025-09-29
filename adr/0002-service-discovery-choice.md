# 0002 — Service Discovery Mechanism

**Status:** Accepted — 2025-09-29

## Context

We need HTTP-based service discovery with at least two instances per service. Calls between services should resolve logical service names to healthy instances and support client-side load balancing.

## Decision

Adopt **Eureka Server (Spring Cloud Netflix Eureka)** as a central registry. Services will self-register and renew heartbeats. HTTP clients will address peers via `lb://{service-name}` using **Spring Cloud LoadBalancer**.

## Consequences

### Positive

* **Tight Spring Boot 3.x integration.** Minimal boilerplate: dependencies, a few properties, and `@LoadBalanced WebClient`. Works well with Spring Cloud and Micrometer.
* **Great for local and demo setups.** One compose file can bring up Eureka plus multiple service replicas; easy to reason about instance lists while developing.
* **Client-side load balancing out of the box.** Spring Cloud LoadBalancer provides round-robin (and pluggable) strategies without an external L7 proxy.
* **Rich instance metadata.** We can attach build info, zones, versions, and expose them to clients for simple routing or progressive delivery.
* **Failure isolation at the edge.** Each caller owns its balancing, timeouts, and resilience (Resilience4j), reducing reliance on a single shared gateway during outages.
* **Observability friendliness.** Eureka and services are Spring Actuator-based; instance lists and health statuses are easy to inspect, scrape, and alert on.
* **Thin control plane.** Eureka’s responsibilities are constrained to registration/lookup, keeping control-plane complexity relatively low compared to service meshes.

### Negative / Trade-offs (trimmed)

* **Requires HA & adds footprint.** Needs a small cluster (2–3 nodes) plus extra CPU/RAM and monitoring overhead.
* **Eventual consistency & tuning risks.** Stale entries during churn; mis-set heartbeats/TTLs or self-preservation can hide dead nodes or evict healthy ones.
* **Client-side complexity.** Each service must own timeouts, retries, backoff, circuit breakers—risk of inconsistent policies.
* **Security surface.** Must secure with TLS, auth, and network policies; compromise exposes topology or enables DoS.
* **Fewer L7 features.** Lacks built-in canary/shadowing/header routing/distributed rate limiting compared to gateways/meshes.
* **Tooling friction.** Extra component to run in CI/integration tests; longer builds and more containers to manage.

### Risk Mitigations

* **HA & quorum:** Run 3 Eureka nodes in production; validate peer replication; test failover regularly.
* **Robust client policies:** Enforce global defaults for connect/read timeouts, limited retries (idempotent ops only), and circuit breakers.
* **Operational tuning:** Calibrate `leaseRenewalIntervalInSeconds`, `leaseExpirationDurationInSeconds`, and self-preservation settings aligned with instance churn patterns.
* **Security hardening:** Mutual TLS for registry traffic, credentials/secrets management, network policies.
* **Progressive delivery guardrails:** Use instance metadata (e.g., `version`, `zone`) for safe rollout filtering; pair with health/readiness gates to prevent premature registration.
* **Monitoring & alerts:** Track Eureka peer replication status, registry size, heartbeat renewals, eviction counts, client HTTP error rates, and latency percentiles.

## Alternatives Considered

* **Consul:** Pros — built-in KV store, robust health checks, broad ecosystem. Cons — extra operational surface compared to Eureka for Spring-only needs; more to run in local demos.
* **Kubernetes Service DNS (and possibly a service mesh):** Excellent in K8s environments with built-in discovery and load balancing. Not a fit for Docker Compose-based demo and adds a heavier dependency if we’re not otherwise on K8s yet.
* **Static DNS + external L7 (NGINX/Envoy/Gateway):** Simpler name resolution but pushes us toward centralized proxies and a different operational model; overkill for current scope.

## Implementation

* Create a dedicated `discovery-server` module (Eureka).
* Services:

    * `spring.cloud.discovery.enabled=true`
    * `eureka.client.serviceUrl.defaultZone=http://discovery:8761/eureka`
    * Use `WebClient.Builder` with `@LoadBalanced`.
* Configure sensible client defaults (timeouts, limited retries) and resilience (Resilience4j).
* Add dashboards/alerts for: Eureka health, peer replication, eviction count, heartbeat renewals, and client call failures.
* For production: run 3 Eureka replicas behind TLS with authentication; back up configuration; include disaster-recovery runbooks.

---
