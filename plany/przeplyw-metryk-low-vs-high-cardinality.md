# Przepływ metryk: `lowCardinalityKeyValue` vs `highCardinalityKeyValue`

Dokument tłumaczy fundamentalną decyzję, którą podejmujesz przy każdym `Observation.createNotStarted(...)` w naszym kodzie: **czy ten klucz idzie do `low`, czy do `high` cardinality**. To nie jest kosmetyka — od tej decyzji zależy, czy:
- Prometheus przeżyje (low → tag, high → tylko Tempo)
- Czy znajdziesz konkretną rezerwację w trace'ach o 3 nad ranem (high → masz, low → nie masz)
- Czy alerty mają sens (musisz mieć denominator — niska cardinality)

W Micrometer Observation API dwa wywołania:

```java
.lowCardinalityKeyValue("starport", "ALPHA-BASE")          // < 50 unikalnych wartości
.highCardinalityKeyValue("reservationId", "42")            // miliony unikalnych wartości
```

…wyglądają prawie identycznie. Ale pod spodem **idą do różnych systemów**, służą **różnym celom** i **mieszanie ich** prowadzi do incydentów.

---

## TL;DR — diagram w 60 sekund

```
┌─────────────────────────────────────────────────────────────────┐
│  Observation.createNotStarted("reservations.outbox.append", …)  │
│         .lowCardinalityKeyValue("binding", "reservations-out")  │
│         .lowCardinalityKeyValue("eventType", "ReservationConf") │
│         .highCardinalityKeyValue("reservationId", "42")         │
│         .observe(() -> { ... })                                 │
└──────────┬──────────────────────────────────┬───────────────────┘
           │                                  │
           │   "low" → DWIE drogi             │   "high" → JEDNA droga
           │                                  │
           ▼                                  ▼
   ┌───────────────┐              ┌──────────────────────┐
   │ Micrometer    │              │ Span (OTLP exporter) │
   │ Timer (auto)  │              │                      │
   │               │              │  attributes:         │
   │ tag: binding  │              │    binding           │
   │ tag: eventType│              │    eventType         │
   │               │              │    reservationId  ◄── wyłącznie tu!
   └──────┬────────┘              └──────────┬───────────┘
          │                                  │
          │ /actuator/prometheus             │ OTLP HTTP
          │                                  │
          ▼                                  ▼
   ┌───────────────────┐           ┌─────────────────┐
   │   Prometheus      │           │      Tempo      │
   │                   │           │                 │
   │ reservations_     │           │ Trace Search,   │
   │   outbox_append_  │           │ TraceQL,        │
   │   seconds_*{      │           │ waterfall       │
   │     binding=…,    │           │                 │
   │     eventType=…   │           │ Find by         │
   │   }               │           │ reservationId=42│
   └───────────────────┘           └─────────────────┘
       agregaty + alerty                konkretne przypadki
       (RED, p99, rate)                 (incident debug)
```

**Klucz**: low cardinality idzie do **obu** systemów. High cardinality **tylko do Tempo**. To jest by-design, nie ograniczenie.

---

## Część I — Co Micrometer Observation robi „pod spodem"

`Observation.observe(...)` to nie magia, to dwa **handlery** uruchamiane synchronicznie:

```
Observation.observe(() -> work)
   │
   ▼
   start() — wywołuje listę zarejestrowanych ObservationHandler'ów:
              ┌───────────────────────────────────────────┐
              │ DefaultMeterObservationHandler            │  ◄── Micrometer auto-Timer
              │   │                                       │
              │   ▼ tworzy Timer.Sample                   │
              └───────────────────────────────────────────┘
              ┌───────────────────────────────────────────┐
              │ DefaultTracingObservationHandler          │  ◄── OpenTelemetry/Tracing
              │   │                                       │
              │   ▼ tworzy Span                           │
              └───────────────────────────────────────────┘
   │
   ▼
   work()  — Twój kod
   │
   ▼
   stop() — zamyka Timer.Sample i Span
              │
              ▼
              Timer.Sample → reservations_outbox_append_seconds_*
              Span         → wysłany przez OTLP do Tempo
```

W naszym `application.yml` mamy włączone oba handlery (przez `spring-boot-starter-actuator` + `spring-boot-starter-actuator` + tracing dependencies). Każdy `Observation.observe(...)` produkuje **dwie rzeczy** równolegle: punkt w histogramie Timera + span tracingowy.

I tu wchodzi rozróżnienie **low vs high**:

- `DefaultMeterObservationHandler` filtruje key-values: kopiuje **tylko low cardinality** jako Micrometer tags. High cardinality są ignorowane.
- `DefaultTracingObservationHandler` kopiuje **wszystkie** key-values jako span attributes.

Stąd dwie ścieżki, jeden API.

---

## Część II — Dlaczego ten split istnieje

### Problem cardinality w Prometheusie

Prometheus przechowuje metryki w postaci **time series**. Każda unikalna kombinacja `(metric_name, label1=value1, label2=value2, ...)` to **osobna seria** — z osobnym katalogiem na dysku, osobnym indeksem w pamięci, osobnym wpisem w WAL.

Cardinality = liczba unikalnych kombinacji labeli.

| Liczba serii | Stan |
|---|---|
| < 100 000 | komfort |
| 100 000 – 1 000 000 | OK, monitoruj RAM |
| 1 000 000 – 10 000 000 | granica wytrzymałości typowego deploya |
| > 10 000 000 | OOM, scrape spowalnia, alerty się gubią |

Pojedynczy label `reservationId` z 1 000 000 unikalnych wartości × pojedyncza metryka × 70 bucketów histogramu = **70 000 000 serii**. Prometheus pada w godzinę.

Dlatego klucze, które mają `unbounded growth` (każdy nowy event = nowa wartość), **NIGDY** nie powinny być labelem metryki. Musi być zatamowanie.

### Tempo nie ma tego problemu

Tempo przechowuje **spany**, nie serie. Każdy span to dokument indeksowany po `traceId`. Atrybuty spana są wewnątrz dokumentu, nie tworzą struktur indeksowych po wartości. Stąd:
- 1 000 000 spanów z atrybutem `reservationId=<unikat>` to 1 000 000 dokumentów × ~1 KB = ~1 GB. Ok.
- W Prometheusie analogiczna konstrukcja = OOM.

Inny model danych = inne ograniczenia = inne use case'y.

---

## Część III — Co dokładnie low/high robi w kodzie

### Definicje z Micrometer

W klasie `Observation`:

```java
public Observation lowCardinalityKeyValue(String key, String value) {
    // dorzuca {key, value} do KeyValues this.lowCardinalityKeyValues
}

public Observation highCardinalityKeyValue(String key, String value) {
    // dorzuca {key, value} do KeyValues this.highCardinalityKeyValues
}
```

Dwie osobne kolekcje wewnątrz Observation. Handlery czytają je niezależnie.

### Co czyta `DefaultMeterObservationHandler`

```java
@Override
public void onStop(Observation.Context context) {
    Timer.builder(context.getName())
            .tags(createKeyValues(context.getLowCardinalityKeyValues()))   // ← TYLKO low
            .register(meterRegistry)
            .record(getDuration(context));
}
```

Tylko `getLowCardinalityKeyValues()`. High są ignorowane przez ten handler.

### Co czyta `DefaultTracingObservationHandler`

```java
@Override
public void onStop(Observation.Context context) {
    Span span = ...;
    context.getLowCardinalityKeyValues().forEach(kv -> span.tag(kv.getKey(), kv.getValue()));
    context.getHighCardinalityKeyValues().forEach(kv -> span.tag(kv.getKey(), kv.getValue()));
    span.end();
}
```

Oba zestawy. W spanie nie ma rozróżnienia — atrybut to atrybut, niezależnie od cardinality.

---

## Część IV — Konkrety z naszego kodu

### `OutboxAppender.java` — klasyczny wzorzec

```java
Observation.createNotStarted("reservations.outbox.append", () -> senderContext, observationRegistry)
        .lowCardinalityKeyValue("binding", reservationsBinding)        // 3 unikalne wartości
        .lowCardinalityKeyValue("eventType", "ReservationConfirmed")    // 2 unikalne wartości
        .highCardinalityKeyValue("reservationId", String.valueOf(reservation.getId()))   // miliony
        .observe(() -> outboxWriter.save(...));
```

**Co Prometheus widzi**:
```
reservations_outbox_append_seconds_count{binding="reservations-out",eventType="ReservationConfirmed",instance="…",job="starport-registry"}    1234
reservations_outbox_append_seconds_sum{...}                                                                                                   45.6
reservations_outbox_append_seconds_bucket{...,le="0.05"}                                                                                       1100
```

Brak `reservationId` jako label. Jest 6 unikalnych kombinacji labeli (3 bindingi × 2 eventType, ale praktycznie tylko 1 kombinacja jest używana = `binding=reservations-out, eventType=ReservationConfirmed`). Cardinality < 10. Bezpiecznie.

**Co Tempo widzi** (w waterfall view):
```
Span: reservations.outbox.append
Service: starport-registry
Kind: PRODUCER
Duration: 12 ms
Status: OK

Span attributes:
  binding         = reservations-out
  eventType       = ReservationConfirmed
  reservationId   = 42                    ← konkretny ID
```

Każda rezerwacja ma swój unikalny span z swoim `reservationId`.

### `InboxPublisher.java` — analogicznie

```java
Observation publishObs = Observation.createNotStarted(OBS_PUBLISH, () -> receiverCtx, observationRegistry)
        .lowCardinalityKeyValue("binding", outboxEvent.getBinding())
        .lowCardinalityKeyValue("eventType", outboxEvent.getEventType())
        .highCardinalityKeyValue("outboxId", String.valueOf(outboxEvent.getId()));
```

`outboxId` to klucz primary key z tabeli `event_outbox` — monotonicznie rosnący, każdy event = nowa wartość. Klasyczny przykład wysokiej cardinality. Trafia tylko do spana.

### `CreateHoldReservationService.java` — wszystko low

```java
Observation.createNotStarted(OBSERVATION_NAME, observationRegistry)
        .lowCardinalityKeyValue("starport", command.destinationStarportCode())       // ~20-50 starports
        .lowCardinalityKeyValue("shipClass", command.shipClass().name())              // 4 wartości
        .observe(() -> persistenceFacade.createHoldReservation(command));
```

Tu high cardinality **nie ma** — to ogranicza diagnostykę: jeśli ktoś zgłosi „rezerwacja 42 trwała wiecznie", w Tempo nie znajdziesz spana po ID 42. Można by dodać:

```java
.highCardinalityKeyValue("reservationId", String.valueOf(reservationId))
```

…ale `reservationId` w tym momencie jeszcze nie istnieje (bo właśnie próbujemy go utworzyć). Można dodać `customerCode` jako high (nieprzewidziany zbiór), albo `shipCode`. Decyzja architekta — co jest najczęstszym kluczem wyszukiwania incydentów?

---

## Część V — Use case 1: alerting (low cardinality jest niezbędna)

Cel: alarm „p99 outbox append latency > 100 ms przez 5 minut".

PromQL:
```promql
histogram_quantile(
  0.99,
  sum by (le, binding) (rate(reservations_outbox_append_seconds_bucket[5m]))
) > 0.1
```

To zadziała tylko dlatego że:
- `binding` jest **labelem metryki** (dzięki `lowCardinalityKeyValue`)
- Możemy zsumować histogramy między instancjami przez `sum by (le, binding)`
- Liczba serii jest skończona i mała → Prometheus radzi sobie z `rate()` i kwantyl interpolacją

Gdyby zamiast `binding` był tam `reservationId`:
- Każda rezerwacja → osobna seria histogramu z jednym samplem
- `histogram_quantile(0.99, ...)` na seriach z 1-2 samplami = NaN albo szum
- Liczba serii rośnie liniowo z liczbą rezerwacji = OOM
- Alert przestaje działać po godzinie

**Reguła**: jeśli chcesz alertować, agregować, liczyć rate — użyj `lowCardinalityKeyValue`.

---

## Część VI — Use case 2: incident debugging (high cardinality jest niezbędna)

### Scenariusz

03:14 nad ranem, Twój pager budzi Cię alertem:
> **HTTP error rate > 1% na starport-registry przez 5 minut. Klikam → Grafana panel 4 → 5xx skoczyło z 0% do 4%.**

Idziesz krok po kroku:

#### Krok 1: znaleźć konkretne logi

W panelu 61 (Error & Warning Logs) widzisz:
```
[starport-registry,7c3a9b8f5d2e1c4a,abc123] ERROR Failed to confirm reservation 4847: 
   ReservationConfirmationException: not found
```

Klikasz `traceId=7c3a9b8f5d2e1c4a`. Otwiera się Tempo waterfall.

#### Krok 2: znaleźć przyczynę w trace

Waterfall pokazuje 8 spanów. Klikasz `reservations.confirm` — failed po 4 sekundach. Klikasz w niego — atrybuty:
```
Span: reservations.confirm
Status: ERROR
Duration: 4127 ms

Attributes:
  starport       = ALPHA-BASE
  reservationId  = 4847                ← masz!
```

Wracasz do logów: filter `traceId=7c3a9b8f5d2e1c4a` lub `"reservation 4847"`. Widzisz pełen ślad operacji.

#### Krok 3: czy to pojedynczy przypadek?

W Tempo Search albo w panelu 73 (TraceQL Metrics) wpisujesz:
```traceql
{ name = "reservations.confirm" && status = error }
```

Widzisz 6 trace'ów w ostatnich 5 minutach, każdy z innym `reservationId`: 4847, 4912, 4988, 5021, 5034, 5067. Klikasz w każdy, sprawdzasz czy mają wspólny mianownik:
- `starport=ALPHA-BASE` we wszystkich? 5 z 6 → tak, prawie wszystkie.
- `customerCode` jakiś jeden? 6 różnych. → nie, problem nie po stronie konkretnego klienta.

Hipoteza: problem dotyczy **starport ALPHA-BASE**, niezależnie od klienta. Może DB lock na bayach, może dyskowy I/O.

#### Krok 4: weryfikacja

Sprawdzasz USE dashboard → panel Hikari pool: `pending threads = 25` (saturacja!). Idziesz do logów PostgreSQL → seria slow queries na `bay` table. Diagnoza: deadlock przez współbieżne `FOR UPDATE SKIP LOCKED`.

**Cały proces = ~5 minut.** Bez `highCardinalityKeyValue("reservationId", ...)` w którymś z spanów byś tej diagnozy nie postawił bez czytania kodu.

### Co konkretnie umożliwia high cardinality

W Tempo Search / TraceQL możesz pisać:

```traceql
# Znajdź konkretny event po ID (klasyczny incident lookup):
{ outboxId = "1847" }

# Wszystkie spany konkretnej rezerwacji:
{ reservationId = "4847" }

# Wszystkie błędy w obrębie konkretnego customer:
{ customerCode = "CUST-9876" && status = error }
```

W Prometheusie analogicznych zapytań **nie da się napisać** (bo nie ma takich serii) i **dobrze że nie ma** (bo by rozwaliły infrastrukturę).

---

## Część VII — Anti-patterns

### Pomyłka 1: high jako Prometheus label przez nieuwagę

```java
// ŹLE:
.lowCardinalityKeyValue("reservationId", String.valueOf(id))     // ← powinno być high!
```

Niestety nie ma compile-time check. Liczy się tylko self-discipline + code review. Reguła kciuka:
- Czy ten klucz może mieć > 50 unikalnych wartości w produkcji za 6 miesięcy?
- Czy jest monotonicznie rosnący (ID, sequence, timestamp)?
- Czy zawiera dane użytkownika (email, UUID, randomString)?

Tak na którekolwiek = `highCardinalityKeyValue`.

### Pomyłka 2: low z dynamiczną wartością

```java
// ŹLE — minute precision = 60 wartości na godzinę = 1440/dobę:
.lowCardinalityKeyValue("hour", String.valueOf(LocalDateTime.now().getHour()))

// ŹLE — sekunda × 86400 = nigdy:
.lowCardinalityKeyValue("timestamp", String.valueOf(System.currentTimeMillis()))
```

Time labels prawie zawsze są high cardinality. Wyjątek: bardzo gruba rozdzielczość (`hour-of-day` 0-23, `day-of-week` 0-6) — ale i to rzadko ma sens.

### Pomyłka 3: high cardinality zamiast osobnej operacji

```java
// ŹLE — tworzy span dla każdego elementu w batch:
batch.forEach(item ->
    Observation.createNotStarted("batch.process.item", registry)
            .highCardinalityKeyValue("itemId", item.getId())
            .observe(() -> processItem(item))
);
```

Setki spanów na jeden trace = waterfall nieczytelny + storage Tempo eksploduje. Lepsze:

```java
Observation.createNotStarted("batch.process", registry)
        .lowCardinalityKeyValue("size_bucket", batch.size() > 100 ? "large" : "small")
        .observe(() -> batch.forEach(this::processItem));   // bez per-item spana
```

Pojedynczy span batch'a, items sumarycznie. Per-item details mogą iść do logów w środku, jeśli potrzebne.

### Pomyłka 4: brak high cardinality w ogóle

Niektóre Observations w naszym kodzie **nie mają** `highCardinalityKeyValue` — np. `CreateHoldReservationService`, `FeeCalculatorService`, `ConfirmReservationService`. Skutek: nie da się znaleźć ich spana po `reservationId`.

To jest świadoma decyzja (nie blocker), ale gdy dojdzie do incidentu „rezerwacja X była wolna", musisz iść okrężną drogą:
1. Znajdź log z `reservationId=X`
2. Z log linii wyciągnij `traceId`
3. Z `traceId` znajdź trace w Tempo

Bezpośrednie wyszukanie po `reservationId` w TraceQL byłoby szybsze.

**Akcja do rozważenia**: dodać `.highCardinalityKeyValue("reservationId", id)` do Observations w `FeeCalculatorService`, `ConfirmReservationService` po policzeniu reservationId.

---

## Część VIII — Reguły kciuka — szybkie decyzje

### Czy ten klucz to low czy high?

| Kryterium | Low | High |
|---|---|---|
| Liczba unikalnych wartości | < 50 | > 50 |
| Wzrost w czasie | bounded | unbounded |
| Wartości znane z góry | tak (enum, lista) | nie |
| Czy chcesz alertować po tej wymiarze? | tak → low | (nieistotne) |
| Czy chcesz znaleźć konkretny przypadek po wartości? | (nieistotne) | tak → high |
| Przykłady | `starport`, `shipClass`, `binding`, `eventType`, `outcome`, `severity` | `reservationId`, `customerCode`, `traceId`, `email`, `UUID`, timestamp |

### Decision tree

```
Klucz: ___________
   │
   ▼
   Czy unikalnych wartości w produkcji < 50?
   │
   ├── TAK ──── Czy chcesz po nim agregować/alertować?
   │             │
   │             ├── TAK → lowCardinalityKeyValue
   │             └── NIE → możesz dać low (bezpiecznie)
   │                       albo high (jeśli rzadko używasz)
   │
   └── NIE ──── highCardinalityKeyValue
                (lub w ogóle pomiń, jeśli nieistotne)
```

---

## Część IX — Pełen przegląd low/high w naszym kodzie

| Plik:linia | low | high |
|---|---|---|
| `CreateHoldReservationService:34` | starport, shipClass | — |
| `FeeCalculatorService:37` | starport, shipClass | — |
| `TradeRoutePlannerHttpAdapter:55` | startStarport, destinationStarport | — |
| `ConfirmReservationService:32` | starport | — |
| `OutboxAppender:35` | binding, eventType | **reservationId** ✓ |
| `InboxPublisher:98` | binding, eventType | **outboxId** ✓ |
| `PlanRouteService:63` | originPortId, destinationPortId, shipClass | — |
| `EventPipelineConfiguration` (3 pipelines) | pipeline | — |
| `ReserveBayValidationService` | (validation specific) | — |

**Obserwacja**: tylko 2 z 11 Observations mają `highCardinalityKeyValue`. Pozostałe 9 mają wyłącznie low — co znaczy, że dla tych operacji nie da się znaleźć spana po konkretnym ID.

To nie jest bug — to świadoma decyzja podjęta przy pisaniu (lub niedopatrzenie, niezależnie). Wartą rozważenia konsystencji jest dodanie `reservationId` do hold/fee/confirm/route services — wtedy każdy span flow'u rezerwacji byłby przeszukiwalny po ID.

---

## Część X — Use case'y w czasie

### „Wczoraj wieczorem mieliśmy spike błędów"

- **Prometheus**: `rate(http_server_requests_seconds_count{status=~"5.."}[1h])` w time range „yesterday 21:00-23:00" — widzisz **kiedy** i **ile**.
- **Tempo**: `{ status = error }` w tym samym time range — widzisz **konkretne trace'y**, możesz wybrać 5 najgorszych i przeanalizować.

Low cardinality (status code) → Prometheus widzi spike. High cardinality (traceId, reservationId) → Tempo pokazuje incydenty.

### „Customer X skarży się że zamówienie nie doszło"

- **Prometheus**: nie odpowie. `customerCode` nie jest labelem.
- **Tempo (jeśli ustawione high)**: `{ customerCode = "CUST-9876" }` → wszystkie spany tego customera. Diagnoza w 30 sekund.
- **Tempo (jeśli nie ustawione)**: musisz znaleźć trace przez logi, log po `customerCode`, z linii log wyciągnąć `traceId`. 5 minut.

### „Service A robi się powolny po południu"

- **Prometheus**: `histogram_quantile(0.99, ...)` — widzisz crispy p99 chart, lokalizujesz godzinę.
- **Prometheus z `service.name` jako label**: pokazuje, że tylko `service A` rośnie, nie wszystkie.
- **Tempo**: nieprzydatny — nie szukasz konkretnego incidentu, szukasz trendu.

Low → trend. High → konkret. Każdy ma swoje miejsce.

---

## Część XI — TL;DR

| | `lowCardinalityKeyValue` | `highCardinalityKeyValue` |
|---|---|---|
| Trafia do | Prometheus + Tempo | **TYLKO Tempo** |
| Forma w Prometheusie | label metryki (`{key=value}`) | nie istnieje |
| Forma w Tempo | span attribute | span attribute |
| Cardinality limit | < 50 wartości | nieograniczony |
| Service: alerting | ✅ niezbędne | ❌ niemożliwe |
| Service: incident search | przydatne, ale grube | ✅ niezbędne dla precyzji |
| Przykład w naszym kodzie | `starport`, `binding`, `eventType` | `reservationId`, `outboxId` |
| Co się stanie jak źle wybierzesz | (low → high) tracisz alerty po tej wymiarze | (high → low) Prometheus OOM po godzinach |

**Mantra**: low to **agregat**. High to **konkret**. Oba są niezbędne, służą różnym pytaniom, idą do różnych systemów. Mieszanie ich = problemy operacyjne.

W praktyce: dodaj `lowCardinalityKeyValue` dla każdego wymiaru, po którym chcesz alertować/agregować. Dodaj `highCardinalityKeyValue` dla każdego wymiaru, po którym chcesz znaleźć **konkretny incydent** o 3 nad ranem.

---

*Dokument utrzymywany ręcznie. Po każdym dodaniu nowego Observation w kodzie warto zaktualizować tabelę w Części IX.*
