# ADR-0042 — No planner instance in the registry is unavailability, under its own outcome

**Status:** Accepted
**Data:** 2026-11-02
**Powiązane ADR:** ADR-0039 (selective resilience), ADR-0040 (contract violation ≠ rejection — amended), ADR-0038 (resilience observability)

---

## Kontekst

ADR-0040 zamknął wszystkie odpowiedzi plannera w czterech gałęziach `TradeRoutePlannerClient`:
podpisana odmowa (422 + `ROUTE_REJECTED`), inne 4xx (`misrouted`), 5xx / timeout / błąd
połączenia (`infrastructure`), 2xx bez ciała (`empty_response`). Każda gałąź ma własny `outcome`,
a `misrouted` i `empty_response` pilnuje alert „ma być zero".

Jedna awaria zdarza się **przed** wysłaniem pierwszego bajtu i w żadnej gałęzi nie siedzi. Klient
jest `@LoadBalanced`: host z `app.trade-route-planner.base-url` (`trade-route-planner`) nie jest
nazwą DNS, tylko identyfikatorem serwisu w Eurece. Gdy rejestr nie zna takiego serwisu —
literówka w `base-url` albo obie repliki wyrzucone z rejestru po długiej awarii —
`BlockingLoadBalancerClient` (Spring Cloud LoadBalancer 4.3.0) rzuca **goły wyjątek JDK**, a
który, zależy od classpath: u nas spring-retry jest na nim (ciągnie go spring-kafka), więc łańcuch
to `DeferringLoadBalancerInterceptor → RetryLoadBalancerInterceptor → execute(serviceId,
instance, request)` i wyjątek to `IllegalArgumentException("Service Instance cannot be null,
serviceId: trade-route-planner")`; bez spring-retry zwykły `LoadBalancerInterceptor` woła
`execute(serviceId, request)` i leci `IllegalStateException("No instances available for
trade-route-planner")`. (Pierwszy szkic tego ADR znał tylko drugą formę, z lektury bajtkodu; test
z prawdziwą biblioteką pokazał pierwszą.) Żaden z nich nie jest
`RestClientException`, więc `catch (RestClientException)` go nie łapie; nie stoi na żadnej liście
Resilience4j, więc breaker zapisuje **sukces** i retry nie ponawia; `ReservationService` go nie
łapie, więc nie ma `reservations{outcome=…}`; `GlobalExceptionHandler` oddaje klientowi **500
„Internal server error"**. Żaden licznik `outcome` nie rośnie, żaden alert nie strzela. Jedyny ślad
to stack trace w logu i `http_client_requests{exception="IllegalStateException"}`.

Przypadek wyszedł przy pisaniu przewodnika „Zły adres plannera" do talku: zła **ścieżka** jest
widoczna (`misrouted`, 502, alert), zła **nazwa serwisu** znika.

## Decyzja

1. **`TradeRoutePlannerClient` łapie `IllegalArgumentException | IllegalStateException` i
   dopasowuje typ + prefiks komunikatu**: `IllegalArgumentException` z `"Service Instance cannot
   be null"` albo `IllegalStateException` z `"No instances available for "`. Spring Cloud
   LoadBalancer nie ma dedykowanego typu wyjątku, więc para typ + komunikat jest jedynym uchwytem.
   Każdy inny wyjątek tych typów (także właściwy komunikat na niewłaściwym typie) wychodzi z metody
   **nietknięty** (ten sam obiekt, własny stack trace, zero outcome) — to nasz bug, nie werdykt
   rejestru.
2. **Nowy outcome `no_instances`** na `reservations.route.plan.outcome`. Osobna etykieta, nie
   `infrastructure`: zdrowy system trzyma ją na zerze, a dyżurny ma od razu widzieć „rejestr nie
   zna plannera", nie „coś z siecią".
3. **Mapowanie na `RoutePlannerUnavailableException`** (503 + `Retry-After: 10`, ponawiane raz,
   zapisywane jako porażka breakera), z jawnym powodem w ciele 503 (`no planner instance
   registered in the service registry`) zamiast nazwy klasy wyjątku. Nowy konstruktor
   `RoutePlannerUnavailableException(origin, destination, reason, cause)`.
4. **Alert:** `TradeRoutePlannerUnexpectedOutcome` rozszerzony o `no_instances`
   (`outcome=~"misrouted|empty_response|no_instances"`, warning). Literówka w `base-url`
   podnosi więc dwa alerty naraz: ten (konfiguracja?) i po chwili `TradeRoutePlannerCircuitOpen`
   (critical).

Kontrakt przybity testami: `TradeRoutePlannerClientNoInstancesTest` (bez Springa, 7 przypadków:
obie formy werdyktu → `no_instances` + unavailable; inny `IllegalArgumentException` / `IllegalStateException`,
brak komunikatu, fraza w środku komunikatu, właściwy komunikat na niewłaściwym typie → nietknięty,
zero outcome) oraz `NoPlannerInstanceInRegistryTest` (prawdziwy `@LoadBalanced` klient,
`spring.cloud.discovery.enabled=true`, Eureka wyłączona, `SimpleDiscoveryClient` bez plannera:
dosłowny komunikat biblioteki z `RetryLoadBalancerInterceptor`, 0 requestów do WireMocka, retry
×2, 2 porażki breakera, `no_instances` +2, HTTP 503 + `Retry-After: 10` +
`ROUTE_PLANNER_UNAVAILABLE`, HOLD zwolniony).

## Dlaczego unavailable, a nie contract (502)

Pierwszy szkic mapował ten przypadek na `PlannerContractException` (502, bez retry, trzecia
gałąź breakera = sukces), bo literówka w konfiguracji jest deterministyczna jak zły URL. Przegląd
obalił to na scenariuszu **eviction po awarii**: breaker jest OPEN, po 10 s przechodzi w HALF_OPEN,
sonda trafia w `IllegalStateException`, a ten jako „sukces" **zamyka obwód** przeciwko plannerowi,
który wciąż leży. Dalej każde żądanie dostaje 502 „nasz bug", breaker zostaje CLOSED, alert
`TradeRoutePlannerCircuitOpen` (critical) milknie w środku awarii. Adapter nie umie odróżnić
literówki od eviction, więc wybiera mapowanie, które **nigdy nie ukrywa awarii**; literówkę łapie
osobny outcome z alertem „ma być zero". Koszt: klient przy literówce słyszy „wróć za 10 s", choć
powrót nic nie da — ale literówka w konfiguracji ma umierać na deployu (smoke test, readiness),
nie w kodzie adaptera.

## Alternatywy

- **Sprawdzanie rejestru przed wywołaniem** (`DiscoveryClient#getInstances` → pusta lista →
  wyjątek) — wyścig z rejestrem, dodatkowa zależność w adapterze, a profil `demo` (klient bez LB)
  nie ma rejestru wcale.
- **`catch (IllegalStateException)` bez sprawdzania komunikatu** — relabelowałby nasze własne
  bugi na „planner unavailable", czyli dokładnie to, przed czym broni
  `TradeRoutePlannerMappingBugTest`.
- **Dopasowanie po substringu zamiast prefiksu** — opakowany lub przeredagowany komunikat
  udawałby werdykt rejestru. Prefiks + test z prawdziwą biblioteką wystarczą, a zmiana komunikatu
  w nowej wersji Spring Cloud wywróci test, nie produkcję.
- **Wyłączenie retry dla `no_instances`** (lista `ignore-exceptions` retry) — ponowienie jest
  darmowe (brak sieci) i po odświeżeniu cache'u Eureki (30 s) może trafić; nie warto osobnego typu.

## Konsekwencje

- `misrouted` nadal oznacza wyłącznie „planner (lub ktoś pod jego adresem) odpowiedział 4xx bez
  podpisu". `no_instances` oznacza „nikt nie odpowiedział, bo nie było komu".
- `RoutePlannerUnavailableException` z `statusCode == NO_RESPONSE` obejmuje teraz także brak
  instancji; `details` w 503 mówi, który to przypadek.
- Dopasowanie po komunikacie biblioteki to świadomy dług: przy podbiciu Spring Cloud sprawdzić
  `NoPlannerInstanceInRegistryTest`.
- Poza zakresem (osobny ADR, jeśli kiedyś): walidacja ciała 2xx (cudzy JSON mapuje się na rekord
  z `routeId=null` i przechodzi jako `success`), fail-fast na `base-url` przy starcie.
