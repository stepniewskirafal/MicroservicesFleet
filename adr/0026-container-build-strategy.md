# 0026 — Container Build Strategy: Multi-stage Dockerfile, Temurin 21 JRE, Container-aware JVM

**Status:** Accepted
**Date:** 2026-04-17

---

## Context

Four services ship as Docker images (ADR-0008): three Spring Boot applications and the
Eureka server. Each image decision has outsized consequences:

- **Base image** affects image size, vulnerability surface, and container-aware JVM
  behaviour.
- **Build reproducibility** affects CI flakiness and local-vs-CI parity.
- **JVM memory flags** affect whether the container honours cgroup limits or silently
  consumes host memory.
- **User inside the container** affects how CVEs that grant code execution escalate (or
  don't).
- **Layer ordering** affects incremental rebuild time — get it wrong and every code
  change re-downloads Maven dependencies.

This ADR documents the container conventions applied uniformly across all four services
and calls out known gaps that would need to be closed before production.

---

## Decision

### 1. Multi-stage Dockerfile with Maven + Temurin 21

Each service uses an identical two-stage pattern:

```dockerfile
# Stage 1: build
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY <module>/pom.xml <module>/
RUN mvn -f <module>/pom.xml dependency:go-offline -am -DskipTests
COPY . .
RUN mvn -f <module>/pom.xml package -DskipTests -am

# Stage 2: runtime
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app
COPY --from=build /build/<module>/target/*.jar app.jar
EXPOSE <port>
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
```

Rationale for each choice:

- **`maven:3.9-eclipse-temurin-21`** for build — matches the wrapper version committed to
  `.mvn/` (ADR-0025) so local and image builds use the same Maven bytecode.
- **`eclipse-temurin:21-jre-alpine`** for runtime — JRE (not JDK; no `javac` needed at
  runtime). Alpine base trims roughly 100 MB compared to the full Ubuntu-based Temurin.
- **`dependency:go-offline -am`** before copying sources — creates a cached layer of
  third-party JARs that only invalidates on `pom.xml` changes. Code-only edits rebuild
  in seconds.
- **`-DskipTests` in the image build** — tests run in CI via `mvn verify`, not during
  image build. Running tests twice wastes cycles; failing an image build because a
  flaky Testcontainers boot stalled is a bad failure mode.
- **`-am` (also-make)** — compiles the parent aggregator plus required inter-module deps
  (ADR-0025) into the build stage, so the service module can resolve its siblings.

### 2. Container-aware JVM flags

```dockerfile
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
```

- **`-XX:+UseContainerSupport`** — honours the cgroup `memory.limit_in_bytes` and
  `cpu.cfs_quota_us`. Without it the JVM reads `/proc/meminfo` and sees the host, then
  OOM-kills the container. This flag is on by default in Java 11+; it is set explicitly
  to prevent an accidental `--no-container-support` downgrade from silently breaking
  memory tuning.
- **`-XX:MaxRAMPercentage=75.0`** — heap gets 75 % of the container's memory limit. The
  remaining 25 % goes to: JVM metaspace, code cache, direct buffers (Netty / Kafka),
  native libraries (Postgres JDBC driver). 75 % is the Spring Boot community default for
  Docker; pushing to 90 % is a recipe for native-memory OOMs.

Both flags together are non-negotiable: removing either re-opens the "JVM thinks it has
host RAM" bug.

### 3. One port per service

| Service              | Container port | Notes                            |
|---|---|---|
| eureka-server        | 8761           | Eureka conventional port         |
| starport-registry    | 8081           | Scaled in Compose as `-1` / `-2` on 8081/8084 |
| trade-route-planner  | 8082           | Scaled as `-1` / `-2` on 8082/8083 |
| telemetry-pipeline   | 8090           | Scaled as `-1` / `-2` on 8090/8091 |

Port is set by `EXPOSE` (documentation only) plus the Spring `PORT` env var (ADR-0009).

### 4. No `.dockerignore` today — known gap

There is no `.dockerignore`. Every `COPY . .` in the build stage copies the entire repo
(including `target/`, `.git/`, `.idea/`). Mitigated slightly by the fact that the build
stage's `target/` is discarded with the image, but:

- **Build cache churn** — a change to `.idea/` invalidates the source COPY layer.
- **Leakage risk** — a local `.env` or an IDE-generated file can end up in build
  contexts and potentially in reproductions.

This is a cleanup candidate (see Consequences).

### 5. Root user, no `USER` directive — known gap

No `USER` directive is set; the runtime process is PID 1 as root inside the container.
Acceptable in a local/Compose context (Docker's default userns is already isolated), but
**must** be addressed before production:

- Standard fix: `RUN addgroup -S app && adduser -S app -G app && USER app`.
- Defence in depth: a CVE that grants code exec with `root` inside the container can, in
  some docker/runc configurations, escape more easily than the same CVE with an
  unprivileged UID.

Flagged here, not fixed here — a security-hardening ADR (currently in the README gaps)
would cover this plus Spring Security at the same time.

### 6. No `HEALTHCHECK` in Dockerfile — health is composed

Health is defined at the Compose layer (`docker-compose.yml`) using
`wget -qO- http://localhost:<port>/actuator/health`. Keeping health out of the Dockerfile:

- Avoids two sources of truth (Dockerfile `HEALTHCHECK` vs Compose `healthcheck`). Compose
  wins.
- Lets us express `depends_on: { condition: service_healthy }` — the Compose feature that
  requires Compose-level health, not Dockerfile-level.

If the images were ever deployed to Kubernetes, the HEALTHCHECK would be moved into the
Dockerfile or replaced by `livenessProbe` / `readinessProbe` in the manifest — both
approaches use the same `/actuator/health` endpoint (ADR-0027).

---

## How the codebase enforces this

1. **Dockerfile per module, identical structure.** A diff across the four files shows
   only port, module name, and EXPOSE differ.
2. **Local-to-image Maven parity.** Both use `maven:3.9-eclipse-temurin-21`; the same
   wrapper-pinned Maven resolves the same dependencies — reproducibility is a
   hash-comparable property.
3. **Compose owns orchestration.** `infra/docker/docker-compose.yml` builds each image,
   wires env vars (ADR-0009), and sets healthchecks. Each app service is defined twice
   (`-1`, `-2`) to satisfy ADR-0008's ≥2-instance requirement.
4. **No base image drift.** Changing the base image is a four-file diff — reviewable.
5. **Alpine-ships musl; PostgreSQL JDBC is pure Java** — no glibc dependency, so the
   Alpine base is safe. (A native library with glibc assumptions would need
   `-ubuntu-jammy` instead.)

---

## Consequences

### Benefits

- **Small runtime images.** `eclipse-temurin:21-jre-alpine` is roughly 140 MB; plus the
  fat JAR yields images under 250 MB.
- **Predictable memory behaviour in containers.** The JVM sees cgroup limits; heap
  stays inside bounds; OOM-killer only fires on genuine leaks, not miscalibration.
- **Fast incremental rebuilds.** The `go-offline` layer caches third-party JARs; a
  typical Java source change rebuilds in under 30 s.
- **Build reproducibility.** Both stages pin to `eclipse-temurin-21`; no
  `FROM openjdk:latest` drift over time.
- **Consistent entrypoint.** Every service is launched the same way; `docker compose run`
  behaves identically across services.

### Trade-offs and gaps

- **Root user.** Local/Compose compromise; production blocker. See §5.
- **No `.dockerignore`.** Build context bloat and minor leakage risk. See §4.
- **Alpine musl libc.** `libc` differences can surprise native-library consumers. Today
  there are none; adding a library with JNI (e.g. RocksDB for a future state store) would
  force a base-image reevaluation.
- **Build caches are per-machine.** No shared registry cache in CI today. For a fleet of
  four, rebuild time is tolerable; at ten+ services a shared Docker BuildKit cache becomes
  necessary.
- **Fat-JAR image vs layered image.** Spring Boot 3 supports `layertools` to split the
  JAR into `dependencies`, `spring-boot-loader`, `snapshot-dependencies`,
  `application` — speeds rebuilds of code-only changes. We do not use this yet; the
  current layer split (pom → go-offline → source) achieves 80 % of the benefit with
  fewer moving parts.
- **No SBOM / provenance.** No `docker buildx` provenance attestation, no CycloneDX SBOM
  generation. For a demo this is fine; for a production artifact shipping to a shared
  registry it is audit-trail-worthy.
- **`EXPOSE` is documentation, not enforcement.** A process can bind to any port; the
  declared `EXPOSE` is honoured only by tooling that reads it (Compose, `docker ps`).
  Acceptable.
- **Build-stage caching is sensitive to aggregator changes.** `-am` reads the root
  POM; a change to a sibling module's `pom.xml` invalidates the build stage even when
  the current module did not change. Rare enough to ignore.

---

## Alternatives Considered

1. **Jib (Google's containerless build).** Would eliminate the Dockerfile entirely and
   produce reproducible, layered images faster than `docker build`. Rejected because
   the team already knows Dockerfile semantics; Jib is a new DSL. Worth revisiting when
   build time becomes a bottleneck.
2. **Spring Boot buildpack (`mvn spring-boot:build-image`).** One command produces a
   Paketo-based image with opinionated defaults (AppCDS, CDS, memory calculator).
   Rejected today because Paketo's memory calculator and our `MaxRAMPercentage=75.0`
   disagree on buffer allocations, producing subtle differences we'd have to chase down.
3. **Distroless (`gcr.io/distroless/java21`).** Smaller attack surface (no shell, no
   package manager). Rejected for local dev because debugging (`kubectl exec ... sh`)
   becomes impossible; the value returns in production but not in Compose.
4. **Full Ubuntu-based Temurin.** `eclipse-temurin:21-jre-jammy`. Larger (roughly +100
   MB) with no benefit for the pure-Java workloads here.
5. **Single-stage Dockerfile** (`FROM maven`, build and run in the same image). Rejected:
   ships Maven + JDK in the runtime image, quadrupling image size.
6. **Bundle JRE with jlink in build stage.** Custom jlink-minimised JRE is a viable
   alternative to `21-jre-alpine`. Rejected as premature — the standard JRE is already
   small enough and easier to audit for CVEs.

---

## References

- ADR-0008 — Deployment Topology (Compose orchestration)
- ADR-0009 — Configuration Management (env var injection)
- ADR-0012 — Virtual Threads (why Java 21 specifically)
- ADR-0025 — Maven Build Topology (the build stage mirrors local `./mvnw`)
- ADR-0027 — Actuator Exposure (where `HEALTHCHECK` URLs come from)
- JDK Enhancement Proposal 379 — Container awareness —
  https://openjdk.org/jeps/379 (background on `UseContainerSupport`)
- Spring Boot Docker images —
  https://docs.spring.io/spring-boot/docs/current/reference/html/container-images.html
- Eclipse Temurin —
  https://adoptium.net/temurin/
