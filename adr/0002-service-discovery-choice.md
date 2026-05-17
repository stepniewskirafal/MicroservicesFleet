# 0002 — Service Discovery: Eureka

**Status:** Accepted — 2025-09-29

---

## Context

We need HTTP-based service discovery with at least two instances per service. Calls between services must resolve logical service names to healthy instances and support client-side load balancing — all on a Spring Boot 3.x stack, runnable from Docker Compose for local dev.

---

## Decision

Adopt **Spring Cloud Netflix Eureka** as the central registry. Services self-register and renew heartbeats; HTTP clients address peers via `lb://{service-name}` using Spring Cloud LoadBalancer.

```yaml
spring.cloud.discovery.enabled: true
eureka.client.serviceUrl.defaultZone: http://discovery:8761/eureka
```

A dedicated `discovery-server` module runs Eureka; production deploys 3 replicas behind TLS.

---

## Why

- **Native Spring Cloud integration.** A few properties and `@LoadBalanced WebClient` — no glue code.
- **Client-side balancing out of the box.** Round-robin (pluggable) without an external L7 proxy.
- **Per-caller resilience.** Timeouts, retries, and circuit breakers (Resilience4j) live with the caller — no shared SPOF.
- **Compose-friendly.** Eureka + N replicas come up in one file; instance lists are inspectable via Actuator.

---

## Alternatives

- **Consul** — more features (KV, health), but extra ops surface for a Spring-only fleet.
- **Kubernetes Service DNS** — excellent on K8s, but our primary runtime is Docker Compose.
- **Static DNS + NGINX/Envoy** — centralised proxy, different operational model; overkill here.

---

## References

- ADR-0003 — HTTP Load Balancing Approach
- Spring Cloud Netflix Eureka — https://spring.io/projects/spring-cloud-netflix
