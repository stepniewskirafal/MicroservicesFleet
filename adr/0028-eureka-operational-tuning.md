# 0028 — Eureka Operational Tuning (Standalone, Fast Eviction)

**Status:** Accepted (development configuration; production follow-up required)
**Date:** 2026-04-17

---

## Context

ADR-0002 picked Eureka but did not tune it. Eureka's defaults (60 s eviction interval,
30 s response cache, self-preservation on) protect large clusters from churn but make a
Compose dev loop painful — a killed container stays in the registry for ~90 s and
clients keep routing to it. This ADR pins the dev-friendly tuning and flags the
production gaps.

---

## Decision

Run `eureka-server` standalone with self-preservation **off by default but
env-overridable**, and tighten eviction + cache + client heartbeats so a dead container
disappears in 10–20 s instead of 90.

```yaml
# eureka-server/src/main/resources/application.yml
eureka:
  client:
    register-with-eureka: false
    fetch-registry: false
  server:
    enable-self-preservation: ${EUREKA_SELF_PRESERVATION:false}
    eviction-interval-timer-in-ms: 5000        # default 60 000
    response-cache-update-interval-ms: 3000    # default 30 000
    wait-time-in-ms-when-sync-empty: 0
```

Every client service tightens its lease so a dead instance expires fast:

```yaml
eureka:
  instance:
    lease-renewal-interval-in-seconds: 10      # default 30
    lease-expiration-duration-in-seconds: 30   # default 90
    prefer-ip-address: true
    instance-id: ${spring.application.name}:${spring.cloud.client.hostname:${HOSTNAME:unknown}}:${server.port}
```

`api-gateway` additionally pulls the registry faster so routing reacts to scale events
quickly — it is the one client whose fetch latency users feel:

```yaml
eureka.client.registry-fetch-interval-seconds: 5   # default 30
```

Compose gates every application service on `eureka { condition: service_healthy }`
(ADR-0027 healthcheck) so nothing boots before the registry is up.

---

## Why

- **Predictable dev loop.** Restart a container, traffic reroutes in ~15 s — no
  "ghost instance" debugging.
- **Env-var override discipline (ADR-0009).** Production hardens via profile/manifest,
  not code: `application-prod.yml` already sets `enable-self-preservation: true`, and
  `EUREKA_SELF_PRESERVATION=true` flips the protective default back on in any profile.
- **Standalone is correct for a single-node demo.** No peers, no replication, no
  self-registration loop.
- **Startup ordering.** Healthcheck-gated `depends_on` removes the race where a service
  fails its first registration attempt because Eureka isn't listening yet.

---

## Alternatives

- **Eureka defaults** — rejected; 90 s ghost instances dominate the dev loop.
- **Kubernetes DNS / native discovery** — Compose is the demo runtime (ADR-0008).
- **Consul / etcd** — already rejected by ADR-0002; richer KV store not needed.
- **Disable eviction, rely on client retry** — risks thundering-herd on dead instances
  during restart storms.

---

## Production gaps (follow-up ADR)

- Run ≥3 Eureka peers; set `register-with-eureka: true` and `service-url.defaultZone`.
- Relax eviction back toward defaults (self-preservation is already re-enabled by the
  `prod` profile).
- Revert client heartbeats to 30 s / 90 s once the fleet grows past ~15 services.
- Zone awareness (`metadataMap.zone` + zone-preferring LB).
- Secure registry endpoints (Spring Security / mTLS) — same gap as ADR-0027.
- More robust instance-ID fallback than `HOSTNAME:unknown` (e.g. `UUID.randomUUID()`).

---

## References

- ADR-0002 — Service Discovery (why Eureka)
- ADR-0003 — HTTP Load Balancing (how clients consume the registry)
- ADR-0008 — Deployment Topology (`depends_on: service_healthy`)
- ADR-0009 — Configuration Management (env-var override pattern)
- ADR-0027 — Actuator Exposure (Eureka healthcheck)
