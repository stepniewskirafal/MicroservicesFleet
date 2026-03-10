# Jak `telemetry-pipeline` komunikuje się z innymi modułami?

## Schemat przepływu danych

```
[Producent zewnętrzny]
        │
        │  JSON → topic: telemetry.raw
        ▼
  ┌─────────────────────────────────┐
  │       KAFKA BROKER (9092/9093)  │
  └────────────────┬────────────────┘
                   │ konsumuje (group: telemetry-pipeline)
                   ▼
  ┌─────────────────────────────────────────────────────┐
  │              telemetry-pipeline                      │
  │  RawTelemetry                                        │
  │     → ValidationFilter  (odrzuca NULL, złe typy)    │
  │     → EnrichmentFilter  (dodaje shipClass, sector,  │
  │                          progi z SensorThresholds)   │
  │     → AggregationFilter (rolling avg, stdDev, max)  │
  │     → AnomalyDetectionFilter (CRITICAL / WARNING)   │
  │                                                      │
  │  Wynik: AnomalyAlert (lub null → brak publikacji)   │
  └────────────────┬────────────────────────────────────┘
                   │ publikuje (jeśli anomalia)
                   │  JSON → topic: telemetry.alerts
                   ▼
  ┌─────────────────────────────────┐
  │       KAFKA BROKER              │
  └─────────────────────────────────┘
        │
        ▼
  [Konsumenci downstream: starport-registry, trade-route-planner, ...]
```

---

## 1. Komunikacja asynchroniczna — Kafka (główna)

| Kierunek | Topic Kafki | Format | Opis |
|---|---|---|---|
| **Wejście** | `telemetry.raw` | JSON (`RawTelemetry`) | Surowe odczyty sensorów statków |
| **Wyjście** | `telemetry.alerts` | JSON (`AnomalyAlert`) | Alerty anomalii (CRITICAL / WARNING) |

- Mechanizm: **Spring Cloud Stream** z **Kafka binder**
- Bean funkcyjny: `Function<RawTelemetry, AnomalyAlert> telemetryPipeline`
- Spring Cloud Stream automatycznie binduje funkcję do obu topiców
- Retry: 3 próby, backoff 1 s (`max-attempts: 3`, `back-off-initial-interval: 1000`)
- `null` zwrócony z funkcji = **żadna wiadomość nie jest publikowana** (filtracja)

---

## 2. Service Discovery — Eureka

- Serwis **rejestruje się** w Eureka Server (`http://eureka:8761/eureka`)
- Dzięki temu inne serwisy mogą go znaleźć po nazwie `telemetry-pipeline`
- Heartbeat co 10 s, wygaśnięcie po 30 s
- Przy skalowaniu uruchamiane są dwie instancje: `telemetry-pipeline-1:8090`, `telemetry-pipeline-2:8091` — obie w tej samej grupie konsumentów Kafki (load balancing na poziomie partycji)

---

## 3. Komunikacja z `starport-registry` — AKTUALNIE statyczna (TODO)

W `EnrichmentFilter` widnieje hardkodowany rejestr statków:

```java
// Static ship registry — in production this would come from Starport Registry via HTTP or cache
private static final Map<String, ShipInfo> SHIP_REGISTRY = Map.of(
    "SHIP-001", new ShipInfo("Corvette", "Alpha-Centauri"),
    ...
);
```

> ⚠️ Komentarz w kodzie wprost mówi, że w produkcji dane o statkach powinny być pobierane z **`starport-registry` przez HTTP lub cache**. Teraz to dummy dane.

---

## 4. Obserwowalność — stos monitorowania

| Komponent | Protokół | Co wysyła |
|---|---|---|
| **Prometheus** | HTTP scrape (`/actuator/prometheus`) | Metryki: `telemetry.messages.received`, `telemetry.messages.invalid`, `telemetry.anomalies.detected{severity=WARNING/CRITICAL}` |
| **Tempo / Zipkin** | OTLP HTTP (`http://tempo:4318/v1/traces`) | Ślady rozproszone (distributed tracing) |
| **Loki / Promtail** | Log scraping z Dockera | Logi aplikacji |
| **Grafana** | UI | Wizualizacja metryk, logów i śladów |

---

## Podsumowanie

```
telemetry-pipeline NIE rozmawia przez HTTP z innymi serwisami biznesowymi.
Cała komunikacja domenowa odbywa się przez Kafkę (event-driven).
```

- **Kafka** → wejście/wyjście danych domenowych (asynchronicznie)
- **Eureka** → rejestracja/discovery (synchronicznie przy starcie)
- **Prometheus/Tempo/Loki** → metryki, tracing, logi (obserwowalność)
- **`starport-registry`** → planowane HTTP/cache, teraz statyczny mock



# Skąd wiadomo gdzie Kafka wysyła eventy i w jakiej kolejności filtry działają?

---

## CZĘŚĆ 1 — Skąd wiadomo, który topic Kafki jest wejściem?

### Krok 1: `application.yml` — deklaracja funkcji i bindings

```yaml
spring:
  cloud:
    function:
      definition: telemetryPipeline   # ← (1) mówi Spring Cloud Stream: "zarejestruj tę funkcję"

    stream:
      bindings:
        telemetryPipeline-in-0:       # ← (2) NAMING CONVENTION: {nazwaFunkcji}-in-{index}
          destination: telemetry.raw  # ← (3) to jest topic Kafki, który jest CZYTANY
          group: telemetry-pipeline   # ← (4) consumer group
          content-type: application/json

        telemetryPipeline-out-0:      # ← {nazwaFunkcji}-out-{index}
          destination: telemetry.alerts  # ← topic, do którego ZAPISYWANY jest wynik
          content-type: application/json
```

### Klucz: konwencja nazewnicza Spring Cloud Stream

```
{nazwaBeana}-in-{index}   → WEJŚCIE (subscribe na topic Kafki)
{nazwaBeana}-out-{index}  → WYJŚCIE (publish na topic Kafki)
```

**Bean `telemetryPipeline`** jest zdefiniowany w `PipelineConfiguration.java`:
```java
@Bean
public Function<RawTelemetry, AnomalyAlert> telemetryPipeline(...) { ... }
//              ↑                ↑
//        typ wejścia       typ wyjścia
```

Spring Cloud Stream widzi:
- `telemetryPipeline-in-0` → czyta z `telemetry.raw`, deserializuje JSON → `RawTelemetry`
- `telemetryPipeline-out-0` → serializuje `AnomalyAlert` do JSON → pisze do `telemetry.alerts`

---

## CZĘŚĆ 2 — W jakiej kolejności wywoływane są filtry?

Kolejność jest **jawna i twarda** — zakodowana bezpośrednio w lambdzie w `PipelineConfiguration.java`:

```java
@Bean
public Function<RawTelemetry, AnomalyAlert> telemetryPipeline(
        ValidationFilter validationFilter,
        EnrichmentFilter enrichmentFilter,
        AggregationFilter aggregationFilter,
        AnomalyDetectionFilter anomalyDetectionFilter) {

    return raw -> {
        // KROK 1 ──────────────────────────────────────────────
        var validated = validationFilter.apply(raw);
        if (validated == null) {
            return null;   // ← STOP: wiadomość jest porzucana, nic nie trafia na Kafkę
        }

        // KROK 2 ──────────────────────────────────────────────
        var enriched = enrichmentFilter.apply(validated);

        // KROK 3 ──────────────────────────────────────────────
        var aggregated = aggregationFilter.apply(enriched);

        // KROK 4 ──────────────────────────────────────────────
        return anomalyDetectionFilter.apply(aggregated);
        // ↑ jeśli null (brak anomalii) → Spring Cloud Stream NIE publikuje nic
    };
}
```

### Wizualnie:

```
[Kafka: telemetry.raw]
         │
         │  JSON deserializacja → RawTelemetry
         ▼
┌─────────────────────┐
│  ValidationFilter   │  ← sprawdza: null? brak shipId? zły sensorType? NaN?
└─────────┬───────────┘
          │  null → PORZUĆ (żadnego publish na Kafkę)
          │  ok   → ValidatedTelemetry
          ▼
┌─────────────────────┐
│  EnrichmentFilter   │  ← dodaje: shipClass, currentSector, progi (lower/upper threshold)
└─────────┬───────────┘
          │  EnrichedTelemetry
          ▼
┌─────────────────────┐
│  AggregationFilter  │  ← oblicza: rollingAvg, rollingStdDev, max (okno 5 min, in-memory)
└─────────┬───────────┘
          │  AggregatedTelemetry
          ▼
┌──────────────────────────┐
│  AnomalyDetectionFilter  │  ← wykrywa: CRITICAL (przekroczone progi) / WARNING (3σ)
└─────────┬────────────────┘
          │  null → brak anomalii, nic nie trafia na Kafkę
          │  AnomalyAlert → serializacja do JSON
          ▼
[Kafka: telemetry.alerts]
```

---

## CZĘŚĆ 3 — Typy danych między filtrami (kontrakt)

```
RawTelemetry         → { shipId, sensorType(String), value, timestamp, metadata }
     ↓ ValidationFilter
ValidatedTelemetry   → { shipId, sensorType(SensorType enum), value, timestamp, metadata }
     ↓ EnrichmentFilter
EnrichedTelemetry    → { + shipClass, currentSector, lowerThreshold, upperThreshold }
     ↓ AggregationFilter
AggregatedTelemetry  → { + rollingAvg, rollingStdDev, max, windowSampleCount }
     ↓ AnomalyDetectionFilter
AnomalyAlert         → { shipId, sensorType, severity, description, currentValue,
                          threshold, rollingAvg, shipClass, currentSector, detectedAt }
```

---

## Skrótowe podsumowanie

| Pytanie | Odpowiedź |
|---|---|
| Skąd Spring wie, który topic czytać? | Naming convention: `telemetryPipeline-in-0` → `destination: telemetry.raw` w YML |
| Co decyduje o kolejności filtrów? | Jawna sekwencja `apply()` w lambdzie w `PipelineConfiguration.java` |
| Co się dzieje przy złej wiadomości? | `ValidationFilter` zwraca `null` → funkcja zwraca `null` → Spring Cloud Stream nie publikuje nic |
| Co się dzieje gdy nie ma anomalii? | `AnomalyDetectionFilter` zwraca `null` → j.w., brak publish |


# Czy bean `telemetryPipeline` jest kluczowy? Czy można to zrobić inaczej?

---

## Odpowiedź krótka

**Tak — bean jest kluczowy**, ale nie musi być beanen `@Bean` w klasie `@Configuration`.
Spring Cloud Stream akceptuje **kilka różnych sposobów** rejestracji funkcji.

---

## Sposób 1 (obecny) — `@Bean` w `@Configuration`

```java
@Configuration
public class PipelineConfiguration {

    @Bean
    public Function<RawTelemetry, AnomalyAlert> telemetryPipeline(...) {
        return raw -> {
            var validated = validationFilter.apply(raw);
            if (validated == null) return null;
            var enriched   = enrichmentFilter.apply(validated);
            var aggregated = aggregationFilter.apply(enriched);
            return anomalyDetectionFilter.apply(aggregated);
        };
    }
}
```

✅ **Zalety**: pełna kontrola nad kompozycją, łatwe testowanie jednostkowe lambdy, jawna kolejność.
❌ **Wady**: wszystko w jednym miejscu — przy wielu pipeline'ach robi się tłoczno.

---

## Sposób 2 — `@Component` implementujący `Function<I, O>`

```java
@Component("telemetryPipeline")   // ← nazwa beana = klucz do bindingu w YML
public class TelemetryPipelineFunction
        implements Function<RawTelemetry, AnomalyAlert> {

    private final ValidationFilter      validationFilter;
    private final EnrichmentFilter      enrichmentFilter;
    private final AggregationFilter     aggregationFilter;
    private final AnomalyDetectionFilter anomalyDetectionFilter;

    // konstruktor z @Autowired / wstrzyknięcie przez konstruktor

    @Override
    public AnomalyAlert apply(RawTelemetry raw) {
        var validated = validationFilter.apply(raw);
        if (validated == null) return null;
        var enriched   = enrichmentFilter.apply(validated);
        var aggregated = aggregationFilter.apply(enriched);
        return anomalyDetectionFilter.apply(aggregated);
    }
}
```

✅ **Zalety**: bardziej obiektowy, OCP — każda klasa ma jedną odpowiedzialność.
⚠️ **Uwaga**: nazwa `@Component("telemetryPipeline")` musi pasować do `definition: telemetryPipeline` w YML.

---

## Sposób 3 — Kompozycja przez `andThen()` (funkcyjny styl)

Spring Cloud Stream wspiera **łańcuchowanie funkcji** przez `definition`:

```yaml
spring:
  cloud:
    function:
      definition: validate|enrich|aggregate|detectAnomalies
    stream:
      bindings:
        validate-in-0:
          destination: telemetry.raw
        detectAnomalies-out-0:
          destination: telemetry.alerts
```

```java
@Bean public Function<RawTelemetry, ValidatedTelemetry>   validate()        { ... }
@Bean public Function<ValidatedTelemetry, EnrichedTelemetry> enrich()        { ... }
@Bean public Function<EnrichedTelemetry, AggregatedTelemetry> aggregate()    { ... }
@Bean public Function<AggregatedTelemetry, AnomalyAlert>  detectAnomalies() { ... }
```

Operator `|` (pipe) mówi Spring Cloud Stream: **skomponuj te funkcje w pipeline**.
To jest odpowiednik `validate.andThen(enrich).andThen(aggregate).andThen(detectAnomalies)`.

✅ **Zalety**: każda funkcja to osobny, testowalny bean. Czysto funkcyjny styl.
❌ **Wady**: trudniej obsłużyć `null` (short-circuit) pomiędzy krokami — trzeba używać `Optional` lub osobnych mechanizmów filtrowania.

---

## Sposób 4 — `StreamListener` (stary, przestarzały)

```java
// ❌ DEPRECATED od Spring Cloud Stream 3.x — NIE używaj w nowych projektach
@StreamListener("telemetry.raw")
@SendTo("telemetry.alerts")
public AnomalyAlert handle(RawTelemetry raw) { ... }
```

---

## Porównanie wszystkich sposobów

| Sposób | Rejestracja beana | Elastyczność null | Testowalność | Styl |
|---|---|---|---|---|
| `@Bean` lambda (obecny) | `@Configuration` | ✅ pełna | ✅ łatwa | funkcyjny |
| `@Component` implements `Function` | `@Component` | ✅ pełna | ✅ łatwa | obiektowy |
| Pipe `validate\|enrich\|...` w YML | `@Bean` per krok | ⚠️ trudna | ✅ b. łatwa | czysto funkcyjny |
| `@StreamListener` | adnotacja | ✅ pełna | ⚠️ trudna | ❌ deprecated |

---

## Co jest NAPRAWDĘ kluczowe?

Nie sam `@Bean` — kluczowe są **trzy rzeczy razem**:

```
1. Nazwa beana        →  musi pasować do klucza w application.yml
                          (definition: telemetryPipeline)

2. Typ generyczny     →  Function<RawTelemetry, AnomalyAlert>
                          Spring Cloud Stream używa typów do deserializacji/serializacji JSON

3. Binding w YML      →  telemetryPipeline-in-0 / telemetryPipeline-out-0
                          łączy funkcję z fizycznym topicem Kafki
```

Bez któregokolwiek z tych trzech — binding nie zadziała.

---

## Skrót decyzyjny

```
Masz jeden pipeline z null-check między krokami?
  → Sposób 1 lub 2 (obecny w projekcie) ✅

Chcesz każdy krok testować zupełnie niezależnie?
  → Sposób 3 (pipe w YML) ✅

Piszesz nowy kod na starym projekcie Spring Cloud Stream 2.x?
  → Sposób 2 (@Component) ✅

Widzisz @StreamListener w kodzie?
  → Zrefaktoruj do Function<I,O> ⚠️
```


# Jak Spring Cloud Stream łączy YML z beanem Java?

---

## Punkt wyjścia — dwa pliki muszą "rozmawiać" tym samym imieniem

```yaml
# application.yml
spring:
  cloud:
    function:
      definition: telemetryPipeline   # ← STRING: nazwa beana
    stream:
      bindings:
        telemetryPipeline-in-0:       # ← STRING: {ta sama nazwa}-in-0
          destination: telemetry.raw
        telemetryPipeline-out-0:      # ← STRING: {ta sama nazwa}-out-0
          destination: telemetry.alerts
```

```java
// PipelineConfiguration.java
@Bean
public Function<RawTelemetry, AnomalyAlert> telemetryPipeline(...) { ... }
//                                          ↑
//                             nazwa metody = nazwa beana w kontekście Springa
```

**Klucz: `@Bean` bez podanej nazwy = nazwa metody jest nazwą beana.**
Spring rejestruje ten bean pod kluczem `"telemetryPipeline"` w `ApplicationContext`.

---

## Krok po kroku — co się dzieje przy starcie

### KROK 1: Spring Boot skanuje kontekst i rejestruje beany

```
ApplicationContext rejestruje:
  "validationFilter"       → ValidationFilter
  "enrichmentFilter"       → EnrichmentFilter
  "aggregationFilter"      → AggregationFilter
  "anomalyDetectionFilter" → AnomalyDetectionFilter
  "telemetryPipeline"      → Function<RawTelemetry, AnomalyAlert>  ← lambda
```

Zależności są wstrzykiwane przez konstruktor beana:

```java
@Bean
public Function<RawTelemetry, AnomalyAlert> telemetryPipeline(
        ValidationFilter validationFilter,      // ← Spring wstrzykuje bean "validationFilter"
        EnrichmentFilter enrichmentFilter,      // ← Spring wstrzykuje bean "enrichmentFilter"
        AggregationFilter aggregationFilter,    // ← Spring wstrzykuje bean "aggregationFilter"
        AnomalyDetectionFilter anomalyDetectionFilter) { // ← j.w.
    ...
}
```

**Kolejność tworzenia beanów:**
Spring widzi że `telemetryPipeline` ZALEŻY od 4 filtrów → tworzy je NAJPIERW,
dopiero potem tworzy `telemetryPipeline`. To jest standardowy DI.

---

### KROK 2: `FunctionCatalog` skanuje beany i szuka Function/Consumer/Supplier

`FunctionCatalog` (spring-cloud-function) przegląda wszystkie beany w kontekście
i zbiera te, których typ to `Function`, `Consumer` lub `Supplier`:

```
Znalazł:
  "validationFilter"       → Function<RawTelemetry, ValidatedTelemetry>
  "enrichmentFilter"       → Function<ValidatedTelemetry, EnrichedTelemetry>
  "aggregationFilter"      → Function<EnrichedTelemetry, AggregatedTelemetry>
  "anomalyDetectionFilter" → Function<AggregatedTelemetry, AnomalyAlert>
  "telemetryPipeline"      → Function<RawTelemetry, AnomalyAlert>
```

---

### KROK 3: `definition: telemetryPipeline` — FunctionCatalog szuka po NAZWIE

```yaml
spring:
  cloud:
    function:
      definition: telemetryPipeline
```

`FunctionCatalog.lookup("telemetryPipeline")` robi dosłownie:

```java
// uproszczony pseudokod z FunctionCatalog
Object bean = applicationContext.getBean("telemetryPipeline");
//                                        ↑
//                          szuka po STRINGU z definition
```

Znalazł bean → zapisuje go jako "aktywną funkcję" dla pipeline'u.

---

### KROK 4: Parsowanie kluczy bindingów — konwencja `{nazwa}-in-{index}`

`BindingServiceProperties` parsuje klucze z YML:

```
"telemetryPipeline-in-0"
 └── split("-in-")  → ["telemetryPipeline", "0"]
     funkcja: "telemetryPipeline"
     kierunek: INPUT
     index: 0
     destination: "telemetry.raw"

"telemetryPipeline-out-0"
 └── split("-out-") → ["telemetryPipeline", "0"]
     funkcja: "telemetryPipeline"
     kierunek: OUTPUT
     index: 0
     destination: "telemetry.alerts"
```

Spring weryfikuje: czy funkcja `"telemetryPipeline"` istnieje w `FunctionCatalog`?
→ TAK → binding jest prawidłowy.

---

### KROK 5: KafkaMessageChannelBinder tworzy fizyczne kanały

```
INPUT binding:
  KafkaConsumer(
    topic         = "telemetry.raw",          ← z destination
    consumerGroup = "telemetry-pipeline",     ← z group
    maxAttempts   = 3,                        ← z consumer.max-attempts
    backoff       = 1000ms
  )
  → podłącza się do wewnętrznego MessageChannel "telemetryPipeline-in-0"

OUTPUT binding:
  KafkaProducer(
    topic = "telemetry.alerts"               ← z destination
  )
  → podłącza się do wewnętrznego MessageChannel "telemetryPipeline-out-0"
```

---

### KROK 6: MessageConverter — JSON ↔ Java (skąd zna typy?)

```java
// Spring robi przez refleksję na beanie "telemetryPipeline":
Method beanMethod = PipelineConfiguration.class
                        .getMethod("telemetryPipeline", ...);

Type returnType = beanMethod.getGenericReturnType();
// → ParameterizedType: Function<RawTelemetry, AnomalyAlert>

ResolvableType resolvable = ResolvableType.forType(returnType);
Class<?> inputType  = resolvable.getGeneric(0).resolve(); // → RawTelemetry.class
Class<?> outputType = resolvable.getGeneric(1).resolve(); // → AnomalyAlert.class
```

Jackson dostaje `RawTelemetry.class` i wie jak zdeserializować JSON:

```
Kafka bajty:
{"shipId":"SHIP-001","sensorType":"TEMPERATURE","value":42.5,"timestamp":"..."}
                    ↓  Jackson.readValue(bytes, RawTelemetry.class)
RawTelemetry { shipId="SHIP-001", sensorType="TEMPERATURE", value=42.5, ... }
                    ↓  telemetryPipeline.apply(raw)
AnomalyAlert { shipId="SHIP-001", severity=CRITICAL, ... }
                    ↓  Jackson.writeValueAsBytes(anomalyAlert)
Kafka bajty: {"shipId":"SHIP-001","severity":"CRITICAL",...}
```

---

## Cały łańcuch w jednym widoku

```
application.yml                           Java / Spring
─────────────────────────────────────────────────────────────────────
definition: telemetryPipeline    →  applicationContext.getBean("telemetryPipeline")
                                     = @Bean metoda o nazwie telemetryPipeline()

telemetryPipeline-in-0           →  FunctionCatalog: funkcja="telemetryPipeline"
  destination: telemetry.raw         kierunek=INPUT, index=0
  group: telemetry-pipeline      →  KafkaConsumer(topic="telemetry.raw",
  content-type: application/json      group="telemetry-pipeline")
                                  →  Jackson deserializuje do RawTelemetry.class
                                     (typ z Method.getGenericReturnType() generics[0])

telemetryPipeline-out-0          →  FunctionCatalog: funkcja="telemetryPipeline"
  destination: telemetry.alerts      kierunek=OUTPUT, index=0
  content-type: application/json →  KafkaProducer(topic="telemetry.alerts")
                                  →  Jackson serializuje AnomalyAlert do JSON
                                     (typ z Method.getGenericReturnType() generics[1])
```

---

## Dlaczego bean `telemetryPipeline` jest tworzony jako ostatni?

Spring automatycznie wykrywa graf zależności przez parametry metody `@Bean`:

```java
@Bean
public Function<RawTelemetry, AnomalyAlert> telemetryPipeline(
        ValidationFilter validationFilter,       // ← zależy od tego beana
        EnrichmentFilter enrichmentFilter,       // ← zależy od tego beana
        AggregationFilter aggregationFilter,     // ← zależy od tego beana
        AnomalyDetectionFilter anomalyDetectionFilter) { // ← zależy od tego beana
```

Spring buduje DAG (directed acyclic graph) zależności:

```
telemetryPipeline
    ├── validationFilter       (tworzy jako 1.)
    ├── enrichmentFilter       (tworzy jako 2.)
    ├── aggregationFilter      (tworzy jako 3.)
    └── anomalyDetectionFilter (tworzy jako 4.)
                               (telemetryPipeline tworzy jako 5.)
```

Nie trzeba tego nigdzie deklarować ręcznie — Spring sam to rozgryza.
