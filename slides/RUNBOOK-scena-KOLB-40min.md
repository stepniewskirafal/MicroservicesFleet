# RUNBOOK SCENICZNY — „Działa ≠ działa i ja o tym wiem" · WERSJA KOLB · 40 min

> **Zgrany z deckiem:** `Działa-nie-równa-się-działa-KOLB.pptx` (**24 slajdy**). To NOWY dokument — zastępuje `RUNBOOK-scena.md` dla wersji 40-minutowej. Stary runbook (20 min, historia-najpierw) zostaje jako archiwum.
>
> **Kluczowa zmiana dramaturgii (cykl Kolba):** publiczność NAJPIERW ogląda żywy system i widzi, jak się psuje — **bez komentarza z Twojej strony**. Sama nazywa problem. Dopiero potem padają nazwy wzorców. Twoja historia „3 miesiące" przychodzi jako potwierdzenie ich odkrycia, nie jako otwarcie.
>
> Legenda w każdym kroku:
> **🗣 MÓWISZ** — gotowy tekst. Fragmenty z ⚓ mów DOSŁOWNIE (kręgosłup), resztę parafrazuj — ale tempo i pauzy zostaw.
> **🎭 JAK** — sposób podania (pauza, ręka w górę, tempo, gest, CISZA).
> **🎬 ROBISZ** — akcja: slajd / komenda w terminalu / przełącz okno / flipchart.
> **📊 NA WYKRESIE** — który panel dashboardu i jaka liczba; pokazuj palcem to, o czym mówisz.
> **⏱ CHECKPOINT** — jeśli jesteś dalej niż ten czas, tnij wskazane fragmenty.
>
> **⚠️ Komendy `.ps1` uruchamiasz z `cmd`** — stąd przedrostek `powershell -ExecutionPolicy Bypass -File`. Tekst po `#` to komentarz dla Ciebie, nie wklejaj go do `cmd`.

---

## 0. Budżet czasu — 40 minut z buforem

| Akt Kolba | Slajdy | Czas | Skumulowane |
|---|---|---|---|
| **Wejście** | 1–2 | 2:30 | 2:30 |
| **1. DOŚWIADCZENIE** — demo-ślepe (psujesz bez słowa) | 3–4 + Grafana | 8:00 | 10:30 |
| **2. REFLEKSJA** — sala nazywa problem + Twoja historia | 5–9 | 8:30 | 19:00 |
| **3. KONCEPTUALIZACJA** — nazwanie wzorców | 10–16 | 8:30 | 27:30 |
| **4. ZASTOSOWANIE** — naprawa na żywo, krok po kroku | 17–20 + Grafana | 8:00 | 35:30 |
| **5. SYNTEZA** — flipchart, Spring Boot 4, klamra | 21–24 | 4:30 | **40:00** |

**Bufor jest wpisany W akty, nie po nich**: każda interakcja z salą ma limit odpowiedzi (max 3) i twardy checkpoint. Jeśli sala milczy — masz przygotowane „odpowiedzi podstawione" (niżej). Jeśli sala gada za dużo — masz zdania ucinające. Realny zapas: ~3 min ukryte w checkpointach (każdy akt ma wskazane „co ciąć").

---

## 1. Setup — T-20 → T-2 (dłużej niż w wersji 20-min, bo demo startuje wcześniej!)

### T-20 — podnieś stack Z OVERLAYEM DEMO
```powershell
cd C:\softwareEngineer\MicroservicesFleet
docker compose -f infra/docker/docker-compose.yml -f infra/docker/docker-compose.demo.yml up -d --build
docker ps --format "table {{.Names}}\t{{.Status}}"
powershell -ExecutionPolicy Bypass -File .\scripts\preflight-demo.ps1 -Full
```
`preflight-demo.ps1 -Full` MUSI zwrócić **0**. To też moment na nagranie ekranu (Plan B).

### T-5 — uruchom zdrowy ruch (Terminal A) — WCZEŚNIEJ niż w starej wersji
```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\demo-resilience.ps1 -DurationMinutes 60
```
**`-DurationMinutes 60`, nie 30** — demo-ślepe zaczyna się w 3. minucie talku, a generator musi lecieć do końca (40 min + Q&A). Okno ślizgowe CB musi być PEŁNE zanim wejdziesz — inaczej failure rate pokaże „—" i pierwsza awaria będzie nieczytelna.

### Checklista (odhacz przed wejściem)
- [ ] `preflight-demo.ps1 -Full` = **0**
- [ ] Terminal A: zdrowy ruch leci od ≥3 min (okno CB pełne, failure rate = 0%, nie „—")
- [ ] Grafana: http://localhost:3000 → dashboard **„Resilience4j — starport→trade-route (odporność widzialna)"**, zakres **ostatnie 15 min**, refresh 5 s
- [ ] Prometheus: http://localhost:9090/alerts?search=TradeRoutePlanner (druga karta)
- [ ] Terminal B otwarty, pusty, gotowy na `-Chaos`
- [ ] `QA-cheatsheet.md` na drugim ekranie
- [ ] Flipchart + marker (AKT 2 i AKT 5)
- [ ] Backup: PDF decku + zrzuty stanów CB (zielony / OPEN / half_open) + nagranie z preflight

### Układ ekranu
| Element | Co |
|---|---|
| **Ekran 1 (rzutnik)** | Slajdy; w AKT 1 i AKT 4 przełączasz na Grafanę |
| **Ekran 2 (twój)** | Ten runbook + `QA-cheatsheet.md` + timer |
| **Terminal A** | zdrowy ruch (leci, nie dotykasz) |
| **Terminal B** | komendy `-Chaos` na żywo |

---

## 2. Slajd po slajdzie — pełny tekst mówiony

# WEJŚCIE (0:00 → 2:30)

### Slajd 1 — Tytuł · ~15 s
- 🗣 **MÓWISZ:** *(nic — nie czytaj tytułu)*
- 🎭 **JAK:** wejdź, stań, 2 s ciszy, spójrz na salę. Obecność przed mówieniem.
- 🎬 **ROBISZ:** od razu → slajd 2.

### Slajd 2 — „Wyobraźcie sobie" · ~2 min
- 🗣 **MÓWISZ:**
  „Wyobraźcie sobie zwykły wtorek. Piszecie serwis. Jeden komponent musi zapytać drugi — przez sieć, zwykłym HTTP-em. Piszecie ten jeden, niewinny request. Wysyłacie. Czekacie na odpowiedź. *(pauza)* I tyle.

  Nie ma retry — jak nie odpowie, to nie odpowie. Nie ma timeoutu z prawdziwego zdarzenia. Nie ma metryki. Nikt nie patrzy. Pipeline zielony, deploy poszedł, idziecie na kawę. *(pauza)* Działa.

  *(pauza 2 s, ręka w górę)* A teraz szczerze — ręka w górę — kto z was ma w swoim systemie *taki właśnie* request? Jeden. Taki, którego tak naprawdę nikt nie pilnuje."
- 🎭 **JAK:** czekaj na ręce. Policz na głos: „Sporo. Zapamiętajcie tę rękę."
- 🎬 **ROBISZ:** → slajd 3. **NIE zapowiadaj demo. NIE mów, że coś się zepsuje.**
- ⏱ **CHECKPOINT: 2:30.**

---

# AKT 1 — DOŚWIADCZENIE KONKRETNE (2:30 → 10:30) · demo-ślepe

> **Zasada żelazna tego aktu: nie nazywasz ANI JEDNEGO zjawiska.** Nie mówisz „latencja", „timeout", „circuit breaker". Mówisz tylko: „patrzcie". Sala ma poczuć dyskomfort odkrywania, zanim dostanie słownik.

### Slajd 3 — Mapa systemu („Co w co strzela") · ~1,5 min
- 🗣 **MÓWISZ:**
  „Zanim cokolwiek pokażę — 60 sekund na mapę, żebyście wiedzieli, na co patrzycie. Klient strzela przez gateway. Gateway woła starport-registry — dwie repliki — który robi rezerwację doku. Rezerwacja potrzebuje trasy, więc starport pyta trade-route-planner — też dwie repliki. *(palec z góry na dół)*

  To wywołanie — starport do plannera — to nasz bohater. Zwykły request HTTP między dwoma serwisami. Dokładnie taki, o jaki pytałem przed chwilą. *(pauza)* System żyje. Właśnie teraz, za moimi plecami, leci przez niego prawdziwy ruch — cztery rezerwacje na sekundę."
- 🎭 **JAK:** rzeczowo, bez dramaturgii. Mapa to instrukcja obsługi, nie opowieść. **NIE wspominaj o Toxiproxy** — strzałka „DEMO wstrzykuje" na slajdzie jest, ale jej nie tłumacz; jak ktoś zapyta w Q&A, powiesz.
- 🎬 **ROBISZ:** → slajd 4.

### Slajd 4 — „Popatrzcie na ten ekran." · ~30 s + przełączenie
- 🗣 **MÓWISZ:**
  ⚓ „Popatrzcie na ten ekran. Żywy system. Prawdziwy ruch. *(pauza)* Nic więcej nie mówię."
- 🎬 **ROBISZ:** **PRZEŁĄCZ RZUTNIK NA GRAFANĘ.** Dashboard zielony: DOSTĘPNA, failure rate 0%, timeline zielony, ~3–4 req/s successful.
- 📊 **NA WYKRESIE:** wskaż tylko dwa miejsca palcem, bez nazw: kafelek „DOSTĘPNA" („to jest dobre") i strumień req/s („to jest ruch"). Tyle.

### 🎬 DEMO-ŚLEPE — sekwencja Terminala B · ~6 min

> Wszystko poniżej robisz **przy Grafanie na rzutniku**. Wpisujesz komendy w Terminal B **bez tłumaczenia, co robią** — najlepiej miej je wklejone wcześniej i tylko naciskaj Enter. Między krokami: **CISZA + patrzenie na salę**.

**Krok 1 — pełzająca degradacja (slow 300):**
```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\demo-resilience.ps1 -Chaos slow -SlowMs 300
```
- 🗣 **MÓWISZ** *(po ~20 s, gdy panel latencji drgnie)*: „Coś się zmieniło. *(pauza)* Kto widzi — gdzie?"
- 📊 **NA WYKRESIE:** JEDYNY panel, który reaguje, to „Maks. latencja wywołań" (~0,02 s → ~0,35 s). Failure rate dalej 0%. Stan dalej DOSTĘPNA. **Nie pokazuj palcem — niech szukają.**
- 🎭 **JAK:** zbierz 1–2 głosy. Ktoś powie „latencja urosła" — kiwnij głową: „Coś urosło. Zapamiętajcie który wykres. Idziemy dalej." **Nie potwierdzaj słownika, nie tłumacz progu 500 ms.**
- *(jeśli sala milczy 15 s)*: „Podpowiem: dziewięć paneli się nudzi. Jeden nie."

**Krok 2 — degradacja nad progiem (slow 600):**
```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\demo-resilience.ps1 -Chaos slow -SlowMs 600
```
- 🗣 **MÓWISZ** *(gdy slow_call_rate zaczyna się wspinać)*: „A teraz? *(cisza 10 s)* Patrzcie na prawy górny róg drugiego rzędu. I na pas czasu."
- 📊 **NA WYKRESIE:** slow_call_rate wspina się do 50% → timeline przeskakuje na CZERWONY → kafelek „ODCIĘTA" → słupki „not permitted" startują. **Failure rate przez cały czas ~0%** — to jest bomba tego kroku.
- 🎭 **JAK:** gdy zrobi się czerwono, zapytaj: ⚓ „Ile błędów widzicie na liczniku błędów? *(pauza)* Zero. A system właśnie sam sobie odciął zależność. *(pauza)* Zapamiętajcie ten obrazek. Wrócimy do niego."
- **NIE tłumacz czemu.** To pytanie zostaje otwarte do AKT 3.

**Krok 3 — twarda awaria (down):**
```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\demo-resilience.ps1 -Chaos down
```
- 🗣 **MÓWISZ:** „Ostatni ruch. *(Enter, cisza)* Co się różni od poprzedniego?"
- 📊 **NA WYKRESIE:** failure rate → 100%, czerwony kafelek, timeline czerwony, latencja *failed* krótka (connection refused). Kontrast z krokiem 2: tam czerwono BEZ błędów, tu czerwono OD błędów.
- 🎭 **JAK:** 1 głos z sali wystarczy. Zamknij akt: ⚓ „Trzy razy zepsułem ten sam system. Trzy różne obrazki. *(pauza)* A teraz najważniejsze pytanie tego wystąpienia—"

**Przywróć system PO CICHU (będzie się leczył w tle podczas AKT 2):**
```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\demo-resilience.ps1 -Chaos heal
```
- 🎬 **ROBISZ:** Enter bez słowa, **przełącz rzutnik z powrotem na slajdy** → slajd 5.
- ⏱ **CHECKPOINT: 10:30.** Jeśli później — w AKT 2 tnij do 2 odpowiedzi sali zamiast 3 i skróć slajd 8 do samej trójki „Nie powinny / Nie pewnie tak / Wie".

**Plan B tego aktu (demo padło):** przełącz na nagranie z preflight ALBO na zrzuty ekranu w decku. Mów dokładnie to samo — pytania do sali działają tak samo na nagraniu. Najgorszy wariant: „Zaufajcie mi na słowo przez 20 minut — na końcu pokażę zrzuty" i przeskocz do slajdu 5.

---

# AKT 2 — REFLEKSJA (10:30 → 19:00) · sala nazywa, Ty potwierdzasz historią

### Slajd 5 — „Co widzicie?" · ~3 min · INTERAKCJA GŁÓWNA
- 🗣 **MÓWISZ:**
  ⚓ „Co widzieliście? Nie mówię. Wy mi powiedzcie. *(pauza)* Trzy pytania na ekranie: co się zmieniło na wykresach? Które requesty zginęły, a które tylko zwolniły? I trzecie — moje ulubione — skąd ten system w ogóle WIEDZIAŁ, że jest źle?"
- 🎭 **JAK:** otwarta dłoń, zbierz **max 3 odpowiedzi**. Na każdą: „dobre", „notuję", „o tym za chwilę" — **nie oceniaj merytorycznie, nie prostuj, nie dopowiadaj nazw wzorców.** Jeśli ktoś powie „circuit breaker" — uśmiech: „Ktoś zna słowo. Nie psuj innym zabawy — za dziesięć minut wszyscy będą je znali."
- **Odpowiedzi podstawione** (gdy sala milczy): „Widzieliście, że najpierw urósł tylko jeden wykres? Że potem zrobiło się czerwono, choć licznik błędów stał na zerze? Że na końcu system przestał w ogóle wołać tamten serwis?"
- 🎬 **ROBISZ:** → slajd 6.

### Slajd 6 — „A może powinniśmy…" + FLIPCHART · ~2,5 min · INTERAKCJA
- 🗣 **MÓWISZ:**
  „Dobrze. To teraz odwróćmy: gdybyście projektowali ten system od zera — co byście dodali do tego jednego requestu, żeby spać spokojnie? Może sprawdzać, czy tamten serwis żyje? Mierzyć, jak długo odpowiada? Mieć sygnał, który krzyknie? *(pauza)* Powiedzcie — co byście dodali?"
- 🎭 **JAK:** **FLIPCHART** — wypisuj hipotezy sali (health-check, retry, timeout, alert, metryka, odcięcie...). To ICH lista. Max 3 min.
- ⚓ **Most:** „Zapamiętajcie tę listę. Wrócimy do niej na końcu — co do jednego punktu. A teraz powiem wam, dlaczego ten temat jest dla mnie osobisty."
- 🎬 **ROBISZ:** → slajd 7.

### Slajd 7 — „3 miesiące" · ~1,5 min · 💙 SERCE OPOWIEŚCI
- 🗣 **MÓWISZ:**
  „Jakiś czas temu wdrożyliśmy nową usługę. Wszystko na zielono. Testy przeszły, deploy przeszedł, ruch poszedł. Klasyka. Tylko jeden adres — poprawnie sformatowany, odpowiadający w testach — był *niewłaściwy*. I nasz system, taki jak ten, który przed chwilą oglądaliście — tyle że BEZ tych ekranów — wysyłał requesty w zły adres. *(pauza)* Przez trzy miesiące.

  Bez retry, bez metryk, bez sygnału. A wiecie, jak wyszło? Nie z alertu. Nie z dashboardu. *(pauza)* Przypadkiem. Ktoś przeglądał logi z zupełnie innego powodu. Dwa błędy na dobę — utopione w tysiącach linii szumu."
- 🎭 **JAK:** zwolnij, mów jak do jednej osoby, BEZ wymówek. PAUZA po „trzy miesiące".
- 🎬 **ROBISZ:** → slajd 8.

### Slajd 8 — „Ilu z was wie…" · ~45 s
- 🗣 **MÓWISZ:**
  ⚓ „Ilu z was wie — w tej chwili — że wszystkie wasze zależności żyją? Nie »powinny«. Nie »pewnie tak«. *(pauza)* Wie."
- 🎭 **JAK:** ZOSTAW CISZĘ. Nie odpowiadaj za nich. Nie zapowiadaj, że ma zaboleć.
- 🎬 **ROBISZ:** → slajd 9.

### Slajd 9 — TEZA · ~1 min · ⭐ NAJWOLNIEJ W CAŁYM TALKU
- 🗣 **MÓWISZ:**
  „To, co widzieliście na demo — czerwony ekran przy zerowym liczniku błędów — i to, co mnie spotkało — trzy miesiące ciszy — to jest TA SAMA awaria. *(pauza)* To nie była awaria retry. Retry by tu nic nie zmienił. ⚓ To była awaria *widoczności*. *(pauza)* »Działa« nie znaczy »działa i ja o tym wiem«. Między tymi dwoma zdaniami zmieściły się moje trzy miesiące."
- ⏱ **CHECKPOINT: 19:00.** Jeśli później — w AKT 3 tnij slajd 15 (Micrometer-fasada) do jednego zdania i pomiń ciekawostkę o cebuli na slajdzie 10.

---

# AKT 3 — KONCEPTUALIZACJA (19:00 → 27:30) · teraz dostają słownik

### Slajd 10 — Mapa wzorców · ~1,5 min
- 🗣 **MÓWISZ:**
  „Wszystko, co wykrzyczeliście na flipchart — i wszystko, co widzieliście na ekranie — ktoś już nazwał i zaimplementował. Resilience4j. Pięć wzorców: Retry — coś mrugnęło, ponów. CircuitBreaker — zależność leży, odetnij. TimeLimiter — nie czekaj w nieskończoność. Bulkhead — odgrodź zasób. RateLimiter — nie przeciążaj. *(pauza)* I uwaga: to są koncepty, nie biblioteka. W dobie AI kod napiszecie w każdym języku — w Go to będzie gobreaker, w .NET Polly. Liczy się, czy używacie konceptów *poprawnie*. I o tym są następne dwa slajdy."
- 🎬 **ROBISZ:** → slajd 11.

### Slajd 11 — Circuit breaker = bezpiecznik · ~2 min
- 🗣 **MÓWISZ:**
  „Ten czerwony ekran z demo — to był circuit breaker. Bezpiecznik. Jak w domu: iskrzy — odcina prąd. Trzy stany: ZAMKNIĘTY — ruch przechodzi, bezpiecznik tylko liczy błędy i czasy. OTWARTY — zależność padła, odcinamy, oddajemy plan B; nie dobijamy leżącego, a klient nie czeka w próżni. PÓŁOTWARTY — po dziesięciu sekundach puszczamy kilka prób: działa → wracamy do zielonego, nie działa → znów odcinamy.

  I teraz zagadka z demo: pamiętacie krok drugi? Czerwono, a licznik błędów zero. *(pauza)* Bo mój bezpiecznik liczy DWIE rzeczy: procent błędów — i procent wywołań *wolniejszych niż pół sekundy*. Awaria »działa, ale umiera« — której żaden licznik 5xx nie złapie. To jest odpowiedź na pytanie, które zostawiłem otwarte."
- 🎭 **JAK:** to jest domknięcie pętli z AKT 1 — sala powinna mieć moment „aaa, TO dlatego". Daj im go: pauza po „procent wywołań wolniejszych niż pół sekundy".
- 🎬 **ROBISZ:** → slajd 12.

### Slajd 12 — Kolejność ma znaczenie (pułapka #2383) · ~2,5 min · NOWY
- 🗣 **MÓWISZ:**
  „Teraz rzecz, na której wykładają się seniorzy. W Springu te wzorce układają się w cebulę: domyślnie Retry owija CircuitBreaker. Brzmi niewinnie. Policzmy: metoda rzuca wyjątek, retry ma trzy próby. Ile porażek zobaczy circuit breaker? *(pauza, zbierz 1 odpowiedź)* Trzy. Nie jedną. Bo każda próba retry to dla breakera OSOBNE wywołanie. Jedna logiczna operacja = trzy »failed calls«. Wasz próg 50% failure rate? Realnie zadziała dużo wcześniej, niż myślicie. To zgłoszony problem — issue 2383 w repo Resilience4j.

  I pułapka w pułapce: kolejność adnotacji na metodzie NIC nie zmienia. Możecie pisać @Retry nad @CircuitBreaker albo odwrotnie — cebula jest zaszyta na stałe. Chcecie »jedna operacja = jeden wynik«? Musicie to zrobić świadomie: właściwość aspectOrder — niższa wartość znaczy bardziej na zewnątrz. CircuitBreaker: 1, Retry: 2 — i breaker widzi jeden finalny wynik całej operacji."
- 🎭 **JAK:** pytanie „ile porażek?" zadaj serio i poczekaj — połowa sali powie „jedną". To jest moment nauki.
- 🎬 **ROBISZ:** → slajd 13.

### Slajd 13 — Retry nie jest za darmo · ~1,5 min · NOWY
- 🗣 **MÓWISZ:**
  „Druga zasada dojrzałego retry: ponawiaj tylko to, co MOGŁO się udać. 5xx, timeout — błędy przejściowe, sieć mrugnęła, druga próba może trafić na zdrową replikę. Ponów. Ale 4xx? To błąd deterministyczny — TWÓJ błąd. Zły request, brak uprawnień. Druga próba da dokładnie ten sam wynik, tylko dołoży ruchu i zatruje failure-rate breakera błędami, które nie są awarią zależności. Konfiguracyjnie: ignore-exceptions z HttpClientErrorException.

  I kontrapułapka: nie zignorujcie za szeroko. ignore-exceptions: RuntimeException i wasz breaker jest ślepy — nigdy się nie otworzy, bo prawie wszystko ignoruje. Ignorujcie wąsko i świadomie."
- 🎬 **ROBISZ:** → slajd 14.

### Slajd 14 — Zwrot akcji: czarna skrzynka · ~1 min
- 🗣 **MÓWISZ:**
  „No dobrze — dodaliście retry, breaker, we właściwej kolejności, z właściwymi wyjątkami. Brawo. Tylko... skąd wiecie, że retry się w ogóle ODPALIŁ? Ile razy? Czy circuit otworzył się w nocy i zamknął, a wy spaliście? *(pauza)* ⚓ Bez metryki — nie wiecie. Dorzuciliście mechanizm, ale dalej go nie WIDZICIE. Retry bez observability to mój błąd sprzed trzech miesięcy — tylko schowany o warstwę głębiej."
- 🎬 **ROBISZ:** → slajd 15.

### Slajd 15 — Micrometer jako fasada · ~1 min
- 🗣 **MÓWISZ:**
  „Jak to zobaczyć? Micrometer. ⚓ Micrometer jest dla metryk tym, czym SLF4J jest dla logów — jeden interfejs, backend dowolny: Prometheus, Datadog, co chcecie, decyduje zależność w pom.xml. W Boot 3 jeden HTTP call opakowuje się w jedną Observation — i z niej wypadają I metryki, I trace'y. Boot robi to za was."
- *(tnij ten slajd do JEDNEGO zdania z ⚓, jeśli goni czas)*
- 🎬 **ROBISZ:** → slajd 16.

### Slajd 16 — Metryki za darmo · ~1 min
- 🗣 **MÓWISZ:**
  „A Resilience4j? Zero kodu liczników. Trzy zależności — actuator, resilience4j, micrometer-registry-prometheus — i macie: stan circuita, failure rate, czasy wywołań, liczniki retry. Wszystko, co oglądaliście na tamtym dashboardzie, wyszło z tych trzech linijek w pom. Nie napisałem ANI JEDNEGO licznika do tego, co widzieliście." *(uwaga dla Ciebie: jeden licznik domenowy — fallback — napisałeś; przyznasz to na slajdzie 17, tu nie komplikuj)*
- ⏱ **CHECKPOINT: 27:30.** Jeśli później — AKT 4: pokaż tylko kroki partial + heal (pomiń powtórkę slow), zaoszczędzisz ~2 min.

---

# AKT 4 — ZASTOSOWANIE (27:30 → 35:30) · naprawa na żywo

### Slajd 17 — Realny kod · ~1,5 min
- 🗣 **MÓWISZ:**
  „To jest CAŁY kod, który stoi za tamtym demo. Dwie adnotacje nad metodą: @Retry i @CircuitBreaker z fallbackiem. *(wskaż bursztynowe linie — nie czytaj metody)* I jedna rzecz, którą chcę żebyście zobaczyli: fallback NIE MILCZY. Liczy metrykę — errorType circuit_open — i rzuca wyjątek, który kontroler zamienia na 409. Rezerwacja bez trasy jest świadomie odrzucona z czytelnym kodem. Ten fallback mówi: wiem, że jest gorzej — i wiem, O ILE gorzej. To jedyny licznik, który napisałem ręcznie — bo tłumaczy awarię techniczną na język produktu: ile REZERWACJI straciło trasę."
- 🎬 **ROBISZ:** → slajd 18.

### Slajd 18 — Dashboard, dwie publiczności · ~1 min
- 🗣 **MÓWISZ:**
  „Ten dashboard, który oglądaliście, ma dwa rzędy — celowo. *(gest w lewo)* Rząd dla devów: failure rate, retry, stan circuita, mechanika. *(gest w prawo)* Rząd dla biznesu, na TYM SAMYM ekranie: czy zależność żyje, ile downtime'u, ile tras oddaliśmy. ⚓ Ten sam sygnał — dwa języki. Nie musicie tłumaczyć technicznego na ludzki, dashboard robi to za was. *(pauza)* A teraz zamknijmy pętlę. Wracamy do żywego systemu — tym razem z pełnym słownikiem."
- 🎬 **ROBISZ:** **PRZEŁĄCZ RZUTNIK NA GRAFANĘ** (system po `heal` z AKT 1 jest zielony — timeline pokazuje historię: widać czerwone epizody z demo-ślepego!).

### 🎬 DEMO-NAPRAWA — sekwencja Terminala B · ~4,5 min

> Różnica względem AKT 1: **teraz wszystko nazywacie RAZEM.** Sala zna słowa — Ty pokazujesz palcem, oni mówią, co się dzieje. Zamień wykład w quiz.

**Krok 1 — partial (jedyny scenariusz, którego NIE widzieli!):**
```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\demo-resilience.ps1 -Chaos partial
```
- 🗣 **MÓWISZ:** „Nowy scenariusz — tego rano nie było: połowa wywołań wisi aż do timeoutu. Patrzcie na panel retry — pierwszy raz dziś ożyje. *(czekaj ~20 s)* successful_with_retry rośnie: retry realnie RATUJE wywołania — druga próba trafia na zdrową ścieżkę. failed_with_retry też rośnie: część drugich prób znowu wisi. Retry nie jest dekoracją — tu jest dowód. A failure rate balansuje na progu 50% — widzicie, jak breaker się waha?"
- 📊 **NA WYKRESIE:** panel ⑧ Retry (liczniki rosną — skok = retry się odpalił), failure rate ~50%, latencja failed ~0,8 s (read-timeout).
- 🎭 **Pytanie-quiz do sali:** „Czemu na tym panelu liczniki tylko rosną i nigdy nie spadają? *(1 odpowiedź)* Bo to liczniki kumulatywne — rate() pokazywałby zera między zdarzeniami i kłamał, że retry nie pracuje."

**Krok 2 — down + ALERT:**
```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\demo-resilience.ps1 -Chaos down
```
- 🗣 **MÓWISZ:** „Pełna awaria. Sala — co się zaraz stanie? *(zbierz: »otworzy się«)* Otworzy się. *(czerwony)* I teraz rzecz, której rano nie pokazałem—"
- 🎬 **ROBISZ:** przełącz kartę na **http://localhost:9090/alerts** → `TradeRoutePlannerCircuitOpen` przechodzi PENDING → **FIRING**.
- 🗣 ⚓ „Nie opowiadam wam o alercie. On się właśnie odpalił — na waszych oczach. Te dwa błędy na dobę z mojej historii? Byłyby TUTAJ. Trzy miesiące byłyby trzydziestoma sekundami."
- 📊 **NA WYKRESIE** (wróć do Grafany): słupki not-permitted — „każdy słupek to wywołanie, które NIE poszło dobijać leżącego serwisu. Fail-fast w mikrosekundach zamiast wiszenia 800 milisekund."

**Krok 3 — heal:**
```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\demo-resilience.ps1 -Chaos heal
```
- 🗣 **MÓWISZ:** „I finał. Naprawiam zależność — i NIC więcej nie robię. Nie restartuję, nie klikam. *(czekaj ~10–15 s)* Pomarańczowy — półotwarty: system SAM sprawdza, czy tamten wrócił. Trzy próbne wywołania... *(zielony)* Zielony. ⚓ System sam się zdiagnozował, sam się odciął, sam sprawdził i sam wrócił. A my widzieliśmy każdy krok."
- 📊 **NA WYKRESIE:** timeline open → half_open → closed. Zostaw ten obraz na 3 s ciszy.
- 🎬 **ROBISZ:** **PRZEŁĄCZ NA SLAJDY** → slajd 19–20 przewiń szybko (są rezerwowe: „Demo" i „Alert domyka pętlę" — użyj ich TYLKO jeśli demo padło i mówisz ze zrzutów), normalnie → slajd 21.
- ⏱ **CHECKPOINT: 35:30.** Jeśli później — slajd 21 (Spring Boot 4) skróć do dwóch zdań z ⚓.

**Plan B tego aktu:** zrzuty stanów CB w decku (slajdy 19–20) + PromQL alertu na slajdzie. Mów w czasie przeszłym: „na próbie wyglądało to tak".

---

# AKT 5 — SYNTEZA (35:30 → 40:00) · „po to są te narzędzia"

### Slajd 21 — Spring Boot 4: forward · ~1,5 min · NOWY
- 🗣 **MÓWISZ:**
  „Zanim zamknę — jedno spojrzenie w przód, żebyście wyszli stąd aktualni. Spring Boot 4, Framework 7: retry i limit współbieżności wchodzą do RDZENIA. @EnableResilientMethods, @Retryable, @ConcurrencyLimit — zero zewnętrznych zależności. Trzy rzeczy, które musicie wiedzieć: raz — @Retryable liczy 1 wywołanie plus maxRetries, domyślnie razem cztery. Dwa — @ConcurrencyLimit BLOKUJE, gdy limit pełny, nie odrzuca; chcecie fail-fast — to nadal Bulkhead z R4j. Trzy — ⚓ to jest podzbiór, nie zamiennik: circuit breaker, rate limiter, time limiter, slow-call, fallback deklaratywny — po to wszystko nadal idziecie do Resilience4j. Zmienia się dostawca adnotacji. Zasada — »instrumentuj to, czemu ufasz« — zostaje."
- 🎬 **ROBISZ:** → slajd 22. **Podejdź do flipchartu.**

### Slajd 22 — Wróćmy do listy · ~1,5 min
- 🗣 **MÓWISZ** *(odhaczaj markerem NA ŻYWO)*:
  „Wasza lista. Sprzed pół godziny. Health-check? *(odhacz)* Macie — kafelek DOSTĘPNA/ODCIĘTA. Retry? *(odhacz)* Jest — i widzieliście panel, który udowadnia, że pracuje. Timeout? *(odhacz)* Jest — 800 milisekund, widzieliście go na wykresie latencji. Alert? *(odhacz)* Nie opowiadałem — odpalił się na waszych oczach. Metryka? *(odhacz)* Wszystko, co dziś oglądaliście, to były metryki. ⚓ Ja wam niczego nie sprzedałem. To były WASZE pomysły. Narzędzia — Resilience4j, Micrometer, Grafana — po prostu je spełniają. Widzieliście je w działaniu: najpierw jak wykrywają problem, którego nikt nie zapowiedział. Potem jak mu zapobiegają i jak go rozwiązują — same."
- 🎬 **ROBISZ:** → slajd 23.

### Slajd 23 — Klamra · ~45 s · ⭐ ZDANIE DO ZAPAMIĘTANIA
- 🗣 **MÓWISZ** *(wolno, pauza między zdaniami)*:
  „System się BĘDZIE psuł. Sieć padnie, zależność zwolni, ktoś wdroży zły adres — jak ja. *(pauza)* ⚓ Nie chodzi o to, żeby się nie psuło. Chodzi o to, żeby psucie było WIDAĆ."
- 🎬 **ROBISZ:** → slajd 24.

### Slajd 24 — Dziękuję / Q&A
- 🗣 **MÓWISZ:** „Dziękuję. *(pauza, uśmiech)* Pytania?"
- 🎬 **ROBISZ:** `QA-cheatsheet.md` pod ręką. Nowa amunicja względem starej wersji: pytania #1–12 z cheatsheeta + kolejność aspektów (slajd 12 masz jako kotwicę do pokazania) + Spring Boot 4 (slajd 21, w tym trzy doprecyzowania z sekcji 11-cd cheatsheeta).
- ⏱ **CHECKPOINT KOŃCOWY: 40:00.**

### Po talku
- [ ] Ctrl+C w Terminalu A (zatrzymaj generator)
- [ ] (opcjonalnie) `docker compose -f infra/docker/docker-compose.yml -f infra/docker/docker-compose.demo.yml down`

---

## 3. Mapa „stary slajd → nowy slajd" (dla Twojej pamięci mięśniowej)

| Nowy # | Treść | Pochodzenie |
|---|---|---|
| 1 | Tytuł | stary 1 |
| 2 | Wyobraźcie sobie | stary 2 |
| 3 | Mapa systemu | **stary 12** (przeniesiony na początek) |
| 4 | „Popatrzcie na ten ekran" | **NOWY** (demo-ślepe) |
| 5 | „Co widzicie?" | **NOWY** (refleksja) |
| 6 | A może powinniśmy… (flipchart) | stary 3 |
| 7 | 3 miesiące | stary 4 |
| 8 | Ilu z was wie | stary 5 |
| 9 | TEZA: awaria widoczności | stary 6 |
| 10 | Mapa wzorców | stary 7 |
| 11 | Bezpiecznik (3 stany) | stary 8 |
| 12 | Kolejność aspektów + #2383 | **NOWY** |
| 13 | Retry ≠ 4xx | **NOWY** |
| 14 | Zwrot akcji: czarna skrzynka | stary 9 |
| 15 | Micrometer fasada | stary 10 |
| 16 | Metryki za darmo | stary 11 |
| 17 | Realny kod | stary 13 |
| 18 | Dashboard, 2 publiczności | stary 14 |
| 19 | „Demo" (ciemny) | stary 15 — **rezerwowy/przejściowy** |
| 20 | Alert domyka pętlę | stary 16 — **rezerwowy** (zrzut na Plan B) |
| 21 | Spring Boot 4 | **NOWY** |
| 22 | Wróćmy do listy | stary 17 |
| 23 | Klamra | stary 18 |
| 24 | Dziękuję / Q&A | stary 19 |

## 4. Trzy progi, które musisz mieć w głowie (bez patrzenia)

**300 < 500 (slow-call) < 600 < 800 (read-timeout)** — czyli:
- `-SlowMs 300` = latencja rośnie, ale NIC się nie otwiera (szara strefa; widzi to tylko panel latencji),
- `-SlowMs 600` = slow_call_rate wspina się do 50% → **OPEN bez ani jednego błędu** (bomba talku),
- `partial` = wywołania wiszą do 800 ms → błąd → **retry pracuje**, failure rate ~50%,
- `down` = connection refused → failure rate 100% → OPEN na błędach → **alert FIRING**.

## 5. Najczęstsze pułapki tej wersji (40 min, Kolb)

1. **Najtrudniejsza rzecz w całym talku: MILCZEĆ w AKT 1.** Każdy odruch tłumaczenia („to jest latencja, bo Toxiproxy...") niszczy cykl Kolba. Ćwicz na próbie: komenda → Enter → cisza → pytanie. Nic więcej.
2. **`failure_rate` = „—"** → okno niezapełnione. Terminal A musi lecieć ≥3 min PRZED wejściem (stąd T-5, nie T-2).
3. **Timeline w AKT 4 pokazuje historię z AKT 1** — to feature, nie bug: „widzicie te czerwone epizody? To wasze demo sprzed pół godziny". Ale jeśli Cię to zmyli na scenie — zmień zakres dashboardu na „ostatnie 5 min".
4. **`heal` po AKT 1 zrób PO CICHU** — jeśli zapomnisz, system będzie czerwony przez całą teorię i AKT 4 zacznie się od otwartego circuita (demo-naprawa nie zadziała dramaturgicznie).
5. **Interakcje mają twarde limity**: max 3 głosy na slajd 5, max 3 min flipchart na slajdzie 6, 1 głos na pytania-quizy w AKT 4. Bufor 40 min jest w limitach, nie w gadaniu szybciej.
