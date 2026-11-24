# ADR-0043: cała flota jako jedna aplikacja Spring Modulith

## Status

Zaakceptowany (2026-11-16, stan końcowy 2026-11-24). Ten dokument łączy siedem decyzji podjętych na
gałęzi `modulith-migration` (dawne ADR-0043–0049) — wszystkie prowadziły do jednego celu, więc są
zapisane jako jedna. Numery 0044–0049 nie są już używane.

Zastępuje ADR-0002, 0003, 0008, 0014, 0028, 0031 (discovery, load balancing, repliki, odporność
wywołania HTTP, Eureka, gateway), ADR-0010 (własny outbox) i ADR-0001 w części „osobny styl na
osobny serwis". Zmienia ADR-0041 (planner staje się modułem), ADR-0016 (lista topików), ADR-0017
(propagacja trace), ADR-0022 (gdzie żyje Pipes & Filters) i ADR-0030 (jedna nazwa metryki).
Uzupełnia ADR-0005 (Prometheus bez Thanosa).

## Kontekst

Flota składała się z pięciu procesów: trzech serwisów biznesowych po dwie repliki, bramki i rejestru
usług. Jedyne wywołanie synchroniczne wymagało discovery, load balancera, timeoutów, retry
i circuit breakera, a reszta komunikacji szła przez Kafkę, choć nadawca i odbiorca leżały w tym
samym repozytorium. ADR-0041 pokazał na plannerze, że Spring Modulith daje granice sprawdzane
w buildzie. Ta decyzja stosuje to do całej floty.

## Decyzja

### 1. Jedna aplikacja, trzy moduły

`galactic-fleet` (Spring Modulith 1.4.13), pakiet `com.galactic.fleet`, jeden proces na `:8080`.
Moduł to bezpośredni podpakiet. Kryterium modułu: własny stan albo własny cykl życia.

| Moduł | Posiada | Wystawia | Zależy od | Styl wnętrza |
|---|---|---|---|---|
| `booking` | REST, porty, doki, rezerwacje, taryfa; jedyny właściciel bazy | `events`: `ReservationConfirmed` | `routeplanning::api` | warstwy |
| `routeplanning` | wyliczenie trasy: zasięg paliwa, ryzyko, ETA | `api`: `PlanRouteUseCase` | — | czysta funkcja |
| `activityfeed` | rekordy wyliczane z potwierdzonych rezerwacji | — | `booking::events` | Pipes & Filters |

Kod platformowy (`AsyncTracingConfiguration`, `OtelLogbackInstaller`,
`EventPublicationMaintenance`) leży w pakiecie głównym i nie należy do żadnego modułu.

### 2. Granice zapisane w kodzie

- API modułu to podpakiety `api` / `events` oznaczone `@NamedInterface` — na pakiecie, nie na
  klasie, bo adnotacja na typie nie obejmuje klas generowanych przez Lomboka.
- Każdy moduł ma w `package-info.java` listę `allowedDependencies` i jawne `type = Type.CLOSED`.
- Zdarzenia między modułami mają `@DomainEvent` (jMolecules), żeby biblioteka widziała je jako
  publikowane.

### 3. Dwa sposoby rozmowy między modułami

- **Wywołanie:** `booking` → `routeplanning::api`. Jedyne wywołanie synchroniczne; czyste
  obliczenie bez I/O, wykonywane **przed** otwarciem transakcji rezerwacji.
- **Zdarzenie po commicie:** `booking` publikuje `ReservationConfirmed` wewnątrz transakcji; Event
  Publication Registry (`spring-modulith-starter-jpa`, tabela `event_publication`) zapisuje je w tej
  samej transakcji i dostarcza do `@ApplicationModuleListener` w `activityfeed` po commicie, na
  innym wątku. Własnego outboxa nie ma.

### 4. Rezerwacja w jednej transakcji

Walidacja → opłata i trasa (bez transakcji) → jedna transakcja: blokada wolnego doku
(`FOR UPDATE SKIP LOCKED`), zapis `CONFIRMED`, publikacja zdarzenia. Nie ma stanu HOLD, kompensacji
ani zadania sprzątającego. W transakcji nie wolno robić I/O poza bazą.

### 5. Dostarczanie zdarzeń: co najmniej raz

- Listener **przepuszcza wyjątki** — rejestr uznaje publikację za ukończoną, gdy listener wróci
  normalnie, więc połknięty błąd oznacza utracone zdarzenie.
- `EventPublicationMaintenance` co minutę ponawia publikacje starsze niż minuta, pod blokadą
  doradczą Postgresa (jedna instancja naraz). Biblioteka sama nie ponawia.
- `completion-mode: delete` — w tabeli zostają tylko publikacje oczekujące.
- `ReservationConfirmed` niesie `traceparent`, żeby ponowiony rekord trafił do trace'u żądania.
- Rekordy mają klucz Kafki (identyfikator rezerwacji, kod trasy) do rozpoznawania duplikatów.

### 6. Kafka tylko na wyjściu

Nic nie wchodzi; wychodzą `telemetry.enriched-reservations` i `telemetry.enriched-routes`.
Aplikacja nie ma konsumenta Kafki, a `spring.cloud.stream.function.autodetect: false` nie pozwala
podpiąć do topiku żadnego beana `Function`. Topiki `starport.*`, `telemetry.raw`
i `telemetry.alerts` nie istnieją; potok czujników (`shipmonitoring`) został usunięty, bo nic
w repozytorium go nie zasilało.

### 7. Pipes & Filters w `activityfeed`

Pakiety `filter` / `model` / `pipeline`. Sześć filtrów (`Function`) w dwóch potokach złożonych przez
`PipelineBuilder`:

```
rezerwacja:  ReservationIntakeFilter → StayDurationFilter → ReservationRecordFilter
trasa:       RouteExtractionFilter → RiskClassificationFilter → RouteRecordFilter
```

`null` kończy potok (rezerwacja bez trasy nie daje rekordu trasy). `PipelineBuilder` mierzy czas
etapu (`events.filter.<etap>`) i **nie łapie wyjątków** — muszą wyjść z listenera (punkt 5).

### 8. Obserwowalność

Jeden Prometheus z retencją lokalną 15 dni; bez Thanosa i MinIO; Grafana czyta `prometheus:9090`.
Nazwy metryk pozostają te z floty serwisów, z wyjątkami: zniknęły metryki outboxa i `telemetry.*`,
`reservations.hold.allocate` nazywa się `reservations.bay.allocate`, doszły
`events.publications.incomplete` / `.oldest.age` i `events.filter.*`.

### 9. Kto pilnuje

| Założenie | Strażnik |
|---|---|
| granice modułów, kształt nazwanych interfejsów, moduły `CLOSED`, publikowane zdarzenia | `ModularityTest` |
| planowanie trasy nie robi I/O i nie publikuje zdarzeń | `RoutePlanningPurityTest` |
| planowanie trasy jest tanie | reguła `job:routes_plan_seconds:p99_5m`, próg 50 ms |
| układ Pipes & Filters | `PipesAndFiltersArchitectureTest`, `PipelineBuilderTest` |
| listener nie połyka błędów, rekordy mają klucz | `FleetEventListenerTest` |
| ponawianie zaległych publikacji | `EventRedeliveryE2ETest` |
| kontrakt zewnętrzny (HTTP, topiki, nazwy metryk) | `system-acceptance/` — 30 testów na żywym stosie |

Zmiana asercji w `system-acceptance` wymaga ADR. Zestaw był w tej migracji zmieniany świadomie
trzy razy: wraz z usunięciem topików `starport.*` i metryk outboxa, z dodaniem kluczy rekordów
i z usunięciem potoku czujników.

## Jak do tego doszło

| Krok | Co zmienił |
|---|---|
| 1 | Pięć procesów → jedna aplikacja; moduły odwzorowywały dawne serwisy. |
| 2 | Nazwane interfejsy i `allowedDependencies`. |
| 3 | `@DomainEvent` i jawny `Type.CLOSED`. |
| 4 | Moduły pocięte według domen; rezerwacja w jednej transakcji; zdarzenia po commicie zamiast outboxa. |
| 5 | Usunięcie Thanosa i MinIO. |
| 6 | Ponawianie publikacji, klucze rekordów, `traceparent` w zdarzeniu. |
| 7 | Usunięcie potoku czujników; Pipes & Filters przeniesione do `activityfeed`. |

## Konsekwencje

- Znikają: sieć między serwisami, discovery, bramka, retry i circuit breaker, repliki, własny
  outbox, stan HOLD, konsument Kafki i stan w pamięci procesu.
- Awaria Kafki nie zmienia odpowiedzi HTTP rezerwacji.
- Granice są sprawdzane w każdym buildzie, także `-Pfast`.
- Jeden proces to jedno wdrożenie i jedna skala dla całości.
- `listener_id` w `event_publication` zawiera pełną nazwę klasy listenera; zmiana pakietu lub nazwy
  listenera albo zdarzenia wymaga pustej tabeli przed wdrożeniem (tu baza jest ulotna).

## Co zostaje otwarte

- Brak kolejki zdarzeń trwale błędnych — takie zdarzenie jest ponawiane bez końca.
- Wysyłka na Kafkę jest asynchroniczna (`producer.sync` nieustawione): błąd brokera po przyjęciu
  rekordu do bufora producenta nie wraca do listenera.
- Odpowiedź 422 `ROUTE_REJECTED` jest nieosiągalna przez API (najkrótszy zasięg to 5 LY, minimum 1).
- Klasa statku pochodzi z żądania i nie jest porównywana z klasą statku w bazie.
- Trasa jest liczona także dla żądania, które skończy się 409.
- Praca kilku instancji nie była sprawdzana pod obciążeniem.

## Odrzucone alternatywy

- **Zostać przy mikroserwisach.** Koszt sieci i infrastruktury bez korzyści dla jednego zespołu
  i jednego repozytorium. Wariant żyje na gałęziach `master` i `demo`.
- **Własny outbox zamiast rejestru publikacji.** Osiem klas i tabela do utrzymania; listener biegł
  w transakcji przekaźnika.
- **Listener synchroniczny (`@EventListener`).** Kod odbiorcy na wątku żądania, w transakcji nadawcy.
- **`@NamedInterface` na klasach.** Nie obejmuje klas generowanych.
- **Symulator odczytów zamiast usunięcia potoku czujników.** Moduł nadal nie łączyłby się z niczym
  w aplikacji.
- **`PipelineBuilder`, który łapie wyjątki.** Ciche gubienie zdarzeń.
