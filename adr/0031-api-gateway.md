# 0031 — API Gateway as the Single Public Ingress

**Status:** Accepted
**Date:** 2026-04-17

---

## Context

Before this ADR, every replica was bound to a distinct host port (`localhost:8081`,
`:8084`, `:8082`, `:8083`, ...). That hard-couples clients to one replica, leaves the
second replica without any external load balancing, and exposes every app instance
directly to the host. ADR-0003 already routes service-to-service traffic via Spring
Cloud LoadBalancer + Eureka; this ADR closes the **client → service** gap with the
same mechanism.

---

## Decision

Add a Spring Cloud Gateway module (`api-gateway`, Eureka client). It is the **only**
application container with a host-bound port (`:8080`). Routes use `lb://<service>`,
so the gateway's internal LoadBalancer rotates across whatever Eureka knows about.

```yaml
# api-gateway/src/main/resources/application.yml
spring.cloud.gateway.routes:
  - id: starport-registry
    uri: lb://starport-registry
    predicates: [ Path=/api/v1/starports/** ]
  - id: trade-route-planner
    uri: lb://trade-route-planner
    predicates: [ Path=/routes/** ]
```

Compose changes:

- App replicas drop `ports:` and use `expose:` only — unreachable from the host.
- Every replica of a service listens on the **same** internal port
  (`starport-registry`: 8081, `trade-route-planner`: 8082, `telemetry-pipeline`: 8090,
  `api-gateway`: 8080). Uniqueness comes from `container_name` + Eureka `instance-id`,
  matching the Kubernetes "every pod on the same port" convention.
- Host-bound ports are now only: `api-gateway:8080`, `eureka:8761`, infra
  (`postgres`, `kafka`), and observability (`grafana`, `prometheus`, `tempo`, `loki`,
  `kafka-ui`).
- Prometheus scrapes targets by Compose-network DNS (`starport-registry-1:8081`, ...)
  instead of `host.docker.internal:*`.
- Load-test scripts default `-Base` to `http://localhost:8080`.

`telemetry-pipeline` is **not** routed — it has no REST API (ADR-0022).

---

## Why

- **One stable public URL per service.** Clients never learn replica addresses;
  scaling and blue/green are invisible to them.
- **Real external load balancing.** Gateway + LoadBalancer rotate per request against
  the live Eureka registry; failed instances are skipped on the next call.
- **Scaling is a Compose-only change.** Add a replica → one Compose block + one
  `prometheus.yml` target. No gateway or client edits.
- **Reduced attack surface.** Only the gateway is reachable from the host.
- **Production-shaped.** Translates mechanically to K8s — gateway → Ingress, `expose:`
  → `containerPort`, replicas → `replicas: N`.
- **Centralised cross-cutting concerns.** Future CORS, JWT, rate limits land on the
  gateway, not duplicated per service.

---

## Alternatives

- **NGINX reverse proxy** — static config, no Eureka; scaling means editing `nginx.conf`.
- **Traefik with Docker labels** — auto-discovers containers but not Eureka-native;
  two sources of truth.
- **Spring Cloud Gateway MVC (servlet) instead of reactive Gateway** — would benefit
  from virtual threads (ADR-0012), but a proxy is the archetypal reactive use case and
  the gateway has no domain logic that would benefit from imperative code.
- **`docker compose --scale`** — loses `container_name`, making Prometheus + log labels
  hard to reason about.
- **Keep one instance port as "debug"** — either a port is production-like or it is
  not; leaving one exposed tempts hard-coding.

---

## Production gaps (follow-up ADR)

- ≥2 gateway replicas behind a cluster-level LB.
- JWT / mTLS auth, CORS policy, `RequestRateLimiter` (Redis-backed).
- Contract test: every `spring.application.name` in Eureka has a matching gateway
  route, or is explicitly allow-listed as internal.
- No gateway-side retry (callers own retry contracts via Resilience4j, ADR-0014) —
  intentionally; a second retry layer risks N² multiplier.

---

## References

- ADR-0002 — Service Discovery (Eureka)
- ADR-0003 — HTTP Load Balancing (Spring Cloud LoadBalancer)
- ADR-0008 — Deployment Topology (Docker Compose)
- ADR-0014 — HTTP Resilience (why no gateway-side retry)
- ADR-0027 — Actuator Exposure (`/actuator/gateway/routes`)
- ADR-0028 — Eureka Operational Tuning (gateway is an Eureka client)
- Spring Cloud Gateway — https://docs.spring.io/spring-cloud-gateway/reference/index.html
