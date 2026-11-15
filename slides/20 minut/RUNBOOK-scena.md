# RUNBOOK SCENICZNY — „Działa ≠ działa i ja o tym wiem"

> **Zgrany z deckiem:** `Działa-nie-równa-się-działa_TED.pptx` (22 slajdy). Numeracja slajdów w tym runbooku odpowiada slajdom w tym pliku — architektura jest **przed** kodem, a circuit breaker i alert mają własne slajdy (8 i 17). Mapa slajdów → sekcja 6.

> Legenda w każdym kroku:
> **🗣 MÓWISZ** — gotowy tekst. Fragmenty z ⚓ mów DOSŁOWNIE (to kręgosłup), resztę możesz parafrazować — ale tempo i pauzy zostaw.
> **🎭 JAK** — sposób podania (pauza, ręka w górę, tempo, gest).
> **🎬 ROBISZ** — akcja: następny slajd / komenda w terminalu / przełącz okno / flipchart.
> **📊 NA WYKRESIE** (tylko w demie) — który panel i jaka liczba; pokazuj palcem to, o czym mówisz.

> **⚠️ Uruchamiasz z `cmd` (Wiersz polecenia), nie z PowerShell.** Dlatego każda komenda `.ps1` ma przedrostek `powershell -ExecutionPolicy Bypass -File` — bez niego `cmd` otworzy skrypt w Notatniku zamiast go uruchomić. Komendy `docker` / `curl` działają w `cmd` bez zmian. Kopiuj **samą komendę** — tekst po `#` to komentarz dla Ciebie, w `cmd` go nie wklejaj (`cmd` nie zna `#`).
---

## 0. Zanim wejdziesz — setup (T-15 → T-2)

### T-15 — podnieś stack Z OVERLAYEM DEMO i sprawdź
```powershell
# z roota repo (C:\softwareEngineer\MicroservicesFleet). Pełny rozbiór flag → sekcja „🔧 Słownik komend" niżej.
docker compose -f infra/docker/docker-compose.yml -f infra/docker/docker-compose.demo.yml up -d --build
#   ^ bazowy stack (1. -f) + overlay demo z Toxiproxy (2. -f) → zbuduj obrazy (--build) → uruchom w tle (-d)
docker ps --format "table {{.Names}}\t{{.Status}}"      # lista kontenerów — czekaj, aż wszystko „Up/healthy" (w tym toxiproxy)
powershell -ExecutionPolicy Bypass -File .\scripts\preflight-demo.ps1 -Full   # smoke test: realnie otwiera/zamyka circuit; MUSI zwrócić exit 0
```
`preflight-demo.ps1 -Full` realnie otwiera i zamyka circuit + sprawdza alert. **Exit 0 = demo zadziała.** To też dobry moment na nagranie ekranu (Plan B).

### Checklista (odhacz przed wejściem)
- [ ] `preflight-demo.ps1 -Full` = **0**
- [ ] Grafana: http://localhost:3000 → dashboard **„Resilience4j — starport→trade-route (odporność widzialna)"**
- [ ] Prometheus alerty: http://localhost:9090/alerts?search= → wpisz `TradeRoutePlannerCircuitOpen` (druga karta, zostaw otwartą na AKT 4)
- [ ] `QA-cheatsheet.md` otwarte na drugim ekranie
- [ ] Flipchart / tablica + marker (potrzebne w AKT 1 i AKT 5)
- [ ] Backup: PDF wyeksportowany z decku (`Plik → Eksportuj → PDF`) + zrzuty stanów CB (zielony / OPEN / half_open)

### T-2 — uruchom zdrowy ruch (Terminal A) i ZOSTAW lecący
```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\demo-resilience.ps1 -DurationMinutes 30
```
To **pre-warm**: wypełnia okno ślizgowe (`minimum-number-of-calls: 5`), inaczej failure rate pokaże „—". Zostaw to okno otwarte, nie dotykaj go do końca.

#### 🔁 Co dokładnie robi generator ruchu (Terminal A) — `demo-resilience.ps1` (tryb TRAFFIC)
Symuluje realnych klientów rezerwujących doki, tak by **każde żądanie faktycznie wołało trade-route-planner** — czyli karmiło circuit breaker i retry (te panele, które pokazujesz).

| Aspekt | Wartość | Uwaga |
|---|---|---|
| **Co wysyła** | `POST http://localhost:8080/api/v1/starports/{dest}/reservations` | przez api-gateway (`-Base`, dom. `:8080`) |
| **Jak szybko** | **~4 req/s** (`-RatePerSecond 4`) | pętla ze `Start-Sleep` 250 ms między żądaniami (`1000/RatePerSecond`) |
| **Jak często / jak długo** | ciągle, przez **`-DurationMinutes`** (tu 30) | ~4 × 60 = **~240 żądań/min**, ~7200 przez 30 min |
| **Kluczowa flaga** | `requestRoute: true` | **wymusza** wywołanie starport → planner (bez tego CB/retry są suche) |
| **Body (losowane co żądanie)** | statek+klasa (X-WING/SCOUT, FALCON/FREIGHTER, STAR-DESTROYER/CRUISER), klient `CUST-001..005`, `originPortId` ≠ `dest` z 10 realnych portów (TATOO, CORUS, ALDER, HOTH, ENDOR, NABOO, DAGO, BESPIN, KAMINO, MUSTAF) | okno `startAt` rzucane **1–3650 dni w przyszłość** → brak kolizji doków, żeby rezerwacja nie odpadła na walidacji |
| **Co liczy** | `201` = OK · `409` = degradacja (fallback / circuit open) · `inne` | wypisuje bilans co 20 żądań |

**Dlaczego to ważne dla demo:** `requestRoute=true` sprawia, że ruch przechodzi przez chronioną krawędź. Generator **niczego nie psuje** — awarię wstrzykuje dopiero Terminal B (`-Chaos ...`). A robi się to na *żywym, stałym* strumieniu, więc okno CB jest pełne i wykresy reagują od razu. *(Nie używaj do pre-warmu `load-test-timed.ps1` — on leci z `requestRoute:false`, więc CB nie dotyka.)*

### Układ ekranu
| Element | Co |
|---|---|
| **Ekran 1 (rzutnik)** | Slajdy (`Działa-nie-równa-się-działa_TED.pptx`), a w AKT 4 — przełączasz na Grafanę + Prometheus |
| **Ekran 2 (twój)** | Tryb prezentera (notatki/timer) **lub** ten runbook + `QA-cheatsheet.md` |
| **Terminal A** | zdrowy ruch (już leci, nie dotykasz) |
| **Terminal B** | tu wklejasz komendy `-Chaos ...` na żywo |

---

## 1. Slajd po slajdzie — pełny tekst mówiony

# AKT 0 — Wejście

### Slajd 1 — Tytuł („Działa ≠ działa i ja o tym wiem") · ~15 s
- 🗣 **MÓWISZ:** *(nic — nie czytaj tytułu)*
- 🎭 **JAK:** wejdź, stań, 2 s ciszy, spójrz na salę. Pierwsze, co robisz, to nie mówienie — tylko obecność.
- 🎬 **ROBISZ:** od razu → slajd 2 (wchodzisz w doświadczenie, nie w tytuł).

### Slajd 2 — „Wyobraźcie sobie" · ~1,5 min
- 🗣 **MÓWISZ:**
  „Wyobraźcie sobie zwykły wtorek. Piszecie serwis. Jeden komponent musi zapytać drugi — przez sieć, zwykłym HTTP-em. Piszecie ten jeden, niewinny request. Wysyłacie. Czekacie na odpowiedź. *(pauza)* I tyle.

  Nie ma retry — jak nie odpowie, to nie odpowie. Nie ma timeoutu z prawdziwego zdarzenia. Nie ma metryki. Nikt nie patrzy. Pipeline zielony, deploy poszedł, idziecie na kawę. *(pauza)* Działa.

  *(pauza 2 s, ręka w górę)* A teraz szczerze — ręka w górę — kto z was ma w swoim systemie *taki właśnie* request? Jeden. Taki, którego tak naprawdę nikt nie pilnuje."
- 🎭 **JAK:** na slajdzie po prawej przerywana strzała w próżnię z podpisem „nikt nie patrzy" — możesz na nią wskazać przy „Nikt nie patrzy".
- 🎬 **ROBISZ:** po rękach → slajd 3.
- ⏱ **Checkpoint po AKT 0: do 1:45.**

# AKT 1 — Refleksja / oddanie głosu sali

### Slajd 3 — „A może powinniśmy…" · ~3 min · INTERAKCJA
- 🗣 **MÓWISZ:**
  „Skoro już macie ten request przed oczami — mam pytanie, na które chcę usłyszeć *waszą* odpowiedź. Co byśmy z nim zrobili, żeby spać spokojnie?

  Może powinniśmy sprawdzać, czy ten drugi serwis w ogóle *żyje*? Może mierzyć, jak długo odpowiada? Może mieć z tego jakiś sygnał — metrykę, coś, co *krzyknie*, kiedy zrobi się źle?

  Powiedzcie mi — co *wy* byście dodali?
  *(zbierasz odpowiedzi, wypisujesz na flipchart)*

  ⚓ **Most do historii (dosłownie):** „Zapamiętajcie tę listę. To jest *wasza* lista — i obiecuję, że wrócimy do niej na końcu, co do jednego punktu. Brzmi na razie teoretycznie? To pozwólcie, że opowiem wam bardzo konkretną historię. Moją. Niezbyt chlubną."
- 🎭 **JAK:** otwarta dłoń do sali, oddaj głos. Zbierz 2–3 odpowiedzi, **NIE oceniaj merytorycznie** („dobre", „jest", „notuję" — tyle).
- 🎬 **ROBISZ:** **FLIPCHART** — wypisz hipotezy sali (health-check, retry, timeout, alert, metryka…). To ICH lista — wrócisz do niej na slajdzie 18. → slajd 4.
- ⏱ **Checkpoint po AKT 1: do 4:15.** ⚠️ Najłatwiejsze miejsce na przekroczenie — limit 3 odpowiedzi, potem zdanie ucinające.

# AKT 2 — Historia

### Slajd 4 — „3 miesiące" · ~1,5 min · 💙 SERCE OPOWIEŚCI
- 🗣 **MÓWISZ:**
  „Jakiś czas temu wdrożyliśmy nową usługę. Wszystko na zielono. Testy przeszły, deploy przeszedł, ruch poszedł. Klasyka.

  Ten serwis miał odpytywać inny — pod konkretnym adresem. I ten adres *wyglądał* dobrze. Naprawdę dobrze. Poprawnie sformatowany, dobrze odpowiadał w czasie testów, nie rzucał wyjątkiem. Tylko że był… niewłaściwy. Za fasadą porządnie wyglądającego adresu kryło się złe miejsce w sieci.

  *(pauza)* I przez trzy miesiące — *(pauza)* — trzy miesiące — strzelaliśmy requestami w zły adres. Bez retry. Bez observability. Bez jednego sygnału, że coś jest nie tak.

  *(pauza)* A wiecie, jak to w końcu wyszło? Nie z alertu. Nie z dashboardu. ⚓ *Przypadkiem.* Ktoś przeglądał logi z zupełnie innego powodu i potknął się o to. Dwa błędy na dobę. Dwa — utopione w tysiącach linii szumu."
- 🎭 **JAK:** najważniejsze 90 sekund talku. Zwolnij, mów jak do jednej osoby, **bez wymówek**. **PAUZA po „trzy miesiące"** i przed „Przypadkiem".
- 🎬 **ROBISZ:** → slajd 5.

### Slajd 5 — „Ilu z was wie w tej chwili" · ~30 s
- 🗣 **MÓWISZ:**
  „I wtedy zadałem sobie pytanie, które chcę teraz zostawić wam. Ilu z was wie — naprawdę *wie*, w tej sekundzie — że wszystkie zależności waszego systemu właśnie teraz żyją? Nie „powinny". Nie „pewnie tak, rano działało". Tylko: *wie*."
- 🎭 **JAK:** retoryczne. **ZOSTAW CISZĘ.** Nie odpowiadaj za nich. Nie zapowiadaj, że to ma zaboleć — niech samo zaboli.
- 🎬 **ROBISZ:** → slajd 6.
- ⏱ **Checkpoint po AKT 2: do 6:15.**

# AKT 3 — Sedno

### Slajd 6 — „To była awaria widoczności" · ~30 s · ⭐ TEZA
- 🗣 **MÓWISZ:**
  „Zobaczcie, co tak naprawdę się stało. To nie była awaria retry — retry by tu nic nie zmienił, requesty przecież „działały". To nie była awaria kodu.

  ⚓ To była awaria *widoczności*. *(pauza)* „Działa" — nie znaczy „działa i ja o tym wiem". *(pauza)* Między tymi dwoma zdaniami zmieściły się moje trzy miesiące."
- 🎭 **JAK:** **najwolniej w całym talku.** To jest zdanie, po które przyszli.
- 🎬 **ROBISZ:** → slajd 7.

### Slajd 7 — „Mapa wzorców" · ~1 min
- 🗣 **MÓWISZ:**
  „Dobra wiadomość: nie musimy tego wymyślać od zera. Wszystko, co przed chwilą wykrzyczeliście na ten flipchart, ktoś już nazwał i zaimplementował. Te potrzeby *mają imiona* — i mają gotową bibliotekę: Resilience4j.

  Bo każdy z tych wzorców to odpowiedź na jeden konkretny sposób, w jaki sieć potrafi was zawieść. Retry — gdy coś mrugnęło raz: ponów. CircuitBreaker — gdy zależność leży: przestań się dobijać, odetnij. TimeLimiter — gdy odpowiedź nie przychodzi: nie czekaj w nieskończoność. Bulkhead — gdy jeden zasób się zapycha: odgrodź go, żeby nie zatopił reszty. RateLimiter — gdy jest za duży ruch: nie przeciążaj.

  Po to je wymieniam: żebyście zobaczyli, że na każdą bolączkę z waszej listy *jest* nazwany wzorzec i jedna linijka konfiguracji.

  ✂️ *(tnij, gdy goni czas)* I ciekawostka, która zaskakuje nawet seniorów: w Springu te wzorce układają się w *warstwy*, w konkretnej kolejności. Retry owija CircuitBreaker, ten owija RateLimiter, ten Bulkhead — a wasza funkcja siedzi w samym środku, jak rdzeń cebuli. Kolejność ma znaczenie, bo decyduje, co zadziała pierwsze."
- 🎭 **JAK:** **nazwij, nie wykładaj.** Przy ciekawostce wskaż tylko linijkę kolejności (`Retry( CircuitBreaker( … ))`) — bo to ona zaskakuje.
- 🎬 **ROBISZ:** → slajd 8.

### Slajd 8 — „Circuit breaker — bezpiecznik dla wywołań" · ~40 s · 🔎 DLA CAŁEJ SALI
> Ten slajd to graficzne przełożenie circuit breakera na język nietechniczny (odtworzone natywnie w decku z `circuit-breaker-biznes.svg`). Z całej piątki wzorców tylko ten dostaje osobny obrazek — bo to jego będziesz dziś psuł na żywo i to on najczęściej myli.
- 🗣 **MÓWISZ:**
  „Jednemu z tych wzorców należy się osobny obrazek — bo to jego za chwilę zobaczycie w akcji. Circuit breaker. Bezpiecznik.

  Wyobraźcie sobie bezpiecznik w domu. Gdy w instalacji zaczyna iskrzyć, nie czeka, aż spłonie dom — odcina prąd. Tu jest tak samo, tylko zamiast prądu odcinamy wywołania do usługi, która przestała działać.

  Trzy stany. ZAMKNIĘTY — zielony — ruch przechodzi normalnie, bezpiecznik tylko liczy błędy i czasy. OTWARTY — czerwony — zależność padła, więc odcinamy ruch i oddajemy plan B; nie dobijamy leżącego, klient nie wisi w próżni. I PÓŁOTWARTY — pomarańczowy — po jakichś dziesięciu sekundach puszczamy kilka prób: udają się — wracamy do zielonego; dalej źle — znów odcinamy.

  A retry to coś innego: drugie podejście, *zanim* w ogóle uznamy coś za błąd. Sieć mrugnęła? Ponów raz — często druga próba trafia na zdrową kopię usługi."
- 🎭 **JAK:** to slajd dla **całej sali**, także nietechnicznej. Metafora domowego bezpiecznika niesie resztę — nie wchodź w konfigurację. Pokazuj trzy karty palcem, po kolei.
- 🎬 **ROBISZ:** → slajd 9.

### Slajd 9 — „Zwrot" · ~1 min · ⭐ REVEAL
- 🗣 **MÓWISZ:**
  „Ale teraz uwaga — bo tu jest zwrot akcji. Powiedzmy, że dodaliście retry. Brawo. Tylko… skąd wiecie, że on się w ogóle *odpalił*? Ile razy ponowił? Czy circuit otworzył się wczoraj w nocy i zamknął — a wy spaliście spokojnie? *(pauza)*

  ⚓ Bez metryki — *nie wiecie*. Samo retry to wciąż czarna skrzynka.

  Dorzuciliście mechanizm, owszem — ale dalej go nie *widzicie*. A to znaczy, że popełniliście dokładnie ten sam błąd. Tylko schowaliście go o jedną warstwę głębiej."
- 🎭 **JAK:** **PAUZA przed „Bez metryki — nie wiecie".**
- 🎬 **ROBISZ:** → slajd 10.
- ⏱ Mid-checkpoint: tu powinieneś być ~**10:00**.

### Slajd 10 — „Micrometer jako fasada" · ~45 s
- 🗣 **MÓWISZ:**
  „Więc jak to *zobaczyć*? Tu wchodzi Micrometer. ⚓ *Micrometer jest dla metryk tym, czym SLF4J jest dla logów.* Jeden wspólny interfejs, jeden sposób zapisu — a z tyłu podpinasz dowolny backend: Prometheus, Datadog, co chcecie. Tak jak nie piszecie logów „pod konkretny plik", tak tu nie piszecie metryk „pod konkretny system". Instrumentujecie raz — metryka gotowa, a gdzie poleci, decydujecie zależnością w pom-ie.

  A w Spring Boot 3 jest jeszcze ładniej. Ten sam call HTTP opakowuje się w coś, co nazywa się Observation — i z jednej obserwacji wypadają *i* metryki, *i* trace'y. Dwa w jednym. I najlepsze: Boot robi to za was. Auto-instrumentuje RestClient, RestTemplate, WebClient. W większości projektów, które widziałem, Micrometer to *już* jest w classpathie."
- 🎭 **JAK:** spokojnie. Jak ktoś dopyta: Observation i metryki R4j to **dwie współpracujące** warstwy, nie jeden mechanizm (→ QA #8).
- 🎬 **ROBISZ:** → slajd 11.

### Slajd 11 — „Metryki za darmo" · ~45 s
- 🗣 **MÓWISZ:**
  „A Resilience4j oddaje swoje metryki praktycznie *za darmo*. I chcę być precyzyjny, co znaczy „za darmo" — bo nie chodzi o koszt. ⚓ Chodzi o to, że piszecie *zero* własnego kodu liczników. Zero. Żadnego „tu wstaw counter, tam zwiększ gauge".

  Dorzucacie trzy zależności — actuator, resilience4j, micrometer-registry-prometheus — i nagle macie: stan circuita, failure rate, czasy wywołań, liczniki retry. Gotowe. To wszystko już tam było. Czekało, aż ktoś podłączy kabel."
- 🎭 **JAK:** akcentuj **„zero kodu liczników"** (nie „za darmo kosztowo") — to rozbraja zarzut o koszt (→ QA #10).
- 🎬 **ROBISZ:** → slajd 12 (ARCHITEKTURA).

### Slajd 12 — „Co w co strzela i gdzie to psujemy" (ARCHITEKTURA) · ~25 s · (TUŻ PRZED kodem)
> ⚠️ **Kolejność:** ta narracja pada *tuż przed* „u mnie to już działa". W decku slajd architektury (12) jest już **przed** slajdem z kodem (13) — nic nie musisz przestawiać.
- 🗣 **MÓWISZ:**
  „Zanim pokażę wam swój kod — sekunda na mapę. Żebyście wiedzieli, *gdzie* on siedzi i co za chwilę będę psuł.

  Klient wchodzi przez gateway. Dalej starport-registry, dwie repliki — robi rezerwację, a do tego potrzebuje trasy z trade-route-planner, też dwie repliki. To jedno wywołanie — starport do plannera — jest dziś bohaterem.

  Tę jedną krawędź owinąłem w Retry i CircuitBreaker. I tę jedną krawędź — *tylko ją* — będę dziś psuł na żywo. Nic więcej nie ruszam."
- 🎭 **JAK:** pokaż palcem przepływ z góry na dół. Zielona ramka na slajdzie = „tu żyje odporność" (starport→planner), pomarańczowa = miejsce, gdzie Toxiproxy wstrzykuje awarię. Reszty stacku (Eureka, Kafka, Prometheus) nie wymieniaj — nieistotne dla pointy.
- 🎬 **ROBISZ:** → slajd 13.

### Slajd 13 — „U mnie to już jest — realny kod" · ~1 min
- 🗣 **MÓWISZ:**
  „Spójrzcie: *dwie adnotacje.* Tyle. *(wskaż podświetlone linie)* Retry i CircuitBreaker, z fallbackiem, na jednym realnym wywołaniu — starport pyta trade-route-planner o trasę. Dwie linijki nad metodą.

  I jedna rzecz, na którą chcę zwrócić uwagę: ten fallback *nie milczy*. Kiedy się odpala, nie udaje, że nic się nie stało — liczy metrykę i degraduje się świadomie. Mówi: „wiem, że jest gorzej — i wiem, o ile gorzej"."
- 🎭 **JAK:** wskaż dwie adnotacje na górze. Nie czytaj całej metody — pointa to `incrementErrorCounter(...)` w fallbacku (komentarz „to widać na dashboardzie").
- 🎬 **ROBISZ:** → slajd 14.

### Slajd 14 — „Jeden dashboard, dwie publiczności" · ~45 s
- 🗣 **MÓWISZ:**
  „I teraz rzecz, która sprzedaje to wszystko waszemu szefowi. Jeden dashboard — *dwie* publiczności. *(gest w lewo)* Wy, dev, patrzycie i widzicie: failure rate, p99, liczbę retry, stan circuita. *(gest w prawo)* A osoba od biznesu patrzy na *ten sam* ekran i widzi co innego: czy zależność żyje, ile było downtime'u, czy dowozimy SLA.

  ⚓ Ten sam sygnał — dwa języki. Nie musicie tłumaczyć technicznego na ludzki. Dashboard robi to za was."
- 🎭 **JAK:** gest w lewo / w prawo (dwie kolumny na slajdzie: niebieska „Dla devów", zielona „Dla biznesu").
- 🎬 **ROBISZ:** → slajd 15 (demo). **Przygotuj się: za chwilę przełączasz rzutnik na Grafanę.**
- ⏱ **Checkpoint po AKT 3: do 12:45.** *(AKT 3 ma teraz osobny slajd 8 — circuit breaker — więc ~30 s więcej niż w wersji bez niego.)* Jeśli już po 12:45 → wchodź w demo MINIMALNE.

# AKT 4 — Demo na żywo

> Architektura padła już w AKT 3 (tuż przed kodem) — tutaj NIE powtarzasz mapy. Wchodzisz prosto w demo.

### Slajd 15 — „Demo" · ~15 s
- 🗣 **MÓWISZ:**
  „Dość teorii. Pokażę to na żywym systemie — nie na nagraniu, na żywo, więc trzymajcie kciuki. Zapamiętajcie tylko jedno: najpierw będzie *cisza*. Potem — będzie *widać*."
- 🎬 **ROBISZ:** *(opcjonalnie)* pokaż jeszcze na chwilę slajd 16 — drabinę eskalacji — jako „mapę pięciu szczebli", które za chwilę przejdziecie, po czym **przełącz rzutnik na Grafanę** (dashboard zielony, Terminal A już leci). → patrz sekcja 2 (choreografia demo).

### Slajd 16 — „Drabina eskalacji" (tło demo) · na żywo
👉 **Pełna choreografia komend + narracja do wykresu w sekcji 2 niżej.** Slajd 16 pokazuje pięć szczebli (zielono → 300 ms → 600 ms → częściowo → zgon) i stany CLOSED/OPEN — możesz do niego wracać, żeby sala wiedziała, na którym szczeblu jesteś. Pracujesz jednak głównie na Grafanie + Prometheus.

### Slajd 17 — „Alert domyka pętlę" · ~30 s · (po powrocie ze demo)
> Ten slajd pokazujesz **po** demie — gdy przełączasz rzutnik z Grafany/Prometheusa z powrotem na slajdy. To skrystalizowana wersja tego, co przed chwilą zapłonęło w Prometheusie (ten sam warunek PromQL, ta sama plakietka **FIRING**).
- 🗣 **MÓWISZ:**
  „I to jest to, co domyka całą pętlę. *(wskaż zapytanie)* Ten jeden warunek — circuit otwarty równa się jeden — pilnuje mnie nieprzerwanie. Nie muszę niczego się domyślać ani przeglądać logów. ⚓ *Nigdy więcej odkrywania awarii przez przypadek.*"
- 🎭 **JAK:** wolno. To domknięcie demo i most do finału — plakietka FIRING za tobą robi robotę.
- 🎬 **ROBISZ:** → slajd 18.
- ⏱ **Checkpoint po AKT 4 (demo + heal + slajd 17): do 15:45.**

# AKT 5 — Domknięcie

### Slajd 18 — „Wróćmy do waszej listy" · ~1 min
- 🗣 **MÓWISZ:**
  „Pamiętacie listę z początku? *Waszą* listę? Podejdźmy do niej. *(podejdź do flipchartu)* Health-check — chcieliście wiedzieć, czy zależność żyje? *(odhacz)* Macie, właśnie to widzieliście. Retry? *(odhacz)* Jest, i to widoczny. Timeout? *(odhacz)* Jest. Alert? *(odhacz)* Nie *opowiadam* wam o nim — on się przed chwilą odpalił na waszych oczach. Metryka? Wszystko, co przewinęło się przez te ekrany.

  *(pauza, odsuń się od flipchartu)* ⚓ Zwróćcie uwagę: ja wam tego nie sprzedałem. To były *wasze* pomysły — sprzed dwudziestu minut. Resilience4j i Micrometer po prostu je spełniają. Wy już wiedzieliście, co trzeba zrobić."
- 🎭 **JAK:** **fizycznie podejdź do flipchartu** i odhaczaj NA ŻYWO. Na slajdzie te same hasła mają zielone „ptaszki" — synchronizuj gest z ekranem. Daje poczucie współautorstwa.
- 🎬 **ROBISZ:** → slajd 19.

### Slajd 19 — „Żeby psucie było widać" · ~30 s · ⭐ DO ZAPAMIĘTANIA
- 🗣 **MÓWISZ:**
  „Bo cała ta opowieść nie jest o tym, żeby system się *nie psuł*. On się będzie psuł. Sieć padnie, zależność zwolni, ktoś wdroży zły adres — jak ja. *(pauza)* ⚓ Nie chodzi o to, żeby się nie psuło. Chodzi o to, żeby psucie było *widać*."
- 🎭 **JAK:** wolno, **pauza między zdaniami.** Na slajdzie słowo „widać" jest zielone — zawieś na nim głos.
- 🎬 **ROBISZ:** → slajd 20.

### Slajd 20 — „Konkret na poniedziałek" · ~45 s
- 🗣 **MÓWISZ:**
  „I żebyście nie wyszli stąd tylko z ładną historią — konkret. Na poniedziałek, nie „kiedyś". Trzy kroki. Raz: dodajcie trzy zależności, te które przed chwilą wymieniłem. Dwa: weźcie *jeden* call — ten jeden, najważniejszy, ten, który najbardziej boli, gdy padnie — i owińcie go w CircuitBreaker i Retry. Trzy: zaimportujcie dashboard i postawcie *jeden* alert. ⚓ Nie wszystko naraz. Jedna krytyczna zależność. Od niej zacznijcie."
- 🎬 **ROBISZ:** → slajd 21.

### Slajd 21 — „Wróćmy do endpointu" · ~30 s · ⭐ KLAMRA
- 🗣 **MÓWISZ:**
  „I na koniec wróćmy tam, gdzie zaczęliśmy. Do tego jednego, niewinnego requestu ze slajdu drugiego. Tego, którego nikt nie pilnował. *(pauza)* ⚓ Teraz, kiedy w niego strzelacie — *widzicie to*. I to jest cała różnica."

  ✂️ *(opcjonalny forward, jeśli jest czas:)* „A za chwilę Spring Boot 4 wniesie tę odporność wbudowaną — `@Retryable`, `@EnableResilientMethods`. Zmienia się dostawca adnotacji. Zasada zostaje: instrumentuj to, czemu ufasz."
- 🎭 **JAK:** callback do slajdu 2 — na slajdzie ta sama przerywana strzała co na starcie, ale teraz trafia w zielony punkt (jest sygnał). Spokojne domknięcie.
- 🎬 **ROBISZ:** → slajd 22.
- ⏱ **Checkpoint po AKT 5: do 18:15.**

### Slajd 22 — „Dziękuję" · ~Q&A
- 🗣 **MÓWISZ:** „Dziękuję. *(pauza, uśmiech)* Pytania?"
- 🎬 **ROBISZ:** miej `QA-cheatsheet.md` pod ręką. Częste: timeout vs CB, retry storm, service mesh, `failure_rate=-1`, CB per-instance. Na slajdzie są też linki do materiałów demo (`scripts/DEMO-resilience.md`, `demo-resilience.ps1`, `preflight-demo.ps1`).

---

## 2. Choreografia demo na żywo (slajdy 15–17) · ~4 min

> **Stan wyjściowy:** Grafana na rzutniku (dashboard ZIELONY), Terminal A leci (zdrowy ruch), Terminal B wolny.
> Refresh dashboardu = 5 s, scrape = 3 s, okno CB = 10 wywołań → **każdy krok daj ~15–20 s, żeby wykres zareagował. Nie spiesz się — narracja w trakcie patrzenia.**
> **Próg „slow" (300 vs 600 ms) = flaga `-SlowMs`** (jedyna różnica 1a↔1b). Bez flagi = 600 ms. Trzy progi: **300 < 500 (slow-call) < 600 < 800 (read-timeout)**.
> **Zasada komentowania na żywo:** najpierw NAZWIJ panel, potem ODCZYTAJ liczbę, potem POWIEDZ, co to znaczy. „Patrzcie na *slow call rate* — wciąż zero — a mimo to…". Palec na panelu, o którym mówisz. Liczby to wartości spodziewane (zweryfikowane 29.06) — jeśli realne się różnią, czytaj realne, narracja działa tak samo.

### 🧠 Jak to działa pod spodem — CB, „krok", i skąd te ~15–20 s (żeby sala rozumiała rozwiązanie)

**CB = Circuit Breaker** — ten sam bezpiecznik ze slajdu 8 (Resilience4j na wywołaniu starport → trade-route-planner). „Okno CB = 10 wywołań" to jego **okno ślizgowe**: failure rate / slow-call rate liczy z **ostatnich 10 wywołań**, nie z czasu.

**„Krok"** = jeden etap choreografii = **jedno pociągnięcie w Terminalu B** (`-Chaos ...`). Odpal → poczekaj → opowiadaj patrząc na wykres.

**Łańcuch skrypt → aplikacja → Prometheus → Grafana** (dlatego „daj ~15–20 s" — na każdym ogniwie jest opóźnienie):
```
Terminal B: demo-resilience.ps1 -Chaos ...     ← odpalasz krok (Toxiproxy psuje krawędź starport→planner)
      ▼
Terminal A: zdrowy ruch ~3–4 req/s             ← wywołania LECĄ przez zepsutą ścieżkę (bez tego okno CB puste)
      ▼
Circuit Breaker: okno 10 wywołań (~3 s)        ← musi uzbierać świeże wywołania, by przeliczyć rate/state
      ▼
Prometheus: scrape co 3 s                       ← do 3 s zanim pobierze nową wartość z /actuator/prometheus
      ▼
Grafana: refresh co 5 s                          ← do 5 s zanim przerysuje panel
```
Suma najgorszych przypadków: ~3 s (okno) + do 3 s (scrape) + do 5 s (refresh) + margines ≈ **15–20 s**. Pospieszysz — opowiesz o skutku, zanim panel go pokaże.

**Konfiguracja, którą warto znać (to jest „jak działa rozwiązanie" — możesz to rzucić sali):**

| Parametr | Wartość | Gdzie | Co robi |
|---|---|---|---|
| `sliding-window-size` | **10** | `application.yml` (CB) | z ilu ostatnich wywołań liczone są wskaźniki |
| `minimum-number-of-calls` | **5** | `application.yml` (CB) | poniżej tylu wywołań rate = `-1` („—") — stąd pre-warm Terminalem A |
| `failure-rate-threshold` | **50%** | `application.yml` (CB) | ≥ tego → circuit OPEN na błędach |
| `slow-call-duration-threshold` | **500 ms** | `application.yml` (CB) | wywołanie ≥ 500 ms liczy się jako „slow" |
| `slow-call-rate-threshold` | **50%** | `application.yml` (CB) | ≥ tego udziału „slow" → circuit OPEN (bez błędów!) |
| `wait-duration-in-open-state` | **10 s** | `application.yml` (CB) | po tylu sekundach OPEN → half_open (próba) |
| read-timeout | **800 ms (demo)** / 2000 ms (prod) | `application-demo.yml` → `RestClientConfig` | powyżej wywołanie pada błędem (timeout); w demie skrócony do 800 ms (snappy), baza `application.yml` = 2000 ms |
| scrape interval | **3 s** | `prometheus.yml` | jak często Prometheus pobiera metryki |
| refresh | **5 s** | dashboard Grafany | jak często odświeżają się panele |

**❓ „5 czy 10 wywołań?" — to DWA różne parametry (częsta pomyłka):**
- **`minimum-number-of-calls: 5`** = ile wywołań musi się uzbierać, **zanim breaker w ogóle zacznie oceniać** i może się otworzyć. Poniżej 5 → rate = `-1` („—"), circuit nie zareaguje.
- **`sliding-window-size: 10`** = z ilu *ostatnich* wywołań liczy średnią (max okno). To NIE jest próg wyzwolenia.

Dlatego w `down` (100% błędów) circuit otwiera się już **po 5 wywołaniach** — bo tyle wystarczy, by policzyć failure rate (5/5 = 100% ≥ 50%). „10" to tylko rozmiar okna uśredniania, nie moment reakcji. Stąd w narracji Kroku 3 „po pięciu wywołaniach OPEN" — to jest poprawne.

**Puls vs wstrzykiwacz:** Terminal A (zdrowy ruch) to *puls* — bez niego okno CB się nie napełnia i nie ma czego pokazać. Terminal B to *wstrzykiwacz awarii*. Dlatego A leci cały czas, a Ty ruszasz tylko B.

### Krok 0 — ZIELONO (stan wyjściowy)
- 🎬 *Bez komendy* — Terminal A (zdrowy ruch) leci od T-2, dashboard już zielony.
- 📊 **NA WYKRESIE (pokazuj palcem, panel po panelu):** „Maks. latencja" ~**5–30 ms** · „Failure rate" **0%** · „slow call rate" **0%** · „Stan circuit breakera" **CLOSED** (zielony).
- 🗣 „Zatrzymajmy się na chwilę na zdrowiu. Latencja — *(palec)* — kilkanaście milisekund. Failure rate — zero. Slow call rate — zero. Circuit — zielony, CLOSED. Wszystko śpi, jak należy. *(pauza)* A teraz wyobraźcie sobie, że tego ekranu po prostu *nie ma*. Bo tak właśnie wyglądał mój system przez trzy miesiące — tyle że beze mnie patrzącego."

> 🧭 **Panel „Stan zależności" — co to za metryka (warto wiedzieć przy zielonym stanie).**
> - **Metryka:** `resilience4j_circuitbreaker_state` (gauge, `1` = stan aktywny).
> - **Wzór panelu:** `max(resilience4j_circuitbreaker_state{job="starport-registry", name="$circuit_name", instance=~"$instance", state="open"})`
> - **Co pokazuje / po co:** jedno słowo dla nie-technicznej sali. Mapowanie wartości: **`0` → DOSTĘPNA** (zielone tło), **`1` → ODCIĘTA (circuit OPEN)** (czerwone tło). `max(...)` po instancjach = „czy *którakolwiek* replika ma otwarty circuit". To przekład stanu circuit breakera na język „czy usługa działa".
>
> | Faza demo | Stan panelu |
> |---|---|
> | Rozgrzanie | **DOSTĘPNA** |
> | `slow 300` | **DOSTĘPNA** |
> | `slow 600` | **ODCIĘTA** |
> | `partial` | miga **DOST./ODCIĘTA** |
> | `down` | **ODCIĘTA** |
> | `heal` | → **DOSTĘPNA** (po ~10 s) |
>
> **Błędy widać:** przeskok na czerwień = pełne odcięcie.

### Krok 1a — WOLNO, PODPROGOWO (degradacja widoczna, breaker TRZYMA)
```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\demo-resilience.ps1 -Chaos slow -SlowMs 300
```
- 📊 **NA WYKRESIE:** „Maks. latencja wywołań" skacze z ~20 ms do **~300–350 ms**. „slow call rate" i „failure rate" **dalej 0%**. „Stan circuit breakera" **CLOSED**.
- 🗣 „Wstrzykuję trzysta milisekund opóźnienia. I patrzcie na panel latencji — *(palec)* — był dwadzieścia milisekund, jest **trzysta pięćdziesiąt**. Wykres podskoczył, użytkownik to *czuje*. *(pauza)* A teraz przesuńcie wzrok na *failure rate* i *slow call rate*: dalej **zero**. Circuit dalej **zielony**. Breaker śpi. To jest *szara strefa* — działa, ale wyraźnie gorzej, a metryki *błędów* nic nie widzą. Widzi to *tylko* panel latencji."
- 💡 *(jeśli ktoś dopyta)* próg slow-call to **500 ms**. 300 ms jest *pod* nim → formalnie wywołanie jeszcze nie jest „slow", dlatego breaker trzyma.

### Krok 1b — WOLNO, NADPROGOWO (slow-call OTWIERA breaker — bez ani jednego błędu) ⭐ najmocniejsze „aha"
```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\demo-resilience.ps1 -Chaos slow -SlowMs 600
#   ^ różnica vs 1a to TYLKO -SlowMs 600 (= domyślka, więc samo -Chaos slow daje to samo)
```
- 📊 **NA WYKRESIE:** „Maks. latencja" → **~600–640 ms**. „slow call rate" wspina się do **linii progu 50%** i w tym momencie „Stan circuit breakera" przeskakuje na **OPEN** (czerwony). „Failure rate" przez cały czas **~0%**.
- 🗣 „Teraz podbijam do sześciuset milisekund — powyżej progu, czyli powyżej pięciuset. Patrzcie na *slow call rate* — *(palec)* — wspina się… dochodzi do tej linii progu, pięćdziesiąt procent… i *w tej sekundzie* — *(palec na panel stanu)* — circuit przeskakuje na **OPEN**, robi się czerwony. *(pauza)* A teraz najważniejsze: spójrzcie na *failure rate*. Dalej **zero**. Ani jednego błędu. ⚓ Wolne wywołanie to też awaria. Breaker nie czekał na błędy — zareagował na to, że zależność *umiera, choć jeszcze odpowiada*. Tego nie złapie żaden licznik pięćsetek."

> 💡 **Dlaczego 300 trzyma, a 600 otwiera — gotowy wtręt mówiony (jeśli ktoś dopyta albo chcesz pogłębić).** Mów spokojnie, to ładnie się słucha:
> 🗣 „Wyobraźcie sobie, że circuit breaker ma *dwoje drzwi*, którymi może wyjść z gry.
> Pierwsze to **błędy** — gdy zależność rzuca wyjątkami.
> Drugie to **czas** — gdy zależność jeszcze odpowiada, ale robi to za wolno. I te drugie drzwi mają próg: pięćset milisekund.
> *(pauza)* Przy trzystu wołanie jest wolne, owszem — ale wciąż mieści się *przed* tym progiem.
> Dla breakera to po prostu udane wołanie. Trochę ospałe, ale zdrowe. Obwód się nie otwiera.
> Przy sześciuset jesteśmy już *za* progiem. Każde takie wołanie breaker stempluje jako „slow" — i kiedy połowa okna jest ostemplowana, otwiera te drugie drzwi. Mimo że nie poleciał ani jeden błąd.
> *(pauza)* A dlaczego próg to akurat pięćset, a nie tysiąc? Bo siedzi tuż *pod* naszym read-timeoutem — osiemset milisekund.
> Gdyby był wyżej, wolne wołania zdążyłyby najpierw paść z timeoutu, i breaker policzyłby je jako błędy, a nie jako spowolnienie. A my chcemy złapać dokładnie ten moment, w którym zależność jeszcze żyje, ale już gaśnie. Trzysta — za szybko, żeby się martwić. Sześćset — dość wolno, żeby zareagować. Osiemset — za późno, to już śmierć."
> *(Wartości: `slow-call-duration-threshold: 500ms`, `slow-call-rate-threshold: 50` z `application.yml`; read-timeout **800 ms z `application-demo.yml`** — baza/prod = 2000 ms. Drabina progów: 300 < 500 < 600 < 800.)*

- ⚠️ Chcesz dalej pokazać retry/partial — najpierw **zlecz** (`-Chaos heal`), poczekaj ~15 s na zielono.

### Krok 2 — CZĘŚCIOWO (retry realnie pracuje) ✂️ *(pomijasz w wersji minimalnej)*
```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\demo-resilience.ps1 -Chaos heal     # najpierw RESET, czekaj ~15 s na zielono
powershell -ExecutionPolicy Bypass -File .\scripts\demo-resilience.ps1 -Chaos partial
```
- 📊 **NA WYKRESIE:** „Failure rate" rośnie do **~50%** (balansuje na progu). Panel „Retry wg rodzaju" — słupki `successful_with_retry` / `failed_with_retry` skaczą. „Maks. latencja" nieudanych ~**800 ms** (read-timeout).
- 🗣 „Teraz połowa wywołań wisi aż do read-timeoutu — *(palec na latencję)* — widzicie, osiemset milisekund, to jest ta granica — i leci błąd. Failure rate idzie pod **pięćdziesiąt procent**. Ale spójrzcie tu — *(palec na panel retry)* — „Retry wg rodzaju": te słupki to wywołania, które retry *uratował* za drugim podejściem. Realnie pracuje, na waszych oczach. Bez tego panelu nawet byście nie wiedzieli, że retry się w ogóle odpala."

### Krok 3 — ZGON (pełna awaria → circuit OPEN na błędach) ✂️ *(pomijasz w wersji minimalnej)*
```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\demo-resilience.ps1 -Chaos down
```
- 📊 **NA WYKRESIE:** „Failure rate" → **100%**. „Stan circuit breakera" → **OPEN** (czerwony), „Stan zależności" → **ODCIĘTA**. Rosną „Wywołania odcięte przez otwarty circuit" i „Trasy oddane do fallbacku".
- 🗣 „A teraz pełna awaria — proxy odrzuca połączenia. Failure rate — *(palec)* — strzela na **sto procent**. Po pięciu wywołaniach circuit OPEN — ale tym razem na *błędach*, nie na wolności jak chwilę temu. Patrzcie tu — *(palec)* — „wywołania odcięte przez otwarty circuit" rosną, a tu „trasy oddane do fallbacku" — system *świadomie* się degraduje, oddaje 409 zamiast wisieć. ⚓ *Te moje dwa błędy na dobę? Byłyby dokładnie tutaj* — na czerwono, głośno, nie do przegapienia."

### Krok 4 — ALERT (na żywo w Prometheusie; slajd 17 pokażesz po powrocie)
- 🎬 **Przełącz kartę na** http://localhost:9090/alerts?search= → wpisz `TradeRoutePlannerCircuitOpen` → **FIRING** (po `for: 30s`).
- 📊 **NA WYKRESIE:** wiersz alertu na czerwono, status **FIRING**. *(Reguła: `infra/docker/prometheus/alerts.yml`, grupa `starport_registry_resilience4j`, w kontenerze `/etc/prometheus/alerts.yml`.)*
- 🗣 „I ten alert mówi wprost, czarno na czerwonym: circuit jest otwarty, zależność leży. Nie muszę tego szukać w logach — sam mnie znalazł. *(za chwilę, na slajdzie 17, domkniemy tę myśl)*"

### Krok 5 — HEAL (system sam wraca)
```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\demo-resilience.ps1 -Chaos heal
```
- 🎬 Po wpisaniu — **wróć na Grafanę.**
- 📊 **NA WYKRESIE:** po ~10 s (`wait-duration-in-open-state`) panel stanu: **half_open** (pomarańcz) → 3 próby → **closed** (zielony). „Failure rate" i „slow call rate" wracają do **0%**.
- 🗣 „Przywracam zależność i nic więcej nie robię — patrzcie. *(palec na panel stanu)* Po dziesięciu sekundach circuit nie wraca od razu do zielonego — najpierw **half-open**, ten pomarańczowy stan: puszcza kilka próbnych wywołań na rozgrzewkę. Udają się… i — *(palec)* — **closed**, zielony. Failure rate spada do zera. System *sam* się pozbierał. I wy *widzieliście* każdy krok tego powrotu — to jest cała różnica."
- 🎬 → **przełącz rzutnik z powrotem na slajdy → slajd 17** (Alert domyka pętlę), tam ląduje puenta „Nigdy więcej odkrywania awarii przez przypadek".
- ⏱ **Checkpoint po AKT 4 (demo + heal): do 15:45.**

### Podgląd stanu w razie wątpliwości
```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\demo-resilience.ps1 -Chaos status   # repliki + stan proxy/toksyn Toxiproxy
```

### 🔎 Jak czytać stronę alertów — http://localhost:9090/alerts?search=
To jest „wnętrze" tego, co domyka pętlę talku. Pole `?search=` filtruje po nazwie/labelach — wpisz `TradeRoutePlanner`, żeby zobaczyć tylko nasze reguły. Każdy alert przechodzi przez **trzy stany** (kolejność = narracja awarii):

| Stan | Kolor | Co znaczy |
|---|---|---|
| **Inactive** | zielony | warunek (`expr`) NIE jest spełniony — zależność zdrowa, cisza |
| **Pending** | żółty | warunek WŁAŚNIE się spełnił, ale jeszcze nie minął czas `for:` — Prometheus czeka, czy to nie chwilowy skok |
| **Firing** | czerwony | warunek trzyma się dłużej niż `for:` — alert realnie „dzwoni" |

W demie po `-Chaos down`: `TradeRoutePlannerCircuitOpen` najpierw przez **30 s** wisi w **Pending** (to jest `for: 30s` — celowy bufor antyszumowy), potem przeskakuje w **Firing**. Po `-Chaos heal` warunek znika i wraca do **Inactive**.

Po rozwinięciu wiersza alertu czytasz:
- **expr** — zapytanie PromQL, które go wyzwala (np. `…circuitbreaker_state{state="open"} == 1`),
- **Labels** — `severity`, `service=starport-registry`, `dependency=trade-route-planner` (po tym się filtruje i routuje),
- **Annotations** — `summary` / `description`, czyli zdanie po ludzku: „Circuit breaker OPEN dla trade-route-planner".

Dwie reguły w grupie **`starport_registry_resilience4j`** (`infra/docker/prometheus/alerts.yml`):
- 🔴 **TradeRoutePlannerCircuitOpen** — circuit OPEN, `for: 30s`, severity *critical*. To jest gwiazda demo (Krok 4) i treść slajdu 17.
- 🟠 **TradeRoutePlannerHighFailureRate** — `failure_rate ≥ 25%`, `for: 1m`, *warning*. Wczesne ostrzeżenie *zanim* circuit się otworzy (próg otwarcia to 50%).

> 💡 *(gotowy wtręt, gdyby ktoś zapytał „a skąd to się bierze?")* 🗣 „Alert nie pojawia się od razu, gdy mrugnie. Najpierw jest *Pending* — żółty, czyli „widzę coś, ale daję temu trzydzieści sekund, żeby sprawdzić, czy to nie czkawka". Dopiero gdy problem się utrzyma, robi się *Firing* — czerwony. To ten sam zdrowy rozsądek, co przy człowieku: nie krzyczysz po pierwszym kaszlnięciu."

#### ⏰ Kiedy DOKŁADNIE zobaczysz `TradeRoutePlannerCircuitOpen` jako Firing
„Active Since" w zrzucie to chwila, w której circuit *się otworzył* — czyli ~1–2 s po `-Chaos down` (po 5 nieudanych wywołaniach, które wypełniają okno `minimum-number-of-calls: 5`). Od tego momentu `state{state="open"}` = **1**, alert wchodzi w **Pending** i tyka `for: 30s`.

- W Twoim zrzucie: Active Since **15:24:31** → **Firing o 15:25:01** (dokładnie +30 s), o ile circuit przez te 30 s zostanie otwarty.
- 🪤 **Pułapka demo:** jeśli zrobisz `-Chaos heal` *przed* upływem ~35 s, circuit się zamknie, `state="open"` spadnie do 0, warunek zniknie — i alert wróci do **Inactive, NIE pokazując Firing**. Żeby na pewno zobaczyć czerwone „Firing", zostaw `-Chaos down` na **≥ 35 s**, dopiero potem ulecz.
- Dlaczego to się nie resetuje samo: co 10 s (`wait-duration-in-open-state`) breaker zerka w half-open, ale przy `down` próba od razu pada i wraca do OPEN — więc `state="open"` praktycznie nie schodzi z 1 i `for:` tyka nieprzerwanie.
- Bonus, gdybyś trzymał `down` dłużej: `TradeRoutePlannerHighFailureRate` ma `for: 1m`, więc zobaczyłbyś dwa zapłony po kolei — **CircuitOpen na 30 s, failure-rate na 60 s**. Dlatego w zrzucie oba są jeszcze `pending`: złapałeś je w pierwszych sekundach po `down`.

> 💡 *(miły wtręt na żywo)* 🗣 „Patrzcie — alert jest teraz żółty, *Pending*. Active Since: dwadzieścia cztery, trzydzieści jeden.
> Czyli dokładnie za trzydzieści sekund, o dwudziestej piątej zero jeden, zrobi się czerwony. Prometheus daje awarii pół minuty na wycofanie się — i dopiero gdy ta się nie wycofa, podnosi alarm."

### 📖 Mapa wszystkich paneli dashboardu (metryka · wzór · zachowanie)
Dashboard ma dwa rzędy: górny **„Dla biznesu — czy zależność żyje?"** (cztery kafelki stat), dolny **„Dla devów — co robi circuit breaker i retry"** (sześć wykresów). Zmienne: `$circuit_name` (domyślnie `trade-route-planner`), `$instance` (domyślnie wszystkie repliki). Skrót faz: **Rozgrzanie → slow300 → slow600 → partial → down → heal**.

#### Rząd 1 — Dla biznesu

**① Stan zależności: $circuit_name** *(stat, tło)* — pełny opis przy **Kroku 0** wyżej.
- Metryka `resilience4j_circuitbreaker_state{state="open"}`; `0 → DOSTĘPNA` (zielone), `1 → ODCIĘTA` (czerwone). Jedno słowo dla sali.

**② Failure rate (próg otwarcia 50%)** *(stat, tło · RZĄD 1)*
- **Metryka:** `resilience4j_circuitbreaker_failure_rate` (%, gauge).
- **Wzór:** `max(resilience4j_circuitbreaker_failure_rate{job="starport-registry", name="$circuit_name", instance=~"$instance"})`
- **Po co:** procent **nieudanych** wywołań w **oknie 10**. Progi koloru tła: **zielony <25 · pomarańcz ≥25 · czerwony ≥50** (= próg otwarcia).
- **Mapowanie `-1 → „—"`:** zanim uzbiera się `minimum-number-of-calls=5`, R4j zwraca **`-1`** — *nie „0"!* — żeby nie otworzyć circuita na jednym pechowym wywołaniu. Panel renderuje to jako „—".
- **Czym jest „okno 10":** `sliding-window-size: 10`, typ `COUNT_BASED` → failure rate liczy się z **ostatnich 10 wywołań**, a nie z okna czasowego. Czyli 3 błędy na ostatnie 10 = 30%. Okno przesuwa się z każdym nowym wywołaniem (najstarsze wypada). Praktyczny skutek: po `heal` wskaźnik **nie spada natychmiast** — musi się „wypłukać" dziesięcioma świeżymi, udanymi wywołaniami.
- **W demie:** 0% (lub „—" na starcie) · 0% · **~0%** · ~50% · ~100% · → 0%.
- **🪤 Pułapka, którą ten panel DEMASKUJE:** przy `slow 600` failure rate to wciąż **~0%** — a circuit i tak się otwiera. Bo „wolne" ≠ „błędne". Gdybyś patrzył *tylko* na ten kafelek, przegapiłbyś awarię. Dlatego tuż obok jest **slow-call rate (panel ⑥)** — druga, niezależna droga do otwarcia.
- **Zaleta:** jeden próg (50%) widać wprost — nie trzeba znać konfiguracji, by ocenić „blisko otwarcia".
- **👁 Jak ZOBACZYĆ pomarańczowy (25–49%, bez otwierania circuita):** domyślny `partial` (toksyczność 0.5) balansuje na 50% → wpada w czerwień / otwiera. Żeby zatrzymać się w pomarańczu, zejdź z toksycznością:
  ```powershell
  powershell -ExecutionPolicy Bypass -File .\scripts\demo-resilience.ps1 -Chaos partial -PartialToxicity 0.3
  ```
  ~30% wywołań pada → failure rate ~30% → **pomarańczowe tło, a circuit zostaje CLOSED** (poniżej 50%). Ładnie pokazuje „strefę ostrzegawczą" *przed* otwarciem — i pokrywa się z alertem `TradeRoutePlannerHighFailureRate@25%`. *(`-Chaos partial` wraca do 0.5; `-Chaos heal` czyści.)*
- **Dlaczego 0.3 daje ~30%, a nie mniej (mimo retry):** `@Retry` owija `@CircuitBreaker`, więc breaker liczy **każdą próbę osobno**. 30% zatrutych prób = ~30% na liczniku CB — *nawet jeśli retry uratuje biznesowe wywołanie za drugim podejściem*, na `failure_rate` i tak widać każdą nieudaną próbę. (Gdyby kolejność była odwrotna — CB na zewnątrz — breaker widziałby tylko wynik końcowy i odsetek byłby niższy.)

**③ Downtime zależności (w zakresie)** *(stat · RZĄD 1)*
- **Metryka:** czas, przez który `state="open"` (czyli `resilience4j_circuitbreaker_state{state="open"}` całkowana po czasie).
- **Wzór:** `sum_over_time(max(resilience4j_circuitbreaker_state{… state="open"})[$__range:3s]) * 3`
- **Po co:** sumuje (próbkując co 3 s i mnożąc ×3) **sekundy**, przez które circuit był OPEN w wybranym zakresie czasu. To „downtime" w języku biznesu — ile łącznie zależność była odcięta. Progi: zielony · **pomarańcz ≥1 s** · **czerwony ≥30 s**.
- **🔬 Co dokładnie znaczy ten wzór (od środka na zewnątrz):**
  1. `resilience4j_circuitbreaker_state{… state="open"}` — gauge per replika: **1** gdy ta replika ma circuit OTWARTY, **0** gdy nie.
  2. `max(…)` — po replikach: **1, jeśli KTÓRAKOLWIEK** replika ma OPEN w danym momencie (widok biznesowy: „odcięte = odcięte, choćby na jednej").
  3. `[$__range:3s]` — to **subquery**: policz powyższe na całym wybranym zakresie (`$__range` = okno z time-pickera), próbkując **co 3 s**. Wynik = ciąg zer i jedynek, jedna próbka na 3 sekundy.
  4. `sum_over_time(…)` — zsumuj te próbki. Skoro każda to 0 lub 1, suma = **liczba 3-sekundowych „slotów", w których circuit był OPEN**.
  5. `* 3` — każdy slot to 3 s, więc liczbę slotów mnożymy przez 3 → **łączny czas OPEN w sekundach**.
- **Dlaczego akurat 3 s:** to rozdzielczość równa interwałowi scrape'u Prometheusa (3 s) — jedna próbka ≈ jeden odczyt. Stąd dwa wnioski praktyczne:
  - To **przybliżenie** w rozdzielczości 3 s — mignięcie krótsze niż 3 s może umknąć albo zostać policzone jako pełne 3 s.
  - Wartość zależy od **wybranego zakresu** (`$__range`): zmień time-picker z „last 15m" na „last 1h" i liczba się zmieni, bo sumuje po tym, co aktualnie widać. To „downtime w pokazywanym oknie", nie „od zawsze".
- **W demie:** rośnie tylko gdy circuit OPEN (slow600, down). Po `heal` **stoi** (to suma za zakres, nie chwilowa wartość) — „zeruje się" dopiero, gdy okres OPEN wypadnie poza wybrany zakres czasu.

**④ Trasy oddane do fallbacku (w zakresie)** *(stat)*
- **Metryka:** `reservations_route_plan_errors_total{errorType="circuit_open"}` (licznik aplikacyjny z `TradeRoutePlannerHttpAdapter`).
- **Wzór:** `sum(increase(reservations_route_plan_errors_total{job="starport-registry", instance=~"$instance", errorType="circuit_open"}[$__range]))`
- **Po co:** ile razy odpalił się fallback, **bo** circuit był otwarty. ⚓ „Te dwa błędy na dobę z mojej historii — byłyby tutaj." Zielone 0 · czerwone ≥1.
- **W demie:** 0 · 0 · rośnie (gdy OPEN odcina) · ~0 · **rośnie** · stoi po heal.

#### Rząd 2 — Dla devów
> **Priorytet na 20-min demo:** rdzeń to **⑤ stan + ⑥ failure/slow rate + ⑨ latencja + ⑧ retry** — na nich opiera się narracja Kroków 1a/1b/2. Panele **⑦ (wywołania wg wyniku)** i **⑩ (odcięte)** to wsparcie „dla chętnych" — pokazują, ale nie musisz ich omawiać. (Rozważałem gotowca z grafana.com #21307, ale jest na labelach `application`/`cluster`, których w tym stacku nie ma — mamy `job=starport-registry`. Przeniosłem z niego tylko dwa najlepsze pomysły: retry **per-minutę** ⑧ i **progi latencji jako linie** ⑨.)

**⑤ Stan circuit breakera (timeline)** *(state-timeline — 3 wiersze stanów)*
- **Metryka:** `resilience4j_circuitbreaker_state` (trzy serie: `closed`/`half_open`/`open`, każda 0/1).
- **Wzór:** `max by (state) (resilience4j_circuitbreaker_state{… state=~"closed|half_open|open"}) == 1`
- **Po co:** każdy stan to osobny wiersz **podpisany po polsku**, wypełniany **TYLKO gdy jest aktywny** (filtr `== 1` wycina zera). Kolory: **ZAMKNIĘTY (działa)** zielony · **PÓŁOTWARTY (próba)** pomarańcz (po `wait-duration-in-open-state=10s`) · **ODCIĘTY (OPEN)** czerwony. Nazwy w wierszu + **legenda** na dole; **żadnych surowych liczb**. Najlepszy panel, by pokazać *sekwencję* powrotu.
- **W demie:** wypełniony wiersz „ZAMKNIĘTY" · „ZAMKNIĘTY" · **„ODCIĘTY"** · „ODCIĘTY" · **„ODCIĘTY"** · „PÓŁOTWARTY" → „ZAMKNIĘTY".
- **🛠 Dlaczego tak (historia dwóch ślepych uliczek):** (1) pierwotnie 3 serie miały **wymuszony kolor** (`fixed`), który maluje też wartości 0 → wszystkie wiersze pełne cały czas. (2) Wersja „jeden pasek = liczba 0/1/2" pokazywała **surowe kody** (`+1`/`+2`/`>1`), bo `color mode: thresholds` etykietuje segmenty zakresami progu, nie słowami. **Rozwiązanie:** 3 serie + filtr `== 1` (wiersz istnieje tylko, gdy stan aktywny) + `displayName` po polsku w `overrides` + `showValue: "never"` → czytelne nazwy, kolory i legenda, zero liczb.

**⑥ Failure rate / slow call rate** *(timeseries; czerwona linia progu 50%)*
- **Metryki:** `resilience4j_circuitbreaker_failure_rate` + `resilience4j_circuitbreaker_slow_call_rate`.
- **Wzór:** dwie serie `max(… failure_rate …) >= 0` oraz `max(… slow_call_rate …) >= 0` (`>=0` ukrywa stan `-1`).
- **Po co:** dwie krzywe + linia progu 50%. Tu widać **dwie drogi do otwarcia**: błędy (failure) i wolność (slow). Panel pod Krok 1a/1b.
- **W demie:** slow300 → slow_call_rate **0%** (pod progiem); slow600 → slow_call_rate skacze, OPEN, failure 0; partial → failure ~50%; down → failure 100%.
- **⚠️ „slow podnosi się dopiero powyżej ~500 ms" — to NIE błąd, to definicja metryki.** `slow_call_rate` liczy wywołania **≥ `slow-call-duration-threshold` = 500 ms** (`application.yml:214`). Poniżej 500 ms jest **faktycznie 0** — i właśnie na tym stoi szara strefa Kroku 1a: `-SlowMs 300` (~350 ms) jest pod progiem (slow=0%, latencja jednak rośnie), `-SlowMs 600` (~640 ms) jest nad progiem (slow skacze). „600" w obserwacji = po prostu jedyna wartość *nad* progiem, jaką testuje demo; między 300 a 600 nie ma kroku. **Magnitudę** spowolnienia (niezależnie od progu) pokazuje panel ⑨ „Maks. latencja".
- **👁 Jak pokazać slow rate rosnący STOPNIOWO (nie skokiem 0→100):** dobierz latencję *blisko* progu, żeby część wywołań była nad, część pod 500 ms:
  ```powershell
  powershell -ExecutionPolicy Bypass -File .\scripts\demo-resilience.ps1 -Chaos slow -SlowMs 520
  ```
  Z jitterem ±50 wartości lądują ~470–570 ms → mniej więcej połowa przekracza 500 ms → slow_call_rate pełznie ku ~50% (balansuje na progu otwarcia). Ładnie widać „wspinanie się", a nie skok.
- **🚫 Czego NIE robić:** nie obniżaj `slow-call-duration-threshold`, żeby slow reagował przy 300 ms — to wywróci Krok 1a (300 ms zacznie otwierać breaker i znika cała pointa „wolne, ale jeszcze nie »slow«").

**⑦ Wywołania wg wyniku (req/s)** *(timeseries, stacked)*
- **Metryka:** `resilience4j_circuitbreaker_calls_seconds_count` (tempo).
- **Wzór:** `sum by (kind) (rate(resilience4j_circuitbreaker_calls_seconds_count{…}[1m]))`
- **Po co:** tempo wywołań wg wyniku — **successful (zielony) / failed (czerwony) / ignored**. Stos = całkowity ruch i udział błędów na jednym wykresie.
- **W demie:** same successful · same successful · successful (wolne) · rośnie **failed** · głównie failed/odcięte · powrót successful.

**⑧ Retry wg rodzaju (na minutę)** *(timeseries)*
- **Metryka:** `resilience4j_retry_calls` (licznik kumulatywny) — ale liczony **per minutę**, więc widać piki, nie schodki.
- **Wzór:** `sum by (kind) (increase(resilience4j_retry_calls{…}[1m]))`
- **Po co:** odpowiedź na „czy retry w ogóle się odpala i ile razy". `kind`: `successful_without_retry` (norma), **`successful_with_retry`** (pomarańcz — retry uratował), **`failed_with_retry`** (czerwony — retry wyczerpany). Panel pod Krok 2.
- **🔧 Zmiana vs poprzednio:** był licznik kumulatywny (`sum(…)`) — rósł schodkami i nie wracał, więc w 20-min demie ciężko było zobaczyć „kiedy retry pracuje". `increase(…[1m])` pokazuje **przyrost na minutę** → wyraźny pik dokładnie podczas `partial`, potem spadek. Czytelniejsze na żywo.
- **W demie:** płasko · płasko · płasko *(slow nie wywołuje retry — brak błędów)* · **pik** `successful/failed_with_retry` · `failed_with_retry` · spadek do 0.

**⑨ Maks. latencja wywołań** *(timeseries, jednostka s → auto-skaluje do ms)*
- **Metryka:** `resilience4j_circuitbreaker_calls_seconds_max`.
- **Wzór:** `max by (kind) (resilience4j_circuitbreaker_calls_seconds_max{…})`
- **Po co:** maks. czas trwania wywołań. Tu widać **szarą strefę**: latencja rośnie, choć błędów nie ma. Panel pod Krok 1a.
- **🆕 Dwie linie odniesienia:** pomarańcz **500 ms** = `slow-call-duration-threshold` (powyżej wywołanie liczy się jako „slow" → rusza panel ⑥); czerwona **800 ms** = read-timeout (powyżej wywołanie pada błędem → rusza failure rate). Na żywo widać, w którą strefę wchodzi latencja: **pod 500** (szara strefa, breaker śpi) · **500–800** (slow → breaker może otworzyć na slow-call) · **nad 800** (błąd).
- **W demie:** ~5–30 ms · **~350 ms** (pod pomarańczową) · **~640 ms** (między liniami) · ~800 ms (przy czerwonej) · — · powrót do ms.
- **🪣 Kubełki (`kind`) — „skąd fail?":** panel grupuje `by (kind)`: **`successful`** = wywołanie się udało (max czas udanego), **`failed`** = padło (5xx / connection refused / **timeout**), **`ignored`** = wyjątek nieliczony do CB (tu brak → 0 s). W `partial` `failed` pokazuje ~**read-timeout** (800 ms demo / 2000 ms prod), bo Toxiproxy trzyma wywołanie bez odpowiedzi aż do timeoutu → to jest **błąd, nie „slow"**. W `slow` wywołanie się *udaje* (→ `successful`), więc `failed` zostaje puste. Zobaczysz `failed` = demo działa poprawnie (to sedno kroku „częściowo").

**⑩ Wywołania odcięte przez otwarty circuit (req/s)** *(timeseries, słupki)*
- **Metryka:** `resilience4j_circuitbreaker_not_permitted_calls_total`.
- **Wzór:** `sum(rate(resilience4j_circuitbreaker_not_permitted_calls_total{…}[1m]))`
- **Po co:** wywołania **odrzucone, bo circuit był OPEN** — dowód, że circuit realnie chroni planera przed dobijaniem („not permitted" = nie dopuszczone do zależności).
- **W demie:** 0 · 0 · **rośnie** (OPEN odcina) · ~0 · **rośnie** · 0 po heal.

#### 🎨 Jak działają kolory i progi na tych panelach (żeby nie zdziwiło na żywo)
- **Kafelki stat (① ②)** mają `colorMode: "background"` — wg progów koloruje się **całe tło kafelka**, nie tylko liczba. Dlatego przy `failure_rate ≥ 50%` cały kafelek ② robi się czerwony. *(Domyślny `colorMode: "value"` kolorowałby samą cyfrę — stąd wcześniejsze wrażenie „tło się nie zmienia".)*
- **Linia progu na wykresie ⑥ (czerwona, 50%)** to **statyczna linia odniesienia** (`thresholdsStyle: "line"`). Pokazuje, gdzie jest próg otwarcia — ale **z definicji NIE wypełnia tła**, nawet gdy krzywa skoczy na 100%. To nie błąd, to celowo „sama kreska": kolor sygnału niesie *położenie krzywej względem linii*, nie zacienienie.
  - Gdybyś chciał, by strefa ≥50% była zacieniona na czerwono (pas zagrożenia), zmień w panelu ⑥ `thresholdsStyle` z `"line"` na **`"line+area"`**. Powrót do samej kreski → `"line"`.
- **Stan circuita ⑤ i „stan zależności" ①** używają **mapowań wartości** (`value mappings`), nie skali liczbowej: konkretna liczba → słowo + kolor (`1 → ODCIĘTA`, `open → czerwony` itd.). Dlatego pokazują słowo, nie procent.
- **Przeładowanie po edycji:** dashboard jest provisionowany z pliku `infra/docker/grafana/provisioning/dashboards/resilience4j-trade-route.json` — Grafana podłapuje zmiany sama (~10 s), wystarczy odświeżyć kartę. Gdyby nie: `docker restart grafana`. Nie edytuj w UI — panel jest read-only, plik nadpisze zmiany.

> 💡 *(wtręt, gdyby ktoś zapytał „skąd te dwa rzędy?")* 🗣 „Górny rząd to *jeden ekran dla szefa* — cztery słowa: dostępna, ile błędów, ile downtime'u, ile fallbacków. Dolny to *to samo dla nas* — surowe krzywe, z których te cztery słowa się biorą. Jeden dashboard, dwa języki."

---

## 📌 Ściąga ZDAŃ-KOTWIC (⚓ — naucz się DOSŁOWNIE)

Jeśli zapamiętasz tylko to, prelekcja i tak się obroni:

1. *(slajd 3)* „To jest *wasza* lista — wrócimy do niej na końcu, co do jednego punktu."
2. *(slajd 4)* „A wiecie, jak to wyszło? Nie z alertu, nie z dashboardu. *Przypadkiem*."
3. *(slajd 6)* „To była awaria *widoczności*. »Działa« — nie znaczy »działa i ja o tym wiem«."
4. *(slajd 9)* „Bez metryki — *nie wiecie*. Samo retry to wciąż czarna skrzynka."
5. *(slajd 10)* „Micrometer jest dla metryk tym, czym SLF4J jest dla logów."
6. *(slajd 11)* „Piszecie *zero* własnego kodu liczników. Zero."
7. *(slajd 14)* „Ten sam sygnał — dwa języki."
8. *(demo 1b)* „Wolne wywołanie to też awaria."
9. *(demo 3)* „Te moje dwa błędy na dobę? Byłyby dokładnie tutaj — na czerwono, głośno."
10. *(slajd 17)* „Nigdy więcej odkrywania awarii przez przypadek."
11. *(slajd 18)* „Ja wam tego nie sprzedałem. To były *wasze* pomysły."
12. *(slajd 19)* „Nie chodzi o to, żeby się nie psuło. Chodzi o to, żeby psucie było *widać*."
13. *(slajd 21)* „Teraz, kiedy w niego strzelacie — *widzicie to*. I to jest cała różnica."

> Te zdania to *kręgosłup*. Reszta to mięśnie — może się trochę ruszać. Kręgosłup ma trzymać.

---

## 3. Po talku
- [ ] `Ctrl+C` w Terminalu A (zatrzymaj ruch).
- [ ] Q&A — `QA-cheatsheet.md` pod ręką.
- [ ] (opcjonalnie) `docker compose -f infra/docker/docker-compose.yml -f infra/docker/docker-compose.demo.yml down`

## 4. Plan B (gdy demo na żywo padnie)
1. Przełącz na **zrzuty stanów CB** w slajdach i opowiadaj po nich.
2. Albo odtwórz **nagranie** próbnego przebiegu (z `preflight-demo.ps1 -Full`).
3. Najgorszy scenariusz: opisz słownie + pokaż regułę alertu na slajdzie 17 (`infra/docker/prometheus/alerts.yml`, grupa `starport_registry_resilience4j` → `TradeRoutePlannerCircuitOpen`).
- **Najczęstsza pułapka:** `failure_rate` = „—" → okno ślizgowe niezapełnione. Lek: pre-warm (Terminal A) ≥1 min przed demem.
- **Twardy wariant chaosu** (gdyby Toxiproxy/overlay nie wstał): `-Chaos kill` (docker stop obu replik) i `-Chaos restore` (docker start). Działają zawsze.

## 5. Ściąga wszystkich komend (kolejność sceniczna)
> Co dokładnie robi każda komenda i jej flagi → sekcja **„🔧 Słownik komend"** wyżej.
```powershell
# T-15 — setup
docker compose -f infra/docker/docker-compose.yml -f infra/docker/docker-compose.demo.yml up -d --build
powershell -ExecutionPolicy Bypass -File .\scripts\preflight-demo.ps1 -Full
# T-2 — Terminal A (zostaw lecący)
powershell -ExecutionPolicy Bypass -File .\scripts\demo-resilience.ps1 -DurationMinutes 30
# AKT 4 — Terminal B (po kolei, ~15–20 s; zlecz między etapami, jeśli circuit został OPEN)
# 1a) 300 ms PODPROGOWO: latencja UP, slow_call_rate 0%, breaker TRZYMA (CLOSED)
powershell -ExecutionPolicy Bypass -File .\scripts\demo-resilience.ps1 -Chaos slow -SlowMs 300
# 1b) 600 ms NADPROGOWO: slow_call_rate -> 50%, circuit OPEN bez błędów
powershell -ExecutionPolicy Bypass -File .\scripts\demo-resilience.ps1 -Chaos slow -SlowMs 600
# reset do zielonego przed partial
powershell -ExecutionPolicy Bypass -File .\scripts\demo-resilience.ps1 -Chaos heal
# partial: ~50% timeout -> failure_rate ~50%, retry pracuje
powershell -ExecutionPolicy Bypass -File .\scripts\demo-resilience.ps1 -Chaos partial
# down: 100% -> circuit OPEN na błędach, fallback 409
powershell -ExecutionPolicy Bypass -File .\scripts\demo-resilience.ps1 -Chaos down
#   -> przełącz na http://localhost:9090/alerts?search=TradeRoutePlannerCircuitOpen (FIRING)
# heal: half_open -> closed, system sam wraca
powershell -ExecutionPolicy Bypass -File .\scripts\demo-resilience.ps1 -Chaos heal
#   -> wróć na slajdy -> slajd 17 (Alert domyka pętlę)
# Po talku
#   Ctrl+C w Terminalu A
```

---

## 6. ✅ Zgranie ze slajdami (kolejność już wdrożona w decku)

Deck `Działa-nie-równa-się-działa_TED.pptx` jest już ułożony tak, jak wygłasza ten runbook — **architektura przed kodem** — więc niczego nie przestawiasz. Dwa slajdy dostały osobne miejsce względem starej wersji (Marp/PDF): **circuit breaker** (slajd 8) i **alert** (slajd 17).

Pełna mapa AKT 3 → demo:
```
slajd 6  — teza („awaria widoczności")
slajd 7  — mapa wzorców
slajd 8  — CIRCUIT BREAKER (bezpiecznik, 3 stany)      ← osobny slajd (był SVG „w zanadrzu")
slajd 9  — zwrot („samo retry to czarna skrzynka")
slajd 10 — Micrometer jako fasada
slajd 11 — metryki za darmo
slajd 12 — ARCHITEKTURA („co w co strzela")            ← przed kodem
slajd 13 — realny kod
slajd 14 — jeden dashboard, dwie publiczności
slajd 15 — DEMO (divider)
slajd 16 — drabina eskalacji (tło demo)
slajd 17 — ALERT domyka pętlę                          ← osobny slajd (był tylko w Prometheusie)
```

Mapowanie ze starej numeracji (Marp/PDF), gdybyś wracał do poprzednich notatek: stary **7** (mapa) → dziś **7 + 8**; stary **8** (zwrot) → **9**; stary **9** (Micrometer) → **10**; stary **10** (metryki) → **11**; **architektura** → **12**; stary **11** (kod) → **13**; stary **12** (dashboard) → **14**; stary **14** (demo) → **15**; stare **15–16** → **16**; stary **17** (lista) → **18**; stary **18** → **19**; stary **19** (konkret) → **20**; stary **20** (klamra) → **21**; stary **21** (dziękuję) → **22**.

---

## 7. ⚠️ Do potwierdzenia na próbie generalnej (dry-run)
1. **Dwa progi slow — zweryfikowane na żywo (29.06):** `-SlowMs 300` → latencja ~355 ms, `slow_call_rate` 0%, circuit **CLOSED** (Krok 1a). `-SlowMs 600` → `slow_call_rate` dochodzi do progu **50%**, circuit **OPEN** przy `failure_rate` 0% (Krok 1b). Po 1b wstaw `-Chaos heal` (~15 s) przed `partial`. Mniej ryzyka = pomiń 1a, wejdź od razu w 1b.
2. **Slajd 14 (dwie kolumny „Dla devów / Dla biznesu")** — rzuć okiem z ostatniego rzędu, czy obie kolumny i puenta „Ten sam sygnał, dwa języki" są czytelne (w PPTX to natywne panele, renderują się zawsze — sprawdzasz tylko wielkość z odległości).
3. **Slajd 13 (kod)** — potwierdź, że długa linia `@CircuitBreaker(… fallbackMethod = FALLBACK)` mieści się (zawija na drugą linię z wcięciem) i podświetlenia składni czytają się z sali.
4. **Kolejność slajdów** — architektura (12) jest przed kodem (13), a alert (17) po demie. Zgodne z sekcją 6 — tylko potwierdź na rzutniku.
5. **Czcionki i znaki na rzutniku** — prezentuj **z tego pliku**, nie ze zrzutu. Sprawdź, że `≠`, `→`, polskie znaki (ł ą ę ś ż ó ń) i „ptaszki" ✓ renderują się poprawnie w PowerPoincie maszyny scenicznej. Deck używa Arial + Courier New (bezpieczne), ale gdyby maszyna miała podmianę czcionek — miej pod ręką **PDF wyeksportowany z decku** jako backup.
6. **Czasy aktów** — przejdź z timerem; AKT 3 (slajdy 6–14, w tym circuit breaker 8 i architektura 12) jest najgęstszy, tam najłatwiej przekroczyć budżet. Doszedł slajd 8 → dołóż ~30 s do checkpointu po AKT 3 (do 12:45).
