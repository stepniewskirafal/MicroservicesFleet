# ADR-0044: nazwane interfejsy modułów i jawna lista dozwolonych zależności

## Status

Zaakceptowany (2026-11-20). Zmienia ADR-0043 w jednym punkcie: „API modułu = typy w jego pakiecie
bazowym" przestaje obowiązywać dla `reservations` i `routeplanning` — ich API to nazwane interfejsy
w podpakietach `api` i `events`. Reszta ADR-0043 (moduły, komunikacja, Kafka jako brzeg) bez zmian.

## Kontekst

Po ADR-0043 `ModularityTest` pilnował dwóch rzeczy: braku cykli i braku sięgania do cudzych
podpakietów. Nie pilnował **kierunku ani zakresu** zależności:

- żaden moduł nie miał `package-info.java`, więc żaden nie deklarował `allowedDependencies` —
  dowolny moduł mógł zacząć używać API dowolnego innego, o ile nie powstał cykl,
- całe API modułu było jednym workiem (pakiet bazowy). `telemetry`, które ma tylko słuchać
  zdarzeń, mogło wstrzyknąć `PlanRouteUseCase` i wywołać planowanie; `reservations` mogło zacząć
  słuchać `RoutePlannedEvent`. Build byłby zielony.

Diagram zależności z ADR-0043 był więc opisem, nie regułą.

## Decyzja

### 1. Każdy moduł ma `package-info.java` z `@ApplicationModule`

| Moduł | `allowedDependencies` | Znaczenie |
|---|---|---|
| `reservations` | `routeplanning::api` | woła planowanie; nie widzi zdarzeń plannera ani telemetrii |
| `routeplanning` | `{}` | nie zależy od żadnego modułu |
| `telemetry` | `reservations::events`, `routeplanning::events` | tylko słucha; nie może nikogo wywołać |
| `observability` | `{}` | konfiguracja techniczna, bez zależności biznesowych |

Pusta lista (`{}`) to nie to samo co brak atrybutu: brak oznacza „wolno wszystko", pusta — „nie
wolno nic".

### 2. API modułów to nazwane interfejsy (`@NamedInterface`) na poziomie pakietu

| Nazwany interfejs | Pakiet | Typy |
|---|---|---|
| `routeplanning::api` | `routeplanning.api` | `PlanRouteUseCase`, `RouteRequest`, `PlannedRoute`, `RouteRejectionException` |
| `routeplanning::events` | `routeplanning.events` | `RoutePlannedEvent` |
| `reservations::events` | `reservations.events` | `ReservationConfirmed` |

Adnotacja stoi w `package-info.java` podpakietu. `EventPublishingException` zostaje w pakiecie
bazowym `routeplanning` (interfejs nienazwany): rzuca go tylko wnętrze modułu, a żaden inny moduł
nie ma tego interfejsu na liście dozwolonych.

### 3. Dlaczego pakiet, a nie adnotacja na typie

`@NamedInterface` można też postawić na pojedynczym typie w pakiecie bazowym — bez przenoszenia
klas. Sprawdzono to i odrzucono: adnotacja na typie **nie obejmuje klas zagnieżdżonych**, a rekordy
API mają `@Builder` Lomboka. `verify()` zgłosił:

```
Module 'reservations' depends on module 'routeplanning' via
  …RoutePlanningModuleAdapter -> …routeplanning.RouteRequest$RouteRequestBuilder.
  Allowed targets: routeplanning :: api.
```

Wariant pakietowy obejmuje wszystko, co leży w pakiecie, łącznie z generowanymi builderami.

### 4. Test

`ModularityTest.module_boundaries_are_respected` (`verify()`) wymusza listy z punktu 1. Nowy
`named_interfaces_expose_exactly_the_intended_types` sprawdza skład interfejsów z punktu 2 — żeby
przeniesienie zdarzenia do `api` (albo odwrotnie) nie przeszło niezauważone. Oba biegną w fazie
jednostkowej, także przy `-Pfast`.

## Konsekwencje

- Diagram zależności z ADR-0043 jest teraz regułą builda, nie opisem.
- Nowa zależność między modułami wymaga zmiany `package-info.java` — widać ją w review.
- Zmieniły się nazwy pakietów sześciu typów (`…routeplanning.api.*`, `…routeplanning.events.*`,
  `…reservations.events.*`). Kontrakt zewnętrzny (HTTP, topiki Kafki, metryki) bez zmian;
  `system-acceptance/` nie wymaga żadnej zmiany.
- `allowedDependencies` dotyczy tylko zależności między własnymi modułami. Zależności od bibliotek
  (np. `StreamBridge` w logice) nadal nie są pilnowane — to pozostaje zadaniem dla reguł ArchUnit.
- Kod testowy nie podlega `verify()` (analizowane są klasy produkcyjne).
