# ADR-0043: cała flota jako jedna aplikacja Spring Modulith

## Status

Zaakceptowany (2026-11-16). Zastępuje ADR-0002, 0003, 0008, 0014, 0028 i 0031 (discovery, load
balancing, topologia replik, odporność wywołania HTTP, Eureka, gateway) oraz ADR-0001 w części
„osobny styl na osobny serwis". Zmienia ADR-0041: moduły plannera stają się częścią większej
aplikacji, a wykluczone tam „scalenie floty w jeden modulith" jest teraz decyzją. Uzupełnia
ADR-0004, 0010, 0016, 0017 i 0019 (patrz „Co zostaje z Kafki").

## Kontekst

Flota składała się z pięciu procesów: `starport-registry`, `trade-route-planner`,
`telemetry-pipeline`, `api-gateway`, `eureka-server` (po dwie repliki serwisów biznesowych).
Jedyne wywołanie synchroniczne (starport → planner) wymagało discovery, load balancera, timeoutów,
retry i circuit breakera. Reszta komunikacji szła przez Kafkę, choć producent i konsument należały
do tego samego zespołu i tego samego repozytorium.

ADR-0041 pokazał na plannerze, że Spring Modulith daje granice sprawdzane w buildzie. Ten ADR
stosuje to samo do całej floty: jeden proces, moduły zamiast serwisów.

## Decyzja

Jedna aplikacja `galactic-fleet` (Spring Modulith 1.4.13), pakiet bazowy `com.galactic.fleet`.
Moduł = bezpośredni podpakiet. API modułu = typy w jego pakiecie bazowym; każdy podpakiet jest
wnętrzem, niedostępnym dla innych modułów.

| Moduł | Pochodzenie | API (pakiet bazowy) | Wnętrze |
|---|---|---|---|
| `reservations` | starport-registry | zdarzenie `ReservationConfirmed` | `controller/`, `service/`, `repository/` (REST, HOLD/CONFIRM, opłaty, JPA, outbox) |
| `routeplanning` | trade-route-planner | `PlanRouteUseCase`, `RouteRequest`, `PlannedRoute`, `RouteRejectionException`, `EventPublishingException`, zdarzenie `RoutePlannedEvent` | `internal/` (algorytm, metryki, publikacja na topik kontraktowy) |
| `telemetry` | telemetry-pipeline | — (nikt go nie woła) | `filter/`, `model/`, `pipeline/`, `config/` |
| `observability` | wspólne | — | `ObservationConfig`, `OtelLogbackInstaller` |

Zależności (bez cykli, pilnuje `ModularityTest`):

```
reservations ──wywołanie──► routeplanning
     │                           │
     └──ReservationConfirmed──►  telemetry  ◄──RoutePlannedEvent──┘
```

### Komunikacja między modułami

| Dawniej (między serwisami) | Teraz (między modułami) |
|---|---|
| HTTP `POST /api/v1/routes/plan` + Eureka + LoadBalancer + Resilience4j | wywołanie `PlanRouteUseCase.planRoute(...)` przez API modułu (`RoutePlanningModuleAdapter`) |
| Kafka `starport.route-planned` konsumowana przez telemetrię | zdarzenie aplikacyjne `RoutePlannedEvent`, synchroniczny `@EventListener` w telemetrii |
| Kafka `starport.reservations` konsumowana przez telemetrię | zdarzenie aplikacyjne `ReservationConfirmed`, publikowane przez przekaźnik outboxa |

**Wywołanie zamiast HTTP.** Wynik trasy jest częścią odpowiedzi HTTP rezerwacji, więc zostaje
synchroniczne. Retry, circuit breaker i timeouty znikają razem z siecią. Tłumaczenie wyników
zostaje: odrzucenie trasy → 422 `ROUTE_REJECTED`, każda inna porażka planowania → 409
`ROUTE_UNAVAILABLE`.

**Zdarzenia zamiast Kafki.** Telemetria nie konsumuje już topików pozostałych modułów — słucha
zdarzeń aplikacyjnych (`FleetEventListener`). Listenery są synchroniczne, biegną po listenerze
publikującym na topik kontraktowy (`@Order`) i nigdy nie rzucają wyjątku: porażka wzbogacania jest
logowana i liczona w `events.enrichment.errors`, a nie psuje rezerwacji.

**`ReservationConfirmed` publikuje przekaźnik outboxa, nie wątek żądania.** Wiersz outboxa nadal
powstaje w tej samej transakcji co CONFIRM (ADR-0010). Przekaźnik (`InboxPublisher`) po wysłaniu
komunikatu na `starport.reservations` publikuje zdarzenie aplikacyjne i dopiero wtedy oznacza
wiersz jako wysłany. Dzięki temu:

- listener widzi wyłącznie trwale zatwierdzone rezerwacje,
- gwarancja at-least-once jest ta sama co dotąd, bez drugiego mechanizmu trwałości,
- listener działa w śladzie odtworzonym z wiersza outboxa, czyli w śladzie pierwotnego żądania.

### Co zostaje z Kafki

Kafka jest wyłącznie **brzegiem systemu**, nie kanałem między modułami:

- wejście: `telemetry.raw` (odczyty czujników od zewnętrznych producentów),
- wyjście (kontrakt dla zewnętrznych konsumentów): `starport.reservations`,
  `starport.route-planned`, `telemetry.enriched-reservations`, `telemetry.enriched-routes`,
  `telemetry.alerts`.

Żaden z tych topików nie jest konsumowany przez inny moduł tej aplikacji. Format komunikatów,
klucze i nagłówek `traceparent` są bez zmian.

### Topologia

Jeden proces, jedna instancja, port 8080 wystawiony bezpośrednio. `api-gateway` i `eureka-server`
usunięte. Uruchomienie: `infra/docker/docker-compose.modulith.yml`.

## Jak kod to egzekwuje

- `galactic-fleet/src/test/java/com/galactic/fleet/ModularityTest.java` —
  `ApplicationModules.verify()` w fazie unit; cykl między modułami albo sięgnięcie do cudzego
  podpakietu łamie build, także `-Pfast`.
- `telemetry/architecture/PipesAndFiltersArchitectureTest` — reguły ArchUnit dla wnętrza telemetrii
  (ADR-0022) przeniesione bez zmian.
- `reservations/service/routeplanner/RoutePlanningModuleAdapter` — jedyne miejsce, w którym
  `reservations` dotyka `routeplanning`.
- `telemetry/pipeline/FleetEventListener` — jedyne miejsce, w którym `telemetry` dotyka pozostałych
  modułów (wyłącznie typy zdarzeń).
- `system-acceptance/` — zestaw czarnoskrzynkowy (HTTP, Kafka, Prometheus) zamrożony na
  trzech serwisach commitem `6467fb0`; na monolicie przechodzi bez zmiany ani jednej asercji.
- `FleetEventFlowE2ETest` — ten sam przepływ w buildzie, na binderze testowym.

## Konsekwencje

Zyski:

- pięć procesów → jeden; znika discovery, gateway, load balancer i cała konfiguracja odporności,
- wywołanie planowania trasy nie może się nie udać z powodu sieci,
- trasa i jej wzbogacona kopia powstają w tym samym żądaniu i tym samym śladzie, bez opóźnienia
  konsumenta,
- granice między dawnymi serwisami są sprawdzane w buildzie, nie tylko siecią.

Koszty i zmiany zachowania:

- **Brak izolacji awarii i skalowania per serwis.** Jedna instancja; awaria procesu zatrzymuje
  wszystko, a telemetrii nie da się skalować niezależnie od rezerwacji.
- **Znikają ścieżki awarii plannera**: retry, stany circuit breakera, metryki `resilience4j_*`
  i `http_client_requests_*`. Odpowiedź 409 `ROUTE_UNAVAILABLE` zostaje tylko dla porażki
  publikacji zdarzenia trasy.
- **Wzbogacanie tras biegnie w wątku żądania.** Jest izolowane (nie psuje odpowiedzi), ale wydłuża
  żądanie o czas wzbogacenia i wysyłki.
- **Utracona zostaje ponowna dostawa po stronie telemetrii.** Dawniej nieudane wzbogacenie było
  ponawiane przez konsumenta Kafki (3 próby); teraz jest logowane i liczone. Topiki DLQ
  `starport.reservations.dlq` i `starport.route-planned.dlq` nie są już używane.
- **Endpoint `POST /api/v1/routes/plan` nie istnieje.** Był niedostępny z zewnątrz; jego zamrożony
  zestaw akceptacyjny (`RoutePlannerAcceptanceIntegrationTest`) i kontrakty Spring Cloud Contract
  zastąpił zestaw systemowy oraz testy modułu.
- **Etykieta `job` w metrykach** to teraz `galactic-fleet` zamiast trzech nazw serwisów; nazwy
  metryk bez zmian, dashboardy poprawione. Pole `processedBy` w komunikatach wzbogaconych nadal ma
  wartość `telemetry-pipeline` (kontrakt).
- Projekt przestaje pokazywać trzy style architektury w trzech serwisach; wariant mikroserwisowy
  zostaje w historii gita i na gałęzi `demo`.

## Alternatywy

- **`@ApplicationModuleListener` + rejestr publikacji zdarzeń Modulith** dla `ReservationConfirmed` —
  idiomatyczne, ale dokłada drugą tabelę i drugi mechanizm at-least-once obok istniejącego outboxa,
  a ślad trzeba by przenosić przez wątek asynchroniczny. Outbox już jest trwałym dziennikiem zdarzeń.
- **Zostawić Kafkę między modułami** (monolit sam publikuje i sam konsumuje) — zero zmian
  w zachowaniu, ale broker pozostaje kanałem wewnętrznym, czego ta migracja miała uniknąć.
- **Usunąć Kafkę całkowicie** — telemetria straciłaby wejście (`telemetry.raw`), a zewnętrzni
  konsumenci wszystkie pięć topików; to zmiana kontraktu, nie architektury wewnętrznej.
- **Asynchroniczny listener tras (`@Async`)** — skraca żądanie, ale wymaga przenoszenia kontekstu
  śladu i gubi kolejność względem publikacji na topik kontraktowy.
