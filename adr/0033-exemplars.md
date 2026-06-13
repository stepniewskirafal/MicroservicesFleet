# ADR-0033 — Exemplars: Metrics → Traces korelacja (Prometheus → Tempo)

**Status:** Accepted  
**Data:** 2026-05-17  
**Powiązane ADR:** ADR-0005 (observability stack), ADR-0017 (tracing propagation), ADR-0030 (metrics naming)

---

## Kontekst

Widząc spike p99 na wykresie wiemy **co** się dzieje (wartość metryki), ale nie **dlaczego** (który request). Exemplary dają **klikalny link** od punktu na wykresie wprost do trace'a w Tempo — bez ręcznego przeszukiwania po czasie/serwisie.

---

## Czym jest exemplar

Opcjonalny payload doklejany do próbki metryki w Prometheus Exposition Format 2.0 — `trace_id` (W3C) + faktyczna zmierzona wartość + Unix timestamp:

```
http_server_requests_seconds_bucket{le="0.5",uri="/reservations"} 1027 # {trace_id="4bf92f35...4736"} 0.462 1716123456.789
```

Tu `0.462` to realny czas requestu (nie górna granica bucketu `0.5`).

---

## Który request zostaje exemplarem?

> Ani najwolniejszy, ani „ostatni w paczce".

Micrometer (`micrometer-registry-prometheus`) używa `HistogramExemplarSampler` z `prometheus-metrics-core`. Każdy bucket ma jeden slot, nadpisywany **last-write-wins z time-gating**: nowy pomiar nadpisuje exemplar tylko gdy stary jest starszy niż `minRetentionPeriod = 7 × scrape_interval`. W tym projekcie `scrape_interval: 3s` → **retencja 21s** (`prometheus.yml`).

Skutek: to **w przybliżeniu losowy** request — pierwszy, który trafił w bucket po upłynięciu 21s od poprzedniego exemplara. Nie ma logiki „wybierz max". Jest reprezentatywny: jeśli widzisz exemplar przy 1.8s, ten konkretny request trwał ~1.8s.

---

## Pipeline

`Aplikacja (Micrometer odczytuje trace_id z OTel context) → /actuator/prometheus eksponuje exemplary` → `Prometheus (--enable-feature=exemplar-storage, osobne przechowywanie; API /api/v1/query_exemplars)` → `Grafana (exemplarTraceIdDestinations: trace_id → tempo; renderuje ◆ na wykresie, klik = redirect do Tempo)` → `Tempo (waterfall spanów po trace_id)`.

---

## Konfiguracja — 5 elementów

1. **App — `slo:` buckets** (nie `percentiles-histogram`) w `application.yml` każdego serwisu:
   ```yaml
   management.metrics.distribution.slo.http.server.requests: 50ms,100ms,250ms,500ms,1s,2500ms,5s,10s
   ```
   8 bucketów vs ~90 domyślnych → mniejszy cardinality, niższe zużycie pamięci. Exemplary działają z obydwoma; `slo:` wystarcza.
2. **Prometheus — `--enable-feature=exemplar-storage`** (`docker-compose.yml`). Bez flagi exemplary są **ciche dropowane** przy scrape (zero błędu).
3. **Grafana — `exemplarTraceIdDestinations`** (`datasource.yml`): label `trace_id` → datasource `tempo`. To „klej" UI.
4. **Tempo — `send_exemplars: true`** w `remote_write` (`tempo.yml`): metryki RED generowane przez `span-metrics` też mają linki z powrotem do traceów.
5. **`sampling.probability: 1.0`** — każdy request ma `trace_id`, więc każdy exemplar jest klikalny.

**Zero kodu Java** — Micrometer↔OTel bridge automatycznie wstrzykuje aktywny `trace_id` do pomiaru timera.

---

## Pułapki (top 3)

- **Recording rules tracą exemplary.** `rate()`/`sum()` operują na liczbach — exemplary nie przechodzą przez ruleset. Dashboardy oparte na `recorded` metrykach nie pokażą klikalnych kropek; pytaj o oryginalne metryki lub `query_exemplars` API. → ADR-0030.
- **Summary (`percentiles:`) nie ma bucketów → nie ma exemplarów** (i nie agreguje się między instancjami). Używaj histogramów (`slo:`).
- **`sampling.probability: 0.0` → pusty `trace_id`** → link do Tempo prowadzi donikąd. Przy multi-instance Grafana pokazuje exemplary z obu replik jako osobne diamenty (exemplary nie są agregowalne — to OK).

---

## Konsekwencje

- ✅ Czas „spike p99 → root cause w trace" spada z ~5 min do ~5 s; zero nowego kodu; działa też w drugą stronę przez `tracesToMetrics` w Tempo datasource.
- ⚠️ Exemplary to próbki — nie gwarancja zobaczenia najgorszego requestu (świadomy design). Recording-rule dashboardy nie mają klikalnych kropek.
