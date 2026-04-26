# Dokumentacja paneli — Distributed Tracing — MicroservicesFleet

Dokument tłumaczy każdy panel dashboardu Grafany w `infra/docker/grafana/provisioning/dashboards/distributed_tracing.json`. Cel: zrozumieć, **z jakiego źródła pochodzą dane**, **jak Tempo generuje metryki ze spanów**, **jak Loki koreluje logi z trace'ami**, **co panel mierzy** i **jaką wartość biznesową daje**.

W przeciwieństwie do "Microservices Fleet Dashboard" ten dashboard miksuje **trzy datasources**: **Prometheus** (metryki Micrometera + auto-generowane przez Tempo), **Tempo** (spany + service graph + node map) i **Loki** (logi z `traceId` linkowanym do Tempo).

---

## Część I — Stos obserwabilności w MicroservicesFleet

Dashboard "Distributed Tracing" stoi na czterech filarach. Bez ich zrozumienia większość paneli wygląda jak magia czarna, biała lub szara.

### 1. Trzy filary obserwabilności + glue

```
                       ┌──────────────┐
                       │  Aplikacje   │  starport-registry, trade-route-planner,
                       │  (Java JVM)  │  telemetry-pipeline, api-gateway
                       └──────┬───────┘
              metrics │       │ traces            │ logs
                      ▼       ▼                   ▼
              ┌──────────┐ ┌────────┐    ┌──────────────┐
              │Prometheus│ │ Tempo  │    │ Loki / Promtail │
              │  scrape  │ │  OTLP  │    │   stdout tail   │
              └────┬─────┘ └───┬────┘    └────────┬────────┘
                   │           │                  │
                   │  remote_write              │
                   │       ◄────────┐            │
                   │     metrics    │  generated │
                   │     ze spanów  │            │
                   ▼                ▼            ▼
                  ┌─────────────────────────────────┐
                  │           Grafana               │
                  │   datasource: Prometheus, Tempo, Loki │
                  └─────────────────────────────────┘
```

**Strzałka "remote_write" jest kluczowa**: Tempo nie tylko **przechowuje spany**, ale też **generuje z nich metryki** i wysyła do Prometheusa przez `remote_write`. Konfiguracja w `infra/docker/grafana/tempo.yml`:

```yaml
metrics_generator:
  registry:
    external_labels:
      source: tempo
  storage:
    remote_write:
      - url: http://prometheus:9090/api/v1/write
overrides:
  metrics_generator_processors: [service-graphs, span-metrics]
```

Tempo z dwoma procesorami (`service-graphs` + `span-metrics`) emituje do Prometheusa metryki, które normalnie nie istniałyby w aplikacjach: `traces_spanmetrics_*` (RED per service computed from spans) i `traces_service_graph_*` (krawędzie grafu wywołań).

### 2. Co jest spanem, a co metryką

**Span** to atomowy zapis: „w czasie T1..T2 serwis X wykonał operację Y, ze statusem OK/ERROR, w kontekście traceId=abc, spanId=def, parentSpanId=parent". Każde wywołanie HTTP, każdy `Observation.observe(...)`, każdy `streamBridge.send(...)` to span. Spany są zbierane przez Tempo, indeksowane po traceId i przeglądane w UI w postaci **wodospadu** (waterfall).

**Metryka** to liczba w czasie — agregacja dziesiątek tysięcy spanów do jednego rate'a / kwantyla. Idealna do alertów; bezużyteczna do diagnozy konkretnego pojedynczego błędu.

Dlatego dashboard miksuje:
- **Stat panele** (count, percent, current value) — z Prometheusa, błyskawicznie
- **Timeseries** — z Prometheusa, trendy w czasie
- **Trace Search** — z Tempo, drążenie konkretnych trace'ów
- **Service Graph** — z Tempo, wizualizacja zależności
- **Logs** — z Loki, korelacja błędów z trace'ami

### 3. Jak nasze aplikacje produkują spany

Wszystkie 3 serwisy mają w `application.yml`:
```yaml
management:
  otlp:
    tracing:
      endpoint: http://tempo:4318/v1/traces
  tracing:
    sampling:
      probability: 1.0  # 100% sampling — w dev OK, w prod 1-10%
```

**Spring Boot 3 + Micrometer Tracing** automatycznie:
- Eksportuje spany w formacie OTLP HTTP do Tempo (port 4318)
- Generuje span dla każdego HTTP request (przez `WebMvcMetricsFilter` analog dla tracingu)
- Generuje span dla każdego `Observation.observe(...)` w kodzie (Observation API == jeden obiekt = i metryka i span)
- Propaguje `traceId` przez nagłówki HTTP (W3C Trace Context) i przez Kafka headers (gdzie nasz outbox/inbox dba o forwardowanie)
- Wstawia `traceId,spanId` do MDC i pattern `[%X{traceId},%X{spanId}]` w logach (`logging.pattern.correlation` w app.yml)

To ostatnie pozwala Loki na wyłapanie `traceId` z logów regex'em `\[\w[\w-]*,(\w{16,32}),\w+\]` (zdefiniowanym w `infra/docker/grafana/provisioning/datasources/datasource.yml`), zamianę na klikalny link, i Grafana otwiera ten trace w Tempo w tym samym oknie.

### 4. Span-metrics — RED z trace'ów, magia Tempo

Tempo `span-metrics` processor czyta każdy przychodzący span i emituje do Prometheusa:

| Metryka | Co znaczy |
|---|---|
| `traces_spanmetrics_calls_total{service, span_name, span_kind, status_code}` | counter wywołań — RATE |
| `traces_spanmetrics_latency_bucket{service, ..., le}` | histogram latencji — DURATION |
| `traces_spanmetrics_latency_count`, `_sum` | jak każdy histogram |

Daje to **RED dla każdego serwisu, automatycznie**, bez instrumentacji w kodzie. Wystarczy że emisja spanów działa — Tempo dorobi resztę.

Różnica wobec `http_server_requests_seconds_*` z Micrometera:
- `http.server.requests` mierzy **tylko HTTP server endpoints**
- `traces_spanmetrics_calls` mierzy **wszystkie spany** (w tym wewnętrzne `reservations.confirm`, `reservations.outbox.append`, czy DB calls jeśli włączone)

### 5. Service-graph — kto wywołuje kogo, magia jeszcze większa

Procesor `service-graphs` w Tempo robi coś sprytniejszego: paruje **client span** z **server span** (po `traceId` + chronologii) i emituje:

| Metryka | Co znaczy |
|---|---|
| `traces_service_graph_request_total{client, server}` | rate połączeń client→server |
| `traces_service_graph_request_failed_total{client, server}` | rate połączeń zakończonych błędem |
| `traces_service_graph_request_server_seconds_bucket` | latencja widziana po stronie servera |
| `traces_service_graph_request_client_seconds_bucket` | latencja widziana po stronie clienta (zawiera czas sieci) |

**Magia**: bez zmian w kodzie, bez konfiguracji per pair, dostajesz wykres **zależności między serwisami z latencją i błędami**. Wystarczy że spany propagują traceId.

Z tego wyrasta **node graph** (panel 42) — wizualizacja w stylu „mapa świata" z węzłami=serwisami i krawędziami=połączeniami. Klikasz węzeł → drążysz do trace'ów.

### 6. Co Tempo widzi, czego nie widzi

Tempo widzi tylko spany, które OTEL/Micrometer Tracing wyemitował i wysłał. Nie widzi:
- Spanów po stronie infrastruktury (Postgres internals, Kafka broker)
- Wewnętrznych pętli aplikacji bez Observation
- Ruchu, który nie został posamplowany (`sampling.probability < 1.0`)

W naszym dev environmentu sampling = 100%, więc Tempo widzi wszystko, ale w produkcji to jest świadoma decyzja: 1% sampling = 100× mniej miejsca, ale tracimy 99% pojedynczych przypadków.

### 7. Loki + traceId derived field — most między logami a tracerem

W datasource Loki:
```yaml
derivedFields:
  - datasourceUid: tempo
    matcherRegex: "\\[\\w[\\w-]*,(\\w{16,32}),\\w+\\]"
    name: traceId
    url: "$${__value.raw}"
```

Każdy log line przechodzi przez ten regex. Pattern `[<service>,<traceId>,<spanId>]` w naszych logach (z `logging.pattern.correlation`) jest matchowany. Drugi capture group (16-32 znaków hex = traceId) staje się klikalnym linkiem. Klik → otwiera trace w Tempo.

Ten jeden mostek **redukuje czas diagnozy z minut do sekund**. Widzisz `ERROR Failed to plan route` w logach, klik traceId → Grafana pokazuje cały trace tej rezerwacji, widzisz że route-planner zwrócił 500 po 7 sekundach, klikasz w jego span → masz logi tego konkretnego serwisu w tym samym czasie.

---

## Część II — Panele dashboardu

Panele w kolejności występowania (`gridPos.y` rosnąco). Każdy ma `id` zgodny z polem w JSON.

---

### Sekcja: Tracing Overview (4 stat panele)

#### Panel ID:2 — "Total Request Rate"

**Co pokazuje**: globalny rate HTTP requestów — wszystkie endpointy, wszystkie serwisy, wszystkie metody. Wyświetlone jako duża cyfra `req/s`.

**Datasource**: Prometheus.

**Zapytanie**:
```promql
sum(rate(http_server_requests_seconds_count[$__rate_interval]))
```

**Metryka źródłowa**: `http.server.requests` — built-in Spring Boot Actuator. Każdy endpoint każdego serwisu auto-instrumentowany przez `WebMvcMetricsFilter` (Servlet) lub `WebFluxMetricsFilter` (api-gateway → reactive).

**Kod Java**: brak — autoconfiguration. Włączane przez samą obecność `spring-boot-starter-actuator` + `micrometer-registry-prometheus` na classpath.

**Co Prometheus widzi**: dla `http.server.requests` (Timer) eksportowane są:
- `http_server_requests_seconds_count{job, instance, uri, method, status, outcome, exception}`
- `http_server_requests_seconds_sum`
- `http_server_requests_seconds_max`
- `http_server_requests_seconds_bucket{le=...}` — jeżeli histogram aktywowany

**Jak działa zapytanie**: `rate(_count[5m])` daje **events per second** w okienku 5 minut. `sum()` zwija WSZYSTKIE labele (job, uri, method, status…) → jedna liczba globalna.

**Czy poprawnie?** ✅ Klasyczny RED Rate. Brak split per status — jest na pokazanie: **„czy w ogóle jest jakiś ruch w systemie?"**. Próg wizualny: kolory ustawione na `100 = yellow, 500 = red`, ale żeby to miało sens, próg powinien być oparty o **godzinowy trend** (300 req/s w peak hours = OK, w nocy = anomalia).

**Wartość biznesowa**: heartbeat całego systemu. Spadek do 0 = albo żaden klient nie używa, albo api-gateway umarł. Wzrost = przyspieszenie — capacity planning.

---

#### Panel ID:3 — "Avg Request Latency"

**Co pokazuje**: globalna średnia latencja wszystkich HTTP requestów w ms.

**Zapytanie**:
```promql
1000 * sum(rate(http_server_requests_seconds_sum[$__rate_interval]))
     / clamp_min(sum(rate(http_server_requests_seconds_count[$__rate_interval])), 1e-9)
```

**Metryka**: ta sama `http.server.requests`.

**Jak działa zapytanie**: klasyczny `sum / count` w okienku rate'a, mnożone × 1000 → ms. **`clamp_min(..., 1e-9)`** — jeden z najważniejszych nawyków PromQL: chroni przed `0/0=NaN` w okresach bez ruchu.

**Czy poprawnie?** ⚠️ Z punktu widzenia wartości pojedynczej (stat panel) — OK. Ale średnia ukrywa ogony: 99% requestów po 50ms + 1% po 30s = średnia 350ms (wygląda umiarkowanie), a 1% userów czeka 30 sekund. Dlatego panel ID:11 osobno daje p50/p95/p99 — uzupełnienie.

Próg `200 = yellow, 1000 = red` jest pragmatyczny. Wewnętrznie nasze SLO mogłoby być ostrzejsze (p99 < 1s = red wcześniej).

**Wartość biznesowa**: snapshot „czy API ogólnie szybko odpowiada?". Dla executive dashboard wystarczy. Dla diagnostyki — używaj percentyli.

---

#### Panel ID:4 — "HTTP Error Rate (5xx)"

**Co pokazuje**: procent requestów zakończonych statusem 5xx (server error).

**Zapytanie**:
```promql
100 * sum(rate(http_server_requests_seconds_count{status=~"5.."}[$__rate_interval]))
    / clamp_min(sum(rate(http_server_requests_seconds_count[$__rate_interval])), 1e-9)
```

**Metryka**: `http.server.requests` z labelem `status` (auto-tagged by Spring Boot Actuator: 200, 201, 400, 404, 500, …).

**Filtr `status=~"5.."`** — regex, dwie kropki = dwa dowolne znaki. Czyli wszystkie 5xx (500, 502, 503, 504, …).

**Czy poprawnie?** ✅ Wzorcowa metryka error rate dla SLO. **Pomija celowo 4xx** — to nie nasze błędy, to client errors (zły JSON, brak auth, etc.). 5xx to zawsze wina serwera.

Progi `1% = yellow, 5% = red` — typowe SLO „99% HTTP success" (czyli 1% allowable error). Spójne z best practices.

**Wartość biznesowa**: główny health indicator. **Najprostsza metryka, którą możesz alertować**: `> 1% przez > 5min = page`. Kombinacja z trace'ami w panelu 41 daje pełny pipe diagnostyki: alarm → klik → konkretne błędne trace'y.

---

#### Panel ID:5 — "Active Services"

**Co pokazuje**: ile z naszych 3 mikroserwisów (starport-registry / trade-route-planner / telemetry-pipeline) aktualnie chodzi.

**Zapytanie**:
```promql
count(count by (job) (up{job=~"starport-registry|trade-route-planner|telemetry-pipeline"}))
```

**Metryka źródłowa**: `up` — built-in Prometheusa, generowana przy każdym scrape. Wartość:
- `1` → scrape udany (target healthy)
- `0` → scrape failed (target down lub unreachable)

**Jak działa zapytanie**:
1. `up{job=~"…"}` filtruje po wzorcu nazwy serwisu — daje serie per `(job, instance)`. Dla 2 instancji × 3 serwisy = 6 serii.
2. Wewnętrzne `count by (job) (...)` zlicza ile instancji per serwis.
3. Zewnętrzne `count(...)` policzy **ile osobnych jobów** ma chociaż jedną instancję = ile **serwisów** żyje.

**Czy poprawnie?** ✅ Sprytna konstrukcja. Drobna uwaga: ten panel **nie odróżnia "1/2 instancji żyje" od "2/2 żyje"** — ale do executive overview to wystarczy. Bardziej granularny byłby:
```promql
sum by (job) (up{job=~"…"})  # ile instancji per serwis
```
…wyświetlone jako tabela.

**Wartość biznesowa**: dolny próg sanity check. „Czy w ogóle działa cały stack?" — jedna liczba. Jeżeli pokazuje `2` zamiast `3` = jeden serwis padł.

---

### Sekcja: Service Latency — HTTP Request Duration (2 panele)

#### Panel ID:11 — "HTTP Request Duration by Service [p50 / p95 / p99]"

**Co pokazuje**: percentyle 50, 95, 99 latencji HTTP, per serwis. 9 linii w sumie (3 percentyle × 3 serwisy).

**Zapytania** (3 targety):
```promql
1000 * histogram_quantile(0.50, sum by (le, job) (rate(http_server_requests_seconds_bucket[$__rate_interval])))
1000 * histogram_quantile(0.95, sum by (le, job) (rate(http_server_requests_seconds_bucket[$__rate_interval])))
1000 * histogram_quantile(0.99, sum by (le, job) (rate(http_server_requests_seconds_bucket[$__rate_interval])))
```

**Metryka źródłowa**: `http_server_requests_seconds_bucket` — **histogram z buckety**. Aktywne dzięki `application.yml`:
```yaml
percentiles-histogram:
  http.server.requests: true
```

Wymagane — bez tego `_bucket` nie istnieje, `histogram_quantile()` zwraca `NaN`.

**Jak działa `histogram_quantile`**:
1. `rate(…_bucket[5m])` — daje rate dla każdego bucketu osobno
2. `sum by (le, job) (…)` — sumuje rate'y między instancjami **per (le, job)**, czyli po `le` (próg bucketu) i `job`. **`le` MUSI być zachowany w `by`**, inaczej `histogram_quantile` nie zadziała
3. `histogram_quantile(0.99, …)` — interpoluje liniowo między bucketami żeby znaleźć wartość, poniżej której jest 99% próbek

**Krytyczne**: dlatego histogramy są kluczowe w środowisku rozproszonym. **Sumować rate'y bucketów per (le, job)** = poprawna agregacja. Próba liczenia p99 z `Summary` (client-side percentiles) wzięłaby średnią z dwóch p99 z dwóch instancji — matematycznie zła.

**Czy poprawnie?** ✅ Wzorcowy PromQL dla percentyli. Drobne ryzyka:
- **Buckety domyślne Micrometera** ~67 buckety per endpoint = duża cardinality. Plan §9.3 sugeruje zastąpienie własną paletą `[50ms, 100ms, 250ms, 500ms, 1s, 2.5s, 5s, 10s]` — 15× mniej. **Jeszcze nie zrobione.**
- p99 z bucketu domyślnego Micrometera jest dokładny do ~10ms. Z palety 8-bucket'owej byłby z dokładnością do interpolacji w obrębie bucketu — np. p99 wpada w `[500ms, 1s]` → wartość zwrócona to ~750ms. Mniej precyzji, ale dla SLO wystarczy: chcemy wiedzieć "p99 < 1s czy nie?"

**Wartość biznesowa**: **kluczowy SLI**. p99 to to, co odczuwa „najbardziej cierpliwy" 1% userów. Wzrost p99 bez wzrostu p50 = **długi ogon**, prawdopodobnie zewnętrzna zależność (DB lock, slow downstream service). Wzrost p50 = systemowo gorzej, prawdopodobnie load.

---

#### Panel ID:12 — "HTTP Request Rate by Status Code"

**Co pokazuje**: stacked bar chart rate'u HTTP per (serwis, klasa statusu 2xx/4xx/5xx).

**Zapytania** (3 targety):
```promql
sum by (job) (rate(http_server_requests_seconds_count{status=~"2.."}[$__rate_interval]))
sum by (job) (rate(http_server_requests_seconds_count{status=~"4.."}[$__rate_interval]))
sum by (job) (rate(http_server_requests_seconds_count{status=~"5.."}[$__rate_interval]))
```

**Wizualne**: `drawStyle: bars` + `stacking: normal` + zielony 2xx, pomarańczowy 4xx, czerwony 5xx (overrides w panelu).

**Czy poprawnie?** ✅ Kanoniczna prezentacja. Stacked bars pokazują **łączny ruch** (wysokość słupka) i **rozkład klas statusu** (kolor). Można na pierwszy rzut oka zauważyć:
- Wzrost słupków bez zmiany kolorów = więcej ruchu, system zdrowy
- Zmiana proporcji (czerwony zamiast zielonego) = błędy serwera
- Pomarańczowy dominuje = klienci wysyłają śmieci (auth, walidacja)

**Pułapka**: status `200` i `201` są oba `2xx` — panel ich nie rozróżnia. Dla większości przypadków OK, ale jak ktoś szuka „dlaczego nie ma już 201 (created)" to nie znajdzie tutaj.

**Wartość biznesowa**: główny breakdown ruchu. Pierwszy rzut oka na zdrowie systemu — czy 5xx rośnie? Czy 4xx skoczył (atak? buggy klient?). Korelacja z panelem 4 (HTTP Error Rate).

---

### Sekcja: Cross-Service Tracing — Reservation Flow (4 panele)

#### Panel ID:21 — "Reservation Step Latency [avg ms]"

**Co pokazuje**: średnie latencje 3 kroków flow rezerwacji: hold allocate, fee calculate, route plan (HTTP → trade-route-planner).

**Zapytania** (3 targety):
```promql
1000 * sum(rate(reservations_hold_allocate_seconds_sum[$__rate_interval]))
     / clamp_min(sum(rate(reservations_hold_allocate_seconds_count[$__rate_interval])), 1e-9)
1000 * sum(rate(reservations_fees_calculate_seconds_sum[$__rate_interval]))
     / clamp_min(sum(rate(reservations_fees_calculate_seconds_count[$__rate_interval])), 1e-9)
1000 * sum(rate(reservations_route_plan_seconds_sum[$__rate_interval]))
     / clamp_min(sum(rate(reservations_route_plan_seconds_count[$__rate_interval])), 1e-9)
```

**Metryki źródłowe**: 3 Observations (auto-tworzą Timery):

1. `reservations.hold.allocate` — `CreateHoldReservationService.java:34`
   ```java
   Observation.createNotStarted(OBSERVATION_NAME, observationRegistry)
           .lowCardinalityKeyValue("starport", command.destinationStarportCode())
           .lowCardinalityKeyValue("shipClass", command.shipClass().name())
           .observe(() -> persistenceFacade.createHoldReservation(command));
   ```
   Mierzy: DB transaction `INSERT reservation + FOR UPDATE SKIP LOCKED` na bayach.

2. `reservations.fees.calculate` — `FeeCalculatorService.java:37`
   ```java
   Observation.createNotStarted(OBSERVATION_NAME, observationRegistry)
           .lowCardinalityKeyValue("starport", ...)
           .lowCardinalityKeyValue("shipClass", ...)
           .observe(() -> { /* compute */ });
   ```
   Mierzy: in-memory calculation (stawka × godziny).

3. `reservations.route.plan` — `TradeRoutePlannerHttpAdapter.java:55-58`
   ```java
   return Observation.createNotStarted(OBSERVATION_NAME, observationRegistry)
           .lowCardinalityKeyValue("startStarport", ...)
           .lowCardinalityKeyValue("destinationStarport", ...)
           .observe(() -> callTradeRoutePlanner(command));
   ```
   Mierzy: synchroniczne HTTP wywołanie do trade-route-planner (ok. 50-2000ms zależnie od stanu zewnętrznego serwisu).

**Próg wizualny**: `red value = 2000ms` — overflow przy >2s.

**Czy poprawnie?** ✅ Bardzo wartościowy panel — to klasyczna **latency breakdown** dla głównego flow biznesowego. Jedna uwaga, taka sama jak w głównym dashboardzie: byłoby lepiej z p99 zamiast średniej. Histogramy są (Faza 1), więc `histogram_quantile()` zadziała.

**Subtelność z `route.plan`**: ta metryka mierzy **HTTP call** ze starport-registry, nie wewnętrzny czas trade-route-planner. Nie myl z panelem ID:22 — tamten mierzy „od strony serwera". Różnica = czas sieci + bufory + JSON serializacja. Zwykle różnica jest < 5ms, ale przy backpressure'ie potrafi być >100ms.

**Wartość biznesowa**: identyfikacja **wąskiego gardła** w flow rezerwacji. Jeśli klienci skarżą się "rezerwacja jest wolna", ten panel mówi gdzie. **Hold allocate skoczył** = problem z DB. **Fee calculate** = bug w tabeli stawek. **Route plan** = trade-route-planner ma problemy.

---

#### Panel ID:22 — "Route Planning Latency [p50 / p95 / p99]"

**Co pokazuje**: percentyle latencji **wewnętrznego planowania trasy** w trade-route-planner.

**Zapytania**:
```promql
1000 * histogram_quantile(0.50, sum by (le) (rate(routes_plan_seconds_bucket[$__rate_interval])))
1000 * histogram_quantile(0.95, sum by (le) (rate(routes_plan_seconds_bucket[$__rate_interval])))
1000 * histogram_quantile(0.99, sum by (le) (rate(routes_plan_seconds_bucket[$__rate_interval])))
```

**Metryka źródłowa**: `routes.plan` Observation w `PlanRouteService.java:63-67`:
```java
return Observation.createNotStarted(OBSERVATION_NAME, observationRegistry)
        .lowCardinalityKeyValue("originPortId", request.originPortId())
        .lowCardinalityKeyValue("destinationPortId", request.destinationPortId())
        .lowCardinalityKeyValue("shipClass", request.shipClass())
        .observe(() -> doPlan(request));
```

Histogram z bucketami SLO `10ms, 50ms, 100ms, 200ms, 500ms, 1s, 2s, 5s` (`application.yml`).

**Subtelność `sum by (le)` bez `job`**: bo to dotyczy **tylko jednego serwisu** (trade-route-planner). Sumowanie agreguje 2 instancje serwisu w jedną serię.

**Czy poprawnie?** ✅ Kanonicznie, identycznie jak panel 11 ale dla wewnętrznej metryki. Korelacja z panelem 21 / Route Plan: panel 21 mierzy „client-side", panel 22 mierzy „server-side". Różnica = network + JSON. Idealnie obie linie powinny prawie się pokrywać.

**Wartość biznesowa**: SLI dla **trade-route-planner**. Jego SLO: p99 < 2s. Jeśli wzrasta → zaczyna spowalniać starport-registry → wzrasta latencja `reservations.route.plan` w panelu 21 → wzrasta `http_server_requests_seconds` w starport-registry → wzrasta global `Avg Request Latency` w panelu 3. **Łańcuch przyczynowy** widoczny jeden po drugim — to siła dashboardu.

---

#### Panel ID:23 — "Circuit Breaker — trade-route-planner"

**Co pokazuje**: bieżący stan circuit breakera dla wywołań starport-registry → trade-route-planner.

**Zapytanie**:
```promql
resilience4j_circuitbreaker_state{name="trade-route-planner"}
```

**Metryka źródłowa**: `resilience4j.circuitbreaker.state` — built-in z biblioteki **`resilience4j-micrometer`**. Wartości:
- `CLOSED` (1) — normalny stan, ruch przepuszczany
- `OPEN` (1 dla state=OPEN) — wywołania krótkozwinięte, fallback wykonany
- `HALF_OPEN` (1) — test recovery

**Konfiguracja w `starport-registry/application.yml:189-199`**:
```yaml
resilience4j:
  circuitbreaker:
    instances:
      trade-route-planner:
        register-health-indicator: true
        failure-rate-threshold: 50      # 50% błędów = otwórz
        wait-duration-in-open-state: 10s
        sliding-window-size: 10
        minimum-number-of-calls: 5
```

**Kod Java** — `TradeRoutePlannerHttpAdapter.java:49`:
```java
@CircuitBreaker(name = "trade-route-planner", fallbackMethod = "routeUnavailableFallback")
public Route calculateRoute(ReserveBayCommand command) { … }
```

Adnotacja `@CircuitBreaker` z resilience4j-spring-boot owija metodę. Po przekroczeniu `failure-rate-threshold` w sliding window `OPEN` blokuje wywołania na 10s — fallback `routeUnavailableFallback` rzuca `RouteUnavailableException`, klient widzi `outcome=route_unavailable`.

**Pułapka panelu**: metryka emituje **3 serie** jednocześnie — po jednej dla każdego stanu, z wartością 0 lub 1. Jeśli `legendFormat="{{state}}"` — zobaczysz 3 linie. Tylko jedna jest aktualna; pozostałe = 0. Reduce options w stat panel pokaże tylko ostatnią — może być myląca.

**Czy poprawnie?** ⚠️ Działa, ale stat panel z 3 seriami jest mniej czytelny niż state panel z mappingiem (CLOSED → zielone, OPEN → czerwone). Można poprawić używając `mappings` w fieldConfig.

**Wartość biznesowa**: kluczowy sygnał ochrony przed cascading failures. Jeśli circuit OPEN przez 10s = kompletna blokada flow rezerwacji. Wymaga natychmiastowego pinga na page.

---

#### Panel ID:24 — "Circuit Breaker — Failed Calls"

**Co pokazuje**: liczbę wywołań circuit breakera oznaczonych jako `failed` w okienku.

**Zapytanie**:
```promql
sum(increase(resilience4j_circuitbreaker_calls_seconds_count{kind="failed", name="trade-route-planner"}[$__rate_interval]))
```

**Metryka źródłowa**: `resilience4j.circuitbreaker.calls` — Timer per kombinacja `(name, kind)`. `kind` = `successful | failed | ignored`.

**Jak działa zapytanie**: `increase()` na `_count` = ile próbek histogramu doszło w okresie. Filter `kind=failed` selekcjonuje niepoprawne wywołania.

**Czy poprawnie?** ✅ Sensowne uzupełnienie panelu 23 (state). State pokazuje **stan**, ten panel pokazuje **co go zmieniło**. Wzrost failed → zaraz po nim (lub równocześnie) circuit OPEN.

**Wartość biznesowa**: post-mortem diagnostyki. „Dlaczego circuit pękł?" → 5 failed calls w sliding window 10. Tutaj widać kiedy poszły.

---

### Sekcja: Async Event Tracing — Outbox / Inbox / Kafka (3 panele)

#### Panel ID:31 — "Outbox Poll + Publish Duration"

**Co pokazuje**: średnia latencja jednego cyklu `pollAndPublish()` w `InboxPublisher`, per outcome.

**Zapytanie**:
```promql
1000 * sum by (outcome) (rate(reservations_inbox_poll_duration_seconds_sum[$__rate_interval]))
     / clamp_min(sum by (outcome) (rate(reservations_inbox_poll_duration_seconds_count[$__rate_interval])), 1e-9)
```

**Metryka źródłowa**: `reservations.inbox.poll.duration` — Timer rejestrowany ręcznie (nie Observation) w `InboxPublisher.java:86-90`:
```java
sample.stop(Timer.builder(METRIC_POLL_DURATION)
        .description("Outbox poll+publish batch duration")
        .tag("outcome", outcome)        // success | empty | error
        .tag("batchSize", String.valueOf(actualBatchSize))
        .register(meterRegistry));
```

`outcome`:
- `success` — odebrano batch, każdy event wysłany do Kafki
- `empty` — `lockBatchPending()` zwrócił pustą listę (brak PENDING eventów)
- `error` — wyjątek lub jakiekolwiek `processSingleEvent` zwróciło false

**Pułapka cardinality**: tag `batchSize` = `String.valueOf(actualBatchSize)`. Wartość zmienia się 0..200 (zależnie od batch). To **wysoka cardinality** — każda unikalna wartość batchSize tworzy osobną serię. Zalecam usunąć ten tag (sumować po wszystkich) lub zamienić na bucket'y („low/medium/high"). Plan redesign §6.5 zwraca uwagę na to ryzyko, ale konkretnie ten tag nie został jeszcze poprawiony.

**Czy poprawnie?** ⚠️ Działa, ale `sum by (outcome) (X / Y)` jest **matematycznie poprawne** tylko wtedy, gdy każdy outcome jest agregowany z **osobnym mianownikiem** — dokładnie to robi PromQL. ✓.

**Wartość biznesowa**: monitoring outboxa. Wysoki czas `success` = poller pracuje, ale powoli (DB slow, Kafka slow). `error` rosnący w czasie = poller w pętli błędów (np. circuit otwarty na Kafce, network problem).

---

#### Panel ID:32 — "Outbox Batch Size (events/poll)"

**Co pokazuje**: średnia liczba eventów odebranych w jednym cyklu poll.

**Zapytanie**:
```promql
reservations_inbox_poll_batch_size_events_sum / clamp_min(reservations_inbox_poll_batch_size_events_count, 1)
```

**Metryka źródłowa**: `reservations.inbox.poll.batch.size` — DistributionSummary w `InboxPublisher.java:69-73`:
```java
DistributionSummary.builder(METRIC_BATCH_SIZE)
        .description("Outbox batch size fetched during poll")
        .baseUnit("events")
        .register(meterRegistry)
        .record(batch.size());
```

**Czy poprawnie?** ❌ **Brak `rate()`**! Zapytanie dzieli kumulatywne wartości od startu serwisu — pokazuje **historyczną średnią**, nie obecną. Powinno być:
```promql
rate(reservations_inbox_poll_batch_size_events_sum[5m])
/ clamp_min(rate(reservations_inbox_poll_batch_size_events_count[5m]), 1)
```

Wzór bez rate'a daje sensowne wyniki tylko jeśli batch size jest stały w czasie (co prawdopodobnie jest, ale i tak to anty-wzorzec).

Druga uwaga: `clamp_min(_count, 1)` — może zaniżyć średnią przy bardzo małej liczbie próbek (jeden batch o size=200 + zero counts → `clamp_min` zmienia 0 na 1 → średnia = 200 zamiast `0/0=NaN`). Lepsze: `clamp_min(_count, 1e-9)` — ten sam efekt zabezpieczenia, bez kreatywnej arytmetyki.

**Wartość biznesowa**: czy poller wybiera duże batch'e? Jeśli zawsze 1 = poller jest przeciążony pojedynczymi eventami (poll-interval za rzadki). Jeśli zawsze 200 (max) = batch_size jest za mały, eventów więcej niż poller wyciąga = saturacja.

---

#### Panel ID:33 — "Dead Letter Events (permanently failed)"

**Co pokazuje**: stat — łączna liczba eventów, które wpadły do dead letter (nieudane po 10 próbach), per typ eventu.

**Zapytanie**:
```promql
sum by (eventType) (reservations_outbox_dead_letter_total)
```

**Metryka źródłowa**: `reservations.outbox.dead.letter` Counter w `InboxPublisher.java:144-149`:
```java
if (outboxEvent.getAttempts() >= maxAttempts) {
    outboxEvent.markFailed();
    Counter.builder(METRIC_DEAD_LETTER)
            .tag("eventType", outboxEvent.getEventType())
            .tag("binding", outboxEvent.getBinding())
            .register(meterRegistry)
            .increment();
}
```

**Brak `rate()`** — ale tutaj **świadomie**. Stat panel ma pokazać **łączną liczbę kiedykolwiek**, nie chwilową szybkość. To jest licznik **utraconych eventów**.

**Czy poprawnie?** ✅ Słuszne użycie counterów + sumy dla stat panelu.

Próg `> 0 = problem` — natychmiastowe page'owanie. To utrata danych.

**Wartość biznesowa**: integralność systemu. Każde 1 = jeden event, który nie dojechał do Kafki, czyli telemetry-pipeline go nie wzbogaci, downstream consumers nie zobaczą rezerwacji. Można potem ręcznie poszukać w `event_outbox` po `status=FAILED` i albo replay'ować, albo obsłużyć ręcznie.

---

### Sekcja: Trace Explorer — Tempo (2 panele)

#### Panel ID:41 — "Trace Search — Recent Traces"

**Co pokazuje**: interaktywny panel **Trace Search** — można filtrować po duration, status, service, span name. Klik w trace → otwiera waterfall view.

**Datasource**: **Tempo** (nie Prometheus!).

**Query type**: `traceqlSearch` — natywna składnia Tempo TraceQL. Bez `expr`.

**Co Tempo widzi**: każdy span wysłany przez aplikacje przez OTLP (port 4318). Spany są indeksowane po `traceId`, `service.name`, `span.name`, `status_code`, `duration` itp.

**Bez TraceQL, w UI**: select Service → select Span Name → klik „Run" → lista trace'ów posortowana po czasie. Klik trace → waterfall.

**Czy poprawnie?** ✅ Standardowy panel typu `traces` w Grafanie 10+. Nie ma tutaj „PromQL" — to po prostu interface do bazy spanów.

**Wartość biznesowa**: **drążenie pojedynczego błędu**. Alarm pokaże, że p99 = 5s. Chcesz zobaczyć **konkretny request** który tyle trwał. W panel 41 → filtr `duration > 4s` → klik → widzisz waterfall z dokładnością do milisekundy: 50ms na hold, 5ms na fee, **4900ms na route-plan**. Wąskie gardło zidentyfikowane bez czytania kodu.

---

#### Panel ID:42 — "Service Graph — Node Map"

**Co pokazuje**: mapa serwisów + krawędzi z ruchem między nimi.

**Datasource**: Tempo, query type `serviceMap`.

**Bazuje na**: `traces_service_graph_request_total{client, server}` — Tempo metrics-generator z procesem `service-graphs`. Wymaga aktywacji w `tempo.yml`:
```yaml
overrides:
  metrics_generator_processors: [service-graphs, span-metrics]
```

**Jak Tempo go buduje**: dla każdego trace'u Tempo paruje **client span** (span typu CLIENT z atrybutem `peer.service` lub `server.address`) ze **server span** (span typu SERVER w innym serwisie z tym samym `traceId` i timestampem). Para = krawędź `client → server`.

**Co zobaczysz w naszym setupie**:
- `api-gateway → starport-registry`
- `starport-registry → trade-route-planner`
- `starport-registry → kafka` (Tempo widzi outbox `reservations.outbox.append` jako CLIENT span do "kafka")
- `telemetry-pipeline → kafka` (consumer side)
- ewentualnie `eureka → *` jeżeli Eureka heartbeats też produkują spany (zwykle nie)

**Czy poprawnie?** ✅ Kanoniczne. Działa out-of-the-box.

Jedno ograniczenie: graph pokazuje **co Tempo widzi w spanach**, nie zawsze prawdziwe zależności. Jeśli np. starport-registry komunikuje się z DB postgres, to nie zobaczysz tej krawędzi (bo nie generujemy spanów `org.postgresql.*`).

**Wartość biznesowa**: **architektura runtime'owa**. Zwykle dokumentacja architektury się dezaktualizuje; service graph zawsze aktualny — pokazuje to, **co naprawdę** wywołuje co. Świetne onboarding tool dla nowych developerów.

---

### Sekcja: Span Metrics — Generated from Traces (4 panele)

Wszystkie 4 panele opierają się na metrykach **wygenerowanych przez Tempo** z trace'ów i wysłanych do Prometheusa przez `remote_write`.

#### Panel ID:51 — "Span Rate by Service (from Tempo span-metrics)"

**Co pokazuje**: rate spanów per serwis. **Inny niż HTTP rate** — liczy każdy span (HTTP, DB, Kafka, custom Observation).

**Zapytanie**:
```promql
sum by (service) (rate(traces_spanmetrics_calls_total[$__rate_interval]))
```

**Metryka źródłowa**: `traces_spanmetrics_calls_total` — emit z Tempo `span-metrics` procesora. Labele: `service`, `span_name`, `span_kind`, `status_code`.

**Co Tempo robi**: dla każdego spana inkrementuje counter z labelami z atrybutów spana. Czyli `service` = `service.name` resource attribute (u nas `starport-registry`, etc.), `span_name` = nazwa Observation lub HTTP route.

**Czy poprawnie?** ✅ Dobrze użyte. **Pułapka**: rate ten **NIE jest** rate'em HTTP — w naszym kodzie każdy reservation flow generuje:
- 1 span HTTP server (request do controllera)
- 3+ spany Observations (hold, fee, confirm, route, outbox)

Czyli `traces_spanmetrics_calls_total` w starport-registry będzie ~5× większe niż `http_server_requests_seconds_count`. To **feature**, nie bug — pokazuje pełny work serwisu, nie tylko external API.

**Wartość biznesowa**: pokazuje **nakład pracy całego serwisu** (HTTP + Kafka + DB + internal processing), inaczej niż HTTP rate (tylko external). Kombinacja obu daje pełny obraz.

---

#### Panel ID:52 — "Span Latency p95 by Service (from Tempo span-metrics)"

**Co pokazuje**: p95 latencji spanów per serwis.

**Zapytanie**:
```promql
1000 * histogram_quantile(0.95, sum by (le, service) (rate(traces_spanmetrics_latency_bucket[$__rate_interval])))
```

**Metryka źródłowa**: `traces_spanmetrics_latency_bucket` — Tempo emituje histogram latencji spanów. Domyślne buckety Tempo (~14 buckety: 2ms, 4ms, ..., 4s). Mniej granularne niż Micrometer histogram, ale wystarczające dla overview.

**Czy poprawnie?** ✅ Działa. Drobna uwaga: tytuł panelu mówi „p95" ale tylko jeden percentyl — bez p99 / p50. Można rozbudować analogicznie do panelu 11.

**Wartość biznesowa**: alternatywa wobec `http_server_requests_seconds`. **Uwzględnia spany wewnętrzne** — np. jeśli `reservations.confirm` Observation jest powolny, to pokażą p95 dla starport-registry. Zwykły `http_server_requests` widzi tylko sumę.

---

#### Panel ID:53 — "Service Graph — Request Rate (client → server)"

**Co pokazuje**: rate wywołań i błędów per krawędź grafu (client → server).

**Zapytania**:
```promql
sum by (client, server) (rate(traces_service_graph_request_total[$__rate_interval]))
sum by (client, server) (rate(traces_service_graph_request_failed_total[$__rate_interval]))
```

**Metryki źródłowe**: emit z Tempo `service-graphs` procesora. Każda krawędź → counter `request_total` + osobny `request_failed_total` (gdy jeden ze sparowanych spanów ma `status_code=ERROR`).

**Czy poprawnie?** ✅ Eleganckie. To jest **RED dla każdej krawędzi grafu** — bez instrumentacji, bez kodu, automatycznie ze spanów.

**Wartość biznesowa**: top-down diagnostyka. „Który link jest najsłabszy?" → posortuj po failed rate. Standalone monitoring każdej zależności w systemie.

---

#### Panel ID:54 — "Service Graph — Latency p95 (client → server)"

**Co pokazuje**: p95 latencji per krawędź (po stronie servera).

**Zapytanie**:
```promql
1000 * histogram_quantile(0.95, sum by (le, client, server) (rate(traces_service_graph_request_server_seconds_bucket[$__rate_interval])))
```

**Metryka źródłowa**: `traces_service_graph_request_server_seconds_bucket` — emit Tempo. Ważne: **server-side latency**, nie client-side. Dlatego nie zawiera czasu sieci, jest węższa niż klient widzi.

Dla porównania client-side: `traces_service_graph_request_client_seconds_*` — to istnieje (panel mógłby je dorzucić jako drugą serię), ale dashboard używa tylko server.

**Czy poprawnie?** ✅ Wzorcowo. Drobne ulepszenie: dorzucić `_client_seconds` jako drugą linię żeby zobaczyć **różnicę = czas sieci/buffer** między usługami.

**Wartość biznesowa**: SLI dla każdej zależności. Konkretnie: „p95 dla `starport-registry → trade-route-planner` < 2s" — to nasz SLO. Można alertować na przekroczenie per krawędź.

---

### Sekcja: Correlated Logs — Loki (2 panele)

#### Panel ID:61 — "Error & Warning Logs (all services)"

**Co pokazuje**: streaming logów ERROR / WARN z wszystkich aplikacyjnych serwisów.

**Datasource**: **Loki**.

**Zapytanie LogQL**:
```logql
{service=~"starport-registry.*|trade-route-planner.*|telemetry-pipeline.*"} |~ "ERROR|WARN"
```

**Składnia**:
- `{service=~"..."}` — selektor po labelu `service` (wzorzec regex)
- `|~ "ERROR|WARN"` — line filter regex (mecze tekst zawierający ERROR lub WARN)

**Skąd Loki ma logi**: Promtail (agent) tail'uje `docker logs` z każdego kontenera i pushuje do Loki z labelami `service` (z nazwy kontenera) + `traceId` (jeżeli wykryje pattern). Konfiguracja Promtail w `infra/docker/promtail/`.

**Krytyczne**: format logów aplikacji ma pattern `[<service>,<traceId>,<spanId>]` (z `application.yml` → `logging.pattern.correlation`). Loki datasource ma `derivedFields` z regex'em wyciągającym traceId i robiącym z niego klikalny link do Tempo:

```yaml
derivedFields:
  - datasourceUid: tempo
    matcherRegex: "\\[\\w[\\w-]*,(\\w{16,32}),\\w+\\]"
    name: traceId
    url: "$${__value.raw}"
```

Klik w log line → `traceId` highlightowany jako pillbox → klik w pill → otwiera trace w Tempo w prawym panelu.

**Czy poprawnie?** ✅ Klasyczna trójca observability: **metric → log → trace** correlation. Najlepsze co możesz mieć w jednej Grafanie.

Drobne ulepszenie: regex `|~ "ERROR|WARN"` matche'uje też słowa „ERROR" w treści, np. „retrying after ERROR" w INFO log. Lepsze:
```logql
| json level | level=~"ERROR|WARN"
```
…jeżeli logi są JSON (u nas są tekstowe, więc obecny regex jest OK).

**Wartość biznesowa**: triage operacyjny. Centralne miejsce do "co poszło nie tak w ostatniej godzinie". Bezpośrednie linki do tracerów = **0 sekund context switch**.

---

#### Panel ID:62 — "All Logs — Live Tail"

**Co pokazuje**: tail wszystkich logów aplikacji (bez filtrów ERROR/WARN).

**Zapytanie LogQL**:
```logql
{service=~"starport-registry.*|trade-route-planner.*|telemetry-pipeline.*"}
```

**Czy poprawnie?** ✅ Ta sama mechanika co panel 61. „Live tail" w UI Grafany = real-time stream nowych logów.

**Wartość biznesowa**: debugging. Powtórzenie bug'a → live tail pokazuje co serwis robi w tym momencie. Z linków do trace'ów masz pełną widoczność krok po kroku.

---

## Część III — Wzorce w panelach

### Wzorce poprawne

1. **`histogram_quantile(p, sum by (le, ...) (rate(_bucket[5m])))`** — kanoniczny PromQL dla percentyli. **Zawsze `le` w `by`**, zawsze `rate()`, zawsze histogram (nie Summary).
2. **`100 * sum(rate(_count{filter}[5m])) / sum(rate(_count[5m]))`** — kanoniczna konwersja licznika na procent kategorii.
3. **`clamp_min(X, 1e-9)`** — zabezpieczenie przed NaN. Konsekwentnie użyte.
4. **Datasource explicite na każdym targecie** — w naszym dashboardzie per-target uid Prometheus = `PBFA97CFB590B2093`. Nie miesza się z Tempo/Loki.
5. **`derivedFields` w Loki dla traceId** — most między logami a trace'ami. To jest jedna z najpotężniejszych funkcji Grafany dla observability.

### Wzorce do poprawy

1. **Brak `rate()` na `_sum / _count`** — panel 32 (Outbox Batch Size). Pokazuje historyczną średnią, nie obecną.
2. **Wysoka cardinality tagu `batchSize`** w `reservations.inbox.poll.duration` — każda możliwa wartość batch (0..200) tworzy osobną serię. Z planem RED §6.5 zostało zwrócone uwagę, ale ten konkretny tag nie został usunięty.
3. **Domyślne buckety Micrometera dla `http.server.requests`** — ~67 buckety per endpoint. Plan §9.3 sugeruje custom paletę 8-bucket'ową. Nie zrobione.
4. **`Circuit Breaker — state` panel z 3 seriami** — wartość 0/1 per stan, mylące w stat panelu. Lepiej użyć `mappings` field w fieldConfig dla przejrzystego "CLOSED/OPEN/HALF_OPEN".
5. **Brak korelacji client-side vs server-side latencji** w panelu 54. Dwie linie (client + server) pokazałyby czas sieci.
6. **`telemetry-pipeline` często zachowuje się jako black box** — dashboard nie zawiera dedicated panelu pipeline'owej latencji (tej `telemetry.pipeline.process` Observation z Fazy 2 redesign'u). Można dorzucić w przyszłości.

---

## Część IV — Co warto zrobić dalej

1. Naprawić panel 32 (Outbox Batch Size) — dodać `rate()`.
2. Usunąć tag `batchSize` z `reservations.inbox.poll.duration` Timera (lub zamienić na bucket'y).
3. Zastąpić domyślne buckety `http.server.requests` własnymi SLO bucketami (`50ms, 100ms, 250ms, 500ms, 1s, 2.5s, 5s, 10s`) — 15× redukcja cardinality.
4. Panel 23 — użyć `mappings` żeby wyświetlić nazwę stanu zamiast 0/1.
5. Panel 54 — dodać client-side latency jako drugą linię.
6. Dodać panel z `telemetry.pipeline.process` (Phase 2 RED) — auto-tworzony Timer dla całego pipeline.
7. Dodać alerty oparte na progach z dashboardu:
   - `histogram_quantile(0.99, rate(http_server_requests_seconds_bucket[5m])) > 1` → warning
   - HTTP error rate (5xx) > 1% → warning, > 5% → page
   - `resilience4j_circuitbreaker_state{state="OPEN"} == 1` → page natychmiast
   - `reservations_outbox_dead_letter_total` > 0 → page natychmiast

---

## Część V — Słownik szybki

| Termin | Co znaczy |
|---|---|
| **Trace** | Pełna ścieżka requestu przez wiele serwisów, identyfikowana po `traceId`. |
| **Span** | Pojedyncza operacja w obrębie trace'a. Ma `spanId`, `parentSpanId`, czas, status, atrybuty. |
| **Span kind** | `SERVER` (przyjęcie HTTP), `CLIENT` (wywołanie HTTP), `PRODUCER` (wysłanie do Kafki), `CONSUMER` (odebranie z Kafki), `INTERNAL` (wewnętrzna operacja). |
| **OTLP** | OpenTelemetry Line Protocol. Standard wysyłki spanów (HTTP/gRPC). U nas Tempo na 4318 (HTTP). |
| **Sampling** | Procent trace'ów wysyłanych do Tempo. 100% w dev, 1-10% w prod. |
| **Tempo** | Backend tracingowy Grafany. Trzyma spany, indeksuje po `traceId`, generuje metryki ze spanów. |
| **Loki** | Backend logów Grafany. Trzyma logi, indeksuje po labelach (`service`, `instance`). |
| **Promtail** | Agent Loki — tail'uje `docker logs` i pushuje do Loki. |
| **Span-metrics** | Procesor Tempo → emituje `traces_spanmetrics_*` do Prometheusa. RED per service automatycznie. |
| **Service-graphs** | Procesor Tempo → emituje `traces_service_graph_*`. Krawędzie grafu zależności. |
| **Derived field** | Funkcja Loki — regex w log line wyciąga wartość (np. traceId) i robi z niej link. |
| **W3C Trace Context** | Standard nagłówków HTTP do propagacji `traceId/spanId` (`traceparent`). |
| **MDC** | Mapped Diagnostic Context — slf4j feature do automatycznego wstrzykiwania `traceId/spanId` do logów. |
| **`logging.pattern.correlation`** | Spring Boot property — ustawia format prefiksu logu. U nas `[%X{traceId},%X{spanId}]`. |
| **`management.tracing.sampling.probability`** | Spring Boot property — % trace'ów do exportowania. |
| **`metrics_generator_processors`** | Tempo config — które procesory generują metryki ze spanów. |
| **`remote_write`** | Endpoint Prometheusa do przyjmowania metryk pushowanych z innych źródeł (Tempo, Prometheus Agent). |
| **Waterfall view** | UI Tempo — prezentacja spanów w pionowej osi czasu, hierarchia parent/child. |
| **TraceQL** | Query language Tempo, analog PromQL ale dla spanów. |
| **LogQL** | Query language Loki, analog PromQL ale dla logów. |

---

*Dokument utrzymywany ręcznie. Po każdej zmianie dashboardu / konfiguracji Tempo/Loki warto go zaktualizować.*
