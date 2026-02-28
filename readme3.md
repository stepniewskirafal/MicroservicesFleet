# Load Balancing & Service Discovery — MicroservicesFleet

## Spis treści

1. [Przegląd architektury](#1-przegląd-architektury)
2. [Service Discovery — Eureka Server](#2-service-discovery--eureka-server)
3. [Load Balancing — Spring Cloud LoadBalancer](#3-load-balancing--spring-cloud-loadbalancer)
4. [Konfiguracja usług](#4-konfiguracja-usług)
5. [Topologia wdrożenia (Docker Compose)](#5-topologia-wdrożenia-docker-compose)
6. [Przepływ żądania end-to-end](#6-przepływ-żądania-end-to-end)
7. [Implementacja w kodzie](#7-implementacja-w-kodzie)
8. [Metryki i obserwowalność](#8-metryki-i-obserwowalność)
9. [Uruchamianie i weryfikacja](#9-uruchamianie-i-weryfikacja)
10. [Decyzje architektoniczne (ADR)](#10-decyzje-architektoniczne-adr)

---

## 1. Przegląd architektury

MicroservicesFleet wdraża **client-side service discovery** i **client-side load balancing** za pomocą:

- **Netflix Eureka** — centralny rejestr usług (service registry)
- **Spring Cloud LoadBalancer** — biblioteka client-side LB wbudowana w Spring Cloud

```
┌─────────────────────────────────────────────────────────────────┐
│                        Docker Compose Network                    │
│                                                                  │
│  ┌────────────────┐        ┌──────────────────────────────────┐  │
│  │  Eureka Server │◄───────┤  Starport Registry (instancja 1) │  │
│  │  :8761         │◄───────┤  Starport Registry (instancja 2) │  │
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

Każda instancja usługi **samodzielnie rejestruje się** w Eurece przy starcie i wysyła heartbeaty co kilka sekund. Kiedy `starport-registry` chce wywołać `trade-route-planner`, biblioteka `Spring Cloud LoadBalancer` odpytuje Eurekę o listę zdrowych instancji i wybiera jedną z nich algorytmem round-robin.

---

## 2. Service Discovery — Eureka Server

### Rola

Eureka Server pełni funkcję **centralnego katalogu** (service registry). Przechowuje:
- nazwy aplikacji (`spring.application.name`)
- adresy IP i porty instancji
- stan zdrowia (UP / DOWN / OUT_OF_SERVICE)
- metadane (wersja, strefa, hostname)

### Konfiguracja serwera (`eureka-server/src/main/resources/application.yml`)

```yaml
server:
  port: 8761

spring:
  application:
    name: eureka-server

eureka:
  client:
    register-with-eureka: false   # serwer sam się nie rejestruje
    fetch-registry: false         # nie pobiera własnego rejestru
  server:
    enable-self-preservation: false          # wyłączone dla szybkiej detekcji awarii
    eviction-interval-timer-in-ms: 5000      # sprawdzanie nieaktywnych co 5 s
    response-cache-update-interval-ms: 3000  # odświeżenie cache odpowiedzi co 3 s
    wait-time-in-ms-when-sync-empty: 0       # tryb standalone — nie czeka na peery

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
```

**Kluczowe ustawienia:**

| Parametr | Wartość | Znaczenie |
|---|---|---|
| `enable-self-preservation` | `false` | Instancje są szybko usuwane z rejestru gdy przestają wysyłać heartbeaty |
| `eviction-interval-timer-in-ms` | `5000` | Eureka sprawdza martwe instancje co 5 sekund |
| `response-cache-update-interval-ms` | `3000` | Cache odpowiedzi Eureki odświeżany co 3 sekundy — klienci szybciej widzą nowe instancje |
| `register-with-eureka` | `false` | Serwer nie rejestruje sam siebie |

### Endpointy Eureki

| URL | Opis |
|---|---|
| `http://localhost:8761` | Dashboard UI — lista zarejestrowanych usług i instancji |
| `http://localhost:8761/eureka/apps` | REST API — pełna lista aplikacji (XML/JSON) |
| `http://localhost:8761/eureka/apps/STARPORT-REGISTRY` | Instancje konkretnej usługi |
| `http://localhost:8761/actuator/health` | Health check serwera Eureki |
| `http://localhost:8761/actuator/prometheus` | Metryki Prometheus |

---

## 3. Load Balancing — Spring Cloud LoadBalancer

### Mechanizm działania

`Spring Cloud LoadBalancer` to **client-side** biblioteka. Oznacza to, że decyzja o wyborze instancji podejmowana jest **po stronie wywołującego serwisu** (nie centralnego proxy):

```
Starport Registry                    Eureka Server
       │                                   │
       │──── pobierz instancje ────────────►│
       │◄─── [inst1:8082, inst2:8083] ──────│
       │                                   │
       │  algorytm round-robin             │
       │  → wybiera: inst1:8082            │
       │                                   │
       │──── HTTP POST /routes/plan ──────► inst1:8082
```

### Algorytm

Domyślnie: **Round-Robin** — każde kolejne żądanie trafia do następnej instancji z listy. Biblioteka jest rozszerzalna — można podmienić strategię np. na `WeightedLoadBalancer` lub `ZoneAwareLoadBalancer`.

### Schemat URI `lb://`

Zamiast wpisywać konkretny adres `http://192.168.1.5:8082`, serwis używa:

```
http://trade-route-planner/routes/plan
```

gdzie prefiks `lb://` jest obsługiwany przez `@LoadBalanced RestTemplate` — biblioteka automatycznie resolwuje nazwę serwisu na rzeczywisty adres IP:port z Eureki.

W konfiguracji `application.yml` starport-registry:

```yaml
app:
  trade-route-planner:
    base-url: http://trade-route-planner
```

Baza URL nie zawiera portu ani IP — to Eureka + LoadBalancer je dostarcza.

### Cache instancji

LoadBalancer utrzymuje lokalny cache listy instancji pobranych z Eureki. Cache jest odświeżany periodycznie. Istnieje krótkie okno, gdy cache może zawierać już usuniętą instancję — obsługiwane przez mechanizm retry (patrz: ADR-0010).

---

## 4. Konfiguracja usług

### Starport Registry (`starport-registry/src/main/resources/application.yml`)

```yaml
spring:
  application:
    name: starport-registry       # nazwa pod którą rejestruje się w Eurece

  cloud:
    discovery:
      enabled: true               # włącz service discovery

app:
  trade-route-planner:
    base-url: http://trade-route-planner   # lb:// rozwiązywane przez @LoadBalanced

eureka:
  client:
    serviceUrl:
      defaultZone: ${EUREKA_URL:http://localhost:8761/eureka}
  instance:
    instance-id: ${spring.application.name}:${spring.cloud.client.hostname:${HOSTNAME:unknown}}:${server.port}
    prefer-ip-address: true       # rejestracja po IP zamiast hostname
```

**`instance-id`** jest unikalny dla każdej instancji — zawiera nazwę aplikacji, hostname kontenera i port. Przykładowe wartości:
- `starport-registry:3a4b5c6d:8081`
- `starport-registry:9f8e7d6c:8084`

### Trade Route Planner (`trade-route-planner/src/main/resources/application.yml`)

```yaml
spring:
  application:
    name: trade-route-planner     # nazwa widoczna w rejestrze Eureki

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

## 5. Topologia wdrożenia (Docker Compose)

Plik `infra/compose/docker-compose.yml` uruchamia **po dwie instancje** każdej usługi biznesowej, co pozwala weryfikować działanie load balancingu:

```
┌──────────────────────────────────────────────────────────────────┐
│                    docker-compose network                         │
│                                                                   │
│  eureka                  :8761  ← service registry               │
│                                                                   │
│  starport-registry-1     :8081  ┐                                │
│  starport-registry-2     :8084  ┘ ← 2 repliki (HA)              │
│                                                                   │
│  trade-route-planner-1   :8082  ┐                                │
│  trade-route-planner-2   :8083  ┘ ← 2 repliki (HA)              │
│                                                                   │
│  postgres                :5432  ← baza danych (wspólna)          │
│  kafka                   :9092  ← broker zdarzeń                 │
│  zipkin                  :9411  ← distributed tracing            │
│  prometheus              :9090  ← metryki                        │
└──────────────────────────────────────────────────────────────────┘
```

### Zmienne środowiskowe sterujące discovery

Każda instancja usługi otrzymuje przez `environment`:

| Zmienna | Wartość | Opis |
|---|---|---|
| `PORT` | `8081` / `8084` / `8082` / `8083` | Port serwera HTTP |
| `EUREKA_URL` | `http://eureka:8761/eureka` | Adres rejestru Eureki w sieci Docker |

### Kolejność startowania (depends_on)

```yaml
starport-registry-1:
  depends_on:
    eureka:
      condition: service_healthy   # czeka aż Eureka odpowie na /actuator/health
    postgres:
      condition: service_healthy
```

Usługi biznesowe nie startują dopóki Eureka nie jest gotowa — eliminuje problem rejestracji przed uruchomieniem serwera.

---

## 6. Przepływ żądania end-to-end

### Rejestracja instancji przy starcie

```
1. Kontener starport-registry-1 startuje (PORT=8081)
2. Spring Boot wczytuje konfigurację Eureka client
3. Po inicjalizacji kontekstu: POST http://eureka:8761/eureka/apps/STARPORT-REGISTRY
   Body: { "hostName": "3a4b5c", "ipAddr": "172.18.0.5", "port": 8081, "status": "UP" }
4. Eureka dodaje instancję do rejestru
5. Co ~30s: PUT http://eureka:8761/eureka/apps/STARPORT-REGISTRY/starport-registry:3a4b5c:8081
   (heartbeat — odnowienie lease'u)
```

### Wywołanie z load balancingiem

```
1. Klient HTTP wysyła POST /starports/1/reservations
   → trafia do starport-registry-1:8081 lub starport-registry-2:8084

2. ReservationService potrzebuje zaplanować trasę
   → wywołuje RoutePlannerClient z URL: http://trade-route-planner/routes/plan

3. @LoadBalanced RestTemplate przechwytuje żądanie
   → odpytuje lokalny cache instancji dla "trade-route-planner"
   → jeśli cache pusty/wygasły: GET http://eureka:8761/eureka/apps/TRADE-ROUTE-PLANNER
   → otrzymuje: [{ ip: 172.18.0.7, port: 8082 }, { ip: 172.18.0.8, port: 8083 }]

4. Round-robin wybiera instancję (np. trade-route-planner-1:8082)
   → POST http://172.18.0.7:8082/routes/plan

5. Odpowiedź wraca do starport-registry
   → ReservationService kontynuuje przetwarzanie
```

### Usunięcie instancji (failover)

```
1. trade-route-planner-2:8083 przestaje odpowiadać
2. Eureka nie otrzymuje heartbeatu przez ~90s (lub 5s przy wyłączonej self-preservation)
3. Eureka usuwa instancję z rejestru
4. Kolejne żądanie z Starport Registry: LoadBalancer pobiera świeżą listę
5. Lista zawiera tylko trade-route-planner-1:8082
6. Wszystkie żądania kierowane do działającej instancji — bez zmian w kodzie klienta
```

---

## 7. Implementacja w kodzie

### Bean `@LoadBalanced RestTemplate`

**Plik:** `starport-registry/src/main/java/com/galactic/starport/config/RestClientConfig.java`

```java
@Configuration
class RestClientConfig {

    @Bean("tradeRoutePlannerRestTemplate")
    @LoadBalanced                          // ← kluczowa adnotacja
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
        return new RestTemplate();         // fallback dla testów bez Eureki
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

**Jak to działa:**

1. `@LoadBalanced` rejestruje `RestTemplate` jako interceptowany przez `LoadBalancerInterceptor`
2. Każde wychodzące żądanie jest przechwytywane przez interceptor
3. Interceptor resolwuje nazwę hosta (`trade-route-planner`) przez `DiscoveryClient` (Eureka)
4. Zastępuje nazwę rzeczywistym IP:port wybranej instancji
5. Przekazuje zmodyfikowane żądanie do serwera HTTP

### Warunkowość (`@ConditionalOnProperty`)

Konfiguracja obsługuje dwa tryby:
- **`spring.cloud.discovery.enabled=true`** (domyślnie): używa `@LoadBalanced RestTemplate` — Eureka resolwuje adresy
- **`spring.cloud.discovery.enabled=false`**: używa zwykłego `RestTemplate` — przydatne w testach integracyjnych z WireMock

### Weryfikacja architektoniczna (ArchUnit)

Projekt zawiera testy `ArchUnit` weryfikujące, że:
- klasy warstwy Infrastructure mogą wywoływać zewnętrzne serwisy
- klasy domenowe **nie** importują klas Spring Cloud (separacja warstw)

---

## 8. Metryki i obserwowalność

### Metryki Eureki

Dostępne przez `/actuator/prometheus` na porcie `8761`:

| Metryka | Opis |
|---|---|
| `eureka_server_registry_size` | Liczba zarejestrowanych instancji |
| `eureka_server_renewals_per_minute` | Częstotliwość heartbeatów |
| `eureka_server_evictions_total` | Łączna liczba usuniętych instancji |

### Metryki klienta (Starport Registry)

| Metryka | Opis |
|---|---|
| `spring.cloud.loadbalancer.requests.total` | Łączna liczba żądań LB |
| `starport.external.route.time` | Latencja wywołań do Trade Route Planner |
| `http.client.requests` | Histogram żądań HTTP (Spring Actuator) |

Konfiguracja SLO dla czasu planowania trasy:

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

Każde żądanie przechodzące przez load balancer zawiera nagłówki trace:
- `X-B3-TraceId` — identyfikator śladu (propagowany przez wszystkie serwisy)
- `X-B3-SpanId` — identyfikator aktualnego spanu
- `X-B3-ParentSpanId` — span wywołujący

W Zipkin (`http://localhost:9411`) widoczny jest kompletny ślad:

```
[Klient] → [starport-registry-1:8081] → [trade-route-planner-1:8082]
              ▲ span: reservation         ▲ span: route-planning
              └─────────── trace ─────────┘
```

---

## 9. Uruchamianie i weryfikacja

### Start całego stosu

```bash
cd infra/compose
docker compose up --build
```

Kolejność startowania gwarantowana przez `depends_on` + `healthcheck`:
1. `eureka` (:8761)
2. `postgres`, `kafka`, `zipkin`, `prometheus` (równolegle)
3. `starport-registry-1`, `starport-registry-2` (po zdrowiu Eureki i Postgres)
4. `trade-route-planner-1`, `trade-route-planner-2` (po zdrowiu Eureki)

### Weryfikacja rejestracji usług

```bash
# Dashboard Eureki — lista zarejestrowanych instancji
open http://localhost:8761

# REST API — wszystkie aplikacje w formacie JSON
curl -H "Accept: application/json" http://localhost:8761/eureka/apps | jq .

# Instancje konkretnej usługi
curl -H "Accept: application/json" \
  http://localhost:8761/eureka/apps/TRADE-ROUTE-PLANNER | jq .

# Oczekiwany wynik: 2 instancje ze statusem UP
```

### Weryfikacja load balancingu

```bash
# Wysyłanie kilku żądań do Starport Registry
for i in {1..6}; do
  curl -s -o /dev/null -w "%{http_code}\n" \
    -X POST http://localhost:8081/starports/1/reservations \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: test-$i" \
    -d '{"shipId":1,"customerId":1,"duration":2}'
done

# Sprawdzenie logów obu instancji Trade Route Planner
docker compose logs trade-route-planner-1 | grep "route"
docker compose logs trade-route-planner-2 | grep "route"
# Oba powinny mieć żądania — round-robin aktywny
```

### Weryfikacja failover

```bash
# Zatrzymanie jednej instancji Trade Route Planner
docker compose stop trade-route-planner-2

# Poczekaj ~10s na eviction w Eurece (enable-self-preservation: false)
sleep 10

# Sprawdź rejestr — powinna być 1 instancja UP
curl -H "Accept: application/json" \
  http://localhost:8761/eureka/apps/TRADE-ROUTE-PLANNER | jq '.application.instance | length'
# Wynik: 1

# Żądania nadal działają — kierowane do trade-route-planner-1
curl -X POST http://localhost:8081/starports/1/reservations ...
# HTTP 200/201 — failover automatyczny
```

### Health Checks

```bash
# Eureka Server
curl http://localhost:8761/actuator/health

# Instancje Starport Registry
curl http://localhost:8081/actuator/health
curl http://localhost:8084/actuator/health

# Instancje Trade Route Planner
curl http://localhost:8082/actuator/health
curl http://localhost:8083/actuator/health
```

---

## 10. Decyzje architektoniczne (ADR)

### ADR-0002 — Service Discovery (Eureka)

**Decyzja:** Netflix Eureka jako centralny rejestr usług.

**Uzasadnienie:**
- Natywna integracja ze Spring Boot 3.x i Spring Cloud
- Zero dodatkowej infrastruktury poza już używanym stosem
- Client-side load balancing bez zewnętrznego proxy
- Bogata metadanych instancji (wersja, strefa, hostname)
- Dobre wsparcie dla lokalnego developmentu (Docker Compose)

**Rozważane alternatywy:**

| Opcja | Powód odrzucenia |
|---|---|
| **Consul** | Większy narzut operacyjny dla projektu Spring-only |
| **Kubernetes Service DNS** | Nie pasuje do Docker Compose (środowisko docelowe) |
| **NGINX/Envoy (server-side)** | Centralizacja = SPOF; nadmiarowość dla obecnego zakresu |

**Ryzyka i mitigacje:**
- *Eventual consistency rejestru* → krótki TTL heartbeatu + wyłączona self-preservation
- *Stale cache u klienta* → retry na innej instancji przy błędzie połączenia
- *Bezpieczeństwo* → TLS + auth dla środowisk produkcyjnych

**Plik:** `adr/0002-service-discovery-choice.md`

---

### ADR-0003 — HTTP Load Balancing (Spring Cloud LoadBalancer)

**Decyzja:** Client-side load balancing przez `Spring Cloud LoadBalancer` z adresowaniem `lb://service-name`.

**Uzasadnienie:**
- Jedyna opcja integrująca się bezpośrednio z rejestrem Eureki
- Zero dodatkowej infrastruktury (brak NGINX/Envoy/Istio)
- Czytelna składnia URI: `lb://trade-route-planner`
- Każdy serwis zarządza własną odpornością (brak SPOF)
- Automatyczna instrumentacja Micrometer

**Rozważane alternatywy:**

| Opcja | Powód odrzucenia |
|---|---|
| **NGINX/Envoy (server-side proxy)** | Dodatkowy komponent, potencjalny SPOF, brak integracji z Eureką |
| **Kubernetes kube-proxy (L4)** | Nie pasuje do Docker Compose; brak granularności health-checków |

**Implementacja:**
- Adnotacja `@LoadBalanced` na beanie `RestTemplate`
- Base URL: `http://trade-route-planner` (bez portu/IP)
- `@ConditionalOnProperty` — fallback dla testów bez Eureki
- Strategia: Round-Robin (domyślna, rozszerzalna)

**Plik:** `adr/0003-http-load-balancing.md`

---

## Podsumowanie

| Aspekt | Rozwiązanie |
|---|---|
| **Service Registry** | Netflix Eureka Server (`:8761`) |
| **Protokół rejestracji** | REST API Eureka (heartbeat co ~30s) |
| **Load Balancing** | Spring Cloud LoadBalancer (client-side) |
| **Algorytm LB** | Round-Robin (domyślny, pluggable) |
| **Adresowanie** | `lb://service-name` → resolwowane przez Eurekę |
| **Instancje per serwis** | 2 (weryfikacja HA i LB) |
| **Failover** | Automatyczny — usunięcie martwej instancji z rejestru co 5s |
| **Metryki** | Micrometer → Prometheus → Grafana |
| **Tracing** | Zipkin (trace propagowany przez wszystkie instancje) |
| **Środowisko** | Docker Compose (lokalne), Spring Cloud 2025.0.0, Java 21 |
