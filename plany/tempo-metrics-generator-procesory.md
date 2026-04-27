# Tempo `metrics_generator` — procesory i co każdy z nich robi

Dokument odpowiada na dwa pytania, które padły w trakcie pracy nad observability MicroservicesFleet:

1. **Czy w Tempo mamy różne procesory? Jeden czy wiele?**
2. **Czym dokładnie jest zmiana w `tempo.yml` polegająca na włączeniu procesora `local-blocks`?**

Krótka odpowiedź: tak, **w Tempo `metrics_generator` jest pluginową architekturą — można włączyć kilka procesorów równolegle, każdy robi co innego**. W naszym setupie używamy trzech: `service-graphs`, `span-metrics` i `local-blocks` (ten ostatni dodany właśnie w trakcie tych prac). Każdy z nich obserwuje ten sam strumień spanów, ale generuje inny rodzaj output'u.

Poniżej szczegóły z przykładami.

---

## 1. Gdzie w architekturze Tempo siedzi `metrics_generator`

Tempo to nie monolit — to mikroserwisowa architektura sama w sobie. Składa się z kilku komponentów, które w naszym Docker Compose są zlepione w jedno (single-binary mode):

```
   Aplikacje (Spring Boot + OTLP exporter)
              │
              ▼   port 4318 OTLP HTTP
   ┌──────────────────┐
   │   distributor    │ — przyjmuje spany, waliduje, replikuje
   └────────┬─────────┘
            │
       ┌────┴─────┐                    ┌──────────────────────┐
       ▼          ▼                    │                      │
   ┌────────┐ ┌──────────────────┐ ◄───┤ metrics_generator    │
   │ingester│ │metrics_generator │     │  (procesory wewnątrz)│
   └───┬────┘ └────────┬─────────┘     └──────────────────────┘
       │               │
       │               │ remote_write
       ▼               ▼
   ┌────────┐    ┌─────────────┐
   │storage │    │ Prometheus  │
   │(blocks)│    └─────────────┘
   └────────┘
```

**`distributor`** przyjmuje spany przez OTLP. Każdy span fan-out'uje równolegle do:
- **`ingester`** — który zapisuje go do bloków na dysku (długoterminowy storage do Trace Search)
- **`metrics_generator`** — który **przetwarza** spana przez **łańcuch procesorów**, każdy generujący swój specyficzny output

Procesor != pełen serwis. To plugin wewnątrz `metrics_generator`. Każdy procesor:
1. Dostaje strumień spanów
2. Robi z nimi coś specyficznego (liczy, paruje, buforuje)
3. Produkuje wynik (metryki przez `remote_write` LUB lokalne bloki do TraceQL queries)

---

## 2. Wszystkie procesory dostępne w Tempo (stan na wersję 2.4)

Tempo ma trzy „kanoniczne" procesory:

| Procesor | Co robi | Wynik | Stabilność |
|---|---|---|---|
| `service-graphs` | paruje client/server spany w krawędzie grafu zależności | metryki Prometheus przez `remote_write` | stabilny od ~2.0 |
| `span-metrics` | dla każdego spana liczy rate + histogram latencji | metryki Prometheus przez `remote_write` | stabilny od ~2.0 |
| `local-blocks` | buforuje świeże spany w lokalnym formacie zoptymalizowanym pod TraceQL queries | TraceQL metrics queries dostępne w Grafanie | **eksperymentalny w 2.4**, stabilny od 2.5+ |

W Tempo 2.5+ pojawiają się dodatkowe (np. `host-info-processor`), ale do MicroservicesFleet ich nie używamy.

**Procesory są addytywne** — można włączyć dowolną kombinację (1, 2 lub wszystkie 3). Nie ma konfliktów; każdy procesor patrzy na ten sam strumień spanów niezależnie.

Aktywuje się je przez `overrides.metrics_generator_processors`, np.:
```yaml
overrides:
  metrics_generator_processors: [service-graphs, span-metrics, local-blocks]
```

To pole musi tu być **zawsze** (nawet jeśli config procesora podasz wyżej w `metrics_generator.processor.X`) — Tempo wymaga jawnej aktywacji per-tenant. U nas mamy 1 tenant (single-tenant deployment), więc lista globalnie obowiązuje.

---

## 3. Procesor `service-graphs` — graf zależności

### Co robi

Czyta każdy span i sprawdza, czy ma „parę". W tracingu typowe wywołanie HTTP rodzi:
- po stronie klienta — span typu `CLIENT` z atrybutem `peer.service` lub `server.address`
- po stronie serwera — span typu `SERVER` w innym serwisie, z tym samym `traceId`

Procesor paruje te dwa spany (po `traceId` + chronologii) i emituje **metrykę krawędzi grafu**: „A wywołał B, X razy, średnio przez Y sekund".

### Wynik metryczny w Prometheusie

```
traces_service_graph_request_total{client="api-gateway", server="starport-registry"}              42
traces_service_graph_request_failed_total{client="api-gateway", server="starport-registry"}        2
traces_service_graph_request_server_seconds_bucket{client=…, server=…, le="0.5"}                 ...
traces_service_graph_request_client_seconds_bucket{client=…, server=…, le="0.5"}                 ...
```

Server seconds = czas widziany u serwera. Client seconds = czas widziany u klienta (zawiera czas sieci). Różnica = network latency. To jest **kanoniczna metryka inter-service RED** bez konieczności instrumentacji w kodzie.

### Przykład z naszego MicroservicesFleet

Po wysłaniu rezerwacji:
- `traces_service_graph_request_total{client="api-gateway", server="starport-registry"}` → +1
- `traces_service_graph_request_total{client="starport-registry", server="trade-route-planner"}` → +1
- `traces_service_graph_request_total{client="starport-registry", server="kafka"}` → +1 (outbox publisher → Kafka, bo Kind=PRODUCER w `OutboxAppender`)

Z tego Tempo + Grafana buduje **service graph (mapę node'ów)** widoczną w panelu 42 dashboardu „Distributed Tracing".

### Konfiguracja w `tempo.yml`

Procesor jest **default'owy** — nie potrzeba dodatkowej konfiguracji. Wystarczy aktywować:
```yaml
overrides:
  metrics_generator_processors: [service-graphs]
```

Można dostroić, np. ustawić `wait` (jak długo Tempo czeka na sparowanie spanów po stronie serwera, zanim uzna trace za niesparowany):
```yaml
metrics_generator:
  processor:
    service_graphs:
      wait: 10s
      max_items: 10000
      workers: 10
```

W naszym setupie używamy domyślnych — wystarczają.

---

## 4. Procesor `span-metrics` — RED ze spanów

### Co robi

Dla każdego spana wystawia counter wywołań i histogram latencji, z labelami pochodzącymi z atrybutów spana (`service.name`, `span.name`, `span.kind`, `status_code`).

### Wynik metryczny

```
traces_spanmetrics_calls_total{service="starport-registry", span_name="POST /reservations", span_kind="SPAN_KIND_SERVER", status_code="STATUS_CODE_OK"}    150
traces_spanmetrics_latency_bucket{service=…, span_name=…, span_kind=…, status_code=…, le="0.5"}                                                              140
traces_spanmetrics_latency_count{...}                                                                                                                       150
traces_spanmetrics_latency_sum{...}                                                                                                                          25.3
```

To jest **RED dla każdego spana** — nie tylko HTTP serwer, ale też CLIENT spany, INTERNAL spany (Observations w Java), PRODUCER/CONSUMER spany (Kafka) — wszystko, co aplikacja wyemitowała przez OTLP.

### Subtelność: rozumienie label `span_name`

Dla HTTP server spana `span_name` to typowo path-template (np. `POST /api/v1/starports/{code}/reservations`). Dla Observations w Java to nazwa Observation (np. `reservations.confirm`, `reservations.fees.calculate`).

W praktyce dla starport-registry zobaczysz zapis w stylu:
```
traces_spanmetrics_calls_total{service="starport-registry", span_name="POST /api/v1/starports/{code}/reservations"}    50
traces_spanmetrics_calls_total{service="starport-registry", span_name="reservations.confirm"}                          50
traces_spanmetrics_calls_total{service="starport-registry", span_name="reservations.fees.calculate"}                   50
traces_spanmetrics_calls_total{service="starport-registry", span_name="reservations.outbox.append"}                    50
... 
```

To zwykle jest bardziej szczegółowy obraz niż `http_server_requests_seconds_*` — pokazuje pracę WEWNĄTRZ serwisu (Observations), nie tylko zewnętrzne HTTP requesty.

### Custom dimensions (dla zaawansowanych)

Można skonfigurować procesor, by jako label dorzucał wartość z dowolnego atrybutu spana:
```yaml
metrics_generator:
  processor:
    span_metrics:
      dimensions:
        - business.operation     # dorzuci label business_operation, jeśli span ma taki atrybut
        - http.route
```

Po tym dodaniu, jeśli aplikacja ustawi `Span.current().setAttribute("business.operation", "reservation.create")`, w Prometheusie pojawi się:
```
traces_spanmetrics_calls_total{..., business_operation="reservation.create"}    ...
```

To było wymienione jako „Ścieżka B" w analizie e2e — sposób na dodanie agregowalnych metryk per business operation BEZ pisania własnych Counterów w kodzie.

W naszym setupie nie używamy custom dimensions (na razie zostają default).

### Konfiguracja u nas

```yaml
overrides:
  metrics_generator_processors: [..., span-metrics]
```

Z domyślną konfiguracją. Buckety histogramu wyboru przez Tempo (typowo 14: 2ms, 4ms, …, 4s).

---

## 5. Procesor `local-blocks` — interaktywne TraceQL Metrics

To jest **nowość**, którą dodaliśmy w commicie `2f19349`. Reszta tego dokumentu skupia się na nim.

### Co robi (mechanicznie)

Buforuje świeże spany na dysku w specjalnym, lokalnym formacie zoptymalizowanym pod **agregacje TraceQL queries**. Trzyma rolling window kilkunastu minut do godziny ostatnich danych.

To NIE są te same bloki, w których Tempo trzyma trace'y do Trace Search:
- **`storage.trace.local.path`** = długoterminowe archiwum (godziny, dni). Indexed by `traceId`. Optymalizowane pod „daj mi konkretny trace".
- **`metrics_generator.traces_storage.path`** = krótkoterminowy bufor (minuty). Optymalizowany pod „policz mi rate / quantile / count z ostatnich N minut spanów".

To są dwa różne magazyny dla dwóch różnych use case'ów.

### Co staje się możliwe po włączeniu

**TraceQL Metrics Queries** w Grafanie (datasource Tempo, queryType `traceql`):

```traceql
{ resource.service.name = "api-gateway" } | rate()
```
→ liczba spanów na sekundę dla api-gateway, w czasie

```traceql
{ resource.service.name = "starport-registry" && kind = server } | rate() by (name)
```
→ rate per nazwa endpointu, w starport-registry, tylko server spany

```traceql
{ resource.service.name = "trade-route-planner" } | quantile_over_time(duration, 0.99) by (name)
```
→ p99 latencji per nazwa spana, w trade-route-planner

```traceql
{ resource.service.name = "starport-registry" && status = error } | count_over_time()
```
→ liczba spanów z błędem w czasie

```traceql
{ name = "reservations.fees.calculate" && duration > 100ms } | rate()
```
→ rate „wolnych" wywołań fee calculator (> 100ms)

To są zapytania, których **nie da się** zrealizować przez Prometheus, bo Prometheus widzi tylko zagregowane metryki — nie pojedyncze spany.

### Konfiguracja u nas (rozszyfrowana)

```yaml
metrics_generator:
  processor:
    local_blocks:
      filter_server_spans_from_root_span: true     # ← (A)
      flush_to_storage: true                        # ← (B)
  traces_storage:
    path: /tmp/tempo/generator/traces              # ← (C)
overrides:
  metrics_generator_processors: [service-graphs, span-metrics, local-blocks]   # ← (D)
```

#### (A) `filter_server_spans_from_root_span: true`

**Najważniejszy flag**. Bez niego TraceQL `rate()` po `kind=server` policzy **każdy** server span w trace. Dla rezerwacji to typowo:

```
trace abc123:
  ├─ HTTP server span (api-gateway)            kind=SERVER
  │   └─ HTTP client span (gateway → starport)  kind=CLIENT
  │       └─ HTTP server span (starport-registry) kind=SERVER  ← drugi server!
  │           ├─ Observation reservations.hold.allocate  kind=INTERNAL
  │           ├─ Observation reservations.fees.calculate kind=INTERNAL
  │           └─ HTTP client span (starport → planner)   kind=CLIENT
  │               └─ HTTP server span (trade-route-planner) kind=SERVER  ← trzeci server!
  └─ ... (potem outbox spany)
```

Bez flagi: `{ kind = server } | rate()` zwróci **3 server spany dla 1 rezerwacji** = inflated 3×.

Z flagiem `true`: tylko **najwyżej położony server span** w trace się liczy. Dla rezerwacji = api-gateway. Czyli „1 rezerwacja = 1 punkt", co ma sens biznesowy.

To jest jedyna rzecz, której zazwyczaj się chce — chyba że celowo chcesz mierzyć każdy hop.

#### (B) `flush_to_storage: true`

Wymusza okresowe zapisanie buforowanych bloków na dysk. Bez tego flagu bufor jest tylko w RAM-ie i znika przy każdym restarcie Tempo.

W produkcji: zalecane `true` (przeżywa restart). W dev environmencie: wybór mniej istotny, ale zostawiamy `true` dla spójności.

Drobny koszt: dodatkowy I/O. Pomijalny przy naszym ruchu.

#### (C) `traces_storage.path: /tmp/tempo/generator/traces`

Gdzie fizycznie leżą lokalne bloki. **Kluczowa konwencja**: musi być inny katalog niż:
- `storage.trace.local.path` (`/tmp/tempo/blocks` — długoterminowe trace storage)
- `metrics_generator.storage.path` (`/tmp/tempo/generator/wal` — WAL Tempo `remote_write`)

Mieszanie ich = corruption.

W Docker Compose mountujemy `/tmp/tempo/` jako volume (lub anonymous mount), więc oba katalogi są w tym samym mount'e, ale to OK — to różne podkatalogi.

#### (D) `overrides.metrics_generator_processors`

Lista aktywowanych procesorów. **Kluczowe**: bez umieszczenia `local-blocks` na tej liście, sam config w `processor:` jest ignorowany. Tempo wymaga jawnej aktywacji per-tenant.

---

## 6. Trzy procesory razem — co dostajemy

| Pytanie | Procesor | Gdzie zobaczyć |
|---|---|---|
| „Jaki jest rate POST /reservations?" | `span-metrics` | Prometheus query `traces_spanmetrics_calls_total{...}` |
| „p99 latencji api-gateway?" | `span-metrics` | Prometheus query `histogram_quantile(0.99, …spanmetrics_latency_bucket{...})` |
| „Kto wywołuje kogo w naszym systemie?" | `service-graphs` | Grafana panel typu node-graph (panel 42), datasource Tempo `serviceMap` |
| „Ile czasu zajęło wywołanie starport → planner (ze strony klienta vs serwera)?" | `service-graphs` | Prometheus `traces_service_graph_request_*_seconds_bucket{client=…, server=…}` |
| „Daj mi rate spanów z błędem w ostatnich 10 minutach, podział per route" | `local-blocks` | Grafana panel Tempo z TraceQL: `{ status = error } \| rate() by (name)` |
| „Pokaż mi trasy z duration > 1s w ostatniej godzinie" | `local-blocks` | Grafana TraceQL: `{ duration > 1s } \| rate()` |
| „Daj mi p99 trace duration" — stabilna metryka do alertu | (pusto — brak persistowanych metryk) | trzeba dodać własną metrykę aplikacyjną (jak `events.reservation.lag.seconds`) |

Trzy procesory pokrywają 3 różne use case'y. Razem dają potężny zestaw — żadne pojedyncze narzędzie nie zastępuje pozostałych.

---

## 7. Schemat data flow w MicroservicesFleet po zmianach

```
                       Aplikacje (OTLP HTTP)
                            │
                            ▼
                    ┌───────────────────┐
                    │    distributor    │
                    └────────┬──────────┘
                             │
           ┌─────────────────┼──────────────────────┐
           │                 │                       │
           ▼                 ▼                       ▼
      ┌──────────┐   ┌──────────────────────────────────┐
      │ ingester │   │       metrics_generator           │
      │          │   │  ┌────────────────────────────┐  │
      │  blocks  │   │  │ service-graphs processor   │  │
      │  (long-  │   │  └─────────────┬──────────────┘  │
      │   term)  │   │  ┌─────────────▼──────────────┐  │
      └────┬─────┘   │  │ span-metrics processor     │  │
           │         │  └─────────────┬──────────────┘  │
           │         │                ▼                 │
           │         │       remote_write to            │
           │         │       Prometheus                 │
           │         │                                  │
           │         │  ┌─────────────────────────────┐ │
           │         │  │ local-blocks processor      │ │
           │         │  │ (NEW: traces_storage on disk)│ │
           │         │  └─────────────────────────────┘ │
           │         └──────────────────┬───────────────┘
           │                            │
           ▼                            ▼
   /tmp/tempo/blocks            /tmp/tempo/generator/{wal,traces}
   (Trace Search)                (TraceQL Metrics interactive)
```

Oba magazyny żyją obok siebie, oba są używane:
- Otwarcie konkretnego trace (panel 41 „Trace Search") → idzie do `ingester` blocks
- TraceQL Metrics query (panel 73) → idzie do `local-blocks` traces_storage
- Pre-zdefiniowane metryki na dashboardach (panele 51-54) → idą do Prometheusa (z `remote_write`)

---

## 8. Trade-offs i ograniczenia `local-blocks`

### Plusy
- **Interaktywne queries po surowych spanach** — żaden Prometheus nie da takiej elastyczności (Prometheus ma tylko pre-agregowane metryki)
- **Brak konieczności wcześniejszej instrumentacji** — pomyślałeś o nowej metryce o 2 w nocy, otwierasz Grafanę i piszesz query, działa od razu
- **Świetne narzędzie do incidentu** — drążenie bez czekania na release

### Minusy
- **Tylko świeże dane** — bufor trzyma ~10-60 minut, zależnie od ruchu. Zapytanie „co się działo w zeszłym tygodniu" zwróci pustkę.
- **Wyniki nie są persistowane jako metryki** — nie da się ich użyć w recording rules, ani w Alertmanager'ze. To narzędzie eksploracyjne, nie alertowe.
- **Eksperymentalne w Tempo 2.4** — niektóre TraceQL składnie metryczne (np. `quantile_over_time(duration, 0.99)`) mogą zwracać błędy. Stabilizuje się w 2.5+.
- **Koszt CPU per query** — każde zapytanie skanuje bloki = chwila CPU per query. Nie pchać agresywnie z dashboardu auto-refresh co 5s. Dashboard refresh np. 30s+ jest OK.
- **Koszt disk** — kilkadziesiąt MB do kilku GB, zależnie od ruchu i retencji. U nas pomijalne.
- **Koszt RAM** — kilka MB heap'a Tempo per tenant. Pomijalne.

### Kiedy używać
- Ad-hoc debugowanie incydentu („dlaczego nagle pojawiły się slow reservations?")
- Eksploracja danych przed dodaniem nowej pre-zdefiniowanej metryki (sprawdzasz, jaki jest rzeczywisty rozkład, zanim dobierzesz buckety SLO)
- Generowanie nowych metryk biznesowych przez label słownikowanie (po skonfigurowaniu `span-metrics.dimensions`)

### Kiedy NIE używać
- Alerting („obudź mnie, gdy p99 wzrośnie") — alert powinien iść na trwałą metrykę w Prometheusie
- Długoterminowe trendy („jak ruch zmienił się przez ostatnie 3 miesiące?") — bufor nie sięga tak daleko
- Wysoki QPS dashboardów (refresh co 5s × 10 paneli) — koszt CPU rośnie liniowo

---

## 9. Stan po naszych zmianach (commit `2f19349`)

```yaml
# infra/docker/grafana/tempo.yml
metrics_generator:
  registry:
    external_labels:
      source: tempo
  storage:
    path: /tmp/tempo/generator/wal
    remote_write:
      - url: http://prometheus:9090/api/v1/write
        send_exemplars: true
  # NOWE — konfiguracja procesora local-blocks
  processor:
    local_blocks:
      filter_server_spans_from_root_span: true
      flush_to_storage: true
  traces_storage:
    path: /tmp/tempo/generator/traces

overrides:
  # NOWE: dorzucony local-blocks
  metrics_generator_processors: [service-graphs, span-metrics, local-blocks]
```

Po `docker compose restart tempo`:
- Stare metryki z `service-graphs` i `span-metrics` dalej lecą do Prometheusa
- Dodatkowo Tempo zaczyna buforować spany w `/tmp/tempo/generator/traces`
- Po ~1 minucie ruchu Grafana panel 73 (TraceQL Metrics — Span rate by route) ma czym się zapełnić

W razie błędów: `docker logs tempo` szukać linii zawierających `local_blocks` lub `processor` — typowo problem z ścieżką (`traces_storage.path` musi istnieć i być zapisywalna) albo z aktywacją w `overrides`.

---

## 10. Co dalej (jeśli chcemy więcej)

- **Upgrade Tempo do 2.5+** — TraceQL metrics przestaje być eksperymentalne, więcej składni działa stabilnie. Drobny exercise w `infra/docker/docker-compose.yml`: `image: grafana/tempo:2.5.0` (sprawdzić release notes pod kątem breaking changes).
- **Dodać `span-metrics.dimensions: [business.operation]`** + ustawiać atrybut w kodzie aplikacji — Ścieżka B z analizy e2e. To pozwoli mieć **persistowane** metryki per business operation BEZ pisania własnych Counterów.
- **Recording rules nad TraceQL** — na razie niedostępne w Tempo. Można rozważyć Mimir/Cortex jako frontend, ale to spore przedsięwzięcie.

---

## 11. TL;DR

**Pytanie**: czy w Tempo `metrics_generator` mamy różne procesory?
**Odpowiedź**: tak, **trzy** w naszym setupie po commicie `2f19349`:

1. **`service-graphs`** — graf zależności między serwisami (krawędzie). Eksportuje `traces_service_graph_*` do Prometheusa.
2. **`span-metrics`** — RED metrics per service / span name. Eksportuje `traces_spanmetrics_*` do Prometheusa.
3. **`local-blocks`** (nowe) — bufor surowych spanów na dysku, pod **interaktywne TraceQL queries** w Grafanie. Nie eksportuje nic do Prometheusa; queries idą wprost do Tempo.

Każdy procesor odpowiada na inne klasy pytań. Razem dają pełne pokrycie:
- pre-zdefiniowane metryki agregowane (Prometheus, alerty) ← `service-graphs` + `span-metrics`
- ad-hoc eksploracja na surowych spanach (Grafana, drążenie incidentu) ← `local-blocks`

`local-blocks` rozszerza możliwości Tempo z „magazynu trace'ów + producenta zdefiniowanych metryk" o „interaktywną bazę agregacji ad-hoc". To trzecia, równoległa ścieżka analizy obok PromQL i klasycznego Trace Search. Realnie używana rzadko, ale przy debugowaniu nieprzewidzianego problemu — bezcenna.

---

*Dokument utrzymywany ręcznie. Po każdej zmianie `tempo.yml` lub konfiguracji procesorów warto go zaktualizować.*
