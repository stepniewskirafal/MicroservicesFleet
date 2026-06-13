# 0009 — Configuration Management

**Status:** Accepted
**Date:** 2026-02-28

---

## Context

Services must run in local dev, Docker Compose, and CI with the same artifact but
different infrastructure addresses and tunables. Secrets must not be committed.
The fleet is small (three services) and there is no Kubernetes cluster (ADR-0008),
so heavy config infrastructure is not justified.

---

## Decision

Use **Spring Boot externalized configuration** with `${ENV_VAR:default}` substitution
in `application.yml`, plus Spring profiles (`test`, `docker`) for environment-specific
overrides. Defaults target local dev; Docker Compose injects container addresses via
`environment:` blocks; Testcontainers uses `@ServiceConnection`.

```yaml
spring:
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/starports}
    password: ${DB_PASS:postgres}
  cloud.stream.kafka.binder.brokers: ${KAFKA_BROKERS:localhost:9092}
eureka.client.serviceUrl.defaultZone: ${EUREKA_URL:http://localhost:8761/eureka}
telemetry.adapter: ${TELEMETRY_ADAPTER:stream}   # stream | kafka — feature switch <!-- TODO: verify against code (not found in any application.yml) -->
```

Secrets (`DB_PASS`, broker credentials) only ever arrive as env vars; `.env` files
must be `.gitignore`d.

---

## Why

- **Zero extra infrastructure.** No config server or Vault to run, version, monitor.
- **Spring-native.** `@Value`, `@ConfigurationProperties`, profile YAMLs — no new libs.
- **Self-documenting defaults.** Every overridable property has its local-dev default
  visible in the committed file.
- **Works identically everywhere.** Same `application.yml` in local dev, Compose, CI.

---

## Alternatives

- **Spring Cloud Config Server** — centralised + Git-backed, but adds a startup-time
  dependency for three services; revisit at ~5+ services.
- **HashiCorp Vault** — proper secret rotation, but premature operational complexity.
- **K8s ConfigMaps/Secrets** — requires K8s, which ADR-0008 explicitly does not use.

---

## References

- ADR-0008 — Deployment Topology (Docker Compose)
- Spring Boot Externalized Configuration — https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.external-config
- 12-Factor App — Config — https://12factor.net/config
