# 0031 — API Gateway as the Single Public Ingress; No Host-Bound Instance Ports

**Status:** Accepted
**Date:** 2026-04-17

---

## Context

Up to ADR-0030 the Compose topology exposed every application replica on a distinct host
port:

```
localhost:8081  → starport-registry-1
localhost:8084  → starport-registry-2
localhost:8082  → trade-route-planner-1
localhost:8083  → trade-route-planner-2
localhost:8090  → telemetry-pipeline-1
localhost:8091  → telemetry-pipeline-2
```

That setup is a dev shortcut, but it is wrong as a "production-shaped" demo for three
reasons:

1. **Clients learn instance-specific URLs.** Hard-coding `http://localhost:8081` into a
   load-test script, Postman collection, or another team's client couples them to one
   replica. Scaling, restarts, and blue/green deploys break the contract.
2. **No external load balancing.** Two replicas are registered in Eureka, but there is
   nothing on the outside of the fleet that rotates between them. The second replica
   (`:8084`) gets traffic only if a human explicitly types that port — it is not a real
   HA story.
3. **Instance ports are a security surface.** A CVE on any one instance is reachable
   directly from the host. In production, app instances should live behind an ingress
   and not be addressable from outside the cluster / Docker network.

ADR-0003 already established that **service-to-service** calls go through Spring Cloud
LoadBalancer + Eureka (`lb://service-name`). This ADR closes the remaining gap —
**client → service** traffic — by introducing a gateway that does the same, so the
outside world sees one stable URL and the fleet keeps every replica behind it.

---

## Decision

### 1. Spring Cloud Gateway as the single public ingress

Add a new Maven module `api-gateway` running **Spring Cloud Gateway** (Eureka client
enabled). The gateway is the **only** app-layer container with a host-bound port (`:8080`).

```yaml
# api-gateway/src/main/resources/application.yml
spring:
  cloud:
    gateway:
      routes:
        - id: starport-registry
          uri: lb://starport-registry
          predicates:
            - Path=/api/v1/starports/**
        - id: trade-route-planner
          uri: lb://trade-route-planner
          predicates:
            - Path=/routes/**
```

Routes use **`lb://<service-name>`** — Eureka resolves which replicas exist, Spring
Cloud LoadBalancer rotates between them on every request. Adding a third replica is a
one-line Compose change with zero gateway changes.

`telemetry-pipeline` is **not** routed. It has no REST API and does not need external
HTTP access (ADR-0022 — it is a Kafka-driven service).

### 2. App-instance host ports removed from Compose

Before this ADR, every replica had:

```yaml
ports:
  - "8081:8081"   # host:container
```

After: **no `ports:` mapping** on any of the six application-instance services. They
use Compose's `expose:` directive to document the internal listener port, but the port
is reachable only on the Compose network (accessible to gateway, Prometheus, and each
other — not to `localhost`).

### 3. Unified container port per service

Previously every replica used a **different** internal port (e.g. `starport-registry-1`
listened on 8081, `starport-registry-2` on 8084) to align with the distinct host-port
mapping. With host ports gone, that distinction is obsolete and harmful — it produces
an inconsistency between replicas.

Now every replica of a given service listens on the same internal port:

| Service              | Container port (all replicas) |
|---|---|
| api-gateway          | 8080                          |
| starport-registry    | 8081                          |
| trade-route-planner  | 8082                          |
| telemetry-pipeline   | 8090                          |
| eureka-server        | 8761                          |

Replica uniqueness comes from `container_name` (Docker DNS) and Eureka `instance-id`,
not from port numbers. This matches real production: two Kubernetes pods of the same
Deployment both listen on `8080`; they differ in pod name and IP.

### 4. Only the gateway, the registry, and infra ports are host-bound

The Compose file now binds host ports only for:

- `api-gateway:8080` — public business ingress
- `eureka:8761` — registry dashboard (operator tooling)
- `postgres:5432`, `kafka:9092` — dev convenience (Postman / IDE access)
- `grafana:3000`, `prometheus:9090`, `tempo:3200`, `loki:3100`, `kafka-ui:8085` —
  observability tooling

Everything else is internal. A `curl http://localhost:8081` from the host **fails by
design** — that port is no longer exposed.

### 5. Prometheus scraping moves onto the Compose network

Previously Prometheus scraped `host.docker.internal:8081,8084,...` — each instance via
its host port. After this ADR, Prometheus targets containers by DNS name on the Compose
network:

```yaml
# infra/docker/prometheus/prometheus.yml
scrape_configs:
  - job_name: "starport-registry"
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ["starport-registry-1:8081", "starport-registry-2:8081"]
```

Adding a replica is one line per scrape job.

### 6. Tests and load scripts default to the gateway

The PowerShell load-test scripts default `-Base` to `http://localhost:8080`. Anyone
running them gets rotation across both starport-registry replicas automatically, because
the gateway's internal LoadBalancer picks a replica per request.

---

## How the codebase enforces this

1. **`api-gateway` module** — one new Spring Boot app, two dependencies that matter
   (`spring-cloud-starter-gateway` + `spring-cloud-starter-netflix-eureka-client`),
   `@EnableDiscoveryClient` on the main class, routes in YAML.
2. **Root `pom.xml`** — adds `api-gateway` to `<modules>` so `./mvnw verify` builds it.
3. **`infra/docker/docker-compose.yml`** — app instances use `expose:` instead of
   `ports:`; `api-gateway` is the only application service with a `ports:` mapping for
   business HTTP. Unified internal port per service.
4. **`infra/docker/prometheus/prometheus.yml`** — scrape targets moved from
   `host.docker.internal:*` to container DNS names on the Compose network.
5. **`scripts/load-test.ps1` / `load-test-all.ps1`** — defaults changed to
   `http://localhost:8080`.
6. **Gateway actuator exposure** — aligned with ADR-0027 + adds `gateway` endpoint so
   `/actuator/gateway/routes` lists active routing rules (useful at debug time).

---

## Consequences

### Benefits

- **One public URL per service.** `http://localhost:8080/api/v1/starports/...` is the
  stable contract. Clients never learn replica-level addresses.
- **Real external load balancing.** Gateway + LoadBalancer rotate requests across the
  live Eureka registry. A failed instance is skipped on the next call.
- **Scaling is a Compose-only change.** Adding `starport-registry-3` requires one new
  block in `docker-compose.yml` and one new target in `prometheus.yml` — no client,
  gateway, or route change.
- **Reduced attack surface.** Only the gateway is reachable from the host; app
  instances are fenced inside the Compose network.
- **Production-shaped topology.** Swapping Compose for Kubernetes becomes a mechanical
  exercise — gateway becomes an `Ingress`, `expose:` becomes `ContainerPort`, replicas
  become `replicas: 2` on a Deployment. No architectural rewrite.
- **Centralised cross-cutting concerns.** Future additions — CORS, global rate limits,
  JWT validation, request-ID injection — go on the gateway, not duplicated across every
  service.
- **Uniform internal ports** match the Kubernetes and cloud-LB convention (every pod on
  the same port; orchestrator handles the rest).

### Trade-offs

- **One more service to run.** Compose has an extra container (~150 MB image, ~200 MB
  RAM). Fine for a laptop with 8 GB+.
- **Gateway is now a single point of failure.** Production needs ≥2 gateway replicas
  behind a cluster LB (same pattern as ADR-0028 for Eureka). Not implemented in Compose
  (single-instance gateway) — documented as a production gap.
- **Extra network hop.** Gateway → backend adds ~1–3 ms P50 latency locally. Negligible
  for the demo; budget for production SLOs.
- **Debugging individual instances is less convenient.** Previously you could
  `curl :8084` to hit replica 2 directly; now that path is gone. Mitigation: `docker
  compose exec starport-registry-2 wget -qO- http://localhost:8081/actuator/health` or
  add a temporary `ports:` mapping in a dev override file.
- **Gateway routes are another configuration surface.** Adding a new service means
  adding a route in `api-gateway/application.yml`. Forgetting this silently leaves the
  service unreachable from the outside. Mitigation: a gateway contract test could
  assert every registered `spring.application.name` has a matching route.

### Known gaps (production-readiness)

- **Gateway HA.** Single replica today. Production needs ≥2 behind a cluster LB.
- **Auth / JWT / CORS.** Not configured — same gap as ADR-0027. The gateway is where
  it will land.
- **Request rate limiting.** Not configured. Spring Cloud Gateway's `RequestRateLimiter`
  (Redis-backed) is the idiomatic place; deferred.
- **Retry policy on the gateway side.** Intentionally off — callers have their own
  retry contracts (ADR-0014). A second retry layer on the gateway risks N² multiplier.
- **Gateway observability.** Basic actuator + Micrometer. A dedicated Grafana panel for
  gateway latency + per-route traffic is a follow-up.

---

## Alternatives Considered

1. **NGINX in Compose as a reverse proxy.** Works for static routing, but does not
   read Eureka. Scaling requires editing `nginx.conf` and restarting. Rejected — the
   point of the fleet is service discovery; the gateway must participate in it.
2. **Traefik with Docker labels.** Auto-discovers containers via labels. Elegant for
   Compose but not Eureka-native; would force every service to carry both Eureka
   registration and Traefik labels — two sources of truth. Rejected.
3. **Netflix Zuul / Spring Cloud Gateway MVC (servlet) instead of reactive Gateway.**
   Zuul 1 is EOL. Gateway MVC (Spring Cloud Gateway Server MVC) is a genuine option
   — it runs on Tomcat and would benefit from ADR-0012 virtual threads, since the
   gateway's work is mostly I/O (wait for backend response). Picked reactive Gateway
   because a proxy is the archetypal use case for the reactive model: a request
   enters, is held as a `Mono`, proxied downstream, and the response streams back
   without any business logic that would benefit from imperative code. The gateway
   contains no domain code, so the cognitive cost of Reactor (`Mono`/`Flux`,
   `Schedulers`) is paid once, in a small and contained codebase, not diffused
   across business services. Note: `spring.threads.virtual.enabled` has no effect
   here because Reactor Netty does not use a thread-per-request model.
4. **`docker compose --scale`** without named replicas. Would fully randomise ports
   and names. Cleaner in some ways, but Compose scales lose `container_name`, making
   Prometheus scraping and log labels much harder to reason about. Rejected — named
   replicas + unified port is the best trade-off for a demo.
5. **Expose gateway + keep one instance port as "debug".** Considered. Rejected:
   either a port is production-like or it is not; leaving one exposed tempts engineers
   to hard-code it.
6. **Kubernetes Ingress / Service instead of Gateway.** Right answer for production,
   wrong for this demo (ADR-0008 pins Compose). The topology in this ADR is chosen to
   translate cleanly to K8s later — Ingress replaces gateway, Services replace `expose`.

---

## Production-readiness checklist (for a future ADR)

- [ ] Deploy ≥2 gateway replicas behind a cluster-level LB.
- [ ] Configure JWT / mTLS authentication on the gateway.
- [ ] Add `RequestRateLimiter` filter with Redis-backed token bucket.
- [ ] Configure CORS policy centrally.
- [ ] Add a contract test: every `spring.application.name` in Eureka has a matching
      gateway route OR is explicitly allow-listed as "internal only".
- [ ] Consider `ReactiveCircuitBreaker` filter on the gateway as an orthogonal layer
      to caller-side Resilience4j (ADR-0014), with non-overlapping thresholds.
- [ ] Gateway-specific Grafana dashboard: P50/P99 per route, 5xx ratio, route
      throughput.

---

## References

- ADR-0002 — Service Discovery Mechanism (Eureka)
- ADR-0003 — HTTP Load Balancing (Spring Cloud LoadBalancer)
- ADR-0008 — Deployment Topology (Docker Compose)
- ADR-0014 — HTTP Resilience (why no retry at the gateway layer)
- ADR-0026 — Container Build Strategy (the gateway follows the same pattern)
- ADR-0027 — Actuator Exposure (`/actuator/gateway/routes` addition)
- ADR-0028 — Eureka Operational Tuning (gateway is an Eureka client)
- Spring Cloud Gateway —
  https://docs.spring.io/spring-cloud-gateway/reference/index.html
- Kubernetes Ingress —
  https://kubernetes.io/docs/concepts/services-networking/ingress/
