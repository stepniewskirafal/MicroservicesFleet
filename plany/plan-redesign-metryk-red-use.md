# Plan redesign'u metryk — RED/USE dla Microservices Fleet

## TL;DR

Obecny zestaw (26 custom metryk + built-ins) ma **dobre pokrycie trace'owe** (8 Observations auto-tworzących Timery) i sensowne histogramy na kluczowych operacjach, ale ma **3 systematyczne wady**:

1. **RED niekompletny na granicach** — brak jednolitego `{name}_requests_total` / `{name}_errors_total` / `{name}_request_duration_seconds` na wszystkich surface'ach request-driven (HTTP ingress, HTTP klient do innych serwisów, Kafka consumer, outbox publisher).
2. **USE praktycznie nieobecny** — brak utilization/saturation dla zasobów poza JVM heap (który i tak leci z built-inów). Brak głębokości kolejki outbox, lag konsumenta Kafki, saturacji puli Hikari.
3. **DistributionSummary używany tam, gdzie `Timer` byłby prostszy** — `routes.risk.score`, `routes.eta.hours` są domenowe (to OK), ale brakuje im towarzyszącego licznika `_total`, żeby RED działało.

Poniżej: szczegółowy audyt, docelowa taksonomia, lista metryk do zmiany typu na histogram z bucketami SLO, oraz plan migracji w 4 fazach.

---

## 1. Metodyka — przypomnienie w 5 zdaniach

**RED** (Google SRE / Weaveworks) dotyczy **ruchu przez system** — wszystko co przyjmuje "request" (HTTP, Kafka message, scheduled tick):
- **Rate** — liczba requestów na sekundę, w rozbiciu per surface/operation.
- **Errors** — liczba requestów zakończonych błędem, **ten sam denominator co Rate**.
- **Duration** — histogram latencji, z kwantylami liczonymi po stronie Prometheusa z bucketów (nie po stronie aplikacji).

**USE** (Brendan Gregg) dotyczy **zasobów w systemie** — wszystko co ma pojemność i może być nasycone:
- **Utilization** — jaki % zasobu jest wykorzystywany (CPU, heap, pool connections).
- **Saturation** — jak bardzo kolejka do zasobu się piętrzy (runnable threads, DB wait queue, Kafka consumer lag).
- **Errors** — odrzucenia/timeouty na tym zasobie.

Dwie dyscypliny są komplementarne: błąd *użytkownika* łapie RED, błąd *systemowy* pokazuje USE.

---

## 2. Audyt obecnego stanu

### 2.1 Request-driven surfaces — ocena RED

| Surface | Rate | Errors | Duration | Ocena |
|---|---|---|---|---|
| starport-registry HTTP POST reservations | `reservations_total{outcome}` (built-in z Observation `reservations.confirm` + custom counter?) | wydzielony w label `outcome=no_capacity\|route_unavailable` | `http_server_requests_seconds_*` + `reservations_confirm_seconds_*` | **7/10** — ma wszystko, ale metryka `reservations_total` jest implicitna, label `outcome` miesza success/error semantykę |
| starport-registry → trade-route-planner (RestClient) | brak jawnego `_total` | `reservations_route_plan_errors_total{errorType}` | `reservations_route_plan_seconds_*` (z Observation) | **6/10** — brak jawnego licznika wszystkich wywołań; `errorType` ma sensowne wartości (`domain/infrastructure/empty_response/circuit_open`), ale success jest w osobnej metryce `reservations_route_plan_success_total` — asymetria |
| trade-route-planner HTTP POST /routes/plan | `routes_planned_count_total` (success) + `routes_rejected_count_total{reason}` (error) | osobne counters | `routes_plan_seconds_*` | **7/10** — RED jest, ale **Rate = success + error** nie jest jedną metryką; wymaga PromQL sumowania |
| starport-registry outbox publish → Kafka | pokryte przez `reservations_outbox_append_seconds_*` (z Observation) | `reservations_outbox_dead_letter_total{eventType,binding}` | ma Timer | **8/10** — w porządku |
| starport-registry inbox poll → DB | `reservations_inbox_poll_batch_size_events_*` (DistributionSummary) + `reservations_inbox_poll_duration_seconds_*` | brak dedykowanego error counter (liczy się z outbox DLQ) | Timer | **6/10** — poll jako operacja nie ma jawnego `errors_total`; retry/DB timeout tonie w logach |
| telemetry-pipeline Kafka consumer | `telemetry_messages_received_total` (Counter) | `telemetry_messages_invalid_total` + `telemetry_anomalies_detected_total{severity}` | **brak Timera per-pipeline-stage** — są Timery per filter (`telemetry_filter_*`) | **5/10** — RED per wiadomość jest, RED per pipeline stage rozczepiony; brak end-to-end latency |
| telemetry-pipeline event-driven (events.reservation/route) | `events_reservation_received_total` / `events_route_received_total` (Rate) + `_enriched_total` | **brak error counter** dla "enrichment failed" | **brak Timera** na pipeline enrichment | **3/10** — tylko Rate, bez Errors i Duration |
| api-gateway | `http_server_requests_seconds_*` (built-in) + `spring_cloud_gateway_requests_*` | jak wyżej | jak wyżej | **7/10** — built-ins wystarczają, bo gateway to proxy; brakuje tylko metryk auth/rate-limit jeśli pojawią się w przyszłości |

### 2.2 Zasoby — ocena USE

| Zasób | Utilization | Saturation | Errors | Ocena |
|---|---|---|---|---|
| JVM heap | `jvm_memory_used_bytes / jvm_memory_max_bytes` (wymaga PromQL) | `jvm_gc_pause_seconds_*`, `jvm_memory_committed_bytes` | `jvm_gc_pause_seconds_count{action="end of major GC"}` | **7/10** — built-in pokrywa, ale dashboard pokazuje raw MB zamiast % |
| CPU | `process_cpu_usage` (built-in, wartość 0-1) | `system_load_average_1m` | brak | **6/10** — saturacja mylona z utylizacją |
| Tomcat threads | `tomcat_threads_busy_threads / tomcat_threads_config_max_threads` | `tomcat_threads_current_threads` vs max | brak dedykowanego | **5/10** — jest w built-inach, nie używane w dashboardzie |
| Virtual threads (Spring Boot 3.2+) | brak | brak (specyficzne) | brak | **0/10** — projekt używa virtual threads (widziałem `[virtual-129]` w logach) — nie jest to monitorowane |
| Hikari pool (PostgreSQL) | `hikaricp_connections_active / hikaricp_connections_max` | `hikaricp_connections_pending` (threads czekające na connection) | `hikaricp_connections_timeout_total` | **8/10** — built-in ma wszystko, ale nieprezentowane na dashboardzie |
| Kafka consumer lag | `kafka_consumer_fetch_manager_records_lag_max` (built-in z spring-kafka) | jak wyżej | `kafka_consumer_*_errors_total` | **5/10** — built-in istnieje, ale nie wiem czy jest enabled (wymaga flagi) i nie ma panelu |
| Kafka producer buffer | `kafka_producer_buffer_available_bytes` | `kafka_producer_record_queue_time_avg` | `kafka_producer_record_error_total` | **5/10** — j.w. |
| Outbox table jako kolejka | **brak** | **brak** (liczba PENDING rekordów) | brak | **0/10** — to krytyczna luka; brak widoczności czy outbox się nie "zatyka" |
| Circuit breaker (resilience4j) | `resilience4j_circuitbreaker_state` (0/1 per state) | `resilience4j_circuitbreaker_buffered_calls` | `resilience4j_circuitbreaker_calls{outcome=failed}` | **8/10** — built-in z resilience4j-micrometer, ale nie widziałem na dashboardzie |
| Disk / FS | built-in `disk_free_bytes`, `disk_total_bytes` | brak | brak | **4/10** — mało istotne dla stateless serwisów |

---

## 3. Docelowa taksonomia — konwencje nazewnicze

Przyjmijmy **stały kształt nazwy** we wszystkich serwisach, zgodny z Prometheus best practices (`_total`, `_seconds`, `_bytes`, base unit w nazwie):

### 3.1 RED — granice request-driven

Dla każdego surface'u request-driven (HTTP endpoint, Kafka topic listener, scheduled task, outbox poller) wyemitujmy **trzy metryki** o spójnych nazwach:

```
<domain>_<operation>_requests_total              (Counter)
<domain>_<operation>_request_errors_total        (Counter, z labelem `errorType`)
<domain>_<operation>_request_duration_seconds    (Timer → histogram + count + sum)
```

Przy czym `requests_total` powinien **liczyć WSZYSTKIE requesty (success + error)** — denominator dla error rate. Dzisiaj mamy `success` i `error` w osobnych counterach, co wymusza podwójne sumowanie w PromQL i łamie paradygmat RED.

**Przykład dla starport-registry reservation:**
```
reservation_create_requests_total                       # WSZYSTKIE, success + error
reservation_create_request_errors_total{errorType="no_capacity|route_unavailable|invalid_time|internal"}
reservation_create_request_duration_seconds_bucket{le=...}
reservation_create_request_duration_seconds_count
reservation_create_request_duration_seconds_sum
```

**Tagi dozwolone (low cardinality, <20 unikalnych wartości):** `starport`, `shipClass`, `errorType`, `operation`.
**Zabronione (eksplodują kardynalność):** `customerCode`, `shipCode`, `reservationId`, `routeCode`, `traceId`, timestampy.

### 3.2 USE — zasoby

Dla każdego zasobu, trzy metryki zgodne z semantyką USE:

```
<resource>_utilization_ratio            (Gauge, 0..1)  — ile % capacity aktywnie używane
<resource>_saturation                   (Gauge lub Counter) — ile czeka/kolejkuje
<resource>_errors_total                 (Counter) — odrzucenia, timeouty
```

Większość z tych metryk **już istnieje jako built-in** z różnymi nazwami. Nie przemianowujemy — tylko **udostępniamy w dashboardzie** w sekcji "USE per resource".

### 3.3 Konwencja nazewnicza dla business metrics

Metryki domenowe (fee amount, route ETA, risk score) to **business KPI**, nie RED/USE. Zachowujemy jako DistributionSummary, ale **obok każdej musi istnieć Counter `_total`** — żeby można było policzyć `avg = sum/count` bez dzielenia przez zero, i żeby widzieć rate zdarzeń biznesowych niezależnie od ich rozkładu.

---

## 4. Metryki do zamiany na histogram z bucketami SLO

Histogram z bucketami to **jedyny** poprawny sposób liczenia `quantile()` w Prometheusie rozproszenie. Summary (client-side percentiles) **nie agreguje się** między instancjami — to w naszym setupie (2x starport-registry, 2x trade-route-planner) powoduje, że dashboardowe `p99` jest średnią percentyli per-instance, nie prawdziwym p99 globalnym.

Poniżej lista metryk **request-driven duration**, które muszą być histogramami z explicite zdefiniowanymi bucketami dobranymi do SLO:

| Metryka | Obecny typ | Docelowe bucket boundaries (seconds) | Uzasadnienie SLO |
|---|---|---|---|
| `reservation_create_request_duration_seconds` (nowa) | — (wyprowadzić z `http.server.requests{uri=...}`) | 0.05, 0.1, 0.2, 0.5, 1, 2, 5 | SLO: p99 < 1s; zakres musi obejmować typowy zakres 100ms-2s |
| `reservations.route.plan` (Observation → Timer) | już Timer, buckety: 0.05, 0.1, 0.2, 0.5, 1, 2, 5 | **zostawić** | SLO zewnętrzny (wywołuje trade-route-planner); p99 < 2s |
| `reservations.fees.calculate` | już Timer, buckety: 0.001, 0.005, 0.01, 0.05, 0.1 | **zostawić** — to in-memory op, to poprawny zakres | SLO: p99 < 50ms |
| `reservations.hold.allocate` | już Timer, buckety: 0.01, 0.05, 0.1, 0.5, 1, 2 | **rozszerzyć do**: 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1 | dotyka PG + FOR UPDATE SKIP LOCKED; p99 < 100ms w normie, alert na > 500ms |
| `reservations.confirm` (Observation) | Timer, brak jawnych bucketów | **dodać**: 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2 | confirm = UPDATE + outbox INSERT; SLO: p99 < 250ms |
| `reservations.outbox.append` (Observation) | Timer bez bucketów | **dodać**: 0.001, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5 | pure DB write, p99 powinno być < 25ms |
| `reservations.inbox.publish` (Observation) | Timer bez bucketów | **dodać**: 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2, 5 | Kafka send może być powolne przy re-elect/lag |
| `reservations.inbox.poll.duration` | Timer bez bucketów | **dodać**: 0.005, 0.01, 0.05, 0.1, 0.5, 1, 5 | batch polling + publish; szeroki zakres |
| `routes.plan` (Observation, trade-route-planner) | Timer, buckety: 0.01, 0.05, 0.1, 0.2, 0.5, 1, 2, 5 | **zostawić** | |
| `telemetry.filter.*` (validation/enrichment/aggregation/anomaly) | Timer, buckety: 0.001, 0.005, 0.01, 0.05 | **rozszerzyć**: 0.0001, 0.0005, 0.001, 0.005, 0.01, 0.05, 0.1 | per-message filter, chcemy widzieć sub-ms ogony |

Metryki typu `http.server.requests` **są już** histogramem z percentyli-buckets dzięki `management.metrics.distribution.percentiles-histogram.http.server.requests=true` — ale Prometheus dostaje domyślną paletę bucketów Micrometera (~150 bucketów). **Propozycja**: wymusić własny, krótszy zestaw bucketów specyficzny dla każdego URI, żeby odciążyć storage Prometheusa i zyskać dokładność tam gdzie się liczy. Każdy dodatkowy bucket to 1 seria + `{le="..."}` label per route+method — przy 4 serwisach × 6 endpointach × 150 bucketów = 3600 serii tylko z HTTP histogram. 10 SLO bucketów zamiast 150 = **15x redukcja cardinality**.

### 4.1 Metryki które NIE powinny być histogramem

- `reservations.fees.calculated.amount` — **DistributionSummary zostaje**, ale histogram=false (nie potrzebujemy percentyli opłat w CR, interesuje nas suma/średnia jako KPI biznesowy). Obecnie `percentiles-histogram: reservations.fees.calculated.amount: true` jest w configu — **wyłączyć**.
- `reservations.fees.calculated.hours` — j.w.
- `routes.risk.score`, `routes.eta.hours` — DistributionSummary OK, histogram tylko jeśli chcemy SLI "% tras z risk>0.8" — wtedy **tak, bucket na 0.8**.
- `reservations.inbox.poll.batch.size` — DistributionSummary OK, histogram niepotrzebny (nic nie alarmujemy na rozkładzie batch size).

---

## 5. Co trzeba DODAĆ (obecnie nie istnieje)

### 5.1 USE dla outbox (krytyczne)

Gauge `reservations_outbox_pending_events{binding}` — liczba rekordów w `event_outbox` ze `status = 'PENDING'`, odświeżana co 15s przez scheduled task (ten sam task który poll'uje — może łatwo dorzucić query count).

- **Utilization**: n/a (tabela nie ma twardego limitu).
- **Saturation**: ten gauge. Alert: `pending > 1000` przez > 5 min = outbox nie nadąża.
- **Errors**: już mamy `reservations_outbox_dead_letter_total`.

### 5.2 USE dla Kafka consumer (jeśli jeszcze nie aktywne)

Włączyć w `application.yml` każdego serwisu konsumującego Kafkę:
```yaml
management:
  metrics:
    enable:
      kafka: true
```
To wyemituje `kafka_consumer_fetch_manager_records_lag_max{topic, partition, client_id}` i pokrewne. To **najważniejszy jeden gauge** dla event-driven systemu.

### 5.3 RED dla starport → trade-route-planner po stronie klienta

Obecnie `reservations_route_plan_success_total` + `reservations_route_plan_errors_total{errorType}` to RED *po części*. Dodać:
- `reservations_route_plan_requests_total` (success + error w jednej) — **albo** zmienić success na `reservations_route_plan_requests_total` + zrobić `errors_total` fakultatywne.
- Histogram `reservations_route_plan_client_duration_seconds` (osobny od Observation, mierzący wyłącznie po stronie RestClient, żeby łapał też I/O timeouty — bo Observation może nie zamknąć się na timeout).

### 5.4 RED dla telemetry pipeline end-to-end

Obecnie mamy Timer per filter, ale brakuje Timera per-message end-to-end przez cały pipeline (received → validated → enriched → aggregated → anomaly-checked). Dodać:
- `telemetry_pipeline_request_duration_seconds` (histogram) — cały cykl.
- `telemetry_pipeline_requests_total` — rate.
- `telemetry_pipeline_request_errors_total{stage, errorType}` — error z rozbiciem na etap.

### 5.5 Virtual threads utilization

Spring Boot 3.2+ eksportuje `executor.active{name=applicationTaskExecutor}` dla virtual threads, ale virtual threads z Tomcata latają w osobnym executor. Dodać custom gauge:
```
jvm_virtual_threads_mounted_count  (liczba aktywnie mounted na platform thread)
jvm_virtual_threads_total_count    (całkowita liczba żyjących)
```
Wymaga JFR lub `Thread.getAllStackTraces()` — trade-off: to ma koszt. Alternatywnie — użyć wbudowanego `jvm.threads.states{state=RUNNABLE}`.

---

## 6. Anty-wzorce do wyeliminowania (znalezione w obecnym kodzie)

### 6.1 Pre-registracja metryki bez tagów (naprawiono dzisiaj)
Wzorzec z `FeeCalculatorService` (usunięty). **Nigdy** nie rejestrować metryki pustym zestawem kluczy tagów, a potem tej samej nazwy z tagami — `PrometheusMeterRegistry` odrzuci drugi meter. Jeśli naprawdę chcemy, żeby metryka była "widoczna od startu", zarejestrować **z tagami-placeholderami tego samego schematu**, np. `.tag("starport", "__none__").tag("shipClass", "__none__")`, i od razu `.record(0)`. Lub — lepsza opcja — zostawić metryki do pojawienia się na żywym ruchu i użyć `or vector(0)` w PromQL dashboardu.

### 6.2 Osobne Counters na success vs error zamiast jednego Counter + error counter
Obecnie: `reservations_route_plan_success_total` + `reservations_route_plan_errors_total`.
Docelowo: `reservations_route_plan_requests_total` (wszystkie) + `reservations_route_plan_errors_total{errorType}` (tylko błędy, z labelem kategorii).

Powód: error rate = `errors_total / requests_total`. Przy obecnym układzie: `errors / (errors + success)` — wymaga dwóch metryk w PromQL, łatwo pomylić.

### 6.3 DistributionSummary bez towarzyszącego Counter `_total`
`reservations_fees_calculated_amount_cr_count` istnieje (bo DistributionSummary emituje `_count`), ale nie ma `_total` w formatowaniu zgodnym z Prometheus convention. Drobny detal — zostawić, PromQL na `_count` działa.

### 6.4 Histogram dla wartości biznesowych, których nie percentylujemy
`percentiles-histogram: reservations.fees.calculated.amount: true` — wyłączyć. Percentyl opłat nikogo nie obchodzi, a każdy bucket to koszt storage.

### 6.5 Tagi o wysokiej kardynalności
Przeglądnięty kod nie zawiera takich grzechów (starport codes ~20, shipClass = 4 wartości). Ale zapisać w ADR/konwencji: tagi z kardynalnością > 50 idą do logów/tracingu, nie do metryk.

---

## 7. Plan migracji — 4 fazy

### Faza 1 — higiena i uzupełnienia (1 PR, 1-2h)
- Usunąć `percentiles-histogram: true` z business metrics (`fees.calculated.amount`, `fees.calculated.hours`) w `application.yml` wszystkich serwisów.
- Dodać jawne `slo:` buckets do metryk: `reservations.confirm`, `reservations.outbox.append`, `reservations.inbox.publish`, `reservations.inbox.poll.duration` w starport-registry.
- Włączyć `management.metrics.enable.kafka=true` w starport-registry (producer) i telemetry-pipeline (consumer).
- Dodać gauge `reservations_outbox_pending_events` w InboxPublisher scheduling loop.

**Ryzyko:** niskie. Zero breaking change. Dashboard bez zmian. Eksport Prometheusa zyskuje nowe serie + traci parę zbędnych bucketów.

### Faza 2 — unifikacja RED (1 PR per serwis, ~0.5 dnia każdy)
- starport-registry: dodać `reservation_create_requests_total` (z outcome jako label) — docelowo zastąpi to, co obecnie latwi jako `reservations_total`. Histogramy zostają.
- starport-registry: `reservations_route_plan_requests_total` jako superset `success_total`; `success_total` deprecate.
- trade-route-planner: `routes_plan_requests_total` jako superset `planned_count_total` + `rejected_count_total`; zostawić `routes_planned_total` i `routes_rejected_total{reason}` dla kompatybilności i PRs-ów biznesowych.
- telemetry-pipeline: `telemetry_pipeline_requests_total`, `_errors_total{stage}`, `_duration_seconds` histogram — owinąć cały enrichment w jedną Observation na górnym poziomie.

**Ryzyko:** średnie. Dashboardy i alerty trzeba zaktualizować na nowe nazwy. Zostawić stare metryki przez 2 sprinty jako deprecated (emitować równolegle), potem usunąć.

### Faza 3 — USE dashboard (1 PR tylko Grafana, 1-2h)
Jeden nowy dashboard "Resources (USE)" z czterema wierszami:
- JVM (heap util%, GC pause p99, old-gen saturation)
- Thread pools (Tomcat, Hikari, ForkJoinPool)
- Kafka (consumer lag per topic/partition, producer queue time)
- Outbox queue (pending_events, dead letter rate, append latency)

Plus każdy serwis dostaje w "swoim" dashboardzie sekcję "Resources" z wybranymi metrykami USE dotyczącymi tego serwisu.

**Ryzyko:** niskie, nic nie zmieniamy w kodzie.

### Faza 4 — alerty i SLO (1 PR w Prometheus/AlertManager config, 0.5 dnia)
Zdefiniować alerty oparte o bucket-based percentyle:
- `histogram_quantile(0.99, rate(reservation_create_request_duration_seconds_bucket[5m])) > 1` przez 10 min = warning.
- `rate(reservation_create_request_errors_total[5m]) / rate(reservation_create_requests_total[5m]) > 0.01` przez 5 min = page.
- `reservations_outbox_pending_events > 1000` przez 5 min = warning.
- `kafka_consumer_fetch_manager_records_lag_max > 10000` przez 5 min = warning.

**Ryzyko:** false positives na początku. Wymaga 1-2 tygodni tuning'u progów na rzeczywistym ruchu.

---

## 8. Macierz RED/USE — stan docelowy

### RED per surface

| Surface | Rate | Errors | Duration (histogram) |
|---|---|---|---|
| starport-registry HTTP /reservations | `reservation_create_requests_total` | `reservation_create_request_errors_total{errorType}` | `reservation_create_request_duration_seconds_bucket` |
| starport-registry → trade-route-planner | `reservations_route_plan_requests_total` | `reservations_route_plan_errors_total{errorType}` | `reservations_route_plan_seconds_bucket` |
| starport-registry outbox publish | `reservations_outbox_append_seconds_count` | `reservations_outbox_dead_letter_total{eventType,binding}` | `reservations_outbox_append_seconds_bucket` |
| starport-registry inbox poll | `reservations_inbox_poll_duration_seconds_count` | *(nowy)* `reservations_inbox_poll_errors_total{errorType}` | `reservations_inbox_poll_duration_seconds_bucket` |
| trade-route-planner HTTP /routes/plan | `routes_plan_requests_total` | `routes_rejected_total{reason}` | `routes_plan_seconds_bucket` |
| telemetry-pipeline Kafka consumer | `telemetry_messages_received_total` | `telemetry_messages_invalid_total` + `_errors_total{stage}` | *(nowy)* `telemetry_pipeline_request_duration_seconds_bucket` |
| api-gateway | `http_server_requests_seconds_count` (built-in) | `http_server_requests_seconds_count{status=~"5.."}` | `http_server_requests_seconds_bucket` |

### USE per resource

| Zasób | Utilization | Saturation | Errors |
|---|---|---|---|
| JVM heap | `jvm_memory_used_bytes / jvm_memory_max_bytes` | `jvm_gc_pause_seconds_sum` rate | `jvm_gc_overhead_percent` |
| CPU process | `process_cpu_usage` | `system_load_average_1m` | n/a |
| Tomcat threads | `tomcat_threads_busy / tomcat_threads_config_max` | `tomcat_threads_current - tomcat_threads_busy` waiting | `tomcat_connections_keepalive_current` |
| Hikari pool | `hikaricp_connections_active / hikaricp_connections_max` | `hikaricp_connections_pending` | `hikaricp_connections_timeout_total` |
| Outbox queue | n/a | `reservations_outbox_pending_events` *(nowy)* | `reservations_outbox_dead_letter_total` |
| Kafka consumer | `kafka_consumer_fetch_manager_fetch_size_avg / max_size` | `kafka_consumer_fetch_manager_records_lag_max` | `kafka_consumer_*_errors_total` |
| Kafka producer | `kafka_producer_buffer_available_ratio` | `kafka_producer_record_queue_time_avg` | `kafka_producer_record_error_total` |
| Circuit breaker (trade-route-planner) | `resilience4j_circuitbreaker_buffered_calls / max` | `resilience4j_circuitbreaker_slow_call_rate` | `resilience4j_circuitbreaker_calls{outcome=failed}` |

---

## 9. Decyzje do potwierdzenia przed kodowaniem

1. **Deprecation windows** — czy zostawiamy stare metryki (`reservations_route_plan_success_total`) emitowane równolegle z nowymi przez 2 sprinty, czy tniemy w jednym PR? Rekomendacja: **emitować równolegle**, bo inaczej wszystkie dashboardy i alerty walą się jednocześnie.
2. **Nazwa metryki "creation"** — `reservation_create_requests_total` czy `reservation_requests_total{operation=create}`? Rekomendacja: **bez `operation`**, bo `/reservations/{id}/cancel` i `/reservations/{id}` GET to osobne surfaces z osobnymi SLO.
3. **Histogram bucketów HTTP** — zastępujemy domyślną paletę Micrometera własną via `management.metrics.distribution.slo.http.server.requests`? Oszczędność cardinality vs. ryzyko że komuś zabraknie bucketu przy analizie. Rekomendacja: **zastępujemy**, bo 150 bucketów per endpoint to astronomia. Definiujemy palete `[50ms, 100ms, 250ms, 500ms, 1s, 2.5s, 5s, 10s]`.
4. **Telemetry pipeline end-to-end Timer** — gdzie go umieścić? Propozycja: na granicy consumer listener method, `@Observed("telemetry.pipeline.process")` z child observations per filter. Wymaga sprawdzenia, czy `ObservationConvention` propaguje tagi między parent/child — jeśli nie, `stage` label trzeba będzie przepisywać ręcznie.

---

## 10. Co nie zmieniać — świadome decyzje

- **Observations z Micrometer Tracing zostają wszędzie.** To one generują `http.server.requests` oraz auto-Timer per observation — baza tracing + metrics z jednego API.
- **Circuit breaker fallback → exception → metric jest OK.** Wzorzec w `TradeRoutePlannerHttpAdapter` (increment error counter w catch + rzuć domain exception) jest poprawny; nie refaktorujemy na `@Timed` + `Metrics.counter().increment()`, bo straciłoby to link z trace context.
- **Histogramy na `reservations.fees.calculate` Timer zostają** — to observation, której percentyle są interesujące (żeby złapać ewentualne anomalie w liczeniu fee).

---

## Następne kroki

Powiedz które decyzje z §9 idą w którą stronę, i czy zaczynamy od **Fazy 1** jako pierwszy PR. Faza 1 jest zero-risk i najbardziej opłacalna (usunięcie zbędnych bucketów + Kafka metrics enable + outbox pending gauge) — jest to też najlepsza "baseline" przed większą refaktoryzacją w Fazach 2-4.
