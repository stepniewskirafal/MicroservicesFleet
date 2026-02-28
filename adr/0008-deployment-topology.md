# 0008 — Deployment Topology

**Status:** Accepted — 2026-02-28
**Deciders:** Team
**Technical Story:** The assignment requires each service to run at least two instances and communicate via service discovery. A deployment model is needed that satisfies this constraint for local development and demos without requiring a Kubernetes cluster.

---

## Context and Problem Statement

The fleet consists of three microservices (Starport Registry, Trade Route Planner, Telemetry Pipeline), each required to run in at least two instances. The supporting infrastructure includes Eureka Server, PostgreSQL, Kafka (KRaft mode), and the observability stack (Prometheus, Grafana, Tempo, Loki, Zipkin). How should these components be deployed for local development and demo purposes while keeping the setup reproducible and lightweight?

---

## Decision Drivers

* Must satisfy the ≥2 instances per service requirement in the simplest possible way.
* Must be runnable on a developer laptop without cloud credentials or a Kubernetes cluster.
* Infrastructure-as-code: the entire environment must be reproducible with a single command.
* Service instances must register in Eureka and be routable via `lb://service-name`.
* The observability stack must co-exist in the same Compose network so Prometheus can scrape service actuators.
* Docker is already a prerequisite for Testcontainers, so it is a safe dependency.

---

## Considered Options

1. **Docker Compose with `--scale` flags** — each service image scaled to N replicas; Eureka handles discovery; single `docker compose up` command.
2. **Kubernetes (minikube / kind)** — full K8s with `Deployment` replicas and Service resources; platform-native discovery.
3. **Bare-metal / process-level scaling** — run multiple JVM processes on different ports on the host machine; manual configuration per instance.

---

## Decision Outcome

**Chosen option: Docker Compose with `--scale` for service replicas.**

Docker Compose is already required for Testcontainers and the observability stack. Scaling services to 2+ replicas with `docker compose up --scale starport-registry=2` is a one-line operation. Eureka handles instance registration and deregistration automatically. This approach requires zero additional tooling beyond Docker.

### Positive Consequences

* **Single command to start the full fleet.** `docker compose up` (optionally with `--scale`) launches all services and infrastructure.
* **Reproducible environments.** Compose files are version-controlled; any team member gets the same environment.
* **Eureka auto-registration.** Each container instance registers with its hostname and port; clients discover all live instances transparently.
* **Network isolation.** All containers share a Compose-defined bridge network; services address each other by container name, not host IP.
* **Infrastructure as code.** `docker-compose.yml` (in `starport-registry/docker/` and `infra/compose/`) declares all dependencies: PostgreSQL, Kafka, Eureka, Prometheus, Grafana, Tempo, Loki, Zipkin.
* **Port flexibility.** Scaling does not require manual port assignment; Docker assigns ephemeral host ports; Eureka stores the internal container port.
* **Fast iteration.** Individual service images can be rebuilt and replaced (`docker compose up --build starport-registry`) without restarting the entire stack.

### Negative Consequences

* **Not production-ready.** Docker Compose is a development/demo tool; production deployments require Kubernetes, ECS, or equivalent orchestration.
* **Resource contention on a laptop.** Two instances of each service plus five observability containers plus Kafka, PostgreSQL, and Eureka can exceed 8 GB RAM on modest machines.
* **No automatic restart / self-healing.** Docker Compose does not reschedule crashed containers the way Kubernetes does; manual `docker compose up` is required to recover.
* **Networking subtleties with Kafka.** Kafka must expose two listeners: `PLAINTEXT_HOST://localhost:9092` for host-side clients and `PLAINTEXT_DOCKER://kafka:9093` for container-to-container traffic (see `docker-compose.yml` environment variables).
* **Stateful volumes.** Prometheus and Tempo use named Docker volumes; a `docker compose down -v` is needed to reset state between full environment resets.
* **Scale limits the demo.** Compose scaling does not redistribute load across host machines; a single-machine scaling ceiling exists.

---

## Pros and Cons of the Options

### Option 1 — Docker Compose with `--scale` ✅

* Good, because zero extra tooling beyond Docker (already required).
* Good, because reproducible one-command setup.
* Good, because Eureka handles multi-instance discovery automatically.
* Good, because all infrastructure services (Kafka, PostgreSQL, observability) co-located in the same Compose network.
* Bad, because not production-grade; no self-healing or pod rescheduling.
* Bad, because resource-heavy on developer machines.

### Option 2 — Kubernetes (minikube / kind)

* Good, because production-closer topology; Health probes, resource limits, rolling updates available.
* Good, because `replicas: 2` in a Deployment is idiomatic K8s.
* Bad, because minikube / kind adds setup overhead (install, cluster start, `kubectl` familiarity).
* Bad, because K8s DNS replaces Eureka — changes the service discovery mechanism used elsewhere.
* Bad, because observability stack deployment in K8s requires Helm charts or custom manifests.
* Bad, because local resource usage is higher (minikube runs its own VM or nested containers).

### Option 3 — Bare-metal process scaling

* Good, because no containerisation overhead; fastest startup for individual services.
* Bad, because each instance requires manual port configuration, environment variable setup, and process management.
* Bad, because not reproducible; environment differs per developer machine.
* Bad, because no network isolation; all services share the host network.
* Bad, because Kafka and PostgreSQL still need to be installed or run separately.

---

## Implementation

### Infrastructure services (always started)

| Service | Image | Host Port |
|---|---|---|
| `eureka` | Built from `eureka-server/Dockerfile` | 8761 |
| `postgres` | `postgres:16-alpine` | 5432 |
| `kafka` | `apache/kafka:3.7.0` | 9092 (host), 9093 (docker) |
| `kafka-ui` | `provectuslabs/kafka-ui:latest` | 8080 |
| `prometheus` | `prom/prometheus:v2.54.1` | 9090 |
| `grafana` | `grafana/grafana:10.4.10` | 3000 |
| `tempo` | `grafana/tempo:2.4.2` | 3200, 4318 |
| `loki` | `grafana/loki:3.1.2` | 3100 |
| `zipkin` | `openzipkin/zipkin:2.24` | 9411 |

### Service scaling

```bash
# Start the full fleet with 2 instances of starport-registry
docker compose up --scale starport-registry=2
```

Each instance registers in Eureka with a unique `instance-id`:
```yaml
eureka.instance.instance-id: ${spring.application.name}:${HOSTNAME:unknown}:${server.port}
```

### Health checks

Eureka (`/actuator/health`) and PostgreSQL (`pg_isready`) have Docker health checks; dependent services use `condition: service_healthy` to prevent premature starts.

### Environment variable overrides

Service containers receive infrastructure addresses via environment variables:
```
DB_URL=jdbc:postgresql://postgres:5432/starports
KAFKA_BROKERS=kafka:9093
EUREKA_URL=http://eureka:8761/eureka
ZIPKIN_URL=http://zipkin:9411/api/v2/spans
```

---

## References

* ADR-0002 — Service Discovery Mechanism (Eureka)
* ADR-0005 — Observability Stack
* Docker Compose documentation — https://docs.docker.com/compose/
* Apache Kafka KRaft mode — https://kafka.apache.org/documentation/#kraft
