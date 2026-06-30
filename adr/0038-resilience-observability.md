# ADR-0038 — Resilience4j observability: dashboard + alerty (odporność widzialna)

**Status:** Accepted
**Data:** 2026-06-23
**Powiązane ADR:** ADR-0014 (http-resilience — CB/retry), ADR-0030 (metrics naming), ADR-0033 (exemplars)

---

## Kontekst

ADR-0014 wprowadził circuit breaker + retry na wywołaniu `starport-registry → trade-route-planner`. Mechanizm **działał, ale był niewidoczny**: stan circuita, failure rate i liczba retry żyły wyłącznie w pamięci aplikacji. Awaria zależności degradowała planowanie tras po cichu — dokładnie wzorzec „działa ≠ działa i wiem o tym". Odporność bez metryki to ten sam problem, tylko schowany głębiej.

---

## Decyzja

Uczynić odporność widzialną — **bez nowego kodu Java**, na metrykach, które Resilience4j już eksponuje przez `actuator` + `micrometer-registry-prometheus`.

1. **Dashboard** `infra/docker/grafana/provisioning/dashboards/resilience4j-trade-route.json` (auto-provisioned, uid `r4j-trade-route`). Dwie sekcje, jeden sygnał, dwa języki:
   - *Dla biznesu:* stan zależności (DOSTĘPNA/ODCIĘTA), failure rate, downtime w zakresie, trasy oddane do fallbacku.
   - *Dla devów:* timeline stanu CB, failure/slow rate, wywołania wg wyniku, retry wg rodzaju, maks. latencja, wywołania odcięte przez otwarty circuit.
2. **Alerty** `infra/docker/prometheus/alerts.yml` (wpięte w `rule_files` w `prometheus.yml`):
   - `TradeRoutePlannerCircuitOpen` (critical, `for: 30s`) — circuit OPEN.
   - `TradeRoutePlannerHighFailureRate` (warning) — failure rate ≥ 25% (próg otwarcia: 50%).
   - `TradeRoutePlannerRetriesElevated` (info) — retry często ratuje wywołania (niestabilność zanim circuit się otworzy).

---

## Labele — kluczowa pułapka

Społecznościowy dashboard (Grafana ID 21307) filtruje po `application` i `cluster` — **tych labeli ten stack nie nadaje**. Tu identyfikacja jest taka:

| Wymiar | Label | Wartość |
|---|---|---|
| Serwis | `job` | `starport-registry` (z `prometheus.yml`, nie `spring.application.name`) |
| Replika | `instance` | `starport-registry-1:8081` / `-2:8081` |
| Circuit | `name` | `trade-route-planner` |

`cluster`/`replica` to `external_labels` Prometheusa (dla Thanosa), nie per-scrape. Dlatego dashboard jest przepisany na `job`/`instance`/`name`. Zasada: **`label_values` przed wpięciem cudzego dashboardu** (→ ADR-0030).

---

## Metryki (bez własnych liczników)

`resilience4j_circuitbreaker_state` (gauge 0/1 per `state`), `_failure_rate`, `_slow_call_rate`, `_calls_seconds_count{kind}`, `_calls_seconds_max`, `_not_permitted_calls_total`, `resilience4j_retry_calls{kind}`. Dodatkowo app-level `reservations_route_plan_errors_total{errorType="circuit_open"}` z fallbacku (jedyny ręczny licznik, już istniał).

---

## Pułapki (top 3)

- **`failure_rate = -1`** dopóki nie zbierze się `minimum-number-of-calls` (5). Panele mapują `-1 → "—"`; pre-warm ruchem przed demem/odczytem.
- **`resilience4j_retry_calls` to gauge kumulatywny**, nie counter — pokazujemy wartość/`increase()`, nie `rate()` jak na counterze.
- **Brak Alertmanagera** w stacku — alerty widać w Prometheus UI (`/alerts`) i Grafanie, ale routing notyfikacji (Slack/PagerDuty) to osobny krok prod (świadomie poza zakresem).

---

## Demo chaos — Toxiproxy (eskalacja: slow → partial → down)

Żeby pokazać, że odporność łapie **degradację**, nie tylko twardy zgon (zarzut „`docker stop` to ściema"), demo wstrzykuje awarię stopniowo przez Toxiproxy zamiast zabijać kontenery:

- **`slow-call-rate-threshold: 50` + `slow-call-duration-threshold: 500ms`** dodane do CB (`application.yml`) — wolne wywołania (poniżej read-timeoutu 800ms) otwierają circuit po slow-call, ożywiając panel `slow_call_rate`. To jedyna zmiana **produkcyjna**.
- **Wpięcie (tylko profil `demo`):** `app.trade-route-planner.load-balanced=false` (`RestClientConfig`) odpina @LoadBalanced **tylko dla klienta plannera** i kieruje go na `http://toxiproxy:8666`; discovery globalnie zostaje włączone, więc starport dalej rejestruje się w Eurece. Overlay `infra/docker/docker-compose.demo.yml` dokłada kontener `toxiproxy` (upstream `trade-route-planner-1`, API `:8474`).
- **Sterowanie:** `demo-resilience.ps1 -Chaos slow|partial|down|heal`. Twardy wariant bez Toxiproxy (`kill`/`restore` = `docker stop`/`start`) zostaje jako zapas.

---

## Konsekwencje

- ✅ Awaria zależności jest widoczna w ≤1 min (alert) zamiast „odkrywana przypadkiem"; czytelna i dla devów, i dla biznesu. Dashboard+alerty: zero kodu Java; demo-chaos: jeden property-switch + slow-call config.
- ✅ Demo/runbook (`scripts/DEMO-resilience.md`, `demo-resilience.ps1`, `preflight-demo.ps1`) odtwarzalne na żywym stacku — z overlayem `docker-compose.demo.yml` (Toxiproxy).
- ⚠️ Dashboard/alerty pokrywają jeden circuit (`trade-route-planner`). Kolejne zależności wymagają dodania serii (panele są sparametryzowane zmienną `circuit_name`, więc skalują się automatycznie po dodaniu nowych instancji CB).
