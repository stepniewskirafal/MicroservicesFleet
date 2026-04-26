# Dokumentacja paneli — Microservices Fleet Dashboard

Dokument tłumaczy każdy panel głównego dashboardu Grafany w `infra/docker/grafana/provisioning/dashboards/logs_traces_metrics.json`. Cel: zrozumieć **co metryka mierzy**, **skąd się bierze w kodzie Java/Micrometer**, **jak Prometheus ją widzi**, **czy została użyta poprawnie** i **jaka jest jej wartość biznesowa**.

---

## Część I — Niezbędne minimum o Micrometer + Prometheus

Bez zrozumienia tych pięciu pojęć większość paneli wygląda jak czarna magia.

### 1. Cztery typy metryk w Micrometer

| Typ | Co mierzy | Co eksportuje do Prometheusa | Przykład |
|---|---|---|---|
| `Counter` | monotoniczny licznik (tylko rośnie) | jedna seria `<name>_total` | „ile rezerwacji potwierdzonych" |
| `Timer` | rozkład czasów trwania operacji | trzy serie: `_seconds_count`, `_seconds_sum`, `_seconds_max`. Plus `_seconds_bucket{le=…}` jeżeli włączony histogram | „ile trwa potwierdzenie rezerwacji" |
| `DistributionSummary` | rozkład wartości (nie czasu) | tak samo jak Timer, ale z innym base unit | „rozkład kwoty opłaty w CR" |
| `Gauge` | wartość chwilowa (może rosnąć i maleć) | jedna seria z bieżącą wartością | „ile rekordów PENDING w outboxie" |

**Klucz**: `Counter` i `Gauge` to pojedyncze serie liczbowe. `Timer` i `DistributionSummary` to **zawsze trzy** (lub więcej, jeśli histogram) serii pod jedną „metryką". Stąd PromQL na nich wygląda inaczej.

### 2. Naming convention: `nazwa.kropkowana` → `nazwa_podkreslnikowana`

Micrometer trzyma metrykę pod nazwą `reservations.fees.calculate`. Przy eksporcie do Prometheusa snake-case'uje wszystko, dodaje sufiksy zależnie od typu:

- `Counter` → `<name>_total` (jeśli nie kończy się już na `_total`)
- `Timer` → `<name>_seconds_<count|sum|max|bucket>`
- `DistributionSummary` z `baseUnit("cr")` → `<name>_cr_<count|sum|max|bucket>`
- `Gauge` → `<name>` (lub `<name>_<unit>` jeżeli zdefiniowany)

**Pułapka case-sensitivity**: Micrometer wymusza lowercase na nazwie i base unicie. `baseUnit("CR")` w kodzie → w Prometheusie `_cr_*`. Rozjazd „CR vs cr" w dashboardzie = puste wykresy. Mieliśmy to w panelu „Fee Revenue Rate" — historia w `plany/plan-redesign-metryk-red-use.md`.

**Pułapka `.total` + `.created`**: gdy Counter ma nazwę `reservations.created.total`, Micrometer w naszej wersji emituje go jako **`reservations_total`** (a nie `reservations_created_total`, jak intuicyjnie byśmy się spodziewali). Powód to specyficzna obróbka segmentów `.created` (zarezerwowanych w OpenMetrics) i `.total`. To nie błąd — to świadoma decyzja Micrometera, ale nieoczywista. Stąd dashboard używa `reservations_total{outcome=…}`, mimo że w kodzie nazwa to `reservations.created.total`.

### 3. Histogram vs Summary — podstawa percentyli

Aby liczyć kwantyle **prawidłowo w środowisku rozproszonym** (mamy 2× starport-registry, 2× trade-route-planner), metryka **musi być histogramem**, nie Summary. Powód: Summary liczy percentyle po stronie aplikacji, per instancja; nie da się ich potem zsumować poprawnie. Histogram emituje **buckety** (`{le="0.05"}`, `{le="0.1"}`, …), Prometheus agreguje buckety między instancjami i z tego liczy percentyle przez `histogram_quantile()`.

Włącza się go w `application.yml`:
```yaml
management.metrics.distribution:
  percentiles-histogram:
    reservations.confirm: true
  slo:
    reservations.confirm: 5ms,10ms,25ms,50ms,100ms,250ms,500ms,1s,2s
```

`slo:` to **buckety dobrane do progów SLO**. Każdy bucket = jedna dodatkowa seria w Prometheusie; ostrożnie z liczbą.

### 4. Observation API zamiast ręcznych Timerów

Spring Boot 3 + Micrometer 1.10+ wprowadza **Observation API** — wzorzec, w którym jeden obiekt `Observation` automatycznie:
- tworzy Timer (`<name>_seconds_*`)
- tworzy span tracingowy (jeśli jest tracer, np. Tempo)
- propaguje kontekst (traceId/spanId) między wątkami

Dlatego w kodzie zamiast `Timer.builder("foo").register(meterRegistry).record(...)` widać `Observation.createNotStarted("foo", observationRegistry).observe(() -> ...)`. To jeden obiekt, który robi **i** metrykę **i** trace.

Wszystkie metryki w naszym kodzie kończące się na `_seconds_count` / `_seconds_sum` (a jest ich większość) pochodzą **z Observations, nie z ręcznych Timerów**. To dla czytelnika kodu nieoczywiste, bo nazwa `Observation.createNotStarted("reservations.confirm", …)` nie wygląda na metrykę.

### 5. PromQL anatomia: `rate()`, `_sum / _count`, `histogram_quantile()`

Kilka wzorców, które zobaczysz w prawie każdym panelu:

| Wzorzec | Co liczy |
|---|---|
| `rate(<counter>_total[5m])` | tempo zdarzeń na sekundę |
| `rate(<timer>_seconds_sum[5m]) / rate(<timer>_seconds_count[5m])` | średnia latencja |
| `histogram_quantile(0.99, rate(<timer>_seconds_bucket[5m]))` | p99 latencji (wymaga histogramu) |
| `clamp_min(X, 1e-9)` | zabezpieczenie przed dzieleniem przez 0 |
| `sum by (label) (...)` | agregacja po wybranym labelu (instance najczęściej zwijamy) |

Ważne: **dzielenie sumarycznego rate'a przez rate count = średnia w okienku rate'a**, a nie kumulatywna. Dlatego średnia z dashboardu ma sens „ostatnich 5 minut" a nie „od startu serwisu".

---

## Część II — Panele dashboardu

Panele opisuję w kolejności wystąpienia w dashboardzie (`y` rosnąco). Każdy ma identyfikator `ID:N` zgodny z polem `id` w JSON.

---

### Sekcja: Executive Dashboard

#### Panel ID:18 — "Fee Revenue Rate (CR/hour)"

**Co pokazuje**: tempo przychodów ze wszystkich rezerwacji, w kredytach galaktycznych na godzinę, z rozbiciem po klasie statku (SCOUT/FREIGHTER/CRUISER/UNKNOWN).

**Zapytania PromQL**:
```promql
sum by(shipClass) (rate(reservations_fees_calculated_amount_cr_sum[$__rate_interval])) * 3600
sum         (rate(reservations_fees_calculated_amount_cr_sum[$__rate_interval])) * 3600
```

**Metryka źródłowa**: `reservations.fees.calculated.amount` typu `DistributionSummary` z `baseUnit("cr")`.

**Kod Java** — `FeeCalculatorService.java:39-51`:
```java
DistributionSummary.builder(METRIC_FEE_AMOUNT)        // "reservations.fees.calculated.amount"
        .baseUnit("cr")
        .description("Calculated reservation fee amount in Credits")
        .tag("starport", command.destinationStarportCode())
        .tag("shipClass", command.shipClass().name())
        .register(meterRegistry)
        .record(fee.doubleValue());
```

**Co Prometheus widzi**: dla DistributionSummary emitowane są 4 serie (lub więcej z histogramem):
- `reservations_fees_calculated_amount_cr_count` — ile razy zarejestrowano opłatę
- `reservations_fees_calculated_amount_cr_sum` — suma wszystkich kwot w CR (kumulatywnie)
- `reservations_fees_calculated_amount_cr_max` — max w bieżącym oknie
- (opcjonalnie `_bucket{le="..."}` dla histogramu — wyłączony w Fazie 1, bo to KPI biznesowy, nie SLI)

**Jak działa zapytanie**: `rate(_sum[5m])` daje **CR per sekundę** w ostatnich 5 minutach. Mnożymy × 3600 = **CR per godzinę**. `sum by(shipClass)` zwija label `instance` (mamy 2 instancje), więc widzimy globalnie per klasa.

**Czy poprawnie?** ✅ Tak, ale z dwoma obserwacjami:

1. **W przeszłości panel był pusty** — query używała `_CR_sum` (uppercase), Micrometer emituje `_cr_sum` (lowercase). Naprawione.
2. **Druga przeszła awaria** — pre-registracja w konstruktorze bez tagów blokowała emisję otagowanych serii (`PrometheusMeterRegistry` odrzucał drugą rejestrację z innym schematem labeli). Naprawione w commicie `b394e06`.

**Wartość biznesowa**: kluczowy KPI **executive level** — finanse chcą widzieć ile zarabia platforma w czasie rzeczywistym. Spadek do zera = albo serwis nie działa, albo coś z fee calculatorem. Można też zauważyć trendy: które klasy statku dają najwięcej przychodu.

---

#### Panel ID:19 — "Reservation Conversion Rate"

**Co pokazuje**: jaki procent prób rezerwacji kończy się sukcesem (`outcome=confirmed`).

**Zapytanie**:
```promql
100 * sum(rate(reservations_total{outcome="confirmed"}[$__rate_interval]))
    / clamp_min(sum(rate(reservations_total[$__rate_interval])), 1e-9)
```

**Metryka źródłowa**: `reservations.created.total` typu `Counter` z labelami `starport`, `shipClass`, `outcome`. **Uwaga**: w Prometheusie metryka pojawia się jako `reservations_total` (Micrometer strippuje `.created` — patrz Część I §2).

**Kod Java** — `ReservationService.java:76-80`:
```java
private void incrementReservationCounter(String starport, String shipClass, String outcome) {
    meterRegistry
            .counter(METRIC_CREATED, "starport", starport, "shipClass", shipClass, "outcome", outcome)
            .increment();
    // ... dodatkowo emituje też reservations.create.requests.total (Faza 2 RED)
}
```

`outcome` przyjmuje cztery wartości w zależności od ścieżki w `reserveBay()`:
- `confirmed` — sukces, fee policzone, route zaplanowany
- `no_capacity` — `NoDockingBaysAvailableException` z `holdReservation`
- `route_unavailable` — `RouteUnavailableException` z route plannera
- `error` — `ReservationConfirmationException` (nieprzewidziany błąd confirm)

**Jak działa zapytanie**: licznik = rate confirmów, mianownik = rate wszystkich. `clamp_min(..., 1e-9)` chroni przed `0/0=NaN` w okresach bez ruchu. Wynik mnożymy × 100 = procent.

**Czy poprawnie?** ✅ Klasyczny wzorzec konwersji. Dwa drobne uwagi:
1. Zapytanie sumuje po wszystkich labelach (job, instance, starport, shipClass) — to OK dla globalnej konwersji. Brakuje tylko split per starport / shipClass, gdyby chcieć drążyć dalej.
2. Brakuje korelacji z time range — przy `now-30m` widzimy tylko 30 min, nie dłuższy trend.

**Wartość biznesowa**: kluczowy SLO biznesowy. Spadek konwersji = klienci próbują rezerwować, ale system odrzuca. Może być przyczyną:
- starport się zapełnił (no_capacity)
- trade-route-planner nie odpowiada (route_unavailable)
- bug w confirm flow

Próg < 80% = warning, < 50% = page (do skonfigurowania w Fazie 4 planu).

---

#### Panel ID:20 — "Failure Reservation Rate"

**Co pokazuje**: procent rezerwacji odrzuconych z powodu **infrastruktury domenowej** — brak miejsca lub niedostępna trasa. Komplementarny do panelu konwersji.

**Zapytanie**:
```promql
100 * sum(rate(reservations_total{outcome=~"no_capacity|route_unavailable"}[$__rate_interval]))
    / clamp_min(sum(rate(reservations_total[$__rate_interval])), 1e-9)
```

**Metryka źródłowa**: ta sama co wyżej — `reservations_total{outcome}`.

**Jak działa zapytanie**: filtr `outcome=~"no_capacity|route_unavailable"` (regex match) selekcjonuje dwie konkretne ścieżki błędów. **Pomija `outcome=error`** (wewnętrzne błędy), bo te to inny rodzaj problemu (bug, nie capacity).

**Czy poprawnie?** ✅ Sensowne uproszczenie. Można by się zastanowić, czy `error` nie powinno być razem (pełny failure rate), czy osobno (system error vs business error). Aktualnie panel mówi „ile odmówiliśmy z biznesowych powodów" — to ma sens.

**Wartość biznesowa**: separacja **biznesowych odmów** od **bugów** to kluczowa praktyka SRE. Wysokie `failure rate` ≠ awaria — może oznaczać po prostu, że pojemność starportów jest zbyt mała w stosunku do ruchu. Wtedy reakcja: dodaj baye, nie napraw kod. Bug w kodzie pokazałby się jako dużo `outcome=error`.

---

### Sekcja: CPU / Memory (cross-service)

#### Panel ID:13 — "JVM Memory Usage [MB]"

**Co pokazuje**: total JVM memory użycie (heap + non-heap) w MB, per serwis.

**Zapytanie**:
```promql
sum by (job) (jvm_memory_used_bytes) / 1024^2
```

**Metryka źródłowa**: `jvm_memory_used_bytes` — **built-in z Micrometera** (klasa `JvmMemoryMetrics` w `micrometer-core`). Auto-rejestrowana przez Spring Boot Actuator. Labele: `area="heap"|"nonheap"`, `id="<pool name>"` (np. `G1 Eden Space`, `Metaspace`).

**Kod Java**: nie ma — metryka pochodzi z `Spring Boot Actuator` autoconfiguration. Włączamy ją przez samą obecność `spring-boot-starter-actuator` + `micrometer-registry-prometheus` na classpath.

**Jak działa zapytanie**: `sum by (job)` agreguje po wszystkich pulach pamięci JVM (heap + non-heap + metaspace + …) per serwis. Wynik dzielimy przez 1024² żeby dostać MB.

**Czy poprawnie?** ⚠️ Działa, ale **miesza heap z non-heap**, co utrudnia diagnostykę. Pamięć JVM ma kilka komponentów o różnych charakterystykach:
- heap (Eden/Survivor/Old gen) — GC zbiera
- metaspace — klasy
- code cache — JIT
- direct buffers — NIO

Sumując wszystko razem widać tylko trend „rośnie / nie rośnie", bez wskazania **co** rośnie. Lepiej byłoby split-ować po `area`. Ale do executive overview wystarczy.

**Wartość biznesowa**: USE Utilization dla pamięci JVM. Linia stale wzrostowa = leak. Pewny **plateau** = zdrowo. To podstawowa metryka „czy serwis nie wycieka".

---

#### Panel ID:15 — "JVM Heap Memory [MB]"

**Co pokazuje**: TYLKO heap memory, per serwis. Bardziej szczegółowo niż Panel 13.

**Zapytanie**:
```promql
sum by (job) (jvm_memory_used_bytes{area="heap"}) / 1024^2
```

**Metryka źródłowa**: ta sama — `jvm_memory_used_bytes` z labelem `area="heap"`.

**Czy poprawnie?** ✅ Czystszy pomiar — heap jest tym, co GC zbiera, więc obserwacja heap'a = obserwacja zachowania GC. Sumowanie po `id` (Eden/Survivor/Old) per serwis ma sens — patrzymy na całość heap'a.

**Lepszy panel byłby**: % wykorzystania zamiast surowych MB. Mając ten panel wiemy że ALPHA-BASE używa 500 MB heap'a, ale nie wiemy czy to mało (10% z 5 GB) czy dużo (95% z 512 MB). Dlatego centralny dashboard `Resources (USE)` używa `jvm_memory_used_bytes / jvm_memory_max_bytes` × 100.

**Wartość biznesowa**: ta sama co panel 13, ale precyzyjniejsza dla diagnostyki memory leak.

---

#### Panel ID:16 — "CPU Usage"

**Co pokazuje**: % CPU używane przez proces JVM, per serwis.

**Zapytanie**:
```promql
process_cpu_usage * 100
```

**Metryka źródłowa**: `process_cpu_usage` — built-in z Micrometera (`ProcessorMetrics`). Wartość **0..1** (np. 0.25 = 25% CPU).

**Kod Java**: brak — autoconfiguration. `Spring Boot Actuator` rejestruje `ProcessorMetrics` przy starcie.

**Jak działa zapytanie**: × 100 daje procent. Brak `sum by (job)` — pokazuje per instancję (`job` + `instance` etykietki dziedziczone).

**Czy poprawnie?** ⚠️ Drobne uwagi:
- `process_cpu_usage` to **utilization** (% używane), nie **saturation**. Saturacja = `system_load_average_1m`. Panel nazywa się „CPU Usage" — to OK dla utilization.
- Brak `legendFormat` z `instance` powoduje, że dwa starport-registry pokazują się jako dwie linie z tymi samymi etykietami `{{job}}` (nieczytelne). Lepiej: `{{job}} ({{instance}})`.
- Wartości > 100% są możliwe, gdy proces używa wielu rdzeni równolegle (np. 250% = 2.5 rdzenia).

**Wartość biznesowa**: USE Utilization dla CPU. Saturacja CPU w kontenerach JVM = często źródło latencji. Wzrost CPU w jednym kontenerze + brak w innych = nierównomierny load balancing.

---

### Sekcja: Starport Registry

#### Panel ID:5 — "Avg Reservation HTTP Request Duration [ms]"

**Co pokazuje**: średni czas odpowiedzi POST `/api/v1/starports/{code}/reservations` w milisekundach.

**Zapytanie**:
```promql
1000 * sum(rate(http_server_requests_seconds_sum{job="starport-registry",
                                                  uri="/api/v1/starports/{code}/reservations",
                                                  method="POST"}[$__rate_interval]))
     / clamp_min(sum(rate(http_server_requests_seconds_count{...}[$__rate_interval])), 1e-9)
```

**Metryka źródłowa**: `http.server.requests` — **built-in z Spring Boot Actuator** (poprzez `WebMvcMetricsFilter`). Auto-instrumentacja każdego endpointu.

Labele:
- `uri` — wzorzec URL (z parametrami zwijanymi do `{code}` itp.)
- `method` — GET/POST/...
- `status` — 200/201/400/500
- `outcome` — SUCCESS/CLIENT_ERROR/SERVER_ERROR
- `exception` — nazwa rzuconego wyjątku albo `None`

**Kod Java**: brak — autoconfiguration. Włączane domyślnie. SLO buckety dla tej metryki konfigurujemy w `application.yml` (Faza 1):
```yaml
percentiles-histogram:
  http.server.requests: true
percentiles:
  http.server.requests: 0.5,0.95,0.99  # client-side percentile (Summary)
```

**Jak działa zapytanie**: klasyczny wzorzec średniej latencji = `sum / count` w okienku rate'a. Mnożenie × 1000 zamienia sekundy na milisekundy.

**Czy poprawnie?** ⚠️ Trzy uwagi:

1. **Średnia ukrywa ogony**. Lepiej byłoby pokazać p50, p95, p99 (z `histogram_quantile()`). Średnia z 1000 requestów: 999 × 50ms + 1 × 30s = średnia 80ms — wygląda zdrowo, a 1 user czeka 30 sekund. p99 by to pokazał.

2. **Średnia w środowisku 2-instancyjnym jest matematycznie OK** (bo dzielimy globalne sumy), ale **percentyle z Summary nie**. Dlatego włączyliśmy `percentiles-histogram=true` — żeby p99 z `histogram_quantile()` działało.

3. Brakuje split per `status` — średnia miesza 200ms requesty zakończone sukcesem z 5ms odpowiedziami 4xx. Lepiej `sum by (status) (...)`.

**Wartość biznesowa**: RED Duration. Najprostsza diagnostyka „czy API jest szybkie?". Wzrost = jakaś zależność spadła (DB, route planner, Kafka).

---

#### Panel ID:1 — "Route Plan Results"

**Co pokazuje**: tempo (per sekundę) udanych vs nieudanych wywołań z starport-registry → trade-route-planner.

**Zapytania**:
```promql
rate(reservations_route_plan_success_total[$__rate_interval])    # success
rate(reservations_route_plan_errors_total[$__rate_interval])     # error
```

**Metryki źródłowe**:
- `reservations.route.plan.success` — `Counter` bez tagów
- `reservations.route.plan.errors` — `Counter` z tagiem `errorType`

**Kod Java** — `TradeRoutePlannerHttpAdapter.java:43-45, 97`:
```java
this.routePlanSuccessCounter = Counter.builder(METRIC_ROUTE_PLAN_SUCCESS)
        .description("Number of successfully planned routes")
        .register(meterRegistry);

// ... później w callTradeRoutePlanner:
routePlanSuccessCounter.increment();   // po udanym call
```

I error counter (linia 130-134):
```java
private void incrementErrorCounter(String errorType) {
    Counter.builder(METRIC_ROUTE_PLAN_ERROR)
            .description("Number of failed route planning attempts")
            .tag("errorType", errorType)   // domain | infrastructure | empty_response | circuit_open
            .register(meterRegistry)
            .increment();
}
```

`errorType` rozróżnia 4 kategorie błędów:
- `domain` — 4xx z trade-route-plannera (np. „insufficient fuel range")
- `infrastructure` — 5xx, timeout, IO error
- `empty_response` — odpowiedź pusta (200 OK, ale body=null)
- `circuit_open` — circuit breaker trzaśnięty

**Czy poprawnie?** ⚠️ Anty-wzorzec RED §6.2 z planu redesign: **success i error w osobnych counterach** zamiast jednego counter wszystkich + osobny counter błędów. Skutek: error rate = `error / (success + error)`, co wymaga sumowania w PromQL. Tutaj panel po prostu pokazuje obie linie obok siebie — co jest OK do wizualnego porównania, ale nie pozwala na proste alerty „> 1% błędów".

W Fazie 2 dodaliśmy **trzecią metrykę** `reservations.route.plan.requests.total` (superset success + error). Panel jeszcze jej nie używa — przyszły refactor.

**Wartość biznesowa**: czerwona linia rośnie powyżej zielonej = trade-route-planner ma problemy. Bardzo podstawowa, ale skuteczna diagnostyka health zewnętrznej zależności.

---

#### Panel ID:2 — "Reservation processing errors"

**Co pokazuje**: scatter plot zdarzeń per kategoria błędu, w okienku rate.

**Zapytanie**:
```promql
sum by (outcome) (
  increase(reservations_total{outcome!="confirmed"}[$__rate_interval])
)
```

**Metryka źródłowa**: ta sama `reservations_total`, ale filtr `outcome != "confirmed"` selekcjonuje wszystkie ścieżki błędów (`no_capacity`, `route_unavailable`, `error`).

**Jak działa zapytanie**: `increase()` to *„ile wzrosło w okresie"*. Czyli „ile błędów per kategoria w ostatnich N minutach". Wynik sumowany po `outcome` (zwijając instance/job/starport).

**Drawstyle: points** — to celowe. Wykres punktowy wyróżnia spike'i (np. nagle 5 błędów), które na linii liniowej wyglądałyby jak ledwie widoczna drobnostka.

**Czy poprawnie?** ✅ Sensowne uzupełnienie panelu konwersji. Konwersja pokazuje **procent**, ten panel **wartości absolutne** w czasie. Razem dają pełen obraz.

**Wartość biznesowa**: identyfikacja wzorców błędów. Np. wszystkie błędy `no_capacity` skoncentrowane w jednym starporcie = trzeba rozbudować. Wszystkie `route_unavailable` w określonych godzinach = zewnętrzny serwis ma problemy w peak hours.

---

#### Panel ID:6 — "Average Reservation Fee [CR]"

**Co pokazuje**: średnia opłata pobierana za rezerwację.

**Zapytanie**:
```promql
sum(rate(reservations_fees_calculated_amount_cr_sum[$__rate_interval]))
/ clamp_min(sum(rate(reservations_fees_calculated_amount_cr_count[$__rate_interval])), 1e-9)
```

**Metryka źródłowa**: `reservations.fees.calculated.amount` (DistributionSummary) — ta sama co Panel 18.

**Jak działa zapytanie**: średnia w okienku rate. **Ten panel używa `count`, panel 18 używa `_sum × 3600`**. Różnica:
- Panel 18 — **revenue** (suma opłat)
- Panel 6 — **avg fee** (średnia kwota za jedną rezerwację)

**Czy poprawnie?** ✅ Klasyczny wzorzec. **Z subtelną wadą matematyczną**: dzielenie globalnych rate'ów (`sum_global / count_global`) jest poprawne. Jeśli ktoś by to zrobił `sum by(instance) (sum / count)` byłoby błędne. Tutaj nie sumujemy po niczym = całość, więc OK.

**Wartość biznesowa**: monitoring revenue per ticket. Spadek średniej opłaty = ktoś rezerwuje krótsze okna lub dla tańszych klas statku. Wzrost = klienci kupują dłuższe rezerwacje. Sygnał trendu rynku.

---

#### Panel ID:21 — "Reservation Step Avg Latency [ms]"

**Co pokazuje**: średnia latencja każdego z 3 kroków flow rezerwacji: hold allocate, fee calculate, confirm.

**Zapytania** (3 targety, podobne):
```promql
1000 * rate(reservations_hold_allocate_seconds_sum[$__rate_interval])
     / clamp_min(rate(reservations_hold_allocate_seconds_count[$__rate_interval]), 1e-9)
1000 * rate(reservations_fees_calculate_seconds_sum[$__rate_interval])
     / clamp_min(rate(reservations_fees_calculate_seconds_count[$__rate_interval]), 1e-9)
1000 * rate(reservations_confirm_seconds_sum[$__rate_interval])
     / clamp_min(rate(reservations_confirm_seconds_count[$__rate_interval]), 1e-9)
```

**Metryki źródłowe**: trzy **Observations**, automatycznie tworzące Timery przez `DefaultMeterObservationHandler`:

1. `reservations.hold.allocate` — `CreateHoldReservationService.java:34`
   ```java
   Observation.createNotStarted(OBSERVATION_NAME, observationRegistry)
           .lowCardinalityKeyValue("starport", command.destinationStarportCode())
           .lowCardinalityKeyValue("shipClass", command.shipClass().name())
           .observe(() -> { ... persistenceFacade.createHoldReservation(command) ... });
   ```
   Mierzy: `INSERT INTO reservation` + `FOR UPDATE SKIP LOCKED` na bayach.

2. `reservations.fees.calculate` — `FeeCalculatorService.java:37`
   ```java
   Observation.createNotStarted(OBSERVATION_NAME, observationRegistry)
           .lowCardinalityKeyValue("starport", ...)
           .lowCardinalityKeyValue("shipClass", ...)
           .observe(() -> { ... compute fee ... });
   ```
   Mierzy: in-memory calculation (tabela stawek × godziny).

3. `reservations.confirm` — `ConfirmReservationService.java:32`
   ```java
   obs.observe(() -> {
       Reservation reservation = persistenceFacade.confirmReservation(...);
       outboxFacade.publishReservationConfirmedEvent(reservation);
       return reservation;
   });
   ```
   Mierzy: UPDATE reservation status + INSERT event_outbox.

**Czy poprawnie?** ✅ Bardzo wartościowy panel. Mała uwaga:
- **3 linie na 1 wykresie** = łatwe wizualne porównanie. Jeśli `Confirm` skoczy a `Hold Allocate` nie — wąskie gardło to outbox INSERT, nie hold.
- p99 byłoby lepsze niż średnia (te metryki MAJĄ histogram po Fazie 1) — ale średnia szybciej rzuca się w oczy. Ideal: drugi panel obok z p99.

**Wartość biznesowa**: rozkład czasu w pipeline rezerwacji. Identyfikuje **bottleneck**. Optymalizacja czegoś, co trwa 1ms zamiast 10ms = strata czasu. Optymalizacja kroku, który trwa 200ms = duża wartość.

---

#### Panel ID:22 — "Outbox Dead Letters (rate/s)"

**Co pokazuje**: tempo permanentnie zarzuconych eventów outboxa per typ eventu i binding Kafki.

**Zapytanie**:
```promql
rate(reservations_outbox_dead_letter_total[$__rate_interval])
```

**Metryka źródłowa**: `reservations.outbox.dead.letter` — `Counter` z labelami `eventType`, `binding`.

**Kod Java** — `InboxPublisher.java:144-149`:
```java
if (outboxEvent.getAttempts() >= maxAttempts) {
    outboxEvent.markFailed();
    log.warn("Outbox permanently failed id={} attempts={}", ...);
    Counter.builder(METRIC_DEAD_LETTER)
            .description("Outbox events permanently failed after max delivery attempts")
            .tag("eventType", outboxEvent.getEventType())
            .tag("binding", outboxEvent.getBinding())
            .register(meterRegistry)
            .increment();
}
```

`maxAttempts = 10` (konfigurowalne). Po 10 nieudanych próbach wysłania do Kafki event dostaje `status=FAILED` i już nigdy nie zostanie wysłany — utrata danych.

**Czy poprawnie?** ✅ Krytyczna metryka — to jest **alarm na utratę danych**. Każda niezerowa wartość = problem.

**Wartość biznesowa**: integralność systemu eventowego. Potwierdzenie rezerwacji, które nie dojechało do Kafki = telemetry-pipeline nie wzbogaci go, downstream consumers (gdyby były) nie dostaną. To może być utrata przychodu lub niedopełnienie SLA.

Próg: `> 0 przez > 0 sekund` = page natychmiast.

---

#### Panel ID:8 — "Outbox Append: Number of Appended Events by Binding"

**Co pokazuje**: ile eventów zostało **dopisanych do tabeli outbox** w okresie, per binding.

**Zapytanie**:
```promql
sum by (binding) (increase(reservations_outbox_append_seconds_count[$__rate_interval]))
```

**Metryka źródłowa**: `reservations.outbox.append` — Observation, automatycznie tworzy Timer. Używamy tu serii `_count` (= liczba wywołań `.observe()`).

**Kod Java** — `OutboxAppender.java:35-49`:
```java
Observation.createNotStarted("reservations.outbox.append", () -> senderContext, observationRegistry)
        .lowCardinalityKeyValue("binding", reservationsBinding)
        .lowCardinalityKeyValue("eventType", "ReservationConfirmed")
        .highCardinalityKeyValue("reservationId", String.valueOf(reservation.getId()))
        .observe(() -> {
            outboxWriter.save(...);   // INSERT INTO event_outbox
        });
```

Observation `reservations.outbox.append` jest też **tracingowa** (typ `SenderContext` z `Kind.PRODUCER`) — Tempo zobaczy span „producer wysyła do Kafka" choć fizycznie INSERT-uje do tabeli (outbox pattern!).

**Czy poprawnie?** ✅ Słuszne użycie Observation z `Kind.PRODUCER` semantyką. Pokazuje **produkcję eventów**, nie ich wysłanie do Kafki — to są dwie różne rzeczy w outbox pattern.

**Mały trip-up**: nazwa panelu mówi „Number of Appended Events", a metryka to histogram time'u (`_count` to liczba próbek histogramu). Działa, bo każde wywołanie Observation = 1 sample = 1 event dopisany. Ale nazwa lekko myli.

**Wartość biznesowa**: **producer rate**. Razem z outbox pending events i dead letters daje pełny obraz „czy outbox nadąża". Producer rate > publisher rate = pending rośnie = saturacja.

---

#### Panel ID:9 — "Outbox Append: Average Append Duration by Binding (ms)"

**Co pokazuje**: średni czas trwania INSERT do tabeli outbox, per binding.

**Zapytanie**:
```promql
1000 * sum by (binding) (increase(reservations_outbox_append_seconds_sum[$__rate_interval]))
     / sum by (binding) (increase(reservations_outbox_append_seconds_count[$__rate_interval]))
```

**Metryka źródłowa**: ta sama `reservations.outbox.append` Observation. Tym razem dzielimy `_sum / _count` = średnia.

**Czy poprawnie?** ✅ **Lepsze niż panel 21** matematycznie — używa `increase()` zamiast `rate()`, ale efekt finałowy ten sam (oba dają average w okienku). Ważniejsze: `sum by (binding)` jest na **liczniku i mianowniku osobno**, czyli to **prawdziwa średnia globalna per binding**, nie średnia ze średnich.

To jest **wzorzec, którego brakuje w niektórych innych panelach** (np. panel 27 ETA — patrz tam).

**Wartość biznesowa**: monitoring DB write latency. Wzrost = problem z DB (locking, slow query, connection pool exhausted). Po Fazie 1 mamy też histogram + buckety SLO `1ms,5ms,...,500ms`, więc można by dodać osobny panel z p99.

---

#### Panel ID:10 — "Inbox: Publish Throughput by Event Type (events/s)"

**Co pokazuje**: tempo eventów wysłanych z outboxa do Kafki, per typ eventu.

**Zapytanie**:
```promql
sum by (eventType) (rate(reservations_inbox_publish_seconds_count[$__rate_interval]))
```

**Metryka źródłowa**: `reservations.inbox.publish` Observation w `InboxPublisher.processSingleEvent()`.

**Kod Java** — `InboxPublisher.java:98-117`:
```java
Observation publishObs = Observation.createNotStarted(OBS_PUBLISH, () -> receiverCtx, observationRegistry)
        .lowCardinalityKeyValue("binding", outboxEvent.getBinding())
        .lowCardinalityKeyValue("eventType", outboxEvent.getEventType())
        .highCardinalityKeyValue("outboxId", String.valueOf(outboxEvent.getId()));
publishObs.start();
try (Observation.Scope scope = publishObs.openScope()) {
    Message<?> msg = buildMessage(outboxEvent);
    boolean sent = streamBridge.send(outboxEvent.getBinding(), msg);   // <-- send to Kafka
    if (!sent) throw ...;
    outboxEvent.markSent();
} catch (Exception ex) {
    publishObs.error(ex);
    handleFailure(...);
} finally {
    publishObs.stop();
}
```

`Kind.CONSUMER` w `ReceiverContext` może być nieintuicyjne — wysyłamy event, ale kontekst jest „consumer", bo InboxPublisher **konsumuje z outboxa** (kontynuuje trace zaczęty przez producera, czyli OutboxAppender).

**Czy poprawnie?** ✅ Tracingowo elegancko — łączy producer (outbox append) z consumer (publisher) przez ten sam traceId, co daje pełny end-to-end trace event'u.

**Wartość biznesowa**: throughput Kafki publish. Razem z panelem 8 (append rate) widać **delay producent → consumer**. Append > publish = backpressure outboxa.

---

#### Panel ID:11 — "Inbox Poll: Events Fetched From The Database"

**Co pokazuje**: ile eventów na sekundę wyciąga InboxPublisher z DB.

**Zapytanie**:
```promql
rate(reservations_inbox_poll_batch_size_events_sum[$__rate_interval])
```

**Metryka źródłowa**: `reservations.inbox.poll.batch.size` — `DistributionSummary` z `baseUnit("events")`.

**Kod Java** — `InboxPublisher.java:69-73`:
```java
DistributionSummary.builder(METRIC_BATCH_SIZE)
        .description("Outbox batch size fetched during poll")
        .baseUnit("events")
        .register(meterRegistry)
        .record(batch.size());
```

**Jak działa zapytanie**: `_sum` = łączna ilość eventów odczytanych. `rate(_sum)` = events/sec.

**Czy poprawnie?** ✅ Ciekawy use case DistributionSummary. Każde `poll()` rejestruje `batch.size()` jako jeden sample. `_sum` daje łączną ilość eventów, `_count` daje liczbę poll cykli.

**Subtelność**: panel używa `_sum` (eventy/s), nie `_count` (poll cycles/s). Słusznie — chodzi o throughput eventów, nie tempo cyklów.

**Wartość biznesowa**: monitoring `outbox poller`. Niska wartość + wysokie `pending events` = poller za wolny / batch size za mały. Wartość przy progu = poller dochodzi do limitu.

---

### Sekcja: Trade Route Planner

#### Panel ID:24 — "Routes Planned vs Rejected (rate/s)"

**Co pokazuje**: tempo udanych vs odrzuconych planowań tras, z rozbiciem powodów odrzucenia.

**Zapytania**:
```promql
rate(routes_planned_count_total[$__rate_interval])              # Planned
sum by (reason) (rate(routes_rejected_count_total[$__rate_interval]))  # Rejected
```

**Metryki źródłowe**: dwa Counters.

**Kod Java** — `PlanRouteService.java:52-54, 116`:
```java
this.plannedCounter = Counter.builder(METRIC_SUCCESS)   // "routes.planned.count"
        .description("Number of successfully planned routes")
        .register(meterRegistry);
// ... in recordMetrics:
plannedCounter.increment();
```

I rejection (linia 134-138):
```java
Counter.builder(METRIC_REJECTED)   // "routes.rejected.count"
        .description("Number of rejected route planning attempts")
        .tag("reason", "INSUFFICIENT_RANGE")
        .register(meterRegistry)
        .increment();
```

Aktualnie tylko jeden powód rejection: `INSUFFICIENT_RANGE` (ship's fuel range < 1.0 LY). W przyszłości mogą się dorzucić inne (np. `NO_PATH`, `BLOCKED_SECTOR`).

**Czy poprawnie?** ⚠️ Anty-wzorzec RED §6.2 — success i error w osobnych counterach. Faza 2 dodała `routes.plan.requests.total` (superset), panel jeszcze go nie używa.

**Naming nitpick**: `Counter` z nazwą `.count` to redundancja — Prometheus i tak doda `_total`. Wynik w Prometheusie: `routes_planned_count_total` (count + total). Lepiej byłoby `routes.planned` — wynik `routes_planned_total`.

**Wartość biznesowa**: monitoring **odrzuceń biznesowych**. Wzrost rejections = klienci próbują planować trasy poza zasięgiem statku. Akcja: ulepszyć UI żeby wcześniej walidował, albo dodać upgrade fuel system.

---

#### Panel ID:25 — "Route Planning Latency [ms]"

**Co pokazuje**: średnia latencja + p99 planowania trasy w milisekundach.

**Zapytania**:
```promql
1000 * rate(routes_plan_seconds_sum[$__rate_interval])
     / clamp_min(rate(routes_plan_seconds_count[$__rate_interval]), 1e-9)        # avg
1000 * histogram_quantile(0.99, rate(routes_plan_seconds_bucket[$__rate_interval]))  # p99
```

**Metryka źródłowa**: `routes.plan` — Observation w `PlanRouteService.java:63-67`.
```java
return Observation.createNotStarted(OBSERVATION_NAME, observationRegistry)
        .lowCardinalityKeyValue("originPortId", request.originPortId())
        .lowCardinalityKeyValue("destinationPortId", request.destinationPortId())
        .lowCardinalityKeyValue("shipClass", request.shipClass())
        .observe(() -> doPlan(request));
```

Histogram aktywny dzięki app.yml:
```yaml
percentiles-histogram:
  routes.plan: true
slo:
  routes.plan: 10ms,50ms,100ms,200ms,500ms,1s,2s,5s
```

**Czy poprawnie?** ✅ **Wzorcowy panel RED Duration**: ma i średnią (szybko czytelną), i p99 (pokazujący ogony). Histogram jest, więc p99 z `histogram_quantile()` daje **prawdziwy globalny p99** agregując buckety między instancjami.

**Subtelność z `histogram_quantile()`**: to nie jest p99 z dokładnością do 1ms. To p99 **przybliżony przez interpolację liniową w obrębie odpowiedniego bucketu**. Jeśli p99 wpadnie w bucket `[200ms, 500ms]`, Prometheus zwróci jakąś wartość w tym przedziale — nie da więcej dokładności. Stąd dobór bucketów SLO ma znaczenie: jeśli SLO = p99 < 250ms, to chcesz bucket dokładnie na 250ms.

**Wartość biznesowa**: SLO wewnętrzny — trade-route-planner ma być szybki. Klient czeka. Wzrost p99 = nasza aplikacja staje się powolna w 1% requestów. Po jakimś progu = page.

---

#### Panel ID:26 — "Route Risk Score Distribution (0=safe, 1=dangerous)"

**Co pokazuje**: średni i p99 risk score generowanych tras.

**Zapytania**:
```promql
routes_risk_score_sum / clamp_min(routes_risk_score_count, 1)           # avg
histogram_quantile(0.99, routes_risk_score_bucket)                       # p99
```

**Metryka źródłowa**: `routes.risk.score` — `DistributionSummary` zarejestrowany **bez tagów**, w konstruktorze `PlanRouteService.java:55-57`:
```java
this.riskScoreSummary = DistributionSummary.builder(METRIC_RISK_SCORE)
        .description("Distribution of route risk scores (0=safe, 1=dangerous)")
        .register(meterRegistry);
// ... in recordMetrics:
riskScoreSummary.record(riskScore);  // riskScore in [0, 1]
```

`riskScore` w aktualnym kodzie to `ThreadLocalRandom.current().nextDouble(0.0, 1.0)` (mock z prawdziwego algorytmu).

**Czy poprawnie?** ⚠️ Dwa subtelne błędy:

1. **Brak `rate()` na `_sum / _count`**. Zapytanie `routes_risk_score_sum / clamp_min(routes_risk_score_count, 1)` **dzieli kumulatywne wartości od startu serwisu**. Skutek: średnia jest „średnia od początku", nie „średnia ostatnich 5 minut". Dla risk score może to być OK (rozkład powinien być stabilny), ale to nie best practice. Lepiej: `rate(routes_risk_score_sum[5m]) / rate(routes_risk_score_count[5m])`.

2. **`histogram_quantile()` bez `rate()`**. To zwykle błąd. Bez `rate()` `histogram_quantile()` operuje na kumulatywnych bucketach od startu serwisu — pokazuje **historyczny p99**, nie obecny. Dla nieinstrumentalizowanego mock-up'u to OK; w produkcji to byłby błąd diagnostyczny.

3. **Histogram aktywowany w starym configu**, ale **wyłączony w Fazie 1** (plan §4.1: „histogram tylko jeśli SLI"). Po commicie Fazy 1 panel `histogram_quantile` zwróci pustkę, bo `_bucket` nie jest emitowany. **Wymaga decyzji**: jeśli chcemy widzieć rozkład risk score, wracamy z histogramem; jeśli nie, usuwamy linię p99.

**Wartość biznesowa**: business intelligence. Średnia risk = 0.5 oznacza, że random algo działa — żadna informacja. Gdy podłączymy prawdziwy algorytm, ta metryka pokaże, czy nasze trasy są ogólnie bezpieczne (avg < 0.3) czy ryzykowne (avg > 0.6) — sygnał dla business: czy klienci dostają dobre trasy.

---

#### Panel ID:27 — "Average Route ETA [hours] by Ship Class"

**Co pokazuje**: średni czas trasy w godzinach, per klasa statku.

**Zapytanie**:
```promql
sum by (shipClass) (
  routes_eta_hours_sum / clamp_min(routes_eta_hours_count, 1)
)
```

**Metryka źródłowa**: `routes.eta.hours` — `DistributionSummary` z `baseUnit("hours")`, **rejestrowany lazy** w `PlanRouteService.java:109-114`:
```java
DistributionSummary.builder(METRIC_ETA_HOURS)
        .description("Distribution of planned route ETA in hours")
        .baseUnit("hours")
        .tag("shipClass", request.shipClass())
        .register(meterRegistry)
        .record(etaHours);
```

`etaHours` zależy od shipClass + riskScore — patrz `computeEta()` w PlanRouteService.

**Czy poprawnie?** ❌ **Trzy błędy w jednym panelu**:

1. **Lazy registration**: metryka **nie istnieje w Prometheusie**, dopóki nie zostanie wykonane pierwsze `.record()`. Pomiędzy restartami serwisu lub przy braku wywołań route plannera (np. wszystkie requesty mają `requestRoute: false`) — pusty wykres. **To jest dokładnie ten problem, który zgłosiłeś** — patrz odpowiedź w poprzednim turn'ie.

2. **Brak `rate()`**: `_sum / _count` daje średnią od startu serwisu, nie obecną.

3. **`sum by (shipClass) (X / Y)` matematycznie błędne dla agregacji między instancjami**. Mając 2 instancje:
   - Instance 1: SCOUT sum=100, count=10 → avg=10
   - Instance 2: SCOUT sum=200, count=10 → avg=20
   - Prawidłowa średnia globalna: (100+200) / (10+10) = 15
   - Wzór z panelu: `sum(10 + 20) = 30` (sumuje średnie!)
   
   Powinno być:
   ```promql
   sum by (shipClass) (rate(routes_eta_hours_sum[5m]))
   /
   clamp_min(sum by (shipClass) (rate(routes_eta_hours_hours_count[5m])), 1e-9)
   ```
   
   (Dzielić **suma sumów** przez **suma countów**, nie sumować średnich.)

**Wartość biznesowa**: niski ETA = szybkie dostawy = lepszy klient experience. Trend wzrostowy = trasy są coraz dłuższe (więcej ryzyka, więcej omijania), albo random algo zaczyna generować inne wartości. Po podłączeniu prawdziwego algorytmu — sygnał trendu floty.

**Akcja do podjęcia**: poprawić zapytanie do wzoru wyżej. Dodać dummy `.record(0)` w konstruktorze (z placeholder shipClass) **albo** użyć `or vector(0)` w PromQL żeby pokazać 0 zamiast „No data".

---

### Sekcja: Telemetry Pipeline

#### Panel ID:29 — "Telemetry Messages (rate/s)"

**Co pokazuje**: tempo wiadomości telemetrii — odebrane vs odrzucone.

**Zapytania**:
```promql
rate(telemetry_messages_received_total[$__rate_interval])    # Received
rate(telemetry_messages_invalid_total[$__rate_interval])     # Invalid
```

**Metryki źródłowe**: dwa Counters.

**Kod Java** — `ValidationFilter.java:22-27, 33`:
```java
this.receivedCounter = Counter.builder("telemetry.messages.received")
        .description("Total raw telemetry messages received")
        .register(meterRegistry);
this.invalidCounter = Counter.builder("telemetry.messages.invalid")
        .description("Telemetry messages rejected by validation")
        .register(meterRegistry);

// ... in apply():
receivedCounter.increment();
if (raw.shipId() == null) {
    invalidCounter.increment();
    return null;
}
```

Walidacja sprawdza:
- shipId nie-null/non-blank
- sensorType znany
- value nie NaN/Infinite
- timestamp obecny

**Czy poprawnie?** ✅ Klasyczny RED Rate + Errors. **Brakuje tylko Duration** (osobne tematy). Można by zauważyć, że `invalidCounter` nie ma labelu „dlaczego invalid" — wszystkie powody są zwijane do jednej kategorii. Lepiej:
```java
.tag("reason", "missing_shipId")
```

**Wartość biznesowa**: cleanliness telemetry feed. Wysoki invalid % = problem po stronie producenta (statki wysyłają śmieci). Można skonfigurować alert „> 5% invalid przez > 5min".

---

#### Panel ID:30 — "Anomalies Detected by Severity (rate/s)"

**Co pokazuje**: tempo wykrytych anomalii w telemetrii, per severity.

**Zapytanie**:
```promql
sum by (severity) (rate(telemetry_anomalies_detected_total[$__rate_interval]))
```

**Metryka źródłowa**: `telemetry.anomalies.detected` — `Counter` z labelem `severity`.

**Kod Java** — `AnomalyDetectionFilter.java:23-30, 46/58/79`:
```java
this.warningCounter = Counter.builder("telemetry.anomalies.detected")
        .tag("severity", "WARNING")
        .register(meterRegistry);
this.criticalCounter = Counter.builder("telemetry.anomalies.detected")
        .tag("severity", "CRITICAL")
        .register(meterRegistry);

// CRITICAL:
if (aggregated.currentValue() > aggregated.upperThreshold()) {
    criticalCounter.increment();
    // ...
}
// WARNING:
if (deviation > STATISTICAL_SIGMA * aggregated.rollingStdDev()) {
    warningCounter.increment();
    // ...
}
```

Severity:
- `CRITICAL` — przekroczenie thresholdu (np. temperatura > 300°C)
- `WARNING` — odchylenie statystyczne (3σ od średniej rolling)

**Czy poprawnie?** ✅ Sensowne rozbicie. Jedna uwaga: dwa Counters można by **zastąpić jednym** z tagiem dynamicznym:
```java
Counter.builder("telemetry.anomalies.detected")
        .tag("severity", severity.name())  // ustawiane w runtime
        .register(meterRegistry)
        .increment();
```
Mniej kodu, identyczny efekt w Prometheusie.

**Wartość biznesowa**: operations. Wzrost CRITICAL anomalii = coś się dzieje z flotą (avaria, sabotage, ekstremalna pogoda). Wzrost WARNING bez CRITICAL = drift normalności (sensors degradują, temperatura średnia rośnie sezonowo).

---

#### Panel ID:31 — "Pipeline Filter Avg Latency [ms]"

**Co pokazuje**: średnia latencja każdego z 4 filtrów pipeline'u.

**Zapytania** (4 targety):
```promql
1000 * rate(telemetry_filter_validation_seconds_sum[$__rate_interval])
     / clamp_min(rate(telemetry_filter_validation_seconds_count[$__rate_interval]), 1e-9)
# similarly for enrichment, aggregation, anomaly
```

**Metryki źródłowe**: `telemetry.filter.validation`, `telemetry.filter.enrichment`, `telemetry.filter.aggregation`, `telemetry.filter.anomaly`.

**Kod Java**: ❌ **NIE ISTNIEJE w kodzie**. Sprawdziłem grep'em całe `telemetry-pipeline/src/main/java` — żaden filtr (ValidationFilter, EnrichmentFilter, AggregationFilter, AnomalyDetectionFilter) **nie tworzy Observation ani Timera o nazwie `telemetry.filter.*`**.

W `application.yml` mamy konfigurację SLO dla tych nazw:
```yaml
slo:
  telemetry.filter.validation: 100us,500us,1ms,5ms,10ms,50ms,100ms
  telemetry.filter.enrichment: ...
  telemetry.filter.aggregation: ...
  telemetry.filter.anomaly: ...
```

…ale konfiguracja SLO **nie tworzy metryki** — tylko konfiguruje istniejące. Skoro żaden filtr ich nie emituje, w Prometheusie tych metryk **nie ma** — panel jest stale pusty.

**Weryfikacja**: zapytałem Prometheus o `telemetry_filter_*` — brak wyników. Dashboard pokazuje pustą paletę.

**Czy poprawnie?** ❌ **NIE** — to jest „dead query" referencujący metryki, które nigdy nie zostały zarejestrowane w kodzie. Trzy opcje naprawy:

1. **Dodać Observations w filtrach** — owinąć `apply()` każdego filtra:
   ```java
   public ValidatedTelemetry apply(RawTelemetry raw) {
       return Observation.createNotStarted("telemetry.filter.validation", observationRegistry)
               .observe(() -> { /* existing apply body */ });
   }
   ```

2. **Użyć `@Observed`** annotation — `ObservationConfig` już rejestruje `ObservedAspect` (linia 12). Trzeba tylko dodać `@Observed("telemetry.filter.validation")` na `apply()`. Wymaga aby filter był Spring beanem (jest — w PipelineConfiguration).

3. **Usunąć panel** — jeśli nie potrzebny.

**Wartość biznesowa**: rozbicie czasu w telemetry pipeline (5 sensorów × wiele statków × tysiące zdarzeń). Wąskie gardło w jednym filtrze = niski overall throughput. Bez tego panelu nie wiadomo, czy wąskim gardłem jest validation czy aggregation. **Wartość duża jeśli metryka by działała.**

---

#### Panel ID:32 — "Message Validation Rejection Rate (%)"

**Co pokazuje**: procent wiadomości odrzucanych przez walidację.

**Zapytanie**:
```promql
100 * rate(telemetry_messages_invalid_total[$__rate_interval])
    / clamp_min(rate(telemetry_messages_received_total[$__rate_interval]), 1e-9)
```

**Metryki źródłowe**: jak panel 29.

**Czy poprawnie?** ✅ Klasyczny error rate. Komplementarny do panelu 29 (który pokazuje wartości absolutne).

**Wartość biznesowa**: SLO data quality. „99% wiadomości validna" = SLO. Spadek = problem z jakością danych po stronie sensorów statku.

---

### Sekcja: Event Flow (Kafka)

#### Panel ID:34 — "Event Pipeline Throughput (events/s)"

**Co pokazuje**: throughput 4 strumieni eventów: reservations received/enriched, routes received/enriched.

**Zapytania** (4 targety):
```promql
sum(rate(events_reservation_received_total[$__rate_interval]))
sum(rate(events_reservation_enriched_total[$__rate_interval]))
sum(rate(events_route_received_total[$__rate_interval]))
sum(rate(events_route_enriched_total[$__rate_interval]))
```

**Metryki źródłowe**: 4 Counters.

**Kod Java** — `EventPipelineConfiguration.java`:
```java
Counter receivedCounter = Counter.builder("events.reservation.received")
        .description("Reservation events consumed from Kafka")
        .register(meterRegistry);
Counter enrichedCounter = Counter.builder("events.reservation.enriched")
        .description("Reservation events enriched and published")
        .register(meterRegistry);

return event -> {
    receivedCounter.increment();
    // ... enrichment logic ...
    enrichedCounter.increment();
    return new EnrichedReservationEvent(...);
};
```

(Analogicznie dla `routePipeline`.)

**Czy poprawnie?** ✅ Wzorzec „received vs enriched" pokazuje czy pipeline coś gubi (received > enriched = exceptions + return null). W aktualnym kodzie różnica = 0 (każde received → 1 enriched), bo nie ma null-returns.

**Wartość biznesowa**: throughput pipeline'a. Skok received bez wzrostu enriched = pipeline się zatkał (np. exception). Stały gap = pewien % wiadomości jest dropped.

---

#### Panel ID:35 — "Events Consumed (stacked)"

**Co pokazuje**: skumulowane (stacked) eventy reservations + routes w okresie.

**Zapytania**:
```promql
sum(increase(events_reservation_received_total[$__rate_interval]))
sum(increase(events_route_received_total[$__rate_interval]))
```

**Stacking**: `"stacking": {"mode": "normal"}` — linie układają się jedna na drugiej, pokazując łączny ruch.

**Czy poprawnie?** ✅ Wizualne uzupełnienie panelu 34. Stacked = zobaczysz **łączny load** Kafki, plus widzisz, który strumień dominuje.

**Wartość biznesowa**: capacity planning. „Jaki łączny throughput Kafki obsługujemy?" To, plus consumer lag (Resources USE), daje pełny obraz nasycenia Kafki.

---

#### Panel ID:36 — "Event Flow Totals" (stat)

**Co pokazuje**: 4 wartości jednoliczne — total events przeprocesowanych od startu.

**Zapytania**:
```promql
sum(events_reservation_received_total) or vector(0)
sum(events_reservation_enriched_total) or vector(0)
sum(events_route_received_total) or vector(0)
sum(events_route_enriched_total) or vector(0)
```

**`or vector(0)`** — fallback gdy metryka nie istnieje (np. po świeżym restarcie). Bez tego panel pokaże „No data" zamiast „0".

**Czy poprawnie?** ✅ Wzorzec ze świetnym pre-emptive `or vector(0)`. Inne panele mogłyby skopiować ten wzorzec.

**Wartość biznesowa**: licznik życia. „Ile zdarzeń przeszło przez nasz pipeline od ostatniego restartu?" — sanity check, czy serwis żyje i pracuje.

---

### Sekcja: Resources (USE) — dodane w Fazie 3

Te panele dodaliśmy we wrześniu 2026, opisałem każdy w `plany/plan-redesign-metryk-red-use.md`. Krótko tutaj.

#### Panel ID:501 — "Hikari Pool Utilization (active / max)"
- Metryka: `hikaricp_connections_active / hikaricp_connections_max` (built-in z `HikariCP-Micrometer`)
- **USE Utilization** dla puli DB
- > 0.8 = pula sizing problem

#### Panel ID:502 — "Outbox Pending Events"
- Metryka: `reservations_outbox_pending_events` — Gauge dodany w Fazie 1
- **USE Saturation** dla outbox queue
- Kod: `InboxPublisher.java:57-60`:
  ```java
  Gauge.builder(METRIC_PENDING_EVENTS, pendingEventsCount, AtomicLong::doubleValue)
          .description("Outbox events with status=PENDING")
          .baseUnit("events")
          .register(meterRegistry);
  ```
  Wartość refresh'owana po każdym poll cycle przez `repo.countPending()`.

#### Panel ID:503 — "Hikari Pool Pending Threads + timeouts"
- Metryki: `hikaricp_connections_pending`, `hikaricp_connections_timeout_total`
- **USE Saturation + Errors** dla puli
- Pending > 0 = wątki czekają na connection = pula za mała / zapytania za wolne

#### Panele 511-513 (Trade Route Planner)
- 511: Tomcat busy/max — **Utilization** webserwera
- 512: Kafka producer queue time avg — **Saturation** producenta
- 513: JVM heap util % — **Utilization** pamięci

#### Panele 521-523 (Telemetry Pipeline)
- 521: Kafka consumer records lag max — **Saturation** consumera (kluczowe dla event-driven)
- 522: Kafka producer queue time
- 523: JVM heap util %

Wszystkie te metryki **zaczęły się emitować dopiero po Fazie 1** (`management.metrics.enable.kafka=true`). Przed tym Kafka client metrics były wyłączone domyślnie.

---

## Część III — Wzorce i anty-wzorce zauważone w panelach

### Powtarzające się wzorce poprawne

1. **`rate(_sum) / rate(_count) × 1000` dla średniej w ms** — konsekwentnie użyte dla średnich latencji.
2. **`clamp_min(..., 1e-9)`** — chroni przed `0/0` w okresach bez ruchu. Lepsze niż `1` (które dawałoby fałszywą wartość przy małym counter'ze).
3. **`sum by (label)` żeby zwinąć instance** — agregacja między instancjami (mamy 2× starport, 2× planner).
4. **`outcome=~"a|b"` regex match** — selektywne grupowanie po labelach.
5. **`or vector(0)` fallback** — żeby panel nie pokazywał „No data" gdy serwis świeżo wstał.

### Powtarzające się anty-wzorce

1. **Średnia z Summary zamiast p99 z histogramu** — w 2-instancyjnym deploymencie p99 z Summary jest matematycznie zły. Trzeba **histogram + `histogram_quantile()`**. Prawie wszystkie panele latencji to mają poprawnie po Fazie 1; pozostały panel 21 mógłby dorzucić p99.

2. **`sum by (X) (A / B)` zamiast `sum(A) / sum(B)`** — dwa miejsca: panele 26 (risk score) i 27 (ETA). Sumowanie średnich != średnia sum. Dla dużych liczb i podobnych instancji różnica jest mała, ale matematycznie błędne.

3. **Brak `rate()` na _sum / _count** — panele 26 i 27. Dają „średnia od początku" zamiast „średnia w okienku".

4. **Lazy registration metryki bez fallback** — panel 27 ETA pokazuje pustkę przed pierwszym requestem. Można naprawić przez `or vector(0)` albo placeholder `.record(0)` w konstruktorze (z taga schematycznymi takimi jak hot path).

5. **Dead query na nieistniejące metryki** — panel 31 (telemetry filter latency) referuje `telemetry_filter_*_seconds_*`, które nie są emitowane przez kod.

6. **Dwa osobne Counters dla success/error** — panele 1 i 24. RED hint: jeden counter na **wszystko** + osobny na **errors**, nie na success. Faza 2 dodała poprawny wzorzec, ale panele jeszcze go nie używają.

7. **`process_cpu_usage * 100` bez `legendFormat` z instance** — panel 16 będzie miał dwie linie z tymi samymi etykietami `{{job}}`.

---

## Część IV — Co warto zrobić dalej (poza zakresem tej dokumentacji)

1. Naprawić panel 27 ETA: `rate()` + `sum(num)/sum(denom)` + fallback dla pustego.
2. Naprawić panel 26 risk: jak wyżej, plus decyzja czy histogram zostaje.
3. Zarejestrować Observations dla `telemetry.filter.*` (panel 31).
4. Dodać p99 do panelu 21 (Reservation Step Avg Latency) — histogramy są, czyli `histogram_quantile()` zadziała.
5. Zmigrować panele 1 i 24 na Faza-2 metryki (`*_requests_total` superset).
6. Dodać `legendFormat: "{{job}} ({{instance}})"` do panelu 16.

Zostały zapisane w `plany/plan-redesign-metryk-red-use.md` § „Co zostaje po stronie".

---

## Część V — Słownik szybki (dla nowych w Micrometer/Prometheus)

| Termin | Co znaczy |
|---|---|
| **Counter** | Monotoniczny licznik, tylko rośnie. W Prometheusie dodaje sufiks `_total`. |
| **Timer** | Mierzy czas trwania. Daje `_count`, `_sum`, `_max`, opcjonalnie `_bucket{le=…}` z histogramem. |
| **DistributionSummary** | Mierzy rozkład wartości (nie czasu). Strukturalnie jak Timer. |
| **Gauge** | Wartość chwilowa. Może rosnąć i maleć. |
| **Observation** | Wzorzec Spring Boot 3 — auto-tworzy Timer + tracing span. |
| **`baseUnit`** | Jednostka dodawana do nazwy: `baseUnit("seconds")` → `_seconds_*`. |
| **Tag (label)** | Dimensja metryki. Każda kombinacja tagów = osobna seria. |
| **High cardinality** | Tag z wieloma unikatowymi wartościami (UUID, timestamp). Anti-pattern. |
| **`rate()`** | Pochodna w okienku, dla counterów rosnących. Daje „events/sec". |
| **`increase()`** | Suma przyrostu w okienku. `increase = rate × duration`. |
| **`histogram_quantile()`** | Liczy percentyl z bucketów histogramu. Wymaga `_bucket{le=…}` series. |
| **`clamp_min(x, n)`** | Wymusza minimum n na wartości x. Chroni przed dzieleniem przez 0. |
| **`sum by (label) (...)`** | Agregacja sumująca, zachowując tylko podany label. |
| **SLO bucket** | Granica histogramu odpowiadająca celowi SLO (np. 250ms). |
| **`management.metrics.enable.<x>=true`** | Włącza eksport built-in metryk grupy `<x>` (jvm, kafka, hikari, …). |
| **`/actuator/prometheus`** | Endpoint, z którego Prometheus scrapuje metryki w formacie tekstowym. |
| **`/actuator/metrics`** | Endpoint Spring Boot do queryowania metryk po nazwie (JSON). |

---

*Dokument utrzymywany ręcznie. Po każdej zmianie dashboardu / metryk warto go zaktualizować.*
