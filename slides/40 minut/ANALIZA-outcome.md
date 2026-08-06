# Jak działa tag `outcome` — analiza licznika domenowego

> Uzupełnienie do [`ANALIZA-wyjatkow-i-bledow.md`](ANALIZA-wyjatkow-i-bledow.md). Tamten dokument
> opisuje **jakie błędy powstają**; ten — **jak są zliczane** i co z tego widać w Prometheusie.
> Dotyczy klasy `TradeRoutePlannerClient` na gałęzi `demo`.

```java
static final String OUTCOME_SUCCESS = "success";
static final String OUTCOME_DOMAIN = "domain";
static final String OUTCOME_INFRASTRUCTURE = "infrastructure";
static final String OUTCOME_EMPTY_RESPONSE = "empty_response";
static final String OUTCOME_CIRCUIT_OPEN = "circuit_open";
```

---

## 1. To jest jedna metryka, nie pięć

Te stałe to **wartości jednej etykiety**, nie nazwy osobnych metryk. Cała mechanika siedzi
w jednej linijce metody `count()`:

```java
Counter.builder(METRIC_OUTCOME)      // nazwa metryki — ZAWSZE ta sama
        .tag("outcome", value)        // ← tu ląduje jedna z pięciu stałych
        .register(meterRegistry)
```

Trzy pojęcia, które łatwo pomylić:

| Pojęcie | Wartość |
|---|---|
| **nazwa metryki** | `reservations.route.plan.outcome` (stała `METRIC_OUTCOME`) |
| **nazwa etykiety** | `outcome` |
| **wartości etykiety** | `success`, `domain`, `infrastructure`, `empty_response`, `circuit_open` |

W Micrometerze każda unikalna para *(nazwa, zestaw tagów)* to osobny `Meter.Id`, czyli osobny
licznik. Pod jedną nazwą powstaje więc do pięciu niezależnych liczników, rozróżnianych etykietą:

```
reservations_route_plan_outcome_total{outcome="success"}         70
reservations_route_plan_outcome_total{outcome="infrastructure"}  28
reservations_route_plan_outcome_total{outcome="circuit_open"}   184
```

*(odczyt z działającego stacku po scenariuszu `-Chaos down`)*

---

## 2. Gdzie każda wartość jest ustawiana

Jedna ścieżka wykonania = dokładnie jedna stała:

| Stała | Miejsce w kodzie | Kiedy |
|---|---|---|
| `OUTCOME_DOMAIN` | `onStatus(is4xxClientError)` | planner odpowiedział 4xx (u nas 422) |
| `OUTCOME_INFRASTRUCTURE` | `onStatus(is5xxServerError)` **oraz** `catch (Exception e)` | planner zwrócił 5xx **albo** timeout / connection refused |
| `OUTCOME_EMPTY_RESPONSE` | `if (response == null)` | 2xx, ale puste ciało |
| `OUTCOME_SUCCESS` | po sprawdzeniu `response != null` | trasa wróciła |
| `OUTCOME_CIRCUIT_OPEN` | `circuitOpenFallback` | obwód otwarty, ciało metody w ogóle się nie wykonało |

`infrastructure` ma **dwa** miejsca zapisu — bo dla bezpiecznika i dla biznesu „planner zwrócił
500" i „planner nie odpowiedział" to ta sama kategoria: awaria zależności.

---

## 3. Dlaczego dokładnie jeden zapis na wywołanie

To nie przypadek, tylko konsekwencja układu bloków `catch`:

```java
} catch (RouteUnavailableException e) {
    throw e;                       // ← już policzone w onStatus / if — przepuść bez liczenia
} catch (Exception e) {
    count(OUTCOME_INFRASTRUCTURE);  // ← liczy tylko to, czego nikt wcześniej nie policzył
    ...
}
```

Bez pierwszego `catch`-a wyjątek rzucony w `onStatus` wpadłby do drugiego i **policzyłby się
drugi raz**. Dokładnie ten błąd mieliśmy wcześniej z fallbackiem typu `Throwable`: każda porażka
lądowała w dwóch licznikach naraz, a suma błędów była dwukrotnie zawyżona.

---

## 4. To liczy PRÓBY, nie operacje

`count()` żyje wewnątrz `plan()`, czyli **wewnątrz** proxy retry. Przy domyślnej kolejności
aspektów (`Retry( CircuitBreaker( plan() ) )`) retry ponawia całe wywołanie, więc operacja
z jednym ponowieniem zostawia **dwa** wpisy — np. dwa razy `infrastructure`.

Jest to spójne z tym, jak w tej konfiguracji liczy bezpiecznik, ale trzeba o tym pamiętać przy
interpretacji:

> `sum(reservations_route_plan_outcome_total)` to liczba **prób HTTP**, nie liczba rezerwacji.

Pod profilem `cb-outer` (bezpiecznik na zewnątrz) ta metryka **nadal liczy próby** — retry jest
wtedy wewnątrz i tak samo powtarza `plan()`. Zmienia się tylko to, co liczy breaker.

---

## 5. Seria nie istnieje, dopóki się nie zwiększy

Micrometer rejestruje licznik dopiero przy pierwszym `count(...)`. Dlatego na żywym stacku widać
**trzy serie zamiast pięciu**: `domain` i `empty_response` nigdy się nie odpaliły, bo planner nie
odrzucił żadnej trasy (zasięg statku zawsze ≥ 5 ly, próg odrzucenia to 1 ly) i zawsze zwracał ciało.

> **Praktyczna konsekwencja:** panel filtrujący po `outcome="domain"` pokaże **„No data", a nie
> zero**. To nie awaria dashboardu — to metryka, która jeszcze nie zaistniała. Ta sama zasada
> dotyczy kafelka „Trasy oddane do fallbacku" przy zdrowym starcie stacku.

---

## 6. Po co `computeIfAbsent`

```java
outcomeCounters.computeIfAbsent(outcome, value -> Counter.builder(...).register(meterRegistry))
        .increment();
```

`register()` jest **idempotentne** — dla tego samego `Meter.Id` zwraca istniejący licznik, więc
poprawność nie zależy od tej mapy. Cache jest wyłącznie dla wydajności: bez niego każde wywołanie
alokowałoby nowy `Meter.Id` i robiło lookup w rejestrze, w dodatku w gorącej ścieżce błędu.

---

## 7. Po co stałe zamiast literałów

1. **Literówka nie wysypie się z błędem.** `"cirucit_open"` skompilowałoby się i utworzyło cichą,
   szóstą serię, której nikt nie zauważy — bo panel filtruje po poprawnej nazwie.
2. **Jedno miejsce pokazuje cały słownik.** Pięć linijek mówi, jakie wartości może przyjąć ta
   etykieta. To dokumentacja, której nie da się zdezaktualizować.
3. **Zmiana nazwy to refaktor, nie grep** — a właśnie przez taką zmianę przechodziliśmy
   (`errorType` → `outcome`).
4. Są **package-private, nie private** — żeby testy w tym samym pakiecie mogły asertować po nich
   zamiast po literałach. Uczciwie: *na razie żaden test z nich nie korzysta*, więc to widoczność
   „na zapas".

---

## 8. Czego NIE wolno tu wrzucać

Wartości tej etykiety muszą pozostać **niskokardynalne** — pięć stałych to pięć serii czasowych.
Gdyby ktoś dorzucił kod HTTP (`outcome="http_503"`), ID rezerwacji albo kod portu, liczba serii
rośnie razem z ruchem i wysadza bazę metryk.

Status HTTP jest już w komunikacie wyjątku (`…planner failed with HTTP 503`) i w logu — tam jest
jego miejsce. Rzeczy o wysokiej kardynalności należą do trace'ów i logów, nie do etykiet metryk.

---

## 9. Co można z tego policzyć

```promql
# rozbicie na przyczyny
sum by (outcome) (rate(reservations_route_plan_outcome_total[5m]))

# success ratio — JEDNO wyrażenie, bo to jedna metryka
sum(rate(reservations_route_plan_outcome_total{outcome="success"}[5m]))
  / sum(rate(reservations_route_plan_outcome_total[5m]))

# ile rezerwacji straciło trasę przez odcięcie (panel „Trasy oddane do fallbacku")
sum(increase(reservations_route_plan_outcome_total{outcome="circuit_open"}[$__range]))
```

Ten drugi zapis jest właśnie powodem, dla którego zastąpiliśmy parę `…success` / `…errors` jednym
meterem z tagiem: przy dwóch osobnych nazwach metryk stosunku sukcesów nie da się policzyć bez
łączenia dwóch szeregów.

---

## 10. Dlaczego nazwa NIE brzmi `reservations.route.plan`

Bo ta nazwa należy już do **Timera generowanego przez `Observation`** w adapterze
(`reservations_route_plan_seconds_*`, używany m.in. przez `distributed_tracing.json`).
Micrometer odrzuca dwa różne typy metryki pod jedną nazwą — aplikacja nie wstałaby.
Stąd sufiks `.outcome`.
