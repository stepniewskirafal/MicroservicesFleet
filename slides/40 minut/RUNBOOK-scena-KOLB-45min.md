# RUNBOOK SCENICZNY — „Działa ≠ działa i ja o tym wiem" · WERSJA KOLB v5 · 45 min

> **Zgrany z deckiem:** `Działanierównasiędziała_KOLB_v4.pptx` i branchem **`demo`**.
> ⚠️ **v5 to zmiana wyłącznie w runbooku** (warstwa AI + skrócony slajd 21) — deck pozostaje v4
> i wymaga **jednej** poprawki tekstu na slajdzie 21, opisanej w 8.6. Numer decku celowo się nie
> zmienia; nie rób nowego pliku pptx dla jednej linijki.
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
> **Co zmieniło się względem v4 (warstwa AI, 2026-08-11) — po panelu recenzenckim:**
> 7. **AI przestaje być jednym slajdem, staje się cienką warstwą przez cały talk.** Zamiast 2:15
>    gęstego monologu na 43. minucie — **cztery zaczepienia po 15–20 s** w miejscach, w których
>    temat jest merytorycznie własny (12 · 15 · 18 · 25), plus **skrócony i poprawiony slajd 21**.
>    Efekt: AI na scenie rośnie (~2:15 → ~2:45), a AKT 5 **schodzi** z przekroczenia budżetu.
> 8. **Slajd 21 przepisany jako osobny tekst: RDZEŃ ~65 s + dwa opcjonalne bloki po ~20–30 s.**
>    Poprawione trzy błędy merytoryczne (429 ≠ 503, „te same sekundy" → dwa timeouty, „czego HTTP
>    nie ma" → „co przy HTTP było wypadkiem"). Jedna kotwica ⚓, jako ostatnie zdanie.
> 9. **Nowa sekcja 9 — zaplecze merytoryczne AI.** Wszystko, co zeszło ze sceny albo nigdy na nią
>    nie weszło: mapa 1:1 z granicami analogii, telemetria Spring AI, koszt, TTFT/TPOT,
>    kardynalność, semconv, gotowe odpowiedzi Q&A (13f–13k) i roadmapa demo na następny talk.
>    Materiał źródłowy: **`Observability_w_AI_od_juniora_do_seniora.pdf`** (37 s., ten sam katalog).
> 10. **Drugi materiał do zabrania.** Slajd 26 wskazuje teraz dwa PDF-y: resilience (26 s.)
>    i observability w AI (37 s.). To jest odpowiedź na zarzut „21 to jedyny slajd bez dowodu".
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

**Warstwa AI — gdzie pada i ile kosztuje** *(v5; wcześniej cała była na slajdzie 21)*:

| Gdzie | Co | Czas | Wolno wyciąć |
|---|---|---|---|
| Slajd 12 (retry ≠ 4xx) | „każde ponowienie ma cenę **na fakturze**" | ~15 s | tak |
| Slajd 15 (metryki za darmo) | „Spring AI instrumentuje model **tym samym** Observation API" | ~20 s | tak |
| Slajd 18 (widoczność) | zapowiedź ręcznego licznika jako jedynej metryki **wyniku** | ~15 s | tak |
| **Slajd 21 (RDZEŃ)** | czwarty stan + jedna kotwica + `???` | **~65 s** | **nie** |
| Slajd 21 (bloki opcjonalne) | A: rachunek za retry · B: licznik jakości | A ~30 s · B ~20 s | tak — **domyślnie NIE grasz** |
| Slajd 25 (klamra) | „…a niektóre awarie nie mają nawet koloru" | ~5 s | nie (0 s netto) |
| Slajd 26 | drugi PDF do zabrania | ~10 s | tak |

**Zasada tej warstwy:** każde zaczepienie jest **jednym zdaniem w miejscu, które i tak omawiasz** —
zero nowych bytów przed slajdem 21. Pierwszy raz słowo „model" pada w minucie ~24 (slajd 12), więc
slajd 21 nie startuje z zera. Jeśli tniesz wszystkie opcjonalne — talk dalej się klei, bo RDZEŃ 21
nie odwołuje się do żadnego z nich.

**Skąd biorą się te sekundy — bilans jest dodatni:**

| Akt | Dokładasz | Skąd / co odzyskujesz | Netto |
|---|---|---|---|
| AKT 3 (12:00) | zaczepienia #1 i #2: **+35 s** | z oznaczonego zapasu aktu (krok 440, slajd 11-bis, slajd 14 „samo ⚓") | **+35 s z zapasu, nie z budżetu** |
| AKT 4 (7:30) | zaczepienie #3: **+15 s** | zapas aktu: wtręt „pomiar, który zaskoczył" (~40 s) jest i tak oznaczony jako pierwszy do cięcia | **0** |
| AKT 5 (7:00) | slajd 25: **+5 s** | slajd 21: **~2:35 → 1:15 = −80 s** | **−75 s** (sam RDZEŃ — wariant domyślny) · **−25 s** (z blokami A+B, tylko przy wejściu ≤37:00) |
| *poza 45:00* | slajd 26, drugi PDF: +10 s | — | *(Q&A, poza budżetem)* |

**Zasada cięcia przy spóźnieniu — kolejność jest sztywna:** #2 (slajd 15) → #1 (slajd 12) →
bloki A/B slajdu 21 → #3 (slajd 18). RDZEŃ 21, zdanie na 25 i pauzy slajdów 23–24 **nie podlegają
negocjacji**. Jeśli AKT 3 wychodzi ponad 12:00 na checkpoincie 28:30, tnij zaczepienia **zanim**
zaczniesz skracać cokolwiek w AKT 4 — one są dodatkiem, tamto jest talkiem.

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

**AKT 5 rozpisany uczciwie** *(panel policzył v4 na 8:30–8:55 przy deklarowanych 7:00 — stąd skrócenie 21)*:

| Slajd | Realnie ze słów i pauz |
|---|---|
| 21 Czwarty stan — RDZEŃ | 1:05–1:15 *(v4: 2:35–2:50)* |
| 22 Wróćmy do listy | 1:45 |
| 23 ECHO | 1:00–1:10 |
| 24 „3 miesiące" + reveal | 2:15–2:25 |
| 25 Klamra | 0:50 |
| 26 Dziękuję | 0:10 |
| **Razem** | **7:05–7:35** |

**Uwaga na arytmetykę — to jest ważniejsze niż wygląda:** nawet **sam RDZEŃ** daje 7:05–7:35 przy
deklarowanych 7:00, więc **~0:35 zapasu trzeba przynieść z wcześniejszych aktów**. Jest to
osiągalne (AKT 3 i 4 mają razem ~3 min oznaczonych cięć), ale nie dzieje się samo. Dlatego bramka
na bloki opcjonalne slajdu 21 jest ustawiona na **wejście przed 38:00**, a nie „przed 38:15":

| Wchodzisz na slajd 21 | Grasz | Koniec ok. |
|---|---|---|
| ≤ **37:00** | RDZEŃ + **A** + **B** (+50 s) | ~45:00 |
| ≤ **37:30** | RDZEŃ + **A** (+30 s) | ~45:10 |
| ≤ **38:00** | **sam RDZEŃ** | ~45:20 |
| > 38:00 | **sam RDZEŃ** i przyspiesz slajd 22 (most do checklisty — 15 s, oznaczony „tnij drugi") | ~45:20 |

**Domyślnie grasz sam RDZEŃ.** Bloki A i B to premia za dobrze zagrane pierwsze 37 minut, nie
element scenariusza. To nie jest wersja gorsza — to wersja właściwa.
**Czego nie wolno skracać, gdy goni czas:** ciszy po „jak długo można tego nie zauważyć?" (23)
i pauzy po „Trzy miesiące" (24). To jedyne dwa miejsca w talku, gdzie cisza jest treścią — a przy
przekroczonym budżecie kompresują się właśnie one, nie wyuczone bloki tekstu.

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
### Slajd 12 — Retry ≠ 4xx · ~2 min (+15 s zaczepienie AI)
- 🗣 „Skoro jesteśmy przy retry — wygląda niewinnie. Dorzucasz adnotację, masz odporność »za darmo«. *(pauza)* Nie jest za darmo.
  Zasada jest jedna: **ponawiaj tylko to, co w ogóle mogło się udać.** Pięćset-iks, timeout — przejściowe: serwer się zakrztusił, sieć mrugnęła; druga próba może trafić na zdrowy węzeł. Czterysta-iks — deterministyczne i **twoje**: złe pytanie. Zapytasz drugi raz tak samo — dostaniesz to samo. Potroiłeś porażkę, która była pewna za pierwszym razem.
  I tu wraca cebula: każda z tych bezsensownych prób ląduje w bezpieczniku jako nieudane wywołanie. **Karmisz bezpiecznik własnymi bugami** — zamiast pilnować, czy zależność żyje, dławi się twoimi czterysta-czwórkami.
  Lekarstwo: powiedz breakerowi i retry, **które wyjątki ignorować** — wąsko i świadomie. Bo kontrapułapka: zignorujesz całe RuntimeException »żeby było spokojnie« — i **oślepiasz bezpiecznik**. Przestaje widzieć realne porażki i nigdy się nie otworzy."
- 🗣 🤖 **ZACZEPIENIE AI #1** *(~15 s)*
  📍 **Miejsce: WEWNĄTRZ tekstu głównego, zaraz po zdaniu „Nie jest za darmo." — przed „Zasada jest
  jedna".** To wtrącenie na jednym oddechu, które **oddaje pałeczkę z powrotem**; nie nowy akapit:
  „*(pauza)* A jeśli po drugiej stronie stoi model językowy — »nie za darmo« robi się dosłowne:
  każde ponowienie ma cenę **na fakturze**. *(pauza, wróć do tempa)* Ale zostańmy przy naszym
  plannerze. — Zasada jest jedna: ponawiaj tylko to, co w ogóle mogło się udać…"
- 🎭 **Po co to tutaj:** żeby słowo „model" padło pierwszy raz w minucie ~24, w miejscu, gdzie jest
  merytorycznie własne — a nie jako doklejka w minucie 43. Slajd 21 startuje wtedy z rozbiegu.
  **Wtrącenie musi trwać krócej niż zdanie, które je otacza** — inaczej rozbija argument slajdu 12,
  a ten argument jest fundamentem pod 12-bis.
- 🎭 **Jeśli ktoś od razu podniesie rękę z pytaniem o AI** *(prawdopodobne)*: „Trzymam to pytanie —
  mam na nie odpowiedź i mam na nie slajd. Ale najpierw muszę wam oddać coś, co obiecałem
  dwadzieścia minut temu." *(płacisz obietnicą, nie odmową; wracasz do wątku dokładnie na 21)*
- 🗣 **TRZECIA KATEGORIA** *(~30 s — mocne, warto zostawić; naturalne przedłużenie tego slajdu)*:
  „I jest jeszcze trzeci rodzaj wyjątku, o którym się zapomina — a jest najpodstępniejszy. *(pauza)* Nie odmowa i nie awaria. **Wasz własny bug.**
  Bo zadajcie sobie pytanie, po co ten bezpiecznik w ogóle istnieje. Żeby odpowiadać na **jedno** pytanie: czy tamten serwis jest zdrowy. *(pauza)* A skoro tak — to co robi w jego oknie `NullPointerException` z **waszego** mapowania? On nie mówi o tamtym serwisie nic. Do tamtego serwisu w ogóle nie dojechaliście.
  Ja miałem czarną listę: »ignoruj odmowy, licz całą resztę«. Czyli mój NPE liczył się jako awaria plannera. *(zwolnij)* Pięć takich wywołań i **bezpiecznik odcina serwis, który jest w idealnym stanie**. Klient dostaje pięćset trzy »wróć za chwilę« — na błąd, który jest deterministyczny, więc powrót nic nie da. Odpala się alert. A dyżurny idzie sprawdzać **nie ten serwis**.
  Naprawa to zamiana czarnej listy na białą. ⚓ **Domyślną odpowiedzią na pytanie »czy to awaria zależności« ma być NIE, a nie TAK.** *(klik — slajd 12-bis)* I to jest cała naprawa, w trzech linijkach."
- 🗣 *(zwolnij — drugie wyznanie)* „I tak — to też znalazłem u siebie. Mój kod przez rok zamieniał **trzy różne awarie w jeden wyjątek** — retry grzecznie ponawiał czterysetki, a bezpiecznik liczył odmowy domenowe jako awarie infrastruktury. *(pauza)* Nie z lenistwa — po prostu nigdy nie zadałem sobie pytania, czym różni się odmowa od awarii. ⚓ **Bo to nie jest talk o tym, że ja mam idealnie. To jest talk o tym, żeby widzieć.**"
- ✂️ **Cięcie zaczepienia AI #1:** tnij w całości bez śladu — slajd 21 nie odwołuje się do niego.

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

### Slajd 15 — Metryki za darmo · ~1 min (+20 s zaczepienie AI)
- 🗣 „Resilience4j: zero kodu liczników. Trzy zależności i macie stan circuita, failure rate, czasy, retry. Wszystko z tamtego dashboardu — z trzech linijek w pom."
- 🗣 🤖 **ZACZEPIENIE AI #2** *(~20 s — najlepszy stosunek treści do sekund w całym talku)*:
  „I jedna rzecz, żebyście nie pomyśleli, że to wiedza o jednej bibliotece. *(pauza)* Ten sam
  mechanizm — **Observation** — instrumentuje dziś wywołania modeli językowych. Dokładacie
  Actuatora, wołacie model, i w rejestrze **same z siebie** pojawiają się: czas operacji, liczba
  tokenów wejściowych i wyjściowych, i to, **który model naprawdę odpowiedział**. Zero kodu
  liczników — dokładnie tak jak tutaj."
- 🎭 **Dlaczego akurat „który model naprawdę odpowiedział":** to jedyny darmowy sygnał, który łapie
  cichą podmianę modelu po stronie dostawcy. Nie tłumacz tego na scenie — masz to w Q&A (pyt. 13g).
  Zdanie ma zostawić w sali wrażenie *„to samo narzędzie, nowy świat"*, nie wykład o semconv.
- 🚫 **Nie mów tu:** nazw dostawców, `gen_ai.*`, „OpenTelemetry", „Spring AI 1.1". Nazwy własne na
  tym slajdzie kosztują uwagę, a niczego nie kupują — sala i tak nie zapisze.
- ✂️ **Cięcie:** tnij razem ze slajdem 14, gdy jesteś spóźniony (razem ~40 s zapasu).
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
- 🗣 🤖 **ZACZEPIENIE AI #3** *(~15 s — wstaw po kotwicy, PRZED „Sprawdźmy"; to setup slajdu 21)*:
  „I zapamiętajcie ten trzeci box. *(wskaż licznik `outcome`)* Bo na całym tym dashboardzie —
  dziesięć paneli, wszystkie za darmo — to jest **jedyna** rzecz, która mówi o **wyniku**.
  Cała reszta mówi o transporcie. *(pauza)* Wrócę do tego pod koniec."
- 🎭 **Po co:** slajd 21 może wtedy powiedzieć „pamiętacie ten licznik" zamiast tłumaczyć go od zera
  — oszczędza ~20 s tam, gdzie sekundy są najdroższe, i zamienia AI-teaser w **spłatę zapowiedzi**
  zamiast dygresji. Kosztuje 15 s w minucie 36, gdzie zapas jest największy.
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
- 🎬 **NA SLAJDY** → przewiń **dwa** slajdy rezerwowe 19–20 (Demo, Alert — Plan B) → slajd 21
  („Czwarty stan"). W decku to pozycje **21, 22 → 23** — patrz ostrzeżenie w sekcji 4;
  **nie wpisuj numeru**, klikaj strzałką.
- ⏱ **CHECKPOINT: 38:00.**

# AKT 5 — SYNTEZA (38:00 → 45:00)

### Slajd 21 — „Czwarty stan" (AI) · RDZEŃ ~65 s (+ bloki opcjonalne)
> ✅ **Deck v4 pokazuje tu już „Czwarty stan"** (dwie kolumny + czerwony wiersz „odpowiada
> źle → ???"). Sekcja 8.8 to wyłącznie rezerwa: rozbudowana odpowiedź w Q&A (rezerwowy slajd
> SB4 jest w decku za Q&A) albo awaryjny powrót do starego decka v3.
>
> ⚠️ **To jest tekst przepisany od zera (v5), nie „v4 bez akapitu".** Wersja v4 miała ~2:35–2:50,
> dziesięć nowych pojęć na 43. minucie i trzy zdania nie do obrony merytorycznie. **Przećwicz
> RDZEŃ ze stoperem jako osobny tekst** — wersja skrócona improwizowana w locie z długiej zawsze
> wychodzi dłuższa. Poprawki merytoryczne i ich uzasadnienie: sekcja **9.1**, pełne tło: sekcja 9.

**RDZEŃ — grasz ZAWSZE, w każdym scenariuszu czasowym (~65 s):**

- 🗣 „Spojrzenie w przód. Przez czterdzieści minut mówiliśmy o jednym wywołaniu HTTP między
  dwoma serwisami. Podmieńcie teraz w głowie jedną rzecz: pod tym URL-em, do którego strzela
  starport, nie siedzi planner. Siedzi model językowy. *(pauza)* Co się zmieniło?
  *(krótka cisza)* **Wszystkie wzorce zostają. Wszystkie liczby trzeba przeliczyć.**
  To dalej jest jedno zdalne HTTP: może być wolne, może odpowiedzieć błędem, może zamilknąć.
  Timeout, retry, bezpiecznik, fallback — obowiązują **co do wzorca**.

  Ale jest coś, co przy HTTP było **wypadkiem**. *(pauza)* Tu jest **trybem pracy**.
  *(klik / wskaż czerwony wiersz)* **Czwarty stan.**
  Model odpowiedział szybko. Dwieście. Zero błędów. Dashboard wygląda dokładnie jak nasz
  wtorek — zielono, płasko, szybko. Tylko w odpowiedzi jest bzdura. *(pauza — przyjmij reakcję,
  nie dobijaj żartem)*

  Pamiętacie krok, w którym system zauważył i nie zareagował? To było groźne. Tu jest gorzej:
  **system w ogóle nie zauważa.** Nie ma progu w YAML-u, który łapie kłamstwo. *(pauza)*

  ⚓ **Tam »działa« nie znaczyło »działa i ja o tym wiem«. Tu »odpowiada« nie znaczy
  »odpowiada prawdę«.**
  *(pauza — patrz na czerwony znak zapytania, nie na salę)* A co zrobić z tym czerwonym znakiem
  zapytania… *(pół uśmiechu)* to na inny raz." **→ natychmiast slajd 22.**

**BLOK A — „rachunek" *(~30 s; grasz tylko wchodząc na slajd 21 przed 37:30 — patrz bramka w sekcji 1)*.**
📍 **Miejsce wstawienia: bezpośrednio po zdaniu „obowiązują **co do wzorca**", przed „Ale jest coś,
co przy HTTP było wypadkiem".** Blok kończy się na „losowaniem" i RDZEŃ podejmuje od „Ale jest coś":
- 🗣 „Jedna rzecz zmienia arytmetykę. **Retry to nie darmowa druga próba — to drugi rachunek.**
  A żeby to nie było twierdzenie: sprawdziłem defaulty biblioteki, która woła dziś modele
  ze Springa. **Dziesięć ponowień.** Dziesięć płatnych prób bez jednej świadomej decyzji.
  *(pauza)* I druga próba **zwraca inną odpowiedź** niż pierwsza. Retry przestaje być drugą
  próbą tego samego. Zaczyna być losowaniem."
- 🎭 **Po co ten blok:** to **jedyny twardy fakt** na całym slajdzie — w talku, którego tezą jest
  „zmierz, nie zakładaj". Bez niego 21 jest jedynym slajdem bez dowodu na ekranie, cztery minuty
  po tym, jak sam mówiłeś „gdybym oparł demo na założeniu, nie zadziałałoby". *(źródło liczby:
  cheatsheet 13e; **sprawdź defaulty w aktualnej wersji przed talkiem** — sekcja 9.7)*

**BLOK B — „licznik jakości" *(~20 s; tylko przy wejściu przed 37:00 I zagranym ZACZEPIENIU AI #3 na slajdzie 18)*.**
📍 **Miejsce wstawienia: inne niż A — po zdaniu „system w ogóle nie zauważa… nie ma progu
w YAML-u", tuż PRZED kotwicą.** Kotwica zostaje ostatnim zdaniem slajdu w każdym wariancie:
- 🗣 „I wraca ten trzeci box ze slajdu osiemnastego — jedyny licznik, który napisałem ręcznie.
  Przy modelu ten ręczny licznik przestaje być dodatkiem. *(pauza)* Liczony na próbce i poza
  ścieżką requestu — bo ocenianie odpowiedzi też kosztuje."
- 🎭 Nie mów „to jedyne, co widzi czwarty stan" — nieprawda i ktoś z sali to wie. Deterministycznie
  łapie się sporo: walidację schematu, odmowę modelu, ucięcie odpowiedzi, a przede wszystkim
  **walidację wobec reguł domeny** (u Ciebie robi to `StarportCodeAllowlist`). Szczegóły: 9.2.

**Reżyseria i pułapki:**
- 🎭 Ton: spokojna ekscytacja, nie hype. ZERO nazw dostawców modeli, zero „AI zmieni wszystko".
  Ostatnie zdanie zawieszasz i od razu → slajd 22 — **nie zbieraj reakcji sali**, teaser ma
  wisieć, nie być dyskutowany (dyskusja → Q&A).
- 🎭 **Jedna kotwica na tym slajdzie.** AKT 5 ma ⚓ na 22, 23, 24 i 25 — piąta w siedem minut
  przestaje cokolwiek znaczyć. Kotwica działa przez rzadkość, więc na 21 jest **jedna** i jest
  **ostatnim zdaniem**. Zdanie „to warsztat pod najdroższą zależność" zeszło ze sceny do Q&A
  (pyt. 13f) — było drugą kotwicą i konkurowało z tą.
- 🎭 **Adrenalina:** 21 to ostatnie miejsce, gdzie mówisz coś nowego — naturalny odruch to
  przyspieszyć i zjeść pauzy. Kartka przy laptopie: **tekst kotwicy + słowo „KLIK"**. Nic więcej.
- 🚫 **Zakazane:** wchodzenie w prompty/evale/konkretne modele, obiecywanie demo z LLM,
  słowo „halucynacja" więcej niż raz. To trailer, nie drugi talk.
- 🚫 **Zeszło ze sceny świadomie — nie improwizuj tego z powrotem** *(uzasadnienia: 9.1)*:
  · **429 „ten sam kontrakt co 503"** — merytorycznie fałszywe i to **autogol na własnej tezie**
    (503 = „on jest chory", 429 = „on jest zdrowy, to ja przesadziłem"). Poprawna wersja jako
    **pułapka** jest w cheatsheecie, pyt. 13h. Dwie liczby wypowiadane słownie to i tak najgorszy
    materiał sceniczny.
  · **„OpenTelemetry GenAI semantic conventions"** — sala właśnie przeczytała to w podtytule
    slajdu; mówienie tego na głos to 12 s za powtórzenie tekstu z ekranu. Stan faktyczny (nic nie
    jest Stable) → pyt. 13g i sekcja 9.6.
  · **„fallback = cache / wczorajsza odpowiedź"** — bez metryki to **sprzeczne ze slajdem 16**,
    gdzie uczysz, że dobry fallback mówi prawdę. Cichy downgrade jakości *produkuje* czwarty stan.
    Jeśli musisz to powiedzieć, tylko z klauzulą: „…i musi zostawić ślad, **kto odpowiedział**".
  · **„obowiązuje jeden do jednego"** — nieprawda przy streamingu (200 jest zacommitowane, zanim
    cokolwiek wiadomo, więc mapowanie wyjątku po statusie nie odpali się nigdy). Stąd „co do
    **wzorca**" w RDZENIU — to jednocześnie uczciwość i **mocniejszy** teaser.
- 🎭 **Budżet czasu — twarda bramka** *(pełna tabela: sekcja 1)*: ≤37:00 → RDZEŃ+A+B ·
  ≤37:30 → RDZEŃ+A · **≤38:00 i później → sam RDZEŃ, bez negocjacji.** Domyślną odpowiedzią
  na pytanie „czy zagrać blok?" jest **NIE** — sprawdź zegar, nie samopoczucie.
- 🎭 **Riposty trzymaj na Q&A, nie otwieraj ich na scenie:** „zwykły serwis też zwraca bzdury"
  → pyt. 13c · „jaki timeout na model?" → pyt. 13d · „Spring AI ma retry" → pyt. 13e ·
  „a semconv/OTel?" → 13g · „429 to przecież to samo co 503" → 13h.
- **Boot 4 w Q&A, nie na scenie:** jeśli pytanie padnie, jedno zdanie — „Boot 4 wciąga retry
  i limit współbieżności do rdzenia, ale bezpiecznika tam nie ma, a `maxAttempts` znaczy co
  innego; szczegóły mam spisane" → cheatsheet sekcja D, pyt. 11–13; pełny stary tekst: 8.8.
- 🎓 **Test kartki:** przeczytaj RDZEŃ na głos ze stoperem dwa razy pod rząd, bez patrzenia w tekst.
  Jeśli za drugim razem trwa krócej o >15 s — jest **za długi**, nie „za wolno zagrany".

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

### Slajd 25 — Klamra · ~50 s · ⭐
- 🗣 *(wolno, pauzy)*: „System się BĘDZIE psuł. Sieć padnie, zależność zwolni, ktoś wdroży zły adres — jak ja. *(pauza)* ⚓ Nie chodzi o to, żeby się nie psuło. Chodzi o to, żeby psucie było WIDAĆ."
- 🗣 🤖 **ZACZEPIENIE AI #4** *(~5 s — domknięcie łuku, koszt netto zero)*:
  *(pauza)* „…a niektóre awarie nie mają nawet koloru. **Te trzeba umieć zmierzyć samemu.**"
- 🎭 **Po co:** bez tego zdania slajd 21 jest dygresją między demo a revealem. Z nim staje się
  **setupem klamry** — i cały AKT 5 dostaje jeden łuk zamiast dwóch. Slajd 21 nie może odwołać się
  do historii (jest wtedy jeszcze tajna), ale klamra **może** odwołać się do czwartego stanu.
- 🚫 Nie mów tu słowa „model", „AI" ani „halucynacja". Zdanie ma działać na tych, którzy złapali
  slajd 21, i **nie kolidować** z odbiorem u tych, którzy przyszli po resilience.

### Slajd 26 — Dziękuję / Q&A
- 🗣 „Dziękuję. *(pauza, uśmiech)* Pytania?"
- 🗣 *(gdy pytania przygasają — materiał do zabrania)*: „I ostatnia rzecz. Wszystko, co dziś pokazałem, opisałem szerzej — z kodem przed i po, arytmetyką okna bezpiecznika i pułapkami migracji na Boot 4. To jest w repo jako **`WYKLAD-resilience-mid-java.pdf`**, dwadzieścia sześć stron. Jeśli macie w zespole kogoś, kto dopiero wchodzi w te wzorce — to jest dla niego."
- 🗣 🤖 **ZACZEPIENIE AI #5 — drugi materiał** *(~10 s; mów TYLKO, jeśli slajd 21 wywołał reakcję
  albo padło pytanie o AI)*: „A jeśli zaczepił was ten czerwony znak zapytania — obok leży drugi
  plik: **observability dla wywołań modeli**, od pierwszego dashboardu po koszt, kardynalność
  i mierzenie jakości. Tam jest ta część, na którą dziś nie było minut."
- 🎭 **To jest odpowiedź na zarzut „21 to jedyny slajd bez dowodu"** — teaser przestaje być wekslem
  bez pokrycia, bo za czerwonym `???` stoi realny artefakt do zabrania *(37 stron,
  `Observability_w_AI_od_juniora_do_seniora.pdf`; mapa scena ↔ materiał: sekcja 9.9)*.
- 🚫 **Jedna oferta na talk.** Skoro 21 zawiesza „to na inny raz", a tutaj wskazujesz PDF-y —
  **nie dokładaj trzeciej zapowiedzi serii.** Trzy oferty w ostatnich siedmiu minutach, 90 s po
  kotwicy „ja wam niczego nie sprzedałem", sala odbiera jako sprzedaż, nawet jeśli tego nie nazwie.
  Dlatego linia „to będzie osobna prezentacja" jest **usunięta na stałe** (była tu do v4).
- 🎬 **`QA-cheatsheet-KOLB-v3.md` na 2. ekranie** (33 pytania dopasowane do tej wersji talku; sekcja D2 = AI). Skróty do najczęstszych: cebula/aspectOrder → pyt. 6–7 (kotwica: slajd 11 + dwa testy w repo) · „czemu 503 a nie 409" → pyt. 9 · „czemu nie @TimeLimiter" → pyt. 3 · „a RateLimiter/Bulkhead?" → pyt. 17 · Spring Boot 4 → pyt. 11–13 (zeszło ze sceny w v4 — pełny tekst: sekcja 8.8, rezerwowy slajd za Q&A) · pytania o AI/LLM po slajdzie 21 → pyt. 13b–13e (halucynacja / „serwis też zwraca bzdury" / timeout na model / retry w Spring AI) **+ 13f–13l** (po co mi to / semconv / 429-pułapka / koszt / kardynalność / „czy AI zrobi observability za mnie" / prywatność promptów) + tabela **„Szybkie liczby — sekcja AI"** · „a Wam się to zdarzyło?" przed slajdem 24 → pyt. 18.
- ⏱ **KONIEC: 45:00.** Po talku: Ctrl+C w Terminalu A; opcjonalnie `docker compose ... down` (oba `-f`); `git checkout master`.

---

## 4. Mapa „treść → slajd pptx" (w v4: 29 pozycji — doszedł rezerwowy SB4 za Q&A)

> ⚠️ **NUMER W DECKU ≠ NUMER W RUNBOOKU.** Deck ma **29 pozycji**, talk ma **26 numerów** —
> bo doszły 11-bis i 12-bis, slajd „Demo" występuje **dwa razy** (przed AKT 1 i przed AKT 4,
> oba z numerem 19 w rogu), a na końcu wisi rezerwowy SB4. **Nie nawiguj po numerze**
> („21+Enter" w trybie prezentacji wyląduje na slajdzie *Demo*, nie na *Czwartym stanie*).
> Jedź strzałkami albo z widoku miniatur.
>
> | Runbook | Deck | | Runbook | Deck | | Runbook | Deck |
> |---|---|---|---|---|---|---|---|
> | 1–4 | 1–4 | | 12 | 13 | | 18 | 20 |
> | 5 (Demo AKT 1) | **5** | | 12-bis | 14 | | 19 Demo (rezerwa) | **21** |
> | 6–11 | 6–11 | | 13 | 15 | | 20 Alert (rezerwa) | 22 |
> | 11-bis | **12** | | 14 | 16 | | **21 Czwarty stan** | **23** |
> | | | | 15 | 17 | | 22–26 | 24–28 |
> | | | | 16 | 18 | | SB4 rezerwa | 29 |
> | | | | 17 | 19 | | | |
>
> **Praktycznie:** po demo-naprawie masz przewinąć **deck 21 i 22** (dwa rezerwowe), żeby dojść
> do **decku 23** = slajd 21 „Czwarty stan". To są dwa kliknięcia w ciszy — przećwicz je, bo
> wypadają dokładnie w przejściu z Grafany na slajdy.

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
| 12 | Retry ≠ 4xx | bez zmian *(dochodzi w tekście ZACZEPIENIE AI #1 — bez zmiany slajdu)* |
| 12a | Biała lista: trzy linijki, trzy decyzje | **DODANY** (YAML `record`/`ignore`/`retry-exceptions`) |
| 13 | Zwrot akcji | bez zmian |
| 14 | Micrometer | bez zmian (mów wersję „samo ⚓") |
| 15 | Metryki za darmo | bez zmian *(dochodzi ZACZEPIENIE AI #2 — bez zmiany slajdu)* |
| 16 | Ten sam request: bez odporności i z odpornością | **DO PRZERÓBKI: dwie kolumny — goły kod z wtorku obok kodu z odpornością** |
| 17 | Dashboard 2 publiczności | bez zmian |
| 18 | Widoczność: jedna decyzja | **PRZEROBIONY z RL+BH — Observation / 3 zależności / fallback+503** |
| 19 | „Demo" (rezerwowy) | Plan B |
| 20 | Alert (rezerwowy) | Plan B |
| 21 | „Czwarty stan" (AI) | ✅ w v4.pptx, **poprawiony 2026-08-11** wg 8.6 (dwa timeouty · fallback „zostawia ślad") |
| 22 | Wróćmy do listy | drobna korekta (bez linii o floodzie) |
| 23 | ECHO pytania | bez zmian |
| 24 | 3 miesiące (+reveal mówiony) | bez zmian *(rozważ dopisek „bo sprawdzały mój kod, nie to, z czym rozmawia" — 9.10)* |
| 25 | Klamra | bez zmian *(dochodzi ZACZEPIENIE AI #4 — jedno zdanie, bez zmiany slajdu)* |
| 26 | Dziękuję / Q&A | **drugi PDF do zabrania** (AI); usunięta opcjonalna zapowiedź serii |

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
9. **Nie improwizuj długiej wersji 21 z rozbiegu.** RDZEŃ jest osobnym, wyuczonym tekstem; bloki
   A i B **doklejasz**, nie „rozwijasz". Wersja improwizowana w drugą stronę (skracanie długiej
   w locie) zawsze wychodzi dłuższa — a płacą za nią pauzy slajdów 23 i 24, nie slajd 21.
10. **Zaczepienia AI (#1 na 12, #2 na 15, #3 na 18) są jednozdaniowe — nie wchodź w wątek.**
   Największe ryzyko: pytanie o AI w minucie 24, gdzie masz mniej miejsca na unik niż w 40.
   Gotowa fraza jest przy slajdzie 12; **przećwicz slajdy 12 i 15 od nowa z tymi zdaniami**,
   bo doklejenie zdania do wyuczonego bloku rozbija rytm bardziej, niż się wydaje.
11. **Trzy zdania, które w v4 były na scenie, a są merytorycznie nie do obrony**, nie mają prawa
   wrócić w improwizacji: „429 to ten sam kontrakt co 503" · „te same sekundy timeoutu" ·
   „czwarty stan to coś, czego HTTP nie ma" *(Twoja własna historia ze slajdu 24 JEST czwartym
   stanem bez żadnego AI — ktokolwiek to zestawi, ma gotową ripostę **w momencie revealu**)*.
   Poprawione wersje: sekcja 9.1.
12. **Nie obiecuj drugiej prezentacji.** Jedna oferta na talk — slajd 26 wskazuje materiały i to
   wystarczy. „Może następna" wypowiedziane 90 s po kotwicy „ja wam niczego nie sprzedałem"
   pracuje przeciwko tej kotwicy.

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
| **Zakończenie AI: czwarty stan** | **slajd 21 (v5: RDZEŃ)** | *(poza wykładem resilience — patrz tabela AI poniżej)* |
| Checklista 10 pytań do własnego serwisu | **wzmianka slajd 22 (dodane)** | rozdz. 16 |
| Ściągawka progów i PromQL | sekcja 5 tego runbooka | rozdz. 17 |

### 7.1 Mapa: warstwa AI ↔ drugi materiał (`Observability_w_AI_od_juniora_do_seniora.pdf`, 37 stron)

Ta sama zasada co wyżej: **wiedz, co możesz spokojnie odesłać do materiału.** Różnica jest taka,
że tu odsyłasz **prawie wszystko** — na scenie zostaje ~2:45, w PDF-ie jest 37 stron. Formuła
odpowiedzi w Q&A: *„krótka wersja: … — całość opisałem w drugim pliku, rozdział N"*.

| Temat | Na scenie | W materiale AI | Rozwinięcie tutaj |
|---|---|---|---|
| Czym AI zmienia obserwowalność (6 różnic) | slajd 21 (jedna: czwarty stan) | rozdz. 3 | 9.1 |
| Sukces techniczny ≠ semantyczny | **teza slajdu 21** | rozdz. 3.2 · antywzorzec 4 | 9.2 |
| Telemetria Spring AI za darmo | **zaczepienie #2 (slajd 15)** | rozdz. 5 i 10 | 9.3 |
| Kropki → podkreślenia, `_total`, `_seconds_*` | tylko Q&A | rozdz. 5.1 | 9.3 |
| Observation API, low/high cardinality | slajdy 14, 18 (dla HTTP) | rozdz. 7 | 9.3 |
| Koszt jako metryka pierwszej kategorii | **blok A slajdu 21** (jedno zdanie) | rozdz. 15 | 9.4 |
| TTFT / TPOT / urwany strumień | tylko Q&A (13d) | rozdz. 13 · pułapka 4 | 9.5 |
| Kardynalność jako zabójca Prometheusa | tylko Q&A (16, 13j) | rozdz. 16 | 9.4 |
| Semconv GenAI — stan faktyczny | **zeszło ze sceny** (podtytuł slajdu) | rozdz. 9 · pułapka 3 | 9.6 |
| Mierzenie jakości (warstwy ewaluacji) | **blok B slajdu 21** | rozdz. 18 | 9.2 |
| Dryf i cicha podmiana modelu | tylko Q&A (13g) | rozdz. 19 | 9.6 |
| Resilience4j przy modelu, tryby degradacji | slajd 21 („wzorce zostają") | **rozdz. 20** | 9.7 |
| Kolejność aspektów → znaczenie metryk | **slajdy 11 i 11-bis** | rozdz. 20, pułapka 5 | — |
| RAG, agenci, tool calling, MCP | **nigdzie — nie wchodź** | rozdz. 12, 21 | 9.9 |
| Prywatność promptów, audyt, AI Act | **nigdzie — nie wchodź** | rozdz. 11, 22 | 9.8 (13k) |

> **Zbieżność, która jest warta zauważenia:** rozdz. 20 tego materiału (pułapka nr 5) opisuje
> dokładnie cebulę ze slajdu 11 — kolejność aspektów Retry/CircuitBreaker i to, jak zmienia
> **znaczenie metryk**. To niezależne potwierdzenie, że talk o resilience jest właściwym
> fundamentem pod talk o AI, a nie osobnym tematem. Jeśli ktoś w Q&A pyta „po co mi to przy
> modelach" — to jest odpowiedź z materiałem w ręku *(pyt. 13f)*.

**Trzy wtręty dodane z wykładu — i co zrobić, gdy goni czas:**
- **Drabina progów (slajd 10, ~30 s)** — *nie tnij*. Bez niej sala widzi, że bezpiecznik zadziałał, ale nie rozumie, dlaczego było to możliwe przy zerze błędów. To mechanika, na której stoi cały AKT 1.
- **Pomiar, który zaskoczył (slajd 18, ~40 s)** — tnij pierwszy, jeśli jesteś spóźniony. Świetnie jednak działa jako odpowiedź w Q&A na „skąd wiecie, że to działa" (cheatsheet, pyt. 19).
- **Most do checklisty (slajd 22, ~15 s)** — tnij drugi; slajd 26 i tak wskazuje materiał.

---

## 8. Zakończenie AI „Czwarty stan" (slajd 21) — uzasadnienie i wdrożenie

> **STATUS: WDROŻONE (2026-08-06), ZREWIDOWANE PO PANELU (v5, 2026-08-11).** Scenariusz mówiony
> jest w AKT 5 → „Slajd 21 — »Czwarty stan« (AI)" i TAM jest wersja kanoniczna. Deck
> `..._KOLB_v4.pptx` ma slajd 21 wg 8.6 **w wersji sprzed poprawki** (patrz 8.6), stary SB4 jako
> rezerwę za Q&A; cheatsheet ma pyt. 13b–13e (13f–13k do dopisania z 9.8). Stan prac: 8.7.
> Sekcja 8.8 = tekst SB4 na wypadek grania ze starego decka v3 albo rozbudowanego Q&A.
> **Merytoryczne zaplecze całej warstwy AI: sekcja 9.**

### 8.1 Dlaczego wymiana — trzy argumenty

1. **SB4 ma datę ważności.** Za rok „co nowego w Boot 4" to slajd historyczny; ten talk ma żyć
   dłużej niż jedna edycja. Cała merytoryka SB4 **nie znika** — przenosi się w całości do Q&A
   (cheatsheet, sekcja D, pyt. 11–13, już istnieje i jest kompletna z pułapką `maxAttempts`).
2. **AI otwiera serię, SB4 ją zamyka.** Zakończenie-teaser to gotowy tytuł następnego talku;
   sala wychodzi z pytaniem, nie z release-notes.
   > ⚠️ **Korekta v5:** pierwotny roboczy tytuł brzmiał *„…Co się stanie, gdy model **zamilknie**?"*
   > — i jest zły, bo milczenie ten talk **rozwiązuje** (breaker + fallback). Teaserowałby problem
   > ogłoszony za zamknięty. Aktualna wersja: **„Twój serwis woła model AI. Co się stanie, gdy
   > model **skłamie**?"** Pełne uzasadnienie i pozycjonowanie serii: **sekcja 9.10**.
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

Pełny tekst mówiony, reżyserka (🎭/🚫) i wersje czasowe żyją w **jednym miejscu**:
AKT 5 → „Slajd 21 — »Czwarty stan« (AI)". Tu celowo nie ma kopii — dwie wersje tego samego
tekstu rozjadą się przy pierwszej korekcie (ta sama zasada co „zrzuty = kod co do linii").
Budżet **v5**: RDZEŃ ~65 s (+20–30 s bloki opcjonalne) zamiast ~2:15 z v4 — **checkpointy
38:00 → 45:00 bez zmian, ale AKT 5 przestaje je przekraczać** (rozpiska: sekcja 1).
Odzyskane ~75 s idzie w pauzy slajdów 23 i 24, nie w kolejną treść.

### 8.4 Kotwice w tym, co sala już widziała — audyt uczciwy (v5)

> **Korekta wobec v4.** Poprzednia wersja tej tabeli deklarowała „zero nowych pojęć". To była
> nieprawda: audyt wiersz po wierszu dał **~10 nowych bytów w 135 s** — czyli jedno nowe pojęcie
> co 13,5 s, przy ~45 s/pojęcie w najgęstszym akcie talku (AKT 3). **3,4× gęstość AKT 3, na
> 43. minucie, bez powtórki i bez pytania sprawdzającego.** Przewidywany transfer: sala zapamięta
> **jedno** — „czwarty stan" — a reszta jest szumem rozcieńczającym to jedno. Stąd cała v5.

**Co zostało na scenie (RDZEŃ) — i czy naprawdę jest zakotwiczone:**

| Fraza w RDZENIU | Kotwica | Nowe pojęcie? |
|---|---|---|
| „jedno zdalne HTTP" | slajd 2–3, bohater talku | NIE |
| „zielono, płasko, szybko" | slajd 5, dosłowny cytat z wtorku | NIE — najlepszy wiersz tabeli |
| „system zauważył i nie zareagował" → „w ogóle nie zauważa" | krok 440 z demo-ślepego | NIE — najmocniejszy ruch slajdu |
| „nie ma progu w YAML-u" | slajd 12-bis (⚓ konfiguracja egzekwuje, decyduje kod) | NIE |
| **„czwarty stan"** | slajd 9 („trzeci stan: milczy") daje formę, nie treść | **TAK — i to jest cel slajdu.** Jedno nowe pojęcie, powtórzone, w tytule, na czerwono |
| „wypadek → tryb pracy" | — | TAK (tani: zamiana ram, nie nowy byt) |

**Bilans RDZENIA: 1 nowe pojęcie na 65 s** — zamiast 10 na 135 s. To jest cała różnica między
„sala wyjdzie z etykietą, której będzie używać" a „sala wyjdzie z wrażeniem, że było dużo".

**Co przeniesione do zaczepień (kotwiczy się wcześniej, po jednym bycie na raz):**

| Byt | Gdzie wprowadzony | Dlaczego tam |
|---|---|---|
| „model = drugi rachunek / token" | zaczepienie #1, slajd 12 | pieniądz nie pada w talku ani razu przez 40 min — musi wejść **w miejscu o koszcie retry**, nie w minucie 43 |
| „telemetria modelu za darmo" | zaczepienie #2, slajd 15 | slajd nazywa się „metryki za darmo"; to jego naturalne przedłużenie |
| „metryka wyniku vs transportu" | zaczepienie #3, slajd 18 | licznik `outcome` jest tam na ekranie — kotwica jest **widoczna**, nie twierdzona |

**Co zeszło ze sceny w całości** *(uzasadnienia: 9.1; treść: 9.4, 9.6, cheatsheet 13g–13h)*:
429/503 · semconv i słowo „OpenTelemetry" *(nie pada w talku ani razu przez 40 min)* ·
kaskada mniejszy-model/cache · „jedyne, co widzi czwarty stan" · druga kotwica ⚓.

### 8.5 Los treści SB4

- **Nic nie kasujemy.** Pełne wersje: cheatsheet sekcja D (pyt. 11–13) + wykład rozdz. 14–15.
- Jeśli pytanie o Boot 4 padnie w Q&A, odpowiedź sceniczna w jednym zdaniu:
  „Boot 4 wciąga retry i limit współbieżności do rdzenia — świetnie — ale bezpiecznika tam
  nie ma, a `maxAttempts` znaczy co innego; szczegóły chętnie po prezentacji, mam je spisane."
- Slajdy 19–20 (Plan B) — bez zmian; przy wymianie 21 nadal działają.

### 8.6 Szkic slajdu — ✅ WERSJA POPRAWIONA (v5), WDROŻONA W PPTX (2026-08-11)

**Stan: `Działanierównasiędziała_KOLB_v4.pptx` ma już poniższą wersję.** Backup sprzed poprawki:
`Działanierównasiędziała_KOLB_v4.BACKUP-2026-08-11.pptx`. Zweryfikowane otwarciem przez
PowerPoint (nie przez LibreOffice — on toleruje pliki, które PowerPoint odrzuca) i eksportem
slajdu do PNG: tekst mieści się w karcie, kolumna lewa nietknięta.

```
   HTTP do serwisu            HTTP do modelu
   ───────────────            ──────────────
   wolny        → timeout     wolny         → DWA timeouty
                                                 pierwszy token / cisza w środku
   błąd         → retry       błąd          → retry
                                                 $ za każdą próbę
   milczy       → breaker     milczy        → breaker + fallback
                                                 który zostawia ślad

                              odpowiada źle → ???          ← jedyny czerwony element slajdu
```

*(Na slajdzie adnotacje są osobnymi, szarymi wierszami pod wzorcem — nie nawiasami w linii.
Wzorzec jest zielony, adnotacja szara, `???` czerwone. Courier New 12,5 pt, ~45 znaków na wiersz —
przy dopisywaniu czegokolwiek trzymaj się tego limitu, bo karta nie ma autofitu.)*

**Co i dlaczego zmienione względem v4:**

| Było | Jest | Powód |
|---|---|---|
| `wolny → timeout (te same sekundy, drożsi klienci)` | `wolny → DWA timeouty (pierwszy token / cisza w środku)` | **Twardy błąd.** 800 ms na modelu = 100% timeoutów; generacja legalnie trwa dziesiątki sekund. To była **jedyna liczba na slajdzie i była fałszywa** — a slajd ktoś sfotografuje. Poprawna para: TTFT + idle *(9.5)* |
| `fallback (mniejszy model / cache)` | `fallback (który zostawia ślad)` | Kaskada modeli to nowy byt bez kotwicy, a cichy downgrade jakości **produkuje czwarty stan** — czyli slajd ilustrowałby dokładnie to, przed czym ostrzega. „Zostawia ślad" kotwiczy się w slajdzie 16 („fallback nie milczy") |

Tytuł slajdu: **„Czwarty stan"** · podtytuł zostaje: *OpenTelemetry GenAI semconv — to się właśnie
standaryzuje* **(sala to przeczyta; Ty tego nie mówisz — to byłoby 12 s za powtórzenie ekranu)**.
Wiersz „???" jest jedynym akcentem kolorystycznym — cały slajd gra na jego pustkę, a ostatnie
zdanie RDZENIA celuje **w ten znak zapytania**, nie w „gdy model zamilknie". *(Teaserowanie ciszy
było logicznym zwarciem: slajd właśnie ogłosił, że milczenie jest rozwiązane przez breaker
i fallback. Nierozwiązany jest czwarty stan.)*

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

**Korekty po panelu recenzenckim 3-osobowym (2026-08-11, `REVIEW-slajd21-AI-panel3.md`) — v5:**

Panel (senior java/observability · prelegent · nauczyciel) dał zgodny werdykt **„TAK ALE, 6/10:
slot właściwy, wykonanie za ciężkie o połowę"** i trzy niezależne perspektywy wskazały **to samo
cięcie**. Zrobione w runbooku:
- [x] **Slajd 21 przepisany od zera** jako RDZEŃ ~65 s + bloki A/B — nie „to samo bez akapitu"
- [x] Trzy błędy merytoryczne poprawione: **429 jako pułapka** (nie „ten sam kontrakt") ·
      **dwa timeouty** (nie „te same sekundy") · **„wypadek → tryb pracy"** (nie „czego HTTP nie ma")
- [x] „Nic." → **„Wszystkie wzorce zostają. Wszystkie liczby trzeba przeliczyć."**
- [x] „obowiązuje jeden do jednego" → **„co do wzorca"** (streaming łamie mapowanie po statusie)
- [x] „bezpiecznik = ochrona budżetu" **usunięte ze sceny** — mechanicznie nieprawda: CB wyzwala
      się na porażkach, a pętla w 100% udana i przepalająca 2 mln tokenów nie otworzy go nigdy.
      Poprawna wersja („na awarii bezpiecznik, na sukcesie limiter") → 9.7 i pyt. 13i
- [x] Jedna kotwica ⚓, jako **ostatnie zdanie**; ostatnie zdanie celuje w `???`
- [x] Sekcja 8.4 przepisana jako **uczciwy audyt** — deklaracja „zero nowych pojęć" była nieprawdą
- [x] Sekcja 1: budżet AKT 5 przeliczony na realne wartości; slajd 26 dopisany do tabeli
- [x] Slajd 26: **usunięta** opcjonalna zapowiedź serii (jedna oferta na talk)
- [x] Slajd 25: dopisane „…a niektóre awarie nie mają nawet koloru" — 21 przestaje być dygresją,
      staje się setupem klamry (AKT 5 dostaje jeden łuk zamiast dwóch)
- [x] **Wariant B panelu wdrożony:** callback AI na slajdzie 12 (+ #2 na 15, #3 na 18) —
      „AI przestaje być doklejką, bo pojawia się w miejscu merytorycznie własnym"
- [x] Nowa **sekcja 9** — całe zaplecze AI + Q&A 13f–13k + roadmapa demo na następny talk
- [x] Slajd 26 wskazuje **drugi materiał** (`Observability_w_AI_od_juniora_do_seniora.pdf`) —
      odpowiedź na „21 to jedyny slajd bez dowodu na ekranie"

**Zrobione poza runbookiem (2026-08-11):**
- [x] **Cheatsheet, sekcja D2:** dopisane pyt. **13f–13l** (po co mi R4j przy AI · semconv ·
      429-pułapka · budżet vs bezpiecznik · kardynalność · „czy AI zrobi to za mnie" · prywatność
      promptów) + nowa tabela **„Szybkie liczby pod ręką — sekcja AI"** + nagłówek z zasadą sekcji
- [x] **Cheatsheet, 13b przepisane:** warstwy obrony od darmowych do drogich (strukturalna →
      domenowa → sygnały od użytkownika → sędzia-model na próbce), jawnie nazwana luka „oracle",
      i **usunięte zdanie „prawdziwości treści tam nie ma"** — semconv ma już atrybuty na wynik
      ewaluacji, więc to twierdzenie miało datę ważności liczoną w miesiącach (9.6)
- [x] Cheatsheet: nagłówek zgrany z runbookiem **v5** + wskazanie drugiego PDF-a

- [x] **pptx, slajd 21 (deck #23) poprawiony wg 8.6:** „timeout (te same sekundy, drożsi klienci)"
      → **„DWA timeouty / pierwszy token / cisza w środku"**; „mniejszy model / cache" →
      **„który zostawia ślad"**. Lewa kolumna (HTTP do serwisu) **nietknięta** — jej „timeout"
      jest poprawny. Backup: `..._v4.BACKUP-2026-08-11.pptx`. Weryfikacja: otwarcie przez
      **PowerPoint** + eksport slajdu do PNG (tekst mieści się, brak przepełnienia karty)

**Do zrobienia poza runbookiem (blokujące przed wystąpieniem):**
- [ ] Przećwiczyć ze stoperem: RDZEŃ 21 osobno, slajd 12 i slajd 15 z doklejonymi zdaniami
- [ ] *(opcjonalnie, 0 s)* slajd 24: „…bo sprawdzały mój kod, nie to, z czym rozmawia" — patrz 9.10

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

---

## 9. Warstwa AI — zaplecze merytoryczne (v5, 2026-08-11)

> **Czym ta sekcja JEST.** Na scenie warstwa AI ma ~2:45 i jedno nowe pojęcie („czwarty stan").
> Tutaj leży wszystko, co za tym stoi: **co zeszło ze sceny i dlaczego**, gotowe odpowiedzi na
> pytania, które po slajdzie 21 padną, oraz materiał pod następny talk.
>
> **Czym NIE jest.** To nie jest scenariusz do wygłoszenia. Ani jedno zdanie stąd nie ma prawa
> trafić na scenę bez przejścia przez test z sekcji 8.4: *czy sala ma na to kotwicę, którą
> widziała na żywo?*
>
> **Źródło:** `Observability_w_AI_od_juniora_do_seniora.pdf` (37 s., ten sam katalog) — numery
> rozdziałów w nawiasach odsyłają do niego. Mapa scena ↔ materiał: sekcja 7.1.

### 9.1 Mapa 1:1 — i cztery miejsca, w których analogia pęka

Teza slajdu 21 („wszystkie wzorce zostają") jest prawdziwa **co do wzorca** i fałszywa co do
liczb i implementacji. To rozróżnienie jest całą uczciwością tego zakończenia — i jednocześnie
najlepszym teaserem, bo pokazuje, że jest o czym robić drugi talk.

| Wzorzec z talku | Przy modelu obowiązuje? | Co się rozjeżdża |
|---|---|---|
| **Timeout** | wzorzec TAK, liczba NIE | 800 ms = 100% timeoutów. Potrzebne **dwa**: TTFT + idle między tokenami *(9.5)* |
| **Retry** | TAK, z dwoma zastrzeżeniami | (a) każda próba to pozycja na fakturze; (b) **ponowienie zwraca inną odpowiedź** — to nie druga próba tego samego, to losowanie |
| **Circuit Breaker** | TAK, bez zmian | wyzwala się na porażkach transportu; **nie chroni budżetu** *(9.7)* |
| **Fallback** | TAK, z warunkiem | mniejszy model / cache **musi zostawić ślad, kto odpowiedział** — inaczej sam produkuje czwarty stan |
| **Biała lista wyjątków (12-bis)** | TAK, dochodzi nowa rodzina | limit, okno kontekstu, filtr treści dostawcy, odmowa modelu, niezgodność ze schematem — **każda wymaga innej reakcji, więc każda potrzebuje własnej etykiety** (rozdz. 3.6) |
| **Kolejność aspektów (11, 11-bis)** | TAK, **1:1** | rozdz. 20 materiału AI opisuje dokładnie tę samą pułapkę. Nic do przeliczenia |
| **Observation → metryka + trace (18)** | TAK, **1:1** | wywołanie modelu ląduje w tym samym drzewie spanów co SQL i Kafka *(9.3)* |

**Cztery pęknięcia — nazwij je, gdy ktoś w Q&A powie „to po prostu to samo":**

1. **Streaming łamie mapowanie wyjątku po statusie.** `TradeRoutePlannerClient` mapuje typ przez
   `.onStatus(is4xx → Rejected / is5xx → Unavailable)`. Przy odpowiedzi strumieniowanej **status
   200 jest zacommitowany, zanim cokolwiek wiadomo** — nagłówki poszły dawno temu, a strumień może
   urwać się w połowie. `onStatus` nie odpali się nigdy. Cała maszyneria ze slajdów 12/12-bis
   wymaga wtedy innego punktu zaczepienia.
2. **429 to nie 503.** 503 = „zależność jest chora, odetnij ją". 429 = „zależność jest **w idealnym
   stanie**, to TY przekroczyłeś limit". Wrzucenie 429 do `record-exceptions` otwiera obwód na
   **zdrowej** zależności — czyli jest **dokładnie tym błędem, przed którym ostrzegasz na slajdach
   12 i 12-bis**. Do tego 429 należy do rodziny rate-limiter/backpressure, a RateLimiter został
   z talku wycięty w całości. Wersja sceniczna, gdyby kiedyś wracała, brzmi jak **pułapka**:
   > „429 wygląda jak nasze 503 — i to jest pułapka. 503 znaczy »on jest chory«. 429 znaczy »on
   > jest zdrowy, to ja przesadziłem«. Wrzućcie to do bezpiecznika, a odetniecie zależność
   > w idealnym stanie. Pamiętacie trzecią kategorię ze slajdu dwunastego? Wraca — z rachunkiem."
3. **Niedeterminizm wywraca sens ponowienia.** W klasycznym systemie odtworzenie błędu = powtórzenie
   wejścia. Tu powtórzenie wejścia **nie odtwarza wyjścia** (rozdz. 3.1). Konsekwencja dla
   telemetrii jest odwrotna niż odruch „zaloguję, jak będzie trzeba": drugiej szansy nie będzie,
   więc telemetria musi zapisać dość, by zrekonstruować zdarzenie **za pierwszym razem**.
4. **Czwarty stan nie jest wyjątkiem — jest odpowiedzią.** Wszystkie wzorce z talku reagują na
   **brak odpowiedzi albo błąd**. Żaden nie ma punktu zaczepienia w odpowiedzi, która przyszła
   szybko, poprawnie i jest nieprawdziwa.

> **Zastrzeżenie, które musi paść, gdyby ktoś zestawił slajd 21 ze slajdem 24:** Twoja historia
> („trzy miesiące, zły adres, wszystko na zielono") **JEST czwartym stanem bez żadnego AI**.
> 200 OK, poprawnie sformatowany zły adres, zero błędów transportu, sama bzdura. Dlatego RDZEŃ
> mówi „przy HTTP było **wypadkiem**, tu jest **trybem pracy**", a nie „czego HTTP nie ma".
> Zagrane tak, historia ze slajdu 24 nie jest kontrprzykładem — jest **dowodem**, i to Twoim
> własnym. To ta sama choroba, tylko tam była incydentem, a przy modelu jest stanem domyślnym.

### 9.2 Czwarty stan — czym się go naprawdę łapie

Zdanie ze sceny („system w ogóle nie zauważa") jest retoryką i wolno je zagrać. **W Q&A trzeba
umieć je zawęzić**, bo deterministycznie łapie się więcej, niż slajd sugeruje. Warstwy — od
darmowych do drogich (rozdz. 18):

| Warstwa | Sygnał | Koszt | Wiarygodność |
|---|---|---|---|
| **Strukturalna** | walidacja schematu, obecność wymaganych pól, poprawność JSON, `finish_reason` | zerowy | wysoka, ale wąska |
| **Domenowa** ← **tu jest Twoja odpowiedź** | odpowiedź musi wskazać byt, który **istnieje w Twojej bazie**; spójność wyliczeń | zerowy | wysoka dla tego, co obejmuje |
| Heurystyczna | odsetek odmów, obecność cytowań z kontekstu, pokrycie leksykalne wobec dokumentów | niski | przybliżona, dobra jako trend |
| Guardrails | liczba zadziałań filtrów treści, PII, wstrzyknięcia promptu | niski | wysoka w swoim zakresie |
| Sprzężenie od użytkownika | kciuk, **regeneracja**, porzucenie sesji, eskalacja do człowieka | zerowy | rzadka, ale najbliższa prawdzie |
| LLM-as-judge | drugi model ocenia trafność i ugruntowanie | **wysoki** | umiarkowana, obciążona własnymi błędami |
| Ocena ludzka | przegląd próbki przez eksperta | bardzo wysoki | referencyjna |

**Trzy rzeczy, które warto z tego zapamiętać na Q&A:**

- **Najbardziej niedoceniana metryka jakości jest darmowa:** odsetek regeneracji („spróbuj jeszcze
  raz" / to samo pytanie przeformułowane w ciągu 30 s). Nie wymaga sędziego i koreluje
  z niezadowoleniem lepiej niż większość heurystyk. Podobnie: odsetek eskalacji do człowieka.
- **Sędzia-model to drugie płatne wywołanie na request** i drugi niedeterministyczny komponent
  w ścieżce, którą chcesz mierzyć. Stosuje się go **asynchronicznie, na próbce 1–5%, z przypiętą
  wersją modelu i przypiętym promptem oceniającym** — inaczej zmiana po stronie sędziego wygląda
  jak zmiana jakości Twojego systemu. Wynik traktujesz jako **trend**, nie jako prawdę
  o pojedynczej odpowiedzi.
- **Masz w repo gotowy szkielet warstwy domenowej — i to jest najmocniejsza rzecz, jaką możesz
  powiedzieć po slajdzie 21.** `StarportCodeAllowlist` robi dokładnie to: waliduje wartość wobec
  zamkniętego zbioru reguł domeny. `telemetry-pipeline` ma architekturę output-validation
  (`ValidationFilter` → `AnomalyDetectionFilter`, pilnowaną przez `PipesAndFiltersArchitectureTest`)
  — **za zero tokenów**. Odpowiedź brzmi więc nie „nie da się", tylko *„warstwę darmową mam,
  drogiej jeszcze nie zbudowałem"*.

> **Uczciwość, której slajd nie zdąży wypowiedzieć:** licznik `outcome` liczy to, co **kod potrafi
> rozstrzygnąć**. Jakość modelu wymaga **oracle'a** — czegoś, co wie, jak brzmi dobra odpowiedź.
> To jest realna luka, nie retoryczna. Nie zaklejaj jej na scenie; nazwij ją w Q&A.

### 9.3 Telemetria modelu za darmo — czyli zaczepienie #2 rozwinięte

To jest miejsce, w którym Java ma realną przewagę: **Spring AI nie dokłada własnej warstwy
monitoringu** — instrumentuje swoje komponenty **tym samym Observation API**, którym Spring
instrumentuje HTTP, JDBC i Kafkę. Efekt: wywołanie modelu ląduje w jednym drzewie spanów razem
z zapytaniem SQL i konsumpcją z topiku, **bez żadnego klejenia** (rozdz. 5). Dokładnie ten sam
mechanizm, który na slajdzie 18 daje Ci metrykę i trace z jednego opisu.

| Obserwacja | Kiedy powstaje | Co mierzy |
|---|---|---|
| `spring.ai.chat.client` | `ChatClient.call()/stream()` | czas całej operacji po stronie frameworka |
| `spring.ai.advisor` | wykonanie advisora | **uwaga: liczy advisory zagnieżdżone** — sumowanie czasów advisorów liczy je wielokrotnie |
| `gen_ai.client.operation` | `ChatModel.call()/stream()` | czas realnego wywołania dostawcy |
| `gen_ai.client.token.usage` | przy każdym wywołaniu | tokeny wejściowe i wyjściowe |
| `spring.ai.tool` | wywołanie narzędzia | czas; **nazwa narzędzia jest kluczem NISKIEJ kardynalności** — celowo, żeby liczyć skuteczność każdego osobno |
| `db.vector.client.operation` | `add`/`delete`/`query` | operacje na bazie wektorowej |

**Nazwy w Prometheusie — ta sama pułapka, którą znasz z tego repo** *(kropki → podkreślenia,
Timer dostaje `_seconds_*`, Counter dostaje `_total`)*:

| Micrometer | Prometheus |
|---|---|
| `gen_ai.client.operation` (Timer) | `gen_ai_client_operation_seconds_count` / `_sum` / `_max` / `_bucket` / **`_active_count`** |
| `gen_ai.client.token.usage` (Counter) | `gen_ai_client_token_usage_total` |
| etykieta `gen_ai.request.model` | `gen_ai_request_model` |

- ⚠️ **`_active_count` to NIE to samo co `_count`.** To gauge „ile wywołań trwa w tej chwili";
  serie `_seconds_count/_sum/_max` dotyczą **wyłącznie zakończonych**. Przy streamingu, gdzie
  jedno wywołanie trwa kilkanaście sekund, gauge aktywnych jest często ciekawszym sygnałem
  przeciążenia niż przepustowość. *(To ta sama lekcja, co `http_server_requests_active_seconds_count`
  przy wątkach wirtualnych w tym repo.)*
- ⚠️ **Nawyk, który oszczędza godziny — ten sam co tutaj:** zanim napiszesz PromQL, otwórz
  `/actuator/metrics` i zobacz, co **naprawdę** zarejestrowała Twoja wersja biblioteki, a potem
  `/actuator/metrics/{nazwa}` po etykiety. Nazwy mierników Spring AI zmieniały się między
  1.0.0-M, 1.0.0 GA i 1.1.x. **Rejestr nie kłamie, dokumentacja bywa krok za kodem.**
- ⚠️ **Observability dla `ChatModel` jest w 1.1.x zaimplementowana tylko dla części dostawców.**
  Dla `EmbeddingModel` lista jest jeszcze krótsza. Brak metryk przy nietypowym dostawcy **nie jest
  błędem konfiguracji** — to warto wiedzieć, zanim spędzisz wieczór na debugowaniu pustego panelu.
- ⚠️ **Percentyle: `percentiles-histogram`, nigdy `percentiles`.** Ten drugi liczy percentyl
  w instancji i eksportuje gotową liczbę — **średnia z trzech p99 nie jest p99**. Przy wielu
  replikach chcesz kubełków i `histogram_quantile()` w PromQL. Twój dashboard robi to dobrze;
  przy modelu jest to jeszcze ważniejsze, bo rozkład ma bardzo długi ogon.

**Cztery pierwsze panele (rozdz. 6)** — jeśli ktoś zapyta „od czego zacząć": ruch · latencja
(p50/p95/p99 na jednym wykresie) · błędy po klasie · **tokeny/s w rozbiciu na wejście i wyjście**.
Trzy pierwsze to klasyczne RED. Czwarty jest specyficzny dla AI i pełni podwójną rolę: pilnuje
budżetu **i jest najczulszym wykrywaczem tego, że coś zmieniło się w promptach** — nagły wzrost
tokenów wejściowych przy stałym ruchu prawie zawsze znaczy, że ktoś dołożył kontekstu.

### 9.4 Koszt i kardynalność — dwie rzeczy, które przy modelu bolą wcześniej

**Koszt.** W zwykłym mikroserwisie koszt żądania to ułamek amortyzacji instancji. Przy LLM koszt
pojedynczego żądania różni się **stukrotnie** w zależności od długości kontekstu — użytkownik,
który wkleja PDF-a, kosztuje tyle, co tysiąc krótkich pytań (rozdz. 3.3). Tokeny to jeszcze nie
pieniądze: przeliczenie wymaga cennika, rozróżnienia wejścia od wyjścia i obsługi tokenów
z cache'u — a to trzeba dołożyć samemu, bo framework tego nie zna.

- **Wzorzec:** własny `ObservationHandler` na kontekście wywołania modelu → `DistributionSummary`
  w **mikrocentach** (unika problemów z precyzją `double`), etykiety: model + `feature`.
- **Elegancki trik:** nie zaszywaj cen w kodzie. Publikuj cennik jako **osobny Gauge** i licz koszt
  w PromQL (`* on (model) group_left()`). Zmiana cennika = zmiana konfiguracji, a historyczne
  wykresy zachowują ceny obowiązujące w danym okresie.
- **Liczba, która naprawdę interesuje biznes**, to nie „ile wydaliśmy", tylko **koszt jednostki
  wartości**: koszt rozwiązanego zgłoszenia, przetworzonego wniosku, aktywnego użytkownika.
  Wymaga jednej etykiety o **zamkniętym zbiorze** (`feature` / `use_case`) — i to jest zwykle
  najbardziej wartościowa etykieta w całym systemie. *(To dokładnie ta sama myśl, co Twój
  „drugi rząd dashboardu dla biznesu" ze slajdu 17 — tylko z walutą.)*

**Kardynalność.** Liczba serii to **iloczyn**, nie suma: 4 modele × 3 środowiska × 12 kubełków
= 144 serie; dołóż `tenant` (200) i `user_id` (50 000) i jesteś na miliardzie serii. Objaw:
rosnąca pamięć, wolne zapytania, OOM przy kompakcji — **i wygląda to jak awaria infrastruktury,
a nie jak błąd w kodzie aplikacji**.

| Dana | Miejsce | Dlaczego |
|---|---|---|
| model, dostawca, operacja, `feature`, wersja promptu, klasa błędu | **metryka** | zamknięty zbiór, rzadko się zmienia |
| `tenant` | metryka **warunkowo** | tylko przy dziesiątkach; przy setkach agreguj do klasy (plan, region) |
| `conversation_id`, `user_id`, `request_id`, `response_id` | **ślad** | unikalne z definicji |
| treść promptu i odpowiedzi | log / event | nieograniczona kardynalność i wrażliwość |
| liczba tokenów pojedynczego żądania | ślad + osobny histogram | jako **wartość** — do histogramu; jako **etykieta** — nigdy |

- **Kod obronny wart wpisania pierwszego dnia:** `MeterFilter.ignoreTags("user.id", "conversation.id", …)`
  wywala etykiety, które ktoś może dołożyć przez nieuwagę, a `MeterFilter.maximumAllowableTags(…, 20, deny())`
  odrzuca kolejne serie **zamiast zabijać klaster**.
- **Test przed wdrożeniem:** `topk(20, count by (__name__)({__name__=~".+"}))` na środowisku
  testowym z realistycznym ruchem. Nie po fakcie na produkcji.
- 🔗 **Kotwica w Twoim repo:** to jest ta sama dyscyplina, co `StarportTagSanitizer` i ADR-0030 —
  tylko że przy modelu wybuch przychodzi szybciej, bo dochodzi `prompt.version`, `model`, `feature`
  i `finish_reason` naraz. *(Uwaga na własnym podwórku: sanitizer jest dziś używany tylko
  w `ReservationService` — pozostałe miejsca tagują surowym kodem z requestu.)*

### 9.5 Latencja, której nie zmierzy zwykły Timer

Powód, dla którego „te same sekundy" ze szkicu v4 były błędem: przy streamingu użytkownik
doświadcza **trzech różnych rzeczy**, a Timer mierzy tylko jedną — i to nie tę najważniejszą
(rozdz. 13).

| Miara | Definicja | Dlaczego istotna |
|---|---|---|
| **TTFT** (time to first token) | od wysłania żądania do pierwszego fragmentu | **to jest odczuwana szybkość**; poniżej ~500 ms interfejs wydaje się natychmiastowy |
| **TPOT** (time per output token) | średni czas kolejnego tokenu | określa, czy tekst „płynie"; wolniej niż tempo czytania = złe wrażenie |
| Czas całkowity | do ostatniego tokenu | liczy się dla zadań wsadowych i dla kosztu połączenia |
| **Czas w kolejce** | od przyjęcia żądania do wysłania go do dostawcy | ujawnia **własne** wąskie gardło (pula połączeń, limiter) — najczęściej mylone z „wolnym modelem" |

Odpowiedź, która zaczyna płynąć po 300 ms i kończy po 12 s, jest odbierana jako **szybka**.
Odpowiedź, która milczy 4 s i kończy po 5 s — jako **wolna**. Jeden Timer na całość opisuje coś,
czego nikt nie odczuwa.

> **Pułapka, którą warto znać, bo żaden framework jej nie da:** żądanie, które zwróciło pierwszy
> token i **urwało się po dwóch sekundach**, ma świetny TTFT, poprawny status 200 (nagłówki poszły
> dawno temu) i **nie pojawi się w metryce błędów**. Z punktu widzenia użytkownika to całkowita
> porażka. Potrzebny jest osobny licznik strumieni zakończonych **bez** sygnału `onComplete`.
> To jest czwarty stan w wersji transportowej — i najlepsza możliwa riposta na „przecież
> 200 znaczy 200".

### 9.6 Semconv GenAI — stan faktyczny (nie mów tego ze sceny, ale wiedz)

**Nic specyficznego dla GenAI nie ma dziś statusu Stable.** Konwencje zostały w 2026 wydzielone
do osobnego repozytorium `open-telemetry/semantic-conventions-genai`; wydanie v1.42.0 głównego
repo oznaczyło całą przestrzeń `gen_ai.*` jako przeniesioną, a v1.43.0 nie zawiera jej wcale.
Nowe repozytorium nie ma jeszcze wersjonowanych wydań ani ustalonego schema URL.
**Praktyczny wniosek: przypnij wersje bibliotek instrumentujących i czytaj changelogi** — nazwa
atrybutu potrafi zmienić się bez podbicia wersji głównej.

| Pułapka | Na czym polega |
|---|---|
| **Dwa pokolenia atrybutów naraz** | `gen_ai.system` → `gen_ai.provider.name`. Spring AI 1.1.x nadal emituje starą nazwę; różne biblioteki w tej samej instalacji potrafią emitować oba pokolenia. **Jeden filtr w PromQL i połowa ruchu znika z panelu** — zanim uznasz, że masz awarię, sprawdź, czy nie patrzysz na dwie nazwy tego samego |
| `prompt_tokens` → `input_tokens` | ta sama historia, wcześniejsza rewizja |
| Kubełki kończą się na 10 s | rekomendowane granice histogramu czasu trwania mają ostatni kubełek na 10 s — **przy dłuższych generacjach musisz skonfigurować własne SLO** w Micrometerze |
| Treść rozmowy = osobny event | `gen_ai.client.inference.operation.details`, opt-in. Idea jest architektoniczna: duże ładunki oddziela się od śladu, żeby kierować je do innego magazynu, z inną retencją i innymi uprawnieniami |

> ⚠️ **Korekta do cheatsheetu 13b.** Zdanie „prawdziwości treści tam nie ma" ma **datę ważności
> liczoną w miesiącach** — w semconv istnieje już `gen_ai.evaluation.result`. Bezpieczniejsza
> wersja tej odpowiedzi: *„standard porządkuje transport, a ocenę jakości dopiero zaczyna
> obejmować — i tak zostawia Tobie decyzję, **co znaczy »dobrze« w Twojej domenie**"*.

**Cicha podmiana modelu — jedna metryka, którą warto wdrożyć od razu** (rozdz. 19): wykres
`count by (gen_ai_response_model) (gen_ai_client_operation_seconds_count)` z alertem na
**pojawienie się nowej wartości**. Dosłownie kilka linijek konfiguracji, a wykrywa klasę
problemów, o której większość zespołów dowiaduje się z reklamacji użytkowników.
Dlatego `gen_ai.response.model` (co odpowiedziało) jest osobnym atrybutem niż
`gen_ai.request.model` (o co prosiłeś) — i dlatego zaczepienie #2 mówi „**który model naprawdę
odpowiedział**". To najtańszy istniejący detektor dryfu.

**Cztery niezależne osie wersjonowania** w systemie AI: wersja kodu · wersja promptu · wersja
modelu · wersja indeksu wektorowego. Bez etykiet nie odpowiesz na pytanie „co się zmieniło".
Wszystkie cztery są kluczami **niskiej** kardynalności — pod warunkiem że wersje promptów są
**numerowane, a nie hashowane treścią**.

### 9.7 Resilience4j przy modelu — co dokładnie trzeba przeliczyć

Zdanie ze sceny brzmi „wszystkie wzorce zostają, wszystkie liczby trzeba przeliczyć". Oto liczby:

| Ustawienie z Twojego YAML-a | Przy modelu |
|---|---|
| read-timeout 800 ms | **nie przenosi się.** Dwa timeouty: TTFT (sekundy) + idle między tokenami |
| slow-call 500 ms | próg musi wynikać z TTFT, nie z całości; przy streamingu „wolne" znaczy co innego |
| `max-attempts: 2` | **sprawdź defaulty biblioteki** — potrafią być wielokrotnie wyższe; każda próba to pozycja na fakturze |
| backoff ~100 ms + jitter | za krótki: dostawcy podają `Retry-After`, a właściwy backoff dla limitu to sekundy–minuty |
| okno 10 wywołań | przy niskim ruchu na drogiej zależności okno licznikowe pamięta prehistorię jeszcze mocniej niż tutaj *(cheatsheet pyt. 5)* |
| `record-exceptions` | dochodzi rodzina błędów, których HTTP nie ma: okno kontekstu, filtr treści, odmowa modelu, niezgodność schematu |
| `ignore-exceptions` | **tu ląduje 429** — limit nie jest awarią zależności *(9.1 pkt 2)* |

**Trzy rzeczy, które ta tabela mówi między wierszami:**

1. **Bezpiecznik nie chroni budżetu.** CB wyzwala się na **porażkach**. Pętla agenta w 100% udana,
   przepalająca dwa miliony tokenów, **nie otworzy go nigdy**. Poprawna formuła:
   *„na awarii chroni bezpiecznik, na sukcesie chroni limiter — ten, którego dziś nie pokazałem"*.
   Domyka to lukę po wyciętym z talku RateLimiterze zamiast ją ukrywać.
2. **Degradacja musi być mierzalnym stanem.** Fallback do tańszego modelu, cache'u albo szablonu
   jest sensowną strategią **pod warunkiem, że jest widoczny**: `counter("ai.serving.mode",
   "mode", primary|fallback|cached|static, "reason", provider_5xx|rate_limit|timeout|budget,
   "feature", …)`. Udział trybu awaryjnego to gotowy SLI. **System, który po cichu przełącza się
   na gorszy model, w raportach wygląda zdrowo, a użytkownikom daje coraz gorsze odpowiedzi** —
   czyli produkuje czwarty stan własnymi rękami. U Ciebie odpowiednikiem jest `outcome="circuit_open"`
   ze slajdu 18: kotwica **już istnieje**, wystarczy ją nazwać.
3. **Alertuj na doświadczenie użytkownika albo na pieniądze — nie na stan komponentu.**
   Warto: spalanie budżetu błędów (burn rate) · koszt godzinowy powyżej normy · odsetek pustych
   wyników retrievalu · **nagła zmiana `gen_ai.response.model`** · wzrost `finish_reason=content_filter|length`
   · udział trybu awaryjnego. Nie warto: pojedyncza 5xx od dostawcy · wzrost latencji o 10% wobec
   wczoraj · **otwarcie bezpiecznika na 30 sekund**. *(Ten ostatni wiersz jest niewygodny, bo Twój
   `alerts.yml` alertuje dziś dokładnie na `resilience4j_circuitbreaker_state` — patrz 9.9.)*

### 9.8 Gotowe odpowiedzi Q&A — pyt. 13f–13l

> ✅ **Wdrożone w `QA-cheatsheet-KOLB-v3.md`, sekcja D2 (2026-08-11)** — tam jest wersja kanoniczna,
> dopracowana pod czytanie na drugim ekranie w trakcie Q&A (+ 13l o prywatności promptów
> i + „Szybkie liczby pod ręką — sekcja AI"). Poniżej zostaje wersja robocza z uzasadnieniami;
> **przy korekcie zmieniaj cheatsheet, nie to.**

**13f. „Po co mi wiedza o Resilience4j, skoro idziemy w AI?"**
> **Bo wywołanie modelu to najdroższa i najmniej przewidywalna zdalna zależność, jaką dziś macie
> w systemie.** Wszystko, co dziś zobaczyliście, jest warstwą pod spodem: timeout, ponowienie,
> odcięcie, tryb awaryjny, metryka wyniku. Zmienia się jedna rzecz — liczby. I dochodzi jedna
> nowa — to, że odpowiedź może być nieprawdziwa. *(Jeśli ktoś chce dowodu, że to nie jest naciągane:
> materiał o observability w AI ma osobny rozdział o Resilience4j, w którym pułapka nr 5 to
> dokładnie kolejność aspektów ze slajdu jedenastego.)*

**13g. „Skoro OpenTelemetry standaryzuje GenAI, to problem sam się rozwiąże?"**
> **Nie, bo standaryzuje transport, a nie prawdę.** Stan faktyczny: nic specyficznego dla GenAI
> nie jest dziś Stable, konwencje wyjechały do osobnego repozytorium bez wersjonowanych wydań,
> a nazwy atrybutów zmieniają się między rewizjami — `gen_ai.system` stało się
> `gen_ai.provider.name` i biblioteki potrafią emitować oba pokolenia naraz. Standard porządkuje
> to, co dziś oglądaliście na wykresach. Ocenę jakości dopiero zaczyna obejmować — i tak zostawia
> Wam decyzję, **co znaczy „dobrze" w Waszej domenie**.

**13h. „429 to przecież to samo co Wasze 503 — mogę wrzucić do tej samej listy?"**
> **Nie. To jest pułapka i to dokładnie ta, przed którą ostrzegałem na slajdzie dwunastym.**
> 503 znaczy „on jest chory". 429 znaczy „on jest zdrowy — to ja przesadziłem". Wrzucicie 429 do
> `record-exceptions`, a bezpiecznik **odetnie zależność w idealnym stanie**. To jest miejsce dla
> `ignore-exceptions` plus backoff wynikający z nagłówka `Retry-After`, a systemowo — dla
> limitera po Waszej stronie, żeby w ogóle tam nie dojechać.

**13i. „To bezpiecznik ochroni mnie przed rachunkiem?"**
> **Nie — bezpiecznik chroni przed przepalaniem na awarii. Przed przepalaniem na sukcesie chroni
> limiter.** Bezpiecznik wyzwala się na porażkach; pętla, która w stu procentach się udaje
> i przepala dwa miliony tokenów, nie otworzy go nigdy. Od pieniędzy jest budżet tokenów jako
> osobna metryka i limit — to ten wzorzec, którego dziś nie pokazałem.

**13j. „Ile etykiet mogę dołożyć do metryk modelu?"**
> **Mniej, niż się wydaje — i to iloczyn, nie suma.** Model × środowisko × kubełki histogramu to
> już setki serii; dołóżcie `user_id` i zabijacie Prometheusa w tydzień, a wygląda to jak awaria
> infrastruktury. Do metryki: model, dostawca, operacja, `feature`, wersja promptu, klasa błędu.
> Do śladu: identyfikatory konwersacji i użytkownika. Do logu albo eventu: treść. I dwa
> bezpieczniki w kodzie od pierwszego dnia — `ignoreTags` na to, co ktoś dołoży przez nieuwagę,
> `maximumAllowableTags` żeby nadmiar był **odrzucany, a nie zabijał klaster**.

**13k. „Czy AI zrobi observability za mnie?"**
> **Częściowo i nie tam, gdzie obiecują.** Dobrze działa: wykrywanie anomalii w szeregach
> czasowych, grupowanie podobnych błędów w logach, pisanie zapytań PromQL z opisu po ludzku,
> streszczanie przebiegu incydentu. Gorzej, niż obiecują materiały marketingowe: automatyczne
> ustalanie przyczyny źródłowej — model wskaże, co zmieniło się **razem** z awarią, ale korelacja
> nie jest przyczyną, a systemy rozproszone są pełne zmian współbieżnych. Traktujcie to jako
> generator hipotez, nie wyrocznię. *(I rekurencja, o której warto pamiętać: asystent do analizy
> incydentów sam ma koszt, latencję i tryby awarii — a taki, który jest niedostępny podczas
> incydentu, jest ozdobą, nie narzędziem.)*

**Bonus — pytanie o prywatność, jeśli padnie:** treści promptów i odpowiedzi są domyślnie
**wyłączone** z telemetrii i to jest jedyne rozsądne ustawienie domyślne — użytkownik potrafi
wkleić do czatu PESEL albo fragment umowy pod NDA, a stamtąd tekst leci do Loki, jest replikowany,
backupowany i przeszukiwalny przez każdego, kto ma dostęp do Grafany. Wzorce, które godzą
debugowanie z prywatnością: **metadane zamiast treści** (długość, hash, wersja szablonu — wystarcza
w ~80% diagnostyki) · osobny kanał z krótką retencją i własnym RBAC · próbkowanie z opt-inem ·
flaga włączana na czas incydentu. I jedno rozróżnienie architektoniczne: **observability to nie
audyt** — systemy observability są z założenia stratne (sampling, retencja, brak niezmienności),
a audyt wymaga dokładnie odwrotnych własności.

### 9.9 Co musi być w repo, żeby następny talk dało się zagrać z żywym demo

Kryterium przy każdej pozycji: **co konkretnie widać na ekranie w 90 sekundach.** Kolejność wg
wartości scenicznej.

| | Zadanie | Co widać na ekranie |
|---|---|---|
| **A** | **Sterowalny fake modelu z czwartym trybem.** Toxiproxy psuje transport; czwartego stanu nie zrobi. Stub z trybami `slow` · `error` · `silent` · `429+Retry-After` **oraz `hallucinate` — 200 OK, szybko, treść deterministycznie zła.** Ten sam interfejs co `demo-resilience.ps1` | Enter → 20 s → **dashboard bez jednej zmiany**. Cały następny talk w jednym kadrze. Dziś niepokazywalne |
| **B** | **Metryka jakości domenowej + panel obok transportowego.** Walidator odpowiedzi (model musi wskazać port istniejący w bazie) → licznik `outcome=valid\|invalid\|unparseable` | Dwa wykresy jeden pod drugim: górny płaski zielony („transport OK"), dolny lecący w dół („jakość"). **Jedyny dowód, że czwarty stan da się zobaczyć** |
| **C** | **Adapter LLM w tej samej architekturze co planner** — port + adapter obok `TradeRoutePlannerClient`, owinięty **tymi samymi** `@Retry`/`@CircuitBreaker`/`Observation` i tym samym rozdziałem wyjątków | Zrzut lewo/prawo w formacie slajdu 16, tylko z modelem — sala rozpoznaje kształt w 3 s. Plus **trace w Tempo z `gen_ai.*` obok dzisiejszego HTTP-owego**: jeden obraz zamiast akapitu o semconv |
| **D** | **Licznik kosztu ponowień.** Tokeny × liczba prób × cennik z configu → panel „koszt ponowień w oknie 15 min" | Włączasz `-Chaos error`, retry pracuje, **rośnie słupek w złotówkach**. „Retry to drugi rachunek" przestaje być twierdzeniem |
| **E** | **`ResponseGroundednessFilter` + test-strażnik + ADR-0040.** Szkielet już istnieje: `ValidationFilter` → `AnomalyDetectionFilter`, pilnowany przez `PipesAndFiltersArchitectureTest`. Test na wzór `ResilienceAspectOrderTest`: nieprzechodząca walidacja podbija metrykę jakości i **nie** otwiera bezpiecznika (transport był zdrowy) | Nazwa testu na zielono + trzy linijki ADR-a. Dziś na „macie na to dowód?" odpowiadasz ADR-0039 i dwoma testami — następny talk musi mieć swój odpowiednik |

**Dwie rzeczy, które kosztują mało, a wzmacniają dzisiejszy talk już teraz:**

1. **Exemplary na serii `outcome != "success"`.** ADR-0033 jest wdrożony,
   `exemplars-success-error.json` działa — brakuje podpięcia. Klik z wykresu wprost w trace.
   Dla modelu to **jedyna droga** od „jakość spadła o 3%" do „zobacz **TĘ** odpowiedź". Piszesz
   raz, działa i dla plannera, i dla LLM-a.
2. **Alert na WYNIK, nie na transport.** `alerts.yml` ma dziś wyłącznie
   `resilience4j_circuitbreaker_state`, `_failure_rate` i retry — czyli **dokładnie te sygnały,
   które w czwartym stanie milczą**. Dołóż:
   `sum(rate(reservations_route_plan_outcome_total{outcome="success"}[5m])) / sum(rate(reservations_route_plan_outcome_total[5m])) < 0.95`
   To jest **jedyny alert, który potrafi złapać „zielono, ale źle" — bez żadnego modelu**, czyli
   teza slajdu 21 zademonstrowana na deterministycznej zależności. Gdyby to istniało przed talkiem,
   slajd 21 przestaje być wekslem bez pokrycia.

**ADR-0040 „wywołanie GenAI jako zależność zewnętrzna" — szkic treści:** dwa timeouty (TTFT +
idle) · **429 poza `record-exceptions`** · budżet tokenów jako `Counter(model, token.type)`
(semconv `gen_ai.client.token.usage` to histogram — do rachunku nie nadaje się wprost) ·
`prompt_version` **wyłącznie jako atrybut spanu** (rośnie z każdym deployem → nieograniczony
w czasie → zakaz jako label) · `reason` od sędziego jako atrybut spanu (bywa tekstem = śmierć
bazy metryk) · rozkład score 0..1 jako `DistributionSummary` z histogramem, **nigdy Gauge
z „aktualną jakością"** (last-value, nie agreguje się między replikami, supplier odpala się przy
każdym scrape × każda replika) · semconv `gen_ai.*` w statusie Development → własna nazwa metryki
+ mapowanie. **Przy 39 istniejących ADR-ach to najtańszy dowód, że decyzja była świadoma.**

### 9.10 Pozycjonowanie: co ten slajd naprawdę załatwia, a czego nie

**Slajd 21 nie zbierze sali.** Uwaga publiczności rozstrzyga się w **tytule, abstrakcie i pierwszych
dwóch minutach** — nikt nie wybiera talku ze względu na minutę 43. Jeśli celem jest „ludzi bardziej
interesuje AI", to jest odpowiedź na pytanie o **pozycjonowanie**, a slajd 21 jest zmianą w treści
na końcu — czyli w jedynym miejscu, gdzie ta zmiana nie może zadziałać. Slajd 21 robi trzy inne
rzeczy, i robi je dobrze:

1. daje sali **nazwę** dla problemu, który wielu z nich już ma („czwarty stan");
2. pokazuje, że dzisiejsze 40 minut **skaluje się o poziom** — nie jest wiedzą o jednej bibliotece;
3. zostawia otwarte `???`, które uzasadnia następny talk **bez obiecywania go**.

**Właściwa odpowiedź na „ludzi interesuje AI" to osobne zgłoszenie CFP, nie osobny slajd.**
Roboczy tytuł, który działa: **„Twój serwis woła model AI. Co się stanie, gdy model skłamie?"** —
świadomie **„skłamie", nie „zamilknie"**: milczenie jest rozwiązane przez breaker i fallback już
w tym talku, więc teaserowanie ciszy zapowiada problem, który sam ogłosiłeś za zamknięty.
Nierozwiązany jest czwarty stan — i to on jest towarem.

**Dlaczego ten kierunek jest lepszy niż „Spring Boot 4 / co nowego":** SB4 ma datę ważności
liczoną w miesiącach i nie buduje serii — za rok to slajd historyczny. „Czwarty stan" to problem
klasy, a nie wersji: nie przeterminuje się, dopóki modele są probabilistyczne. Do tego domyka
serię logicznie — talk 1 uczy, że **awarię trzeba widzieć**; talk 2 pyta, **co zrobić z awarią,
która nie ma koloru**.

> **Wersja radykalna, warta rozważenia przy pisaniu CFP:** gdyby planner na mapie ze slajdu 3
> **był** modelem, a Toxiproxy psuł wywołanie modelu, cały talk byłby „AI-owy" **bez jednego
> slajdu o AI**. To jedyna wersja, w której dwie minuty AI mają pełny sens — bo wtedy tych dwóch
> minut w ogóle nie ma. Koszt: demo-ślepe traci deterministyczność, a AKT 1 stoi na tym, że
> psucie jest powtarzalne co do sekundy. Decyzja na następną edycję, nie na tę.

**Jedna rzecz, której ten talk nie pokrywa, a która przy modelu jest kluczowa — i warto to
wiedzieć, zanim ktoś zapyta:** *jak dobrze testować*. Jedyny żywy obraz testowania w talku jest
**negatywny** („testy przeszły" jako sprawca fałszywego spokoju na slajdzie 24). Najtańsza
naprawa to sześć słów w tym samym zdaniu, zero sekund różnicy:

> „…wszystko na zielono, **testy przeszły — bo sprawdzały mój kod, nie to, z czym rozmawia.**"

Zamienia jedyny obraz testowania z antytestowego w protestowy, retrospektywnie uzasadnia całą
warstwę AI (bo to jest **dokładnie** problem czwartego stanu: test sprawdza Twoją stronę granicy)
— i jest w miejscu, które sala i tak zapamięta najlepiej.
