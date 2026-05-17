# ADR-0033 — Exemplars: Metrics → Traces korelacja (Prometheus → Tempo)

**Status:** Accepted  
**Data:** 2026-05-17  
**Powiązane ADR:** ADR-0005 (observability stack), ADR-0017 (tracing propagation), ADR-0030 (metrics naming)

---

## Kontekst

W systemie rozproszonym obserwując spike p99 latencji na wykresie wiemy **co się dzieje** (wartość metryki), ale nie wiemy **dlaczego** (który request, jakie zasoby, która ścieżka kodu). Przejście od metryki do konkretnego trace'a wymagało wcześniej manualnego, czasochłonnego przeszukiwania Tempo po przedziale czasowym i serwisie.

Exemplary rozwiązują ten problem dostarczając **bezpośredni, klikalny link** od punktu na wykresie metryki wprost do trace'a w Tempo — bez żadnego ręcznego szukania.

---

## Czym jest exemplar — precyzyjna definicja

**Exemplar** (OpenMetrics RFC) to dodatkowy, opcjonalny payload dołączany do próbki metryki w formacie Prometheus Exposition Format 2.0:

```
http_server_requests_seconds_bucket{le="0.5",method="POST",uri="/reservations"} 1027 # {trace_id="4bf92f3577b34da6a3ce929d0e0e4736"} 0.462 1716123456.789
```

Składowe:
| Pole | Wartość w przykładzie | Opis |
|---|---|---|
| `# {trace_id="..."}` | `4bf92f3577b34da6a3ce929d0e0e4736` | W3C TraceID requestu-próbki |
| `0.462` | 0.462s | Faktyczna zmierzona wartość (nie górna granica bucketu) |
| `1716123456.789` | Unix timestamp | Kiedy ten request nastąpił |

---

## Algorytm wyboru exemplara — odpowiedź na kluczowe pytanie

> **Czy exemplar to najgorszy (najwolniejszy) request? Czy ostatni w "paczce"?**

**Ani jedno, ani drugie.**

### Implementacja w `micrometer-registry-prometheus`

Micrometer używa `HistogramExemplarSampler` z biblioteki `prometheus-metrics-core`. Algorytm (last-write-wins z time-gating):

```
Każdy histogram bucket ma slot: ExemplarSlot { traceId, value, timestamp }

Przy każdym Timer.record(duration):
  bucket = histogram.findBucket(duration)       // do którego le= wpada?
  existing = bucket.exemplar
  
  if (existing == null
      || now - existing.timestamp > minRetentionPeriod):   // ← kluczowy warunek
    bucket.exemplar = Exemplar(currentTraceId, duration, now)
  // else: zachowaj stary exemplar
```

**`minRetentionPeriod`** = `7 × scrape_interval`  
W tym projekcie: `scrape_interval: 3s` → **retencja = 21 sekund**

### Co to oznacza w praktyce?

| Pytanie | Odpowiedź |
|---|---|
| Czy to najwolniejszy request? | ❌ Nie — brak jakiejkolwiek logiki "wybierz max" |
| Czy to ostatni request jaki przyszedł? | ❌ Nie — time-gating blokuje nadpisanie przez 21s |
| Czy to losowy request? | ✅ W przybliżeniu tak — ostatni który trafił w bucket po upłynięciu minRetentionPeriod |
| Czy to reprezentatywna próbka? | ✅ Tak — jeśli widzisz exemplar przy 1.8s, ten konkretny request trwał ~1.8s |

### Wizualizacja bucketu

```
Timeline (scrape_interval = 3s, retention = 21s):

t=0s   Request A (0.48s) → trafił w bucket le=0.5s → ZAPISANY jako exemplar
t=3s   Request B (0.49s) → ten sam bucket → stary ma 3s < 21s → ODRZUCONY
t=6s   Request C (0.47s) → stary ma 6s < 21s → ODRZUCONY
...
t=21s  Request D (0.43s) → stary ma 21s ≥ 21s → ZAPISANY (nadpisuje A)
t=24s  Prometheus scrape → widzi Request D jako exemplar dla le=0.5s
```

---

## Pełny pipeline w tym projekcie

```
┌─────────────────────────────────────────────────────────────────────────┐
│  WARSTWA 1: Aplikacja Spring Boot (starport-registry, trade-route-planner,
│             telemetry-pipeline)                                          │
│                                                                          │
│  request wchodzi → OtelContextPropagator nadaje trace_id (W3C)          │
│       ↓                                                                  │
│  Timer.record(duration)  ← Micrometer odczytuje trace_id z MDC/OTel     │
│       ↓                                                                  │
│  HistogramExemplarSampler.sample(duration, traceId)                      │
│       ↓                                                                  │
│  /actuator/prometheus eksponuje exemplary w OpenMetrics format           │
└──────────────────┬──────────────────────────────────────────────────────┘
                   │ HTTP scrape co 3s
                   ▼
┌─────────────────────────────────────────────────────────────────────────┐
│  WARSTWA 2: Prometheus (--enable-feature=exemplar-storage)              │
│                                                                          │
│  Przechowuje exemplary osobno od zwykłych samples                       │
│  (normalny TSDB nie przechowuje exemplarów — potrzeba flagi)            │
│  Eksponuje przez API: GET /api/v1/query_exemplars                        │
└──────────────────┬──────────────────────────────────────────────────────┘
                   │ Grafana query
                   ▼
┌─────────────────────────────────────────────────────────────────────────┐
│  WARSTWA 3: Grafana (exemplarTraceIdDestinations: trace_id → tempo)     │
│                                                                          │
│  Panel Time series → renderuje exemplary jako ◆ (diamenty) na wykresie │
│  Klik na diament → Grafana czyta trace_id z exemplara                   │
│  → redirect do Tempo: /tempo/trace/{trace_id}                           │
└──────────────────┬──────────────────────────────────────────────────────┘
                   │ trace lookup
                   ▼
┌─────────────────────────────────────────────────────────────────────────┐
│  WARSTWA 4: Tempo                                                        │
│                                                                          │
│  Trace znaleziony po trace_id → pełny waterfall spanów                  │
│  → root cause w ciągu sekund                                            │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## Konfiguracja w tym projekcie — co jest zrobione i dlaczego

### 1. Aplikacja — SLO buckets zamiast `percentiles-histogram`

```yaml
# application.yml (starport-registry, trade-route-planner, telemetry-pipeline)
management:
  metrics:
    distribution:
      slo:
        http.server.requests: 50ms,100ms,250ms,500ms,1s,2500ms,5s,10s
```

**Dlaczego `slo:` a nie `percentiles-histogram: true`?**

| Podejście | Liczba bucketów | Przykład |
|---|---|---|
| `percentiles-histogram: true` | ~90 wbudowanych (Micrometer default) | le=0.001, 0.00147, 0.00215... |
| `slo:` | Dokładnie tyle ile podasz | le=0.05, 0.1, 0.25, 0.5, 1.0... |

`slo:` daje **mniej bucketów → mniejszy cardinality w Prometheus → niższe zużycie pamięci**,  
przy zachowaniu wszystkich korzyści exemplarów. W tym projekcie 8 bucketów vs ~90.

> **Ważne:** Exemplary działają z **obydwoma** podejściami. `slo:` jest wystarczające.

### 2. Prometheus — `--enable-feature=exemplar-storage`

```yaml
# docker-compose.yml
prometheus:
  command:
    - --config.file=/etc/prometheus/prometheus.yml
    - --web.enable-remote-write-receiver
    - --enable-feature=exemplar-storage          # ← bez tego exemplary są ignorowane
```

Bez tej flagi Prometheus odrzuca exemplary przy scrape — **nie pojawia się żaden błąd**, po prostu są ciche dropowane.

### 3. Tempo — `send_exemplars: true` w remote_write

```yaml
# tempo.yml
metrics_generator:
  storage:
    remote_write:
      - url: http://prometheus:9090/api/v1/write
        send_exemplars: true     # ← Tempo-generated metrics też mają exemplary
```

Tempo generuje swoje metryki (RED: rate, error, duration) przez `span-metrics` processor i odsyła je do Prometheusa **z exemplarami** — czyli metryki wygenerowane z traceów też mają linki z powrotem do traceów.

### 4. Grafana — `exemplarTraceIdDestinations`

```yaml
# datasource.yml
- name: Prometheus
  jsonData:
    exemplarTraceIdDestinations:
      - name: trace_id          # nazwa labela exemplara (musi pasować do tego co wysyła Micrometer)
        datasourceUid: tempo    # UID datasource Tempo
```

To jest "klej" UI: Grafana wie że gdy napotka exemplar z labelkiem `trace_id`, ma go traktować jako link do Tempo.

---

## Ograniczenia i pułapki

### 1. Exemplary nie są agregowalne (multi-instance problem)

```
starport-registry-1 → exemplar trace_id="abc" dla bucket le=0.5s
starport-registry-2 → exemplar trace_id="xyz" dla bucket le=0.5s

Prometheus po scrape obu instancji: przechowuje OBA exemplary
PromQL histogram_quantile() agreguje _bucket counters skalarnie,
ale exemplary nie mają zdefiniowanej semantyki agregacji.
```

**W praktyce:** Grafana pokazuje exemplary z obu instancji jako oddzielne diamenty — to OK.

### 2. `percentiles:` (Summary) nie ma exemplarów

```yaml
# NIE rób tego jeśli chcesz exemplary:
management:
  metrics:
    distribution:
      percentiles:
        http.server.requests: 0.5, 0.95, 0.99   # ← Summary, brak bucketów, brak exemplarów
```

Summary gauges (`_p99`) nie mają bucketów → nie można dołączyć exemplarów. Dodatkowo nie są agregowalne między instancjami.

### 3. Sampling probability musi być > 0

Jeśli `management.tracing.sampling.probability: 0.0`, requestom nie są przydzielane trace_id. Exemplary istnieją, ale mają pusty `trace_id` → link do Tempo prowadzi donikąd.

W tym projekcie: `sampling.probability: ${TRACE_SAMPLING:1.0}` — wszystkie requesty są tracowane, więc wszystkie exemplary mają wypełniony `trace_id`. ✅

### 4. Exemplary a `recording rules`

```yaml
# recording.yml — pre-agregacja histogramów
- record: job:http_server_requests_seconds_bucket:rate3m
  expr: sum(rate(http_server_requests_seconds_bucket[3m])) by (job, le)
```

**Recording rules TRACĄ exemplary.** `rate()` i `sum()` operują na liczbach, exemplary nie przechodzą przez ruleset. Jeśli chcesz exemplary, **pytaj o oryginalne metryki** (nie recorded), albo używaj `query_exemplars` API bezpośrednio.

---

## Jak to wygląda w raw Prometheus format

`GET http://starport-registry-1:8081/actuator/prometheus` zwraca:

```
# TYPE http_server_requests_seconds histogram
# HELP http_server_requests_seconds Duration of HTTP server request handling

http_server_requests_seconds_bucket{job="starport-registry",le="0.05",...}  842
http_server_requests_seconds_bucket{job="starport-registry",le="0.1",...}  1203
http_server_requests_seconds_bucket{job="starport-registry",le="0.25",...} 1891 # {trace_id="a1b2c3d4e5f60000",span_id="f1e2d3c4"} 0.187 1716123456.789
http_server_requests_seconds_bucket{job="starport-registry",le="0.5",...}  2047 # {trace_id="9f8e7d6c5b4a0000",span_id="b1c2d3e4"} 0.431 1716123478.012
http_server_requests_seconds_bucket{job="starport-registry",le="1.0",...}  2051 # {trace_id="1a2b3c4d5e6f0000",span_id="a0b1c2d3"} 0.923 1716123490.345
http_server_requests_seconds_bucket{job="starport-registry",le="2.5",...}  2052 # {trace_id="deadbeefcafe0000",span_id="11223344"} 2.187 1716123502.678
http_server_requests_seconds_bucket{job="starport-registry",le="+Inf",...} 2052
http_server_requests_seconds_count{...} 2052
http_server_requests_seconds_sum{...}   847.234
```

Każda linia `_bucket` z exemplarem zawiera:
- `trace_id` + `span_id` — W3C identyfikatory
- Faktyczną zmierzoną wartość (np. `2.187` — nie `2.5`)  
- Unix timestamp próbki

---

## Decyzja

Exemplary są **włączone we wszystkich serwisach** przez kombinację:
1. `slo:` buckets w `application.yml` każdego serwisu (tworzy histogram → sloty na exemplary)
2. `--enable-feature=exemplar-storage` w Prometheus (trwałe przechowywanie)
3. `exemplarTraceIdDestinations` w Grafana datasource (UI linking)
4. `send_exemplars: true` w Tempo remote_write (metryki generowane z traceów też mają linki)
5. `sampling.probability: 1.0` (każdy request ma trace_id → każdy exemplar jest klikalny)

**Nie dodajemy żadnego dodatkowego kodu Java** — integracja Micrometer ↔ OpenTelemetry Bridge automatycznie wstrzykuje aktywny trace_id do każdego pomiaru timera.

---

## Konsekwencje

✅ **Benefit:** Czas od "widzę spike p99" do "mam root cause w trace" spada z ~5 minut do ~5 sekund  
✅ **Benefit:** Brak nowego kodu — zero-cost feature wynikający z już skonfigurowanego OTel + Prometheus  
✅ **Benefit:** Działa również w drugą stronę przez `tracesToMetrics` w Tempo datasource  
⚠️ **Tradeoff:** Exemplary to próbki, nie gwarancja że "zobaczycie najgorszy request" — to świadomy design, nie bug  
⚠️ **Tradeoff:** Recording rules tracą exemplary — dashboardy oparte na `recorded` metrykach nie pokażą klikalnych kropek  

