# Jak działają wyjątki w tym systemie — analiza dla gałęzi `demo`

> Materiał pomocniczy do talku „Działa ≠ działa i ja o tym wiem".
> Uzupełnienie: [`ANALIZA-outcome.md`](ANALIZA-outcome.md) — jak te błędy są ZLICZANE (tag `outcome`). Odpowiada na pytania:
> **kiedy dostajemy 4xx, kiedy 5xx, kiedy timeout, a kiedy to nasz własny błąd** — i co każdy
> z tych przypadków robi z retry, bezpiecznikiem, metryką i odpowiedzią dla klienta.
> Wszystko zweryfikowane w kodzie gałęzi `demo`, nie z pamięci.

---

## 1. Skąd w ogóle biorą się błędy — dwie różne rzeczy

Trzeba rozdzielić dwa źródła, bo mylenie ich jest źródłem większości nieporozumień:

| Źródło | Co to jest | Kto to wywołuje |
|---|---|---|
| **`trade-route-planner`** | realny serwis, który odpowiada 422 albo 500 na podstawie własnej logiki | logika domenowa plannera |
| **Toxiproxy** | proxy na ścieżce sieciowej, wstrzykuje latencję i zrywa połączenia | skrypt `demo-resilience.ps1` |

**Na demie nigdy nie zmieniamy kodu plannera.** Cały chaos jest sieciowy — dlatego scenariusze
`slow`, `partial` i `down` produkują wyłącznie **timeouty i zerwane połączenia**, a nie 5xx.
To ważne przy czytaniu tabel niżej.

---

## 2. Cztery rodzaje porażki — kiedy który

### 2.1. Odmowa domenowa → planner odpowiada **422**

Planner realnie odrzuca żądanie tylko w jednym przypadku (`PlanRouteService.validateFuelRange`):

```java
if (request.fuelRangeLY() < MIN_FUEL_RANGE_LY) {   // MIN = 1.0 ly
    metrics.recordRejected(request, "INSUFFICIENT_RANGE");
    throw new RouteRejectionException(...);
}
```

`RoutePlannerExceptionHandler` mapuje to na **`422 Unprocessable Entity`** z ciałem
`{"error":"ROUTE_REJECTED","reason":"INSUFFICIENT_RANGE", ...}`.

**Kiedy to się dzieje realnie:** gdy klasa statku daje zasięg poniżej 1 ly. W naszym mapowaniu
(`TradeRoutePlannerHttpAdapter.fuelRangeLYForShipClass`) najniższa wartość to `UNKNOWN → 5.0`,
więc **na demie to się nie zdarza**. Ten przypadek widać w testach akceptacyjnych (WireMock zwraca
422) i mógłby wystąpić na produkcji po dodaniu nowej klasy statku.

> Uwaga terminologiczna: mówimy „4xx", ale realnie planner zwraca **422**, nie 400. Nasz adapter
> łapie **cały zakres 4xx** (`HttpStatusCode::is4xxClientError`), więc 400 z niepoprawnego JSON-a
> też trafi w tę samą ścieżkę.

### 2.2. Awaria infrastruktury → planner odpowiada **5xx**

`RoutePlannerExceptionHandler` zwraca `500` dla:
- `EventPublishingException` — nie udało się opublikować zdarzenia do Kafki,
- każdego innego nieobsłużonego `RuntimeException`.

**Na demie nie występuje** — nie psujemy plannera od środka. Ta ścieżka jest pokryta testem
(`ResilienceAspectOrderTest`, WireMock zwraca 503).

### 2.3. Cisza → **timeout albo zerwane połączenie**

To jest **jedyny rodzaj awarii, który realnie produkujemy na scenie**:

| Scenariusz | Co robi Toxiproxy | Co widzi nasz kod |
|---|---|---|
| `-Chaos slow -SlowMs 300` | latencja 300 ms ±50 | wszystko **się udaje**, tylko wolniej |
| `-Chaos slow -SlowMs 440` | latencja 440 ms ±50 | j.w., pojedyncze wywołania ocierają się o próg „wolne" |
| `-Chaos slow -SlowMs 600` | latencja 600 ms ±50 | **sukcesy**, ale wszystkie ≥ 500 ms → liczone jako `slow` |
| `-Chaos partial` | zawiesza część połączeń | część wywołań wisi do read-timeoutu → `SocketTimeoutException` |
| `-Chaos down` | wyłącza proxy | `connection refused` natychmiast |

Spring opakowuje jedno i drugie w **`ResourceAccessException`** (niekontrolowany), który łapiemy
w `catch (Exception e)`.

### 2.4. Nasz własny błąd → **NPE, IllegalState, IllegalArgument**

Np. `Objects.requireNonNull(command)` przy `null`, błąd w mapowaniu `buildRequest`. To **nie jest
awaria zależności** — do plannera w ogóle nie dojechaliśmy.

---

## 3. Drabina progów — dlaczego „wolno" to nie to samo co „padło"

```
   0 ms ────────── 500 ms ────────── 800 ms ──────────────→
       zdrowo      │    "wolne"      │   timeout = PORAŻKA
                   │                 │
  slow-call-duration-threshold   read-timeout (profil demo; bazowo 2000 ms)
```

Próg „wolnego" leży **poniżej** timeoutu — celowo. Gdyby leżał powyżej, nigdy by się nie
uruchomił: każde wolne wywołanie stałoby się wcześniej timeoutem, czyli zwykłym błędem.
Dzięki tej szczelinie wywołanie trwające 600 ms **kończy się sukcesem** (klient dostaje trasę),
ale bezpiecznik już wie, że coś jest nie tak. To jest mechanika, na której stoi cały AKT 1.

Do tego `connect-timeout: 200 ms` — nawiązanie połączenia albo jest szybkie, albo wcale.

---

## 4. Jak to jest tłumaczone na wyjątki — jedno miejsce

Cała klasyfikacja dzieje się w `TradeRoutePlannerClient.plan(...)`:

```java
.onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
    count("domain");
    throw new RoutePlanningRejectedException(origin, destination, res.getStatusCode().value());
})
.onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
    count("infrastructure");
    throw new RoutePlannerUnavailableException(origin, destination, res.getStatusCode().value());
})
// ...
if (response == null) {                     // puste ciało
    count("empty_response");
    throw new RoutePlannerUnavailableException(origin, destination, NO_RESPONSE);
}
// ...
} catch (Exception e) {                     // timeout / connection refused
    count("infrastructure");
    log.warn("Failed to plan route from {} to {}: {}", origin, destination, e.toString());
    throw new RoutePlannerUnavailableException(origin, destination, e);
}
```

Plus **fallback**, który odpala się wyłącznie przy otwartym obwodzie:

```java
private Route circuitOpenFallback(..., CallNotPermittedException e) {
    count("circuit_open");
    throw new RoutePlannerUnavailableException(origin, destination, e);
}
```

### Hierarchia

```
RuntimeException
└── RouteUnavailableException                  ← typ bazowy (stary, zachowany dla zgodności)
    ├── RoutePlanningRejectedException         ← 4xx: planner ODPOWIEDZIAŁ „nie"
    └── RoutePlannerUnavailableException       ← 5xx / timeout / puste / obwód otwarty
```

**Dlaczego dwa typy, a nie jedno pole `kind`:** bo Resilience4j filtruje po **klasie**
(`isAssignableFrom`), nie po zawartości. `retry-exceptions`, `record-exceptions`,
`ignore-exceptions` i dopasowanie metody fallbackowej — wszystko działa na typach.

---

## 5. Tabela zbiorcza — co się dzieje z każdym przypadkiem

| Co się stało | Wyjątek | Retry? | Bezpiecznik | Tag `outcome` | Klient dostaje |
|---|---|---|---|---|---|
| Planner: **422** (odmowa) | `RoutePlanningRejectedException` | **nie** | **ignoruje** | `domain` | **409** |
| Planner: **5xx** | `RoutePlannerUnavailableException` | tak (1×) | **liczy porażkę** | `infrastructure` | **503** + `Retry-After: 10` |
| **Timeout** (>800 ms) | `RoutePlannerUnavailableException` | tak (1×) | **liczy porażkę** | `infrastructure` | **503** + `Retry-After: 10` |
| **Connection refused** | `RoutePlannerUnavailableException` | tak (1×) | **liczy porażkę** | `infrastructure` | **503** + `Retry-After: 10` |
| **Puste ciało** odpowiedzi | `RoutePlannerUnavailableException` | tak (1×) | **liczy porażkę** | `infrastructure` | **503** + `Retry-After: 10` |
| **Obwód otwarty** | `RoutePlannerUnavailableException` (z fallbacku) | — | odrzucenie (`not_permitted`) | `circuit_open` | **503** + `Retry-After: 10` |
| Wywołanie **wolne, ale udane** (500–800 ms) | brak | — | liczy jako **`slow`** | `success` | **201** (trasa jest!) |
| **Nasz bug** (NPE/ISE) | `NullPointerException` itd. | nie | **nie widzi go wcale** | brak | **500** |
| `requestRoute=false` | brak (zwraca `null`) | — | **nie widzi go wcale** | brak | **201** bez trasy |

### Konfiguracja, która to wymusza

```yaml
circuitbreaker.instances.trade-route-planner:
  record-exceptions:                              # BIAŁA lista: tylko to jest awarią zależności
    - ...RoutePlannerUnavailableException
  ignore-exceptions:                              # odmowa to ODPOWIEDŹ, nie awaria
    - ...RoutePlanningRejectedException

retry.instances.trade-route-planner:
  max-attempts: 2                                 # pierwsza próba + JEDNO ponowienie
  retry-exceptions:                               # ponawiaj wyłącznie to, co mogło się udać
    - ...RoutePlannerUnavailableException
```

---

## 6. Dwa niuanse, które zaskakują

### 6.1. Wyjątek spoza obu list liczy się jako **sukces**

`CircuitBreakerStateMachine#handleThrowable` ma **trzy** gałęzie, w tej kolejności:

```
1. pasuje do ignore-exceptions  → releasePermission()  — wywołanie NIE trafia do okna
2. pasuje do record-exceptions  → state.onError(...)   — PORAŻKA
3. żadne z powyższych           → state.onSuccess(...) — SUKCES
```

Dlatego samo dodanie białej listy **nie wystarczyło**: nasze bugi przestały być porażkami, ale
stały się „sukcesami". Rozwiązaniem strukturalnym było **zawężenie zasięgu adnotacji** —
`@Retry`/`@CircuitBreaker` siedzą dziś na `TradeRoutePlannerClient.plan(...)`, czyli obejmują
wyłącznie wyjazd na drut. Walidacja, skrót `requestRoute=false`, `Observation` i mapowanie
domena→DTO zostały w adapterze, **poza proxy**. Dzięki temu ani nasz NPE, ani rezerwacja bez
trasy nie trafiają do okna w ogóle.

> Gdyby ktoś kiedyś wpisał do `ignore-exceptions` całe `RuntimeException` „żeby było spokojnie" —
> **oślepiłby bezpiecznik na amen**. W aplikacji Springowej praktycznie każdy wyjątek docierający
> do breakera jest niekontrolowany (Spring opakowuje nawet `SocketTimeoutException`), więc okno
> nigdy nie zapisałoby ani jednej porażki.

### 6.2. Wyjątek z wątku asynchronicznego wraca opakowany

`ReservationCalculationService` woła planner równolegle z naliczaniem opłaty:

```java
CompletableFuture<Route> routeFuture =
        CompletableFuture.supplyAsync(() -> routePlanner.calculateRoute(command), VIRTUAL_EXECUTOR);
```

`CompletableFuture.join()` opakowuje **każdy** błąd w `CompletionException`. Gdyby to zostawić,
typowane `catch` w `ReservationService` i handlery w `GlobalExceptionHandler` by go nie złapały,
a klient dostałby **500 zamiast 409/503**. Dlatego jest pomocnik, który rozpakowuje przyczynę:

```java
private static <T> T join(CompletableFuture<T> future) {
    try { return future.join(); }
    catch (CompletionException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof RuntimeException runtime) { throw runtime; }
        if (cause instanceof Error error) { throw error; }
        throw ex;
    }
}
```

---

## 7. Droga wyjątku przez warstwy

```
TradeRoutePlannerClient.plan()          ← klasyfikacja: 4xx / 5xx / timeout / puste
   ↑ @Retry            ponawia tylko RoutePlannerUnavailableException
   ↑ @CircuitBreaker   liczy tylko RoutePlannerUnavailableException; fallback na CallNotPermitted
TradeRoutePlannerHttpAdapter            ← walidacja, requestRoute, Observation, mapowanie (POZA proxy)
CompletableFuture.supplyAsync           ← opakowuje w CompletionException
   ↓ join()                             ← rozpakowuje z powrotem
ReservationService                       ← catch RouteUnavailableException → licznik „route_unavailable" → rethrow
GlobalExceptionHandler                   ← RoutePlannerUnavailable → 503+Retry-After
                                            RouteUnavailable (w tym odmowa) → 409
                                            RuntimeException → 500
```

---

## 8. Co widać na dashboardzie w każdym scenariuszu

| Scenariusz demo | Failure rate | Slow rate | Stan obwodu | Panel odcięć | Ruch klienta |
|---|---|---|---|---|---|
| zdrowo | 0% | 0% | ZAMKNIĘTY | pusty | 201 |
| `slow 300` | 0% | 0% | ZAMKNIĘTY | pusty | 201 (wolniej) |
| `slow 440` | 0% | 0–20% | ZAMKNIĘTY | pusty | 201 |
| `slow 600` | **0%** | **→100%** | **OTWARTY** | rośnie | 503 |
| `partial` | ~0% | ~10% | ZAMKNIĘTY | pusty | głównie 201, retry pracuje |
| `down` | **100%** | 0% | **OTWARTY** | rośnie ~4,7/s | 503 |

**Najważniejszy wiersz to `slow 600`:** obwód otwiera się przy **zerowym liczniku błędów**, bo
wszystkie wywołania kończą się sukcesem — tylko wolno. Awaria typu „niby działa", której licznik
5xx nigdy nie złapie.

**`partial` zaskakuje:** toksyna Toxiproxy działa **per połączenie**, a pula keep-alive obsługuje
większość żądań po już nawiązanych, zdrowych połączeniach — więc nominalne „50%" nie równa się
połowie żądań. Zmierzone: przez 90 s retry odpalił się raz.

---

## 9. Podsumowanie w trzech zdaniach

1. **Kiedy 4xx:** planner odpowiedział „nie da się tej trasy" (u nas: zasięg < 1 ly → 422).
   To odpowiedź, nie awaria — nie ponawiamy, bezpiecznik ignoruje, klient dostaje 409.
2. **Kiedy 5xx / timeout:** planner nie odpowiedział użytecznie. To awaria przejściowa —
   ponawiamy raz, bezpiecznik liczy, klient dostaje 503 z `Retry-After`.
3. **Kiedy nasz błąd:** wyjątek powstaje przed wyjazdem na drut, więc nie dociera do bezpiecznika
   w ogóle i leci jako 500 — bo to bug do naprawienia, a nie degradacja do przeczekania.

⚓ **Bezpiecznik odpowiada na jedno pytanie: czy `trade-route-planner` jest zdrowy.
Wszystko, czego nie dowiedzieliśmy się z drutu, jest poza jego kompetencją.**
