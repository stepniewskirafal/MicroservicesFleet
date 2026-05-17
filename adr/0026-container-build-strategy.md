# 0026 — Container Build: Multi-stage Dockerfile, Temurin 21 JRE, Container-aware JVM

**Status:** Accepted
**Date:** 2026-04-17

---

## Context

Four services ship as Docker images (ADR-0008). Image choices have outsized
consequences: base image drives size + CVE surface, JVM flags decide whether cgroup
limits are honoured, layer ordering decides whether code edits re-download Maven, and
the run-as user decides how a remote-code-execution CVE escalates. This ADR locks in the
conventions applied uniformly and flags known production gaps.

---

## Decision

**Two-stage Dockerfile, identical per service**:

```dockerfile
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY <module>/pom.xml <module>/
RUN mvn -f <module>/pom.xml dependency:go-offline -am -DskipTests
COPY . .
RUN mvn -f <module>/pom.xml package -DskipTests -am

FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app
COPY --from=build /build/<module>/target/*.jar app.jar
EXPOSE <port>
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
```

Build stage uses `maven:3.9-eclipse-temurin-21` to match the wrapper version pinned by
ADR-0025; runtime is JRE-only on Alpine (~140 MB image). `dependency:go-offline -am`
caches third-party JARs in a layer that only invalidates on `pom.xml` changes — code
edits rebuild in seconds. Tests run in CI (`mvn verify`), not during image build.

**Container-aware JVM flags are non-negotiable**:

- `-XX:+UseContainerSupport` — honours cgroup memory/CPU limits. On by default in Java
  11+; set explicitly to prevent silent regression.
- `-XX:MaxRAMPercentage=75.0` — heap gets 75 % of the container limit; the remaining
  25 % covers metaspace, code cache, direct buffers, native libs. Pushing higher invites
  native-memory OOMs.

**Health is owned by Compose**, not the Dockerfile. `infra/docker/docker-compose.yml`
runs `wget -qO- /actuator/health | grep '"status":"UP"'` so `depends_on:
service_healthy` works. Two sources of truth (Dockerfile `HEALTHCHECK` + Compose
`healthcheck`) is worse than one.

**Known production gaps** (intentional today, blockers for prod):

- **No `USER` directive** — process runs as root inside the container. Fix:
  `addgroup -S app && adduser -S app -G app && USER app`.
- **No `.dockerignore`** — `COPY . .` pulls in `target/`, `.git/`, `.idea/`. Causes
  cache churn and minor leakage risk.
- **No SBOM / provenance attestation.** Fine for a demo, audit-worthy for prod.

---

## Why

- **Small runtime images** (~140 MB JRE + fat JAR under 250 MB total).
- **Predictable container memory.** JVM sees cgroups; OOM-killer only fires on real
  leaks, not miscalibration.
- **Fast incremental rebuilds** via the `go-offline` cache layer.
- **Reproducibility.** Both stages pin `eclipse-temurin-21`; no `:latest` drift.
- **Local-to-image parity.** Same Maven version locally and in the build stage.

---

## Alternatives

- **Jib** — reproducible layered builds without a Dockerfile, but a new DSL the team
  doesn't know. Revisit when build time becomes the bottleneck.
- **`spring-boot:build-image` (Paketo buildpack)** — Paketo's memory calculator
  conflicts subtly with `MaxRAMPercentage=75`; not worth chasing.
- **Distroless (`gcr.io/distroless/java21`)** — smaller attack surface, but no shell
  breaks local `docker exec` debugging.
- **Ubuntu-based Temurin (`21-jre-jammy`)** — ~100 MB larger with no benefit for
  pure-Java workloads.
- **Single-stage Dockerfile** — ships Maven + JDK in runtime; quadruples image size.
- **Custom jlink runtime** — premature optimisation; standard JRE is small enough.

---

## References

- ADR-0008 — Deployment Topology (Compose orchestration)
- ADR-0009 — Configuration Management (env-var injection)
- ADR-0012 — Virtual Threads (why Java 21)
- ADR-0025 — Maven Build Topology
- ADR-0027 — Actuator Exposure (where `/actuator/health` comes from)
- JEP 379 — Container awareness — https://openjdk.org/jeps/379
