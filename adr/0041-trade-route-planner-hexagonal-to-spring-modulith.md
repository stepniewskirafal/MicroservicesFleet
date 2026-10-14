# ADR-0041: trade-route-planner — z architektury heksagonalnej na Spring Modulith

## Status

Zaakceptowany (2026-10-14). Zastępuje ADR-0021 w zakresie trade-route-planner.

## Kontekst

trade-route-planner realizował architekturę heksagonalną (`domain/`, `port/{in,out}/`,
`adapter/{in,out}/`), egzekwowaną konwencją, nie narzędziem (ArchUnit był zależnością,
ale żaden test nie pilnował granic). Dla serwisu tej wielkości (1 use case, 2 adaptery
wyjściowe) pełny zestaw portów i composition root to głównie koszt ceremonii. Spring
Modulith daje granice modułów weryfikowane testem (`ApplicationModules.verify()`),
komunikację przez zdarzenia aplikacyjne i generowaną dokumentację — przy zachowaniu
jednego deployowalnego artefaktu.

## Decyzja

Przepisujemy wnętrze trade-route-planner na Spring Modulith **1.4.13** (linia dla Boot 3.5).
Zachowanie widoczne z zewnątrz (REST, kontrakt Kafka, nazwy metryk, discovery) pozostaje
bez zmian — pilnuje tego zamrożony zestaw testów akceptacyjnych black-box
(`RoutePlannerAcceptanceIntegrationTest`, prawdziwa Kafka przez Testcontainers).

Moduły (bezpośrednie podpakiety `com.galactic.traderoute`):

| Moduł | API (pakiet bazowy) | Wnętrze (`internal/`) |
|---|---|---|
| `planning` | `PlanRouteUseCase`, `RouteRequest`, `PlannedRoute`, `RouteRejectionException`, `RoutePlannedEvent` | `PlanRouteService`, `RouteMetrics` (Micrometer) |
| `web` | — (sam REST, nic nie eksponuje) | kontroler, DTO, exception handler |
| `publishing` | `EventPublishingException` | listener → `StreamBridge`, wire-DTO `RoutePlannedMessage` |
| `observability` | — | `ObservationConfig`, `OtelLogbackInstaller` |

Mapowanie pojęć heksagonalnych:

- **port wejściowy** → interfejs w API modułu `planning` (bez zmiany roli),
- **port wyjściowy zdarzeń** (`RouteEventPublisher`) → **zdarzenie aplikacyjne**:
  `PlanRouteService` publikuje `RoutePlannedEvent` przez `ApplicationEventPublisher`,
  a `publishing` odbiera je **synchronicznym** `@EventListener` i wysyła na Kafkę.
  Synchronicznym, bo kontrakt zewnętrzny wymaga: porażka publikacji → `routes.publish.failed`
  + HTTP 502 w tym samym żądaniu, metryka sukcesu dopiero po publikacji (jak dotąd).
- **port wyjściowy metryk** (`RouteMetricsPort`) → wnętrze `planning` (interfejs + implementacja
  Micrometer w `internal/`); to szczegół obserwowalności jednego modułu, nie kontrakt między modułami.
- **composition root** (`RoutePlanningConfig`) → znika; `PlanRouteService` jest `@Service`
  (Modulith nie wymaga rdzenia wolnego od frameworka — granice pilnuje weryfikacja modułów).

Granice egzekwuje test `ModularityIntegrationTest` (`ApplicationModules.verify()` + `Documenter`).

## Dlaczego

- Granice sprawdzane w buildzie zamiast konwencji w README (heksagon nie był egzekwowany).
- Mniej ceremonii przy tym samym odseparowaniu domeny (1 use case nie broni 3 warstw portów).
- Zdarzenia aplikacyjne to naturalny szew pod przyszły outbox (`spring-modulith-events`),
  którego ten serwis dziś świadomie nie ma (dual-write opisany przy `routes.publish.failed`).
- Dokumentacja modułów generowana z kodu (PlantUML/moduły), nie utrzymywana ręcznie.

## Alternatywy

- **Zostawić heksagon + dodać ArchUnit** — egzekwuje granice, ale nie redukuje ceremonii
  i nie daje modelu zdarzeń ani dokumentacji z kodu.
- **`@ApplicationModuleListener` (async + event registry)** — zmienia semantykę na
  at-least-once po commicie; tu nie ma transakcji, a kontrakt wymaga synchronicznej porażki → 502.
- **Scalenie floty w jeden modulith** — zmienia zachowanie zewnętrzne (discovery, topologia),
  wprost wykluczone przez wymagania migracji.

## Konsekwencje

- PIT `targetClasses` i progi bez zmian merytorycznych, ścieżki pakietów zaktualizowane.
- Testy wewnętrzne przeniesione do pakietów modułów; testy kontraktowe SCC bez zmian
  (mock przeniesiony z portu na `StreamBridge`).
- ADR-0021 pozostaje historycznym opisem wariantu heksagonalnego.
