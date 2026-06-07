# Grafana dashboard review — MicroservicesFleet

Ocena 5 dashboardów (`infra/docker/grafana/provisioning/dashboards/`) przez pryzmat wykładu
„Grafana Dashboards — wartość biznesowa i anatomia błędów średnich" (RED / percentyle / SLI-SLO /
dobór paneli / PromQL). Dwie perspektywy oceniającego: **surowy senior od observability** (czy
wykres jest technicznie poprawny i czy w ogóle ma dane) i **CEO** (czy ten ekran odpowiada na
pytanie, które płaci rachunki).

## Werdykt zbiorczy

| Dashboard | Persona (poziom) | Werdykt | Jednozdaniowo |
|---|---|---|---|
| `business-revenue-executive` | Klient / CEO (L1) | **KEEP** | Jedyny prawdziwie biznesowy ekran. Dobór paneli wzorcowy. Drobne poprawki. |
| `exemplars-success-error` | Dev on-call (L2) | **KEEP** | Czysty RED z drill-downem. Najlepszy technicznie. Brakuje mu tylko progów SLO. |
| `distributed_tracing` | Dev / SRE (L2) | **KEEP-WITH-FIXES** | Wartościowy (Tempo/Loki/span-metrics), ale `Avg latency` kłamie i brak zmiennych. |
| `resources_use` | SRE / ops (L3) | **KEEP-WITH-FIXES** | Poprawna metoda USE, ale **GC Pause p99 to martwy wykres** (brak bucketów). |
| `logs_traces_metrics` | „wszystko dla wszystkich" | **SPLIT / DELETE** | 3408 linii, anty-wzorzec „feel-good 30 paneli". Duplikuje pozostałe cztery. |
| `slo-error-budget` ✨ | Klient/CEO + SRE (L1) | **NEW / DODANY** | Domykał brakujące „czy dotrzymujemy obietnicy". Availability/latency SLO 30d + error budget + burn-rate. |

## Trzy ustalenia ponad pojedynczymi dashboardami

1. **Dashboard SLO / error-budget — ✅ ZAIMPLEMENTOWANY** (`slo-error-budget.json`). To była największa
   luka: mieliśmy RED (rate/errors/duration) w kilku miejscach, ale nigdzie odpowiedzi na pytanie nr 1
   CEO: *„czy w tym miesiącu dotrzymaliśmy obietnicy?"*. Teraz jest: availability 30d, error budget
   remaining, latency <500ms, lost reservations, burn-rate i top konsumenci budżetu. Pozostało
   **sformalizować SLO w ADR** i dodać alert na burn-rate. → szczegóły w `00-MISSING-slo-dashboard.md`.

2. **Histogramy istnieją tylko dla metryk z `distribution.slo:`.** W tym projekcie świadomie
   usunięto `percentiles-histogram` i percentyle client-side (słuszna decyzja — patrz ADR/komentarze
   w `application.yml`). Skutek: `histogram_quantile()` zwraca dane **wyłącznie** dla metryk
   wymienionych w `slo:`. Dwa panele łamią tę zasadę i są **puste** (potwierdzone w kodzie i configu):
   - `logs_traces_metrics` → *Route Risk Score p99* — `routes.risk.score` to `DistributionSummary`
     bez histogramu (`PlanRouteService.java:55`) i nie ma go w `slo:` → brak `routes_risk_score_bucket`,
   - `resources_use` → *GC Pause p99* — `jvm.gc.pause` nie ma w `slo:`, a w repo brak `MeterFilter`/
     `publishPercentileHistogram` → brak `jvm_gc_pause_seconds_bucket`.
   Weryfikacja na żywym stacku: `curl -s http://localhost:9090/api/v1/label/__name__/values | tr ',' '\n' | grep -E 'risk_score|gc_pause'`.

3. **Masywne nakładanie się.** `logs_traces_metrics` zawiera w sobie ~80% pozostałych czterech
   dashboardów (Executive row ≈ `business-revenue`, rows USE id=500/510/520 ≈ `resources_use`,
   latencje ≈ `distributed_tracing`). Z perspektywy CEO to koszt utrzymania bez wartości: cztery
   źródła prawdy o tej samej liczbie → przy zmianie metryki trzeba edytować w wielu miejscach,
   a on-call nie wie, na który ekran patrzeć. Jeden dashboard = jedna persona = jedno pytanie.

## Pozytywy, które warto utrzymać (nie psuć przy refaktorze)

- Wszędzie `$__rate_interval` zamiast sztywnego `[5m]` — Grafana sama dobiera okno ≥ 4× scrape. Zgodne z wykładem.
- `sum/sum` (ważona) zamiast `avg/avg` tam, gdzie liczona jest średnia latencja — poprawnie.
- `histogram_quantile(..., sum by (le) (...))` w `exemplars` i `distributed_tracing` — wzorcowo.
- `business-revenue` używa BarGauge/Stat/Gauge/Table zgodnie z naturą danych (kategorie vs jedna wartość vs drill-down) — dobór paneli książkowy.
- Decyzja `slo:`-zamiast-`percentiles` świadomie rozwiązuje problem „percentyli nie da się uśredniać między instancjami".

Szczegóły per dashboard — w plikach obok.
