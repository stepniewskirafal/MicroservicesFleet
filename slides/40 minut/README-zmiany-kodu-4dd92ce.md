# README — co zmienił commit `4dd92ce` i dlaczego

> Commit `4dd92cef7461` (branch `demo`, 2026-07-31) — squash ośmiu zmian przygotowujących
> talk „Działa ≠ działa i ja o tym wiem" (KOLB v3). 18 plików, +812/−323.
> Decyzja architektoniczna: **ADR-0039**. Wszystko zweryfikowane testami (98/98) i
> pełną sekwencją chaosu na żywym stacku.

---

## Po co to wszystko (jedno zdanie)

Kod demonstrowany na scenie zawierał trzy realne pułapki resilience — zamiast je ukrywać,
commit je **naprawia**, a talk pokazuje diff „przed → po" jako materiał dydaktyczny.

---

## 1. Fallback przestał kłamać

**Plik:** `TradeRoutePlannerHttpAdapter.java`

**Było:** `routeUnavailableFallback(ReserveBayCommand, Throwable)` — Resilience4j dopasowuje
fallback po typie parametru, więc `Throwable` łapał **każdy** wyjątek: także NPE i OOM
z własnego kodu. Wszystko lądowało w metryce jako `circuit_open` i było
liczone podwójnie (raz `domain`/`infrastructure`, raz `circuit_open`).

**Jest:** `circuitOpenFallback(ReserveBayCommand, CallNotPermittedException)` — odpala się
**wyłącznie** przy otwartym obwodzie. Inne wyjątki propagują nietknięte.

**Dlaczego:** kafelek „Trasy oddane do fallbacku" na dashboardzie filtruje po
`circuit_open` — wcześniej rósł przy KAŻDEJ awarii (zanim breaker w ogóle się otworzył),
teraz pokazuje prawdę. Bug we własnym kodzie nie przebiera się już za otwarty obwód.

## 2. Dwa wyjątki zamiast jednego — serce zmiany

**Pliki:** `RoutePlanningRejectedException.java` (nowy), `RoutePlannerUnavailableException.java`
(nowy), `TradeRoutePlannerHttpAdapter.java`, `application.yml`

**Było:** 4xx, 5xx, timeout i puste ciało odpowiedzi spłaszczane do jednego
`RouteUnavailableException`. Konsekwencje:
- retry ponawiał **deterministyczne odmowy 4xx** (druga próba = ta sama odpowiedź),
- breaker liczył odmowy domenowe jak awarie — fala „niemożliwych tras" mogła odciąć
  **zdrowego** plannera.

**Jest:**
- `RoutePlanningRejectedException` (4xx, niesie status HTTP) — **odmowa**: planner
  odpowiedział, po prostu „nie".
- `RoutePlannerUnavailableException` (5xx / timeout / puste ciało / otwarty obwód) —
  **niedostępność**: planner nie odpowiedział użytecznie.
- Konfiguracja czyta się jak zdanie: breaker → `ignore-exceptions` na odmowie
  (odmowa to ODPOWIEDŹ, nie awaria); retry → `retry-exceptions` tylko na niedostępności
  (ponawiaj wyłącznie to, co MOGŁO się udać).

**Bez ryzyka dla reszty kodu:** oba typy dziedziczą po `RouteUnavailableException`,
więc istniejące `catch`e (`ReservationService`) i stary handler 409 działają dalej.

## 3. Cebula odwrócona — breaker NA ZEWNĄTRZ retry (issue #2383)

**Plik:** `application.yml`

**Ważne:** domyślna kolejność `Retry( CircuitBreaker( metoda ) )` to **nie bug, tylko inna
semantyka** — breaker liczy wtedy PRÓBY (większa czułość, szybciej otwiera przy realnej
awarii). My wybieramy liczenie OPERACJAMI, bo wtedy próg 50% znaczy dokładnie to, co mówi
konfiguracja — i nie dryfuje przy każdej zmianie `max-attempts`.

**Zamiana to dosłownie dwa inty:**
```yaml
resilience4j:
  circuitbreaker:
    circuit-breaker-aspect-order: 1   # niższa wartość = bardziej na zewnątrz
  retry:
    retry-aspect-order: 2             # => CircuitBreaker( Retry( metoda ) )
```

**Oba scenariusze są WYKONYWALNE** — dwie klasy testowe różniące się wyłącznie tymi dwoma
intami (`@TestPropertySource`), ten sam chwiejny planner (co drugi request pada, każdą
operację ratuje retry), ta sama seria trzech operacji:

| | `ResilienceAspectOrderTest` (produkcja: CB na zewnątrz) | `ResilienceDefaultAspectOrderTest` (domyślna: Retry na zewnątrz) |
|---|---|---|
| Okno po uratowanej operacji | **1 sukces / 0 porażek** | **1 porażka / 1 sukces** |
| Seria 3 operacji | 3 sukcesy, breaker **ZAMKNIĘTY**, 6 requestów na drucie | breaker **otwiera się W POŁOWIE 3. operacji** (5. próba = 60% ≥ 50%) i **odrzuca jej własne retry** — klient dostaje błąd, na drut wyszło tylko 5 requestów |
| 4xx (odmowa) | 1 request, 0 wpisów | identycznie (selektywność wyjątków działa w obu) |

**Wnioski (plusy i minusy, uczciwie):**
- ✚ CB na zewnątrz: próg = próg; otwarty obwód odrzuca natychmiast (zero spalonych prób);
  metryka „failed" = nieudane OPERACJE, czyli to, co czuje klient.
- ✚ Retry na zewnątrz (domyślna): szybsza reakcja na twardą awarię (każda padnięta próba
  dokłada do okna); retry z backoffem może „przeczekać" krótkie otwarcie.
- ✖ Retry na zewnątrz: zmierzony efekt uboczny powyżej — zależność, która odpowiedziała
  poprawnie na **każdą operację**, zostaje odcięta, a breaker zabija retry, które właśnie
  ratowało operację. Próg efektywny zależy od `max-attempts` — konfiguracja znaczy co
  innego, niż mówi.
- ⚓ **Nie do obrony jest tylko jedno: nie wiedzieć, którą kolejność się ma.** Stąd testy —
  pomyłka w dwóch intach jest bezobjawowa aż do awarii.

## 4. Kontrakt HTTP mówi klientowi, co ma zrobić

**Plik:** `GlobalExceptionHandler.java`

**Było:** każda utrata trasy = **409 Conflict** — czyli „zmień żądanie". Przy leżącym
plannerze to aktywnie myliło klienta: żądanie było poprawne.

**Jest:**
- odmowa domenowa → **409** („zmień żądanie") — bez zmian,
- niedostępność → **503 + `Retry-After: 10`** („wróć za chwilę"; 10 s =
  `wait-duration-in-open-state`, najwcześniejszy moment domknięcia obwodu).

Do tego: oczekiwana awaria zależności loguje **jedną linię WARN** zamiast pełnego
stacktrace'a na każdą próbę — przy chaosie 4 req/s stacktrace'y to dokładnie ten szum,
w którym w historii z talku utonęły „dwa błędy na dobę".

## 5. Backoff z jitterem — właściwy kształt do skopiowania

**Plik:** `application.yml` — `enable-exponential-backoff` + `exponential-backoff-multiplier: 2`
+ `randomized-wait-factor: 0.5`. Przy `max-attempts: 2` efekt kosmetyczny (jedno czekanie
100 ms ± 50%), ale kto skopiuje ten config z `max-attempts: 5`, dostanie rosnące,
zdesynchronizowane odstępy — a nie wszystkie instancje dobijające wstającą zależność
w tym samym takcie.

## 6. Dashboard: nowy panel Retry + doprecyzowana latencja

**Plik:** `infra/docker/grafana/provisioning/dashboards/resilience4j-trade-route.json`

- **Nowy panel ⑪ „Retry — skumulowane wyniki"** — runbook w kroku `partial` kazał patrzeć
  na panel retry, a dashboard **nie miał ani jednego zapytania** `resilience4j_retry_*`.
  Panel celowo pokazuje surowe liczniki (nie `rate()`): zdarzenia retry są rzadkie,
  rate() pokazywałby zera między nimi i kłamał.
- **Opis „Maks. latencja"**: po odwróceniu cebuli breaker mierzy czas **całej operacji**
  (obie próby + czekanie), więc udany call uratowany przez retry może przekroczyć czerwoną
  linię 800 ms — i to nie jest błąd (zmierzone: 0,92 s przy `partial`).

## 7. Skrypt ruchu rozumie nowy kontrakt

**Plik:** `scripts/demo-resilience.ps1` — licznik traktuje **409 i 503** jako `degraded`
(wcześniej tylko 409; 503 wpadałoby do „inne" i psuło obraz na scenie).
Zweryfikowane: 20 min ruchu przez pełną sekwencję chaosu → 2495 wysłanych,
2188×201, 307 degraded, **`inne=0`**.

## 8. Dokumentacja i materiały sceniczne

- **ADR-0039** (nowy) — decyzja + dlaczego; **ADR-0014** dostał adnotację „amended".
- **RUNBOOK KOLB v3** — trzy wzorce zamiast pięciu (RateLimiter/Bulkhead świadomie
  wycięte: trzy rzeczy do końca zamiast pięciu w przelocie), drabinka 300→440→600→down,
  AKT 4 na prawdziwych diffach, **zmierzone timingi sceniczne** (kotwica „zero błędów"
  w pierwszych ~20–30 s; partial NIE otwiera breakera — toksyna działa per połączenie;
  alert pending ~60–90 s zanim FIRING).
- **Deck pptx** — slajd 9 przycięty do 3 wzorców, slajd 16 pokazuje aktualny kod,
  slajd 18 przerobiony z RL+BH na „Widoczność".
- **QA-cheatsheet** — odpowiedź o fallbacku zgodna z 503.

---

## Weryfikacja (dwie iteracje review + testy)

| Co | Wynik |
|---|---|
| Pełna suita `starport-registry` | **98/98 zielone** |
| `ResilienceAspectOrderTest` (kontrakt cebuli/wyjątków) | 3/3 |
| Preflight na świeżym stacku | exit 0, 10/10 × 201 |
| Sekwencja chaosu slow 300/440/600 → partial → down → heal | zgodna z runbookiem |
| Kontrakt E2E przez gateway przy `down` | `503`, `Retry-After: 10`, `ROUTE_PLANNER_UNAVAILABLE` |
| Ruch 20 min przez chaos | 0 odpowiedzi poza kontraktem |

## Czego commit świadomie NIE zmienia

- progi CB (50%, 500 ms, okno 10, half-open 10 s) — choreografia demo jest pod nie skalibrowana,
- brak `@TimeLimiter` — metoda synchroniczna, timeout robi read-timeout RestClienta (800 ms w demo),
- nazwy metryk domenowych (`reservations_route_plan_*`) — dashboard działa bez migracji,
- backlog po talku (unifikacja liczników pod tag `outcome`, `TIME_BASED` window,
  idempotency key dla retry POST) — sekcja 8 w `BRANCH-demo-zmiany-kodu.md`.
