# Plan: Obsługa setek równoczesnych requestów rezerwacji

## Stan obecny (analiza bottlenecków)

| Komponent | Obecna wartość | Problem |
|-----------|---------------|---------|
| HikariCP pool | **10 połączeń** (default) | 200 wątków Tomcat walczy o 10 połączeń DB |
| Tomcat threads | **200** (default) | 190 wątków czeka bezczynnie na connection pool |
| Indeksy na `reservation` | **brak** | `NOT EXISTS` w `findFreeBay` skanuje całą tabelę |
| Outbox polling | **1 wątek / 30s / batch 50** | Opóźnienie eventów do 60s |
| Health-check interval | **15s** | Failover trwa do 15s |
| Rate limiting | **brak** | Brak ochrony przed przeciążeniem |
| Circuit breaker | **brak** | Wolny trade-route-planner blokuje wątki |

**Wąskie gardło nr 1**: 10 DB connections vs 200 HTTP wątków.
Przy 100+ równoczesnych requestach, 90% wątków czeka na connection — response time rośnie wykładniczo.

**Wąskie gardło nr 2**: Brak indeksów na tabeli `reservation`.
Query `findFreeBay` z `NOT EXISTS` wykonuje full table scan po `reservation(docking_bay_id, start_at, end_at)`.

---

## Plan zmian — krok po kroku

### Krok 1: Indeksy na tabeli `reservation` (krytyczne)

**Plik:** nowa migracja `V4__reservation_indexes.sql`

```sql
CREATE INDEX idx_reservation_bay_time
    ON reservation (docking_bay_id, start_at, end_at);
```

**Dlaczego:** Query `findFreeBay` używa `NOT EXISTS (SELECT 1 FROM reservation WHERE docking_bay_id = ? AND start_at < ? AND end_at > ?)`. Bez indeksu każde wywołanie skanuje WSZYSTKIE rezerwacje. Z indeksem — index-only scan O(log n).

**Wpływ:** Największy pojedynczy zysk wydajnościowy. Przy 10 000 rezerwacji różnica 100-1000x.

---

### Krok 2: Tuning connection pool HikariCP

**Plik:** `application.yml`

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 30          # z 10 → 30
      minimum-idle: 10               # utrzymuj 10 idle connections
      connection-timeout: 5000       # 5s zamiast 30s — fail fast
      leak-detection-threshold: 10000  # wykryj connection leaki >10s
```

**Reguła:** `pool_size ≈ (2 × CPU cores) + disk_spindles`.
Dla 4 CPU + SSD: ~30 connections na instancję. Dwie instancje = 60 połączeń do PostgreSQL — daleko od limitu (default: 100).

**Dlaczego 30, a nie 200?**
Więcej connections ≠ więcej throughput. PostgreSQL zwalnia przy >50 aktywnych connections (context switching). Connection pool działa jak bufor — wątki czekają krótko, ale DB nie jest przeciążona.

---

### Krok 3: Tuning Tomcat — zmniejszenie wątków do proporcji pool

**Plik:** `application.yml`

```yaml
server:
  tomcat:
    threads:
      max: 60        # z 200 → 60 (2x pool size)
      min-spare: 15
    accept-count: 200  # queue dla nadmiarowych requestów
    max-connections: 2000
```

**Dlaczego zmniejszamy wątki?**
60 wątków z 30 DB connections oznacza max 30 czekających — akceptowalny queue. 200 wątków z 30 connections = 170 czekających = OOM i thread starvation.

**accept-count: 200** — OS-level queue; requestów nie odrzucamy od razu, kolejkujemy na poziomie TCP.

---

### Krok 4: Indeks na `docking_bay` dla szybszego join

**Plik:** migracja `V4__reservation_indexes.sql` (ten sam plik)

```sql
CREATE INDEX idx_docking_bay_starport_class
    ON docking_bay (starport_id, ship_class, status);
```

**Dlaczego:** Query `findFreeBay` joinuje `starport → docking_bay` po `starport_id` + filtruje po `ship_class` i `status`. Compound index pokrywa cały WHERE.

---

### Krok 5: Circuit breaker na wywołanie trade-route-planner

**Biblioteka:** `resilience4j-spring-boot3` (już w ekosystemie Spring Cloud)

**Plik:** nowy `ResilienceConfig.java` + zmiana `application.yml`

```yaml
resilience4j:
  circuitbreaker:
    instances:
      trade-route-planner:
        failure-rate-threshold: 50        # otwórz po 50% błędów
        wait-duration-in-open-state: 10s  # próbuj ponownie po 10s
        sliding-window-size: 10           # okno 10 requestów
        permitted-number-of-calls-in-half-open-state: 3
  timelimiter:
    instances:
      trade-route-planner:
        timeout-duration: 2s
```

**Zastosowanie w `TradeRoutePlannerHttpAdapter`:**
```java
@CircuitBreaker(name = "trade-route-planner", fallbackMethod = "routeUnavailable")
public RoutePlan planRoute(String origin, String destination, ShipClass shipClass) { ... }
```

**Dlaczego:** Bez circuit breakera wolny/padnięty trade-route-planner blokuje wątki starport-registry przez 2s (read timeout) × setki requestów = wyczerpanie wątków. Z CB — po 5 błędach circuit się otwiera i natychmiast zwraca fallback.

---

### Krok 6: Zwiększenie przepustowości outbox polling

**Plik:** `application.yml`

```yaml
app:
  poll-interval-ms: 5000   # z 30s → 5s
  batch-size: 200           # z 50 → 200

spring:
  task:
    scheduling:
      pool:
        size: 2             # z 1 → 2 wątki schedulera
```

**Dlaczego:**
Obecny throughput: 50 eventów / 30s ≈ 1.6 evt/s.
Po zmianie: 200 eventów / 5s ≈ 40 evt/s — 25x poprawa.

---

### Krok 7: Health-check interval — szybszy failover

**Plik:** `application.yml`

```yaml
spring:
  cloud:
    loadbalancer:
      health-check:
        interval: 5s   # z 15s → 5s
```

**Dlaczego:** Failover z 15s → 5s. Przy dwóch instancjach, padnięcie jednej powoduje 5s niedostępności zamiast 15s.

---

### Krok 8: Monitoring bottlenecków — nowe metryki

**Plik:** nowy `ConnectionPoolMetricsConfig.java`

HikariCP automatycznie eksponuje metryki do Micrometer:
- `hikaricp_connections_active` — ile connections w użyciu
- `hikaricp_connections_pending` — ile wątków czeka na connection
- `hikaricp_connections_timeout_total` — ile timeoutów

**Dodać do Grafana dashboardu:**
```promql
# Alarm: pending connections > 50% pool size
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

### Krok 9: PostgreSQL — tuning po stronie bazy

**Plik:** `docker-compose.yml` — PostgreSQL command/config

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

**Dlaczego:**
- `max_connections=200` — 2 instancje × 30 pool + outbox + monitoring = ~80. Default 100 wystarczy, ale 200 daje margines.
- `shared_buffers=256MB` — cache dla często odpytywanych tabel.
- `random_page_cost=1.1` — SSD; zachęca planner do używania indeksów.

---

### Krok 10: (Opcjonalnie) Rate limiting z Bucket4j

**Kiedy wdrożyć:** Gdy system jest publicznie dostępny lub potrzebna ochrona przed DDoS/abuse.

**Biblioteka:** `bucket4j-spring-boot-starter`

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

**Efekt:** Max 100 req/s na endpoint. Nadmiarowe dostają HTTP 429 Too Many Requests.

---

## Kolejność wdrożenia (priorytet)

| Priorytet | Krok | Czas | Wpływ |
|-----------|------|------|-------|
| **P0** | 1. Indeksy na `reservation` | 15 min | Ogromny — eliminuje full table scan |
| **P0** | 2. HikariCP pool 10→30 | 5 min | Duży — 3x więcej równoczesnych transakcji |
| **P1** | 3. Tomcat threads 200→60 | 5 min | Średni — eliminuje thread starvation |
| **P1** | 4. Indeks na `docking_bay` | 5 min | Średni — szybszy join w findFreeBay |
| **P1** | 5. Circuit breaker | 1h | Duży — ochrona przed kaskadową awarią |
| **P2** | 6. Outbox polling tuning | 5 min | Średni — 25x szybsze publikowanie eventów |
| **P2** | 7. Health-check 15s→5s | 2 min | Mały — szybszy failover |
| **P2** | 8. Monitoring connection pool | 30 min | Średni — widoczność bottlenecków |
| **P3** | 9. PostgreSQL tuning | 10 min | Mały — margines bezpieczeństwa |
| **P3** | 10. Rate limiting | 1h | Mały — ochrona, nie wydajność |

---

## Oczekiwany efekt

| Metryka | Przed | Po |
|---------|-------|-----|
| Max concurrent reservations | ~10/s | **~100-200/s** |
| Avg response time (p50) | ~200ms | **~30-50ms** |
| p99 response time | ~5s (connection wait) | **~200ms** |
| Failover time | 15s | **5s** |
| Outbox event latency | do 60s | **do 5s** |
| findFreeBay query time (10k rezerwacji) | ~50ms (full scan) | **~1ms (index scan)** |

---

## Czego NIE robimy (i dlaczego)

1. **Reactive stack (WebFlux)** — wymaga przepisania całej aplikacji; ROI zbyt niski dla obecnej skali.
2. **CQRS / Event Sourcing** — overengineering; aktualna architektura z outbox pattern wystarcza.
3. **Redis cache na bay availability** — `FOR UPDATE SKIP LOCKED` jest już optymalny; cache wprowadziłby problemy z consistency.
4. **Sharding bazy** — przy setkach req/s PostgreSQL single-node z indeksami daje radę.
5. **Kafka partitioning tuning** — bottleneck jest po stronie producenta (outbox polling), nie konsumenta.
