# 0027 — Spring Boot Actuator: Endpoint Exposure & Security Posture

**Status:** Accepted (with production-hardening follow-up explicitly noted)
**Date:** 2026-04-17

---

## Context

Every service in the fleet depends on `/actuator/*` for three distinct audiences:

1. **Prometheus** (ADR-0005) scrapes `/actuator/prometheus` on each instance.
2. **Docker Compose healthchecks** (ADR-0008) probe `/actuator/health` on each instance,
   and `depends_on: { condition: service_healthy }` blocks startup until it returns `UP`.
3. **Humans / CI** may hit `/actuator/info` for build metadata.

Actuator endpoints are powerful — some of them (`env`, `heapdump`, `threaddump`,
`configprops`) trivially leak secrets, configuration, or memory contents. Spring Boot 3's
default policy is "expose only `health` and `info`". We deliberately expose more, and the
exposure is not (currently) fenced by authentication.

The alternative — exposing `/actuator/*` on a separate `management.server.port` bound only
to an internal network interface — would be the production hardening path. We do not
apply it today because every observer (Prometheus, Compose healthcheck) already lives on
the same Docker network as the service; there is no attacker-reachable network path to
actuator in the demo topology.

This ADR documents **what is exposed, why, what is deliberately NOT exposed, and what must
change before production**.

---

## Decision

### 1. Exposed endpoints (all four services)

| Endpoint           | Exposed | Rationale                                                        |
|---|---|---|
| `health`           | yes     | Compose healthcheck; Eureka client uses readiness state          |
| `info`             | yes     | Build metadata; harmless                                         |
| `metrics`          | yes     | Ad-hoc Micrometer inspection during debugging                    |
| `prometheus`       | yes     | Prometheus scrape target (ADR-0005)                              |
| `liveness`         | yes     | Kubernetes-style liveness probe (future-proofing ADR-0008)       |
| `readiness`        | yes     | Kubernetes-style readiness probe; Eureka uses this to gate traffic |
| `env`              | **no**  | Leaks every environment variable including DB passwords          |
| `configprops`      | **no**  | Leaks full Spring configuration tree                             |
| `heapdump`         | **no**  | Downloads live heap — potential for OOM on request, secret leak  |
| `threaddump`       | **no**  | Exposes internal thread names and stack traces                   |
| `loggers` (write)  | **no**  | Allows runtime log-level changes — deliberately omitted          |
| `shutdown`         | **no**  | Self-explanatory                                                 |

Exposure is configured per-service in `application.yml`:

```yaml
# all four services' application.yml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus,liveness,readiness
  endpoint:
    health:
      show-details: always
      probes:
        enabled: true   # enables /actuator/health/liveness and /readiness
```

**Eureka** exposes a slightly narrower set (`health,info,prometheus,liveness,readiness`)
— no `metrics` because Eureka's business metrics are uninteresting and because the
service has no custom Micrometer counters worth inspecting.

### 2. `show-details: always`

Health details are always returned, not gated behind `when-authorized`. Consequences:

- Compose healthchecks grep for `"status":"UP"` and accept any body — details are
  tolerated.
- Humans see which subcomponent failed (DB down? Kafka binder disconnected? circuit
  breaker open?) without logging in.
- **Operators and attackers see the same thing.** That is the trade-off: debuggability
  over information hiding.

In production, this should be `when-authorized` behind Spring Security. Flagged below.

### 3. Management runs on the business port

`management.server.port` is not set. `/actuator/*` shares the same HTTP listener as
`/api/v1/*`. This:

- Simplifies Compose (one port per service).
- Shares the Tomcat thread pool (ADR-0012) between business and management endpoints.
  Virtual threads make this cheap.
- **Couples availability** — if a business handler deadlocks Tomcat, the health endpoint
  also deadlocks, potentially causing orchestrators to kill a container that is only
  partially unhealthy. On virtual threads this is less of a problem than on platform
  threads.

A separate management port is the common production pattern (bind management to
`127.0.0.1` or a private interface). Deferred.

### 4. No Spring Security

There is **no** `spring-boot-starter-security` in any service's POM. All of
`/actuator/*` is publicly reachable on the container's business port. In the Compose
network, "publicly" means "reachable from any container on the Compose network" — which
is exactly who needs it (Prometheus, Compose healthcheck).

On a public deployment, this is unacceptable. The production posture would be one of:

- (a) Bind management to a non-routable interface (`management.server.address=127.0.0.1`
  with a sidecar scraper).
- (b) Gate `/actuator/*` behind Spring Security with Basic Auth or mTLS to a scrape user.
- (c) Deploy in a private mesh where the network layer enforces access.

### 5. `info` contributors not populated

`management.info.*` is not configured anywhere. `/actuator/info` returns `{}`. Git and
build metadata could be added with `spring-boot-maven-plugin build-info` + the Git Commit
ID plugin — a small ergonomic win at PR review time ("which commit is this deployed
instance running?"). Deferred; the 5-line fix is tracked as a follow-up.

### 6. Probes split into `liveness` and `readiness`

`probes.enabled: true` gives two separate endpoints:

- `/actuator/health/liveness` — "is the JVM alive and application context initialised?"
  A failure here restarts the container.
- `/actuator/health/readiness` — "is this instance ready to accept traffic?" A failure
  de-registers the instance from Eureka's load-balancer rotation (`ApplicationAvailability`
  signals propagate to the Eureka client).

The Compose healthcheck uses the composite `/actuator/health`, not the probes. This is
deliberate: Compose does not need to distinguish startup from readiness — it just waits
for `UP` before launching dependent services. Kubernetes would use the probes
individually.

---

## How the codebase enforces this

1. **Per-service `application.yml` blocks.** Four nearly identical
   `management.endpoints.web.exposure.include` declarations. Mini-drift (Eureka lacks
   `metrics`) is deliberate and documented here.
2. **Compose healthcheck** — `infra/docker/docker-compose.yml` — probes
   `http://localhost:<port>/actuator/health` via `wget -qO- ... | grep '"status":"UP"'`.
3. **Prometheus scrape config** — `infra/docker/prometheus/prometheus.yml` — points at
   `/actuator/prometheus` on each service instance (ADR-0005).
4. **Absence of Security starter** — `grep spring-boot-starter-security` across all
   POMs returns nothing. Intentional; revisit when production auth model is chosen.
5. **Dev defaults preserved** — no custom filters, no RBAC on actuator. The Spring Boot
   "expose nothing by default" shield is opened to the listed whitelist only.

---

## Consequences

### Benefits

- **Observability works out of the box.** Prometheus scrapes, healthchecks green-light
  startup, dashboards show data — no extra Security config to debug during onboarding.
- **Fast incident response.** `show-details: always` means a failed health check names
  the failing component. Saves 30 minutes of diffing configs during a midnight incident.
- **Probes align with Kubernetes.** Migrating to K8s later is a manifest change; the
  endpoints already exist.
- **Small attack surface** (even without Security). The exposed endpoint set is
  deliberately minimal: no `env`, `heapdump`, `threaddump`, `shutdown`, runtime logger
  writes. A PR that adds one of those to `exposure.include` is reviewable.

### Trade-offs and production gaps (explicit)

- **No authentication on actuator.** Acceptable within Compose; unacceptable on an
  internet-reachable deploy. Blocking for production.
- **`show-details: always` leaks structure.** An attacker learning that "Kafka binder
  is DOWN" narrows reconnaissance. Mitigation in prod: `when-authorized`.
- **Shared listener couples business and management availability.** A stuck business
  endpoint can starve healthchecks. Virtual threads (ADR-0012) mitigate this; a separate
  `management.server.port` eliminates it.
- **No `/actuator/info` metadata.** Operators cannot tell image version from the
  endpoint alone; they must read container labels or logs.
- **No `loggers` endpoint.** Runtime log-level changes require a redeploy. Deliberate —
  an unauthenticated `loggers` endpoint is a CVE waiting to happen.

### Explicit non-decisions

- **Spring Boot Admin UI.** Not deployed. Could aggregate all services' actuator data in
  one dashboard. Deferred.
- **Actuator audit log endpoint** (`auditevents`). Not exposed; requires Spring Security
  auditing to produce meaningful data.
- **Custom HealthIndicators.** None added beyond Spring's defaults (DataSource, DiskSpace,
  Kafka binder, Ping). A business-level indicator (e.g. "outbox backlog under N rows")
  could become a composite `starport-ready` group via
  `management.endpoint.health.group.*` — deferred.

---

## Alternatives Considered

1. **Expose `*` (everything).** Flat-out rejected; the default starter set is dangerous.
2. **Expose only `health` + `prometheus`.** Considered. Rejected because `info`,
   `metrics`, `liveness`, `readiness` have negligible risk and non-negligible
   operability value.
3. **Separate management port from day one.** Considered; would add a per-service port
   mapping to Compose and complicate local `curl` ergonomics. Deferred until production
   requires it.
4. **Spring Security with Basic Auth right now.** Rejected because the demo explicitly
   runs without auth (ADR's README gap-list). Adding Security just for actuator without
   a broader auth policy would be a half-step.
5. **Disable `show-details` now.** Rejected — makes debugging an integration failure
   significantly harder for no demo-environment gain.

---

## Production-readiness checklist (for a future ADR)

Before any internet-reachable deploy, the following must be decided:

- [ ] Actuator on `management.server.port` bound to non-public interface, or
- [ ] Spring Security with a dedicated scrape principal, or
- [ ] Network policy / service mesh restricting `/actuator/*` ingress
- [ ] `management.endpoint.health.show-details: when-authorized`
- [ ] `management.info.git.mode: full` + `build-info` populated
- [ ] Consider enabling `auditevents` with a durable audit sink
- [ ] Rate-limit `/actuator/prometheus` at the ingress layer
- [ ] If adopting Kubernetes, wire liveness/readiness to probes explicitly (not the
      composite `health` endpoint)

---

## References

- ADR-0005 — Observability Stack (Prometheus scrape)
- ADR-0008 — Deployment Topology (Compose healthchecks)
- ADR-0009 — Configuration Management (env-var overrides for probe endpoints)
- ADR-0012 — Virtual Threads (why shared listener is less risky)
- ADR-0017 — Tracing Propagation (correlation for actuator traces)
- Spring Boot Actuator —
  https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html
- CWE-215: Information Exposure Through Debug Information —
  https://cwe.mitre.org/data/definitions/215.html
