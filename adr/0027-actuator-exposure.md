# 0027 — Spring Boot Actuator: Endpoint Exposure & Security Posture

**Status:** Accepted (with production-hardening follow-up explicitly noted)
**Date:** 2026-04-17

---

## Context

Every service depends on `/actuator/*` for three audiences: Prometheus scrapes
`/actuator/prometheus` (ADR-0005), Compose probes `/actuator/health` to gate
`depends_on: service_healthy` (ADR-0008), and humans hit `/actuator/info`. Spring Boot's
default is "expose only health + info"; we deliberately expose more. Production
hardening (separate management port, Spring Security) is **not** applied today because
every observer lives on the same Docker network; there is no attacker-reachable path.

---

## Decision

**Whitelist exposure** in every service's `application.yml`:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus,liveness,readiness
  endpoint:
    health:
      show-details: always
      probes:
        enabled: true
```

Eureka drops `metrics` from its include list (no custom Micrometer counters worth
inspecting); otherwise identical.

**Deliberately NOT exposed**: `env`, `configprops`, `heapdump`, `threaddump`,
`loggers` (write), `shutdown`. Each leaks secrets, memory, or grants runtime mutation.

**`show-details: always`** — Compose healthchecks need the body; humans need to see
which subcomponent failed during incidents. Operators and attackers see the same thing;
acceptable in Compose, must become `when-authorized` in prod.

**Management on the business port.** `management.server.port` not set. Simplifies
Compose and shares the Tomcat pool (cheap on virtual threads, ADR-0012). Couples
availability — a stuck business handler can starve healthchecks; a separate management
port is the prod fix.

**No Spring Security** in any POM. `/actuator/*` is reachable from any container on the
Compose network — exactly the intended audience. Production options: bind management to
`127.0.0.1`, gate with Spring Security + scrape principal, or rely on a service-mesh
policy.

**Probes split** (`probes.enabled: true`) gives `/actuator/health/liveness` (JVM alive?)
and `/actuator/health/readiness` (accept traffic?). Compose uses the composite
`/actuator/health`; Kubernetes (future) would use the probes individually.

**`/actuator/info` is empty** today — `build-info` + git-commit-id plugin is a 5-line
follow-up.

---

## Why

- **Observability works out of the box.** Prometheus scrapes, healthchecks green-light
  startup, dashboards populate — no Security config to debug during onboarding.
- **Fast incident response.** `show-details: always` names the failing subsystem.
- **K8s-ready endpoints.** Migration is a manifest change; probes already exist.
- **Small attack surface even without Security.** The dangerous endpoints (`env`,
  `heapdump`, `shutdown`, runtime `loggers`) are explicitly off; a PR adding one is
  reviewable.

---

## Alternatives

- **Expose `*`** — flat-out rejected; the default starter set is dangerous.
- **Expose only `health` + `prometheus`** — drops `metrics`/`info`/probes for negligible
  risk reduction and meaningful operability loss.
- **Separate management port now** — adds a per-service port to Compose and complicates
  local `curl`. Deferred to production.
- **Spring Security + Basic Auth now** — half-step without a broader auth model; the
  demo runs auth-free intentionally.
- **Disable `show-details`** — makes integration debugging significantly harder for no
  demo-environment gain.

---

## Production-readiness checklist (future ADR)

- [ ] Management on a non-public interface, or Spring Security with scrape principal, or
  network-policy enforcement
- [ ] `show-details: when-authorized`
- [ ] `build-info` + git metadata populated
- [ ] Consider `auditevents` with a durable sink
- [ ] Wire K8s liveness/readiness to the individual probes, not the composite

---

## References

- ADR-0005 — Observability Stack (Prometheus scrape)
- ADR-0008 — Deployment Topology (Compose healthchecks)
- ADR-0012 — Virtual Threads (why a shared listener is less risky)
- ADR-0017 — Tracing Propagation
- Spring Boot Actuator —
  https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html
- CWE-215 — Information Exposure Through Debug Information —
  https://cwe.mitre.org/data/definitions/215.html
