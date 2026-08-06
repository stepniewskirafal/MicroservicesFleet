# Branch `demo` — zmiany kodu pod talk v3 (Timeout · Retry · CircuitBreaker)

> **Stan: WYKONANE** na gałęzi `demo` (commity poniżej). Wersja v2 tego pliku (plan
> RateLimiter+Bulkhead — świadomie porzucony) leży w `BRANCH-demo-zmiany-kodu.v2.bak.md`.
> Zasada bez zmian: **zero zmian na masterze przed talkiem**.

## 0. Commity (materiał źródłowy slajdów 16–18)

```
f529bd3 fix(starport-registry): fire circuit fallback only on CallNotPermittedException
d67bc2f feat(starport-registry): split route failure into rejection vs unavailability
97e9fa6 feat(starport-registry): invert resilience aspect order — breaker wraps retry
22a1529 feat(starport-registry): exponential backoff with jitter on planner retry
62a462f style(starport-registry): apply spotless (palantir) to resilience changes
```

Decyzja i uzasadnienie: **ADR-0039** (`adr/0039-selective-resilience-rejection-vs-unavailability.md`);
ADR-0014 dostał adnotację „amended".

Zrzuty na slajdy rób z tych diffów: `git show <hash>` albo widok side-by-side w IDE.

---

## 1. Fallback tylko na otwarty obwód (`f529bd3`) — slajd 17, wątek „metryka mówi prawdę"

**PRZED:** `routeUnavailableFallback(ReserveBayCommand, Throwable)` — łapał WSZYSTKO
(także NPE i OOM z własnego kodu), etykietował każdą porażkę jako `circuit_open`
i podwójnie liczył błędy (raz `domain`/`infrastructure`, raz `circuit_open`).

**PO:** `circuitOpenFallback(ReserveBayCommand, CallNotPermittedException)` — resilience4j
dopasowuje fallback po TYPIE parametru, więc odpala się wyłącznie przy odciętym obwodzie.
Kafelek „Trasy oddane do fallbacku" (filtr `outcome="circuit_open"`) pokazuje teraz
wyłącznie realne odcięcia.

## 2. Dwa wyjątki zamiast jednego (`d67bc2f`) — slajd 17

**PRZED:** 4xx, 5xx, timeout, puste ciało → jeden `RouteUnavailableException`.
Breaker liczył odmowę domenową jak awarię; retry ponawiał deterministyczne 4xx.

**PO:**
- `RoutePlanningRejectedException` (4xx, niesie status) — **odmowa**: nie ponawiana
  (`retry-exceptions` już jej nie obejmuje), ignorowana przez breaker (`ignore-exceptions`),
  klient dostaje **409**.
- `RoutePlannerUnavailableException` (5xx/timeout/puste/circuit open) — **niedostępność**:
  jedyna rzecz, którą retry ponawia i breaker liczy; klient dostaje **503 + Retry-After: 10**
  (10 = `wait-duration-in-open-state`, najwcześniejszy moment, w którym obwód może się domknąć).
- Oba dziedziczą po `RouteUnavailableException` → istniejące `catch`e i stary handler 409
  działają dalej (siatka bezpieczeństwa).
- Bonus: oczekiwana awaria zależności loguje **jedną linię WARN** zamiast pełnego stacktrace'a
  na każdą próbę (przy chaosie 4 req/s stacktrace'y to dokładnie ten szum, o którym jest talk).

## 3. Cebula odwrócona (`97e9fa6`) — slajd 16 + kotwica slajdu 11

**PRZED:** domyślnie `Retry( CircuitBreaker( metoda ) )` — issue #2383: jedna operacja
z maxAttempts=2 = DWA wpisy w oknie breakera; przy otwartym obwodzie retry dalej pukał.

**PO (application.yml):**
```yaml
resilience4j:
  circuitbreaker:
    circuit-breaker-aspect-order: 1   # niższa wartość = bardziej na zewnątrz
  retry:
    retry-aspect-order: 2             # => CircuitBreaker( Retry( metoda ) )
```
Breaker widzi finał operacji: jedna operacja = jeden werdykt.

**Strażnik kontraktu — i dowód A/B:** dwie klasy testowe różniące się WYŁĄCZNIE dwoma
intami (`@TestPropertySource` przywraca domyślną kolejność):
- `ResilienceAspectOrderTest` (produkcja, CB na zewnątrz): 503→200 = `1 success / 0 failed`;
  422 = 1 request, 0 wpisów; 503×2 = 2 requesty, 1 failed; seria 3 uratowanych operacji =
  breaker ZAMKNIĘTY, 3/0.
- `ResilienceDefaultAspectOrderTest` (domyślna, Retry na zewnątrz): ta sama uratowana
  operacja = `1 failed / 1 success`; ta sama seria 3 operacji = breaker otwiera się
  **W POŁOWIE trzeciej** (5. próba w oknie = 60%) i **odrzuca jej własne retry** — klient
  dostaje błąd od zależności, która odpowiedziała poprawnie na każdą operację.

Uczciwa rama: domyślna kolejność to nie bug — liczenie próbami daje szybszą reakcję na
twardą awarię. Kosztem: próg 50% przestaje znaczyć „50% operacji" i dryfuje z każdą zmianą
`max-attempts`. Wybraliśmy przewidywalność progu; testy istnieją, żeby wybór nie zdryfował.

## 4. Backoff + jitter (`22a1529`) — wzmianka na slajdzie 16

```yaml
enable-exponential-backoff: true
exponential-backoff-multiplier: 2
randomized-wait-factor: 0.5
```
Przy maxAttempts=2 efekt kosmetyczny (jedno czekanie 100 ms ± 50%), ale KSZTAŁT configu
jest produkcyjny: kto skopiuje z maxAttempts=5, dostanie rosnące, zdesynchronizowane
odstępy zamiast wszystkich instancji dobijających wstającą zależność w tym samym takcie.

## 5. Kontrakt HTTP + skrypty

- `GlobalExceptionHandler`: `RoutePlannerUnavailableException` → **503 + Retry-After: 10**;
  odmowa i legacy → **409** (bez zmian).
- `demo-resilience.ps1`: licznik ruchu traktuje **409 i 503** jako `degraded`
  (PS 5.1: kod wyciągany z `$_.Exception.Response`).
- Dashboard: bez zmian JSON-a — nazwy metryk i tagi nietknięte; panel „Trasy oddane do
  fallbacku" po prostu zaczął mówić prawdę.

**Q&A „czemu 503, nie 409?":** 409 mówi klientowi „zmień żądanie" — a przy awarii plannera
żądanie jest w porządku. 503+Retry-After mówi „wróć za chwilę" i pozwala klientom budować
sensowny retry po swojej stronie. Odmowa domenowa zostaje na 409, bo tam faktycznie trzeba
zmienić żądanie.

## 6. Czego NIE robić (bez zmian względem v2)

- **Nie dodawaj `@TimeLimiter`** — `TimeLimiterAspect` wymaga sygnatury zwracającej
  `CompletionStage`; na `Route calculateRoute(...)` rzuca `IllegalReturnTypeException`
  przy każdym wywołaniu (kompiluje się, wysadza w runtime). Timeout robi read-timeout
  800 ms na RestClient. Uwaga: samo wywołanie **już biegnie asynchronicznie**
  (`supplyAsync` w `ReservationCalculationService`) — ale R4j patrzy na typ zwracany,
  nie na wątek. Gotowa odpowiedź na Q&A: cheatsheet v3, pyt. 3.
- **Nie zmieniaj progów CB** — choreografia 300/440/600/down/heal jest pod nie skalibrowana.
- **Nie merguj `demo` do mastera przed talkiem.**

## 7. Q&A: „a gdzie RateLimiter i Bulkhead?"

Świadoma decyzja v3: **trzy wzorce głęboko zamiast pięciu płytko**. Odpowiedź na scenie:
„To ta sama biblioteka i dosłownie jedna adnotacja więcej — ale każda z nich zasługuje na
własne demo, a ja wolałem pokazać wam trzy rzeczy do końca, z metrykami i pułapkami, niż
pięć w przelocie. Przepis na dołożenie limitera macie w repo." (plan w `*.v2.bak.md`;
`demo-flood.ps1` w tym folderze to niepodłączony artefakt tamtego planu).

## 8. Po talku (backlog z code review — NIE przed prezentacją)

1. Jeden licznik `reservations.route.plan` z tagiem `outcome` zamiast pary success/errors
   (wymaga zmiany zapytań dashboardu).
2. `sliding-window-type: TIME_BASED` (60 s) — COUNT_BASED(10) na niskim ruchu pamięta
   prehistorię (wymaga rekalibracji choreografii demo).
3. `fuelRangeLYForShipClass` → do domeny (reguła biznesowa w adapterze HTTP).
4. Klucz idempotencji dla retry POST-a (komentarz w yml to uczciwie przyznaje).
5. `Optional<Route>` zamiast `null` przy `requestRoute=false`.
