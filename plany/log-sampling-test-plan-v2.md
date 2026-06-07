# Plan testu — dowód poprawności samplingu logów (v2)

**MicroservicesFleet · 2026-06-07 · ADR-0035 (zastępuje 0034)**
Weryfikuje pipeline `OTLP push → Alloy → Loki`.

Aplikacje pushują logi przez `OpenTelemetryAppender`; Alloy **nie scrapuje stdoutu apek**.
Sampling jest **deterministyczny per cały traceId** (`probabilistic_sampler mode=equalizing`),
nie per-linia. Test ~10 min.

> **Powłoka: `cmd.exe`** (zwykły wiersz poleceń `C:\…>`). Wszystkie komendy poniżej są pod cmd —
> przetestowane na żywym stacku. Reguły, które tu obowiązują:
> - cudzysłowy wewnątrz LogQL escapujemy jako `\"` (np. `job=\"starport-registry\"`),
> - `findstr` zamiast PowerShellowego `Select-String`,
> - jeden `|` w regexie (np. `WARN|ERROR`) wypada poza śledzeniem cudzysłowów cmd, więc piszemy go
>   jako `^|`,
> - `curl.exe` (nie samo `curl`).
>
> Wolisz PowerShell? Równoważny runbook: `infra\docker\alloy\VERIFY-LOGGING.md`.

> **Czym ten test NIE jest.** Wersja ADR-0034 liczyła linie JSON `"level":"INFO"` na stdout
> (`findstr /c:"\"level\":\"INFO\"" | Measure-Object`). To **nieaktualne**: stdout apek jest
> **celowo zwykłym tekstem** (wzorzec Spring Boota, „dla `docker logs`"), `logstash-logback-encoder`
> usunięty, a Alloy stdoutu apek nie czyta. Dowód samplingu robimy **po stronie Loki**, na
> atrybutach OTel. (Plus `Measure-Object` to cmdlet PowerShella — w cmd i tak nie zadziała.)

---

## Krok 0 · Setup

```
cd C:\softwareEngineer\MicroservicesFleet\infra\docker
docker compose down -v
docker compose up -d --build
```

Poczekaj aż wszystko będzie healthy (apki ~1–2 min po starcie Eureki — rejestracja w LB):

```
docker compose ps
```

✅ **PASS:** 16 kontenerów, wszystkie `Up … (healthy)` (poza `kafka-ui`/`prometheus`, które nie mają
healthchecka — wystarczy `Up`).

> **Tip buildowy:** nie przepuszczaj `up --build` przez `| more`/`| findstr` — pipe maskuje exit code
> Dockera. Jeśli build pada na `Premature end of Content-Length` z Maven Central, to przejściowy błąd
> sieci — ponów; Dockerfile'e mają już `MAVEN_OPTS` z retry. Po nieudanym buildzie sprzątnij
> osierocone kontenery: `docker compose down --remove-orphans`.

---

## Krok 1 · Apki nie krzyczą na OTLP (wersje OTel)

```
docker logs eureka 2>&1 | findstr /c:"AbstractMethodError" /c:"Started EurekaServerApplication"
```

✅ **PASS:** widać `Started EurekaServerApplication`, **zero** `AbstractMethodError`. Powtórz dla
`api-gateway`, `starport-registry-1`, `trade-route-planner-1`, `telemetry-pipeline-1`.

❌ **FAIL:** `AbstractMethodError` przy starcie = appender OTLP wyprzedził SDK (api-incubator drift).
Wyrównaj wersje przez `instrumentation-bom-alpha` (2.15.0-alpha ↔ core 1.49.0 dla Spring Boot 3.5.6)
i powtórz import w `api-gateway` + `eureka-server` (nie dziedziczą gt-parent).

Opcjonalnie — wyrównane wersje wbite w obraz (runtime to `jre-alpine`, **nie ma narzędzia `jar`** —
używamy `unzip` wewnątrz kontenera):

```
docker run --rm --entrypoint sh microservices_fleet-api-gateway:latest -c "unzip -l /app/app.jar | grep -E 'incubator|sdk-logs'"
```

✅ **PASS:** `api-incubator-1.49.0-alpha` i `sdk-logs-1.49.0` — ten sam pociąg 1.49 (nie 1.50).

---

## Krok 2 · Logi docierają do Loki właściwą drogą

```
curl.exe -s http://localhost:3100/loki/api/v1/label/job/values
curl.exe -s http://localhost:3100/loki/api/v1/label/container/values
```

✅ **PASS:** `job` → 5 apek (`api-gateway, eureka-server, starport-registry, trade-route-planner,
telemetry-pipeline`); `container` → tylko infra (`kafka, postgres, tempo, loki, grafana, alloy,
prometheus, kafka-ui`). To dowód, że apki idą OTLP, infra docker-scrape, i nic się nie dubluje.

❌ **FAIL:** apka w `container` = `discovery.relabel` w `config.alloy` jej nie odrzuca
(regex `action=drop`). Apka brak w `job` = OTLP nie dochodzi (sprawdź
`OTEL_EXPORTER_OTLP_LOGS_ENDPOINT` i czy appender się zainstalował — Krok 1).

---

## Krok 3 · Wygeneruj kontrolowany ruch (200 trace'ów)

Wklej jako jedną linię (w wierszu poleceń `%i`; w pliku `.bat` użyj `%%i`):

```
for /l %i in (1,1,200) do @curl.exe -s -o NUL -X POST "http://localhost:8080/api/v1/starports/TATOO/reservations" -H "Content-Type: application/json" -d "{\"customerCode\":\"C%i\",\"shipCode\":\"S%i\",\"shipClass\":\"FREIGHTER\",\"startAt\":\"2026-09-01T10:00:00Z\",\"endAt\":\"2026-09-01T12:00:00Z\",\"requestRoute\":false}"
```

Każdy request = 1 trace + 1 log `INFO "Received reservation create request"` (zwraca 404 — INFO
leci wcześniej). Każdy ma unikalny traceId, więc liczba zachowanych INFO = liczba zachowanych
trace'ów. Odczekaj ~15 s na flush batcha OTLP. Daty `startAt`/`endAt` muszą być w przyszłości.

---

## Krok 4 · INFO samplowane do ~10%, per cały trace (kluczowy dowód)

```
curl.exe -s -G http://localhost:3100/loki/api/v1/query --data-urlencode "query=count_over_time({job=\"starport-registry\"} |= \"Received reservation create request\" [25m])"
```

Odczytaj `N` z `"value":[…,"N"]`.

| Metryka | Wartość | Oczekiwane |
|---|---|---|
| Wysłane (źródło) | 200 | — |
| Zachowane INFO w Loki | `N` | **~20** (10%; przy n=200 norma to ~14–26, ±wariancja binomialna p=0,1) |

Potwierdź próg samplera na **traced** rekordzie (DEBUG/INFO z traceId):

```
curl.exe -s -G http://localhost:3100/loki/api/v1/query_range --data-urlencode "query={job=\"starport-registry\", level=\"DEBUG\"}" --data-urlencode "limit=1" | findstr /c:"sampling.threshold"
```

✅ **PASS:** ratio `N`/200 ≈ 0,10 oraz w wyniku widać `"sampling.threshold":"e666"` → 0xE666 ≈ 0,9 →
drop 90% / keep 10%.

❌ **FAIL:** ratio ≈ 1,0 → sampler się nie aktywował (sprawdź `otelcol.processor.probabilistic_sampler`
w `config.alloy`: `sampling_percentage=10`, `attribute_source="traceID"`, oraz czy gałąź `info_down`
faktycznie tam kieruje). Ratio ≪ 0,10 → sampler za agresywny lub brak `fail_closed=false`.

> **Niuans, który myli:** zapytanie `{level=\"INFO\"} limit=1` potrafi zwrócić
> `"sampling.threshold":"0"` — to log **bez traceId** (startowy/@Scheduled), poprawnie przepuszczony
> przez `fail_closed=false` (threshold 0 = nigdy nie odrzucaj). Próg `e666` niosą tylko logi traced —
> dlatego sprawdzamy go na DEBUG, nie na losowym INFO.

---

## Krok 5 · WARN/ERROR = 100% (omijają sampler)

WARN nie niesie progu samplera:

```
curl.exe -s -G http://localhost:3100/loki/api/v1/query_range --data-urlencode "query={level=\"WARN\"}" --data-urlencode "limit=1" | findstr /c:"sampling.threshold"
```

✅ **PASS:** **brak wypisanej linii** (findstr nic nie znalazł) → WARN ominął sampler (gałąź
`warn_up`, severity_number ≥ 13). Dla kontrastu INFO/DEBUG (Krok 4) *niosą* próg.

WARN/ERROR obecne w każdej apce — 5 jawnych linii (pipe w `WARN^|ERROR` escapowany przez `^|`;
okno `[3h]`, bo WARN/ERROR lecą głównie przy starcie):

```
curl.exe -s -G http://localhost:3100/loki/api/v1/query --data-urlencode "query=sum(count_over_time({job=\"api-gateway\"} | json | severity=~\"WARN^|ERROR\" [3h]))"
curl.exe -s -G http://localhost:3100/loki/api/v1/query --data-urlencode "query=sum(count_over_time({job=\"eureka-server\"} | json | severity=~\"WARN^|ERROR\" [3h]))"
curl.exe -s -G http://localhost:3100/loki/api/v1/query --data-urlencode "query=sum(count_over_time({job=\"starport-registry\"} | json | severity=~\"WARN^|ERROR\" [3h]))"
curl.exe -s -G http://localhost:3100/loki/api/v1/query --data-urlencode "query=sum(count_over_time({job=\"trade-route-planner\"} | json | severity=~\"WARN^|ERROR\" [3h]))"
curl.exe -s -G http://localhost:3100/loki/api/v1/query --data-urlencode "query=sum(count_over_time({job=\"telemetry-pipeline\"} | json | severity=~\"WARN^|ERROR\" [3h]))"
```

✅ **PASS:** każda linia zwraca licznik > 0 w `"value":[…,"N"]`.

❌ **FAIL:** WARN niesie `sampling.threshold` (Krok 5 zwraca linię) albo licznik = 0 → błąd selektora
severity w `config.alloy` (próg `severity_number < 13` w `filter.warn_up` / `≥ 13` w `info_down`).
Pusty wynik mimo poprawnego configu = WARN/ERROR wypadły z okna; poszerz `[3h]` lub zrestartuj apkę.

---

## Krok 6 · Cały trace, nie pojedyncze linie

cmd nie wyciągnie traceId automatycznie — zrób to dwuetapowo. Najpierw podejrzyj jeden traceId:

```
curl.exe -s -G http://localhost:3100/loki/api/v1/query_range --data-urlencode "query={job=\"starport-registry\"} |= \"Received reservation create request\"" --data-urlencode "limit=1" | findstr /c:"traceid"
```

Skopiuj 32-znakowy hex z pola `"traceid":"…"`, wklej do zmiennej i policz wszystkie jego linie:

```
set TID=tu-wklej-32-hex
curl.exe -s -G http://localhost:3100/loki/api/v1/query --data-urlencode "query=sum(count_over_time({job=~\".+\"} |= \"%TID%\" [30m]))"
```

✅ **PASS:** licznik **> 1** — zachowany trace trzyma *wszystkie* swoje linie (różne serwisy/poziomy),
a nie losowy podzbiór. To jest sedno różnicy względem ADR-0034 (per-linia rwało trace na strzępy).

---

## Krok 7 · Logi bez traceId są zachowane (`fail_closed=false`)

```
curl.exe -s -G http://localhost:3100/loki/api/v1/query --data-urlencode "query=count_over_time({job=\"starport-registry\"} |= \"Initializing Servlet\" [1h])"
```

✅ **PASS:** licznik ≥ 1 — log bez traceId nie został zdropniony. `fail_closed=false` przepuszcza brak
randomness zamiast traktować go jako błąd (inaczej zniknęłyby wszystkie boot/@Scheduled INFO+DEBUG).

---

## Bonus · Sanity: stdout to tekst (celowo)

```
docker logs starport-registry-1 --tail 3
```

✅ **PASS:** linie typu `2026-… INFO 1 --- [starport-registry] …` — zwykły wzorzec Spring Boota.
**To NIE jest błąd:** ADR-0035 świadomie zostawia stdout tekstowy dla debugowania, a transport do
Loki idzie OTLP-em. Jeśli ktoś szuka tu JSON-a `"level":"INFO"` — pracuje wg starego (martwego)
planu 0034.

---

## Sygnał regresji w przyszłości

Alert na zaniku samplingu mimo ruchu (na metryce `otelcol` zamiast `loki_process`):

```promql
sum(rate(otelcol_processor_probabilistic_sampler_count_logs_sampled{policy="info_logs"}[5m])) == 0
for 30m
```

Jeśli przez 30 min nic nie jest samplowane mimo aktywnego ruchu: (a) apki przestały logować INFO/DEBUG,
(b) gałąź `info_down` nie kieruje do samplera, albo (c) ktoś podbił `sampling_percentage` do 100.
Sprawdź też metryki receivera OTLP (`otelcol_receiver_accepted_log_records`) — zero = appender odpadł
(patrz Krok 1).

---

| # | Sprawdzenie | Pass |
|---|---|---|
| 0 | 16 kontenerów `Up (healthy)` | ✅ |
| 1 | Brak `AbstractMethodError`, apka wstała | ✅ |
| 2 | 5 jobów (OTLP) + infra w `container` (scrape), bez nakładania | ✅ |
| 4 | INFO zachowane ≈ 10%; próg `e666` na traced | ✅ |
| 5 | WARN/ERROR bez `sampling.threshold`; obecne we wszystkich apkach | ✅ |
| 6 | Zachowany trace ma > 1 linię | ✅ |
| 7 | Log startowy bez traceId obecny | ✅ |

*Decyzja architektoniczna:* `adr/0035-otlp-logs-deterministic-sampling.md`.
*Runbook źródłowy (PowerShell):* `infra/docker/alloy/VERIFY-LOGGING.md`.
*Config pipeline'u:* `infra/docker/alloy/config.alloy`.
