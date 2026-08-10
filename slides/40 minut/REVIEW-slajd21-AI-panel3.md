# Panel recenzencki — slajd 21 „Czwarty stan" (AI) w `RUNBOOK-scena-KOLB-45min.md`

**Data:** 2026-08-10 · **Branch materiału:** `demo` · **Recenzowany fragment:** linie 479–524 (AKT 5, slajd 21) + sekcja 8 (linie 643–758)

**Skład panelu:**
- **R1 — Senior Java Developer / architekt AI + observability** (Micrometer, OpenTelemetry) — wiarygodność merytoryczna
- **R2 — Prelegent konferencyjny** — dramaturgia, timing, ryzyko sceniczne
- **R3 — Nauczyciel / mentor** — cykl Kolba, transfer wiedzy, uczciwość dydaktyczna

**Pytanie do panelu:** czy motyw AI został poprawnie wprowadzony na koniec prezentacji?

---

## 1. Werdykt zbiorczy

| Recenzent | Werdykt | Ocena |
|---|---|---|
| R1 — senior java/observability | TAK ALE | 6,5 / 10 |
| R2 — prelegent | TAK ALE | 6 / 10 |
| R3 — nauczyciel/mentor | TAK ALE | 6 / 10 |

**Werdykt panelu: TAK ALE — 6/10. Decyzja jest słuszna, wykonanie nie.**

Trzy niezależne perspektywy zbiegły się na tej samej diagnozie: **slot 21 jest właściwy, a tekst jest za długi i za gęsty o mniej więcej połowę.** Wszyscy trzej — z zupełnie różnych powodów — wskazali **to samo cięcie**: zostaw „czwarty stan" + kotwice ⚓, wytnij akapit o koszcie/429 i zdanie o semconv. To nie jest kompromis, to zbieżność.

**Rzecz, którą panel podkreśla najmocniej:**
> **Wersja „cięcie awaryjne" nie jest wersją gorszą. Jest wersją właściwą. Graj ją zawsze, nie tylko przy spóźnieniu.**

---

## 2. Na czym panel się zgadza (konsensus 3/3)

### 2.1 Slot 21 jest dobrze wybrany — sekcja 8.2 ma rację

Argument z 8.2 („intelektualny szczyt przed emocjonalnym"; „AI po klamrze = reklama po napisach") broni się u wszystkich trzech recenzentów. Nikt nie postuluje przenoszenia slajdu. Spór dotyczy **wagi**, nie pozycji.

### 2.2 Tekst jest za długi — i to matematycznie wywraca AKT 5

R2 rozpisał realny budżet AKT 5 (deklarowane 38:00 → 45:00 = 7:00):

| Slajd | Deklarowane | Realne (ze słów + pauz) |
|---|---|---|
| 21 Czwarty stan | 2:00 (przyznane 2:15) | **2:35–2:50** (321 słów) |
| 22 Wróćmy do listy | 1:30 | 1:45 |
| 23 ECHO | 0:45 | 1:00–1:10 |
| 24 „3 miesiące" | 2:00 | 2:15–2:25 |
| 25 Klamra | 0:45 | 0:45 |
| 26 Dziękuję | — (nieujęte) | 0:10 |
| **Razem** | **7:00** | **8:30–8:55** |

**Przekroczenie ~1:30–2:00** — i to przy założeniu wejścia równo o 38:00, czego po `for: 30s` + 60–90 s pending alertu realnie nie zrobisz.

**Gdzie pęknie:** nie tam, gdzie runbook przewiduje. Bloków 21 i 22 nie utniesz w locie (są wyuczone z tekstu). Skompresują się **23 i 24** — czyli echo i reveal. Konkretnie: zjesz ciszę po „jak długo można tego nie zauważyć?" i długą pauzę po „Trzy miesiące". To jedyne dwa miejsca w talku, gdzie **cisza jest treścią**.

> AI-teaser nie kradnie energii revealowi bezpośrednio. Kradnie mu sekundy pauzy — co jest tym samym.

### 2.3 Deklaracja „zero nowych pojęć" (sekcja 8.4) jest nieprawdziwa

R3 przeaudytował tabelę 8.4 wiersz po wierszu. Realnie nowych bytów: **~10**.

| Fraza | Deklarowana kotwica | Czy naprawdę znana | Nowe? |
|---|---|---|---|
| „jedno zdalne HTTP" | slajd 2–3 | TAK, bohater talku | NIE |
| „zielono, płasko, szybko" | slajd 5, cytat | TAK — najlepszy wiersz tabeli | NIE |
| „system zauważył i nie zareagował" → „w ogóle nie zauważa" | krok 440 | TAK — najmocniejszy ruch slajdu | NIE |
| „retry = drugi rachunek za tokeny" | slajd 12 + jitter ze 16 | **NIE.** Slajd 12 uczy kosztu *poprawnościowego*, jitter — *obciążeniowego*. **Pieniądz nie pada w talku ani razu przez 40 min** | **TAK** (+ „token" = drugi byt) |
| „bezpiecznik = ochrona budżetu" | brak w 8.4 | trzecia rola znanego obiektu | TAK (tani) |
| „429 = ten sam kontrakt co 503" | 503 + Retry-After | **POŁOWICZNIE.** Kotwiczy nagłówek, nie status i nie przyczynę. Przyczyną jest limit — a **RateLimiter został z talku wycięty w całości** | **TAK** |
| „fallback = mniejszy model" | fallback ze slajdu 16 | NIE. Kotwiczy *kształt*, nie kaskadę modeli | TAK |
| „fallback = cache, wczorajsza odpowiedź" | fallback ze slajdu 16 | **NIE — gorzej, SPRZECZNE.** Slajd 16 uczy, że dobry fallback **mówi prawdę** (503 + Retry-After, „nie milczy"). Tu fallback = po cichu podać nieświeże | **TAK, wymaga naprawy** |
| „**czwarty stan**" | **brak w 8.4** | kotwica istnieje (slajd 9: „trzeci stan: milczy"), ale audyt pominął **tezę slajdu** | TAK (definicyjnie) |
| „ręczny licznik jakości widzi czwarty stan" | slajd 18, licznik `outcome` | **POŁOWICZNIE.** `outcome` liczy to, co **kod potrafi rozstrzygnąć**; jakość modelu wymaga **oracle'a**. Luka nienazwana | TAK (ukryte) |
| „OpenTelemetry GenAI semantic conventions" | **brak w 8.4** | **NIE.** Słowo „OpenTelemetry" **nie pada w talku ani razu** — talk mówi Micrometer/Prometheus/Grafana | **TAK ×3** |

**Policzone obciążenie poznawcze:**
- Slajd 21: **10 pojęć / 135 s ≈ 1 nowe pojęcie co 13,5 s**
- AKT 3 (najgęstszy akt talku, 12 min): ~16 pojęć / 720 s ≈ **1 co 45 s** — i każde z realną kotwicą

> **Slajd 21 ma ~3,4× gęstość pojęciową najgęstszego aktu talku — na 43. minucie, bez powtórki, bez notatki, bez pytania sprawdzającego, z ~40% kotwic twierdzonych zamiast realnych.**

Przewidywany transfer: sala zapamięta **jeden** element („czwarty stan" — bo to tytuł, czerwony wiersz i jedyna rzecz powtórzona). Reszta jest szumem rozcieńczającym ten jeden element.

### 2.4 Teaser celuje w niewłaściwy problem (3/3, niezależnie)

Slajd mówi *„milczy → breaker + fallback, wszystko obowiązuje 1:1"*, a kończy: *„co się stanie, gdy model naprawdę **zamilknie**… to osobna opowieść"*.

**Teaserujesz problem, który slajd właśnie ogłosił za rozwiązany.** Nierozwiązany jest **czwarty stan**, nie cisza. Ten sam błąd siedzi w 8.1 pkt 2 jako proponowany tytuł następnego talku.

→ Ostatnie zdanie musi celować w `???`, nie w milczenie.

---

## 3. Twarde błędy merytoryczne (R1, potwierdzone przez panel)

Trzy zdania, które usłyszy praktyk LLM-owy z sali i będzie miał rację.

### 3.1 „429 z Retry-After — dokładnie ten kontrakt, który dziś widzieliście przy 503" (linie 491–493, tabela 8.4 linia 690)

**To nie jest ten sam kontrakt.** 503 = „zależność jest chora, odetnij ją". 429 = „zależność jest **w idealnym stanie**, to TY przekroczyłeś limit".

**Dlaczego groźne:** kto potraktuje to dosłownie i wrzuci 429 do `record-exceptions`, otwiera obwód na **zdrowej** zależności — czyli popełnia dokładnie ten błąd, przed którym ostrzegasz na slajdach 12 i 12-bis. **Autogol na własnej tezie.** Dodatkowo 429 należy do rodziny rate-limiter/backpressure, czyli do wzorca **wyciętego z talku**.

**Poprawka (odwraca wtopę w największą wygraną):**
> „429 wygląda jak nasze 503 — i to jest **pułapka**. 503 znaczy »on jest chory«. 429 znaczy »on jest zdrowy, to ja przesadziłem«. Wrzućcie to do bezpiecznika, a odetniecie zależność w idealnym stanie. Pamiętacie trzecią kategorię ze slajdu dwunastego? Wraca — z rachunkiem."

### 3.2 „wolny → timeout (te same sekundy, drożsi klienci)" (szkic slajdu, linia 710)

**Fałsz.** 800 ms na modelu = 100% timeoutów. Generacja legalnie trwa dziesiątki sekund. To **jedyna liczba na tym slajdzie i jest błędna** — a slajd ktoś sfotografuje.

**Poprawka:** `wolny → **dwa** timeouty (pierwszy token / cisza między tokenami)`. Treść cheatsheetu 13d skraca się do pół zdania.

### 3.3 „I jest coś, czego HTTP nie ma. Czwarty stan." (linia 495)

**Twoja własna historia ze slajdu 24 JEST czwartym stanem bez żadnego AI:** 200 OK, zielono, poprawnie sformatowany zły adres, trzy miesiące. Zero błędów transportu, sama bzdura.

**Dlaczego groźne:** slajd 24 pada **cztery minuty później**. Ktokolwiek to zestawi, ma gotową ripostę — i tracisz punkt akurat przy revealu.

**Poprawka (zamienia konflikt we wzmocnienie):**
> „I jest coś, co przy HTTP było **wypadkiem**. Tu jest **trybem pracy**. Czwarty stan."

Dopiero wtedy sala widzi, że Twoja historia i czwarty stan to ta sama choroba.

### 3.4 Pozostałe, mniejszej wagi

| # | Problem | Poprawka |
|---|---|---|
| 4 | „Bezpiecznik staje się ochroną budżetu" — mechanicznie nieprawda. CB wyzwala się na **porażkach**; pętla w 100% udana i przepalająca 2 mln tokenów nie otworzy go nigdy | „Bezpiecznik chroni przed przepalaniem **na awarii**. Przed przepalaniem **na sukcesie** chroni limiter — ten, którego dziś nie pokazałem." (domyka lukę po wyciętym RateLimiterze) |
| 5 | „System w ogóle nie zauważa" — za mocne. Deterministycznie łapie się: walidację schematu, `finish_reason=length`, odmowę modelu, a przede wszystkim **walidację wobec reguł domeny** (dokładnie to robi u Ciebie `StarportCodeAllowlist`) | Zostaw retorykę o YAML-u, dobij własną kotwicą z 12-bis: „⚓ **Konfiguracja egzekwuje decyzję — podejmuje ją kod.** Tu tak samo: YAML nie złapie. Kod może." Zamienia puste `???` w jedną konkretną odpowiedź, bez ani sekundy dłużej |
| 6 | „ręczny licznik jakości… to **jedyne**, co widzi czwarty stan" — (a) „jedyne" fałszywe; (b) most przedstawiony jako „ten sam tani licznik", a nie jest: sędzia-model to **drugie płatne wywołanie na request** | dopisz pół zdania: „…liczony **na próbce, poza ścieżką requestu**, bo ocenianie też kosztuje" |
| 7 | Semconv opisany zbyt „gotowo". Stan na lipiec 2026: **nic nie jest Stable**, wszystko w statusie Development; `gen_ai.system` → `gen_ai.provider.name` (v1.37), `prompt_tokens` → `input_tokens` (v1.27). **I korekta do własnej tezy:** istnieje już `gen_ai.evaluation.result` (semconv v1.39.0) — czyli zdanie z cheatsheetu 13b „prawdziwości treści tam nie ma" ma datę ważności liczoną w miesiącach | R2 i R3 postulują **wyciąć ze sceny w całości** (sala i tak czyta to w podtytule slajdu). Skorygować w cheatsheecie |
| 8 | „obowiązuje **jeden do jednego**" — streaming łamie mechanizm z całego AKT 3. `TradeRoutePlannerClient` mapuje typ wyjątku przez `.onStatus(is4xx→Rejected/is5xx→Unavailable)`; przy SSE **status 200 jest zacommitowany zanim cokolwiek wiadomo** — `onStatus` nie odpali się nigdy | „obowiązuje co do **wzorca**. Implementacja się rozjeżdża — i to jest osobny talk." Zyskujesz uczciwość i **mocniejszy** teaser |
| 9 | Niedeterminizm retry jest w cheatsheecie (13e), nie ma go na scenie — a to najmocniejszy argument z listy | Jedno zdanie warte więcej niż cały akapit o pieniądzach: „ponowienie modelu **zwraca inną odpowiedź**. Retry przestaje być drugą próbą tego samego. Zaczyna być losowaniem." |
| 10 | Fallback „mniejszy model / cache" **bez metryki produkuje czwarty stan** — cichy downgrade jakości to dokładnie to, przed czym slajd ostrzega | „…i musi zostawić ślad, **kto odpowiedział**." Kotwica już istnieje: `outcome="circuit_open"` |

---

## 4. Ryzyko sceniczne (R2)

| # | Sytuacja | Objaw | Ratunek |
|---|---|---|---|
| 1 | Wchodzisz na 21 o 38:40 (alert wisiał w pending) | „cięcie awaryjne" ma nadal ~1:20; reveal startuje po 42:30, moderator macha w trakcie slajdu 24 | Twardy gate: „po 38:30 gram wersję 50-sekundową" — i **przećwicz ją jako osobny tekst**, nie jako „to samo bez akapitu" |
| 2 | Ktoś przerywa pytaniem o AI (prawdopodobieństwo wysokie) | Odpowiesz jednym zdaniem → otwierasz wątek na 3 min. Utniesz „po prezentacji" → brzmi jak ucieczka przed własnym tematem | Gotowa fraza, która **płaci obietnicą zamiast odmawiać**: *„Trzymam to pytanie — mam na nie odpowiedź. Ale najpierw muszę wam oddać coś, co obiecałem czterdzieści minut temu."* Przygotowuje slajd 22 |
| 3 | Sala reaguje śmiechem na „tylko w odpowiedzi jest bzdura" (najbardziej relatable zdanie talku w 2026) | Energia skacze, a za 90 s potrzebujesz ciszy | Przyjmij reakcję 1 s, **nie dobijaj żartem**, wejdź prosto w kotwicę. Slajd 22 zagraj ciszej niż zwykle |
| 4 | Własna adrenalina — 21 to ostatnie miejsce, gdzie mówisz coś „nowego" | Przyspieszasz, zjadasz pauzy, kotwica ląduje w środku oddechu | Kartka przy laptopie: tekst kotwicy + słowo „KLIK". Nic więcej |
| 5 | **Konflikt obietnic:** 21 „może następna" + 22 ⚓ „**Ja wam niczego nie sprzedałem**" + 26 PDF + 26 opcjonalna zapowiedź serii | Trzy oferty w ostatnich 7 minutach, jedna 90 s przed „niczego nie sprzedałem". Sala tego nie nazwie, ale poczuje | Maksymalnie **jedna** zapowiedź serii w całym talku. Skoro ma ją 21 — usuń linię ze slajdu 26 **na stałe**, nie warunkowo |
| 6 | **21 to jedyny slajd w talku bez dowodu na ekranie** — w talku, którego tezą jest „zmierz". Na 18 mówisz ⚓ „gdybym oparł demo na założeniu, nie zadziałałoby". Cztery minuty później: 2:40 samych założeń | Dociekliwy podniesie to w Q&A | Wstaw **jeden twardy fakt** zamiast zdania o semconv: *„Sprawdziłem defaulty Spring AI: dziesięć ponowień. Dziesięć płatnych prób bez jednej świadomej decyzji."* 8 sekund (treść: cheatsheet 13e) |

**Inflacja kotwic:** AKT 5 ma ⚓ na 21 (×2), 22, 23, 24, 25 — **sześć „zapamiętajcie to" w siedem minut**. Kotwica działa przez rzadkość. Na slajdzie 21 zostaw **wyłącznie** „Tam »działa«… Tu »odpowiada«…" i uczyń ją **ostatnim zdaniem slajdu**.

---

## 5. Redakcja tekstu mówionego (R2 + R1)

| Fraza w runbooku | Problem | Propozycja |
|---|---|---|
| „Co się zmieniło? *(cisza)* **Nic.**" | Mocne, ale własny cheatsheet zaprzecza w trzech miejscach (13b/13d/13e). Wersja mocna idzie do 200 osób, korekta do trzech, które zapytają | „**Wszystkie wzorce zostają. Wszystkie liczby trzeba przeliczyć.**" — nic nie tracisz dramaturgicznie, przestajesz sprzedawać uproszczenie |
| „przy limicie dostajecie **czterysta dwadzieścia dziewięć** z Retry-After — dokładnie ten kontrakt… przy **pięćset trzy**" | 28 słów na oddechu; **dwie liczby mówione**, jedną sala musi z pamięci zmapować na drugą. Liczby wypowiadane słownie to najtrudniejszy materiał sceniczny | **Wytnij ze sceny.** Skorygowaną wersję (pkt 3.1) trzymaj w cheatsheecie |
| „Zresztą branża już to standaryzuje — OpenTelemetry ma dziś GenAI semantic conventions…" | 24 słowa, angielski termin w polskim zdaniu, **„Zresztą" drugi raz na tym samym slajdzie** (tik niewidoczny na papierze, słyszalny na scenie). To i tak jest podtytuł slajdu — mówisz to, co sala właśnie przeczytała | **Nie mów wcale.** Zwolnione ~12 s oddaj pauzie po kotwicy |
| „to już nie jest »jedna miła metryka domenowa«" | Cudzysłów ironiczny jest **niesłyszalny**. Sala usłyszy dosłownie | „Przy modelu ten ręczny licznik przestaje być dodatkiem." |
| „warsztat pod… zależność, jaką wasze serwisy **zaczną** wołać" | „warsztat pod zależność" — kolokacja książkowa. Czas przyszły osłabia: w 2026 sala już ją woła | „To warsztat pod najdroższą zależność, jaką **dziś macie** w systemie." |
| Akapit o koszcie (489–494), 74 słowa | Najgęstszy fragment: 3 nowe pojęcia w 25 s, bez ani jednej realnej kotwicy | Zostaw **jedno** zdanie: „Retry to nie darmowa druga próba. To drugi rachunek." Reszta → Q&A 13e |
| „A co się stanie, gdy model naprawdę zamilknie… może następna" | Po najsilniejszym zdaniu slajdu następuje słabsze, i to w formie oferty | Celuj w `???`: „A co zrobić z tym czerwonym znakiem zapytania… *(pauza, pół uśmiechu)* — to na inny raz." |

**Test kartki (R2):** przeczytaj slajd 21 na głos ze stoperem, bez patrzenia w tekst, dwa razy pod rząd. Jeśli za drugim razem trwa krócej o >15 s — masz go **za długi**, nie „za wolno zagrany".

---

## 6. Cykl Kolba — luka, którą panel uznał za najważniejszą (R3)

**AKT 5 nie oddaje uczniowi pałeczki. Cykl kończy się bez aktywnego eksperymentowania, a materiał, który by je dał, jest na liście cięć.**

Runbook (sekcja 0) mapuje AKT 4 na „Zastosowanie". To nadużycie terminologiczne: w AKT 4 *prelegent* pokazuje kod i odpala demo, sala patrzy. To dalej konkretne doświadczenie z drugiej ręki, nie AE — bo AE u Kolba jest z definicji fazą **ucznia w jego własnym kontekście**. W 45 minutach nie da się przeprowadzić AE na sali — da się i trzeba **zaprojektować przekazanie pałeczki**.

**Bilans budżetu AKT 5:**

| Co | Czas | Status cięcia | Czy sala może to zrobić w poniedziałek |
|---|---|---|---|
| Slajd 21 (AI) | 2:00–2:15 | slajd zostaje | **NIE** (jawnie: brak rozwiązania) |
| Slajd 22 „most do checklisty" | 0:15 | **„tnij drugi"** (sekcja 7) | TAK — jedyny taki punkt |
| „praca domowa" o limicie | ~5 s | warunkowa | TAK, ale zawieszona w próżni (RL wycięty) |
| Slajd 26 wskazanie PDF | ~15 s | „gdy pytania przygasają" | TAK, ale poza 45:00 |

> **Talk płaci 9× więcej czasu za temat, na który uczeń nie ma żadnego ruchu, niż za jedyny ruch, jaki mu zostawia.**

### Naprawa — trzy ruchy, koszt netto ~0 s

1. **Slajd 21 → wersja 45–60 s.** Zostaje: „podmieńcie planner na model — wszystkie wzorce zostają" + czwarty stan + **jedna** kotwica ⚓ jako ostatnie zdanie. Wypada: koszt/tokeny, 429, mniejszy model/cache, OTel semconv. **Zysk: ~75 s.**

2. **Nowy slajd 22-bis „Poniedziałek" (~60 s)** — jedyny slajd w talku z listą do wykonania, trzy pozycje, każda odwołana do tego, co widzieli:
   - *Otwórz swój `application.yml`* — jaką masz kolejność aspektów? (slajd 11 — „nie do obrony jest tylko nie wiedzieć")
   - *Otwórz swojego `catch`* — masz białą czy czarną listę? (slajd 12-bis)
   - *Napisz jeden test, który przybija tę odpowiedź* — i pokaż **na ekranie dwie nazwy z repo**: `ResilienceAspectOrderTest` / `ResilienceInvertedAspectOrderTest` — „różnią się dwoma intami; jak zmienisz konfigurację, jeden zapali się na czerwono i dowiesz się o tym **przed** produkcją".

   Jednym ruchem: domyka Kolba (AE), realizuje mentorski koncept „jak dobrze testować", zdejmuje najmocniejszy dowód wiarygodności z Q&A na scenę.

3. **Jedna wtrącona klauzula w slajdzie 24 (0 s, nie rusza dramaturgii).** Zamiast „*wszystko na zielono, testy przeszły*" → „*wszystko na zielono, testy przeszły — bo sprawdzały mój kod, nie to, z czym rozmawia.*" Sześć słów zamienia jedyny **żywy obraz testowania** w talku z antytestowego w protestowy i retrospektywnie uzasadnia punkt 3 ze slajdu 22-bis.

### Dodatkowo: klamra jako domknięcie AI-teasera (za darmo)

Slajd 21 nie może odwołać się do historii (jest tajna), ale **25 może odwołać się do czwartego stanu** — i tego nie robi. Po „chodzi o to, żeby psucie było widać" dopisz:
> „…a niektóre awarie nie mają nawet koloru. Te trzeba umieć zmierzyć samemu."

Wtedy 21 przestaje być dygresją, a staje się **setupem klamry**, i cały AKT 5 dostaje jeden łuk zamiast dwóch.

### Uczciwość dydaktyczna cliffhangera — jedno zastrzeżenie R3

Kryterium: cliffhanger uczy, gdy uczeń wychodzi z (a) **nazwą** problemu, (b) **diagnostyką** („jak rozpoznam, że mam to u siebie"), (c) świadomością, że nierozwiązanie jest cechą świata, nie prelegenta.

Slajd 21: (a) TAK — „czwarty stan" to używalna etykieta i najlepszy transfer na tym slajdzie. (b) CZĘŚCIOWO. (c) obiektywnie TAK — **ale** runbook nakazuje „nie zbieraj reakcji sali", a pułapka 6.8 odsyła każde pytanie o AI do Q&A.

> Otwarte pytanie, o które nie wolno zapytać w sali, przestaje być otwartym pytaniem — staje się **zarządzanym efektem**. Jeśli slajd musi być poddany kwarantannie, żeby nie uszkodzić talku, to jest argument za jego **skróceniem**, nie za zakneblowaniem sali.

---

## 7. Czy sygnał mentora został dobrze zinterpretowany?

Mentor napisał trzy rzeczy: (1) „Super analiza", (2) „ludzi bardziej interesuje AI niż Spring Boot", (3) listę wymagań warsztatu: *jak dobrze testować · jak działają systemy rozproszone · observability jak zrobić i jak działa* + profil: *proaktywny, jest wsparciem, kontestuje merytorycznie*.

**Stanowisko panelu: decyzja o *umiarze* jest dojrzała, interpretacja sygnału jest *płytka*.**

**Co jest dojrzałe i panel tego broni:** nie przebudował talku pod AI, nie dokleił demo z LLM, zakazał nazw dostawców i słowa „hype", trzymał 2 minuty, zachował tezę. Argument 8.1 pkt 3 („teza skaluje się o poziom, nie zmienia tematu") jest merytorycznie poprawny — LLM naprawdę jest tym samym bohaterem. To odporność na trend, nie uleganie mu.

**Dlaczego mimo to płytka:**

1. **Zła warstwa odpowiedzi.** „Ludzi bardziej interesuje AI" to zdanie o **uwadze publiczności**, a uwaga rozstrzyga się w tytule, abstrakcie i pierwszych dwóch minutach — **nie w 43.** Nikt nie wybiera talku ze względu na minutę 43. Odpowiedziano na pytanie o *pozycjonowanie* zmianą w *treści na końcu* — jedyne miejsce, gdzie ta zmiana nie może zadziałać.
2. **Odwrócona hierarchia.** Punkt 2 był wtrętem („ogólnie faktycznie…"). Punkt 3 był listą wymagań — i **nie ma w nim ani słowa o AI**. W punkt 2 poszło ~115 linii runbooka, sekcja D2 cheatsheetu, przebudowa pptx i runda review. W punkt 3 — zero nowej pracy.
3. **Opcja, która może nigdy nie zostać wykonana.** Runbook sam oznacza zapowiedź serii jako OPCJONALNĄ. Płacisz 2:15 z najdroższego odcinka **teraz** za opcję o niepewnej realizacji.
4. **Ryzyko „najgorszego z dwóch światów".** Dla przyciągniętego AI — 2 minuty to za mało (i jawnie bez odpowiedzi). Dla przyszłego na resilience — dygresja tuż przed puentą.

**Właściwa odpowiedź na „ludzi interesuje AI":** nie doklejka, tylko **osobne zgłoszenie CFP „Czwarty stan"** jako pełny talk. Plus — wersja radykalna, którą panel też uznaje: gdyby planner na mapie ze slajdu 3 **był** modelem i Toxiproxy psuł wywołanie modelu, cały talk byłby „AI-owy" bez jednego slajdu o AI. To jedyna wersja, w której 2 minuty AI mają sens — bo wtedy tych 2 minut nie ma.

### Pokrycie trzech konceptów mentora

| Koncept | Gdzie w talku | Poziom |
|---|---|---|
| **(a) jak dobrze testować** | slajd 11-bis (blok **warunkowy**) · 12-bis (pół zdania) · 24: „testy przeszły" jako **sprawca fałszywego spokoju** · 18 „pomiar, który mnie zaskoczył" (hipoteza → pomiar → falsyfikacja — najlepsza treść testowa w talku, oznaczona jako *opcjonalna* i **pierwsza do cięcia**) · `ResilienceAspectOrderTest` tylko w Q&A pyt. 7 | **BRAK na scenie.** ≤2 zdania, wszystkie warunkowe albo cięte. Jedyny **żywy obraz** testowania jest negatywny |
| **(b) jak działają systemy rozproszone** | cały AKT 1 (sala *doświadcza* awarii częściowej, zanim ktokolwiek ją nazwie) · slajd 9 fallacies · 10 drabina progów · 11 arytmetyka kompozycji · 16 semantyka porażki przez granicę · 18 pula keep-alive vs myślenie per-request | **UCZONE**, w najsilniejszej formie (doświadczenie przed nazwą). Luki: kaskada przez >1 skok *twierdzona, nie pokazana*; brak backpressure; **brak idempotencji** — talk uczy „ponawiaj to, co mogło się udać", ale nigdy „czy tę operację **wolno** powtórzyć" |
| **(c) observability** | 13–15 Micrometer · 17 dwie publiczności · 18 Observation → metryka+trace · AKT 1 i 4 na Grafanie · alert FIRING na żywo | **UCZONE dla metryk** — mocno. **Trace'y: tylko WSPOMNIANE** (ani jednego spanu na ekranie, choć repo ma pełny pipeline Tempo/OTLP i ADR-0017/0033/0037). **Logi: BRAK** — pojawiają się wyłącznie jako *problem* („utopione w tysiącach linii szumu"), a rozwiązanie (log skorelowany po `traceId`, ADR-0034/0035/0036) istnieje w repo i **nie zostaje zaoferowane**. Stawiasz historię o zgubionym błędzie w logach i nie mówisz, jak było go znaleźć |

**Talk pokrywa 1,5 z 3 konceptów mentora. AI-teaser nie dotyka żadnego.**

---

## 8. Trzy warianty slotu AI (R2) — rekomendacja panelu

**Wariant A — cięcie do 50–60 s, slot 21 zostaje** ← **rekomendowany, 3/3**
Zostaje: podmiana URL-a → „wszystkie wzorce zostają, wszystkie liczby trzeba przeliczyć" → czwarty stan („zielono, płasko, szybko — tylko w odpowiedzi bzdura") → jedna kotwica jako zamknięcie. Wypada: 429/503, semconv, akapit o liczniku jakości, druga kotwica.
- **Kiedy grać:** zawsze. To wersja domyślna, nie awaryjna.
- **+** AKT 5 wraca do realnego 7:20, reveal odzyskuje pauzy, slajd nie otwiera pętli, zostaje najlepsza linijka.
- **−** Tracisz elegancki łuk 429↔503 i argument „$". Oba idą do Q&A, gdzie i tak padną.

**Wariant B — jednozdaniowy callback w AKT 3 + 30 s w slocie 21**
Na slajdzie 12 („Retry ≠ 4xx"), przy „nie jest za darmo", dokładasz: *„…a jeśli po drugiej stronie stoi model — każde ponowienie ma cenę na fakturze."* Slot 21 kurczy się do 30 s.
- **+** AI przestaje być doklejką — pojawia się **w miejscu merytorycznie własnym**. Najlepszy stosunek efektu do minut.
- **−** Ryzyko pytania o AI **w minucie 25**, gdzie masz mniej miejsca na unik niż w 40. Wymaga przećwiczenia slajdu 12 od nowa.

**Wariant C — AI wyłącznie w Q&A**
- **+** Zero ryzyka dla revealu; rozmowa na pełnej głębokości z tymi, którzy jej chcą.
- **−** Talk kończy się bez horyzontu i traci otwarcie serii — czyli dokładnie to, co mentor uznał za wartość. Sala 2026 nie usłyszy ani słowa o AI; to jest dziś zauważalne **przez brak**.

*(Odrzucony: AI jako rama otwarcia na slajdzie 2–3 — skaziłoby demo-ślepe, sala szukałaby modelu na wykresach zamiast patrzeć na to, co się psuje.)*

---

## 9. Lista zmian do runbooka — do wykonania przed wystąpieniem

**Priorytet 1 (błędy merytoryczne — nawet jeśli treść schodzi ze sceny, popraw w cheatsheecie i na slajdzie rezerwowym):**
- [ ] linia 710 (szkic 8.6): „te same sekundy" → „**dwa** timeouty (pierwszy token / cisza między tokenami)"
- [ ] linie 491–493 + tabela 8.4 wiersz „429": przepisać 429 jako **pułapkę**, nie jako „ten sam kontrakt"
- [ ] linia 495: „czego HTTP nie ma" → „co przy HTTP było **wypadkiem**, tu jest **trybem pracy**"
- [ ] linie 490–491: „bezpiecznik = ochrona budżetu" → „na awarii bezpiecznik, na sukcesie limiter"
- [ ] linie 504–506: skorygować status semconv (Development, nie Stable; `gen_ai.provider.name`; istnieje `gen_ai.evaluation.result`)

**Priorytet 2 (skrócenie — realizacja wariantu A):**
- [ ] Slajd 21 przepisać jako **osobny tekst 50–60 s** (nie „to samo bez akapitu") i przećwiczyć ze stoperem
- [ ] Zostawić **jedną** kotwicę ⚓, jako ostatnie zdanie slajdu
- [ ] „Nic." → „Wszystkie wzorce zostają. Wszystkie liczby trzeba przeliczyć."
- [ ] Ostatnie zdanie: celuje w `???`, nie w „gdy model zamilknie"
- [ ] Sekcja 8.4: dopisać uczciwą kolumnę „nowe pojęcie TAK/NIE" — deklaracja „zero nowych pojęć" jest nieprawdziwa

**Priorytet 3 (domknięcie Kolba):**
- [ ] Nowy slajd **22-bis „Poniedziałek"** (~60 s, z uwolnionych 75 s)
- [ ] Slajd 24: dopisać „…bo sprawdzały mój kod, nie to, z czym rozmawia"
- [ ] Slajd 25: dopisać „…a niektóre awarie nie mają nawet koloru"
- [ ] Slajd 26: **usunąć** opcjonalną zapowiedź serii (jedna oferta na talk)
- [ ] Sekcja 1: dopisać slajd 26 do tabeli budżetu (dziś go w niej nie ma) i przeliczyć AKT 5 na realne wartości

---

## 10. Podsumowanie panelu w trzech zdaniach

1. **Decyzja jest dobra:** AI jako uogólnienie tezy, w slocie 21, na 2 minuty, bez demo i bez nazw dostawców — to dojrzała odpowiedź na sygnał mentora, nie uleganie trendowi.
2. **Wykonanie jest za ciężkie:** 10 nowych pojęć w 135 s na 43. minucie, trzy zdania nie do obrony merytorycznie i przekroczenie budżetu AKT 5 o ~1:30 — które zapłacą pauzy revealu.
3. **Właściwą odpowiedzią na „ludzi interesuje AI" jest osobny talk, nie osobny slajd** — a te 75 s, które odzyskasz na cięciu, wydaj na to, czego mentor naprawdę wymienił na liście: **jak dobrze testować**.

---
---

# CZĘŚĆ II — Jak rozwijać aplikację, żeby wpasować się w profil seniora

> Profil z listy mentora: **„jak dobrze testować · jak działają systemy rozproszone · observability jak zrobić i jak to działa · jest proaktywny, jest wsparciem, kontestuje moje sugestie merytorycznie"**

Poniżej **zweryfikowane** ustalenia panelu o stanie repo (sprawdzone bezpośrednio w `git`, nie przyjęte na słowo) i roadmapa. Legenda: **T** = testowanie · **D** = systemy rozproszone · **O** = observability · **AI** = materiał na następny talk.

## Diagnoza — cztery ustalenia o stanie repo

Trzy potwierdzone w repo bez zastrzeżeń (#2, #3, #4). Zarzut #1 **zweryfikowany negatywnie** i przeformułowany — panel oparł go na diffie między rozjechanymi gałęziami i wyciągnął z niego wniosek o zdarzeniu, którego nie było. Zostawiam korektę jawnie, bo to jest dokładnie ten błąd, przed którym ostrzega slajd 18: *„nie ufaj temu, co powinno się dziać — zmierz"*.

1. **Branch `demo`, z którego grasz talk o widoczności, NIE MA testów obserwowalności, które są na `master`.**

   > **Korekta wobec pierwszej wersji tego dokumentu.** Panel zgłosił to jako „`demo` **kasuje** testy obserwowalności", opierając się na `git diff --stat master..demo` (`PlanRouteServiceObservabilityTest.java` −60/0, `EventPipelineObservabilityTest.java` −50/0, `PlanRouteServiceTest.java` −25). Sprawdzenie topologii branchy pokazuje, że **to nie jest usunięcie**:
   >
   > ```
   > 0d8fcfd ──┬── 8769d4e ── 0249fe3          [master]   ← tu powstał PlanRouteServiceObservabilityTest
   >           │        └───── 8a1a757          [worktree] ← tu powstał EventPipelineObservabilityTest
   >           └── 87f988f                      [demo]
   > ```
   >
   > `demo` odgałęził się w `0d8fcfd`, **zanim** te testy w ogóle napisano. Diff między rozjechanymi gałęziami pokazuje je jako „usunięte" wyłącznie z powodu kierunku porównania. Nikt nic nie skasował.

   **Zarzut w wersji poprawionej — nadal realny, ale inny:** gałąź, z której grasz talk o widoczności, jest **cofnięta o dwa commity względem `master`** i nie zawiera testów obserwowalności, które gdzie indziej już istnieją. To jest problem rozjazdu gałęzi (patrz poz. 11), a nie utraty pokrycia.
2. **`ReservationServiceMetricsTest` nie ma ani jednej metody `@Test`.** Cały setup (SimpleMeterRegistry, ObservationRegistry, handlery) jest, oba ciała testów są w komentarzu blokowym, javadoc przyznaje to wprost: *„test bodies are intentionally commented out… kept as a reference pattern"*.
   > **To jest Twój czwarty stan we własnym repo:** zielono, szybko, zero błędów, treść nieprawdziwa. Nazwa obiecuje pokrycie, build to potwierdza, asercji nie ma. Nie potrzebujesz LLM-a, żeby zademonstrować tezę slajdu 21 — masz ją w `src/test`.
3. **Nie ma żadnego CI.** Brak `.github/workflows`, GitLab CI, Jenkinsfile. Zdanie „98 testów zielonych" jest twierdzeniem o jednym lokalnym uruchomieniu.
4. **ArchUnit jest zadeklarowany w `starport-registry` i `trade-route-planner`, ale klasę testową ma tylko `telemetry-pipeline`** (`PipesAndFiltersArchitectureTest`). ADR-0011 jest zadeklarowane i nieegzekwowane w 2/3 repo.

Do tego: **brak `TradeRoutePlannerClientTest`** — licznik `reservations.route.plan.outcome`, na którym stoi cały most ze slajdu 18 do slajdu 21, nie ma dedykowanego testu.

## Roadmapa — 12 pozycji, w kolejności wykonania

### Tydzień 1 — „przestań mieć czwarty stan we własnym repo" (T, O)

| # | Zadanie | Dlaczego to jest ruch seniora |
|---|---|---|
| 1 | **Ujednolić gałęzie: `demo` nie ma `PlanRouteServiceObservabilityTest` ani `EventPipelineObservabilityTest`, bo odgałęziła się przed ich powstaniem.** Domerguj albo świadomie zostaw i dopisz w `BRANCH-demo-zmiany-kodu.md` jedno zdanie, czym `demo` różni się od `master` | Rozjazd między gałęzią demo a resztą repo to dokładnie ten typ, który talk piętnuje. Świadoma decyzja + zapis ≠ przypadek. Patrz też poz. 11 |
| 2 | **Dokończ albo skasuj `ReservationServiceMetricsTest`** | Zielony test bez asercji jest gorszy niż brak testu — daje fałszywe poczucie pokrycia. Wzorzec do skopiowania masz gotowy w `FeeCalculatorServiceObservabilityTest` i `OutboxAppenderObservabilityTest` |
| 3 | **`TradeRoutePlannerClientTest`** na `SimpleMeterRegistry`: dokładnie **jeden** increment na wywołanie (regresja z `ANALIZA-outcome.md` §3 — podwójne liczenie przy fallbacku na `Throwable`), zamknięty zbiór 5 wartości tagu, brak tagów spoza słownika | Metryka bez testu umiera cicho przy najbliższym refaktorze. A ta zasila panel biznesowy **i** SLO |
| 4 | **`ResilienceMetricsEmissionTest`** — `MeterRegistryAssert` na `resilience4j_circuitbreaker_state`, `..._calls`, `..._slow_call_rate` i licznikach retry | Dziś **cały dashboard ze sceny jest niepokryty testem**. Bump zależności albo rename oślepia go bez jednego czerwonego builda |
| 5 | **CI: workflow `build + test`**, `failsafe` podpięty pod `verify`, PIT w profilu `mutation` na `application/`, NullAway z `WARN` → `ERROR` na modułach domenowych | Bez tego wszystkie liczby o testach są anegdotą. To jest minimum wejścia w rozmowę „jak dobrze testować" |

### Tydzień 2 — „napraw to, co talk twierdzi, a repo nie robi" (D, O)

| # | Zadanie | Dlaczego |
|---|---|---|
| 6 | **Napraw niespójną sanityzację tagu `starport` — realna bomba kardynalnościowa wbrew ADR-0030.** `StarportTagSanitizer` jest używany **tylko** w `ReservationService`. `FeeCalculatorService` taguje `reservations.fees.calculated.amount` **surowym** kodem z requestu; to samo robią `lowCardinalityKeyValue("starport", …)` w `FeeCalculatorService`, `CreateHoldReservationService`, `ConfirmReservationService` i `TradeRoutePlannerHttpAdapter`. **To jest metryka przychodowa.** + test, że nieznany kod ląduje jako `other` | Kardynalność to jedyny sposób, w jaki observability zabija produkcję. Znalezienie tego u siebie i naprawienie **przed** incydentem jest definicją proaktywności |
| 7 | **Rozdziel „próby" od „operacji".** `outcome` siedzi wewnątrz proxy Retry, więc `sum(...)` to requesty HTTP, nie rezerwacje (`ANALIZA-outcome.md` §4) — a dashboard biznesowy prezentuje to jako liczbę tras. Drugi meter na granicy operacji + `DistributionSummary` „prób na operację" | Ten sam problem co cebula #2383, tylko po stronie metryk. Materiał na slajd, nie tylko refaktor |
| 8 | **Alert na WYNIK, nie na transport.** `alerts.yml` ma dziś wyłącznie `resilience4j_circuitbreaker_state`, `_failure_rate` i retry — czyli dokładnie te sygnały, które w czwartym stanie **milczą**. Dodaj: `sum(rate(reservations_route_plan_outcome_total{outcome="success"}[5m])) / sum(rate(reservations_route_plan_outcome_total[5m])) < 0.95` | **Jedyny alert w repo, który potrafi złapać „zielono, ale źle" — bez żadnego modelu.** To jest teza slajdu 21 zademonstrowana na deterministycznej zależności |
| 9 | **Idempotencja jako reguła egzekwowalna.** Adnotacja `@IdempotentOperation` + reguła ArchUnit: `@Retry` wolno postawić **wyłącznie** na metodzie nią oznaczonej. Druga reguła (strukturalna połowa naprawy ze slajdu 12-bis): `@CircuitBreaker`/`@Retry` tylko na adapterach w pakiecie portu wychodzącego | Zamyka lukę (b) z listy mentora: talk uczy „ponawiaj to, co mogło się udać", ale nie „co **wolno** powtórzyć". ArchUnit jest już zależnością — brakuje reguł |
| 10 | **Chaos jako test w buildzie, nie sztuczka sceniczna.** `ChaosResilienceIT` (Testcontainers ma moduł Toxiproxy) pinujący trzy niezmienniki demo: `slow 600` → OPEN, `down` → 100% failure + reguła alertu zapala się, `heal` → CLOSED. Plus `promtool test rules` na `alerts.yml` | Zdanie ze slajdu 18 („nie ufaj temu, co *powinno* się dziać — zmierz") przestaje być morałem, a staje się krokiem builda. **To jest jedno zdanie, które kupuje Ci cały koncept (a) mentora** |
| 11 | **Zmerguj `demo` → `master` albo powiedz „branch demo" ze sceny.** Dziś na `master` nie ma: `ResilienceAspectOrderTest`, `RoutePlannerUnavailableException`, `RoutePlanningRejectedException`, `alerts.yml`, ADR-0039 ani Toxiproxy | Slajd 16 zakłada, że ktoś sfotografuje i wejdzie na repo. Wejdzie na `master` i nie znajdzie nic. Talk o mówieniu prawdy o tym, co masz, nie może mieć rozjazdu ze sceną |
| 12 | **Test, który złapałby „3 miesiące" — nazwany po historii.** Repo reprodukuje warunki: `BaseAcceptanceTest` kieruje wszystko na WireMock:8089, więc zły adres produkcyjny przeszedłby testy tak samo jak wtedy. (a) `DownstreamEndpointConfigurationTest` — każdy URL zależności rozwiązuje się do znanej usługi/pasuje do wzorca; (b) startupowy `HealthIndicator` na osiągalność plannera; (c) `@ConfigurationProperties` + walidacja zamiast gołego `@Value` | Zamienia najmocniejszą anegdotę talku w **artefakt**, nie morał. I daje slajdowi 24 puentę, której dziś nie ma: „…i dziś mam na to test" |

### Ścieżka AI — co musi być w repo, żeby następny talk dało się zagrać z żywym demo

Kryterium przy każdej pozycji (R2): **co konkretnie widać na ekranie w 90 sekundach.** Kolejność wg wartości scenicznej: **A → B → C → D → E.**

| | Zadanie | Co widać na ekranie |
|---|---|---|
| **A** | **Sterowalny fake modelu z czwartym trybem.** Toxiproxy psuje transport; czwartego stanu nie zrobi. Stub z trybami: `slow`, `error`, `silent`, `429 + Retry-After` **oraz `hallucinate` — 200 OK, szybko, treść deterministycznie zła.** Skrypt `demo-llm.ps1 -Chaos hallucinate`, ten sam interfejs co `demo-resilience.ps1` | Enter → 20 s ciszy → **dashboard bez jednej zmiany**. Cały następny talk w jednym kadrze. Dziś niepokazywalne |
| **B** | **Metryka jakości domenowej + panel obok transportowego.** Walidator odpowiedzi (model musi wskazać port istniejący w bazie) → licznik `outcome=valid\|invalid\|unparseable` | Dwa wykresy jeden pod drugim: górny płaski zielony („transport OK"), dolny lecący w dół („jakość"). **Jedyny dowód, że czwarty stan da się zobaczyć** — bez niego następny talk kończy się na diagnozie, jak dziś slajd 21 |
| **C** | **Adapter LLM w tej samej architekturze co planner** — port + adapter obok `TradeRoutePlannerClient`, owinięty **tymi samymi** `@Retry`/`@CircuitBreaker`/`Observation` i tym samym rozdziałem wyjątków | Zrzut lewo/prawo w formacie slajdu 16, tylko z modelem — sala rozpoznaje kształt w 3 s. Plus **trace w Tempo z `gen_ai.*` obok dzisiejszego HTTP-owego**: jeden obraz zamiast akapitu o semconv |
| **D** | **Licznik kosztu ponowień.** Tokeny × liczba prób × cennik z configu → panel „koszt ponowień w oknie 15 min" | Włączasz `-Chaos error`, retry pracuje, **rośnie słupek w złotówkach**. „Retry to drugi rachunek" przestaje być twierdzeniem, staje się pomiarem |
| **E** | **`ResponseGroundednessFilter` + test-strażnik + ADR-0040.** Masz gotowy szkielet guardraili: `ValidationFilter` → `AnomalyDetectionFilter` → `PipelineBuilder`, pilnowany przez `PipesAndFiltersArchitectureTest`. Filtr waliduje odpowiedź wobec reguł domeny (`StarportCodeAllowlist`, spójność ETA) — architektura output-validation 1:1, **za zero tokenów**. Test na wzór `ResilienceAspectOrderTest`: nieprzechodząca walidacja podbija metrykę jakości i **nie** otwiera bezpiecznika (transport był zdrowy); fallback wchodzi wyłącznie na `CallNotPermitted` | Nazwa testu na zielono + trzy linijki ADR-a. Dziś na „macie na to dowód?" odpowiadasz ADR-0039 i dwoma testami — następny talk musi mieć swój odpowiednik |

**ADR-0040 „wywołanie GenAI jako zależność zewnętrzna"** — treść: dwa timeouty (TTFT + idle), 429 **poza** `record-exceptions`, budżet tokenów jako `Counter(model, token.type)` (semconv `gen_ai.client.token.usage` to histogram — do rachunku nie nadaje się wprost), `prompt_version` **wyłącznie jako atrybut spanu** (rośnie z każdym deployem → nieograniczony w czasie → zakaz jako label), `reason` od sędziego jako atrybut spanu (bywa tekstem = śmierć bazy metryk), rozkład score 0..1 jako `DistributionSummary` z histogramem, **nigdy Gauge z „aktualną jakością"** (last-value, nie agreguje się między replikami, supplier odpala się przy każdym scrape × każda replika), semconv `gen_ai.*` w statusie Development → własna nazwa metryki + mapowanie.

> Przy 39 istniejących ADR-ach to Twoja **najmocniejsza karta pod profil „proaktywny, kontestuje merytorycznie"** — ADR jest dowodem, że decyzja była świadoma, a nie że tak wyszło.

**Bonus, praktycznie darmowy:** ADR-0033 jest wdrożony, `exemplars-success-error.json` działa. Podłącz exemplary do serii `outcome != "success"` — klik z wykresu wprost w trace w Tempo z pełnym request/response. Dla modelu to jedyna droga od „jakość spadła o 3%" do „zobacz **TĘ** odpowiedź". Piszesz raz, działa i dla plannera, i dla LLM-a.

## Dwa rytuały pracy — to jest właściwa odpowiedź na „kontestuje merytorycznie"

**Rytuał 1 — log decyzji z kontrargumentem.**
Każda sugestia mentora/reviewera dostaje 5-liniową notatkę:
```
sugestia → dane, które sprawdziłem → moja rekomendacja → co by mnie przekonało
```
**Ten review jest tego przykładem:** mentor powiedział „AI", dane mówią „punkt 3 listy jest ważniejszy i niepokryty", rekomendacja to slajd 22-bis.

> Odpowiedź *„zrobiłem, o co prosiłeś — a teraz pokazuję, co z tego wynika"* jest dokładnie tym profilem, o który mentor pyta. Wymiana jednego slajdu **bez** tej notatki wygląda jak wykonanie polecenia.

Umiejętność już masz: slajd 11-bis pokazuje obie strony uczciwie, cheatsheet pyt. 5 przyznaje „w produkcji wybrałbym TIME_BASED" zamiast się bronić. Brakuje wyłącznie **rytuału**.

**Rytuał 2 — żadnej liczby bez skryptu.**
Każda liczba w ADR / na slajdzie / w cheatsheecie ma odsyłacz do odtwarzalnego skryptu w `scripts/`.

Też już to robisz: odrzuciłeś przełączanie na żywo po zmierzonych ~100 s ciszy, zdjąłeś „1,67 raza" ze sceny jako zbędną precyzję, pomiarem per-connection obaliłeś własną hipotezę o 50% błędów.

> **To jest jedyna rzecz w tym repo, która jest już na poziomie senior+.** Warto ją nazwać i uczynić standardem zespołu — bo to ona odróżnia „wiem" od „słyszałem". I to jest dokładnie ten sam ruch, który sprzedajesz ze sceny na slajdzie 18.

## Mapowanie roadmapy na listę mentora

| Koncept mentora | Pozycje roadmapy | Stan po wykonaniu |
|---|---|---|
| **jak dobrze testować** | 2, 3, 4, 5, 9, 10, 12, E | z **BRAK** → mutation testing + ArchUnit egzekwujący architekturę + chaos w CI + test przybijający własną awarię sprzed lat |
| **jak działają systemy rozproszone** | 7, 9, 10, 12, C | z **UCZONE, z lukami** → idempotencja jako reguła, kaskada pokazana a nie twierdzona, konfiguracja walidowana na starcie |
| **observability: jak zrobić i jak działa** | 1, 3, 4, 6, 8, B, D, bonus | z **metryki mocno / trace wspomniane / logi BRAK** → alert na wynik zamiast na transport, kardynalność pilnowana testem, exemplary jako most metryka→trace |
| **proaktywny · wsparcie · kontestuje merytorycznie** | rytuał 1, rytuał 2, ADR-0040, ten dokument | Z „wykonuję sugestie" → „sprawdzam dane, rekomenduję, mówię co by mnie przekonało" |

## Jedno zdanie na koniec

Nie potrzebujesz LLM-a, żeby zrobić następny talk o czwartym stanie — **masz go w `src/test` (test metryk bez ani jednej asercji) i w `alerts.yml`, który patrzy na transport zamiast na wynik.** Napraw to najpierw. Wtedy zdanie ze slajdu 21 „może następna" przestanie być wekslem bez pokrycia.
