# ADR-0045: pełny model modułu w kodzie — `@DomainEvent` i jawny `Type.CLOSED`

## Status

Zaakceptowany (2026-11-20). Uzupełnia ADR-0044.

## Kontekst

Spring Modulith opisuje moduł trzema pojęciami:

- **interfejs udostępniany** (provided): publiczne beany + publikowane zdarzenia,
- **wnętrze** (internal): podpakiety ukryte przed innymi modułami,
- **interfejs wymagany** (required): zależności od beanów innych modułów + słuchane zdarzenia.

Po ADR-0044 kod miał wszystkie trzy, ale model biblioteki widział tylko część. W generowanych
opisach modułów (`target/spring-modulith-docs/module-*.adoc`) były wiersze „Spring components",
„Bean references" i „Events listened to", a wiersza „Published events" nie miał żaden moduł — choć
`reservations` publikuje `ReservationConfirmed`, a `routeplanning` publikuje `RoutePlannedEvent`.

Biblioteka rozpoznaje słuchacza po adnotacji `@EventListener`, ale publikacja to zwykłe wywołanie
`ApplicationEventPublisher.publishEvent(Object)` — typ zdarzenia trzeba jej wskazać.

## Decyzja

Rekordy zdarzeń między modułami dostają adnotację `org.jmolecules.event.annotation.DomainEvent`:
`reservations.events.ReservationConfirmed` i `routeplanning.events.RoutePlannedEvent`.

Nowa zależność: `org.jmolecules:jmolecules-events` (1.10.0) — same adnotacje i interfejsy, bez kodu
wykonywalnego i bez zależności przechodnich.

`ModularityTest.modules_declare_the_events_they_publish` sprawdza listę publikowanych zdarzeń
każdego modułu, żeby nowe zdarzenie bez adnotacji nie zniknęło po cichu z dokumentacji.

## Konsekwencje

- Opisy modułów pokazują teraz pełny interfejs udostępniany. Dla `routeplanning` także miejsce
  utworzenia zdarzenia.
- Zachowanie aplikacji bez zmian: adnotacja jest metadaną, Spring jej nie interpretuje.
- Zasada na przyszłość: zdarzenie przeznaczone dla innych modułów leży w pakiecie `events`
  (ADR-0044) **i** ma `@DomainEvent`.
- Odrzucona alternatywa: interfejs znacznikowy `org.jmolecules.event.types.DomainEvent`. Działa tak
  samo, ale wpisuje zależność od biblioteki w sygnaturę typu; adnotacja jest lżejsza.

## Typ modułu: jawne `ApplicationModule.Type.CLOSED`

`@ApplicationModule` ma atrybut `type`: `CLOSED` (domyślny) albo `OPEN`. Moduł otwarty udostępnia
także swoje podpakiety — inne moduły mogą sięgać do jego wnętrza bez błędu „non-exposed type".

Wszystkie cztery moduły dostają jawne `type = ApplicationModule.Type.CLOSED`. To wartość domyślna,
więc zachowanie się nie zmienia; zapis jest po to, żeby decyzja była widoczna w kodzie, a zmiana
na `OPEN` — w review. `ModularityTest.no_module_is_open` pilnuje, żeby żaden moduł nie został
otwarty przypadkiem.

`OPEN` jest narzędziem migracji starego kodu (najpierw wszystko otwarte, potem zamykanie moduł po
module). Tu nie ma czego migrować.

Sprawdzone na próbnej klasie w `reservations`, sięgającej po `routeplanning.internal.RouteMetrics`:

| `routeplanning` | Wynik `verify()` |
|---|---|
| `CLOSED` | `Module 'reservations' depends on non-exposed type …internal.RouteMetrics within module 'routeplanning'!` |
| `OPEN` | `Module 'reservations' depends on module 'routeplanning' via … -> …internal.RouteMetrics. Allowed targets: routeplanning :: api.` |

Wniosek: `type` dostawcy i `allowedDependencies` odbiorcy to dwa niezależne zabezpieczenia.
Otwarcie modułu zdejmuje pierwsze, ale lista odbiorcy nadal obowiązuje.
