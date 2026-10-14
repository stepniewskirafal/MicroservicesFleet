# ADR-0048: ponawianie publikacji, klucze rekordów, trace w zdarzeniu i porządki po ADR-0046

## Status

Zaakceptowany (2026-11-23). Uzupełnia ADR-0046. Zmienia ADR-0030 (jedna nazwa metryki).

## Kontekst

ADR-0046 zostawił listę ryzyk „do rozwiązania po wdrożeniu". Po wdrożeniu okazało się, że pierwsze
z nich jest poważniejsze, niż zapisano:

- **Nic nie ponawiało nieudanych publikacji.** Event Publication Registry trzyma wiersz
  w `event_publication`, dopóki listener nie skończy, ale sama biblioteka ponawia tylko przy
  restarcie aplikacji (i tylko po włączeniu właściwości). Gdy listener `activityfeed` padł —
  np. przy niedostępnej Kafce — rekord nie trafiał do `telemetry.enriched-*` aż do ręcznej
  interwencji. Zapowiadane „co najmniej raz" nie było prawdą.
- Ponowiona publikacja biegłaby w innym trace niż żądanie, które potwierdziło rezerwację:
  rejestr zapisuje zdarzenie, a nie kontekst trace wątku.
- Rekordy `telemetry.enriched-*` wychodziły bez klucza Kafki, więc odbiorca nie miał po czym
  rozpoznać duplikatu.
- Tabela `event_publication` rosła o jeden wiersz na każdą rezerwację.
- Metryka `reservations.hold.allocate` nosiła nazwę stanu, którego już nie ma.
- Trasa była liczona także dla żądań, które chwilę później kończyły się 404.
- Założenie „planowanie trasy to czyste obliczenie" i założenie „`telemetry.raw` jest kluczowany
  po `shipId`" nie były niczym pilnowane.

## Decyzja

### 1. Ponawianie nieukończonych publikacji

`EventPublicationMaintenance` (pakiet główny aplikacji, nie moduł) uruchamia się co
`events.redelivery.interval-ms` (60 s) i woła
`IncompleteEventPublications.resubmitIncompletePublicationsOlderThan(...)` dla publikacji starszych
niż `events.redelivery.older-than-ms` (60 s). Próg wieku trzyma zadanie z dala od publikacji, które
po prostu są jeszcze w obsłudze.

**Wiele instancji.** Każda instancja uruchamia to zadanie na tej samej tabeli. Blokada doradcza
Postgresa (`pg_try_advisory_xact_lock`), trzymana do końca transakcji zadania, przepuszcza jedną
instancję naraz; pozostałe pomijają rundę. Bez nowej zależności i bez tabeli blokad.

**Zdarzenie, które nigdy się nie udaje**, jest ponawiane w każdej rundzie, bez końca. Rejestr
w wersji 1.4 nie liczy prób, więc nie ma dead-letter. Zamiast tego jest widoczność:

- `events.publications.incomplete` — liczba czekających publikacji,
- `events.publications.oldest.age` — wiek najstarszej,
- ostrzeżenie w logu, gdy najstarsza jest starsza niż `events.redelivery.warn-after-ms` (15 min).

Obie metryki odświeża zadanie; lambda gauge'a nie pyta bazy. Każda instancja raportuje tę samą
tabelę, więc dashboard czyta `max()`.

### 2. Ukończona publikacja jest usuwana

`spring.modulith.events.completion-mode: delete`. W `event_publication` zostaje tylko to, co
jeszcze trzeba dostarczyć: tabela nie rośnie z historią, a liczba wierszy jest liczbą zaległości.

### 3. Klucz Kafki na rekordach `telemetry.enriched-*`

`telemetry.enriched-reservations` — klucz to identyfikator rezerwacji;
`telemetry.enriched-routes` — klucz to kod trasy. Dostarczanie pozostaje „co najmniej raz";
klucz jest tym, po czym odbiorca deduplikuje. Zmiana niełamiąca: treść rekordów bez zmian.

### 4. Kontekst trace podróżuje w zdarzeniu

`ReservationConfirmed` dostaje pole `traceparent` (W3C). Listener porównuje je z trace bieżącego
wątku. Przy pierwszym dostarczeniu są równe i nic się nie dzieje. Przy ponowieniu wątek niesie
trace zadania ponawiającego, więc listener otwiera span `events.reservation.redelivery`
z rodzicem odtworzonym z pola — rekordy trafiają do trace'u pierwotnego żądania.

Zdarzenia zapisane przed tą zmianą nie mają pola i nadal się wczytują (pole jest wtedy puste).

### 5. `reservations.hold.allocate` → `reservations.bay.allocate`

Zmiana w jednym kroku: kod, histogram SLO, reguły `recording.yml` (także nazwy reguł), dashboard,
`system-acceptance`. Mierzy to samo co dotąd — przydział doku wewnątrz transakcji rezerwacji.

### 6. Klient i statek sprawdzani w walidacji

Dwie nowe reguły walidacji (`CustomerValidationRule`, `ShipValidationRule`) działają przed
wyliczeniem opłaty i trasy. Żądanie, które skończy się 404, nie planuje już trasy i nie zawyża
metryk `routes.*`. Kolejność odpowiedzi bez zmian: port, potem klient, potem statek.

Żądanie odrzucone z 409 (brak wolnego doku) nadal ma policzoną trasę: o braku miejsca wiadomo
dopiero w transakcji. Przyjęte.

### 7. Dwa strażniki założeń

- **`RoutePlanningPurityTest`** (ArchUnit, faza unit): moduł `routeplanning` nie może zależeć od
  Kafki, JPA/JDBC, klientów HTTP ani `ApplicationEventPublisher`. Pierwsze I/O w planowaniu
  wywróci build — i to jest moment na decyzję o trybie asynchronicznym.
- **Próg rewizji**: p99 `routes.plan` powyżej 50 ms (reguła `job:routes_plan_seconds:p99_5m`).
  Stos nie ma Alertmanagera, więc to próg do odczytu, nie alarm.
- **`telemetry.messages.key.mismatch`**: licznik odczytów sensorów, których klucz Kafki nie jest
  `shipId`. Okna agregacji żyją w pamięci jednej instancji, więc wszystkie odczyty statku muszą
  trafiać do tej samej — Kafka gwarantuje to tylko przy kluczu `shipId`. Producent jest poza tym
  repozytorium; licznik pokazuje, że kontrakt jest łamany. Odczyt i tak jest przetwarzany.

### `system-acceptance`

- `ObservabilitySystemTest`: `reservations_hold_allocate_seconds_bucket` zastąpione przez
  `reservations_bay_allocate_seconds_bucket`; doszły `events_publications_incomplete_events`,
  `events_publications_oldest_age_seconds`, `telemetry_messages_key_mismatch_total`.
- `ReservationEventFlowSystemTest`: doszły asercje na klucze obu rekordów.

## Konsekwencje

- „Co najmniej raz" jest teraz prawdą także bez restartu aplikacji, z opóźnieniem do ok. 2 minut
  (próg wieku + okres zadania).
- Rekord może przyjść dwa razy; odbiorca deduplikuje po kluczu.
- Jedno zapytanie do `event_publication` na minutę na instancję.
- Dwa dodatkowe zapytania `exists` na każde żądanie rezerwacji (klient, statek).

## Co zostaje otwarte

- **Brak dead-letter.** Zdarzenie trwale niepoprawne jest ponawiane bez końca i widoczne tylko
  w metrykach i logu. Limit prób wymaga własnego licznika albo Spring Modulith 2.x (statusy
  publikacji) — osobna decyzja.
- **Odpowiedź 422 „trasa odrzucona" jest nieosiągalna przez API**: najkrótszy zasięg paliwa to
  5 LY, minimum 1 LY. Ścieżka zostaje do czasu, gdy `routeplanning` dostanie model odległości.
- **Okna agregacji sensorów** nadal giną przy rebalansie grupy konsumentów (okno trwa 5 minut).
  Trwałe rozwiązanie to stan w Kafka Streams — tylko jeśli ta utrata zacznie przeszkadzać.
- **Wywołanie `booking` → `routeplanning` pozostaje synchroniczne.** Strażniki z punktu 7 mówią,
  kiedy do tego wrócić.

## Odrzucone alternatywy

- **`republish-outstanding-events-on-restart`.** Ponawia tylko przy starcie, a przy wielu
  instancjach każda startująca instancja ponawia także publikacje będące w obsłudze u innych.
- **ShedLock.** Robi to samo co blokada doradcza, ale wymaga zależności i tabeli.
- **Zaakceptować osobny trace przy ponowieniu.** Dotyczy tylko ścieżki awaryjnej — ale to właśnie
  awarie chce się śledzić od żądania do rekordu.
