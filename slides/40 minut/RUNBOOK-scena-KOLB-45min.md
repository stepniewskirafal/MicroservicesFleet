# RUNBOOK SCENICZNY — „Działa ≠ działa i ja o tym wiem" · WERSJA KOLB v4 · 45 min

> **Zgrany z deckiem:** `Działanierównasiędziała_KOLB_v4.pptx` i branchem **`demo`**.
> ✅ **Rozjazd z deckiem ZAMKNIĘTY (2026-08-06):** deck v4 ma na slocie 21 slajd **„Czwarty
> stan" (AI)** wg szkicu 8.6; stary slajd SB4 przeniesiony na koniec (za Q&A) jako rezerwowy.
> `Działanierównasiędziała_KOLB_v3.pptx` zostaje na dysku jako backup — jeśli MUSIAŁBYŚ grać
> z v3, tekst SB4 jest w sekcji 8.8.
> Opis zmian w kodzie: `BRANCH-demo-zmiany-kodu.md` i `README-zmiany-kodu-4dd92ce.md`.
> *(Nie podajemy hashy commitów — po squashach na tej gałęzi bywają nieosiągalne; ścieżki plików są trwałe.)*
>
> **Co zmieniło się względem v2:**
> 1. **Trzy wzorce zamiast pięciu: Timeout · Retry · CircuitBreaker.** RateLimiter i Bulkhead wypadły
>    w całości (slajd 18-RL/BH, flood, squeeze, `demo-flood.ps1`, panele ⑪⑫). Wszystko, co pokazujesz,
>    istnieje w kodzie i na dashboardzie — zero obietnic bez pokrycia.
> 2. **AKT 4 = realny kod z repo** (slajd 16 w formacie PRZED/PO), dashboard i widoczność.
>    Bez live-codingu: zrzut lewo/prawo, jedna lekcja na slajd.
> 3. **Kod dopracowany przed talkiem** — fallback tylko na CallNotPermitted, dwa typy wyjątków,
>    biała lista `record-exceptions`, backoff+jitter. **Cebula zostaje DOMYŚLNA** — bo taką ma
>    każdy, kto jej świadomie nie zmienił. Odwrócenie żyje pod profilem `cb-outer` i omawiasz je
>    KONCEPTOWO na slajdzie 11-bis, bez wykresów (przełączanie na żywo przetestowane i odrzucone:
>    ~98 s ciszy). Przekaz: „mam wybór i wiem, który wybrałem", nie „naprawiłem błąd".
>    Samą białą listę pokazujesz na **slajdzie 12-bis** — to jedyny ekran z konfiguracją w talku.
> 4. **Kontrakt HTTP:** niedostępność plannera = **503 + Retry-After** (nie 409). 409 zostaje dla
>    odmowy domenowej. `demo-resilience.ps1` liczy oba jako „degraded".
> 5. **AKT 1 = drabinka 300 → 440 → 600 → down** (440: „system zauważył i NIE zareagował").
>
> **Co zmieniło się względem v3 (= cała zmiana v4):**
> 6. **Slajd 21 = „Czwarty stan" (AI) zamiast Spring Boot 4.** LLM jako to samo zdalne HTTP +
>    czwarty stan (200 OK, zielony dashboard, bzdura w odpowiedzi) = teza talku o poziom wyżej;
>    zakończenie otwiera serię. Merytoryka SB4 w całości przechodzi do Q&A (cheatsheet, sekcja D,
>    pyt. 11–13). Uzasadnienie i wdrożenie: **sekcja 8**.
>
> Legenda: **🗣 MÓWISZ** (⚓ = dosłownie) · **🎭 JAK** · **🎬 ROBISZ** · **📊 NA WYKRESIE** · **⏱ CHECKPOINT**.
> Komendy `.ps1` z `cmd` — z przedrostkiem `powershell -ExecutionPolicy Bypass -File`.

---

## 0. Cykl Kolba — mapa aktów

| Faza Kolba | Slajdy | Co się dzieje |
|---|---|---|
| **Doświadczenie konkretne** | 3–5 + Grafana | Żywy system; psujesz **bez słowa** (300 → 440 → 600 → down). Zero nazw, zero tez. |
| **Refleksja** | 6–8 | Sala nazywa obserwacje, projektuje zabezpieczenia na flipchart; TEZA jako synteza ICH słów. |
| **Konceptualizacja** | 9–15 | Słownik: 3 złudzenia → 3 wzorce, bezpiecznik, cebula #2383, retry≠4xx, Micrometer. Każdy koncept kotwiczony w tym, co widzieli. |
| **Zastosowanie** | 16–18 + Grafana | Realny kod z repo (slajd 16 w formacie PRZED/PO), dashboard, widoczność + demo-naprawa (partial → down+alert → heal). |
| **(2. obieg)** | 21–26 | „Czwarty stan" (AI) → flipchart-powrót → echo pytania + HISTORIA → klamra. |

**Tajemnica historii bez zmian:** przez cały talk ani słowa o „mojej firmie" — reveal dopiero na slajdzie 24.

---

## 1. Budżet czasu — 45 minut

| Akt | Slajdy | Czas | Skumulowane |
|---|---|---|---|
| **Wejście** | 1–2 | 2:00 | 2:00 |
| **Mapa + PYTANIE-zobowiązanie** | 3–4 | 3:00 | 5:00 |
| **1. DOŚWIADCZENIE — demo-ślepe (4 kroki)** | 5 + Grafana | 7:30 | 12:30 |
| **2. REFLEKSJA** | 6–8 | 6:00 | 18:30 |
| **3. KONCEPTUALIZACJA** | 9–15 (+11-bis, +12-bis) | 12:00 | 30:30 |
| **4. ZASTOSOWANIE — kod + demo-naprawa** | 16–18 + Grafana | 7:30 | 38:00 |
| **5. SYNTEZA** | 21–26 | 7:00 | **45:00** |

**Q&A jest POZA tym budżetem** — slajdy 21–25 wypełniają 7:00 co do minuty, slajd 26 otwiera pytania po 45:00.
Realny zapas w samym talku: ~3 min w oznaczonych cięciach (krok 440, slajd 11-bis, slajd 14, dygresja Gosling).
Dwa slajdy w AKT 3 są **konceptowe** (hasła + YAML, zero demo i zero wykresów): **11-bis** zaraz po
slajdzie 11 — *kolejność aspektów*, oraz **12-bis** zaraz po slajdzie 12 — *biała lista wyjątków*.
Różnica priorytetu: 11-bis wolno wyciąć w całości, 12-bis nie — on zawiera receptę, którą slajd 12
tylko zapowiada. Przy spóźnieniu tnij 12-bis do wersji krótkiej (~75 s), nie do zera.

---

## 2. Setup — T-20 → T-5

### T-20 — stack z overlayem demo, NA BRANCHU `demo`
```powershell
cd C:\softwareEngineer\MicroservicesFleet
git checkout demo
docker compose -f infra/docker/docker-compose.yml -f infra/docker/docker-compose.demo.yml up -d --build
docker ps --format "table {{.Names}}\t{{.Status}}"
powershell -ExecutionPolicy Bypass -File .\scripts\preflight-demo.ps1 -Full
```
Preflight MUSI zwrócić **0**.

### T-5 — zdrowy ruch (Terminal A), zostaw lecący
```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\demo-resilience.ps1 -DurationMinutes 60
```

### Checklista
- [ ] `git branch` pokazuje `demo`
- [ ] Kod ma to, co pokazujesz — jeden grep zamiast liczenia commitów:
      `git grep -l "circuitOpenFallback\|RoutePlannerUnavailableException\|record-exceptions" -- starport-registry/src/main` → **4 pliki**
      (adapter, oba nowe wyjątki widoczne przez handler, `application.yml`)
- [ ] `preflight-demo.ps1 -Full` = 0
- [ ] Terminal A leci ≥3 min (failure rate = 0%, nie „—"); licznik pokazuje `409/503(degraded)=0`
- [ ] Grafana: dashboard r4j-trade-route, **zakres 15 min**, wszystkie 10 paneli z danymi (panel odcięć pusty = OK przy zdrowym ruchu; panel retry = równa linia successful_without_retry)
- [ ] Prometheus /alerts (druga karta) · Terminal B pusty · **`QA-cheatsheet-KOLB-v3.md`** na 2. ekranie *(NIE ten z katalogu „20 minut" — ma inne kotwice i stary kontrakt HTTP)*
- [ ] Zrzut kodu na **slajdzie 16** zgodny z repo co do linii (ktoś sfotografuje i wejdzie na repo)
- [ ] Flipchart + marker · Backup: PDF + zrzuty + nagranie z preflight

---

## 3. Slajd po slajdzie

# WEJŚCIE (0:00 → 2:00)

### Slajd 1 — Tytuł · ~15 s
- 🗣 *(nic)* · 🎭 wejdź, 2 s ciszy, spójrz. · 🎬 → slajd 2.

### Slajd 2 — „Wyobraźcie sobie" · ~1,5 min
- 🗣 „Wyobraźcie sobie zwykły wtorek. Piszecie serwis. Jeden komponent musi zapytać drugi — przez sieć, zwykłym requestem HTTP. Wysyłacie jeden niewinny request. Czekacie na odpowiedź. Zapisujecie do bazy. *(pauza)* Działa. Pipeline zielony, idziecie na kawę. *(pauza)*"
- 🎭 BEZ ręki w górę. Kończysz na „Działa." → slajd 3. ⏱ **CHECKPOINT: 2:00.**

# MAPA + ZOBOWIĄZANIE (2:00 → 5:00)

### Slajd 3 — Mapa systemu · ~1,5 min
- 🗣 „Taki request istnieje naprawdę — mam go tu, za plecami. Minuta na mapę. Klient przez gateway → starport-registry, dwie repliki, robi rezerwację doku → rezerwacja potrzebuje trasy → starport pyta trade-route-planner, też dwie repliki. *(palec z góry na dół)* To wywołanie — starport do plannera — to nasz bohater. Zwykły HTTP między dwoma serwisami. Dokładnie taki jak z wtorku."
- 🎭 rzeczowo; **nie tłumacz strzałki Toxiproxy**. 🎬 → slajd 4.

### Slajd 4 — ⭐ „Kto by tak zostawił?" · ~1,5 min · ZOBOWIĄZANIE PUBLICZNE
- 🗣 ⚓ „I teraz pytanie — zanim cokolwiek pokażę. Ten request. Goły. Bez retry, bez metryki, bez nadzoru. *(pauza)* Kto by tak zostawił? *(cisza — niech pokręcą głowami)* Czego brakuje — hasłowo, dwa-trzy słowa?" *(to nie jest egzamin)*
- 🎭 Zbierz 2–3 hasła USTNIE i powtórz: „Retry. Timeout. Alert. Zapamiętajcie, że to POWIEDZIELIŚCIE. *(pauza)* Bo ja teraz nie dodam żadnej z tych rzeczy. Popatrzymy na system taki, jaki jest."
- **Nie skracaj tego slajdu, nawet gdy goni czas** — deklaracja sali wraca w minucie 40.
- 🎬 → slajd 5. ⏱ **CHECKPOINT: 5:00.**

# AKT 1 — DOŚWIADCZENIE (5:00 → 12:30) · demo-ślepe

> **Żelazna zasada: nie nazywasz NICZEGO.** Komenda → Enter → cisza → pytanie „co widzicie?". Nic więcej.
> Oś aktu: **„podkręcam pokrętło o jedną kreskę, a system zmienia zdanie skokowo."**

### Slajd 5 — „Popatrzcie na ten ekran." · ~1 min + przełączenie
- 🗣 „Popatrzcie na ten ekran. Grafana — wszyscy ją znamy, wszyscy czasami udajemy, że rozumiemy wszystkie panele. *(pauza)* To nie jest zrzut ekranu. To jest ten system, który opisałem — w tle właśnie lecą prawdziwe zapytania klientów o miejsca dokowania."
- 🎬 **PRZEŁĄCZ RZUTNIK NA GRAFANĘ** *(2 s ciszy)*
- 🗣 „Dziesięć prostokątów. Ktoś tu miał produktywne popołudnie. *(śmiech)*
  *(palec — lewy górny kafelek)* Zielony. Zielony to dobrze, to akurat wiem na pewno. Tu jest napisane: dostępna. Ten drugi serwis — nasz bohater z mapy — żyje i odbiera.
  *(palec — trzy kafelki obok)* Zero. Zero. Zero. Jak w alkomacie, kiedy policja zatrzyma nas rano do kontroli.
  *(palec — szeroki pas przez ekran)* Ten pas czasu — cały zielony, od lewej do prawej. Nuda.
  *(palec — wykres lewy dolny)* A tu jest ruch. Nasze rezerwacje, kilka na sekundę, non stop.
  *(palec — wykres obok)* Linia płaska przy zerze.
  *(palec — wykres latencji)* I tu — dwie setne sekundy. Szybciej, niż zdążycie mrugnąć.
  Są tu jeszcze jakieś angielskie słowa, których nie tłumaczę. *(machnięcie ręką)* Dojdziemy do nich. *(pauza)*
  Zielono, płasko, szybko. ⚓ **Tak wygląda wtorek.**"
- 🚫 **Zakazane:** progi (500/800/50%), czytanie tytułów paneli, „zapamiętajcie bo…", pokazywanie pustego panelu odcięć.

### 🎬 DEMO-ŚLEPE — Terminal B · ~6 min

**Krok 1 — slow 300 · ~60 s:**
```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\demo-resilience.ps1 -Chaos slow -SlowMs 300
```
- 🎭 Enter → cisza 20 s → patrz na salę, nie na ekran.
- 🗣 „O. Coś się zmieniło. Kto widzi — gdzie?" *(1–2 głosy; cisza? „Podpowiem: prawie wszystko się nudzi. Prawie.")*
- 📊 reaguje TYLKO „Maks. latencja" (0,02 → ~0,35 s).
- 🗣 „Ktoś coś ściąga na wifi. *(wzruszenie ramion)* Jedziemy dalej."

**Krok 2 — slow 440 · ~45 s · *(cięcie awaryjne: pomiń cały krok)*:**
```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\demo-resilience.ps1 -Chaos slow -SlowMs 440
```
- 🗣 *(po ~20 s)* „Podkręcam. *(pauza)* Znowu wifi? Dwa razy pod rząd to już nie wifi."
- 📊 „Maks. latencja" ociera się o pomarańczową linię; na „Failure rate / slow call rate" druga linia może drgnąć i wrócić do zera (jitter — wartość losowa: 0–20%).
- 🗣 „Popatrzcie *(palec: latencja)* — dotknęła jakiejś pomarańczowej linii. Nie mówię, co znaczy. A tu *(palec: failure/slow rate)* coś się na moment obudziło i wróciło spać. *(pauza)* I teraz uwaga — **nic się nie stało.** System zauważył. I nie zareagował. *(pauza)* Zapamiętajcie to zdanie."
- 🩹 Mów „**coś się obudziło**", nigdy konkretnej liczby — przy jitterze może wyjść 0%. Wtedy: „Prawie. *(palec na latencję)* Ale widać, że było blisko."

**Krok 3 — slow 600 · ~90 s · ⭐ PUENTA:**
```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\demo-resilience.ps1 -Chaos slow -SlowMs 600
```
- 🗣 „Jeszcze jedna kreska. Sto sześćdziesiąt milisekund więcej niż przed chwilą. *(Enter, cisza 15 s)* A teraz? *(cisza)* Prawy górny róg drugiego rzędu. I pas czasu."
- 📊 slow_call_rate wspina się do progu 50% → timeline CZERWONY → kafelek ODCIĘTA → słupki not-permitted. **Failure rate cały czas 0%.**
- 🩹 **Timing kotwicy (zmierzone):** zdanie „ile błędów na liczniku błędów? zero" rzuć w PIERWSZYCH ~20–30 s, póki kafelek failure rate pokazuje 0% a slow rate się wspina. Po otwarciu breaker cyklicznie próbuje half-open (co 10 s, a próby na wolnym plannerze padają) — kafelki przechodzą wtedy w „—", a pas mruga czerwony↔pomarańczowy. To nie błąd — to scena bonusowa: *„Pomarańczowy? System właśnie sam sprawdza, czy tamten wyzdrowiał. Nie wyzdrowiał — odcina znowu."*
- 🗣 ⚓ *(najwolniej w akcie)* „Sto sześćdziesiąt milisekund. Tyle dzieliło poprzedni ekran od tego. *(pauza)* A teraz spójrzcie tutaj. **Ile błędów na liczniku błędów?** *(pauza — niech ktoś powie „zero")* Zero. Żaden request nie zwrócił błędu. A system sam sobie coś odciął. *(pauza)* To pytanie zostaje otwarte."

**Krok 4 — down · ~60 s:**
```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\demo-resilience.ps1 -Chaos down
```
- 🗣 „Ostatni ruch. *(Enter, cisza)* Co się różni od poprzedniego?" — 1 głos wystarczy.
- 📊 failure rate → 100%, czerwono; kontrast: tam czerwono BEZ błędów, tu OD błędów.
- 🗣 ⚓ „Tam było czerwono bez błędów. Tu jest czerwono od błędów. *(pauza)* Cztery razy podkręciłem to samo pokrętło. Cztery różne obrazki. Zapamiętajcie je."

**Heal PO CICHU + powrót na slajdy:**
```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\demo-resilience.ps1 -Chaos heal
```
- 🎬 Enter bez słowa → **slajdy** → slajd 6. ⏱ **CHECKPOINT: 12:30.** *(później? tnij w AKT 2 do 2 głosów na slajd i skróć flipchart do 2 min)*
- **Plan B:** nagranie z preflight albo zrzuty — pytania do sali działają identycznie.

# AKT 2 — REFLEKSJA (12:30 → 18:30)

### Slajd 6 — „Co widzicie?" · ~2,5 min · INTERAKCJA
- 🗣 ⚓ „Co widzieliście? Nie mówię. Wy mi powiedzcie. *(pauza)* Co się zmieniło na wykresach? Które requesty zginęły, a które tylko zwolniły? I trzecie, moje ulubione: skąd system w ogóle WIEDZIAŁ, że jest źle?"
- 🎭 max 3 głosy; „dobre / notuję / o tym za chwilę". Ktoś powie „circuit breaker"? — „Ktoś zna słowo. Nie psuj innym — za dziesięć minut wszyscy będą je znali."
- **Podstawione** (milcząca sala): „Widzieliście, że najpierw urósł tylko jeden wykres? Że zrobiło się czerwono przy zerowym liczniku błędów? Że system przestał w ogóle wołać tamten serwis?"

### Slajd 7 — „A może powinniśmy…" + FLIPCHART · ~2,5 min
- 🗣 „Wróćmy do haseł z początku — teraz macie za sobą obraz awarii. Gdybyście projektowali ten system: co dodajecie, żeby spać spokojnie? *(flipchart)* Powiedzcie — co byście dodali?"
- 🎭 wypisz: health-check, retry, timeout, alert, metryka, odcięcie… **To ICH lista** — wrócisz do niej na slajdzie 22. Max 2,5 min.
- ⚓ **Most:** „Zapamiętajcie tę listę. Wrócimy do niej na końcu — co do punktu."

### Slajd 8 — TEZA · ~1 min · ⭐ NAJWOLNIEJ
- 🗣 „To, co widzieliście — czerwony ekran przy zerowym liczniku błędów — nazwę jednym zdaniem, które jest osią tego wystąpienia: ⚓ »Działa« nie znaczy »działa i ja o tym wiem«. *(pauza)* To nie są awarie retry ani sieci. To są awarie *widoczności*. I wszystko, co teraz pokażę, służy jednemu: żeby między tymi dwoma zdaniami nie mieściło się nic."
- 🎭 Historia jest tajna do slajdu 24 — ani słowa o „moich trzech miesiącach".
- ⏱ **CHECKPOINT: 18:30.** *(później? tnij slajd 14 do samego ⚓ i pomiń dygresję o Goslingu)*

# AKT 3 — KONCEPTUALIZACJA (18:30 → 28:30)

### Slajd 9 — Mapa złudzeń → trzy odpowiedzi · ~2 min
- 🗣 „Wszystko, co przed chwilą wypisaliśmy na flipcharcie — ktoś już dawno nazwał. I to nie w zeszłym roku. *(pauza)*
  Na początku lat dziewięćdziesiątych w Sun Microsystems spisano listę **fałszywych założeń programowania rozproszonego** — rzeczy, które każdy z nas przyjmuje po cichu, pisząc wywołanie do drugiego serwisu. *(palec na slajd)*
  **»Sieć jest niezawodna«.** Nie jest. Pakiety giną, połączenia się rwą.
  **»Opóźnienie jest zerowe«.** *(pauza, półuśmiech)* Widzieliście przed chwilą, ile jest warte to założenie.
  **»Zdalne wywołanie to to samo co lokalne«.** Najdroższe złudzenie ze wszystkich — bo lokalna metoda albo działa, albo rzuca wyjątek. Zdalna ma trzeci stan: **milczy**. I nikt wam nie powie, jak długo.
  Na te trzy złudzenia są trzy odpowiedzi — i dziś zobaczycie wszystkie trzy w kodzie i na wykresach. *(wodzisz palcem)* Nie wiadomo, czy odpowie — **nie czekaj wiecznie**. Mrugnęło — **ponów**. Leży — **odetnij** i nie dobijaj leżącego.
  Timeout. Retry. I ten trzeci, który już widzieliście w akcji, choć nie znacie jego imienia. *(pauza)*
  To są **koncepty, nie biblioteka**. Ja pokażę je w Javie, bo w Javie pracuję — ale w .NET, w Go, w Pythonie znajdziecie implementacje dokładnie tych samych wzorców. ⚓ **Zmienia się dostawca. Problem jest ten sam od trzydziestu lat.**"
- ➕ *(zapas czasu — dygresja)*: „Autorzy: pierwsze cztery Bill Joy i Tom Lyon, resztę dołożył Peter Deutsch, a ósme dopisał James Gosling — ten sam, który napisał Javę. Człowiek, który dał nam język do pisania serwerów, uznał za stosowne ostrzec nas przed pisaniem serwerów."

### Slajd 10 — Bezpiecznik (3 stany) + drabina progów · ~2,5 min · spłata długu z AKT 1
- 🗣 „Ten czerwony ekran sprzed dwudziestu minut. *(pauza)* To był **bezpiecznik**. I nazwa nie jest metaforą na siłę: bezpiecznik w domu nie jest po to, żeby prąd nigdy nie znikał — jest po to, żeby dom się nie spalił. Iskrzy → odcina.
  **ZAMKNIĘTY** — ruch płynie, ale bezpiecznik cały czas **liczy**. W tle, zawsze — inaczej nie miałby jak zauważyć, że przestało być dobrze.
  **OTWARTY** — odcina. I to nie jest tylko ochrona nas — to ochrona **tamtego** serwisu. On leży; ostatnie, czego mu trzeba, to tysiąc requestów na sekundę od nas. Nie dobijamy leżącego.
  **PÓŁOTWARTY** — po dziesięciu sekundach kilka prób. Udało się → zielony. Nie → znów odcinamy.
  *(zwolnij)* I zagadka, którą zostawiłem otwartą: **ile było błędów na liczniku błędów?** Zero. A ekran czerwony. Bo mój bezpiecznik liczy **dwie** rzeczy: procent błędów ORAZ procent wywołań wolniejszych niż pół sekundy. Tam nie było ani jednego błędu — były same sukcesy. Tylko wolne. *(pauza)* Awaria typu »niby działa«. Najpodlejszy rodzaj, jaki znam — licznik piątek nigdy jej nie złapie. Dashboard zielony, SLA zielone, klient czeka. *(moment „aaa")* To odpowiedź na otwarte pytanie."
- 🗣 **DRABINA PROGÓW** *(~30 s — bez tego sala widzi CO, ale nie rozumie DLACZEGO; rysuj palcem w powietrzu linię od lewej do prawej)*:
  „I jeszcze jedna liczba, bo bez niej to nie ma prawa zadziałać. Mój timeout to **osiemset** milisekund. A próg »wolnego« — **pięćset**. *(pauza)* Zwróćcie uwagę na kolejność: próg wolnego leży **poniżej** timeoutu. Celowo.
  Bo gdyby leżał powyżej — nie odpaliłby się **nigdy**. Każde wolne wywołanie stałoby się wcześniej timeoutem, czyli zwykłym błędem, i wylądowałoby w tym drugim liczniku. *(pauza)* A tak mam między nimi trzysta milisekund szarej strefy: wywołanie, które trwa sześćset, **kończy się sukcesem** — klient dostaje trasę — ale system już wie, że coś jest nie tak. ⚓ **To w tej szczelinie mieszka cała dzisiejsza pointa. Awarię widać, zanim zamieni się w błąd.**"

### Slajd 11 — Kolejność aspektów #2383 · ~2,5 min
- 🗣 „Zanim policzymy, jedna rzecz, na której to wszystko stoi. *(pauza)* Te wzorce układają się jak **cebula** — jeden owija drugi. I domyślnie Retry siedzi na zewnątrz, a CircuitBreaker w środku. To znaczy: każda ponowna próba przechodzi przez bezpiecznik **od nowa**, jako osobne wywołanie. Zapamiętajcie ten obrazek.
  **Weźmy przykład.** Macie dziesięć operacji. Padają dwie. Dwadzieścia procent — patrzycie i myślicie: spokojnie, próg mam na pięćdziesięciu, bezpiecznik śpi. I to jest zupełnie zdrowa intuicja. Tak liczy człowiek — operacjami.
  Tylko że bezpiecznik nie liczy operacji. **Liczy próby.** Retry ma trzy próby: dwie porażki razy trzy to sześć nieudanych wywołań. Dorzućcie osiem udanych — czternaście wywołań, sześć czerwonych. **Czterdzieści trzy procent.** *(pauza, gest: lewa-prawa)* Wam padło dwadzieścia. Bezpiecznik widzi czterdzieści trzy i jest o włos od otwarcia. Ta sama chwila — dwie różne prawdy, zależnie od tego, kto liczy. Issue dwa-trzy-osiem-trzy. To nie bug. To domyślna konfiguracja, która robi coś innego, niż myślisz.
  I to jest **kolejność domyślna** — ta, którą ma każdy, kto nie zmienił jej świadomie. Także ja: mój system, ten, który dziś oglądaliście, **działa dokładnie tak**."
- 🎭 pytanie „ile porażek widzi breaker?" zadaj serio — połowa powie „jedną". To moment nauki.
- 🗣 *(uczciwa rama — NIE mów, że domyślna kolejność to bug)*: „I żeby było jasne: to **nie jest błąd biblioteki**. To inna filozofia. Licząc próbami, bezpiecznik reaguje **szybciej** na twardą awarię — i to jest sensowny wybór. Cena jest taka, że próg pięćdziesięciu procent przestaje znaczyć »połowa operacji« i zaczyna znaczyć »połowa prób« — a jego realna wartość przesuwa się przy każdej zmianie liczby ponowień. *(pauza)* Można to odwrócić: `aspectOrder`, niższa wartość znaczy bardziej na zewnątrz. CircuitBreaker jedynka, Retry dwójka — i bezpiecznik widzi dopiero finał operacji.
  Dwie liczby w konfiguracji. Tyle. *(pauza)* ⚓ **I to nie jest tak, że jedna wersja jest dobra, a druga zła. Nie do obrony jest tylko jedno: nie wiedzieć, którą się ma.**"
- 🗣 *(ciszej — pierwsze wyznanie)* „Bo ja nie wiedziałem. *(pauza)* Przygotowując ten talk, poszedłem sprawdzić własny `application.yml` — i zobaczyłem, że mam domyślnie. Nie dlatego, że tak zdecydowałem. Dlatego, że nigdy o tym nie pomyślałem. *(pauza)* Za chwilę pokażę wam tę różnicę **na żywo, na wykresach**."
- 🎭 **CO POKAZUJESZ, A CZEGO NIE:** bazowa konfiguracja na scenie = **domyślna cebula**. Odwrócenie jest opcją do pokazania (demo w AKT 4, krok 3), nie „naprawą, którą zrobiłem". Nie mów „naprawiłem to" — mów „mam wybór i wiem, który wybrałem".

### Slajd 11-bis — Kolejność aspektów: co możesz zmienić i co z tego wynika · ~1,5 min

> **Czym ten slajd JEST, a czym nie.** To slajd **konceptowy** — hasła i pięć linijek YAML-a.
> Niczego tu nie demonstrujesz i nie pokazujesz żadnych wykresów. Slajd 11 dał arytmetykę
> („43%"), ten daje **decyzję**: co realnie zmieniasz, co na tym zyskujesz, co tracisz i kiedy
> w ogóle warto. Przełączanie na żywo zostało przetestowane i **odrzucone** — po restarcie replik
> ruch wraca dopiero po ~100 s, a zmierzona różnica wymaga zastrzeżeń. Sto sekund ciszy na scenie
> za taki dowód to zły interes.

- 🖼 **Na slajdzie:** dwie kolumny haseł (domyślnie / odwrócone) + blok YAML pod spodem. Bez zrzutów.

- 🗣 „Skoro cebula ma dwie wersje, to naturalne pytanie brzmi: **którą wybrać**. Odpowiedź nie jest
  taka, że jedna jest dobra. *(pauza)*

  *(lewa kolumna)* **Domyślnie retry jest na zewnątrz — bezpiecznik liczy PRÓBY.** Plus: reaguje
  **szybciej** na twardą awarię, bo każda nieudana próba dokłada do okna. Retry z backoffem potrafi
  też przeczekać krótkie otwarcie. Minus: próg pięćdziesięciu procent znaczy »połowa **prób**«, nie
  »połowa operacji« — a jego realna wartość **przesuwa się przy każdej zmianie liczby ponowień**.
  I przy otwartym obwodzie każda operacja puka do zamkniętych drzwi dwa razy zamiast raz.

  *(prawa kolumna)* **Odwrotnie — bezpiecznik na zewnątrz — liczy OPERACJE.** Plus: próg znaczy
  dokładnie to, co mówi, i nie dryfuje. Odmowa przy otwartym obwodzie jest natychmiastowa, bez
  spalonej próby. A licznik porażek pokazuje **to, czego doświadczył klient** — nie techniczne próby.
  Minus: odcina **wolniej**, bo żeby uzbierać pięć porażek, potrzeba pięciu pełnych operacji, każdej
  z ponowieniami. W czasie tej zwłoki dobijasz zależność.

  *(wskaż YAML)* A cała zmiana to **dwie liczby**. Niższa wartość znaczy »bardziej na zewnątrz«.
  ⚓ **I najważniejsze: kolejność adnotacji nad metodą nie zmienia NIC. Tylko te dwie liczby.**"

#### 🎯 Kiedy warto odwrócić — gotowa odpowiedź, gdyby ktoś zapytał „to co mam zrobić?"

- 🗣 „Krótko: **odwracam, kiedy próg ma znaczyć to, co mówi.**
  Po pierwsze — gdy dobieracie progi **od SLO na operacjach**: »pięć procent rezerwacji może paść«.
  Wtedy chcecie, żeby bezpiecznik liczył rezerwacje, a nie próby HTTP.
  Po drugie — gdy macie **więcej niż dwie próby**. Przy pięciu rozjazd między »procentem prób«
  a »procentem operacji« robi się na tyle duży, że konfiguracja przestaje cokolwiek znaczyć.
  Po trzecie — gdy **każde pukanie kosztuje**: płatny provider, ciężkie zapytanie, zależność,
  która ledwo dyszy. Wtedy podwójne dobijanie przy otwartym obwodzie to realne pieniądze.
  *(pauza)* A **zostaję przy domyślnej**, gdy zależy mi na najszybszym możliwym odcięciu przy
  twardej awarii, mam dużo replik i tanie ponowienia — bo wtedy druga próba naprawdę często ratuje."

#### ⚠️ Czego się spodziewać po przełączeniu (mów tylko, jeśli ktoś dopyta)

- 🗣 „Trzy rzeczy, o których trzeba wiedzieć, zanim się to zmieni na produkcji.
  **Progi trzeba przeliczyć od nowa** — te same pięćdziesiąt procent znaczy teraz co innego, więc
  alerty oparte na failure-rate też przeglądacie.
  **Fallback odpala się później** — po wyczerpaniu ponowień, a nie przy pierwszej porażce; do czasu
  odpowiedzi dochodzi czas retry.
  I **testy, które przybijały stare zachowanie, przestaną przechodzić** — co jest dobrą wiadomością,
  bo znaczy, że w ogóle je macie."

- 🎭 **Nie podawaj liczb z pomiarów** (»1,67 raza mniej odcięć«). Zmierzyłem to i wynik jest
  powtarzalny, ale zależy od tempa ruchu i cyklu half-open — na scenie to zbędna precyzja, która
  zaprasza do sporu. Mów jakościowo: „dwa pukania zamiast jednego". Liczby trzymaj na Q&A
  (`QA-cheatsheet-KOLB-v3.md`, pyt. 6–7).
- ✂️ **Cięcie:** przy braku czasu pomiń cały slajd — arytmetyka ze slajdu 11 i zdanie „mam na to
  dwa testy" wystarczą. To pogłębienie, nie fundament.
- 🎓 **Dla ciekawych po talku:** profil `cb-outer` + overlay compose przełączają to jedną komendą;
  szczegóły i pomiary w `BRANCH-demo-zmiany-kodu.md` oraz ADR-0039.
### Slajd 12 — Retry ≠ 4xx · ~2 min
- 🗣 „Skoro jesteśmy przy retry — wygląda niewinnie. Dorzucasz adnotację, masz odporność »za darmo«. *(pauza)* Nie jest za darmo.
  Zasada jest jedna: **ponawiaj tylko to, co w ogóle mogło się udać.** Pięćset-iks, timeout — przejściowe: serwer się zakrztusił, sieć mrugnęła; druga próba może trafić na zdrowy węzeł. Czterysta-iks — deterministyczne i **twoje**: złe pytanie. Zapytasz drugi raz tak samo — dostaniesz to samo. Potroiłeś porażkę, która była pewna za pierwszym razem.
  I tu wraca cebula: każda z tych bezsensownych prób ląduje w bezpieczniku jako nieudane wywołanie. **Karmisz bezpiecznik własnymi bugami** — zamiast pilnować, czy zależność żyje, dławi się twoimi czterysta-czwórkami.
  Lekarstwo: powiedz breakerowi i retry, **które wyjątki ignorować** — wąsko i świadomie. Bo kontrapułapka: zignorujesz całe RuntimeException »żeby było spokojnie« — i **oślepiasz bezpiecznik**. Przestaje widzieć realne porażki i nigdy się nie otworzy."
- 🗣 **TRZECIA KATEGORIA** *(~30 s — mocne, warto zostawić; naturalne przedłużenie tego slajdu)*:
  „I jest jeszcze trzeci rodzaj wyjątku, o którym się zapomina — a jest najpodstępniejszy. *(pauza)* Nie odmowa i nie awaria. **Wasz własny bug.**
  Bo zadajcie sobie pytanie, po co ten bezpiecznik w ogóle istnieje. Żeby odpowiadać na **jedno** pytanie: czy tamten serwis jest zdrowy. *(pauza)* A skoro tak — to co robi w jego oknie `NullPointerException` z **waszego** mapowania? On nie mówi o tamtym serwisie nic. Do tamtego serwisu w ogóle nie dojechaliście.
  Ja miałem czarną listę: »ignoruj odmowy, licz całą resztę«. Czyli mój NPE liczył się jako awaria plannera. *(zwolnij)* Pięć takich wywołań i **bezpiecznik odcina serwis, który jest w idealnym stanie**. Klient dostaje pięćset trzy »wróć za chwilę« — na błąd, który jest deterministyczny, więc powrót nic nie da. Odpala się alert. A dyżurny idzie sprawdzać **nie ten serwis**.
  Naprawa to zamiana czarnej listy na białą. ⚓ **Domyślną odpowiedzią na pytanie »czy to awaria zależności« ma być NIE, a nie TAK.** *(klik — slajd 12-bis)* I to jest cała naprawa, w trzech linijkach."
- 🗣 *(zwolnij — drugie wyznanie)* „I tak — to też znalazłem u siebie. Mój kod przez rok zamieniał **trzy różne awarie w jeden wyjątek** — retry grzecznie ponawiał czterysetki, a bezpiecznik liczył odmowy domenowe jako awarie infrastruktury. *(pauza)* Nie z lenistwa — po prostu nigdy nie zadałem sobie pytania, czym różni się odmowa od awarii. ⚓ **Bo to nie jest talk o tym, że ja mam idealnie. To jest talk o tym, żeby widzieć.**"

### Slajd 12-bis — Biała lista: trzy linijki, trzy decyzje · ~1,5 min

> **Czym ten slajd JEST.** Slajd 12 postawił diagnozę („karmisz bezpiecznik własnymi bugami").
> Ten pokazuje **całą receptę na jednym ekranie**: dziewięć linijek YAML-a i dwie linijki
> komentarza o tym, skąd biorą się te typy. Konceptowy — nic tu nie demonstrujesz na żywo.
> To jedyne miejsce w talku, gdzie publiczność widzi *konfigurację* odporności; wszędzie indziej
> pokazujesz adnotacje albo wykresy.

- 🖼 **Na slajdzie:**

```yaml
circuitbreaker.instances.trade-route-planner:
  record-exceptions:      # BIAŁA lista: tylko to jest awarią zależności
    - ...RoutePlannerUnavailableException
  ignore-exceptions:      # odmowa to ODPOWIEDŹ, nie awaria
    - ...RoutePlanningRejectedException

retry.instances.trade-route-planner:
  retry-exceptions:       # ponawiaj wyłącznie to, co MOGŁO się udać
    - ...RoutePlannerUnavailableException
```
> `// o typie decyduje klient:  4xx → Rejected  ·  5xx/timeout → Unavailable`
> `// czego nie ma na żadnej liście (nasz NPE) → breaker liczy to jako SUKCES`

- 🗣 **GŁÓWNY TEKST** *(~60 s)*:
  „Popatrzcie, jak mało tu jest. *(pauza)* Trzy klucze — i każdy z nich to **jedna decyzja**,
  którą i tak podejmujecie, tylko zwykle nieświadomie.

  *(wskaż `record-exceptions`)* Pierwszy: **co JEST awarią zależności.** Biała lista, jeden typ.
  Nie »wszystko oprócz«, tylko »wyłącznie to«.
  *(wskaż `ignore-exceptions`)* Drugi: **co awarią NIE jest.** Odmowa domenowa. Planner
  odpowiedział — powiedział »nie«, ale odpowiedział. To jest **odpowiedź, nie awaria**.
  *(wskaż `retry-exceptions`)* Trzeci: **co wolno ponawiać.**

  I teraz najciekawsze. *(zwolnij)* Zauważcie, że w pierwszej i trzeciej liście stoi **ten sam
  typ wyjątku**. To nie przypadek i nie kopiuj-wklej. ⚓ **»Czy to jest awaria zależności?«
  i »czy warto spróbować drugi raz?« to w praktyce jedno pytanie.** Jeśli u was odpowiedzi się
  rozjeżdżają — albo macie bardzo dobry powód, albo błąd w konfiguracji."

- 🗣 **SKĄD SIĘ BIORĄ TE TYPY** *(~25 s — bez tego slajd wisi w powietrzu)*:
  „Tylko uwaga: **YAML nie umie odróżnić odmowy od awarii.** On umie tylko dopasować **typ**.
  Więc żeby te trzy linijki miały jakikolwiek sens, ktoś wcześniej musiał ten typ ustalić —
  i to jest robota kodu, nie konfiguracji. U mnie robi to klient: czterysta-iks staje się
  »odrzucone«, pięćset-iks i timeout stają się »niedostępne«. *(pauza)* ⚓ **Konfiguracja
  egzekwuje decyzję. Ale podejmuje ją kod.**"

- 🗣 **TRZECIA GAŁĄŹ** *(~25 s — domknięcie wątku »własnego buga« ze slajdu 12)*:
  „I zostaje pytanie, które ktoś zawsze zadaje: *a co z resztą? Co z moim NullPointerem, który
  nie stoi na żadnej z tych list?* *(pauza)* Resilience4j ma na to trzecią gałąź: **jeśli wyjątek
  nie jest ani zapisywany, ani ignorowany — wywołanie liczy się jako SUKCES.**
  Brzmi absurdalnie. *(pauza)* Ale to jest uczciwe: **na tyle, na ile ten bezpiecznik wie,
  zależność nie zawiodła.** Bo do zależności w ogóle nie dojechaliśmy.
  ⚓ Bezpiecznik nie jest od pilnowania jakości waszego kodu. Od tego są testy i alerty na
  pięćsetkach. On odpowiada na **jedno** pytanie — i to jedno ma odpowiadać dobrze."

- 🎭 **Kontrapułapka — powiedz ją, zanim ktoś wpadnie na to sam** *(~15 s)*:
  „Ktoś teraz pomyśli: »to ja wrzucę do `ignore-exceptions` całe `RuntimeException` i będzie
  spokój«. *(pauza)* Będzie. **Bezpiecznik nigdy się nie otworzy.** Zignorowaliście też te
  wyjątki, dla których on istnieje. To nie jest cisza — to ślepota."

- 🎭 *(jeśli ktoś dopyta: sama biała lista to połowa naprawy. Druga połowa jest **strukturalna** —
  zawęzić zasięg adnotacji do samego wywołania zdalnego, żeby walidacja i mapowanie w ogóle nie
  wchodziły do okna. Pokazuję to na slajdzie 16. Pełna odpowiedź: cheatsheet pyt. 10c,
  `ANALIZA-wyjatkow-i-bledow.md`.)*
- ✂️ **Cięcie:** przy braku czasu zostaw **GŁÓWNY TEKST + kontrapułapkę** (~75 s), a „skąd się
  biorą te typy" i „trzecią gałąź" oddaj do Q&A. Sam slajd zostaw zawsze — to jedyny ekran
  z konfiguracją w całym talku.
- ⏱ **Skąd te 1,5 min:** slajd 12 oddaje tu swoją puentę (−20 s), slajd 14 leci w wersji „samo ⚓"
  (−20 s), slajd 15 skrócony do dwóch zdań (−15 s). AKT 3 zostaje w 12:00.

### Slajd 13 — Zwrot akcji · ~1 min
- 🗣 „Dodaliście wszystko, we właściwej kolejności. Brawo. Tylko skąd wiecie, że retry się ODPALIŁ? Ile razy? Czy circuit otworzył się w nocy? ⚓ Bez metryki — nie wiecie. Mechanizm bez widoczności to ten sam problem, schowany o warstwę głębiej."

### Slajd 14 — Micrometer · ~45 s *(cięcie awaryjne: samo ⚓)*
- 🗣 ⚓ „Micrometer jest dla metryk tym, czym SLF4J dla logów — jeden interfejs, backend z pom.xml. W Boot 3: jeden call = jedna Observation → i metryki, i trace'y."

### Slajd 15 — Metryki za darmo · ~1 min
- 🗣 „Resilience4j: zero kodu liczników. Trzy zależności i macie stan circuita, failure rate, czasy, retry. Wszystko z tamtego dashboardu — z trzech linijek w pom."
- ⏱ **CHECKPOINT: 28:30.**

# AKT 4 — ZASTOSOWANIE (28:30 → 38:00)
> **Rama aktu:** „Wracamy do requestu z wtorku — tego, o którym powiedzieliście »kto by tak zostawił«.
> Pokażę wam go dwa razy: jak wyglądał wtedy i jak wygląda teraz. Bez live-codingu, zrzut lewo-prawo."

### Slajd 16 — Ten sam request: bez odporności i z odpornością · ~2,5 min
- **Slajd = dwie kolumny.** LEWA: goły kod z wtorku (`restClient.post()…body()` i nic więcej).
  PRAWA: ten sam kod dziś — dwie adnotacje, fallback, dwa typy wyjątków. Pod spodem pasek z YAML-em.
- 🎭 **Zasada tego slajdu:** porównujesz **kod bez odporności z kodem z odpornością** — nie wersje
  swojego repo. Sala nie zna historii Twoich refaktorów i nie ma powodu jej znać. Zna goły request
  ze slajdu 2 i to jest jedyny punkt odniesienia, jaki ma.

- 🗣 *(wskaż LEWĄ kolumnę)* „Pamiętacie? To jest ten request z wtorku. Wysyłam, czekam, zapisuję.
  Trzy linijki, wszystko działa. *(pauza)* I dokładnie ten kod widzieliście dziś, jak umiera na
  ekranie — najpierw wolno, potem czerwono, na koniec wcale.

  *(przesuń rękę na PRAWĄ kolumnę)* A to jest ten sam request dzisiaj. **Policzcie zmiany w Javie.
  Dwie adnotacje.** `@Retry` i `@CircuitBreaker`. Cała reszta — progi, okna, czasy — mieszka w
  YAML-u, który czytaliście przez ostatnie dziesięć minut, nie wiedząc o tym: pół sekundy na
  slow-call, pięćdziesiąt procent na otwarcie, dziesięć sekund w otwartym.

  *(wskaż fallback)* Do tego jedna metoda — **fallback**. I dwie rzeczy, które w niej są ważne.
  Po pierwsze: łapie **wyłącznie** wyjątek otwartego obwodu, a nie »cokolwiek poszło źle«. Bo
  gdyby łapał wszystko, to Wasz własny błąd w kodzie przebrałby się za awarię zależności — i
  dashboard pokazałby awarię serwisu, który ma się świetnie.
  Po drugie: on **nie milczy**. Liczy metrykę i zwraca konkretną odpowiedź.

  *(wskaż typy wyjątków)* I ostatnia rzecz, najważniejsza dla tego, kto nas woła. Odmowa i
  niedostępność to **dwa różne typy**. Nie dlatego, że lubimy klasy — dlatego, że bezpiecznik i
  retry filtrują **po typie**. Dzięki temu bezpiecznik ignoruje odmowy, retry ponawia tylko to,
  co mogło się udać, a klient dostaje prawdę: »nie da się tej trasy« to **409**, zmień żądanie;
  »planner leży« to **503 z Retry-After** — wróć za dziesięć sekund. *(pauza)*

  ⚓ **Cała odporność tego systemu to dwie adnotacje i kartka YAML-a. Bariera wejścia nie istnieje.**"

- 🗣 *(zapas ~20 s — wskaż linijki backoffu w YAML-u)*: „I trzy słowa, które kosztują najmniej ze
  wszystkiego: `exponential-backoff` i `randomized-wait-factor`. Przy dwóch próbach to kosmetyka.
  Ale ktoś skopiuje ten blok do serwisu z pięcioma — i wtedy bez jittera **wszystkie wasze
  instancje ponowią w tej samej milisekundzie**, dokładnie wtedy, gdy zależność próbuje wstać.
  Wstaje, dostaje zsynchronizowaną falę i pada drugi raz. Jitter rozjeżdża te fale w czasie."

- 🚫 **Czego NIE mówić na tym slajdzie:** „mój stary fallback łapał Throwable", „mój kod przez rok
  zamieniał trzy awarie w jeden wyjątek". To są historie o ewolucji Twojego repo — dla sali szum.
  Wyznania o własnych zaniedbaniach zostają tam, gdzie mają sens: slajd 11 (kolejność aspektów,
  „nie wiedziałem, że mam wybór") i slajd 24 (reveal). Tutaj tłumaczysz **mechanizm**, nie biografię.

### Slajd 17 — Dashboard, 2 publiczności · ~45 s
- 🗣 „Dashboard ma dwa rzędy: *(gest)* dla devów — mechanika; *(gest)* dla biznesu — czy żyje, ile downtime, ile tras oddaliśmy. ⚓ Ten sam sygnał, dwa języki."

### Slajd 18 — Widoczność: jedna decyzja · ~1,5 min (+40 s opcjonalny wtręt)
- Slajd: trzy boxy — OBSERVATION (metryka+trace z jednego miejsca) · 3 ZALEŻNOŚCI (pom.xml, zero kodu liczników) · FALLBACK (nie milczy: tag outcome + 503) + pasek DEMO.
- 🗣 „I ostatnia rzecz przed powrotem do żywego systemu — najważniejsza, bo o niej jest cały ten talk. Breaker i retry dostaliście z dwóch adnotacji. Ale to, że dziś je **widzieliście** — jest z tego slajdu.
  *(box 1)* Jedno wywołanie owinięte w **Observation** — Micrometer robi z niego i metrykę, i trace'a. *(box 2)* Trzy zależności w pom — i Resilience4j sam wystawia stan bezpiecznika, failure rate, czasy, retry. Wszystko, co dziś oglądaliście, bierze się stąd. *(box 3)* I jeden licznik, który napisałem **ręcznie** — jedyny: ile rezerwacji straciło trasę i **dlaczego**. On tłumaczy technikę na język produktu. *(pauza)* ⚓ **Mechanizm był za darmo. Widoczność kosztowała jedną decyzję: że chcemy wiedzieć.**
  Sprawdźmy, czy to wszystko naprawdę działa."
- 🗣 **POMIAR, KTÓRY MNIE ZASKOCZYŁ** *(~40 s — wtręt budujący wiarygodność; wstaw TU albo w Q&A, patrz cięcia)*:
  „Zanim przełączę — jedna rzecz o tym, skąd wiem, że to działa. Nie wiem tego z dokumentacji. *(pauza)* Przegoniłem cały ten scenariusz na żywym systemie i odczytywałem liczby prosto z Prometheusa. I **jeden pomiar wywrócił moje założenie.**
  Byłem pewien, że jak zepsuję połowę wywołań, to zobaczę pięćdziesiąt procent błędów i otwarty bezpiecznik. Wyszło: **zero procent błędów i bezpiecznik spokojnie zamknięty.** *(pauza)* Dlaczego? Bo narzędzie, którym psuję, działa **na połączeniach**, a nie na żądaniach — a pula keep-alive obsługuje większość żądań po połączeniach już nawiązanych, czyli zdrowych. Nominalne »pięćdziesiąt procent« nie równało się połowie żądań.
  ⚓ **Gdybym oparł dzisiejsze demo na założeniu zamiast na pomiarze — nie zadziałałoby na waszych oczach.** I to jest ta sama lekcja co przez cały talk, tylko o poziom wyżej: nie ufaj temu, co *powinno* się dziać. Zmierz."
- 🎬 **PRZEŁĄCZ NA GRAFANĘ.**

### 🎬 DEMO-NAPRAWA — Terminal B · ~4 min

**Krok 1 — partial (retry pracuje):**
```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\demo-resilience.ps1 -Chaos partial
```
- 🗣 „Nowy scenariusz: część wywołań wisi do timeoutu. Panel retry — pierwszy raz dziś ożyje. *(cierpliwie, 30–60 s)* `successful_with_retry` skacze o schodek: retry **ratuje** — druga próba trafia na zdrową ścieżkę. `failed_with_retry` też rośnie: część drugich prób znowu wisi. Retry nie jest dekoracją — tu dowód."
- 🎭 quiz (1 głos): „Czemu te liczniki tylko rosną? — Kumulatywne; rate() między zdarzeniami kłamałby zerami."
- 📊 **Zmierzone na żywo:** breaker zostaje ZAMKNIĘTY (timeout-toxic działa per połączenie, pula keep-alive łagodzi efekt — realnie wisi ułamek wywołań, failure rate ~0%, slow rate ~10%). Liczniki retry rosną **schodkami co kilkanaście–kilkadziesiąt sekund** (+2/+3 na minutę), nie strumieniem — daj panelowi czas i pokaż schodek palcem. Zakres paneli ustaw na 5–15 min, żeby schodki były czytelne.
- **Sprzątnij:** `-Chaos heal` *(„sprzątam — następny test zasługuje na czysty start")*.


**Krok 2 — down + ALERT + heal (finał techniczny):**
```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\demo-resilience.ps1 -Chaos down
```
- 🗣 „I pełna awaria — sala, co się stanie? *(»otworzy się«)* Otworzy. *(czerwono)* I rzecz, której rano nie pokazałem—" 🎬 karta **:9090/alerts** → FIRING.
- 🗣 ⚓ „Nie opowiadam o alercie. On się właśnie odpalił — na waszych oczach."
- 🩹 **Timing alertu (zmierzone):** po `down` alert jest **pending przez ~60–90 s** (`for: 30s` + interwał ewaluacji), dopiero potem FIRING. Nie przełączaj karty za wcześnie — najpierw omów czerwony dashboard (~minutę), przełącz na alerts, gdy jest już FIRING. Pending na ekranie też da się ograć: *„Prometheus właśnie się upewnia, że to nie mrugnięcie — za chwilę strzeli."*
```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\demo-resilience.ps1 -Chaos heal
```
- 🗣 „Naprawiam — i nic więcej nie robię. *(10–15 s)* Pomarańcz: sam sprawdza. *(zielony)* Zielony. ⚓ **Sam się zdiagnozował, sam odciął, sam sprawdził, sam wrócił. A my widzieliśmy każdy krok.**" *(3 s ciszy)*
- 🎬 **NA SLAJDY** → 19–20 przewiń (rezerwowe, tylko Plan B) → slajd 21.
- ⏱ **CHECKPOINT: 38:00.**

# AKT 5 — SYNTEZA (38:00 → 45:00)

### Slajd 21 — „Czwarty stan" (AI) · ~2 min
> ✅ **Deck v4 pokazuje tu już „Czwarty stan"** (dwie kolumny + czerwony wiersz „odpowiada
> źle → ???"). Sekcja 8.8 to wyłącznie rezerwa: rozbudowana odpowiedź w Q&A (rezerwowy slajd
> SB4 jest w decku za Q&A) albo awaryjny powrót do starego decka v3.
- 🗣 „Spojrzenie w przód. Przez czterdzieści minut mówiliśmy o jednym wywołaniu HTTP między
  dwoma serwisami. Teraz podmieńcie w głowie jedną rzecz: pod tym URL-em, do którego strzela
  starport, nie siedzi planner. Siedzi model językowy. *(pauza)* Co się zmieniło?
  *(krótka cisza)* **Nic.** To dalej jest jedno zdalne HTTP, które może być wolne, może
  odpowiedzieć błędem, może zamilknąć. Timeout, retry, bezpiecznik, fallback — wszystko,
  co dziś widzieliście, obowiązuje jeden do jednego.
  Z jedną różnicą, która zmienia arytmetykę: **każde ponowienie kosztuje realne pieniądze.**
  Retry to nie darmowa druga próba — to drugi rachunek za tokeny. Bezpiecznik przestaje być
  tylko ochroną zależności — staje się ochroną budżetu. Zresztą API modeli już mówią językiem
  z naszego demo: przy limicie dostajecie czterysta dwadzieścia dziewięć z Retry-After —
  **dokładnie ten kontrakt, który dziś widzieliście przy pięćset trzy.** A fallback przestaje
  być kodem błędu: to mniejszy model, cache, wczorajsza odpowiedź.
  I jest coś, czego HTTP nie ma. **Czwarty stan.** Model odpowiedział szybko. Dwieście.
  Zero błędów. Dashboard wygląda dokładnie jak nasz wtorek — zielono, płasko, szybko.
  Tylko w odpowiedzi jest bzdura. *(pauza)*
  Pamiętacie krok, w którym system zauważył i nie zareagował? To było groźne. Tu jest gorzej:
  **system w ogóle nie zauważa.** Nie ma progu w YAML-u, który łapie kłamstwo. Nie ma
  adnotacji na halucynację. ⚓ **Tam »działa« nie znaczyło »działa i ja o tym wiem«.
  Tu »odpowiada« nie znaczy »odpowiada prawdę«.**
  Pamiętacie jedyny licznik, który napisałem ręcznie — ile rezerwacji straciło trasę
  i dlaczego? Przy modelu taki ręczny licznik jakości to już nie jest »jedna miła metryka
  domenowa«. To jedyne, co w ogóle widzi czwarty stan. Zresztą branża już to standaryzuje —
  OpenTelemetry ma dziś GenAI semantic conventions: te same trace'y i metryki, które dziś
  oglądaliście, tylko dla wywołań modeli.
  ⚓ **To, co dziś zobaczyliście, to nie jest wiedza o jednej bibliotece. To warsztat pod
  najdroższą i najmniej przewidywalną zależność, jaką wasze serwisy zaczną wołać.**
  A co się stanie, gdy model naprawdę zamilknie… *(pauza, pół uśmiechu)* to już jest
  osobna opowieść. Może następna."
- 🎭 Ton: spokojna ekscytacja, nie hype. ZERO nazw dostawców modeli, zero „AI zmieni wszystko".
  Ostatnie zdanie zawieszasz i od razu → slajd 22 — **nie zbieraj reakcji sali**, teaser ma
  wisieć, nie być dyskutowany (dyskusja → Q&A).
- 🚫 **Zakazane:** wchodzenie w prompty/evale/konkretne modele, obiecywanie demo z LLM,
  słowo „halucynacja" więcej niż raz. To trailer, nie drugi talk.
- 🎭 *(cięcie awaryjne: zostaw akapit „czwarty stan" + obie kotwice ⚓, wytnij akapit o koszcie
  retry z łukiem 429/503 — jego treść i tak wróci w serii)*
- 🎭 **Budżet czasu:** pełna wersja to realnie ~2:15, nie 2:00 — jeśli wchodzisz na slajd po
  38:30, graj od razu cięcie awaryjne. **Riposty trzymaj na Q&A, nie otwieraj ich na scenie:**
  „zwykły serwis też zwraca bzdury" → pyt. 13c · „jaki timeout na model?" → pyt. 13d ·
  „Spring AI ma retry" → pyt. 13e.
- **Boot 4 w Q&A, nie na scenie:** jeśli pytanie padnie, jedno zdanie — „Boot 4 wciąga retry
  i limit współbieżności do rdzenia, ale bezpiecznika tam nie ma, a `maxAttempts` znaczy co
  innego; szczegóły mam spisane" → cheatsheet sekcja D, pyt. 11–13; pełny stary tekst: 8.8.

### Slajd 22 — Wróćmy do listy · ~1,5 min
- 🗣 *(markerem NA ŻYWO)*: „Wasza lista. Health-check? Macie — kafelek DOSTĘPNA. Retry? Widzieliście panel, który udowadnia, że pracuje. Timeout? 800 ms na wykresie latencji. Alert? Odpalił się na waszych oczach. Metryka? Wszystko dziś było metryką. *(jeśli padło „limit")* Limit? Dziś go nie pokazałem — jedna adnotacja więcej, ta sama biblioteka; praca domowa: sprawdźcie, co się dzieje, gdy to WY jesteście lawiną. ⚓ Ja wam niczego nie sprzedałem. To WASZE pomysły sprzed czterdziestu minut — narzędzia je po prostu spełniają."
- 🗣 *(most do materiału — ~15 s, mów, schodząc od flipchartu)*: „I żebyście nie musieli tego odtwarzać z pamięci: tę listę mam spisaną jako **checklistę dziesięciu pytań** do zadania własnemu serwisowi. Razem z całą resztą — kod przed i po, arytmetyka bezpiecznika, pułapki migracji na Boota 4. *(pauza)* Powiem na końcu, gdzie to jest."

### Slajd 23 — ECHO pytania · ~45 s · most do revealu
- 🗣 *(zmiana tonu — ciszej, wolniej)*:
  ⚓ „Na początku zapytałem: kto by tak zostawił? Pokręciliście głowami. *(pauza)* To teraz wyobraźcie sobie firmę, w której ktoś JEDNAK tak zostawił. Taki request. Bez retry. Bez metryki. Bez nadzoru. *(pauza)* Jak myślicie — jak długo można czegoś takiego nie zauważyć? Dzień? Tydzień? *(zbierz 1–2 liczby — NIE komentuj)*"
- 🎭 sala rzuca liczby w dobrej wierze. Każda będzie za mała. Nie uśmiechaj się.

### Slajd 24 — „3 miesiące" + REVEAL · ~2 min · 💙 SERCE
- 🗣 *(reveal jest TEKSTEM — slajd pokazuje tylko liczbę)*:
  „Trzy miesiące. *(długa pauza)*
  I to nie jest scenariusz, który wymyśliłem na potrzeby wykładu. ⚓ To była moja firma. Mój zespół. Wdrożyliśmy usługę — wszystko na zielono, testy przeszły, deploy przeszedł. Tylko jeden adres — poprawnie sformatowany, odpowiadający w testach — był niewłaściwy. I system dokładnie taki, jak ten, który dziś oglądaliście — tyle że BEZ tych ekranów — wysyłał requesty w zły adres. Przez trzy miesiące.
  A wiecie, jak wyszło? Nie z alertu. Nie z dashboardu. *(pauza)* Przypadkiem. Ktoś przeglądał logi z zupełnie innego powodu. Dwa błędy na dobę, utopione w tysiącach linii szumu.
  Wszystko, co dziś widzieliście — każdy wykres, każdy alert, każdy diff — istnieje w moim systemie dlatego, że raz tego zabrakło. Takie rzeczy nie »zdarzały się kiedyś«. One dzieją się dalej — dokładnie teraz, w systemach ludzi, którzy tak samo jak wy pokręcili głowami na pytanie »kto by tak zostawił«."
- 🎭 zwolnij maksymalnie, mów jak do jednej osoby, zero wymówek.

### Slajd 25 — Klamra · ~45 s · ⭐
- 🗣 *(wolno, pauzy)*: „System się BĘDZIE psuł. Sieć padnie, zależność zwolni, ktoś wdroży zły adres — jak ja. *(pauza)* ⚓ Nie chodzi o to, żeby się nie psuło. Chodzi o to, żeby psucie było WIDAĆ."

### Slajd 26 — Dziękuję / Q&A
- 🗣 „Dziękuję. *(pauza, uśmiech)* Pytania?"
- 🗣 *(gdy pytania przygasają — materiał do zabrania)*: „I ostatnia rzecz. Wszystko, co dziś pokazałem, opisałem szerzej — z kodem przed i po, arytmetyką okna bezpiecznika i pułapkami migracji na Boot 4. To jest w repo jako **`WYKLAD-resilience-mid-java.pdf`**, dwadzieścia sześć stron. Jeśli macie w zespole kogoś, kto dopiero wchodzi w te wzorce — to jest dla niego."
- 🗣 *(OPCJONALNIE — mów TYLKO, gdy termin następnego talku jest realny)*: „A jeśli zaczepił was ten czwarty stan — to będzie osobna prezentacja."
- 🎬 **`QA-cheatsheet-KOLB-v3.md` na 2. ekranie** (26 pytań dopasowanych do tej wersji talku). Skróty do najczęstszych: cebula/aspectOrder → pyt. 6–7 (kotwica: slajd 11 + dwa testy w repo) · „czemu 503 a nie 409" → pyt. 9 · „czemu nie @TimeLimiter" → pyt. 3 · „a RateLimiter/Bulkhead?" → pyt. 17 · Spring Boot 4 → pyt. 11–13 (zeszło ze sceny w v4 — pełny tekst: sekcja 8.8, rezerwowy slajd za Q&A) · pytania o AI/LLM po slajdzie 21 → pyt. 13b–13e (halucynacja / „serwis też zwraca bzdury" / timeout na model / retry w Spring AI) · „a Wam się to zdarzyło?" przed slajdem 24 → pyt. 18.
- ⏱ **KONIEC: 45:00.** Po talku: Ctrl+C w Terminalu A; opcjonalnie `docker compose ... down` (oba `-f`); `git checkout master`.

---

## 4. Mapa „treść → slajd pptx" (w v4: 29 pozycji — doszedł rezerwowy SB4 za Q&A)

| # | Treść | Status (deck v4 = v3 + wymieniony slot 21 + rezerwa SB4 na końcu) |
|---|---|---|
| 1 | Tytuł | bez zmian |
| 2 | Wyobraźcie sobie | bez zmian |
| 3 | Mapa systemu | bez zmian |
| 4 | Kto by tak zostawił? | bez zmian |
| 5 | Popatrzcie na ekran | bez zmian |
| 6 | Co widzicie? | bez zmian |
| 7 | A może powinniśmy (flipchart) | bez zmian |
| 8 | TEZA | bez zmian |
| 9 | Mapa złudzeń → 3 wzorce | **DO PRZERÓBKI: tabela 3 wiersze (nie 5 pigułek)** |
| 10 | Bezpiecznik | bez zmian |
| 11 | Cebula #2383 | bez zmian (dochodzi wyznanie w tekście mówionym) |
| 11a | Kolejność aspektów — co możesz zmienić | **DODANY** (konceptowy: hasła + YAML) |
| 12 | Retry ≠ 4xx | bez zmian (puenta oddana do 12a) |
| 12a | Biała lista: trzy linijki, trzy decyzje | **DODANY** (YAML `record`/`ignore`/`retry-exceptions`) |
| 13 | Zwrot akcji | bez zmian |
| 14 | Micrometer | bez zmian (mów wersję „samo ⚓") |
| 15 | Metryki za darmo | bez zmian (mów dwa zdania, nie trzy) |
| 16 | Ten sam request: bez odporności i z odpornością | **DO PRZERÓBKI: dwie kolumny — goły kod z wtorku obok kodu z odpornością** |
| 17 | Dashboard 2 publiczności | bez zmian |
| 18 | Widoczność: jedna decyzja | **PRZEROBIONY z RL+BH — Observation / 3 zależności / fallback+503** |
| 19 | „Demo" (rezerwowy) | Plan B |
| 20 | Alert (rezerwowy) | Plan B |
| 21 | „Czwarty stan" (AI) | **✅ ZROBIONE w v4.pptx** (nowy slajd wg szkicu 8.6; stary SB4 → za slajd 26 jako rezerwowy do Q&A) |
| 22 | Wróćmy do listy | drobna korekta (bez linii o floodzie) |
| 23 | ECHO pytania | bez zmian |
| 24 | 3 miesiące (+reveal mówiony) | bez zmian |
| 25 | Klamra | bez zmian |
| 26 | Dziękuję / Q&A | bez zmian |

## 5. Progi w głowie
**300 < 440 < 500 (slow-call) < 600 < 800 (read-timeout)** · próg otwarcia 50% · okno 10 · half-open po 10 s, 3 próby · retry: 2 próby, ~100 ms ± jitter.
Kontrakt HTTP: **409** = odmowa domenowa („zmień żądanie") · **503 + Retry-After: 10** = niedostępność („wróć za chwilę").

## 6. Pułapki v3
1. **Tajemnica historii** — na slajdach 2 i 8 ani słowa o „mojej firmie". Q&A po AKT 2: „Dobre pytanie. Wrócę do niego na końcu, obiecuję."
2. **Milczenie w AKT 1** — ćwicz osobno: Enter → cisza → pytanie.
3. **Krok 440: nie podawaj liczb** — jitter sprawia, że spike slow-call bywa 0–20%. Mów „coś się obudziło".
4. **Interakcje z limitem:** slajd 4 = 2–3 hasła; slajd 6 = 3 głosy; flipchart = 2,5 min; quiz AKT 4 = 1 głos; echo = 1–2 liczby bez komentarza.
5. **Branch + commity:** `git log --oneline -4` przed talkiem MUSI pokazać commity resilience — bez nich slajdy 16–18 pokazują diff, którego nie ma w kodzie.
6. **Zrzuty = kod co do linii.** Po KAŻDEJ zmianie w kodzie regeneruj zrzuty slajdów 16–18. Kolejność zawsze: kod → screenshoty → pptx.
7. **`heal` po partial** — krok `down` na wpół-chorym plannerze zamaże kontrast „czerwono OD błędów".
8. **AI-teaser (slajd 21) nie otwiera dyskusji** — zawieś ostatnie zdanie i idź do slajdu 22.
   Dyskusja o modelach 6 minut przed revealem rozbroi slajd 24; każde pytanie o AI → „po
   prezentacji, chętnie". I graj z decku **v4** — jeśli na rzutniku wisi stary v3 (slot 21 =
   SB4), nie mów tekstu AI, tylko graj sekcję 8.8.

---

## 7. Mapa: scena ↔ wykład (`WYKLAD-resilience-mid-java.pdf`, 26 stron)

Po co ta tabela: żebyś **wiedział, co możesz spokojnie odesłać do materiału**, zamiast wciskać w budżet 45 minut. Gdy pytanie dotyczy prawej kolumny, właściwa odpowiedź brzmi: *„krótka wersja: … — pełna jest w wykładzie, rozdział N"*.

| Temat | Na scenie | W wykładzie |
|---|---|---|
| Trzy tryby porażki + fallacies | slajd 9 | rozdz. 1 |
| Mapa systemu | slajd 3 | rozdz. 2 |
| **Drabina progów: dlaczego 500 < 800** | **slajd 10 (dodane)** | rozdz. 3 |
| `@TimeLimiter` nie działa synchronicznie | tylko Q&A (pyt. 3) | rozdz. 3 |
| Bezpiecznik: 3 stany, dwa progi | slajd 10 | rozdz. 4 |
| Okno przesuwne, `minimum-number-of-calls`, `-1` | tylko Q&A (pyt. 4–5) | rozdz. 4 |
| Retry: co ponawiać | slajd 12 | rozdz. 5 |
| **Biała lista: `record`/`ignore`/`retry-exceptions`** | **slajd 12-bis (dodane)** | rozdz. 5 |
| Czym jest `RuntimeException` i czemu oślepia | **slajd 12-bis** (kontrapułapka) + Q&A pyt. 10b | rozdz. 5 |
| Trzecia gałąź: „ani jedno, ani drugie → SUKCES" | **slajd 12-bis** + Q&A pyt. 10c | rozdz. 5 |
| Cztery problemy kodu wyjściowego | slajd 16 (dwa główne) | rozdz. 6 |
| Zmiana 1–2 (fallback, dwa wyjątki) | slajd 16 | rozdz. 7–8 |
| Cebula + kiedy odwrócić | slajd 11 + **11-bis (konceptowy, bez zrzutu)** | rozdz. 9 |
| Backoff + jitter | **slajd 16 (dodane, opcjonalne)** | rozdz. 10 |
| Kontrakt 409 / 503 | slajd 16 | rozdz. 11 |
| Observability, Observation, licznik domenowy | slajdy 13–15, 17–18 | rozdz. 12 |
| Kardynalność etykiet | tylko Q&A (pyt. 16) | rozdz. 12 |
| **Pomiar, który obalił hipotezę** | **slajd 18 (dodane, opcjonalne)** | rozdz. 13 |
| Spring Boot 4 + pułapki migracji | **tylko Q&A (pyt. 11–13)** — zeszło ze sceny w v4 | rozdz. 14–15 |
| **Zakończenie AI: czwarty stan** | **slajd 21 (v4)** | *(poza wykładem — materiał następnego talku)* |
| Checklista 10 pytań do własnego serwisu | **wzmianka slajd 22 (dodane)** | rozdz. 16 |
| Ściągawka progów i PromQL | sekcja 5 tego runbooka | rozdz. 17 |

**Trzy wtręty dodane z wykładu — i co zrobić, gdy goni czas:**
- **Drabina progów (slajd 10, ~30 s)** — *nie tnij*. Bez niej sala widzi, że bezpiecznik zadziałał, ale nie rozumie, dlaczego było to możliwe przy zerze błędów. To mechanika, na której stoi cały AKT 1.
- **Pomiar, który zaskoczył (slajd 18, ~40 s)** — tnij pierwszy, jeśli jesteś spóźniony. Świetnie jednak działa jako odpowiedź w Q&A na „skąd wiecie, że to działa" (cheatsheet, pyt. 19).
- **Most do checklisty (slajd 22, ~15 s)** — tnij drugi; slajd 26 i tak wskazuje materiał.

---

## 8. Zakończenie AI „Czwarty stan" (slajd 21) — uzasadnienie i wdrożenie

> **STATUS: WDROŻONE W CAŁOŚCI (2026-08-06).** Scenariusz mówiony jest w AKT 5 → „Slajd 21 —
> »Czwarty stan« (AI)" i TAM jest wersja kanoniczna. Deck `..._KOLB_v4.pptx` ma nowy slajd 21
> wg 8.6, stary SB4 jako rezerwę za Q&A; cheatsheet ma pyt. 13b. Stan prac: checklista 8.7.
> Sekcja 8.8 = tekst SB4 na wypadek grania ze starego decka v3 albo rozbudowanego Q&A.

### 8.1 Dlaczego wymiana — trzy argumenty

1. **SB4 ma datę ważności.** Za rok „co nowego w Boot 4" to slajd historyczny; ten talk ma żyć
   dłużej niż jedna edycja. Cała merytoryka SB4 **nie znika** — przenosi się w całości do Q&A
   (cheatsheet, sekcja D, pyt. 11–13, już istnieje i jest kompletna z pułapką `maxAttempts`).
2. **AI otwiera serię, SB4 ją zamyka.** Zakończenie-teaser „Twój serwis woła model AI. Co się
   stanie, gdy model zamilknie?" to gotowy tytuł następnego talku. Sala wychodzi z pytaniem,
   nie z release-notes.
3. **Teza skaluje się o poziom, nie zmienia tematu.** LLM to dokładnie ten sam bohater —
   jedno zdalne HTTP — więc slajd nie jest doklejką „bo AI modne", tylko naturalnym
   uogólnieniem czterdziestu minut. A „czwarty stan" (odpowiedział poprawnie technicznie,
   ale źle) to teza talku podniesiona o poziom: *tam* zielony dashboard ukrywał awarię,
   *tu* zielony dashboard ukrywa bzdurę.

### 8.2 Dlaczego slot 21, a nie po klamrze

Dramaturgia AKT 5 stoi na sekwencji **lista → echo → reveal → klamra**: szczyt emocjonalny to
slajd 24 i nic nie ma prawa grać PO nim (klamra tylko wybrzmiewa). Slot 21 to od zawsze miejsce
na „spojrzenie w przód" — intelektualny szczyt przed emocjonalnym. AI-teaser wchodzi dokładnie
w tę rolę: otwiera horyzont, po czym slajd 22 („Wróćmy do listy") ściąga salę z powrotem na
ziemię i arka emocjonalna biegnie bez zmian. Wariant „AI po slajdzie 25" testowany na sucho
brzmi jak reklama po napisach końcowych — odrzucony.

### 8.3 Scenariusz mówiony — przeniesiony do AKT 5

Pełny tekst mówiony, reżyserka (🎭/🚫) i cięcie awaryjne żyją w **jednym miejscu**:
AKT 5 → „Slajd 21 — »Czwarty stan« (AI)". Tu celowo nie ma kopii — dwie wersje tego samego
tekstu rozjadą się przy pierwszej korekcie (ta sama zasada co „zrzuty = kod co do linii").
Budżet: ~2 min, identyczny jak SB4 — **checkpointy 38:00 → 45:00 bez zmian.**

### 8.4 Kotwice w tym, co sala już widziała (nie dodawaj nowych bytów)

| Fraza na slajdzie 21-AI | Skąd sala to zna |
|---|---|
| „jedno zdalne HTTP" | slajd 2–3 (bohater talku) |
| „zielono, płasko, szybko" | slajd 5, dosłowny cytat z wtorku |
| „system zauważył i nie zareagował" | krok 440 z demo-ślepego |
| „retry = drugi rachunek" | slajd 12 (retry ma koszt) + jitter ze slajdu 16 |
| „fallback = wczorajsza odpowiedź" | fallback ze slajdu 16 (nie milczy, zwraca konkret) |
| „ręczny licznik jakości" | slajd 18, licznik `outcome` („jedyny napisany ręcznie") |
| „429 z Retry-After — ten sam kontrakt" | 503 + Retry-After z demo (kontrakt ADR-0039; cheatsheet pyt. 9) |

To jest siła tego zakończenia: **zero nowych pojęć**, każda linijka to mapowanie czegoś,
co sala widziała na żywo, na nowy kontekst.

### 8.5 Los treści SB4

- **Nic nie kasujemy.** Pełne wersje: cheatsheet sekcja D (pyt. 11–13) + wykład rozdz. 14–15.
- Jeśli pytanie o Boot 4 padnie w Q&A, odpowiedź sceniczna w jednym zdaniu:
  „Boot 4 wciąga retry i limit współbieżności do rdzenia — świetnie — ale bezpiecznika tam
  nie ma, a `maxAttempts` znaczy co innego; szczegóły chętnie po prezentacji, mam je spisane."
- Slajdy 19–20 (Plan B) — bez zmian; przy wymianie 21 nadal działają.

### 8.6 Szkic slajdu (✅ zrealizowany w `Działanierównasiędziała_KOLB_v4.pptx`, 2026-08-06)

Dwie kolumny + jeden wiersz-wyjątek:

```
   HTTP do serwisu            HTTP do modelu
   ───────────────            ──────────────
   wolny        → timeout     wolny         → timeout (te same sekundy, drożsi klienci)
   błąd         → retry       błąd          → retry ($ za każdą próbę)
   milczy       → breaker     milczy        → breaker + fallback (mniejszy model / cache)
                              odpowiada źle → ???          ← jedyny czerwony element slajdu
```

Tytuł slajdu: **„Czwarty stan"** · ewentualny podtytuł: *OpenTelemetry GenAI semconv — to się
właśnie standaryzuje*. Wiersz „???" jest jedynym akcentem kolorystycznym — cały slajd gra
na jego pustkę.

### 8.7 Checklista wdrożenia

**Zrobione w runbooku (v4, 2026-08-06):**
- [x] AKT 5: slajd 21 podmieniony na „Czwarty stan" (AI); stary tekst SB4 → 8.8
- [x] Mapa „treść → slajd pptx" (sekcja 4, wiersz 21): `Spring Boot 4` → `Czwarty stan (AI)`
- [x] Sekcja 0 (mapa aktów) + sekcja 7 (scena ↔ wykład): SB4 zeszło ze sceny do Q&A
- [x] Pułapki (sekcja 6, pkt 8): AI-teaser nie otwiera dyskusji + pilnowanie rozjazdu z deckiem

**Zrobione poza runbookiem (2026-08-06):**
- [x] **Nowy slajd 21 w pptx wg 8.6** — w `Działanierównasiędziała_KOLB_v4.pptx` (v3 zostaje
      jako backup); stary slajd SB4 przeniesiony na sam koniec, za Q&A, jako rezerwowy
- [x] Cheatsheet: nowa sekcja D2, pyt. 13b „To jak się bronić przed halucynacją?" — „Dziś:
      ręczna metryka jakości i human-in-the-loop; systemowo — temat osobnego talku"
      (sekcja D bez zmian jako odpowiedź na pytania o Boot 4)
- [x] Slajd 26 / zapowiedź serii: dopisana w AKT 5 jako linia OPCJONALNA — *mów ją dopiero,
      gdy termin następnego talku będzie realny; do tego czasu pomijasz*

**Korekty po review java-AI (2026-08-06):**
- [x] Slajd 21: łuk „429 + Retry-After = nasz kontrakt 503 + Retry-After" w akapicie o koszcie
      retry (pada razem z nim w cięciu awaryjnym); w 🎭 uczciwy budżet ~2:15 + mapa ripost
- [x] Cheatsheet D2: pyt. 13c (bzdura przy 200: bug vs **tryb pracy**), 13d (dwa timeouty:
      time-to-first-token + idle), 13e (defaulty retry Spring AI `maxAttempts=10` + backoff
      2 s→3 min; niedeterminizm ponowienia); w 13b dopisek „semconv `gen_ai.*` ≠ jakość treści"
- [x] Liczba stron wykładu ujednolicona do **26** (slajd 26, sekcja 7, cheatsheet — było
      „21"/„22", plik ma 26)

### 8.8 Rezerwa: stary slajd 21 — Spring Boot 4 (pełny tekst mówiony)

Grasz to tylko, jeśli na rzutniku wisi stary deck v3 (slot 21 = SB4), albo jako rozbudowaną
odpowiedź w Q&A — w decku v4 masz do niej rezerwowy slajd SB4 na końcu, za Q&A
(kotwice: cheatsheet sekcja D, pyt. 11–13; wykład rozdz. 14–15).

- 🗣 „Spojrzenie w przód, bo to pytanie i tak padnie. Boot 4 / Framework 7: retry i limit współbieżności wchodzą do RDZENIA — `@EnableResilientMethods`, `@Retryable`, `@ConcurrencyLimit`, zero zewnętrznych zależności. Świetna wiadomość. I nie zwalnia z niczego, o czym dziś mówiłem.
  **Po pierwsze** *(wskaż prawą kolumnę)*: w rdzeniu **nie ma circuit breakera**. Nie ma slow-call, nie ma deklaratywnego fallbacku, nie ma gotowych metryk stanu obwodu. Po nie nadal idziecie do Resilience4j — a to znaczy, że składacie aspekty z **dwóch bibliotek na jednej metodzie**. Pamiętacie cebulę? Wraca w trudniejszej wersji, bo kolejność trzeba teraz uzgodnić między frameworkami.
  **Po drugie, pułapka dla tych, którzy będą przepisywać konfigurację:** w Springu `maxAttempts` liczy **tylko ponowienia**, pierwsze wywołanie się nie liczy — domyślnie trzy ponowienia, czyli cztery wywołania. W Resilience4j dwójka znaczy „pierwsza próba plus jedno ponowienie". *(pauza)* Przepiszecie moje `max-attempts: 2` jeden do jednego — i **podnosicie ruch do zależności o pięćdziesiąt procent**, nic nie zmieniając świadomie.
  **Po trzecie** *(wskaż `@ConcurrencyLimit`)*: on **blokuje**, nie odrzuca. Przy pełnym limicie wątki czekają w kolejce, zamiast dostać szybką odmowę. Pod przeciążeniem rośnie wam latencja zamiast liczby uczciwych odmów — i to jest inna decyzja produktowa, nie ten sam wzorzec pod nową nazwą.
  ⚓ **To podzbiór, nie zamiennik. Zmienia się dostawca adnotacji; zasada zostaje.**"
- 🎭 *(cięcie awaryjne: zostaw „po pierwsze" i kotwicę, wytnij drugie i trzecie — pełne wersje masz w `QA-cheatsheet-KOLB-v3.md`, pyt. 11–13)*
