# Load Balancing & Service Discovery — MicroservicesFleet

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [Service Discovery — Eureka Server](#2-service-discovery--eureka-server)
3. [Load Balancing — Spring Cloud LoadBalancer](#3-load-balancing--spring-cloud-loadbalancer)
4. [Service Configuration](#4-service-configuration)
5. [Deployment Topology (Docker Compose)](#5-deployment-topology-docker-compose)
6. [End-to-End Request Flow](#6-end-to-end-request-flow)
7. [Code Implementation](#7-code-implementation)
8. [Metrics and Observability](#8-metrics-and-observability)
9. [Running and Verification](#9-running-and-verification)
10. [Architectural Decision Records (ADR)](#10-architectural-decision-records-adr)

---

## 1. Architecture Overview

MicroservicesFleet implements **client-side service discovery** and **client-side load balancing** using:

- **Netflix Eureka** — central service registry
- **Spring Cloud LoadBalancer** — client-side LB library built into Spring Cloud

```
┌─────────────────────────────────────────────────────────────────┐
│                        Docker Compose Network                    │
│                                                                  │
│  ┌────────────────┐        ┌──────────────────────────────────┐  │
│  │  Eureka Server │◄───────┤  Starport Registry (instance 1)  │  │
│  │  :8761         │◄───────┤  Starport Registry (instance 2)  │  │
│  │                │◄───────┤  Trade Route Planner (inst. 1)   │  │
│  │                │◄───────┤  Trade Route Planner (inst. 2)   │  │
│  └───────┬────────┘        └──────────────────────────────────┘  │
│          │ registry lookup                                        │
│          │                                                        │
│  ┌───────▼────────────────────────────────────────────────────┐  │
│  │  Starport Registry ──lb://trade-route-planner──► [inst.1]  │  │
│  │                                              └──► [inst.2]  │  │
│  └────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

Each service instance **self-registers** with Eureka on startup and sends heartbeats every few seconds. When `starport-registry` needs to call `trade-route-planner`, the `Spring Cloud LoadBalancer` library queries Eureka for the list of healthy instances and selects one of them using a round-robin algorithm.

---

## 2. Service Discovery — Eureka Server

### Role

Eureka Server acts as a **central catalog** (service registry). It stores:
- application names (`spring.application.name`)
- IP addresses and ports of instances
- health status (UP / DOWN / OUT_OF_SERVICE)
- metadata (version, zone, hostname)

### Server Configuration (`eureka-server/src/main/resources/application.yml`)

```yaml
server:
  port: 8761

spring:
  application:
    name: eureka-server

eureka:
  client:
    register-with-eureka: false   # the server does not register itself
    fetch-registry: false         # does not fetch its own registry
  server:
    enable-self-preservation: false          # disabled for fast failure detection
    eviction-interval-timer-in-ms: 5000      # check for inactive instances every 5s
    response-cache-update-interval-ms: 3000  # refresh response cache every 3s
    wait-time-in-ms-when-sync-empty: 0       # standalone mode — does not wait for peers

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
```

**Key settings:**

| Parameter | Value | Meaning |
|---|---|---|
| `enable-self-preservation` | `false` | Instances are quickly removed from the registry when they stop sending heartbeats |
| `eviction-interval-timer-in-ms` | `5000` | Eureka checks for dead instances every 5 seconds |
| `response-cache-update-interval-ms` | `3000` | Eureka response cache refreshed every 3 seconds — clients see new instances faster |
| `register-with-eureka` | `false` | The server does not register itself |

### Eureka Endpoints

| URL | Description |
|---|---|
| `http://localhost:8761` | Dashboard UI — list of registered services and instances |
| `http://localhost:8761/eureka/apps` | REST API — full list of applications (XML/JSON) |
| `http://localhost:8761/eureka/apps/STARPORT-REGISTRY` | Instances of a specific service |
| `http://localhost:8761/actuator/health` | Eureka server health check |
| `http://localhost:8761/actuator/prometheus` | Prometheus metrics |

---

## 3. Load Balancing — Spring Cloud LoadBalancer

### How It Works

`Spring Cloud LoadBalancer` is a **client-side** library. This means that the decision about which instance to choose is made **on the calling service side** (not by a central proxy):

```
Starport Registry                    Eureka Server
       │                                   │
       │──── fetch instances ─────────────►│
       │◄─── [inst1:8082, inst2:8083] ──────│
       │                                   │
       │  round-robin algorithm            │
       │  → selects: inst1:8082            │
       │                                   │
       │──── HTTP POST /routes/plan ──────► inst1:8082
```

### Algorithm

Default: **Round-Robin** — each subsequent request goes to the next instance in the list. The library is extensible — the strategy can be swapped, e.g., to `WeightedLoadBalancer` or `ZoneAwareLoadBalancer`.

### `lb://` URI Scheme

Instead of specifying a concrete address like `http://192.168.1.5:8082`, the service uses:

```
http://trade-route-planner/routes/plan
```

where the `lb://` prefix is handled by `@LoadBalanced RestTemplate` — the library automatically resolves the service name to an actual IP:port from Eureka.

In the `application.yml` configuration for starport-registry:

```yaml
app:
  trade-route-planner:
    base-url: http://trade-route-planner
```

The base URL contains neither a port nor an IP — Eureka + LoadBalancer provide them.

### Instance Cache

LoadBalancer maintains a local cache of the instance list fetched from Eureka. The cache is refreshed periodically. There is a brief window when the cache may contain an already-removed instance — this is handled by a retry mechanism (see: ADR-0010).

---

## 4. Service Configuration

### Starport Registry (`starport-registry/src/main/resources/application.yml`)

```yaml
spring:
  application:
    name: starport-registry       # name under which it registers in Eureka

  cloud:
    discovery:
      enabled: true               # enable service discovery

app:
  trade-route-planner:
    base-url: http://trade-route-planner   # lb:// resolved by @LoadBalanced

eureka:
  client:
    serviceUrl:
      defaultZone: ${EUREKA_URL:http://localhost:8761/eureka}
  instance:
    instance-id: ${spring.application.name}:${spring.cloud.client.hostname:${HOSTNAME:unknown}}:${server.port}
    prefer-ip-address: true       # register by IP instead of hostname
```

**`instance-id`** is unique for each instance — it contains the application name, container hostname, and port. Example values:
- `starport-registry:3a4b5c6d:8081`
- `starport-registry:9f8e7d6c:8084`

### Trade Route Planner (`trade-route-planner/src/main/resources/application.yml`)

```yaml
spring:
  application:
    name: trade-route-planner     # name visible in the Eureka registry

  cloud:
    discovery:
      enabled: true

eureka:
  client:
    serviceUrl:
      defaultZone: ${EUREKA_URL:http://localhost:8761/eureka}
  instance:
    instance-id: ${spring.application.name}:${spring.cloud.client.hostname:${HOSTNAME:unknown}}:${server.port}
    prefer-ip-address: true
```

---

## 5. Deployment Topology (Docker Compose)

The file `infra/compose/docker-compose.yml` runs **two instances** of each business service, which allows verifying load balancing behavior:

```
┌──────────────────────────────────────────────────────────────────┐
│                    docker-compose network                         │
│                                                                   │
│  eureka                  :8761  ← service registry               │
│                                                                   │
│  starport-registry-1     :8081  ┐                                │
│  starport-registry-2     :8084  ┘ ← 2 replicas (HA)             │
│                                                                   │
│  trade-route-planner-1   :8082  ┐                                │
│  trade-route-planner-2   :8083  ┘ ← 2 replicas (HA)             │
│                                                                   │
│  postgres                :5432  ← database (shared)              │
│  kafka                   :9092  ← event broker                   │
│  zipkin                  :9411  ← distributed tracing            │
│  prometheus              :9090  ← metrics                        │
└──────────────────────────────────────────────────────────────────┘
```

### Environment Variables Controlling Discovery

Each service instance receives the following via `environment`:

| Variable | Value | Description |
|---|---|---|
| `PORT` | `8081` / `8084` / `8082` / `8083` | HTTP server port |
| `EUREKA_URL` | `http://eureka:8761/eureka` | Eureka registry address in the Docker network |

### Startup Order (depends_on)

```yaml
starport-registry-1:
  depends_on:
    eureka:
      condition: service_healthy   # waits until Eureka responds on /actuator/health
    postgres:
      condition: service_healthy
```

Business services do not start until Eureka is ready — this eliminates the problem of registration before the server is up.

---

## 6. End-to-End Request Flow

### Instance Registration at Startup

```
1. Container starport-registry-1 starts (PORT=8081)
2. Spring Boot loads the Eureka client configuration
3. After context initialization: POST http://eureka:8761/eureka/apps/STARPORT-REGISTRY
   Body: { "hostName": "3a4b5c", "ipAddr": "172.18.0.5", "port": 8081, "status": "UP" }
4. Eureka adds the instance to the registry
5. Every ~30s: PUT http://eureka:8761/eureka/apps/STARPORT-REGISTRY/starport-registry:3a4b5c:8081
   (heartbeat — lease renewal)
```

### Request with Load Balancing

```
1. HTTP client sends POST /starports/1/reservations
   → reaches starport-registry-1:8081 or starport-registry-2:8084

2. ReservationService needs to plan a route
   → calls RoutePlannerClient with URL: http://trade-route-planner/routes/plan

3. @LoadBalanced RestTemplate intercepts the request
   → queries the local instance cache for "trade-route-planner"
   → if cache is empty/expired: GET http://eureka:8761/eureka/apps/TRADE-ROUTE-PLANNER
   → receives: [{ ip: 172.18.0.7, port: 8082 }, { ip: 172.18.0.8, port: 8083 }]

4. Round-robin selects an instance (e.g., trade-route-planner-1:8082)
   → POST http://172.18.0.7:8082/routes/plan

5. Response returns to starport-registry
   → ReservationService continues processing
```

### Instance Removal (Failover)

```
1. trade-route-planner-2:8083 stops responding
2. Eureka does not receive a heartbeat for ~90s (or 5s with self-preservation disabled)
3. Eureka removes the instance from the registry
4. Next request from Starport Registry: LoadBalancer fetches a fresh list
5. The list contains only trade-route-planner-1:8082
6. All requests are directed to the working instance — no changes in client code
```

---

## 7. Code Implementation

### `@LoadBalanced RestTemplate` Bean

**File:** `starport-registry/src/main/java/com/galactic/starport/config/RestClientConfig.java`

```java
@Configuration
class RestClientConfig {

    @Bean("tradeRoutePlannerRestTemplate")
    @LoadBalanced                          // ← key annotation
    @ConditionalOnProperty(
        name = "spring.cloud.discovery.enabled",
        havingValue = "true",
        matchIfMissing = true
    )
    RestTemplate loadBalancedRestTemplate() {
        return new RestTemplate();
    }

    @Bean("tradeRoutePlannerRestTemplate")
    @ConditionalOnProperty(
        name = "spring.cloud.discovery.enabled",
        havingValue = "false"
    )
    RestTemplate plainRestTemplate() {
        return new RestTemplate();         // fallback for tests without Eureka
    }

    @Bean
    RestClient tradeRoutePlannerRestClient(
            @Value("${app.trade-route-planner.base-url}") String baseUrl,
            @Qualifier("tradeRoutePlannerRestTemplate") RestTemplate restTemplate) {
        return RestClient.builder(restTemplate)
                .baseUrl(baseUrl)          // http://trade-route-planner (lb://)
                .build();
    }
}
```

**How it works:**

1. `@LoadBalanced` registers the `RestTemplate` as intercepted by `LoadBalancerInterceptor`
2. Every outgoing request is intercepted by the interceptor
3. The interceptor resolves the hostname (`trade-route-planner`) via `DiscoveryClient` (Eureka)
4. It replaces the name with the actual IP:port of the selected instance
5. It forwards the modified request to the HTTP server

### Conditionality (`@ConditionalOnProperty`)

The configuration supports two modes:
- **`spring.cloud.discovery.enabled=true`** (default): uses `@LoadBalanced RestTemplate` — Eureka resolves addresses
- **`spring.cloud.discovery.enabled=false`**: uses a plain `RestTemplate` — useful in integration tests with WireMock

### Architectural Verification (ArchUnit)

The project contains `ArchUnit` tests verifying that:
- Infrastructure layer classes can call external services
- Domain classes **do not** import Spring Cloud classes (layer separation)

---

## 8. Metrics and Observability

### Eureka Metrics

Available via `/actuator/prometheus` on port `8761`:

| Metric | Description |
|---|---|
| `eureka_server_registry_size` | Number of registered instances |
| `eureka_server_renewals_per_minute` | Heartbeat frequency |
| `eureka_server_evictions_total` | Total number of evicted instances |

### Client Metrics (Starport Registry)

| Metric | Description |
|---|---|
| `spring.cloud.loadbalancer.requests.total` | Total number of LB requests |
| `starport.external.route.time` | Latency of calls to Trade Route Planner |
| `http.client.requests` | HTTP request histogram (Spring Actuator) |

SLO configuration for route planning time:

```yaml
management:
  metrics:
    distribution:
      percentiles-histogram:
        starport.external.route.time: true
      slo:
        starport.external.route.time: 20ms,50ms,100ms,200ms,500ms,1s,2s
```

### Distributed Tracing

Every request going through the load balancer includes trace headers:
- `X-B3-TraceId` — trace identifier (propagated across all services)
- `X-B3-SpanId` — current span identifier
- `X-B3-ParentSpanId` — calling span

In Zipkin (`http://localhost:9411`) the complete trace is visible:

```
[Client] → [starport-registry-1:8081] → [trade-route-planner-1:8082]
              ▲ span: reservation         ▲ span: route-planning
              └─────────── trace ─────────┘
```

---

## 9. Running and Verification

### Starting the Full Stack

```bash
cd infra/compose
docker compose up --build
```

Startup order guaranteed by `depends_on` + `healthcheck`:
1. `eureka` (:8761)
2. `postgres`, `kafka`, `zipkin`, `prometheus` (in parallel)
3. `starport-registry-1`, `starport-registry-2` (after Eureka and Postgres are healthy)
4. `trade-route-planner-1`, `trade-route-planner-2` (after Eureka is healthy)

### Verifying Service Registration

```bash
# Eureka Dashboard — list of registered instances
open http://localhost:8761

# REST API — all applications in JSON format
curl -H "Accept: application/json" http://localhost:8761/eureka/apps | jq .

# Instances of a specific service
curl -H "Accept: application/json" \
  http://localhost:8761/eureka/apps/TRADE-ROUTE-PLANNER | jq .

# Expected result: 2 instances with status UP
```

### Verifying Load Balancing

```bash
# Sending several requests to Starport Registry
for i in {1..6}; do
  curl -s -o /dev/null -w "%{http_code}\n" \
    -X POST http://localhost:8081/starports/1/reservations \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: test-$i" \
    -d '{"shipId":1,"customerId":1,"duration":2}'
done

# Checking logs of both Trade Route Planner instances
docker compose logs trade-route-planner-1 | grep "route"
docker compose logs trade-route-planner-2 | grep "route"
# Both should have requests — round-robin is active
```

### Verifying Failover

```bash
# Stopping one Trade Route Planner instance
docker compose stop trade-route-planner-2

# Wait ~10s for eviction in Eureka (enable-self-preservation: false)
sleep 10

# Check the registry — there should be 1 instance with status UP
curl -H "Accept: application/json" \
  http://localhost:8761/eureka/apps/TRADE-ROUTE-PLANNER | jq '.application.instance | length'
# Result: 1

# Requests still work — directed to trade-route-planner-1
curl -X POST http://localhost:8081/starports/1/reservations ...
# HTTP 200/201 — automatic failover
```

### Health Checks

```bash
# Eureka Server
curl http://localhost:8761/actuator/health

# Starport Registry instances
curl http://localhost:8081/actuator/health
curl http://localhost:8084/actuator/health

# Trade Route Planner instances
curl http://localhost:8082/actuator/health
curl http://localhost:8083/actuator/health
```

---

## 10. Architectural Decision Records (ADR)

### ADR-0002 — Service Discovery (Eureka)

**Decision:** Netflix Eureka as the central service registry.

**Rationale:**
- Native integration with Spring Boot 3.x and Spring Cloud
- Zero additional infrastructure beyond the already-used stack
- Client-side load balancing without an external proxy
- Rich instance metadata (version, zone, hostname)
- Good support for local development (Docker Compose)

**Considered alternatives:**

| Option | Reason for Rejection |
|---|---|
| **Consul** | Greater operational overhead for a Spring-only project |
| **Kubernetes Service DNS** | Does not fit Docker Compose (target environment) |
| **NGINX/Envoy (server-side)** | Centralization = SPOF; overkill for the current scope |

**Risks and mitigations:**
- *Eventual consistency of the registry* → short heartbeat TTL + self-preservation disabled
- *Stale cache on the client* → retry on a different instance upon connection error
- *Security* → TLS + auth for production environments

**File:** `adr/0002-service-discovery-choice.md`

---

### ADR-0003 — HTTP Load Balancing (Spring Cloud LoadBalancer)

**Decision:** Client-side load balancing via `Spring Cloud LoadBalancer` with `lb://service-name` addressing.

**Rationale:**
- The only option that integrates directly with the Eureka registry
- Zero additional infrastructure (no NGINX/Envoy/Istio)
- Clean URI syntax: `lb://trade-route-planner`
- Each service manages its own resilience (no SPOF)
- Automatic Micrometer instrumentation

**Considered alternatives:**

| Option | Reason for Rejection |
|---|---|
| **NGINX/Envoy (server-side proxy)** | Additional component, potential SPOF, no integration with Eureka |
| **Kubernetes kube-proxy (L4)** | Does not fit Docker Compose; lacks health-check granularity |

**Implementation:**
- `@LoadBalanced` annotation on the `RestTemplate` bean
- Base URL: `http://trade-route-planner` (no port/IP)
- `@ConditionalOnProperty` — fallback for tests without Eureka
- Strategy: Round-Robin (default, extensible)

**File:** `adr/0003-http-load-balancing.md`

---

## Summary

| Aspect | Solution |
|---|---|
| **Service Registry** | Netflix Eureka Server (`:8761`) |
| **Registration Protocol** | Eureka REST API (heartbeat every ~30s) |
| **Load Balancing** | Spring Cloud LoadBalancer (client-side) |
| **LB Algorithm** | Round-Robin (default, pluggable) |
| **Addressing** | `lb://service-name` → resolved by Eureka |
| **Instances per Service** | 2 (HA and LB verification) |
| **Failover** | Automatic — dead instance removed from registry every 5s |
| **Metrics** | Micrometer → Prometheus → Grafana |
| **Tracing** | Zipkin (trace propagated across all instances) |
| **Environment** | Docker Compose (local), Spring Cloud 2025.0.0, Java 21 |
