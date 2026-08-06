# Talk: „Działa ≠ działa i ja o tym wiem"

Materiały do prezentacji o uczynieniu odporności widzialną
(Resilience4j + Micrometer + Grafana na żywym mikroserwisie).
Budżet: **20 min** + Q&A.

> **⚠️ Komendy `.ps1` w tym pliku są podane w formie dla `cmd` (Wiersz polecenia)** — z przedrostkiem `powershell -ExecutionPolicy Bypass -File`, bo `cmd` sam z siebie otworzyłby skrypt w Notatniku zamiast go uruchomić. Jeśli wolisz okno **PowerShell**, pomiń przedrostek i wołaj po prostu `.\scripts\<skrypt>.ps1 ...`. Komendy `docker` / `npx` / `curl` działają w obu.


## 🔧 Słownik komend — co dokładnie robi każda

### `docker compose -f <plik1> -f <plik2> up -d --build`
Uruchamia cały stack złożony z dwóch plików konfiguracyjnych.
| Część | Znaczenie |
|---|---|
| `docker compose` | narzędzie uruchamiające wielokontenerową aplikację wg plików YAML |
| `-f infra/docker/docker-compose.yml` | **1. plik = bazowy stack**: gateway, eureka, postgres, kafka, 2× starport, 2× planner, obserwowalność. `-f` = „file" (który plik wczytać) |
| `-f infra/docker/docker-compose.demo.yml` | **2. plik = OVERLAY demo, nakładany na pierwszy.** Compose **scala** oba: overlay dokłada kontener `toxiproxy` i przełącza starport na profil `demo` (klient plannera leci przez proxy). Późniejszy `-f` wygrywa przy konflikcie |
| `up` | utwórz i uruchom kontenery |
| `-d` | „detached" — w tle (oddaje terminal, nie blokuje) |
| `--build` | przed startem przebuduj obrazy z Dockerfile (łapie zmiany w kodzie; bez tego użyje starych) |

> **Dlaczego DWA `-f`?** Bazowy stack nie zna Toxiproxy. Overlay demo dokłada go „na wierzch" tylko na czas talku — bez overlaya (`-f docker-compose.yml` sam) system działa normalnie (LB + Eureka, bez proxy). Kolejność `-f` jest istotna: drugi plik nadpisuje pierwszy.

### `docker ps --format "table {{.Names}}\t{{.Status}}"`
Lista **działających** kontenerów. `--format` = własny układ wyjścia (szablon Go): `{{.Names}}` nazwa, `{{.Status}}` stan, `\t` tabulator. Czekaj, aż wszystkie pokażą `Up` / `healthy`.

### `powershell -ExecutionPolicy Bypass -File .\scripts\preflight-demo.ps1 -Full`
Skrypt PowerShell z repo (`.\` = bieżący katalog). `-Full` = tryb pełny: realnie **zatrzymuje repliki plannera, generuje ruch, czeka aż circuit przejdzie w OPEN i alert w FIRING, po czym przywraca**. Bez `-Full` — tylko szybkie sprawdzenie endpointów i metryk. **Exit 0 = wszystko zielone.**

### `powershell -ExecutionPolicy Bypass -File .\scripts\demo-resilience.ps1 -DurationMinutes 30`
Generator **zdrowego ruchu**: wysyła rezerwacje (POST `/api/v1/starports/.../reservations`, `requestRoute=true`) przez gateway, ~4 req/s, przez 30 min. To **pre-warm** — wypełnia okno ślizgowe CB (`minimum-number-of-calls: 5`), inaczej failure rate pokaże „—".

### `powershell -ExecutionPolicy Bypass -File .\scripts\demo-resilience.ps1 -Chaos <tryb>` (Terminal B — przełącznik awarii)
| Tryb | Co robi pod spodem |
|---|---|
| `slow` | toxic `latency` na 100% wywołań; próg sterujesz flagą **`-SlowMs`** (dom. 600). **`-SlowMs 300`** = pod progiem slow-call (500 ms) → latencja ↑, ale `slow_call_rate` 0% → **breaker TRZYMA** (szara strefa). **`-SlowMs 600`** = nad progiem → `slow_call_rate` rośnie do progu **50%** → **circuit OPEN na slow-call, bez błędów**. Zawsze < read-timeout 800 ms, więc wywołania *udane* |
| `partial` | toxic `timeout`, toxicity 0.5 → ~50% wywołań wisi aż klient zerwie po 800 ms → błąd → **retry pracuje** |
| `down` | wyłącza proxy (`enabled:false`) → connection refused → pełna awaria → **circuit OPEN** |
| `heal` | usuwa toxyny i włącza proxy → powrót: **half_open → closed** |
| `status` | pokazuje stan replik + proxy/toxynów Toxiproxy |
| `kill` / `restore` | **wariant twardy bez Toxiproxy**: `docker stop` / `docker start` obu replik plannera (działa zawsze) |

> **`-SlowMs` — różnica 300 vs 600 to JEDYNA zmiana między Krokiem 1a a 1b demo.** Ten sam tryb `slow`, inna liczba:
> ```powershell
> # Krok 1a — breaker TRZYMA (300 ms jest POD progiem 500 ms):
> powershell -ExecutionPolicy Bypass -File .\scripts\demo-resilience.ps1 -Chaos slow -SlowMs 300
> # Krok 1b — breaker OTWIERA (600 ms jest NAD progiem 500 ms):
> powershell -ExecutionPolicy Bypass -File .\scripts\demo-resilience.ps1 -Chaos slow -SlowMs 600
> ```
> Bez flagi skrypt bierze **600 ms** (domyślka, linia 59 skryptu) — czyli samo `-Chaos slow` = wariant 1b. Trzy progi do zapamiętania: **300 < 500 (slow-call) < 600 < 800 (read-timeout)**.

### `docker compose -f <plik1> -f <plik2> down`
`down` = zatrzymuje i **usuwa** kontenery + sieć utworzone przez `up`. Te same dwa `-f`, żeby objąć też toxiproxy z overlaya. Dane są w tmpfs (ephemeral), więc znikają — to OK.

### Alerty — gdzie są zdefiniowane
- **Repo:** `infra/docker/prometheus/alerts.yml` → grupa **`starport_registry_resilience4j`**. W kontenerze montowane jako **`/etc/prometheus/alerts.yml`** (patrz `prometheus.yml`).
- **UI:** http://localhost:9090/alerts?search= (filtr po nazwie). Brak Alertmanagera — alerty żyją w Prometheus UI / Grafanie, to wystarcza na demo.
- **Reguły w tej grupie:** `TradeRoutePlannerCircuitOpen` (circuit OPEN, `for: 30s`, severity critical) · `TradeRoutePlannerHighFailureRate` (failure_rate ≥ 25%, `for: 1m`). Labele: `job="starport-registry"`, `name="trade-route-planner"`.

### `Ctrl+C` (w Terminalu A)
Przerywa pętlę generatora zdrowego ruchu.


## Pliki

| Plik | Rola |
|---|---|
| **`RUNBOOK-scena.md`** | **Instrukcja sceniczna krok-po-kroku: co mówisz / jak / którą komendę kiedy. Główny dokument na scenę.** |
| `resilience-talk.md` | Slajdy (Marp). 24 punkty w aktach 0–5, notatki prelegenta, timing. |
| `QA-cheatsheet.md` | Ściąga na trudne pytania sali. Trzymaj otwarte na drugim ekranie. |
| `../scripts/DEMO-resilience.md` | Runbook demo: drabina Toxiproxy (slow→partial→down→heal) + przygotowanie stacku. |
| `../scripts/preflight-demo.ps1` | Smoke test — odpal PRZED sceną. |
| `../scripts/demo-resilience.ps1` | Generator ruchu + przełącznik awarii. |

---

## Renderowanie slajdów

**Najszybciej — wszystkie 3 materiały naraz** (talk + oba Q&A), z fallbackiem npx → marp:
```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\render-slides.ps1                  # -> PDF
powershell -ExecutionPolicy Bypass -File .\scripts\render-slides.ps1 -Format html     # -> HTML
```

Ręcznie, plik po pliku (wymaga Node.js — Marp pobierze się przez `npx`):

```powershell
# PDF (do wysłania / backup offline)
npx @marp-team/marp-cli@latest resilience-talk.md -o resilience-talk.pdf --allow-local-files --no-stdin

# HTML (samodzielny plik do przeglądarki)
npx @marp-team/marp-cli@latest resilience-talk.md -o resilience-talk.html --allow-local-files --no-stdin

# Podgląd na żywo z auto-reloadem (podczas pracy nad slajdami)
npx @marp-team/marp-cli@latest -p -w resilience-talk.md --allow-local-files
```

> **Dwie flagi, bez których render potrafi się wywalić:**
> - `--allow-local-files` — deck osadza lokalny obraz `circuit-breaker-biznes.svg` (slajd „jak działa circuit breaker"); bez niej Marp go nie wczyta.
> - `--no-stdin` — w powłoce nieinteraktywnej (skrypt, CI, tło) Marp bez niej **czeka na wejście ze stdin i wisi w nieskończoność**. Zawsze dodawaj przy renderze wsadowym.
>
> **Zamknij otwarty `resilience-talk.pdf` w czytniku przed renderem** — inaczej zapis pada na `EBUSY: resource busy or locked`.
> Gdy `npx …@latest` długo „myśli" (odpytuje sieć o wersję przy każdym uruchomieniu), zainstaluj Marpa raz globalnie: `npm i -g @marp-team/marp-cli`, potem wołaj po prostu `marp …`.

**Notatki prelegenta** = komentarze HTML w `resilience-talk.md`. Widać je w trybie
presenter: otwórz HTML/PDF w przeglądarce i naciśnij **`p`** (osobne okno z notatkami,
timerem i następnym slajdem).

> Alternatywnie: rozszerzenie **Marp for VS Code** daje podgląd bez `npx`.

---

## Kolejność na scenie (checklista)

### T-15 min — przygotowanie (zanim wejdziesz)
```powershell
# 1. Podnieś stack Z OVERLAYEM DEMO (dokłada toxiproxy + profil demo na starport)
docker compose -f infra/docker/docker-compose.yml -f infra/docker/docker-compose.demo.yml up -d --build
#   -f docker-compose.yml       = bazowy stack (gateway, eureka, postgres, kafka, starport×2, planner×2, obserwowalność)
#   -f docker-compose.demo.yml  = OVERLAY na bazowy → dokłada Toxiproxy + profil demo. Drugi -f nadpisuje pierwszy.
#   up -d --build               = uruchom w tle (-d), przebuduj obrazy (--build)

# 2. Smoke test — realnie otwiera/zamyka circuit i sprawdza alert; kod wyjścia 0 = zielono
powershell -ExecutionPolicy Bypass -File .\scripts\preflight-demo.ps1 -Full
```
> Pełny rozbiór wszystkich komend i flag → `RUNBOOK-scena.md`, sekcja **„🔧 Słownik komend"**.
- [ ] `preflight-demo.ps1 -Full` zwrócił **0** (usługi, dashboard, metryki, alerty, CB realnie się otwiera)
- [ ] Grafana otwarta: http://localhost:3000 → dashboard **„Resilience4j — starport→trade-route"**
- [ ] Prometheus alerty otwarte w drugiej karcie: http://localhost:9090/alerts
- [ ] `QA-cheatsheet.md` otwarte na drugim ekranie
- [ ] Backup: PDF slajdów + zrzuty ekranu stanów CB (zielony / OPEN / half_open) — na wypadek padu demo
- [ ] **Pre-warm:** zostaw zdrowy ruch lecący w tle (Terminal A — niżej), żeby okno ślizgowe było zapełnione (inaczej failure rate = „—")

### T-2 min — uruchom zdrowy ruch (Terminal A, zostaw lecący)
```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\demo-resilience.ps1 -DurationMinutes 30
```
To jest **właściwy generator pre-warm**: wysyła rezerwacje z `requestRoute: true`, więc
realnie wołają trade-route-planner i **karmią circuit breaker + retry** — dokładnie te
panele, które pokazujesz. Po ~2 min okno ślizgowe CB jest pełne świeżych `successful`,
failure rate spada do ~0, a cold-startowe maksima latencji wypływają z okna.

> **Nie używaj do pre-warmu `load-test-timed.ps1`** — on wysyła `requestRoute: false`,
> więc grzeje ścieżkę rezerwacji i dashboard zajętości doków, ale **circuit breakera nie
> dotyka** (panele odporności zostają suche). To dobry ogólny test systemu, nie rozgrzewka
> tego demo.

### Przebieg talku

| Czas | Akt | Co robisz | Slajdy |
|---|---|---|---|
| ~2 min | **0 — Wejście** | Hipotetyczne otwarcie + „ręka w górę" | „Wyobraźcie sobie" |
| ~3 min | **1 — Refleksja** | 3 pytania, wypisz hipotezy sali **na flipcharcie** | „A może powinniśmy..." |
| ~3 min | **2 — Historia** | 3 miesiące w zły adres → odkryte przypadkiem | „3 miesiące", „pewność?" |
| ~6 min | **3 — Sedno** | awaria widoczności → retry = czarna skrzynka → metryki za darmo → realny kod | od „To nie była awaria retry" |
| ~4 min | **4 — DEMO** | patrz niżej ⬇ | „Demo", „Alert domyka pętlę" |
| ~2 min | **5 — Domknięcie** | wróć do flipchartu, odhacz; zdanie-klamra; konkret na pn. | od „Wróćmy do waszej listy" |

### Demo na żywo (AKT 4) — sekwencja terminala B
```powershell
# 1. Pokaż dashboard ZIELONY. "A teraz wyobraźcie sobie, że tego ekranu NIE ma."

# 2. Drabina eskalacji (Terminal B) — degradacja zanim twardy zgon:
powershell -ExecutionPolicy Bypass -File .\scripts\demo-resilience.ps1 -Chaos slow       # latency ~600ms -> slow_call_rate ↑
powershell -ExecutionPolicy Bypass -File .\scripts\demo-resilience.ps1 -Chaos partial    # ~50% timeout -> failure rate ↑, retry ↑
powershell -ExecutionPolicy Bypass -File .\scripts\demo-resilience.ps1 -Chaos down       # pełna awaria -> circuit OPEN, fallback
#    -> "Te dwa błędy na dobę z mojej historii — byłyby tutaj."

# 3. Pokaż alert FIRING: http://localhost:9090/alerts -> TradeRoutePlannerCircuitOpen
#    "Nigdy więcej odkrywania awarii przez przypadek."

# 4. Przywróć zależność (Terminal B):
powershell -ExecutionPolicy Bypass -File .\scripts\demo-resilience.ps1 -Chaos heal
#    -> po ~10s: timeline half_open (pomarańcz) -> closed (zielony). System sam wraca.
#    (twardy wariant bez toxiproxy: -Chaos kill / -Chaos restore = docker stop/start replik)
```

### Po talku
- [ ] Zatrzymaj generator ruchu (Ctrl+C w Terminalu A)
- [ ] Q&A — `QA-cheatsheet.md` pod ręką
- [ ] (opcjonalnie) zatrzymaj i usuń stack — **oba `-f`**, żeby objąć też toxiproxy z overlaya:
  ```powershell
  docker compose -f infra/docker/docker-compose.yml -f infra/docker/docker-compose.demo.yml down
  #   down = zatrzymuje I usuwa kontenery + sieć. Dane w tmpfs (ephemeral) znikają — to OK.
  ```

---

## Plan B (gdy demo na żywo padnie)
1. Zrzuty ekranu stanów CB w slajdach — przełącz na nie i opowiadaj.
2. Albo nagranie ekranu z przebiegu próbnego (`preflight-demo.ps1 -Full` to dobry moment, żeby je nagrać).
3. Najgorszy scenariusz: opisz słownie + pokaż PromQL alertu na slajdzie „Alert domyka pętlę".

## Najczęstsza pułapka
**`failure_rate` pokazuje „—" zamiast liczby** → okno ślizgowe niezapełnione
(`minimum-number-of-calls: 5`). Rozwiązanie: pre-warm ruchem (Terminal A) na ≥1 min przed demem.
