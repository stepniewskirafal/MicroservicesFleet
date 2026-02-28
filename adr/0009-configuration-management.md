# 0009 — Configuration Management

**Status:** Accepted — 2026-02-28
**Deciders:** Team
**Technical Story:** Services must be configurable across multiple environments (local dev, Docker Compose, CI) without rebuilding images. Infrastructure addresses, polling intervals, and feature switches must be externalised.

---

## Context and Problem Statement

Starport Registry connects to PostgreSQL, Kafka, Eureka, Zipkin, and Tempo. Each environment (developer laptop, Docker Compose, CI Testcontainers) uses different addresses and may require different tunables (polling interval, batch size, sampling probability). How should configuration be managed so that the same artifact runs in all environments without code changes or image rebuilds?

---

## Decision Drivers

* Spring Boot's externalized configuration with environment variable substitution is a first-class feature — it should be used before introducing additional infrastructure.
* The project must run locally without any external configuration server; developers must be able to start the service with `java -jar` or `./mvnw spring-boot:run` and override only what differs.
* Docker Compose passes environment variables to containers; Testcontainers uses `@ServiceConnection` and auto-configuration — no extra config server is needed.
* Secrets (DB password, Kafka credentials) must not be hard-coded in committed source files.
* Spring profiles (`test`, `docker`) allow environment-specific config blocks without duplicating full `application.yml` files.

---

## Considered Options

1. **Environment variable overrides in `application.yml`** — `${ENV_VAR:default}` syntax; profiles for environment-specific overrides. No additional infrastructure.
2. **Spring Cloud Config Server** — centralised configuration server backed by a Git repo; services fetch config at startup.
3. **HashiCorp Vault** — secrets management with dynamic credential rotation; services authenticate and fetch secrets at startup.
4. **Kubernetes ConfigMaps and Secrets** — K8s-native; works when deployed to K8s but requires `kubectl` / Helm for local management.

---

## Decision Outcome

**Chosen option: Environment variable overrides in `application.yml` with Spring profiles.**

The project is at an early stage; the complexity of a Config Server or Vault is not justified. `${ENV_VAR:default}` covers all current requirements: each infrastructure address has a sensible local default and can be overridden per environment. Profiles (`test`, `docker`) handle environment-specific overrides without duplicating the full configuration file.

### Positive Consequences

* **Zero additional infrastructure.** No config server to run, version, or monitor.
* **Spring Boot native.** `@Value`, `@ConfigurationProperties`, and `${var:default}` are first-class Spring features; no extra libraries required.
* **Works in all environments.** Local dev: defaults in `application.yml`. Docker Compose: `environment:` block in `docker-compose.yml`. CI: `spring.profiles.active=test` activates Testcontainers auto-config. Production: environment variables injected by the orchestrator.
* **Explicit defaults.** Every property that differs per environment has a local-dev default visible in the committed file — useful for onboarding and documentation.
* **Secrets discipline.** Credentials (`DB_PASS`, broker passwords) are passed as environment variables; they never appear in committed YAML files.
* **Feature switches.** The `telemetry.adapter` switch (`stream | kafka`) demonstrates how feature flags can be managed without code changes.

### Negative Consequences

* **No centralised config audit trail.** Config changes are scattered across Compose files, CI environment blocks, and application.yml — hard to audit across all environments at once.
* **Secret rotation is manual.** There is no dynamic credential rotation; a password change requires restarting the service with a new environment variable.
* **Config drift risk.** If the same property is defined in multiple places (profile YAML, Compose env, CI env), precedence rules must be understood to debug unexpected values.
* **Scaling to many services is harder.** As the fleet grows beyond three services, duplicated infrastructure coordinates (Kafka broker address, Eureka URL) become maintenance overhead. A Config Server becomes more attractive at ~5+ services.
* **No encryption at rest for property files.** Secrets in `.env` files or Compose overrides are plain text; developers must ensure `.env` files are in `.gitignore`.

---

## Pros and Cons of the Options

### Option 1 — Env var overrides in `application.yml` ✅

* Good, because zero infrastructure; works immediately.
* Good, because defaults are self-documenting and committed alongside the code.
* Good, because Docker Compose and Testcontainers integrate naturally.
* Bad, because no centralised audit or secret rotation.
* Bad, because config drift grows as the fleet scales.

### Option 2 — Spring Cloud Config Server

* Good, because centralised: all services fetch config from one place.
* Good, because Git-backed: config changes are versioned and auditable.
* Bad, because adds another service to run locally (Config Server + its backing Git repo or filesystem).
* Bad, because introduces a startup-time dependency: services fail if Config Server is unavailable.
* Bad, because overkill for three services with minimal environment-specific properties.

### Option 3 — HashiCorp Vault

* Good, because dynamic secrets, automatic rotation, fine-grained access control.
* Good, because secrets are never stored in environment variables or files.
* Bad, because significant operational complexity — Vault must be initialised, unsealed, and monitored.
* Bad, because local setup (dev mode Vault) requires understanding of Vault concepts.
* Bad, because far exceeds current requirements; premature investment.

### Option 4 — Kubernetes ConfigMaps and Secrets

* Good, because native to K8s; `kubectl apply` to update; Secrets are base64-encoded and access-controlled.
* Bad, because requires K8s — not the chosen deployment topology (Docker Compose, ADR-0008).
* Bad, because developers without `kubectl` cannot manage config locally.
* Bad, because Secrets are only base64-encoded (not encrypted) by default without etcd encryption-at-rest.

---

## Implementation

### Key environment variables and their defaults

| Variable | Default | Used by |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/starports` | JPA datasource |
| `DB_USER` | `postgres` | JPA datasource |
| `DB_PASS` | `postgres` | JPA datasource |
| `KAFKA_BROKERS` | `localhost:9092` | Spring Cloud Stream binder |
| `EUREKA_URL` | `http://localhost:8761/eureka` | Eureka client |
| `ZIPKIN_URL` | `http://localhost:9411/api/v2/spans` | Micrometer Zipkin reporter |
| `PORT` | `8081` | Embedded Tomcat port |

### Spring profiles

* `test` — activated in Surefire/Failsafe; `@ServiceConnection` provides Testcontainers auto-config; Eureka client disabled.
* `docker` (planned) — overrides all `localhost` defaults with Docker Compose service names.

### Docker Compose injection

```yaml
services:
  starport-registry:
    environment:
      DB_URL: jdbc:postgresql://postgres:5432/starports
      KAFKA_BROKERS: kafka:9093
      EUREKA_URL: http://eureka:8761/eureka
      ZIPKIN_URL: http://zipkin:9411/api/v2/spans
```

### Feature switch

```yaml
telemetry:
  adapter: stream   # stream | kafka — controls which OutboxFacade implementation is active
```

---

## References

* ADR-0008 — Deployment Topology (Docker Compose)
* Spring Boot Externalized Configuration — https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.external-config
* Spring Cloud Config — https://docs.spring.io/spring-cloud-config/docs/current/reference/html/
* The Twelve-Factor App — Config — https://12factor.net/config
