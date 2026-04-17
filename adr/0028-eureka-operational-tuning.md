# 0028 — Eureka Server Operational Tuning (Standalone, Self-Preservation Off, Fast Eviction)

**Status:** Accepted (development configuration; production follow-up required)
**Date:** 2026-04-17

---

## Context

ADR-0002 picked Eureka for service discovery. That decision alone does not specify how
Eureka is configured — and defaults matter. Eureka's default behaviour is **tuned for
large production clusters where churn is bad**, not for Compose dev environments where
container restarts are frequent and developers expect near-instant propagation.

The three defaults that cause the most confusion in development:

1. **Self-preservation mode** — if Eureka loses more than 15 % of heartbeats per minute,
   it stops evicting *any* instance, assuming a network partition. In a dev environment
   where you kill a container to rebuild it, Eureka will keep the dead instance
   registered and the load balancer will keep routing to it. Debugging this is a
   half-hour of "why does `lb://...` give me 500s?".
2. **Eviction interval (60 s)** — Eureka's background eviction job runs every 60 seconds.
   A dev restart of a service produces a window of up to 90 seconds where the dead
   instance is still returned to clients.
3. **Response cache TTL (30 s)** — clients receive a registry snapshot cached for 30 s.
   Fresh registrations are invisible for up to that long even after eviction runs.

In production these defaults are protective. In a dev loop they are a constant source of
surprise. This ADR documents the dev-tuned settings and the production TODO.

---

## Decision

### Standalone mode, explicitly

`eureka-server` runs as a **single non-clustered instance**. It does not try to register
with itself or fetch its own registry:

```yaml
# eureka-server/src/main/resources/application.yml
eureka:
  client:
    register-with-eureka: false
    fetch-registry: false
```

No `service-url.defaultZone` — the server has no peers to replicate with. This is
**correct for a single-node demo** and **wrong for production**, where at minimum
three peers are required for availability (captured in "Production checklist" below).

### Self-preservation disabled (in dev)

```yaml
eureka:
  server:
    enable-self-preservation: ${EUREKA_SELF_PRESERVATION:false}
```

- Local / Compose / CI default: `false` (dev wants eviction).
- Production: the `EUREKA_SELF_PRESERVATION=true` env-var override would re-enable it.

The override is **important**: the env-var form means a production deploy does not need
a code change, just a Compose/K8s manifest edit.

### Aggressive eviction

```yaml
eureka:
  server:
    eviction-interval-timer-in-ms: 5000         # run eviction every 5 s (default 60 s)
    response-cache-update-interval-ms: 3000     # refresh registry cache every 3 s (default 30 s)
    wait-time-in-ms-when-sync-empty: 0          # do not wait for peer sync on startup
```

Effect on a dev restart:

- Dead instance evicted within ~5 s of `leaseExpirationDurationInSeconds` (default 90 s)
  — but see §4, this is tightened in the client too.
- Fresh registry visible to clients within ~3 s of eviction.

Worst case: ~8 s from container death to load balancer stops routing to it (still
gated by the 90 s lease expiration unless the clients are tuned — see §4).

### Client-side tuning (on each service)

Service `application.yml` files also tighten the client:

```yaml
eureka:
  instance:
    lease-renewal-interval-in-seconds: 10        # heartbeat every 10 s (default 30)
    lease-expiration-duration-in-seconds: 30     # expire after 30 s (default 90)
    prefer-ip-address: true                      # register by IP, not hostname
    instance-id: ${spring.application.name}:${HOSTNAME:unknown}:${server.port}
  client:
    registry-fetch-interval-seconds: 5           # poll registry every 5 s (default 30)
```

Combined with server tuning, a killed container disappears from the client-visible
registry in roughly 30 s + 3 s cache + 5 s fetch ≈ 40 s at most, typically 10-20 s.

### Eureka's health indicator + actuator

Eureka itself exposes `/actuator/health` (ADR-0027). Compose healthcheck uses
`grep '"status":"UP"'` to block dependent services from starting too early — `postgres`,
`kafka`, and all application services declare
`depends_on: { eureka: { condition: service_healthy } }`.

### Instance ID policy

```yaml
eureka.instance.instance-id: ${spring.application.name}:${HOSTNAME:unknown}:${server.port}
```

- `spring.application.name` — logical service name (what callers use in `lb://...`).
- `HOSTNAME` — Docker container hostname, ensures per-instance uniqueness.
- `server.port` — disambiguates when two containers share a hostname (unusual but
  possible when a sidecar also binds a port).

The `HOSTNAME:unknown` fallback exists so `./mvnw spring-boot:run` on a laptop (no
`HOSTNAME` env var) still produces a unique-ish ID.

---

## How the codebase enforces this

1. **Server config** lives only in `eureka-server/src/main/resources/application.yml`.
   Every tuning value has a one-line comment stating "dev-local" or "override for prod".
2. **Client config** lives in each service's `application.yml` under `eureka.*`. A grep
   for `lease-renewal-interval-in-seconds` returns three hits (starport-registry,
   trade-route-planner, telemetry-pipeline) — kept in sync by review.
3. **Compose** (`infra/docker/docker-compose.yml`) depends on Eureka's `service_healthy`
   for every application service; nothing else starts until Eureka's `/actuator/health`
   returns UP.
4. **Env-var override discipline** — every production-sensitive tunable (`EUREKA_URL`,
   `EUREKA_SELF_PRESERVATION`) is configurable via env var with a dev-sensible default
   (ADR-0009).

---

## Consequences

### Benefits

- **Dev loop is predictable.** Restart a container, wait ~15 s, traffic is rerouted.
  No 90 s-and-counting "ghost instance" debugging sessions.
- **Observability stack starts in order.** Eureka's healthcheck gates every downstream
  service's startup, preventing the race where a service boots and fails to register
  because Eureka isn't listening yet.
- **Single config file per service for Eureka concerns.** All Eureka knobs are in
  `application.yml`; no scattered `@Configuration` classes touching Eureka internals.
- **Env-var-overridable.** Production hardening is a manifest change, not a code change.

### Trade-offs and known production gaps

- **No HA for Eureka itself.** A single Eureka instance is a SPOF — if it dies, new
  services cannot register and existing clients' 30 s registry cache degrades.
  Mitigation: run three peers in production (see checklist).
- **Self-preservation off means fast eviction under real partitions.** In a genuine
  network partition, `enable-self-preservation: false` would evict healthy instances
  that cannot heartbeat due to the partition, worsening the outage. Re-enabling in
  production is not optional.
- **Tight heartbeat intervals load the Eureka server.** 10 s heartbeats × N instances ×
  30 services would stress a small Eureka. Fine for our 3-service fleet; revisit if the
  fleet grows past 15 services.
- **No `preferSameZone` / zone-aware routing.** The current setup is flat. Production in
  multiple AZs should configure `metadataMap.zone` and zone-preferring load balancers
  (ADR-0003 Spring Cloud LoadBalancer supports this).
- **Instance-ID depends on `HOSTNAME`.** In some orchestration runtimes (Jib-built
  images, Kubernetes with `hostname` set to pod name) this works; in others (ECS with
  empty hostname) it falls back to `unknown`, and two containers collide. A more robust
  production fallback is `UUID.randomUUID()`.
- **Actuator `/actuator/health/liveness` is not tied to Eureka registration.** If
  Eureka is down but the JVM is healthy, the instance reports `UP` even though no one
  can reach it. Mitigation: include an `EurekaHealthIndicator` in the composite health
  (Spring Cloud Netflix provides this by default when the starter is on the classpath;
  currently relied upon implicitly).

---

## Alternatives Considered

1. **Default Eureka settings.** Rejected — the 60 s eviction interval + 30 s response
   cache + self-preservation on makes the dev loop painful, as documented in Context.
2. **Kubernetes DNS + native discovery (`spring.cloud.discovery.enabled=false`).**
   Rejected because Compose is the demo runtime (ADR-0008); K8s DNS does not work in
   Compose.
3. **Consul or etcd.** Rejected by ADR-0002. A richer KV store is not needed.
4. **Disable eviction entirely and rely on client-side retry.** Considered. Rejected
   because stale registry entries + retries would produce thundering-herd behaviour on
   dead instances during restart storms.
5. **Longer client registry fetch (30 s default).** Rejected for dev — means a freshly
   started instance waits up to 30 s before becoming reachable via `lb://`. Kept
   aggressive (5 s) to match the restart cadence.

---

## Production-readiness checklist (follow-up ADR territory)

Before deploying Eureka to a non-demo environment:

- [ ] **At least three Eureka peers** — register-with-eureka: true, fetch-registry: true,
      service-url.defaultZone listing the other peers.
- [ ] **Re-enable self-preservation** — `EUREKA_SELF_PRESERVATION=true`.
- [ ] **Relax eviction intervals** — back toward defaults (60 s eviction, 30 s response
      cache) once self-preservation protects against false evictions.
- [ ] **Tighten heartbeat only if fleet size is small**; otherwise revert clients to
      30 s renew / 90 s expiration to reduce server load.
- [ ] **Enable zone awareness** — `metadataMap.zone` in each service, zone-preferring
      load balancer on the caller.
- [ ] **Secure Eureka's UI and registry endpoints** (Spring Security, mTLS, or
      network-layer) — same gap as ADR-0027.
- [ ] **TLS on registry replication** between peers.
- [ ] **Backup of registry state** during upgrades (Eureka is eventually consistent and
      has no persistent store by default).

---

## References

- ADR-0002 — Service Discovery Mechanism (why Eureka)
- ADR-0003 — HTTP Load Balancing (how clients consume the registry)
- ADR-0008 — Deployment Topology (Compose `depends_on: service_healthy`)
- ADR-0009 — Configuration Management (env-var override pattern)
- ADR-0027 — Actuator Exposure (Eureka healthcheck endpoint)
- Spring Cloud Netflix Eureka —
  https://docs.spring.io/spring-cloud-netflix/docs/current/reference/html/
- Netflix: Eureka at cloud scale —
  https://netflixtechblog.com/eureka-2-0-discontinued-485ef4ae0bc4
- Adrian Cole on self-preservation mode —
  https://github.com/Netflix/eureka/wiki/Understanding-Eureka-Peer-to-Peer-Communication
