# ADR-0046: rozłączne domeny, zdarzenia po commicie, Kafka tylko dla wyliczonej telemetrii

## Status

Zaakceptowany (2026-11-23). Zmienia ADR-0043, ADR-0044 i ADR-0045. Zastępuje ADR-0010 (własny
outbox). Zmienia ADR-0016 (lista topików) i ADR-0017 (propagacja trace przez outbox).

## Kontekst

ADR-0043 przeniósł trzy serwisy do jednego procesu, ale granice modułów i mechanizmy zostały takie,
jak między serwisami:

- `routeplanning` w środku wywołania metody wysyłał `starport.route-planned` do Kafki. Awaria
  brokera kończyła rezerwację błędem 409, choć trasa to czysta arytmetyka.
- `telemetry` słuchał zdarzeń synchronicznie: na wątku żądania HTTP (trasa) i wewnątrz transakcji
  relaya outboxa (rezerwacja). Poprawność trzymały dwie konwencje: `@Order` i „nigdy nie rzucaj".
- Rezerwacja szła w dwóch transakcjach (HOLD, CONFIRM) z kompensacją i `HoldReaper`. Podział
  chronił przed trzymaniem transakcji przez zdalne wywołanie HTTP, którego już nie ma.
- Zasięg paliwa per klasa statku — wiedza o trasach — leżał w adapterze po stronie `reservations`.
- `telemetry` łączył dwie niezwiązane rzeczy: pipeline sensorów i wzbogacanie zdarzeń rezerwacji.
- Topiki `starport.reservations` i `starport.route-planned` istniały, bo tak rozmawiały serwisy.
  W repo nie ma ich konsumenta.

## Decyzja

### Moduły

| Moduł | Posiada | Wystawia | Zależy od | Kafka |
|---|---|---|---|---|
| `booking` | starporty, doki, rezerwacje, klienci, statki, taryfa | `events` (`ReservationConfirmed` z danymi trasy) | `routeplanning::api` | brak |
| `routeplanning` | wyliczenie trasy: ryzyko korytarza, ETA, zasięg paliwa per klasa statku | `api` | nic | brak |
| `shipmonitoring` | pipeline sensorów, progi, okna agregacji | nic | nic | `telemetry.raw` → `telemetry.alerts` |
| `activityfeed` | wzbogacanie zdarzeń rezerwacji | nic | `booking::events` | → `telemetry.enriched-reservations`, `telemetry.enriched-routes` |

`routeplanning` zostaje osobnym modułem, mimo że dziś jest czystą funkcją: to miejsce na bardziej
złożone obliczenia tras w przyszłości. Moduł `observability` znika (konfiguracja techniczna nie jest
modułem biznesowym).

### Komunikacja

1. **Zdarzenia między modułami idą po commicie, na innym wątku, w osobnej transakcji.** Mechanizm:
   Event Publication Registry ze Spring Modulith (`spring-modulith-starter-jpa`) i
   `@ApplicationModuleListener`. Publikacja zapisuje się w transakcji nadawcy; nieukończona zostaje
   w tabeli `event_publication` do ponowienia.
2. **Jeden wyjątek: `booking` → `routeplanning::api` to wywołanie synchroniczne.** Trasa jest częścią
   odpowiedzi 201. Wywołanie jest czystym obliczeniem, bez I/O, i odbywa się **przed** otwarciem
   transakcji `booking`. Moduły dzielą więc wątek, ale nie transakcję.
3. **`routeplanning` niczego nie publikuje.** Dane trasy jadą w `ReservationConfirmed`.
4. **Własny outbox znika** (`event_outbox`, relay `InboxPublisher`). Jego jedynym powodem był zapis
   „baza + topik `starport.reservations`"; rejestr publikacji daje to samo dla zdarzenia w procesie.

### Kafka

Wchodzi `telemetry.raw`. Wychodzą `telemetry.alerts`, `telemetry.enriched-reservations` i
`telemetry.enriched-routes`. Topiki `starport.reservations` i `starport.route-planned` są usunięte.

### Rezerwacja w jednej transakcji

1. walidacja,
2. opłata i trasa, bez transakcji (trasa odrzucona → 422, zanim cokolwiek zapiszemy),
3. jedna transakcja: blokada doku (`FOR UPDATE SKIP LOCKED`, ADR-0020), zapis `CONFIRMED` z trasą,
   publikacja zdarzenia,
4. odpowiedź 201.

Znikają: status `HOLD`, kompensacja, `HoldReaper`, równoległe liczenie opłaty i trasy na wątkach
wirtualnych, ścieżka `RouteUnavailableException` (409).

### `system-acceptance`

Zestaw był zamrożony jako dowód, że migracja zachowała zachowanie floty trzech serwisów (ADR-0043).
Ten ADR **świadomie zmienia kontrakt**, więc zmieniają się dwie klasy:

- `ReservationEventFlowSystemTest` — bez asercji na `starport.reservations` i `starport.route-planned`;
  asercje na oba topiki `telemetry.enriched-*` i na wspólny trace zostają,
- `ObservabilitySystemTest` — bez metryk outboxa.

`ReservationApiSystemTest` i `TelemetryPipelineSystemTest` pozostają bez zmian. Od tej chwili zestaw
jest punktem odniesienia dla nowego kontraktu, nie dla migracji.

## Konsekwencje

- Awaria Kafki nie wpływa już na odpowiedź HTTP rezerwacji.
- Graf zależności ma dwie krawędzie: `booking` → `routeplanning::api`, `activityfeed` → `booking::events`.
- Nazwy metryk zostają (w tym `reservations.hold.allocate`); ich zmiana to osobna decyzja.
- `processedBy` w komunikatach `telemetry.enriched-*` zostaje `telemetry-pipeline`.

Ryzyka przyjęte do rozwiązania po wdrożeniu:

- ponawianie nieukończonych publikacji nie jest koordynowane między instancjami,
- rejestr nie zapisuje `traceparent`, więc ponowienie po awarii nie należy do trace'u żądania,
- dostarczanie jest „co najmniej raz" — odbiorcy `telemetry.enriched-*` muszą znosić duplikaty,
- tabela `event_publication` rośnie, dopóki nie ustawimy usuwania ukończonych publikacji,
- gdy obliczenia tras urosną albo dojdzie w nich I/O, wywołanie synchroniczne zacznie blokować
  odpowiedź HTTP; wtedy potrzebna będzie decyzja o trybie asynchronicznym i zmianie kontraktu REST,
- okna agregacji sensorów żyją w pamięci instancji.

## Odrzucone alternatywy

- **Trasa wewnątrz `booking`.** Usuwa jedyne wywołanie synchroniczne, ale zabiera miejsce na rozwój
  obliczeń tras.
- **Trasa liczona asynchronicznie po rezerwacji.** Pełne rozdzielenie wątków, ale odpowiedź 201
  traci trasę — zmiana kontraktu REST.
- **Zostawić własny outbox.** Daje zapisany `traceparent` i limit prób, ale utrzymuje osiem klas
  i tabelę dla topiku, którego już nie ma.
