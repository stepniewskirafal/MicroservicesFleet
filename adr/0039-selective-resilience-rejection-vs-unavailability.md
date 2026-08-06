# ADR-0039 — Selective resilience: rejection vs unavailability, breaker outside retry

**Status:** Accepted
**Data:** 2026-07-31
**Powiązane ADR:** ADR-0014 (http-resilience — amended), ADR-0015 (error contract), ADR-0038 (resilience observability)

---

## Kontekst

ADR-0014 spłaszczał każdą porażkę wywołania `starport-registry → trade-route-planner`
(4xx, 5xx, timeout, puste ciało) do jednego `RouteUnavailableException` i świadomie
akceptował domyślną cebulę aspektów (`Retry` na zewnątrz `CircuitBreaker`). W praktyce:

- retry ponawiał **deterministyczne odmowy 4xx** (druga próba = ta sama odpowiedź),
- breaker liczył odmowy domenowe jak awarie infrastruktury — fala „niemożliwych tras"
  mogła odciąć zdrowego plannera,
- jedna operacja z retry = **dwa wpisy** w oknie breakera (resilience4j issue #2383),
  więc próg 50% działał ~2× wcześniej, niż sugeruje konfiguracja,
- fallback typu `Throwable` łapał także własne bugi (NPE) i liczył je jako `circuit_open`,
- klient dostawał 409 („zmień żądanie") również wtedy, gdy żądanie było poprawne,
  a planner po prostu leżał.

## Decyzja

1. **Dwa typy wyjątków** (oba dziedziczą po `RouteUnavailableException` — istniejące
   `catch`e i handler 409 działają dalej):
   - `RoutePlanningRejectedException` (4xx, niesie status) — odmowa domenowa,
   - `RoutePlannerUnavailableException` (5xx / timeout / puste ciało / circuit open).
2. **Selektywność mechanizmów:** retry ponawia TYLKO unavailability
   (`retry-exceptions`); breaker zapisuje jako porażkę TYLKO unavailability
   (`record-exceptions` — biała lista), a rejection ma jawnie w `ignore-exceptions`.
   Biała lista zamiast czarnej, bo breaker mierzy **zdrowie zależności**: własny bug
   (NPE/ISE) nie mówi o niej nic i nie może otwierać obwodu przeciw zdrowemu serwisowi.
   Uwaga na trzecią gałąź `handleThrowable`: wyjątek spoza obu list liczy się jako
   **sukces** — nasze bugi lądują właśnie tam (niedoskonałe, ale semantycznie obronne).
3. **Kolejność aspektów: zostaje DOMYŚLNA** — `Retry( CircuitBreaker( metoda ) )`.
   Świadomie, bo taką ma każdy projekt, dopóki jej celowo nie zmieni, a liczenie próbami
   daje szybszą reakcję na twardą awarię. Konsekwencję trzeba znać (issue #2383): próg
   znaczy „% prób", nie „% operacji", i dryfuje z `max-attempts`; przy otwartym obwodzie
   retry pali drugą próbę na zamknięte drzwi. Odwrócenie (`circuit-breaker-aspect-order: 1`,
   `retry-aspect-order: 2`) żyje w **profilu `cb-outer`** — po to, by różnicę dało się
   uruchomić i porównać na dashboardzie, a nie tylko o niej przeczytać. Obie kolejności
   przybite testami: `ResilienceAspectOrderTest` (baza) i `ResilienceInvertedAspectOrderTest`.
4. **Fallback tylko na `CallNotPermittedException`** — metryka `errorType="circuit_open"`
   rośnie wyłącznie przy realnie otwartym obwodzie.
5. **Kontrakt HTTP:** rejection → **409**; unavailability → **503 + `Retry-After: 10`**
   (= `wait-duration-in-open-state`). Oczekiwana awaria zależności = 1 linia WARN,
   bez stacktrace.
6. **Backoff:** `enable-exponential-backoff` + `randomized-wait-factor: 0.5` — przy
   `max-attempts: 2` kosmetyka, ale poprawny wzorzec do kopiowania.

Kontrakt pilnowany testem `ResilienceAspectOrderTest` (WireMock): 503→200 = 1 sukces /
0 porażek w oknie; 422 = 1 request, 0 wpisów; 503×2 = 2 requesty, 1 porażka.

## Dlaczego

- Retry ma sens tylko dla tego, co **mogło** się udać; ponawianie 4xx podwajało zatrucie
  failure-rate.
- Breaker ma mierzyć **zdrowie zależności**, nie trafność pytań klientów.
- 503+Retry-After pozwala klientom budować własny, sensowny retry; 409 przy awarii
  infrastruktury aktywnie ich mylił.

## Wykonane po talku

**Zakres `@CircuitBreaker` zawężony do samego wywołania zdalnego.** Adnotacje zeszły z
`TradeRoutePlannerHttpAdapter.calculateRoute` do nowego, pakietowo-prywatnego beana
`TradeRoutePlannerClient`. Adapter robi walidację, skrót „nie chcę trasy", `Observation`
i mapowanie domena→DTO; klient wyłącznie transport i odporność. Naprawia dwie rzeczy naraz:
- nasze bugi (NPE z `requireNonNull`, błąd w mapowaniu) **nie docierają już do okna** —
  ani jako porażka, ani jako sukces z trzeciej gałęzi `handleThrowable`;
- `requestRoute=false` przestało być zapisywane jako **udane wywołanie plannera, którego
  nigdy nie było** — fantomowe sukcesy rozcieńczały failure-rate i slow-call rate.
Oba przypadki przybite testami (`ourOwnBugNeverReachesTheBreakerAtAll`,
`noRouteRequestedIsNotRecordedAsASuccessfulPlannerCall`).

**Jeden licznik zamiast pary.** `reservations.route.plan.success` + `…errors{errorType}` →
`reservations.route.plan.outcome{outcome=success|domain|infrastructure|empty_response|circuit_open}`,
z cache'owaniem `Counter` w mapie zamiast budowania przy każdym wywołaniu. Nazwa celowo NIE brzmi
`reservations.route.plan` — to nazwa Observation i jej Timera, Micrometer odrzuciłby drugi typ
metryki pod tą samą nazwą. Dashboard i ADR-0038 zaktualizowane.

## Backlog

- `Optional<Route>` zamiast `null` w porcie. Zostawione świadomie: `Route` jest nullowalne aż do
  encji, a wszyscy konsumenci już się przed tym bronią (`Optional.ofNullable`, jawne null-checki).
  Zmiana samego portu przeniosłaby niespójność, zamiast ją usunąć — trzeba przeprowadzić `Optional`
  przez rekord kalkulacji, serwis potwierdzenia i mappery.
- `sliding-window-type: TIME_BASED` (60 s) zamiast licznikowego okna 10.
- Klucz idempotencji dla ponawianego POST-a.
- Przeniesienie reguły „klasa statku → zasięg" z adaptera do domeny.

## Alternatywy

- `record-exceptions` zamiast `ignore-exceptions` (whitelist) — równoważne; wybrano
  ignore, bo mniejszy diff i czytelniejsza intencja.
- Rozróżnianie po statusie w jednym typie wyjątku — breaker/retry filtrują po TYPIE,
  nie po polach.
- Pozostanie przy domyślnej cebuli + niższy próg — leczy objaw, nie przyczynę.
