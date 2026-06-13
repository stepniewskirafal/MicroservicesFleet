# 0008 — Deployment Topology

**Status:** Accepted
**Date:** 2026-02-28

---

## Context

The assignment requires each of the three microservices to run with at least two
instances and to communicate via service discovery. The fleet also needs Eureka,
PostgreSQL, Kafka (KRaft), and the observability stack — all on a developer laptop,
without a Kubernetes cluster.

---

## Decision

Deploy via **Docker Compose**, scaling services with `--scale`. Eureka handles
discovery; the Compose bridge network lets containers address each other by name.
Docker is already a prerequisite (Testcontainers), so no new tooling is added.

```bash
docker compose up --scale starport-registry=2
```

Each replica registers a unique Eureka instance-id:

```yaml
eureka.instance.instance-id: ${spring.application.name}:${HOSTNAME:unknown}:${server.port}
```

Single ingress: only the API gateway publishes a host port (`8080:8080`); all app
replicas use `expose:` only (internal: starport-registry 8081, trade-route-planner
8082, telemetry-pipeline 8090) and are reached via the gateway (→ ADR-0031).

Eureka and PostgreSQL expose Docker health checks; dependents use
`condition: service_healthy`. Kafka advertises two listeners
(`PLAINTEXT_HOST://localhost:9092`, `PLAINTEXT_DOCKER://kafka:9093`) so host clients
and containers can both reach it.

---

## Why

- **One command, reproducible.** `docker compose up` brings up the whole fleet plus
  observability — same on every machine.
- **Satisfies ≥2 instances trivially.** `--scale name=N` + Eureka auto-registration.
- **Zero extra tooling.** No kubectl, no minikube VM, no Helm charts.
- **Fast iteration.** Rebuild one service (`--build starport-registry`) without
  restarting the rest of the stack.

---

## Alternatives

- **Kubernetes (minikube / kind)** — production-closer but adds VM/cluster overhead
  and replaces Eureka with K8s DNS.
- **Bare-metal process scaling** — manual ports, no isolation, not reproducible across
  developer machines.

---

## References

- ADR-0002 — Service Discovery Mechanism (Eureka)
- ADR-0005 — Observability Stack
- ADR-0031 — API Gateway (single ingress)
- Docker Compose — https://docs.docker.com/compose/
- Kafka KRaft — https://kafka.apache.org/documentation/#kraft
