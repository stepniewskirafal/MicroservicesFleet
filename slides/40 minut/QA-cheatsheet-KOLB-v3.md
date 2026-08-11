# Q&A — ściąga na trudne pytania · WERSJA KOLB v3 (45 min)

Krótkie, gotowe odpowiedzi. Każda osadzona w realnej konfiguracji demo
(`starport-registry → trade-route-planner`). Format: **pytanie → 1 zdanie pointy → uzasadnienie**.

> **Zgrane z** `RUNBOOK-scena-KOLB-45min.md` (**v5**) **i** deckiem `Działanierównasiędziała_KOLB_v4.pptx` (slot 21 = „Czwarty stan" (AI); stary slajd SB4 jako rezerwowy za Q&A; v3 zostaje jako backup).
> Tam, gdzie warto pokazać ekran w trakcie odpowiedzi, dopisana jest slajd-kotwica (**→ slajd N**).
> Głębsze rozwinięcie każdego tematu: `WYKLAD-resilience-mid-java.pdf` (26 stron) — możesz je bez wstydu podać jako follow-up.
> **Dla sekcji D2 (AI) drugim materiałem jest** `Observability_w_AI_od_juniora_do_seniora.pdf` (37 stron); pełne zaplecze merytoryczne: **runbook, sekcja 9**.
>
> **Zasada nadrzędna na Q&A:** jeśli ktoś przed slajdem 24 zapyta „a zdarzyło się to Panu?" — **NIE odpowiadaj** (pyt. 18).

---

## A. Wzorce i konfiguracja

### 1. „Po co circuit breaker, skoro mam timeout?"
**Timeout chroni JEDEN request, circuit breaker chroni SYSTEM.**
Timeout (u mnie connect 200 ms, read 800 ms w demie / 2000 ms bazowo) ucina pojedyncze wiszące wywołanie. Ale jeśli zależność leży, **każdy** request i tak czeka pełne 800 ms, zżera zasoby i dobija padający serwis. Breaker po przekroczeniu progu przestaje w ogóle pukać — odmowa w mikrosekundach. Timeout to warunek *wykrycia* pojedynczego błędu; breaker to reakcja na *wzorzec* błędów. Działają razem, nie zamiennie. *(→ slajd 10)*

---

### 2. „Dlaczego breaker otworzył się, skoro licznik błędów pokazywał zero?"
**Bo liczy dwie rzeczy: procent błędów ORAZ procent wywołań wolniejszych niż pół sekundy.**
`slow-call-duration-threshold: 500ms` + `slow-call-rate-threshold: 50`. Wywołanie, które trwa 600 ms, **kończy się sukcesem** — jest pod read-timeoutem 800 ms — ale zostaje zapisane jako „wolne". To jedyny mechanizm w tym zestawie, który łapie awarię typu „niby działa": dashboard zielony, SLA zielone, a klient czeka. Licznik 5xx nigdy tego nie zobaczy. *(→ slajd 10, kotwica z AKT 1)*

---

### 3. „Czemu nie użyliście `@TimeLimiter` z Resilience4j?"
**Bo wymaga sygnatury zwracającej `CompletionStage` — a na naszej wysadziłby aplikację w runtime.**
`TimeLimiterAspect` sprawdza **typ zwracany adnotowanej metody**; dla `Route` rzuca `IllegalReturnTypeException` („*has unsupported by @TimeLimiter return type. CompletionStage expected*") przy każdym wywołaniu. Kompiluje się, więc to mina rozbrajana dopiero na produkcji, a nie martwa adnotacja.

**Uwaga na subtelność** (ktoś dociekliwy to podniesie): to wywołanie **już wykonuje się asynchronicznie** — `ReservationCalculationService` odpala je przez `CompletableFuture.supplyAsync(…, VIRTUAL_EXECUTOR)` równolegle z naliczaniem opłaty. Ale dla R4j „asynchroniczny" znaczy „sygnatura zwraca `CompletionStage`", a nie „biegnie poza wątkiem wołającego". Żeby `@TimeLimiter` zadziałał, port musiałby zwracać `CompletableFuture<Route>` (i fallback też).

**I nawet wtedy bym tego nie zrobił:** anulowanie future po limicie **nie przerywa blokującego I/O** — `CompletableFuture.cancel(true)` ignoruje flagę przerwania, więc wołający dostałby `TimeoutException`, a wątek dalej wisiałby na gnieździe do read-timeoutu. Limit byłby pozorny. Read-timeout `RestClient`-a robi to samo lepiej: realnie zrywa odczyt i zwalnia wątek. *(→ pełne porównanie: wykład, rozdz. 3)*

---

### 4. „Dlaczego `failure_rate` pokazuje −1?"
**To nie błąd — to „za mało danych, żeby mieć zdanie".**
Breaker liczy dopiero po `minimum-number-of-calls: 5` w oknie `sliding-window-size: 10`. Wcześniej zwraca `-1`, żeby nie otwierać obwodu na podstawie jednego pecha. Dashboard mapuje `-1 → "—"`. Dlatego przed demem robię warm-up ruchem — inaczej pierwszy kafelek pokazuje myślnik zamiast zera.

---

### 5. „Okno licznikowe 10 wywołań? Przy niskim ruchu to pamięta prehistorię."
**Słuszna uwaga — i tak, w produkcji wybrałbym `TIME_BASED`.**
Przy `COUNT_BASED(10)` na niskim ruchu okno faktycznie pamięta wywołania sprzed godzin, a przy wysokim reaguje na ostatnie kilkaset milisekund. Dla zależności HTTP okno **czasowe** (np. 60 s) jest niemal zawsze lepsze. Zostawiłem licznikowe świadomie: cała choreografia demo jest pod nie skalibrowana i chciałem, żeby na scenie przejście progu było przewidywalne co do wywołania. Zamiana jest w backlogu — i to jest uczciwa odpowiedź, nie wymówka.

---

## B. Kolejność aspektów — najczęstszy temat po slajdzie 11

### 6. „To którą kolejność wy właściwie macie?"
**Domyślną — Retry na zewnątrz. Świadomie, i wiem, co to znaczy.**
Bo taką ma każdy, kto jej celowo nie zmienił, a liczenie **próbami** daje wyższą czułość: breaker reaguje szybciej na twardą awarię, a retry z backoffem potrafi przeczekać krótkie otwarcie. Cena: próg 50% znaczy „50% prób", nie „50% operacji", i jego realna wartość przesuwa się przy każdej zmianie `max-attempts`. Odwrócenie mam pod profilem `cb-outer` — dwie liczby, żadnej zmiany w kodzie — i pokazuję je na żywo, bo różnicę widać na dashboardzie. ⚓ **Żadna z tych wersji nie jest zła. Nie do obrony jest tylko nie wiedzieć, którą się ma.** *(→ slajd 11, demo AKT 4 krok 2)*

---

### 7. „Macie na to dowód, czy to teoria z issue?"
**Mam dwa testy różniące się dokładnie dwoma intami — i pokazuję to na żywo na wykresach.**
`ResilienceAspectOrderTest` (baza, domyślna cebula) i `ResilienceInvertedAspectOrderTest` (`@ActiveProfiles("cb-outer")`). Ten sam chwiejny planner (co drugi request 503, każdą operację ratuje retry), ta sama seria trzech operacji. **Domyślnie:** breaker otwiera się w połowie trzeciej operacji (piąty wpis = 3 porażki na 5 = 60%) i **odrzuca retry tej samej operacji, którą retry właśnie ratował** — 5 requestów na drucie, klient dostaje błąd od zależności, która odpowiedziała poprawnie na każdą operację. **Pod `cb-outer`:** 3 sukcesy, breaker CLOSED, 6 requestów.
Na demie to samo widać bez testów: przełączam profil jedną komendą `docker compose`, chaos leci dalej, i **ten sam planner ma dwa różne obrazy zdrowia**.

---

### 8. „Przecież wystarczy przestawić adnotacje w kodzie."
**Nie wystarczy — i to jest pułapka w pułapce.**
Kolejność jest zaszyta w wartościach `Ordered` aspektów, nie w kolejności zapisu adnotacji nad metodą. Możesz je przestawiać do woli, nic się nie zmieni. Zmieniają ją wyłącznie właściwości: `circuit-breaker-aspect-order: 1`, `retry-aspect-order: 2` (niższa wartość = bardziej na zewnątrz). To dlatego mam na to test — pomyłka w tych dwóch liczbach jest **całkowicie bezobjawowa** aż do pierwszej poważnej awarii.

---

## C. Wyjątki i kontrakt HTTP

### 9. „Czemu 503, a nie 409, skoro wcześniej było 409?"
**Bo 409 mówi klientowi »zmień żądanie«, a przy awarii plannera żądanie jest w porządku.**
Rozdzieliliśmy jeden wyjątek na dwa: `RoutePlanningRejectedException` (4xx — planner odmówił zaplanowania tej trasy) → **409**, oraz `RoutePlannerUnavailableException` (5xx / timeout / otwarty obwód) → **503 + `Retry-After: 10`**. Dziesięć sekund to `wait-duration-in-open-state`, czyli najwcześniejszy moment, w którym obwód może się domknąć. Kod statusu to **instrukcja dla automatu po drugiej stronie** — klient z sensowną polityką retry zobaczy 409 i przestanie próbować, a właśnie próbowanie byłoby tu poprawne.

---

### 10. „Po co nowe typy wyjątków? Nie wystarczyłoby pole `kind` w jednym?"
**Nie — bo breaker i retry filtrują po KLASIE, nie po polach.**
`ignore-exceptions` i `retry-exceptions` w Resilience4j dopasowują przez `isAssignableFrom`. Ta sama zasada rządzi metodą fallbackową (dopasowanie po typie parametru) i rdzeniowym `@Retryable` w Springu 7. Dlatego typ wyjątku to nie szczegół implementacji — to zdanie, które twój kod mówi całej reszcie systemu. Sztuczka, która uczyniła tę zmianę bezpieczną: oba nowe typy **dziedziczą po starym** `RouteUnavailableException`, więc istniejące `catch`e i stary handler 409 działają bez zmiany. *(→ slajd 16)*

---

### 10b. „A gdyby ktoś wpisał `RuntimeException` do `ignore-exceptions`?"
**Oślepiłby breaker całkowicie — i to jest najgroźniejsza literówka w tym pliku.**
Dopasowanie po `isAssignableFrom` obejmuje wszystkie podklasy, a w aplikacji Springowej **praktycznie każdy wyjątek docierający do breakera jest `RuntimeException`** — Spring opakowuje nawet `SocketTimeoutException` w niekontrolowany `ResourceAccessException`. Nasz `RoutePlannerUnavailableException` też nim jest. Efekt: okno nigdy nie zapisuje porażki, `failure_rate` stoi na zerze, obwód nie otworzy się **nigdy**. Bezpiecznik istnieje, jest skonfigurowany, ma metryki — i nie zadziała. To gorsze niż jego brak, bo brak widać w kodzie. Wykluczaj najwęższą klasę opisującą dokładnie jedno zjawisko. *(→ slajd 12)*

---

### 10c. „A co z waszymi własnymi błędami? NPE też otwiera obwód?"
**Nie — i to była realna wada, którą naprawiliśmy białą listą.**
Bezpiecznik istnieje, żeby odpowiadać na jedno pytanie: **czy tamten serwis jest zdrowy**. `NullPointerException` z naszego mapowania nie mówi o nim nic — do niego w ogóle nie dojechaliśmy. Mieliśmy czarną listę (`ignore-exceptions` z jednym typem, resztę licz), więc nasz bug liczył się jako awaria plannera: pięć wywołań i **obwód odcinał serwis w idealnym stanie**, klient dostawał 503 „wróć za chwilę" na deterministyczny błąd, a alert wskazywał nie ten serwis. Teraz jest `record-exceptions` z jednym typem — `RoutePlannerUnavailableException`.

**Jeśli ktoś zna wewnętrzności R4j i dopyta:** `CircuitBreakerStateMachine#handleThrowable` ma trzy gałęzie — pasuje do `ignore` → wywołanie nie trafia do okna; pasuje do `record` → porażka; **żadne z powyższych → sukces**. Nasze bugi lądują w trzeciej, czyli liczą się jako sukces. Niedoskonałe (zawyżają licznik udanych), ale prawdziwe: *na tyle, na ile ten bezpiecznik wie, zależność nie zawiodła*. Strukturalna naprawa to zawężenie zasięgu adnotacji do samego wywołania zdalnego — dziś `@CircuitBreaker` obejmuje też walidację i budowę żądania. W backlogu. ⚓ **Zakres adnotacji jest częścią kontraktu: bezpiecznik mierzy dokładnie to, co obejmuje.**

---

## D. Spring Boot 4 / Framework 7 — zeszło ze sceny w v4; slajd rezerwowy za Q&A

### 11. „Skoro Boot 4 ma `@Retryable` w rdzeniu, to po co mi Resilience4j?"
**Bo w rdzeniu nie ma circuit breakera.**
Framework 7 wnosi `@Retryable`, `@ConcurrencyLimit`, `@EnableResilientMethods` i `RetryTemplate` — realne, dobre rzeczy. Ale nie ma tam breakera, slow-call detection, deklaratywnego fallbacku ani gotowych metryk stanu obwodu. Po nie nadal idziesz do R4j — a wtedy **składasz aspekty z dwóch bibliotek na jednej metodzie** i pytanie o kolejność wraca w wersji trudniejszej, bo trzeba ją uzgodnić między frameworkami. ⚓ Zmienia się dostawca adnotacji. Cebula zostaje.

---

### 12. „Przepiszę waszą konfigurację na `@Retryable` jeden do jednego. Coś mnie zaskoczy?"
**Tak — `maxAttempts` znaczy tam co innego.**
W Spring Framework 7 `maxAttempts` liczy **wyłącznie ponowienia**; pierwsze wywołanie się nie liczy (domyślnie 3 ponowienia = 4 wywołania). W Resilience4j i w starym Spring Retry `maxAttempts = 2` znaczy „pierwsza próba + jedno ponowienie". Dosłowne przepisanie mojego `max-attempts: 2` do `@Retryable(maxAttempts = 2)` daje **trzy wywołania zamiast dwóch — czyli +50% ruchu do zależności**, bez świadomej decyzji. Druga, cichsza pułapka: jitter. U mnie `randomized-wait-factor: 0.5` jest **względny** (±50% odstępu), w Springu `jitter = 10` jest **bezwzględny w milisekundach** — przepisanie `0.5` da pół milisekundy rozrzutu przy zachowanej nazwie parametru.

---

### 13. „`@ConcurrencyLimit` zastąpi mi Bulkhead z R4j?"
**Nie do końca — on blokuje, a nie odrzuca.**
`@ConcurrencyLimit` opiera się na `ConcurrencyThrottleInterceptor` (obecnym w Springu od 1.0): przy pełnym limicie wątki **czekają w kolejce**. Semaforowy `Bulkhead` z R4j ustawiony na `max-wait-duration: 0` robi coś innego — natychmiastową odmowę. Pod przeciążeniem różnica jest fundamentalna: blokowanie daje rosnącą latencję i kolejkę, fail-fast daje szybkie odmowy i stabilny czas odpowiedzi dla tych, którzy weszli. Ciekawostka: dokumentacja Springa uzasadnia tę adnotację tym, że ograniczanie równoległości jest **szczególnie potrzebne przy wirtualnych wątkach, gdzie nie ma już limitu puli** — czyli runtime przestał robić to za ciebie.

---

## D2. AI / „Czwarty stan" — po slajdzie 21

> **Zasada tej sekcji:** slajd 21 świadomie **nie odpowiada** na czerwone `???` — i to jest
> uczciwe, nie wykrętne. Tu masz odpowiedzi na poziomie, na który sala zasługuje w Q&A, plus trzy
> rzeczy, których **nie wolno** powiedzieć ze sceny (429 ≠ 503 · semconv · kaskada fallbacku),
> a które w rozmowie po talku są największą wartością. Nie licytuj się na głębokość: jeśli wątek
> ciągnie się dłużej niż dwa pytania, przekieruj na drugi PDF i rozmowę po sesji.

### 13b. „To jak się bronić przed halucynacją?"
**Warstwami — od darmowych do drogich. Warstwę darmową mam w repo; drogiej jeszcze nie zbudowałem.**
Wszystko, co pokazały dzisiejsze dashboardy — failure rate, latencja, stan obwodu — patrzy na transport, a halucynacja przychodzi w poprawnym transporcie: 200 OK, szybko, zero błędów. Kierunek obrony widzieliście na slajdzie 18: jedyny licznik pisany ręcznie (`outcome`) istnieje, bo metryka, która ma znaczyć „dobrze", musi być **domenowa**, nie infrastrukturalna. Kolejność wdrażania jest odwrotna do intuicji — **zaczynasz od tego, co nic nie kosztuje**:
1. **strukturalna** — walidacja schematu, `finish_reason`, obecność wymaganych pól *(koszt zero, wąska, ale wysoce wiarygodna)*;
2. **domenowa** — odpowiedź musi wskazać byt, który **istnieje w Twojej bazie** *(koszt zero; u mnie robi to `StarportCodeAllowlist`, a `telemetry-pipeline` ma na to gotową architekturę: `ValidationFilter` → `AnomalyDetectionFilter`)*;
3. **sygnały od użytkownika** — regeneracja odpowiedzi, porzucenie sesji, eskalacja do człowieka *(koszt zero i koreluje z niezadowoleniem lepiej niż większość heurystyk)*;
4. dopiero potem **LLM-as-judge** — asynchronicznie, na próbce 1–5%, z przypiętą wersją modelu i promptu sędziego.

Nie obiecuj więcej: „jak to zrobić systemowo — evale, sędziowie, monitoring jakości — to materiał na osobną prezentację". *(→ slajd 21)*

**Jedno zastrzeżenie, które warto powiedzieć samemu, zanim ktoś je wyciągnie:** licznik `outcome` liczy to, co **kod potrafi rozstrzygnąć**. Ocena jakości modelu wymaga **oracle'a** — czegoś, co wie, jak brzmi dobra odpowiedź. To jest realna luka, nie retoryczna.

**Gdyby ktoś dopytał: „skoro OTel standaryzuje GenAI, to po co ręczny licznik?"** — bo standard porządkuje **transport** (tokeny, model, latencja, błędy), a ocenę jakości dopiero zaczyna obejmować — atrybuty na wynik ewaluacji już się w konwencjach pojawiły. **I tak zostawia Tobie decyzję, co znaczy „dobrze" w Twojej domenie** — tej jednej rzeczy żaden standard nie zdejmie, bo nie zna Twojego biznesu. *(Nie mów „prawdziwości treści tam nie ma" — to zdanie ma datę ważności liczoną w miesiącach; patrz 13g.)*

---

### 13c. „Przecież zwykły serwis też może zwrócić 200 z bzdurą. Co tu nowego?"
**Tam to bug. Tu to tryb pracy.**
W deterministycznym serwisie zła treść przy 200 OK to defekt: naprawiasz raz, dokładasz test kontraktowy — i ta klasa błędów znika. Model jest probabilistyczny: bzdura nie jest regresją do naprawienia deployem, tylko stałą cechą zależności, która jutro wróci w innym miejscu z pełną pewnością siebie. Dlatego nie łapie jej ani pipeline, ani test — musi ją łapać **runtime**: walidacja odpowiedzi i metryka jakości. Różnica między „incydent" a „tryb pracy" to cała treść czerwonego wiersza na slajdzie. *(→ slajd 21)*

---

### 13d. „To jaki timeout ustawić na wywołanie modelu?"
**Dwa, nie jeden: na pierwszy token i na ciszę między tokenami.**
Modele woła się streamingiem, a generacja legalnie trwa dziesiątki sekund — pojedynczy timeout na całość musiałby być tak długi, że przestałby chronić. Sensowny podział: krótki **time-to-first-token** („czy model w ogóle żyje" — odpowiednik naszego connect/response timeout) i **idle-timeout między tokenami** („czy nie umarł w połowie zdania"). Dzisiejsze 800 ms nie przenosi się wprost — przenosi się zasada: nie czekaj wiecznie i wiedz, na co czekasz.

---

### 13e. „Spring AI ma przecież retry wbudowane. Po co mi ta wiedza?"
**Właśnie po to — sprawdź defaulty, zanim zapłacisz za nie tokenami.**
Spring AI 1.0 ponawia domyślnie z `maxAttempts = 10` i backoffem od 2 s do 3 minut (`spring.ai.retry.*`) — dziesięć płatnych prób bez jednej świadomej decyzji; to pułapka z pyt. 12 w wersji z rachunkiem. Do tego ponowienie modelu może zwrócić **inną odpowiedź** — idempotencja znaczy tu mniej niż przy plannerze: retry przestaje być drugą próbą tego samego, a staje się **losowaniem**. Recepta ta sama co dziś: własny `max-attempts`, budżet na ponowienia i licznik prób na dashboardzie. *(Przed talkiem sprawdź defaulty w używanej wersji Spring AI — mogły się zmienić.)*

---

### 13f. „Po co mi Resilience4j, skoro idziemy w AI?" — **najważniejsze pytanie tej sekcji**
**Bo wywołanie modelu to najdroższa i najmniej przewidywalna zdalna zależność, jaką dziś macie w systemie.**
Wszystko, co zobaczyliście, jest warstwą pod spodem: timeout, ponowienie, odcięcie, tryb awaryjny, metryka wyniku. Zmienia się jedna rzecz — **liczby**. I dochodzi jedna nowa — to, że odpowiedź może być nieprawdziwa. Jeśli ktoś chce dowodu, że to nie jest naciągane pod temat: materiał o observability w AI ma osobny rozdział o Resilience4j, w którym **pułapka nr 5 to dokładnie kolejność aspektów ze slajdu jedenastego** — ta sama cebula, ten sam skutek dla znaczenia metryk. Nie musiałem tego dopasowywać; to po prostu ta sama warstwa. *(→ slajd 21; drugi PDF)*

---

### 13g. „Skoro OpenTelemetry standaryzuje GenAI, to problem sam się rozwiąże?"
**Nie — standaryzuje transport, nie prawdę. I sam jeszcze nie jest stabilny.**
Stan faktyczny, warto go znać przed decyzją architektoniczną: **nic specyficznego dla GenAI nie ma dziś statusu Stable**, konwencje zostały wydzielone do osobnego repozytorium (`semantic-conventions-genai`) bez wersjonowanych wydań, a nazwy atrybutów zmieniają się między rewizjami — `gen_ai.system` stało się `gen_ai.provider.name`, `prompt_tokens` → `input_tokens`. Praktyczna konsekwencja: **biblioteki w jednej instalacji potrafią emitować oba pokolenia naraz** — jeden filtr w PromQL i połowa ruchu znika z panelu, co wygląda jak awaria, a jest dwiema nazwami tego samego. Przypnij wersje instrumentacji i czytaj changelogi. Standard porządkuje to, co widzieliście na wykresach; **co znaczy „dobrze" w Waszej domenie, i tak definiujecie sami**.

---

### 13h. „429 to przecież to samo co Wasze 503 — mogę wrzucić do tej samej listy?"
**Nie. To jest pułapka — i to dokładnie ta, przed którą ostrzegałem na slajdzie dwunastym.**
503 znaczy „**on** jest chory — odetnij go". 429 znaczy „**on jest w idealnym stanie** — to ja przesadziłem". Wrzucicie 429 do `record-exceptions`, a bezpiecznik odetnie zdrową zależność — czyli powtórzycie mój błąd z NPE, tylko drożej. Miejsce 429 to `ignore-exceptions` **plus** backoff wynikający z nagłówka `Retry-After` (sekundy–minuty, nie moje 100 ms), a systemowo — limiter po Waszej stronie, żeby w ogóle tam nie dojechać. To jest ta rodzina wzorców, której dziś nie pokazałem. *(→ slajdy 12-bis i 16)*

---

### 13i. „To bezpiecznik ochroni mnie przed rachunkiem?"
**Nie. Bezpiecznik chroni przed przepalaniem NA AWARII. Przed przepalaniem NA SUKCESIE chroni limiter.**
Bezpiecznik wyzwala się na **porażkach** — pętla, która w stu procentach się udaje i przepala dwa miliony tokenów, **nie otworzy go nigdy**. To rozróżnienie warto mieć w głowie, zanim ktoś sprzeda Wam CB jako mechanizm kosztowy. Od pieniędzy są trzy inne rzeczy: budżet tokenów jako osobna metryka (wejście i wyjście liczone **oddzielnie**, bo mają różne stawki), limit po własnej stronie i alert na **koszt godzinowy**, nie na stan komponentu. I jedno przełożenie na dziś: liczba, która interesuje biznes, to nie „ile wydaliśmy", tylko **koszt jednej jednostki wartości** — u mnie byłby to koszt jednej zaplanowanej trasy. To dokładnie ten drugi rząd dashboardu ze slajdu 17, tylko z walutą. *(→ slajd 17)*

---

### 13j. „Ile etykiet mogę dołożyć do metryk modelu?"
**Mniej, niż się wydaje — bo liczba serii to iloczyn, nie suma.**
Cztery modele × trzy środowiska × dwanaście kubełków histogramu to już setki serii; dołóżcie `user_id` i zabijacie Prometheusa w tydzień — a wygląda to jak awaria infrastruktury, nie jak błąd w kodzie aplikacji. Podział jest ten sam co w pyt. 16, tylko stawka wyższa, bo kusi więcej wymiarów naraz: **do metryki** — model, dostawca, operacja, `feature`, wersja promptu, klasa błędu; **do trace'a** — identyfikatory konwersacji i użytkownika, liczby tokenów pojedynczego żądania; **do logu albo eventu** — treść. Plus dwa bezpieczniki w kodzie od pierwszego dnia: `MeterFilter.ignoreTags(...)` na to, co ktoś dołoży przez nieuwagę, i `maximumAllowableTags(..., deny())`, żeby nadmiar był **odrzucany zamiast zabijać klaster**. I test przed wdrożeniem, nie po: `topk(20, count by (__name__)({__name__=~".+"}))` na środowisku z realistycznym ruchem. *(→ pyt. 16)*

---

### 13k. „Czy AI zrobi observability za mnie?" — *(pytanie odwrotne: modele PO stronie narzędzi)*
**Częściowo — i nie tam, gdzie obiecują.**
Działa dobrze: wykrywanie anomalii w szeregach czasowych *(istnieje długo przed LLM-ami i ich nie wymaga)*, grupowanie podobnych błędów w logach, pisanie PromQL i LogQL z opisu po ludzku, streszczanie przebiegu incydentu z rozproszonych źródeł. Działa gorzej, niż obiecują materiały marketingowe: **automatyczne ustalanie przyczyny źródłowej** — model wskaże, co zmieniło się *razem* z awarią, ale korelacja nie jest przyczyną, a systemy rozproszone są pełne zmian współbieżnych. Generator hipotez, nie wyrocznia. I rekurencja, którą warto rzucić na koniec: **asystent do analizy incydentów sam ma koszt, latencję i tryby awarii — a taki, który jest niedostępny podczas incydentu, jest ozdobą, nie narzędziem.**

---

### 13l. „A prywatność? Prompty w logach?" — *(jeśli padnie; częściej pada po talku niż na sali)*
**Domyślnie wyłączone i to jedyne rozsądne ustawienie domyślne.**
Użytkownik potrafi wkleić do czatu PESEL, dane medyczne albo fragment umowy pod NDA — a stamtąd tekst leci do Loki, jest replikowany, backupowany i przeszukiwalny przez każdego, kto ma dostęp do Grafany. Wzorce, które godzą debugowanie z prywatnością, w kolejności sensowności: **metadane zamiast treści** (długość, hash, wersja szablonu — wystarcza w ~80% diagnostyki) · osobny kanał z krótką retencją i własnym RBAC · próbkowanie z opt-inem · flaga włączana na czas incydentu i automatycznie gasnąca. I jedno rozróżnienie architektoniczne, które warto znać: **observability to nie audyt** — systemy observability są z założenia stratne (sampling, retencja, brak niezmienności), a audyt wymaga dokładnie odwrotnych własności. Wrzucenie wszystkiego do Loki i nazwanie tego audytem to jeden z najczęstszych błędów w tym obszarze.

---

## E. Observability

### 14. „Ile kodu musieliście napisać, żeby mieć te wszystkie wykresy?"
**Jeden licznik. Reszta to trzy zależności w `pom.xml`.**
`actuator` + `spring-cloud-starter-circuitbreaker-resilience4j` + `micrometer-registry-prometheus` i Resilience4j sam wystawia stan obwodu, failure rate, slow-call rate, czasy, liczniki retry i odrzucenia. Ręcznie napisałem **jeden** licznik: `reservations.route.plan.outcome` z tagiem `outcome` (`success` / `domain` / `infrastructure` / `empty_response` / `circuit_open`) — bo metryki R4j mówią językiem infrastruktury („60% failed"), a ten jeden mówi językiem produktu: **ile rezerwacji straciło trasę i dlaczego**. To jedyne przełożenie techniki na rozmowę z biznesem. *(→ slajdy 15 i 18)*

**Gdyby ktoś dopytał, czemu jeden meter z tagiem, a nie para success/errors:** bo wtedy success-ratio to jedno wyrażenie PromQL, a nie join po dwóch nazwach metryk. I czemu nie `reservations.route.plan` bez sufiksu — ta nazwa należy już do Timera z `Observation`, a Micrometer odrzuca dwa typy metryki pod jedną nazwą (aplikacja by nie wstała).

---

### 15. „Czemu panel retry pokazuje surowe liczniki, a nie `rate()`?"
**Bo zdarzenia retry są rzadkie i `rate()` by kłamał.**
Liczniki R4j są kumulatywne. Retry odpala się punktowo — przy `rate()` między zdarzeniami dostajesz zera, czyli obraz „nic się nie dzieje" w chwili, gdy właśnie coś się dzieje. Dla rzadkich zdarzeń pokazuj **sumę, nie tempo**; `rate()` zostaw dla ruchu ciągłego, jak wywołania na sekundę. Na demie widać to jako schodki co kilkanaście–kilkadziesiąt sekund.

---

### 16. „Nie boicie się kardynalności metryk przy tagowaniu?"
**Dlatego tagujemy tylko `lowCardinalityKeyValue`.**
Kod portu (kilkadziesiąt wartości) jest bezpieczny, ID rezerwacji czy klienta — **nie**: każda unikalna kombinacja etykiet to osobna seria czasowa w Prometheusie. Rzeczy o wysokiej kardynalności należą do trace'ów i logów, nie do etykiet metryk. To zresztą powód, dla którego `Observation` ma osobne API na oba rodzaje kluczy.

---

## F. Zakres i meta

### 17. „A RateLimiter i Bulkhead? Nie pokazaliście ich."
**Świadoma decyzja: trzy wzorce do końca zamiast pięciu w przelocie.**
To ta sama biblioteka i dosłownie jedna adnotacja więcej — ale każdy z nich zasługuje na własne demo z metrykami i pułapkami. Wolałem pokazać wam trzy rzeczy tak, żebyście umieli je jutro wdrożyć, niż pięć tak, żebyście zapamiętali nazwy. Przepis na dołożenie limitera jest w repo. Uwaga na marginesie: pod wirtualnymi wątkami klasyczny argument „wolna zależność zje ci pulę wątków" jest słabszy — realnym limitem są połączenia HTTP i pamięć, nie wątki.

---

### 18. „A Panu się to kiedyś zdarzyło?" — **jeśli padnie PRZED slajdem 24**
**NIE odpowiadaj. Odłóż to.**
⚓ „Dobre pytanie. *(pauza)* Wrócę do niego na końcu — obiecuję." I wracasz. Reveal na slajdzie 24 („trzy miesiące") działa wyłącznie wtedy, gdy sala nie wie, że historia istnieje. Jedno zdanie za wcześnie kosztuje najmocniejsze dwie minuty całego wystąpienia. Jeśli ktoś dopyta drugi raz — powtórz to samo z uśmiechem i przejdź dalej.

---

### 19. „Skąd wiecie, że to wszystko naprawdę działa?"
**Bo zmierzyliśmy, i jeden pomiar obalił naszą hipotezę.**
98 testów zielonych, w tym kontraktowe na kolejność aspektów i kody HTTP. Do tego pełna sekwencja chaosu na żywym stacku z odczytami z Prometheusa. Najciekawszy wynik: zakładaliśmy, że timeout na 50% wywołań da ~50% failure rate i otworzy obwód — a wyszło **failure ~0%, obwód zamknięty**. Przyczyna: toksyna działa per połączenie, a pula keep-alive obsługuje większość żądań po już nawiązanych, zdrowych połączeniach. Morał wykracza poza narzędzie: **nie ufaj nominalnym parametrom narzędzia do chaosu — mierz rezultat.** Gdybyśmy oparli demo na założeniu, na scenie nie zadziałałoby to, co obiecaliśmy.

---

### 20. „Czemu nie service mesh? Istio daje to samo bez kodu."
**Mesh daje retry/CB na L7, ale nie zna semantyki domenowej i nie ma fallbacku.**
Mesh świetnie robi retry, timeout i outlier detection bez dotykania kodu — ale (a) nie odróżni „4xx = odmowa domenowa" od „5xx = infra", a to rozróżnienie było sednem dzisiejszej zmiany; (b) nie poda degradacji ani sensownego kontraktu 409/503; (c) dokłada sidecar i operację. Biblioteka to decyzje **w aplikacji**, gdzie jest kontekst biznesowy. To się nie wyklucza: często infra robi grube CB, a aplikacja subtelne fallbacki.

---

## Szybkie liczby pod ręką

| Co | Wartość |
|---|---|
| Timeouty | connect 200 ms · read 800 ms (demo) / 2000 ms (baza) |
| Progi otwarcia | failure 50% · slow-call 50% przy 500 ms |
| Okno | 10 wywołań, minimum 5 · half-open po 10 s, 3 próby |
| Retry | 2 próby (= 1 ponowienie), 100 ms ±50%, backoff ×2 |
| Kolejność aspektów | CircuitBreaker **1** (zewnętrzny) · Retry **2** (wewnętrzny) |
| Kontrakt HTTP | 409 odmowa · 503 + `Retry-After: 10` niedostępność · 500 nasz bug |
| Alert | `for: 30s` → realnie FIRING po ~60–90 s |

## Szybkie liczby pod ręką — sekcja AI (D2)

| Co | Wartość / zasada |
|---|---|
| Timeouty przy modelu | **dwa**: time-to-first-token + idle między tokenami. 800 ms = 100% timeoutów |
| 429 | **`ignore-exceptions`**, nigdy `record-exceptions`; backoff z nagłówka `Retry-After` |
| Retry Spring AI (default) | `maxAttempts = 10`, backoff 2 s → 3 min — **sprawdź w swojej wersji** |
| Sędzia-model (LLM-as-judge) | asynchronicznie, próbka **1–5%**, przypięta wersja modelu i promptu |
| Kubełki histogramu w semconv | kończą się na **10 s** — przy dłuższych generacjach własne SLO |
| Nazwy w Prometheusie | `gen_ai.client.operation` → `gen_ai_client_operation_seconds_*` · `gen_ai.client.token.usage` → `..._total` |
| Pułapka nazw | `_active_count` (trwające) ≠ `_count` (zakończone) |
| Dwa pokolenia atrybutów | `gen_ai.system` → `gen_ai.provider.name` · `prompt_tokens` → `input_tokens` |
| Status semconv GenAI | **nic nie jest Stable**; osobne repo, brak wersjonowanych wydań |
| Detektor cichej podmiany modelu | alert na nową wartość `gen_ai_response_model` — kilka linijek, wykrywa klasę problemów |
