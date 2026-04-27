# Atrybuty spana w OpenTelemetry — kompletny przewodnik

Dokument tłumaczy **czym są atrybuty spana**, **jak je nadawać w kodzie Java** (Spring Boot 3 + Micrometer Tracing), **gdzie one trafiają** (Tempo, Prometheus, Loki) i **po co się ich używa**.

Punkt wyjścia: w dokumencie `tempo-metrics-generator-procesory.md` napisałem, że Tempo `span-metrics` procesor używa czterech atrybutów `service.name, span.name, span.kind, status_code` jako labeli metryk. To wywołało pytanie — co to są te atrybuty, skąd się biorą, czemu te cztery a nie inne. Tutaj odpowiedź pełna.

---

## Część I — Czym jest atrybut spana

### Span — pojedynczy zapis pracy w czasie

W tracingu **span** to atomowa jednostka „aplikacja zrobiła operację Y w czasie T1..T2". Każdy span ma stałą strukturę:

```
Span
├── traceId           — identyfikator nadrzędnego trace'u (16-32 hex)
├── spanId            — identyfikator tego spana (16 hex)
├── parentSpanId      — id spana, który mnie zrodził (NULL dla root spana)
├── name              — czytelna nazwa operacji ("POST /reservations", "reservations.confirm")
├── kind              — typ: SERVER | CLIENT | PRODUCER | CONSUMER | INTERNAL
├── startTimeUnixNano — kiedy się zaczęło
├── endTimeUnixNano   — kiedy się skończyło
├── status            — UNSET | OK | ERROR
├── attributes        — { key1: value1, key2: value2, ... }    ← ATRYBUTY
├── events            — punkty czasowe w trakcie spana (np. "exception thrown")
├── links             — referencje do innych spanów (rzadko używane)
└── resource          — atrybuty PROCESU, w którym span się wykonał (osobne!)
```

**Atrybut spana** = **para klucz-wartość**, gdzie klucz to string, a wartość to typ prymitywny (string/long/double/bool) albo tablica takich wartości. Atrybuty są **opcjonalne** — span może ich nie mieć ani jednego (poza tymi, które framework dorzuca automatycznie).

### Trzy „warstwy" atrybutów — często mylone

To jest kluczowa rzecz, bo różne narzędzia traktują je inaczej:

| Warstwa | Co to jest | Przykład | Ustawiane raz na |
|---|---|---|---|
| **Resource attributes** | atrybuty PROCESU | `service.name=starport-registry`, `service.version=1.0.0`, `host.name=docker-abc` | start aplikacji |
| **Span intrinsic fields** | top-level pola spana, NIE atrybuty | `name`, `kind`, `status` | tworzenie spana |
| **Span attributes** | key-value attached to span | `http.method=POST`, `customer.tier=gold` | tworzenie spana lub w trakcie |

**Pułapka pierwsza**: `service.name` to **resource attribute**, nie span attribute. Każdy span „dziedziczy" je z procesu, w którym powstał. Dlatego ustawiasz go raz w `application.yml` i pojawia się we wszystkich spanach.

**Pułapka druga**: `span.name` i `span.kind` to **NIE atrybuty** — to top-level pola spana. Tempo `span-metrics` traktuje je jako labele „dla wygody nazewnictwa", ale w API OTel nie używasz `attributes.put("span.name", ...)` — używasz `Span.builder().setSpanName(...)`.

W Tempo `span-metrics` procesor traktuje wszystkie cztery **jednorodnie** jako labele Prometheusa, dlatego w PromQL widzimy:
```
traces_spanmetrics_calls_total{service="starport-registry", span_name="reservations.confirm", span_kind="SPAN_KIND_INTERNAL", status_code="STATUS_CODE_OK"}
```

…chociaż technicznie tylko jeden z tych czterech (`service`) jest „atrybutem", a reszta to top-level pola spana.

---

## Część II — Cztery „metric-defining" pola w `span-metrics`

Tempo `span-metrics` procesor używa domyślnie tych czterech jako labele Prometheusa:

### `service.name`

**Typ**: resource attribute (string).
**Co znaczy**: nazwa serwisu emitującego span. Globalnie unikalna w obrębie systemu.
**Skąd pochodzi w Spring Boot 3**: z property `spring.application.name`. Przykład z naszego `starport-registry/application.yml`:
```yaml
spring:
  application:
    name: starport-registry
```
Spring Boot Actuator + Micrometer Tracing automatycznie ustawia `service.name = ${spring.application.name}` jako resource attribute we wszystkich emitowanych spanach.

**Co byś zobaczył w surowym OTLP**:
```json
{
  "resource": { "attributes": [{ "key": "service.name", "value": { "stringValue": "starport-registry" }}]},
  "scopeSpans": [...]
}
```

**Po co**: separator między mikrousługami w dashboardach, panelach, alertach. „Latencja per serwis" wymaga tego labela.

**Cardinality**: bardzo niska (= liczba serwisów, u nas 4: `api-gateway`, `starport-registry`, `trade-route-planner`, `telemetry-pipeline`).

### `span.name`

**Typ**: top-level field spana (string).
**Co znaczy**: czytelna nazwa operacji. Konwencje OpenTelemetry:
- HTTP server: `<METHOD> <route_template>` (np. `POST /api/v1/starports/{code}/reservations`)
- HTTP client: `<METHOD>` (np. `POST`) lub `<METHOD> <peer.service>`
- Database: `<DB.OPERATION> <DB.NAME>` (np. `SELECT starports`)
- Messaging: `<destination> <kind>` (np. `starport.reservations send`)
- Custom (Observation API): cokolwiek (`reservations.confirm`, `reservations.fees.calculate`)

**Skąd pochodzi w naszym kodzie**:
- HTTP server spany — auto-generowane przez Spring Boot z route template
- Observations — nazwa pierwszego argumentu do `Observation.createNotStarted("nazwa", ...)`. Przykład z `ConfirmReservationService.java:19`:
  ```java
  private static final String OBSERVATION_NAME = "reservations.confirm";
  // ...
  Observation obs = Observation.createNotStarted(OBSERVATION_NAME, observationRegistry);
  ```
  → emit'uje span z `name="reservations.confirm"`.

**Po co**: rozróżnienie operacji w obrębie jednego serwisu. „Latencja `reservations.confirm`" vs „latencja `reservations.hold.allocate`" — dwa różne pomiary, jeden serwis.

**Cardinality — pułapka**: jeśli span name zawiera dynamiczne wartości (np. `POST /reservations/42`), kardynalność eksploduje. Dlatego framework używa **route template** (`POST /reservations/{id}`) zamiast surowego URL.

### `span.kind`

**Typ**: top-level enum field.
**Wartości**: `INTERNAL` | `SERVER` | `CLIENT` | `PRODUCER` | `CONSUMER`.

**Co znaczą**:
- **`SERVER`** — dostałem request (HTTP server, gRPC server, Kafka consumer w sensie „przyjąłem wiadomość")
- **`CLIENT`** — wysłałem request (HTTP client, gRPC client)
- **`PRODUCER`** — wyprodukowałem wiadomość do brokera (Kafka producer)
- **`CONSUMER`** — skonsumowałem wiadomość z brokera (Kafka consumer)
- **`INTERNAL`** — wewnętrzna operacja, nic nie wchodzi/wychodzi (większość Observations w Java to to)

**Skąd pochodzi**:
- HTTP server spany → `SERVER` automatycznie (Spring Boot)
- HTTP client spany przez `RestClient` → `CLIENT` automatycznie
- W kodzie z `Observation.createNotStarted` → domyślnie `INTERNAL`
- Można ustawić explicite przez **kontekst Observation**:
  ```java
  // OutboxAppender.java:30-39 — ustawia PRODUCER kontekst:
  SenderContext<Map<String, Object>> senderContext = new SenderContext<>(Map::put, Kind.PRODUCER);
  senderContext.setRemoteServiceName("kafka");
  
  Observation.createNotStarted("reservations.outbox.append", () -> senderContext, observationRegistry)
          .lowCardinalityKeyValue("binding", reservationsBinding)
          // ...
          .observe(() -> { /* INSERT do event_outbox */ });
  ```
  → span ma `kind=PRODUCER`, `peer.service=kafka`, mimo że fizycznie INSERT-uje do tabeli (outbox pattern!).

  Analogicznie `InboxPublisher.java:95-101` używa `ReceiverContext` z `Kind.CONSUMER`, choć technicznie SELECT-uje z DB.

**Po co**: różne span kinds wymagają różnej obróbki w narzędziach.
- Tempo `service-graphs` paruje **CLIENT** spany z **SERVER** spanami w innym serwisie → krawędź grafu.
- `span-metrics` rozróżnia (i osobno raportuje) duration po stronie servera vs klienta → diagnoza network latency.
- `local-blocks` z `filter_server_spans_from_root_span: true` filtruje po kindzie.

**Cardinality**: bardzo niska (5 wartości stałe).

### `status_code`

**Typ**: top-level enum.
**Wartości**: `STATUS_CODE_UNSET` (default) | `STATUS_CODE_OK` | `STATUS_CODE_ERROR`.

**Co znaczy**: czy operacja się udała.
- `OK` — sukces (jawnie ustawiony)
- `ERROR` — błąd (jawnie ustawiony, np. wyjątek)
- `UNSET` — brak deklaracji (domyślnie)

**Skąd pochodzi w naszym kodzie**:
- HTTP server spany — Spring Boot ustawia `ERROR` automatycznie dla 5xx, `UNSET`/`OK` dla 2xx-4xx
- Observations — automatyczne `ERROR` jeśli lambda w `.observe(() -> ...)` rzuci wyjątek. Przykład z `InboxPublisher.java:111-117`:
  ```java
  try (Observation.Scope scope = publishObs.openScope()) {
      // ...
      streamBridge.send(...)
  } catch (Exception ex) {
      publishObs.error(ex);  // ← jawnie ustawia status=ERROR + dorzuca exception event
      // ...
  } finally {
      publishObs.stop();
  }
  ```

**Po co**: filtrowanie i alerting. „Daj mi rate spanów z `status_code=STATUS_CODE_ERROR`" → alarm na pojawiające się błędy. Jest też używany przez `service-graphs` do liczenia `traces_service_graph_request_failed_total{client, server}`.

**Cardinality**: bardzo niska (3 wartości).

---

## Część III — Konwencje semantyczne OpenTelemetry

OpenTelemetry definiuje **standard nazewnictwa atrybutów** (Semantic Conventions). Powód: jeśli każdy zespół wymyśli własną nazwę dla „statusu HTTP" (`http.code`, `httpStatusCode`, `response.status`), żadne narzędzie nie będzie umiało automatycznie agregować tego globalnie.

### Najważniejsze grupy

| Grupa | Przykładowe atrybuty | Kiedy się pojawiają |
|---|---|---|
| **HTTP server** | `http.request.method`, `http.route`, `http.response.status_code`, `url.path` | każdy HTTP server span |
| **HTTP client** | `http.request.method`, `server.address`, `server.port`, `http.response.status_code` | każdy HTTP client span |
| **Database** | `db.system`, `db.name`, `db.statement`, `db.operation` | spany JDBC/JPA (jeśli włączone tracingowanie) |
| **Messaging** | `messaging.system`, `messaging.destination.name`, `messaging.operation` | Kafka producer/consumer spany |
| **RPC** | `rpc.system`, `rpc.service`, `rpc.method` | gRPC, dubbo |
| **Resource** | `service.name`, `service.version`, `service.namespace`, `host.name`, `os.type` | wszędzie (resource attributes) |

Pełna lista: <https://opentelemetry.io/docs/specs/semconv/>.

**Reguła kciuka**: jeśli stawiasz atrybut o czymś, co istnieje w OTel semconv (HTTP, DB, queue), **użyj nazewnictwa OTel**. Tylko dla domeny biznesowej (rzeczy, których OTel nie standaryzuje) wymyślasz własne (np. `business.operation`, `customer.tier`, `reservation.id`).

### Co robi w naszym kodzie Spring Boot

Spring Boot 3 + Micrometer Tracing automatycznie dodaje:

**Resource attributes (raz na proces)**:
```
service.name      = ${spring.application.name}
service.version   = ${spring.application.version} (jeśli ustawione)
host.name         = nazwa hostname kontenera
process.pid       = PID procesu JVM
```

**Atrybuty HTTP server spana** (auto):
```
http.request.method        = "POST"
http.route                  = "/api/v1/starports/{code}/reservations"
http.response.status_code   = 201
url.scheme                  = "http"
url.path                    = "/api/v1/starports/ALPHA-BASE/reservations"
network.peer.address        = client IP
```

**Atrybuty HTTP client spana** (auto przy używaniu Spring `RestClient`):
```
http.request.method   = "POST"
server.address         = "trade-route-planner"
server.port            = 8082
http.response.status_code = 200
```

To dostajesz **za darmo** — bez ani jednej linii kodu instrumentacji, bo Spring Boot owija Tomcat (server) i RestClient (client) w odpowiednie filtry.

---

## Część IV — Jak nadawać atrybuty w naszym kodzie Java

Cztery sposoby, w kolejności od najczęstszego do najrzadszego.

### Sposób 1 — Observation API (najczęściej używane)

Najpopularniejsze podejście w naszym kodzie. Każde wywołanie `Observation.createNotStarted` można otagować przez `.lowCardinalityKeyValue(key, value)` lub `.highCardinalityKeyValue(key, value)`.

**Przykład — `CreateHoldReservationService.java:34-37`**:
```java
return Observation.createNotStarted(OBSERVATION_NAME, observationRegistry)
        .lowCardinalityKeyValue("starport", command.destinationStarportCode())
        .lowCardinalityKeyValue("shipClass", command.shipClass().name())
        .observe(() -> {
            // ...
        });
```

Co się dzieje:
- W Tempo span ma atrybuty `starport=ALPHA-BASE`, `shipClass=SCOUT`
- W Prometheusie auto-Timer (`reservations_hold_allocate_seconds_*`) ma te same wartości jako Micrometer tags

**Różnica `lowCardinalityKeyValue` vs `highCardinalityKeyValue`**:
- **low** → przeznacza atrybut do bycia **labelem metryki**. Wartości muszą mieć ograniczony zbiór (typowo < 50 unikalnych).
- **high** → atrybut **TYLKO w span**, NIE w metryce. Dla wartości jak `reservationId`, `customerId`, UUID — które potencjalnie mają miliony unikalnych wartości.

Przykład **prawidłowego użycia obu** — `OutboxAppender.java:35-38`:
```java
Observation.createNotStarted("reservations.outbox.append", () -> senderContext, observationRegistry)
        .lowCardinalityKeyValue("binding", reservationsBinding)        // tag w metryce + span attr
        .lowCardinalityKeyValue("eventType", EventType.RESERVATION_CONFIRMED.getEventName())  // tag + attr
        .highCardinalityKeyValue("reservationId", String.valueOf(reservation.getId()))  // tylko span attr
        .observe(() -> { /* save outbox */ });
```

`reservationId` ma typowo miliony unikalnych wartości. Bycie tagiem metryki = eksplozja kardynalności = wywalenie Prometheusa. Bycie tylko span attribute = OK, bo Tempo radzi sobie z wysoko-kardynalnymi atrybutami (są indeksowane po `traceId`, nie po atrybutach).

### Sposób 2 — `@Observed` annotation

Spring Boot 3 + Micrometer wspierają deklaratywny styl. Wymaga registered `ObservedAspect` (tak jak w `telemetry-pipeline/ObservationConfig.java`):
```java
@Bean
ObservedAspect observedAspect(ObservationRegistry registry) {
    return new ObservedAspect(registry);
}
```

Następnie:
```java
@Observed(name = "reservations.fees.calculate", contextualName = "fee-calculation")
public BigDecimal calculateFee(ReserveBayCommand command) {
    // ... ten kod automatycznie owinięty Observation
}
```

W naszym kodzie aktualnie nie używamy `@Observed` (zawsze ręcznie `Observation.createNotStarted`). Powód: Observation API daje większą kontrolę nad atrybutami (warunkowe ustawienie, dynamiczne wartości).

### Sposób 3 — `Span.current()` (niskopoziomowy)

Czasem trzeba dorzucić atrybut do spanów istniejących PO ich utworzeniu. Wtedy:
```java
import io.micrometer.tracing.Span;

Span span = Span.current();
if (span != null) {
    span.tag("custom.attribute", "value");
}
```

Lub przy użyciu OpenTelemetry API bezpośrednio:
```java
import io.opentelemetry.api.trace.Span;

Span.current().setAttribute("custom.attribute", "value");
```

W naszym kodzie **nie używamy** tego stylu — preferujemy Observation API. Wyjątkiem może być sytuacja, gdy chcesz dorzucić atrybut do spanu HTTP server (tworzonego automatycznie), np. po zalogowaniu się usera:
```java
Span.current().setAttribute("user.id", currentUserId);
```

To stawia atrybut na CURRENT span, który w endpoint'cie jest HTTP server spanem.

### Sposób 4 — Resource attributes (raz na proces)

Resource attributes się ustawia **konfiguracyjnie**, nie w kodzie. Trzy metody:

**Metoda A — Spring Boot property** (nasza standardowa):
```yaml
spring:
  application:
    name: starport-registry         # → service.name
```

**Metoda B — Environment variable** (uniwersalna, OTel default):
```bash
OTEL_SERVICE_NAME=starport-registry
OTEL_RESOURCE_ATTRIBUTES=service.version=1.2.0,deployment.environment=prod
```

Spring Boot 3 honoruje te env vars (mają wyższy priorytet niż `application.yml`).

**Metoda C — programatyczna** (rzadko, dla custom resource attrs):
```java
@Bean
SdkTracerProvider tracerProvider() {
    Resource resource = Resource.getDefault().merge(
            Resource.create(Attributes.of(
                    AttributeKey.stringKey("deployment.environment"), "prod"))
    );
    // ...
}
```

W naszym kodzie ograniczamy się do Metody A (`spring.application.name`). Jeżeli kiedyś dorobimy `prod`/`staging`/`dev` rozróżnienie — Metoda B jest najprostsza (env var w docker-compose).

---

## Część V — Atrybuty w MicroservicesFleet — pełen przegląd

### Co ustawiają nasze Observations

| Klasa, linia | Observation name | Atrybuty (`lowCardinality`) | Atrybuty (`highCardinality`) | Span kind (jawnie ustawiony) |
|---|---|---|---|---|
| `CreateHoldReservationService:34` | `reservations.hold.allocate` | `starport`, `shipClass` | — | INTERNAL (default) |
| `FeeCalculatorService:37` | `reservations.fees.calculate` | `starport`, `shipClass` | — | INTERNAL (default) |
| `TradeRoutePlannerHttpAdapter:55` | `reservations.route.plan` | `startStarport`, `destinationStarport` | — | INTERNAL (default) |
| `ConfirmReservationService:32` | `reservations.confirm` | `starport` | — | INTERNAL (default) |
| `OutboxAppender:35` | `reservations.outbox.append` | `binding`, `eventType` | `reservationId` | **PRODUCER** (z `SenderContext`) |
| `InboxPublisher:98` | `reservations.inbox.publish` | `binding`, `eventType` | `outboxId` | **CONSUMER** (z `ReceiverContext`) |
| `PlanRouteService:63` | `routes.plan` | `originPortId`, `destinationPortId`, `shipClass` | — | INTERNAL (default) |
| `EventPipelineConfiguration:reservationPipeline` | `telemetry.pipeline.process` | `pipeline` | — | INTERNAL (default) |
| `EventPipelineConfiguration:routePipeline` | `telemetry.pipeline.process` | `pipeline` | — | INTERNAL (default) |
| `PipelineConfiguration:telemetryPipeline` | `telemetry.pipeline.process` | `pipeline` | — | INTERNAL (default) |
| `ReserveBayValidationService` | `validation.reserve-bay` | (różne, validation specific) | — | INTERNAL (default) |

Dodatkowo:
- HTTP server spany: auto-generowane przez Spring Web, bogate w OTel HTTP attributes (`http.request.method`, `http.route`, `http.response.status_code`)
- HTTP client spany przez RestClient: auto, OTel HTTP client attributes
- (Jeśli włączone JDBC tracing): spany dla każdego SQL z `db.statement`, `db.system="postgresql"`

### Co Tempo `span-metrics` widzi domyślnie

Bez `dimensions` w konfiguracji Tempo `span-metrics`, na dashboardach pojawia się tylko 4-tuple base'owy:
```
traces_spanmetrics_calls_total{
    service="starport-registry",
    span_name="reservations.confirm",
    span_kind="SPAN_KIND_INTERNAL",
    status_code="STATUS_CODE_OK"
}
```

**Atrybuty `starport`, `shipClass`, `binding` itp. są w spanie** (widać w Tempo waterfall), **ale nie są labelami w `traces_spanmetrics_*`** — bo Tempo ich domyślnie nie eksponuje.

### Jak je tam dodać (przyszłość)

Konfiguracja w `tempo.yml`:
```yaml
metrics_generator:
  processor:
    span_metrics:
      dimensions:
        - starport
        - shipClass
        - binding
        - eventType
```

Po tym, jeżeli span ma atrybut `starport`, jego wartość staje się labelem `starport` w `traces_spanmetrics_calls_total`. Jeśli span go nie ma — label jest pusty (`""`).

**Uwaga cardinality**: każdy nowy `dimensions` mnoży liczbę kombinacji. Z 4 base + 4 nowe + 5 starports + 4 shipClass = wzrost serii w Prometheusie. Robić ostrożnie.

---

## Część VI — Low vs High Cardinality — dogłębnie

### Cardinality = liczba unikalnych wartości tego klucza

- `service.name`: 4 (4 serwisy) → **niska**
- `span.kind`: 5 → **niska**
- `status_code`: 3 → **niska**
- `starport`: 5-50 (zależnie od stanu DB) → **niska**
- `shipClass`: 4 (SCOUT, FREIGHTER, CRUISER, UNKNOWN) → **niska**
- `binding`: 3 (mamy 3 Kafka bindingi) → **niska**
- `customerCode`: tysiące, miliony → **wysoka**
- `reservationId`: każda nowa rezerwacja = nowa wartość → **wysoka, monotonicznie rosnąca**
- `traceId`: każdy request = nowy → **wysoka**
- `timestamp`: każda chwila inna → **bardzo wysoka**

### Reguła cardinality

**Atrybut o niskiej kardynalności** (typowo < 50 unikalnych wartości):
- Może być labelem metryki (Prometheus tag)
- Może być atrybutem spana
- Można po nim filtrować w PromQL i TraceQL

**Atrybut o wysokiej kardynalności**:
- **NIE może** być labelem metryki — eksploduje storage
- Może być atrybutem spana (Tempo radzi sobie)
- Można po nim szukać w TraceQL (`{ reservationId = "42" }`) i Tempo Trace Search

W Micrometer Observation API to jest **dosłownie zakodowane** w nazwach metod:
- `.lowCardinalityKeyValue(k, v)` → trafia do **obu**: span attribute + metric tag
- `.highCardinalityKeyValue(k, v)` → trafia **tylko** do span attribute

Dlatego w `OutboxAppender`:
```java
.lowCardinalityKeyValue("binding", reservationsBinding)        // 3 wartości — tag w metryce OK
.lowCardinalityKeyValue("eventType", "ReservationConfirmed")    // 2 wartości — tag w metryce OK
.highCardinalityKeyValue("reservationId", String.valueOf(...))  // miliony — TYLKO w span
```

Jeżeli pomylisz i zrobisz `.lowCardinalityKeyValue("reservationId", ...)`, w Prometheusie powstanie **jedna seria czasowa per reservationId**. Setki tysięcy serii w godzinę. Awaria.

---

## Część VII — Gdzie zobaczysz atrybuty

### W Tempo (waterfall view)

W Grafanie: **Distributed Tracing dashboard → panel 41 (Trace Search) → wybierz trace → klik**.

Po kliknięciu w span widzisz panel po prawej:
```
Span: reservations.confirm
Service: starport-registry
Kind: INTERNAL
Status: OK
Duration: 42 ms

Resource attributes:
  service.name        = starport-registry
  service.version     = 1.0.0-SNAPSHOT
  host.name           = starport-registry-1
  process.pid         = 1

Span attributes:
  starport            = ALPHA-BASE
  
Events:
  (none)
```

Wszystkie atrybuty (resource + span) są widoczne. Dla `OutboxAppender` zobaczysz dodatkowo `reservationId=42`, mimo że to high cardinality (atrybut spana, nie tag metryki).

### W Prometheusie

Tylko **te atrybuty, które stały się tagami metryki** — czyli te z `lowCardinalityKeyValue` (przez Micrometer auto-Timer) lub te w `dimensions` Tempo `span-metrics`.

Przykład query:
```promql
sum by (starport, shipClass) (rate(reservations_hold_allocate_seconds_count[5m]))
```

Po `starport` i `shipClass` można filtrować, bo to są tagi Micrometer Timera (z `lowCardinalityKeyValue`).

```promql
sum by (reservation_id) (rate(reservations_hold_allocate_seconds_count[5m]))
# To NIE zadziała — `reservation_id` nie jest tagiem (ani nie może być, byłaby kardynalność)
```

### W Loki

Tylko `traceId` i `spanId` — przez `logging.pattern.correlation`:
```
[starport-registry,abc123def456...,789...] INFO  Reservation 42 confirmed
                   ^^^^^^^^^^^^^^^^^^      
                   to jest traceId
```

Inne atrybuty spana **nie są w logach** automatycznie. Jeśli chcesz mieć `starport` w logach, musisz to **ręcznie dodać do MDC**:
```java
import org.slf4j.MDC;

MDC.put("starport", command.destinationStarportCode());
try {
    // log linie wewnątrz mają teraz [starport=ALPHA-BASE] w prefiksie
} finally {
    MDC.remove("starport");
}
```

I dorzucić `%X{starport}` do `logging.pattern.correlation`. To uciążliwe — częściej polega się na linkowaniu logów ↔ trace'ów przez `traceId`, a tam już są wszystkie atrybuty.

### W TraceQL queries (Tempo, panel 73)

TraceQL pozwala filtrować po dowolnym atrybucie spana:
```traceql
# Trace'y, gdzie ktokolwiek wywołał operację reservations.confirm:
{ name = "reservations.confirm" }

# Tylko te, gdzie starport=ALPHA-BASE:
{ name = "reservations.confirm" && starport = "ALPHA-BASE" }

# Po wartości high cardinality:
{ reservationId = "42" }

# Połączenie warunków:
{ resource.service.name = "starport-registry" && status = error && duration > 1s }
```

To jest moment, w którym **wysoka kardynalność staje się Twoim przyjacielem**: nie szukasz trendu, szukasz konkretnego incydentu. Tempo umie to zrobić.

---

## Część VIII — Po co się ich używa — 5 konkretnych scenariuszy

### 1. Diagnostyka: „dlaczego ten konkretny request był wolny?"
- Otwierasz panel 61 (Error & Warning Logs) w distributed tracing dashboard
- Klikasz `traceId` w log line
- W Tempo widzisz waterfall — który span trwał najdłużej
- Klikasz na ten span → widzisz atrybuty: `db.statement = SELECT ...`, `db.duration = 5s`
- **Diagnoza w 30 sekund**: slow query

Bez atrybutów byś musiał czytać kod i logi. Z atrybutami widzisz przyczynę natychmiast.

### 2. Agregacja per dimension biznesowy
- „p99 latencji rezerwacji **per starport**" — wymaga atrybutu `starport` w metryce
- Już mamy: dzięki `lowCardinalityKeyValue("starport", ...)` w hold.allocate Observation
- Query: `histogram_quantile(0.99, sum by (le, starport) (rate(reservations_hold_allocate_seconds_bucket[5m])))`
- Widzisz, że ALPHA-BASE jest 3× wolniejszy niż reszta → tam coś nie gra (DB lock? za mało bayów? zła sieć?)

### 3. Service graph — kto wywołuje kogo
- Wymaga `service.name` (resource) + `span.kind` (CLIENT/SERVER) + propagacji `traceId` przez nagłówki
- Tempo `service-graphs` paruje je automatycznie
- Wynik: panel 42 z node graph

Ten use case **całkowicie zależy od atrybutów** — bez `service.name` w resource niczego nie sparujesz.

### 4. Filtrowanie po typie operacji
- Tempo `local-blocks` + TraceQL: `{ kind = client && status = error } | rate() by (name, server.address)`
- Wynik: rate failed HTTP client calls per server, na żywo
- Identyfikuje, które downstream services są problematyczne

### 5. Korelacja error ↔ trace ↔ log
- Loki: pokazuje line z ERROR, w prefiksie `[traceId]`
- Klik traceId → Tempo: pełen trace (wszystkie spany)
- Klik konkretny span → atrybuty + pełen kontekst aplikacyjny
- W spanie atrybut `reservationId = 42` (high cardinality, trzymany tylko w spanie)
- Wracasz do Loki i filtruj `{service="starport-registry"} |= "reservation 42"` — wszystkie logi tej rezerwacji
- **3 minuty od „mam ERROR w logach" do „znam pełny kontekst tego rezerwacji"**

---

## Część IX — Typowe błędy

### 1. Wysokokardynalny atrybut jako label metryki
**Objaw**: po deployu Prometheus zaczyna mieć wysokie obciążenie / OOM.
**Przyczyna**: ktoś dodał `customerId`, `reservationId`, `traceId` jako tag metryki.
**Naprawa**: zamień `lowCardinalityKeyValue` na `highCardinalityKeyValue` (atrybut zostaje w spanie, znika z metryki).

### 2. Brak `service.name`
**Objaw**: w Tempo wszystkie spany mają `service=unknown_service` lub `service=java`.
**Przyczyna**: nie ustawiono `spring.application.name` ani `OTEL_SERVICE_NAME`.
**Naprawa**: ustawić w `application.yml`. **Service graph i `span-metrics` per service nie zadziałają bez tego.**

### 3. Mieszanie atrybutów spana z resource attributes
**Objaw**: ustawiasz `Span.current().setAttribute("service.name", "X")` na konkretnym spanie i myślisz, że to zmieni service name dla całego procesu.
**Przyczyna**: `service.name` to **resource attribute** — global per process, nie per span.
**Naprawa**: ustawić w konfiguracji procesu (Spring property / env var), nie w kodzie.

### 4. Atrybut z `null` value
**Objaw**: span pojawia się bez atrybutu, mimo że kod go ustawia.
**Przyczyna**: OTel ignoruje `null` values — `lowCardinalityKeyValue("foo", null)` nic nie ustawia.
**Naprawa**: jawnie sprawdzić null + ustawić `"unknown"` lub pomijać.

```java
String starport = command.destinationStarportCode();
Observation.createNotStarted("...", registry)
        .lowCardinalityKeyValue("starport", starport != null ? starport : "unknown")
        // ...
```

### 5. Naming clash z OTel semconv
**Objaw**: dorzuciłeś `http.method` jako custom attribute, ale on koliduje z auto-instrumentation.
**Przyczyna**: niektóre nazwy są zajęte przez OpenTelemetry semconv.
**Naprawa**: używać prefiksów custom (`business.`, `app.`, `mycompany.`) dla domeny biznesowej. Standard nazewniczy: `<obszar>.<kategoria>.<pole>`.

```java
.lowCardinalityKeyValue("business.operation", "reservation.create")     // OK
.lowCardinalityKeyValue("operation", "reservation.create")              // ryzyko clash
```

### 6. Zbyt długie nazwy atrybutów
**Objaw**: w niektórych backendach niektóre limity są nakładane na długość kluczy/wartości (Tempo: 250 znaków key, 64 KB value).
**Naprawa**: trzymać klucze < 100 znaków, wartości < 1 KB. Long string values to anti-pattern (większe payloady = wolniejsze trace exporty).

### 7. Zapominanie o `peer.service` przy CLIENT spanie
**Objaw**: service graph nie zawiera tej krawędzi.
**Przyczyna**: `service-graphs` procesor potrzebuje `peer.service` lub `server.address` żeby wiedzieć, kogo wołasz.
**Naprawa**: jawnie ustawić w `SenderContext` (jak robi `OutboxAppender`):
```java
SenderContext<...> ctx = new SenderContext<>(..., Kind.PRODUCER);
ctx.setRemoteServiceName("kafka");      // ← to staje się peer.service
```

---

## Część X — Słownik

| Termin | Wyjaśnienie |
|---|---|
| **Span** | Atomowy zapis "operacja Y w czasie T1..T2". Identyfikowany przez `(traceId, spanId)`. |
| **Trace** | Drzewo spanów połączonych po `parentSpanId`. Pełna ścieżka requestu. |
| **Resource attribute** | Atrybut PROCESU. Stały dla wszystkich spanów emitowanych przez ten proces. Przykład: `service.name`. |
| **Span attribute** | Atrybut konkretnego spana. Może się różnić między spanami w obrębie tego samego procesu. Przykład: `http.route`. |
| **Span name** | Top-level nazwa operacji. Nie jest atrybutem, ale często traktowane razem z nimi. |
| **Span kind** | Top-level enum: SERVER/CLIENT/PRODUCER/CONSUMER/INTERNAL. Krytyczne dla service-graphs i span-metrics. |
| **Status code** | Top-level status: UNSET/OK/ERROR. |
| **Cardinality** | Liczba unikalnych wartości tego klucza w czasie. Kluczowa dla decyzji „czy może być labelem metryki". |
| **Low cardinality attribute** | < ~50 unikalnych wartości. Może być labelem metryki + span attribute. |
| **High cardinality attribute** | Tysiące lub więcej. Tylko span attribute, NIGDY label metryki. |
| **Semantic Conventions (semconv)** | Standardowe nazwy atrybutów OpenTelemetry dla typowych operacji (HTTP, DB, queue). |
| **OTLP** | OpenTelemetry Line Protocol — format wysyłki spanów do backendu. |
| **MDC** | Mapped Diagnostic Context — slf4j feature do automatycznego wstrzykiwania kluczy do log linii. |
| **`peer.service`** | Atrybut na CLIENT/PRODUCER spanie identyfikujący usługę zdalną. Wymagany dla service graph. |
| **`service.name`** | Resource attribute. Globalna nazwa serwisu. Z `spring.application.name`. |
| **Observation API** | Spring Boot 3 / Micrometer wzorzec — jeden obiekt = i metryka, i span. |
| **`@Observed`** | Adnotacja Micrometer — deklaratywne owinięcie metody w Observation. |
| **`Span.current()`** | API niskopoziomowe do dorzucenia atrybutu do bieżącego spana. |
| **`dimensions` w span-metrics** | Lista atrybutów, które Tempo dorzuca jako labele Prometheusa. |

---

## Część XI — Quick reference: jak nazwać i ustawić atrybut

### Decyzja krok po kroku

1. **Czy to coś standardowego (HTTP, DB, queue)?**
   → Użyj OTel semconv. Sprawdź <https://opentelemetry.io/docs/specs/semconv/>.

2. **Czy to per proces (raz na życie)?**
   → Resource attribute. Ustaw w `application.yml` lub `OTEL_RESOURCE_ATTRIBUTES` env.

3. **Czy to per span (zmienne)?**
   → Span attribute. Użyj Observation API.

4. **Ile unikalnych wartości to ma?**
   → < 50 → `lowCardinalityKeyValue` (działa jako tag metryki + span attribute)
   → ≥ 50, ale skończone → rozważyć grouping (`region` zamiast `instance`)
   → tysiące lub więcej → `highCardinalityKeyValue` (tylko span attribute)

5. **Konwencja nazewnicza**:
   - Stałe atrybuty domeny → `business.<area>.<field>` (np. `business.reservation.starport`)
   - Atrybuty API → `<system>.<thing>` (np. `kafka.topic`, `db.table`)
   - Custom dla MicroservicesFleet → bez prefiksu OK, ale z konwencją snake_case lub kebab-case (my używamy camelCase: `starport`, `shipClass`)

### Kod boilerplate

```java
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

// 1. INTERNAL Observation z dwoma low-cardinality atrybutami:
Observation.createNotStarted("my.business.operation", observationRegistry)
        .lowCardinalityKeyValue("starport", starportCode)
        .lowCardinalityKeyValue("shipClass", shipClassName)
        .observe(() -> {
            // kod operacji
        });

// 2. PRODUCER Observation (Kafka):
SenderContext<Map<String, Object>> ctx = new SenderContext<>(Map::put, Kind.PRODUCER);
ctx.setRemoteServiceName("kafka");
ctx.setCarrier(headersMap);
Observation.createNotStarted("kafka.send", () -> ctx, observationRegistry)
        .lowCardinalityKeyValue("topic", topicName)
        .lowCardinalityKeyValue("partition", String.valueOf(partition))
        .highCardinalityKeyValue("messageId", messageId)
        .observe(() -> {
            kafkaTemplate.send(topicName, message);
        });

// 3. CONSUMER Observation (Kafka):
ReceiverContext<Map<String, String>> rctx = new ReceiverContext<>(Map::get, Kind.CONSUMER);
rctx.setCarrier(messageHeaders);
rctx.setRemoteServiceName("kafka");
Observation.createNotStarted("kafka.receive", () -> rctx, observationRegistry)
        .lowCardinalityKeyValue("topic", topic)
        .observe(() -> {
            processMessage(message);
        });
```

---

## TL;DR

**Atrybut spana** to para klucz-wartość, którą dorzucasz do spana, żeby:
- móc po nim **filtrować i agregować** w narzędziach (Tempo, Prometheus, Grafana)
- mieć **kontekst diagnostyczny** podczas debugowania konkretnego incidentu

W naszym setupie atrybuty są w trzech warstwach:
1. **Resource attributes** (np. `service.name`) — z konfiguracji procesu, niezmienne w czasie
2. **Span intrinsic** (`name`, `kind`, `status`) — top-level pola spana, ustawiane przy tworzeniu
3. **Span attributes** (np. `starport`, `shipClass`) — dorzucane przez Observation API

Tempo `span-metrics` procesor używa **czterech base'owych** (`service.name`, `span.name`, `span.kind`, `status_code`) do zbudowania metryk Prometheus. Inne atrybuty są w spanach, ale nie w metrykach — chyba że dodasz je jawnie przez `metrics_generator.processor.span_metrics.dimensions`.

W kodzie Java preferuj **Observation API** z `lowCardinalityKeyValue` (do < 50 unikalnych wartości — może być label metryki) i `highCardinalityKeyValue` (do nieograniczonych — tylko span attribute, NIE label metryki).

Konwencja nazewnicza: dla standardowych rzeczy (HTTP/DB/messaging) używaj OTel semconv. Dla domeny biznesowej własne nazwy z prefiksem (`business.*` jeśli chcesz być formalny, lub po prostu niezagnieżdżone klucze jak nasze `starport`, `shipClass`).

---

*Dokument utrzymywany ręcznie. Po każdej istotnej zmianie atrybutów w kodzie warto go zaktualizować.*
