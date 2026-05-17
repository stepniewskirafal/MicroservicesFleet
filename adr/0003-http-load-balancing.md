# 0003 — HTTP Load Balancing: Spring Cloud LoadBalancer

**Status:** Accepted — 2026-02-28

---

## Context

Starport Registry (A) calls Trade Route Planner (B) synchronously during reservation. Both run multiple instances registered in Eureka (ADR-0002). The caller needs to pick a healthy instance without introducing a central proxy or new infrastructure.

---

## Decision

Use **Spring Cloud LoadBalancer (client-side)** with a `@LoadBalanced WebClient.Builder` and `lb://{service-name}` URIs. Instance lists come straight from Eureka; each caller owns its own timeouts and retries.

```java
@Bean @LoadBalanced
WebClient.Builder webClientBuilder() { return WebClient.builder(); }

// usage
webClient.get().uri("lb://trade-route-planner/routes/{id}", id)...
```

Connect/read timeouts on `ReactorClientHttpConnector`; retry ≤ 1 on idempotent calls only.

---

## Why

- **Zero extra infrastructure.** No NGINX, Envoy, or mesh to deploy or monitor.
- **Live registry integration.** Pulls healthy instances from Eureka without separate polling.
- **Per-caller resilience.** Timeouts, retries, circuit breakers are configured independently — no shared SPOF.
- **Readable URIs + Micrometer.** `lb://` keeps callers concise; HTTP client metrics and traces propagate automatically.

---

## Alternatives

- **NGINX / Envoy reverse proxy** — adds infrastructure and becomes a SPOF; must be kept in sync with Eureka separately.
- **Kubernetes Service DNS + kube-proxy** — transparent in K8s, but Docker Compose is our target runtime; L4-only health granularity.

---

## References

- ADR-0002 — Service Discovery: Eureka
- Spring Cloud LoadBalancer — https://docs.spring.io/spring-cloud-commons/docs/current/reference/html/#spring-cloud-loadbalancer
- Resilience4j — https://resilience4j.readme.io/
