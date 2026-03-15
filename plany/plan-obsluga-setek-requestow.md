# Plan: Handling hundreds of concurrent reservation requests

## Current state (bottleneck analysis)

| Component | Current value | Problem |
|-----------|---------------|---------|
| HikariCP pool | **10 connections** (default) | 200 Tomcat threads competing for 10 DB connections |
| Tomcat threads | **200** (default) | 190 threads waiting idle for connection pool |
| Indexes on `reservation` | **none** | `NOT EXISTS` in `findFreeBay` scans the entire table |
| Outbox polling | **1 thread / 30s / batch 50** | Event delay up to 60s |
| Health-check interval | **15s** | Failover takes up to 15s |
| Rate limiting | **none** | No protection against overload |
| Circuit breaker | **none** | Slow trade-route-planner blocks threads |

**Bottleneck #1**: 10 DB connections vs 200 HTTP threads.
With 100+ concurrent requests, 90% of threads wait for a connection — response time grows exponentially.

**Bottleneck #2**: No indexes on the `reservation` table.
The `findFreeBay` query with `NOT EXISTS` performs a full table scan on `reservation(docking_bay_id, start_at, end_at)`.

---

## Change plan — step by step

### Step 1: Indexes on the `reservation` table (critical)

**File:** new migration `V4__reservation_indexes.sql`

```sql
CREATE INDEX idx_reservation_bay_time
    ON reservation (docking_bay_id, start_at, end_at);
```

**Why:** The `findFreeBay` query uses `NOT EXISTS (SELECT 1 FROM reservation WHERE docking_bay_id = ? AND start_at < ? AND end_at > ?)`. Without an index, each call scans ALL reservations. With an index — index-only scan O(log n).

**Impact:** The single biggest performance gain. With 10,000 reservations the difference is 100-1000x.

---

### Step 2: HikariCP connection pool tuning

**File:** `application.yml`

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 30          # from 10 → 30
      minimum-idle: 10               # keep 10 idle connections
      connection-timeout: 5000       # 5s instead of 30s — fail fast
      leak-detection-threshold: 10000  # detect connection leaks >10s
```

**Rule:** `pool_size ≈ (2 × CPU cores) + disk_spindles`.
For 4 CPU + SSD: ~30 connections per instance. Two instances = 60 connections to PostgreSQL — well below the limit (default: 100).

**Why 30, not 200?**
More connections ≠ more throughput. PostgreSQL slows down at >50 active connections (context switching). The connection pool acts as a buffer — threads wait briefly, but the DB is not overloaded.

---

### Step 3: Tomcat tuning — reducing threads to match pool ratio

**File:** `application.yml`

```yaml
server:
  tomcat:
    threads:
      max: 60        # from 200 → 60 (2x pool size)
      min-spare: 15
    accept-count: 200  # queue for excess requests
    max-connections: 2000
```

**Why reduce threads?**
60 threads with 30 DB connections means at most 30 waiting — an acceptable queue. 200 threads with 30 connections = 170 waiting = OOM and thread starvation.

**accept-count: 200** — OS-level queue; requests are not rejected immediately, they are queued at the TCP level.

---

### Step 4: Index on `docking_bay` for faster join

**File:** migration `V4__reservation_indexes.sql` (same file)

```sql
CREATE INDEX idx_docking_bay_starport_class
    ON docking_bay (starport_id, ship_class, status);
```

**Why:** The `findFreeBay` query joins `starport → docking_bay` on `starport_id` and filters by `ship_class` and `status`. A compound index covers the entire WHERE clause.

---

### Step 5: Circuit breaker for trade-route-planner calls

**Library:** `resilience4j-spring-boot3` (already in the Spring Cloud ecosystem)

**File:** new `ResilienceConfig.java` + changes to `application.yml`

```yaml
resilience4j:
  circuitbreaker:
    instances:
      trade-route-planner:
        failure-rate-threshold: 50        # open after 50% failures
        wait-duration-in-open-state: 10s  # retry after 10s
        sliding-window-size: 10           # window of 10 requests
        permitted-number-of-calls-in-half-open-state: 3
  timelimiter:
    instances:
      trade-route-planner:
        timeout-duration: 2s
```

**Usage in `TradeRoutePlannerHttpAdapter`:**
```java
@CircuitBreaker(name = "trade-route-planner", fallbackMethod = "routeUnavailable")
public RoutePlan planRoute(String origin, String destination, ShipClass shipClass) { ... }
```

**Why:** Without a circuit breaker, a slow/crashed trade-route-planner blocks starport-registry threads for 2s (read timeout) × hundreds of requests = thread exhaustion. With CB — after 5 failures the circuit opens and immediately returns a fallback.

---

### Step 6: Increasing outbox polling throughput

**File:** `application.yml`

```yaml
app:
  poll-interval-ms: 5000   # from 30s → 5s
  batch-size: 200           # from 50 → 200

spring:
  task:
    scheduling:
      pool:
        size: 2             # from 1 → 2 scheduler threads
```

**Why:**
Current throughput: 50 events / 30s ≈ 1.6 evt/s.
After change: 200 events / 5s ≈ 40 evt/s — 25x improvement.

---

### Step 7: Health-check interval — faster failover

**File:** `application.yml`

```yaml
spring:
  cloud:
    loadbalancer:
      health-check:
        interval: 5s   # from 15s → 5s
```

**Why:** Failover from 15s → 5s. With two instances, if one goes down it causes 5s of unavailability instead of 15s.

---

### Step 8: Bottleneck monitoring — new metrics

**File:** new `ConnectionPoolMetricsConfig.java`

HikariCP automatically exposes metrics to Micrometer:
- `hikaricp_connections_active` — how many connections are in use
- `hikaricp_connections_pending` — how many threads are waiting for a connection
- `hikaricp_connections_timeout_total` — how many timeouts occurred

**Add to the Grafana dashboard:**
```promql
# Alert: pending connections > 50% pool size
hikaricp_connections_pending{pool="HikariPool-1"} > 15
```

**application.yml:**
```yaml
management:
  metrics:
    tags:
      application: starport-registry
```

---

### Step 9: PostgreSQL — database-side tuning

**File:** `docker-compose.yml` — PostgreSQL command/config

```yaml
postgres:
  command: >
    postgres
    -c max_connections=200
    -c shared_buffers=256MB
    -c work_mem=4MB
    -c effective_cache_size=512MB
    -c random_page_cost=1.1
```

**Why:**
- `max_connections=200` — 2 instances × 30 pool + outbox + monitoring = ~80. Default 100 is enough, but 200 provides headroom.
- `shared_buffers=256MB` — cache for frequently queried tables.
- `random_page_cost=1.1` — SSD; encourages the planner to use indexes.

---

### Step 10: (Optional) Rate limiting with Bucket4j

**When to implement:** When the system is publicly accessible or protection against DDoS/abuse is needed.

**Library:** `bucket4j-spring-boot-starter`

```yaml
bucket4j:
  filters:
    - cache-name: rate-limit
      url: /api/v1/starports/.*/reservations
      rate-limits:
        - bandwidths:
            - capacity: 100
              time: 1
              unit: seconds
```

**Effect:** Max 100 req/s per endpoint. Excess requests receive HTTP 429 Too Many Requests.

---

## Implementation order (priority)

| Priority | Step | Time | Impact |
|----------|------|------|--------|
| **P0** | 1. Indexes on `reservation` | 15 min | Huge — eliminates full table scan |
| **P0** | 2. HikariCP pool 10→30 | 5 min | Large — 3x more concurrent transactions |
| **P1** | 3. Tomcat threads 200→60 | 5 min | Medium — eliminates thread starvation |
| **P1** | 4. Index on `docking_bay` | 5 min | Medium — faster join in findFreeBay |
| **P1** | 5. Circuit breaker | 1h | Large — protection against cascading failure |
| **P2** | 6. Outbox polling tuning | 5 min | Medium — 25x faster event publishing |
| **P2** | 7. Health-check 15s→5s | 2 min | Small — faster failover |
| **P2** | 8. Connection pool monitoring | 30 min | Medium — bottleneck visibility |
| **P3** | 9. PostgreSQL tuning | 10 min | Small — safety margin |
| **P3** | 10. Rate limiting | 1h | Small — protection, not performance |

---

## Expected outcome

| Metric | Before | After |
|--------|--------|-------|
| Max concurrent reservations | ~10/s | **~100-200/s** |
| Avg response time (p50) | ~200ms | **~30-50ms** |
| p99 response time | ~5s (connection wait) | **~200ms** |
| Failover time | 15s | **5s** |
| Outbox event latency | up to 60s | **up to 5s** |
| findFreeBay query time (10k reservations) | ~50ms (full scan) | **~1ms (index scan)** |

---

## What we are NOT doing (and why)

1. **Reactive stack (WebFlux)** — requires rewriting the entire application; ROI too low for the current scale.
2. **CQRS / Event Sourcing** — overengineering; the current architecture with the outbox pattern is sufficient.
3. **Redis cache for bay availability** — `FOR UPDATE SKIP LOCKED` is already optimal; a cache would introduce consistency issues.
4. **Database sharding** — at hundreds of req/s, a single-node PostgreSQL with indexes can handle the load.
5. **Kafka partitioning tuning** — the bottleneck is on the producer side (outbox polling), not the consumer.
