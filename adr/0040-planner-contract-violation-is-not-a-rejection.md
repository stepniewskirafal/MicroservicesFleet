# ADR-0040 — A 4xx outside the planner's contract is our bug, not a rejection

**Status:** Accepted
**Data:** 2026-09-29
**Powiązane ADR:** ADR-0039 (selective resilience — amended), ADR-0015 (error contract), ADR-0038 (resilience observability)

---

## Kontekst

ADR-0039 mapował **każde** 4xx z `trade-route-planner` na `RoutePlanningRejectedException`:
„planner odmówił tej trasie" → nie ponawiaj, nie licz w breakerze, klientowi 409. Ale planner
odpowiada 4xx w trzech sytuacjach, z których tylko jedna jest odmową:

- **422 + `error: ROUTE_REJECTED`** — świadoma odmowa domenowa (`RoutePlannerExceptionHandler`),
- **422 z mapą pól** — bean validation odrzuciła kształt **naszego** DTO,
- **400 „Malformed JSON"** — **nasz** serializer; **404/405** — zły URL w **naszej** konfiguracji.

Dwie ostatnie grupy to nasz bug lub nasza konfiguracja w mundurze plannera. Skutek: klient dostawał
409 „zmień żądanie" na poprawne żądanie, licznik `outcome=domain` rósł, a żaden alert nie miał
prawa strzelić — dokładnie ten scenariusz, który przez trzy miesiące przeżył zły adres w
konfiguracji (talk „Działa ≠ działa i ja o tym wiem", slajdy 20 i 30).

## Decyzja

1. **Odmowa = 422 z ciałem `ROUTE_REJECTED`.** `TradeRoutePlannerClient` czyta ciało odpowiedzi
   4xx i dopasowuje kod błędu; sam status nie wystarcza, bo walidacja plannera też daje 422.
2. **Każde inne 4xx → `PlannerContractException`** (dziedziczy po `RuntimeException`, celowo
   **nie** po `RouteUnavailableException`): na **żadnej** liście Resilience4j — nie ponawiane
   (druga próba nie naprawi naszego kodu), nie zapisywane jako porażka plannera (odpowiedział);
   trafia do trzeciej gałęzi maszyny stanów jako sukces.
3. **Kontrakt HTTP:** `PlannerContractException` → **502 `ROUTE_PLANNER_CONTRACT`**, bez
   `Retry-After` (powrót za 10 s niczego nie zmieni), z logiem ERROR z fragmentem ciała.
4. **Widoczność:** `reservations.route.plan.outcome{outcome="misrouted"}` — licznik, który zdrowy
   system trzyma na zerze; reguła `TradeRoutePlannerUnexpectedOutcome`
   (`increase(...outcome=~"misrouted|empty_response"[1h]) > 0`, warning — zgłoszenie, nie budzik).
   `ReservationService` liczy to jako `reservations{outcome="planner_contract"}`.

Kontrakt przybity testami: `ResilienceAspectOrderTest` (422 z `ROUTE_REJECTED` = odmowa; 404 i 422
bez kodu = kontrakt: 1 request, obwód CLOSED, 1 sukces / 0 porażek, `misrouted` +1) oraz
`GlobalExceptionHandlerTest` (502, bez `Retry-After`, bez ciała plannera w odpowiedzi).

## Dlaczego

- Odsetek nie widzi rzadkiego, pewnego błędu (2/dobę w tysiącach sukcesów); widzi go tylko licznik
  bezwzględny na wynik, którego nie powinno być wcale — a taki wynik musi mieć **własną etykietę**.
- 409 mówi klientowi „zmień żądanie"; przy naszym bugu to fałsz i ślepy zaułek.
- Breaker mierzy zdrowie plannera; nasz zły URL nic o nim nie mówi.

## Alternatywy

- Rozróżnianie po samym statusie (422 = odmowa) — planner odpowiada 422 także na walidację DTO.
- `record-failure-predicate` zamiast nowego typu — predykat niesie decyzję tylko do breakera; typ
  niesie ją do handlera (502), logów, metryk i testów.
- Wciągnięcie w `RoutePlannerUnavailableException` (503 + retry) — ponawiałoby nasz bug i otwierało
  obwód przeciw zdrowemu plannerowi.

## Uzupełnienie 2026-11-24 — serie „ma być zero" startują od zera, własny bug ma własny licznik

Przegląd `TradeRoutePlannerClient` pokazał dwie dziury w widoczności, którą ten ADR obiecuje
(pkt 4 Decyzji). Obie zamknięte bez zmiany mapowania wyjątków i kontraktu HTTP.

1. **Serie `reservations.route.plan.outcome` są rejestrowane w konstruktorze, od zera.** Wcześniej
   licznik powstawał leniwie, przy pierwszym wystąpieniu danego `outcome`, więc Prometheus widział
   serię po raz pierwszy od razu z wartością 1. `increase()` liczy różnicę między próbkami w oknie
   i nie zakłada startu od zera — wynik 0, reguła `TradeRoutePlannerUnexpectedOutcome` milczała
   przy **pierwszym** `misrouted` / `empty_response` / `no_instances` po każdym restarcie repliki,
   czyli zwykle tuż po deployu. Teraz wszystkie siedem wartości (`ALL_OUTCOMES`) istnieje od startu
   z wartością 0. Zostaje okno jednego odczytu po starcie (`scrape_interval`, tu 3 s): zdarzenie,
   zanim Prometheus raz zobaczy zero, nadal rodzi się z wartością 1.
   Test: `TradeRoutePlannerClientOutcomeRegistrationTest` (dokładnie siedem serii, każda = 0).
2. **`reservations{outcome="internal_error"}`.** `ReservationService` łapał trzy typy
   (`RouteUnavailableException`, `PlannerContractException`, `ReservationConfirmationException`);
   każdy inny `RuntimeException` — nasz bug — wychodził bez próbki w `reservations`, więc wszystkie
   ilorazy na tym liczniku go pomijały. Doszła ostatnia gałąź `catch (RuntimeException)`: zlicza i
   rzuca **ten sam obiekt** dalej. Zasada z tego ADR zostaje: własnego buga nie przemianowujemy
   (typ, stack trace i 500 z `GlobalExceptionHandler` bez zmian) — liczymy go pod własną nazwą.
   Zakres: etap po utworzeniu HOLD (kalkulacja + potwierdzenie); wyjątki z walidacji i z TX1 nadal
   nie trafiają do `reservations`.
   Test: `ReservationServiceTest#should_count_an_unclassified_failure_as_internal_error_without_relabelling_it`.
3. **Panel SLO „Lost Reservations 30d (business)"** (`slo-error-budget.json`): licznik ułamka
   rozszerzony do `outcome=~"no_capacity|route_unavailable|internal_error"`. Rezerwacja utracona
   przez nasz bug jest utracona tak samo jak przez brak miejsc. `error` i `planner_contract` nadal
   są poza tym panelem — osobna decyzja o definicji SLI.

Zweryfikowane: testy jednostkowe `starport-registry` (`mvn test -Pfast`). Nie uruchamiano
`mvn verify` ani reguły alertu na żywym stacku.
