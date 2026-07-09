# Wykład: wiedza senior Java developera
## Fundament merytoryczny prelegenta talku „Działa ≠ działa i ja o tym wiem"

> **Po co ten dokument.** Talk trwa 20 minut i pokazuje *jedną* tezę — że odporność bez metryki jest niewidzialna. Ten wykład to **warstwa pod talkiem**: cała wiedza, którą prelegent powinien mieć w głowie, żeby (a) rozumieć każde zdanie ze sceny głębiej niż slajd, (b) obronić się przed salą seniorów w Q&A, (c) poprowadzić demo ze świadomością, co dzieje się pod spodem. Wszystkie przykłady są osadzone w realnym systemie z demo: `starport-registry → trade-route-planner` (Spring Boot 3.5, Java 21, wątki wirtualne, Resilience4j + Micrometer + Prometheus/Grafana).

> **Jak czytać.** 12 rozdziałów, każdy ~3 strony, każdy kończy się sekcją **„Pytania kontrolne"** — potraktuj je jak symulację ognia z sali. Rozdziały 1–4 to teoria wzorców, 5–8 to obserwowalność i narzędzia, 9–10 to architektura i testowanie, 11–12 to obrona i przyszłość. Jeśli masz mało czasu przed wystąpieniem — przeczytaj **rozdział 11** (arsenał na trudne pytania) i **rozdział 2** (circuit breaker), to one najczęściej decydują o wiarygodności.

---

## Spis treści

1. **Awarie w systemach rozproszonych** — fallacje, awarie szare, kaskady, retry storm, koszt niewidzialności
2. **Circuit Breaker: teoria i Resilience4j** — stany, okno ślizgowe, progi, slow-call, half-open
3. **Retry, backoff i idempotencja** — co wolno retry'ować, jitter, współpraca z CB, retry storm
4. **TimeLimiter, Bulkhead, RateLimiter i kolejność aspektów** — pełna mapa wzorca i dlaczego kolejność zaskakuje
5. **Resilience4j w Spring Boot** — moduły, AOP, fallback, konfiguracja, eventy, reactive
6. **Observability i Micrometer** — trzy filary, fasada, typy metryk, kardynalność, pułapka gauge'a
7. **Observation API, tracing i OpenTelemetry** — jedna obserwacja → metryka+trace, propagacja, exemplary, sampling
8. **Prometheus, PromQL i alerting** — model pull, rate vs increase, reguły, pułapki nazewnictwa Micrometer→Prometheus
9. **Architektura systemu** — discovery, client-side load balancing, wątki wirtualne, pipeline telemetrii
10. **Chaos engineering i testowanie odporności** — fault injection, Toxiproxy, WireMock, jak udowodnić, że CB działa
11. **Obrona przed trudnymi pytaniami** — 10 zarzutów seniora rozłożonych na czynniki + zasada nadrzędna
12. **Przyszłość: Spring Boot 4, Project Loom, mesh vs biblioteka** — co się zmienia, a co zostaje

---

## Rozdział 1 — Awarie w systemach rozproszonych

Zacznijmy od zdania, które brzmi banalnie, a jest sednem całego wykładu: **w systemie rozproszonym awaria nie jest zdarzeniem binarnym**. Monolit albo działa, albo się wywraca z `OutOfMemoryError` i widzisz to natychmiast. Rozproszona ścieżka `api-gateway → starport-registry → trade-route-planner` ma kilkanaście stanów pośrednich, w których „działa" i „nie działa" współistnieją w tym samym procesie, w tej samej sekundzie. Naszym zadaniem jako seniorów jest umieć je nazwać, zanim nazwie je za nas pager o 3:00.

### Osiem fałszywych założeń (8 fallacies)

Lista Petera Deutscha i Jamesa Goslinga z lat 90. nie zestarzała się ani o dzień. Osiem rzeczy, które programista *milcząco zakłada* pisząc kod sieciowy, a które są fałszem:

1. Sieć jest niezawodna.
2. Latencja wynosi zero.
3. Przepustowość jest nieskończona.
4. Sieć jest bezpieczna.
5. Topologia się nie zmienia.
6. Jest jeden administrator.
7. Koszt transportu wynosi zero.
8. Sieć jest jednorodna.

Każdy timeout w naszej konfiguracji to *materializacja* jednego z tych założeń w obronną wartość liczbową. `connect-timeout=200ms` to odpowiedź na fałsz nr 1 i nr 5 — nie zakładamy, że TCP handshake do `lb://trade-route-planner` zawsze się uda, bo replika mogła właśnie zniknąć z Eureki. `read-timeout=800ms` to odpowiedź na fałsz nr 2 — latencja nie jest zerowa i *nie jest stała*. Kluczowa obserwacja: jeśli w kodzie nie ma jawnego timeoutu, to znaczy że założenie pozostało milczące, a wartość domyślna (często nieskończoność albo 60s sterownika) zadziała przeciwko tobie.

### Liveness ≠ correctness

To jest rozróżnienie, które rozwala intuicję większości zespołów. Healthcheck `/actuator/health` odpowiada na pytanie **liveness**: czy proces żyje, czy potrafi zwrócić 200 na `GET`. Nie odpowiada na pytanie **correctness**: czy odpowiada *poprawnie* i *na czas*.

Instancja `trade-route-planner` może mieć zielony healthcheck i jednocześnie:

- odpowiadać `RouteUnavailableException → HTTP 409` na każde `POST /api/v1/routes/plan`, bo straciła połączenie z bazą tras,
- odpowiadać poprawnie, ale po 780ms — tuż pod naszym `read-timeout=800ms`, czyli na granicy urwania,
- odpowiadać 200 z pustym ciałem, bo deserializacja po stronie zależności cicho zwróciła `null`.

```
        liveness OK              correctness FAIL
   ┌────────────────────┐   ┌────────────────────────┐
   │ proces żyje         │   │ 409 na każdy request    │
   │ /health → 200       │   │ p99 = 780ms (≈timeout)  │
   │ Eureka: UP          │   │ null w body, 200        │
   └────────────────────┘   └────────────────────────┘
          ▲                              ▲
   widzi to load balancer        NIE widzi tego nikt,
   i routuje TAM ruch            kto patrzy tylko na /health
```

Load balancer i Eureka patrzą wyłącznie na lewą kolumnę. Dlatego instancja, która zwraca 100% błędów, dalej *dostaje ruch* — jest „zdrowa". To nie jest błąd konfiguracji healthchecka; to fundamentalne ograniczenie tego, co healthcheck *może* wiedzieć. Correctness mierzy się metrykami biznesowymi i SLO na latencji/error-rate, nie endpointem `/health`.

### Awarie częściowe i szare (gray failures)

**Awaria częściowa** to stan, w którym część systemu działa, a część nie — i to jest *domyślny* stan systemu rozproszonego, nie wyjątek. Mamy 2 repliki `starport-registry` i 2 repliki `trade-route-planner`. Jedna replika plannera może paść — wtedy 50% ruchu przez `@LoadBalanced RestClient` trafia w ścianę, a 50% śmiga. Z perspektywy użytkownika system „czasem działa", co jest gorsze diagnostycznie niż „nie działa wcale".

**Gray failure** (termin z pracy Huanga i in., Microsoft Research) to podtyp jeszcze podstępniejszy: system jest *degradowany w sposób, którego jego własny mechanizm wykrywania nie widzi*. Kluczowe pojęcie to **differential observability** — rozjazd między tym, co widzi system o sobie (healthcheck: UP), a tym, co widzą jego klienci (timeouty, 409). Klasyczny gray failure to instancja, której GC pauzuje na 600ms co kilka sekund: healthcheck akurat trafia w okno między pauzami i jest zielony, a klient akurat trafia w pauzę i dostaje read-timeout. Oba „mają rację" — i właśnie ten rozjazd jest definicją problemu.

### Cascading failures i wyczerpanie puli

Tu zwykle pada argument: „ale my mamy wątki wirtualne (ADR-0012), pula nie jest naszym wąskim gardłem". Częściowo słusznie — wirtualne wątki znoszą limit *platformowych* wątków Tomcata. Ale **nie znoszą limitów puli połączeń** (HTTP do plannera, JDBC do bazy) ani semaforów. Zasada Little'a działa nieubłaganie:

```
liczba_zajętych_zasobów = przepustowość[req/s] × czas_obsługi[s]

Normalnie:   100 req/s × 0.05s  =   5 połączeń w locie
Planner m7s: 100 req/s × 0.80s  =  80 połączeń w locie  (read-timeout!)
```

Gdy `trade-route-planner` degraduje i każdy request wisi do `read-timeout=800ms`, czas obsługi rośnie 16-krotnie, a liczba *jednocześnie zajętych* połączeń/wątków w `starport-registry` rośnie tak samo. Pula połączeń HTTP się wysyca, kolejka rośnie, `starport-registry` zaczyna sam timeoutować wobec `api-gateway` — i awaria *propaguje w górę* ścieżki. To jest **cascading failure**: lokalna degradacja zależności zamienia się w globalną niedostępność przez wyczerpanie współdzielonego zasobu. Wirtualne wątki paradoksalnie *pogarszają* obraz: bez limitu wątków nic cię nie zatrzyma przed otwarciem dziesiątek tysięcy wiszących wywołań — backpressure trzeba postawić jawnie (bulkhead, ograniczona pula połączeń, circuit breaker). To właśnie dlatego mamy CB: `failure-rate-threshold=50%`, `sliding-window=10 COUNT_BASED`, `min-calls=5` — żeby po 5 z 10 nieudanych wywołań *przestać* zajmować zasoby na rzecz padającej zależności i zacząć zwracać szybkie 409 zamiast wisieć 800ms.

### Thundering herd i retry storm

Mamy `retry: max-attempts=2, wait=100ms`. Wygląda niewinnie. Ale policz: gdy planner degraduje, każdy request to teraz **2 próby × 800ms ≈ 1.6s** obciążenia zamiast 800ms — *podwajamy* ruch wymierzony dokładnie w moment, gdy zależność jest najsłabsza. To jest **retry storm**: retry to amplifikator, który w awarii działa jak dolewanie benzyny.

**Thundering herd** to bliźniacza patologia synchronizacji w czasie: gdy CB otwiera się na `wait-in-open=10s`, *wszystkie* repliki `starport-registry` próbują pierwszego wywołania testowego (half-open) w tej samej chwili, a klienci, którym wygasły timeouty, ruszają zsynchronizowaną falą. Stały `wait=100ms` bez jittera oznacza, że retry też są zsynchronizowane co do milisekundy. Antidotum: **exponential backoff + jitter** (losowe rozmycie odstępu), budżet retry (np. retry tylko jeśli <10% ruchu to retry), i — kontrintuicyjnie — czasem *mniej* retry jest lepsze niż więcej.

### „Działa ≠ działa i ja o tym wiem"

Tytuł talku to nie gra słów, to teza o **obserwowalności jako warunku poprawności operacyjnej**. Są cztery kwadranty:

```
                 DZIAŁA                  NIE DZIAŁA
            ┌──────────────────┐   ┌──────────────────────┐
WIEM        │ stan docelowy     │   │ incydent, ale         │
            │                   │   │ kontrolowany          │
            ├──────────────────┤   ├──────────────────────┤
NIE WIEM    │ złudzenie spokoju │   │ KOSZTOWNA NIEWIDZIALNA│
            │ (jeszcze)         │   │ AWARIA                │
            └──────────────────┘   └──────────────────────┘
```

Lewy-dolny kwadrant — „działa, ale nie wiem, *dlaczego* i czy nadal" — to dług, który zawsze spłacasz w najgorszym momencie. Prawy-dolny to katastrofa, o której opowiem anegdotą.

### Koszt niewidzialnej awarii

Realny przypadek z tej klasy systemów. Adapter w `starport-registry` przez **trzy miesiące** strzelał `POST /api/v1/routes/plan` w **zły adres** — instancję plannera, która została zdeprecjonowana, ale wpis w konfiguracji przeżył migrację. Skutek: dwa błędy 409 na dobę. Dwa. W strumieniu logów, gdzie *normalny* szum to tysiące linii, dwa wyjątki na dobę to statystycznie zero — żaden alert się nie odpalił, bo error-rate był grubo poniżej każdego progu. Wykryto to **przez przypadek**, gdy ktoś debugował zupełnie inny problem i zauważył nieznany host w trace'ie.

Trzy miesiące × ~2 błędy/dobę ≈ 180 cichych porażek transakcji biznesowych. Każda to klient, którego trasa się nie zaplanowała, retry zamaskował część, a logi *zawierały* prawdę przez cały czas — tyle że nikt jej nie czytał, bo nie było sygnału, który by na nią wskazał. To jest dokładnie prawy-dolny kwadrant: **awaria + brak wiedzy o niej**. Koszt nie leży w samej awarii (2 błędy/dobę to nic), leży w tym, że *system o niej nie krzyczał* — bo nikt nie postawił SLO na sukces planowania trasy, tylko patrzono na error-rate globalny, w którym sygnał utonął.

Morał całego rozdziału: w systemie rozproszonym awaria jest stanem ciągłym i częściowym, healthcheck mierzy najmniej istotny jej wymiar, a różnica między seniorem a juniorem nie polega na tym, że senior nie ma awarii — polega na tym, że senior **wie o nich pierwszy**.

### Pytania kontrolne

**P1: Instancja `trade-route-planner` ma zielony `/actuator/health` i jest `UP` w Eurece, a mimo to zwraca 409 na 100% ruchu. Czy to jest awaria, i dlaczego load balancer dalej tam routuje?**
Tak, to awaria typu correctness przy zachowanej liveness (gray failure). Load balancer i Eureka podejmują decyzję wyłącznie na podstawie liveness — proces żyje i odpowiada 200 na health — więc traktują instancję jako zdrową. Rozjazd między „system widzi siebie jako UP" a „klient dostaje 409" to differential observability; healthcheck z definicji nie mierzy poprawności odpowiedzi biznesowych. Lekarstwo: SLO na error-rate/latencję per-endpoint i, ewentualnie, deep healthcheck sprawdzający zależności krytyczne — z ostrożnością, by sam nie stał się źródłem kaskady.

**P2: Skoro używamy wątków wirtualnych (ADR-0012), to czy wyczerpanie puli wątków przestaje nam grozić? Uzasadnij liczbowo.**
Nie. Wirtualne wątki znoszą limit *platformowych* wątków Tomcata, ale nie znoszą limitów puli połączeń HTTP/JDBC ani semaforów. Z prawa Little'a: przy 100 req/s i normalnym czasie obsługi 0.05s mamy ~5 połączeń w locie; gdy planner degraduje i każdy request wisi do `read-timeout=800ms`, to 100 × 0.8 = 80 połączeń jednocześnie — pula się wysyca. Wirtualne wątki wręcz *pogarszają* sytuację, bo bez naturalnego limitu wątków nic nie powstrzyma otwierania tysięcy wiszących wywołań. Backpressure (circuit breaker, ograniczona pula, bulkhead) trzeba postawić jawnie — i właśnie po to jest CB z progiem 50% na oknie 10 wywołań.

**P3: Mamy `retry max-attempts=2, wait=100ms`. Dlaczego ta konfiguracja może *pogłębić* awarię zależności i co zmienić?**
Bo retry jest amplifikatorem ruchu uruchamianym dokładnie wtedy, gdy zależność jest najsłabsza: degradacja plannera zamienia każdy request w 2 próby, podwajając obciążenie (retry storm). Stały `wait=100ms` bez jittera dodatkowo synchronizuje próby wszystkich replik w czasie (thundering herd), a po otwarciu CB na `wait-in-open=10s` wszystkie repliki uderzają half-open jednocześnie. Zmienić: exponential backoff z jitterem zamiast stałego odstępu, budżet retry (retry tylko gdy stanowi <X% ruchu), retry wyłącznie na błędy idempotentne/przejściowe, oraz oparcie się na CB, by w ogóle przestać wysyłać próby do padającej zależności.


---

## Rozdział 2 — Circuit Breaker: teoria i Resilience4j

W rozdziale pierwszym ustaliliśmy, że „działa" nie jest stanem binarnym, lecz spektrum, w którym usługa może odpowiadać poprawnie, ale wolno, albo odpowiadać szybko, ale błędem. Circuit Breaker (bezpiecznik) to wzorzec, który przekształca to spektrum z powrotem w decyzję operacyjną: *czy wciąż warto wołać tę zależność, czy lepiej natychmiast jej odpuścić?* Nazwa pochodzi z elektryki — bezpiecznik przerywa obwód, zanim przeciążenie spali instalację. W systemach rozproszonych „spaleniem" jest kaskadowa awaria: usługa A czeka na padającą usługę B, wątki A się zatykają, A przestaje odpowiadać swoim klientom, i awaria rozlewa się w górę grafu zależności. Bezpiecznik zatrzymuje tę propagację.

### Trzy stany i przejścia między nimi

Resilience4j implementuje bezpiecznik jako automat skończony z trzema stanami głównymi (`CLOSED`, `OPEN`, `HALF_OPEN`) oraz dwoma terminalnymi (`DISABLED`, `FORCED_OPEN`), które pomijamy. Kluczowa intuicja: nazwy są mylące dla osób spoza elektryki. **CLOSED znaczy „obwód zamknięty, prąd płynie" — czyli wywołania PRZECHODZĄ.** OPEN znaczy „obwód przerwany" — wywołania są BLOKOWANE.

```
                  failure_rate >= threshold
                  (po minimum-number-of-calls)
        ┌──────────────────────────────────────────┐
        │                                           ▼
   ┌─────────┐                                  ┌────────┐
   │ CLOSED  │                                  │  OPEN  │
   │ przepu- │                                  │ fail-  │
   │  szcza  │                                  │  fast  │
   └─────────┘                                  └────────┘
        ▲                                           │
        │ failure_rate < threshold        wait-duration-in-
        │ (probki udane)                  open-state upłynął
        │                                           │
        │                                           ▼
        │         porazka próbnego           ┌────────────┐
        └─────────  wywołania ◄───────────── │ HALF_OPEN  │
                                             │ N prób na   │
              sukces wszystkich N prob ─────►│  żywym ruchu│
              (z powrotem CLOSED)            └────────────┘
```

W stanie **CLOSED** bezpiecznik przepuszcza ruch i rejestruje wynik każdego wywołania w oknie przesuwnym (sliding window). Gdy odsetek porażek przekroczy próg, następuje przejście do **OPEN**. W **OPEN** każde wołanie jest natychmiast odrzucane wyjątkiem `CallNotPermittedException` — to jest *fail-fast*, o którym za chwilę. Po upływie `wait-duration-in-open-state` bezpiecznik przechodzi do **HALF_OPEN**, gdzie przepuszcza ściśle ograniczoną liczbę wywołań próbnych na żywym ruchu. Jeśli wszystkie wypadną dobrze — wraca do CLOSED; jeśli choć jedno padnie — wraca do OPEN i odlicza kolejny `wait-duration` od nowa. HALF_OPEN to kontrolowany test, czy zależność wróciła do życia, bez ryzykowania pełnym wolumenem ruchu.

### Sliding window: COUNT_BASED vs TIME_BASED

Bezpiecznik liczy odsetek porażek nie globalnie, lecz w oknie przesuwnym. Resilience4j oferuje dwa typy. **COUNT_BASED** agreguje N ostatnich wywołań (u nas `sliding-window-size=10` → ostatnie 10). **TIME_BASED** agreguje wszystkie wywołania z ostatnich N sekund, organizując je w częściowe agregaty per sekunda.

Różnica jest fundamentalna przy **zmiennym ruchu**. Okno czasowe o ustalonej szerokości zachowuje się drastycznie inaczej pod 1000 RPS niż pod 2 RPS — w pierwszym przypadku agreguje tysiące próbek (statystycznie wiarygodne), w drugim może agregować garstkę albo nic, a w dolinach ruchu metryka staje się szumem. Okno **COUNT_BASED jest bezpieczniejsze przy nieregularnym ruchu**, bo gwarantuje stałą liczność próby niezależnie od tempa: zawsze N wywołań, więc próg statystyczny jest stabilny. Cena: przy niskim ruchu okno „pamięta" stare wywołania długo (10 wywołań może rozciągać się na minuty), więc reakcja jest wolniejsza w czasie zegarowym. TIME_BASED wybieramy, gdy zależy nam na ograniczonym horyzoncie czasowym (np. „odsetek z ostatnich 60 s") i mamy gwarantowany, gęsty ruch. Dla `trade-route-planner` wybraliśmy COUNT_BASED właśnie dlatego, że ruch planowania tras jest skokowy.

### Konfiguracja instancji `trade-route-planner`

```yaml
resilience4j:
  circuitbreaker:
    instances:
      trade-route-planner:
        sliding-window-type: COUNT_BASED
        sliding-window-size: 10
        minimum-number-of-calls: 5
        failure-rate-threshold: 50            # %
        slow-call-rate-threshold: 50          # %
        slow-call-duration-threshold: 500ms
        wait-duration-in-open-state: 10s
        permitted-number-of-calls-in-half-open-state: 3
        register-health-indicator: true
```

A po stronie klienta HTTP:

```yaml
# RestClient wołający trade-route-planner
read-timeout: 800ms
```

### failure-rate-threshold oraz sentinel minimum-number-of-calls

`failure-rate-threshold=50` znaczy: jeśli ≥50% wywołań w oknie to porażki, otwórz bezpiecznik. Ale procent z małej próby jest bezwartościowy — jedna porażka na dwa wywołania to 50%, co fałszywie otworzyłoby obwód po pierwszej anomalii. Dlatego istnieje `minimum-number-of-calls=5`: bezpiecznik **nie policzy** failure rate, dopóki w oknie nie zbierze co najmniej 5 wywołań.

To prowadzi do detalu, który zaskakuje każdego, kto pierwszy raz patrzy na metryki. Zanim zbierze się minimum, metryka `resilience4j_circuitbreaker_failure_rate` raportuje **-1**, nie 0. To **wartość-strażnik (sentinel)** oznaczająca „za mało danych, by ocenić", a nie „0% porażek". Różnica jest krytyczna na dashboardach i w alertach: gdybyśmy potraktowali -1 jak liczbę i np. uśrednili albo postawili alert „failure_rate > 40", -1 nigdy go nie wzbudzi (dobrze), ale „failure_rate < 1 = zdrowo" zinterpretowałoby brak danych jako zdrowie (źle — to po prostu cisza). Identycznie `slow_call_rate` zwraca -1 przed osiągnięciem minimum. Na wykresach należy te -1 filtrować lub renderować jako „brak danych".

### Detekcja wolnych wywołań — i czemu wolny sukces też otwiera obwód

Najważniejsza lekcja całego talku mieści się tu. Bezpiecznik liczący wyłącznie błędy przepuści najgorszy scenariusz: zależność, która **odpowiada poprawnie, ale wolno**. Wolne odpowiedzi zatykają pulę połączeń i wątków równie skutecznie jak błędy — to jest dokładnie owo „działa ≠ działa" z tytułu. Resilience4j traktuje więc wolne wywołanie jako osobną kategorię ryzyka.

`slow-call-duration-threshold=500ms` definiuje, co jest „wolne": każde wywołanie trwające ≥500 ms jest oznaczane jako *slow call* — **niezależnie od tego, czy zakończyło się sukcesem**. `slow-call-rate-threshold=50` mówi: jeśli ≥50% wywołań w oknie jest wolnych, otwórz bezpiecznik, tak samo jakby to były błędy. Wolny, ale udany request liczy się do tego progu.

Tu wchodzi precyzyjna relacja z `read-timeout=800ms` RestClienta:

| Latencja zależności | Wynik HTTP | Klasyfikacja w CB |
|---|---|---|
| 200 ms | 200 OK | sukces, nie-slow |
| 600 ms | 200 OK | **sukces, ale SLOW** (≥500 ms) |
| >800 ms | timeout → wyjątek | **porażka** (failure) |

Zwróćmy uwagę na środkowy wiersz: 600 ms to udany request poniżej timeoutu, więc klient dostaje poprawną odpowiedź — a mimo to bezpiecznik liczy go do `slow_call_rate`. To celowe: zanim latencja urośnie ponad timeout i zacznie generować błędy, bezpiecznik zdąży zareagować na samo *spowolnienie*. Próg slow (500 ms) jest świadomie niższy niż timeout (800 ms), tworząc strefę wczesnego ostrzegania między 500 a 800 ms. Gdyby slow-threshold był ustawiony powyżej read-timeoutu, byłby martwy — żadne wywołanie nie zdążyłoby zostać „wolne", bo wcześniej padłoby timeoutem i trafiło do failure rate.

### Czasy i okno HALF_OPEN

`wait-duration-in-open-state=10s` to czas, przez który bezpiecznik pozostaje OPEN, dając padającej zależności oddech bez bombardowania jej ruchem. Po 10 s przechodzimy w HALF_OPEN. `permitted-number-of-calls-in-half-open-state=3` ogranicza test do 3 wywołań próbnych — nie wpuszczamy pełnego wolumenu na być-może-wciąż-chorą usługę. Dopiero gdy wszystkie 3 wypadną pomyślnie (i nie-wolno), bezpiecznik wraca do CLOSED. Pojedyncza porażka lub wolne wywołanie w HALF_OPEN natychmiast wraca do OPEN i odmierza kolejne 10 s.

### Fail-fast i ochrona padającej zależności

Bezpiecznik chroni **dwie strony jednocześnie**. Klienta — bo w stanie OPEN zwraca błąd w mikrosekundach (`CallNotPermittedException`) zamiast kazać mu czekać 800 ms na timeout, co uwalnia jego wątki i pozwala uruchomić fallback. I samą zależność — bo przestaje do niej kierować ruch, gdy ta tonie, dając jej szansę wstać zamiast dobijać ją kolejnymi żądaniami. To drugie jest nieoczywiste: czasem najlepszą rzeczą, jaką możemy zrobić dla padającej usługi, jest *przestać ją wołać*.

### Metryki i topologia stanu

Resilience4j eksportuje przez Micrometer komplet metryk pod `job=starport-registry`, `name=trade-route-planner`:

- `resilience4j_circuitbreaker_state{state="closed|open|half_open|...}` — gauge 0/1, dokładnie jeden stan ma wartość 1. Idealne do `max by (state)` na dashboardzie pokazującym aktualny stan.
- `resilience4j_circuitbreaker_failure_rate` — bieżący odsetek porażek (lub **-1** przed osiągnięciem minimum).
- `resilience4j_circuitbreaker_slow_call_rate` — bieżący odsetek wolnych wywołań (również -1 jako sentinel).
- `resilience4j_circuitbreaker_not_permitted_calls_total` — licznik wywołań odrzuconych w stanie OPEN. Jego przyrost to bezpośrednia miara fail-fast: ile żądań bezpiecznik „zjadł", oszczędzając zależności.
- `resilience4j_circuitbreaker_calls_seconds_count{kind="successful|failed|slow|...}` — licznik wywołań z rozbiciem na rodzaj (oraz histogram czasów w `_sum`/`_bucket`).

Krytyczne zastrzeżenie operacyjne: **stan bezpiecznika żyje w pamięci JVM danej instancji**. Mamy 2 repliki `starport-registry`, każda prowadzi *własny* automat. Replika A może być OPEN, podczas gdy replika B jest CLOSED — bo każda widziała inny strumień wywołań. Na dashboardach nie wolno więc agregować `state` przez `sum` bez rozróżnienia po instancji, bo dostaniemy bezsensowne sumy typu 1.4. Alertujemy raczej „jakakolwiek replika OPEN" (`max`), a metryki rozbijamy per pod. `register-health-indicator=true` dorzuca stan bezpiecznika do `/actuator/health` każdej repliki — wygodne, ale pamiętajmy, że zdrowie zależy wtedy od lokalnego automatu, nie od stanu klastra.

### Pytania kontrolne

**1. Dlaczego przy ruchu skokowym (raz 500 RPS, raz 2 RPS) okno COUNT_BASED jest bezpieczniejsze niż TIME_BASED, i jaki jest tego koszt?**
Bo COUNT_BASED gwarantuje stałą liczność próby (zawsze N=10 wywołań), więc próg statystyczny jest jednakowo wiarygodny niezależnie od tempa. TIME_BASED o stałej szerokości czasowej agreguje tysiące próbek pod 500 RPS, a pod 2 RPS garstkę lub nic — odsetek porażek z 3 wywołań to szum, który albo fałszywie otwiera obwód, albo go nie otwiera mimo realnej awarii. Koszt COUNT_BASED: przy niskim ruchu te 10 wywołań mogą rozciągać się na minuty, więc bezpiecznik reaguje wolniej w czasie zegarowym i dłużej „pamięta" stare zdarzenia.

**2. Zależność zaczyna odpowiadać po 600 ms, ciągle ze statusem 200 OK. Read-timeout to 800 ms. Czy bezpiecznik zareaguje, a jeśli tak, to przez którą metrykę i dlaczego to celowe?**
Tak. 600 ms < 800 ms, więc HTTP się udaje (status 200) i nie ma żadnego błędu — `failure_rate` pozostaje niski. Ale 600 ms ≥ `slow-call-duration-threshold` (500 ms), więc wywołanie jest liczone jako *slow* mimo sukcesu i podbija `slow_call_rate`. Po ≥50% wolnych wywołań w oknie bezpiecznik otwiera się przez próg slow, nie failure. To celowe: próg slow (500 ms) leży poniżej timeoutu (800 ms), tworząc strefę wczesnego ostrzegania — reagujemy na samo spowolnienie, zanim latencja przebije timeout i zacznie generować błędy oraz zatykać wątki.

**3. Na dashboardzie failure_rate dla `trade-route-planner` pokazuje -1 przez kilka minut po starcie, mimo że żadne wywołanie nie padło. Co to znaczy i jaka pułapka czeka twórcę alertu?**
-1 to wartość-strażnik „za mało danych" — w oknie nie zebrało się jeszcze `minimum-number-of-calls=5`, więc Resilience4j celowo nie liczy odsetka (zwraca -1, nie 0). To NIE jest 0% porażek ani błąd. Pułapka: alert „failure_rate < 1 ⇒ zdrowo" zinterpretuje -1 (ciszę / brak ruchu) jako zdrowie, a alert uśredniający wartości wciągnie -1 do średniej i ją zaniży. Należy filtrować -1 (np. `> -1`) zanim cokolwiek się liczy lub alarmuje, a na wykresie renderować jako „brak danych". Ta sama zasada dotyczy `slow_call_rate`.


---

## Rozdział 3 — Retry, backoff i idempotencja

Retry to najprostszy z mechanizmów odpornościowych i właśnie dlatego najczęściej robiony źle. Idea jest tak oczywista, że każdy programista napisze ją w pięć minut, nie zaglądając do żadnej biblioteki. Problem w tym, że te pięć minut produkuje kod, który działa na demie i wybucha pod obciążeniem. Ten rozdział jest o tym, dlaczego naiwny retry to broń wymierzona w samego siebie, kiedy wolno ponawiać, a kiedy absolutnie nie, oraz jak Retry, backoff i Circuit Breaker razem zamieniają się z zagrożenia w mechanizm samoobrony systemu.

### Naiwny retry, czyli pętla i sleep

Zacznijmy od kodu, który wszyscy widzieliśmy i wielu z nas napisało:

```java
RoutePlan plan = null;
for (int attempt = 0; attempt < 3; attempt++) {
    try {
        plan = routeClient.planRoute(request);
        break;
    } catch (Exception e) {
        Thread.sleep(100); // spróbujemy jeszcze raz
    }
}
if (plan == null) {
    throw new IllegalStateException("nie udało się");
}
```

Ten fragment ma cztery grzechy główne, a żaden z nich nie jest widoczny na pierwszy rzut oka.

Po pierwsze — to czarna skrzynka. `catch (Exception e)` łapie wszystko: timeout sieciowy, `NullPointerException` w naszym własnym mapowaniu odpowiedzi, błąd walidacji domenowej. Ponawiamy je wszystkie z taką samą beztroską. Po drugie — brak metryk. Nikt na zewnątrz nie wie, że ten kod właśnie wykonał trzy próby zamiast jednej. Z punktu widzenia dashboardu zapytanie po prostu trwało 300 milisekund dłużej, a Ty nie masz pojęcia, czy zależność degraduje, dopóki nie wybuchnie zupełnie. Po trzecie — stały odstęp 100 ms u wszystkich klientów jednocześnie. Po czwarte, i najgroźniejsze — retry storm.

Wyobraźmy sobie, że zależność ma chwilowy zator: GC pause, rolling restart, krótkie nasycenie puli wątków. Bez retry dostaje N zapytań na sekundę. Włączamy naiwny retry z trzema próbami i nagle ta sama zależność, już osłabiona, dostaje do 3N zapytań — dokładnie w momencie, gdy ma najmniej zasobów, żeby je obsłużyć. Retry zaprojektowany, by przetrwać drobną awarię, zamienia ją w awarię totalną. To nie jest scenariusz teoretyczny; to najczęstsza przyczyna kaskadowych zawałów w mikroserwisach.

### Co wolno ponawiać, a czego nie

Złota zasada: ponawiamy wyłącznie błędy przejściowe. Przejściowy znaczy, że ta sama operacja, powtórzona za chwilę, ma realną szansę się udać, bo przyczyna niepowodzenia była chwilowa i niezwiązana z treścią żądania.

Wolno ponawiać: zgubiony pakiet TCP, connection reset, pauza GC po stronie zależności, rolling restart repliki, chwilowy `503` z load balancera, read-timeout na zależności, która zwykle odpowiada szybko. Wszystko to są stany, które miną same.

Nie wolno ponawiać błędów deterministycznych. Jeśli żądanie jest błędne, to będzie błędne za 100 ms tak samo, jak teraz. `400 Bad Request`, `404`, `422`, błąd walidacji domenowej, `NullPointerException` w naszym kodzie — ich ponawianie to czyste marnowanie zasobów i opóźnianie nieuchronnego błędu, który i tak zwrócimy użytkownikowi. Co więcej, ponawianie domenowych 4xx jest aktywnie szkodliwe: dokładasz ruchu zależności, która poprawnie odrzuca śmieci, i maskujesz prawdziwy błąd przed swoimi metrykami.

W naszym projekcie ta zasada jest zaszyta w konfiguracji wprost. Instancja Retry `trade-route-planner` ponawia wyłącznie jeden, konkretny typ wyjątku:

```yaml
resilience4j:
  retry:
    instances:
      trade-route-planner:
        max-attempts: 2          # 1 próba + 1 ponowienie
        wait-duration: 100ms
        retry-exceptions:
          - com.galactic.starport.exception.RouteUnavailableException
```

`RouteUnavailableException` to jawny, semantyczny sygnał „ta replika plannera teraz nie może, spróbuj jeszcze raz". Każdy inny wyjątek — w tym domenowe odrzucenia trasy — przelatuje przez Retry bez ponowienia. To jest różnica między `catch (Exception e)` a inżynierią: ponawiamy jedną, nazwaną sytuację, a nie „cokolwiek poszło nie tak".

Zwróćcie też uwagę na `max-attempts: 2`. To nie literówka. W Resilience4j `max-attempts` liczy łącznie z pierwszą próbą, więc 2 oznacza dokładnie jedno ponowienie. Mając dwie repliki plannera za `@LoadBalanced`, ponowiona próba zwykle ląduje na drugiej, zdrowej replice. Jedno ponowienie wystarczy, by przeskoczyć replikę w trakcie restartu — a brak agresji (nie 5, nie 10 prób) sprawia, że nawet gdyby obie repliki padły, nie generujemy storma.

### Exponential backoff i — krytycznie — jitter

Skoro ponawiamy wiele instancji jednocześnie, stały odstęp jest pułapką. Załóżmy, że zależność padła na sekundę i odzyskała. W tym momencie wszyscy klienci, którzy dostali błąd, czekali równe 100 ms i wszyscy ruszą do ponowienia w tej samej milisekundzie. Zsynchronizowali się na awarii. Dostajesz ostry pik ruchu, zależność znów się dławi, wszyscy znów dostają błąd, znów czekają 100 ms — i tak w tętniących falach. To zjawisko nazywa się thundering herd.

Lekarstwem jest exponential backoff z jitterem. Backoff wykładniczy zwiększa odstęp przy każdej próbie (100 ms, 200 ms, 400 ms…), dając zależności coraz więcej powietrza. Ale sam backoff nie rozprasza klientów — wszyscy nadal czekają tę samą wykładniczą wartość. Dopiero jitter, czyli losowa składowa dodana do każdego odstępu, rozsmarowuje ponowienia w czasie, tak że zamiast piku dostajesz równomierny strumień. Jitter nie jest kosmetyką; to on rozbija synchronizację klientów i jest najważniejszą częścią całego mechanizmu backoffu.

```yaml
resilience4j:
  retry:
    instances:
      some-bursty-dependency:
        max-attempts: 3
        wait-duration: 100ms
        enable-exponential-backoff: true
        exponential-backoff-multiplier: 2
        enable-randomized-wait: true
        randomized-wait-factor: 0.5   # +/- 50% jitteru
```

W naszym planerze, przy jednym ponowieniu i dwóch replikach, pełny backoff jest świadomie pominięty — stałe 100 ms wystarcza, bo nie ponawiamy w pętli. Ale gdy tylko liczba prób rośnie powyżej dwóch, jitter przestaje być opcją.

### Idempotencja i klucz idempotentny

Retry kryje subtelną pułapkę: read-timeout nie znaczy, że zależność niczego nie zrobiła. Wyobraźmy sobie nasz read-timeout ustawiony na 800 ms. Wysyłamy żądanie, zależność je odbiera, przetwarza, zapisuje skutek — i odpowiedź wraca po 850 ms. My już dawno uznaliśmy to za timeout i ponawiamy. Skutek: operacja wykonana dwa razy, duplikat.

Dla operacji odczytu nie ma problemu — są naturalnie idempotentne. Problem zaczyna się przy zapisach z efektem ubocznym: zarezerwuj miejsce, obciąż konto, wyślij ładunek. Tu ratunkiem jest klucz idempotentny: klient generuje unikalny identyfikator żądania i wysyła go w nagłówku, a zależność zapamiętuje, że ten klucz już obsłużyła, i przy ponowieniu zwraca poprzedni wynik zamiast wykonywać operację drugi raz. Retry staje się bezpieczny, bo duplikat jest wyłapywany po stronie odbiorcy.

W naszym systemie świadomie nie mamy klucza idempotentnego dla planera. Powód jest projektowy: planowanie trasy to operacja sub-sekundowa, czysto obliczeniowa, bez trwałego efektu ubocznego po stronie plannera. Ponowne zaplanowanie tej samej trasy zwraca po prostu kolejny równoważny plan — nie ma czego deduplikować. Klucz idempotentny to koszt (przechowywanie kluczy, ich wygasanie), który płacimy tam, gdzie duplikat boli, czyli przy zapisach. Tu by nie bolał, więc go nie wprowadzamy. To nie jest niedopatrzenie — to świadoma decyzja zakresu i warto ją umieć obronić na review.

### Współpraca Retry i Circuit Breaker

Najważniejsza myśl tego rozdziału: to Circuit Breaker rozbraja retry storm, a nie sam Retry. Klucz leży w kolejności aspektów. U nas Retry owija CircuitBreaker — `Retry(CircuitBreaker(...))`. Każda próba przechodzi najpierw przez CB, a Retry obejmuje całość.

Gdy zależność realnie pada, Circuit Breaker przechodzi w stan OPEN. Od tego momentu CB nie przepuszcza już żadnego wywołania do martwej zależności — natychmiast rzuca `CallNotPermittedException`, bez sieci, w mikrosekundach. I teraz kluczowe: `CallNotPermittedException` nie jest na naszej liście `retry-exceptions`. Retry dostaje go, sprawdza listę, nie znajduje dopasowania i nie ponawia. Pętla ponawiania zostaje rozbrojona.

Prześledźmy to. Zależność zdrowa — pierwsza próba przechodzi przez CB, leci do repliki, ewentualne `RouteUnavailableException` powoduje jedno ponowienie na drugą replikę. Idealnie. Zależność martwa — CB jest OPEN, pierwsza „próba" to natychmiastowy `CallNotPermittedException`, Retry go nie ponawia, bo to nie jego wyjątek. Zamiast bombardować nieżyjącą zależność dwoma próbami, kończymy szybko i czysto. Retry działa tam, gdzie ma sens (chwilowy błąd jednej repliki), a milknie tam, gdzie ponawianie tylko by szkodziło (cała zależność leży). Te dwa wzorce nie konkurują — one się uzupełniają, a poprawna kolejność i lista wyjątków sprawiają, że robią to automatycznie.

### Metryka, czyli jak to zobaczyć

Wróćmy do pierwszego grzechu naiwnego retry — czarnej skrzynki. Resilience4j eksponuje `resilience4j_retry_calls{kind}`, gdzie `kind` przyjmuje wartości `successful_without_retry`, `successful_with_retry`, `failed_without_retry`, `failed_with_retry`. Nareszcie widać, ile wywołań udało się dopiero za drugim razem.

Jest jednak haczyk, o który potyka się wielu. To jest gauge kumulatywny — wartość rośnie monotonicznie przez cały czas życia procesu, jak licznik przebiegu w samochodzie. W PromQL liczymy z niego przyrost przez `increase()`, nie przez `rate()`, i nie traktujemy surowej wartości jako „liczby retry teraz":

```promql
increase(resilience4j_retry_calls{kind="successful_with_retry"}[5m])
```

Rosnący `successful_with_retry` to żółte światło: ponowienia ratują ruch, ale zależność degraduje. Rosnący `failed_with_retry` to czerwone: ponawiamy i wciąż przegrywamy — czas spojrzeć na Circuit Breaker. Bez tej metryki retry jest niewidzialny aż do katastrofy; z nią widzisz degradację, zanim stanie się awarią.

### Pytania kontrolne

1. Wymień cztery problemy naiwnego retry napisanego jako pętla `for` ze `sleep`. Który z nich potrafi zamienić drobną awarię w totalną?
2. Dlaczego `max-attempts: 2` w Resilience4j oznacza jedno ponowienie, a nie dwa? Jak na skuteczność tego ponowienia wpływają dwie repliki za `@LoadBalanced`?
3. Podaj po dwa przykłady błędów, które wolno i których nie wolno ponawiać. Dlaczego ponawianie domenowego `4xx` jest nie tylko bezużyteczne, ale wręcz szkodliwe?
4. Czemu sam exponential backoff nie wystarcza i dlaczego jitter jest jego najważniejszą częścią? Co to jest thundering herd?
5. Dlaczego read-timeout nie gwarantuje, że zależność nie wykonała operacji? Jak klucz idempotentny rozwiązuje ten problem i dlaczego świadomie pomijamy go w planerze?
6. Przy kolejności `Retry(CircuitBreaker(...))` i CB w stanie OPEN — jaki wyjątek dostaje Retry i dlaczego go nie ponawia? Dlaczego to właśnie CB, a nie Retry, rozbraja retry storm?
7. Czemu `resilience4j_retry_calls` liczymy przez `increase()`, a nie `rate()`? Co oznacza rosnący `failed_with_retry`?


---

## Rozdział 4 — TimeLimiter, Bulkhead, RateLimiter i kolejność aspektów

W poprzednim rozdziale poznaliśmy dwa wzorce, które w tym projekcie faktycznie pracują na ścieżce `starport-registry → trade-route-planner`: `@Retry` i `@CircuitBreaker` z metodą `fallbackMethod`. Teraz domknijmy mapę wzorca. Resilience4j to nie tylko bezpiecznik i ponawianie — to zestaw współpracujących dekoratorów, a senior, który zna tylko dwa z nich, prędzej czy później zostanie zaskoczony przez pozostałe. Omówimy więc TimeLimiter, Bulkhead i RateLimiter, a na koniec rzecz najczęściej pomijaną na rozmowach i w code review: **kolejność, w jakiej te aspekty się składają**.

Uczciwa deklaracja na wstępie: w naszym projekcie aktywne są wyłącznie Retry i CircuitBreaker. Pozostałe trzy wzorce omawiamy jako pełny obraz — żebyś wiedział, czego nie używasz i dlaczego.

### TimeLimiter — i dlaczego go tu nie ma

Pierwsza pułapka jest definicyjna. `TimeLimiter` w Resilience4j **działa tylko na typach asynchronicznych** — `CompletableFuture`, `Future`, reaktywnych `Mono`/`Flux`. Jego mechanizm to nałożenie limitu czasowego na obietnicę wyniku i anulowanie zadania, gdy się spóźni. Dla zwykłej, **synchronicznej** metody — takiej, która blokuje wątek i zwraca `RouteResult` — `@TimeLimiter` nie ma czego pilnować. Nie istnieje `Future`, który można by przerwać; wątek po prostu siedzi w wywołaniu blokującym.

Nasza metoda `calculateRoute` jest synchroniczna. Dlatego `@TimeLimiter` został z niej **celowo usunięty** — nie przez zapomnienie, lecz świadomie. Rolę limitu czasu pełni tutaj **read-timeout klienta HTTP**, ustawiony na `SimpleClientHttpRequestFactory`:

```java
SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
factory.setConnectTimeout(Duration.ofMillis(500));
factory.setReadTimeout(Duration.ofMillis(800)); // to jest nasz "time limiter"
```

To subtelny, ale fundamentalny punkt: jeśli wywołanie jest blokujące i wykonywane przez bibliotekę kliencką, **timeout należy do tej biblioteki**, nie do Resilience4j. Read-timeout 800 ms gwarantuje, że żaden wątek nie utknie na zawieszonym `trade-route-planner` na dłużej. Gdybyśmy dołożyli `@TimeLimiter`, dostalibyśmy albo wyjątek konfiguracji (zły typ zwracany), albo musielibyśmy sztucznie owijać metodę w `CompletableFuture.supplyAsync(...)` — czyli przenieść pracę na osobny wątek tylko po to, żeby móc go anulować. Dla wywołania synchronicznego to komplikacja bez wartości. Zasada do zapamiętania: **synchroniczne wołanie sieciowe = timeout na kliencie HTTP; asynchroniczna obietnica = `@TimeLimiter`.**

### Bulkhead — gródź wodoszczelna

Nazwa pochodzi z budownictwa okrętowego: gródź to przegroda, która sprawia, że przebicie kadłuba w jednym przedziale nie zatapia całego statku. W systemach rozproszonych Bulkhead **izoluje zasoby**, by jedna spowolniona zależność nie wyssała całej puli wątków obsługujących cały serwis.

Resilience4j oferuje dwie implementacje:

- **SemaphoreBulkhead** — semafor zliczający. Ogranicza liczbę **jednoczesnych** wywołań chronionej metody. Wywołanie wykonuje się na wątku wołającego; semafor jedynie wpuszcza lub odrzuca (`BulkheadFullException`). Lekki, bez przełączania kontekstu.
- **ThreadPoolBulkhead** — dedykowana pula wątków plus kolejka. Wywołanie jest zlecane do osobnej puli, co daje pełną izolację: nawet jeśli wszystkie wątki tej puli się zablokują, wątki obsługujące inne ścieżki pozostają wolne. Działa tylko z typami asynchronicznymi (zwraca `CompletableFuture`).

```yaml
resilience4j:
  bulkhead:
    instances:
      tradeRoute:
        max-concurrent-calls: 20     # SemaphoreBulkhead
        max-wait-duration: 10ms
  thread-pool-bulkhead:
    instances:
      tradeRoute:
        max-thread-pool-size: 16
        core-thread-pool-size: 8
        queue-capacity: 50
```

Dlaczego to chroni pulę wątków przed wyczerpaniem? Wyobraź sobie, że `trade-route-planner` zwalnia do 5 sekund na odpowiedź. Bez Bulkheadu każdy przychodzący request do `starport-registry` zajmuje wątek i czeka. Przy dość dużym ruchu cała pula serwera jest „zaparkowana" na jednej wolnej zależności — i serwis przestaje obsługiwać **wszystko inne**, także endpointy, które z trade-route nie mają nic wspólnego. SemaphoreBulkhead z `max-concurrent-calls: 20` mówi: „najwyżej 20 wątków naraz może czekać na trade-route; dwudziesty pierwszy dostaje natychmiast `BulkheadFullException` i wpada w fallback". Reszta puli żyje.

W naszym projekcie znaczenie ThreadPoolBulkhead jest dodatkowo stłumione przez **wątki wirtualne** (ADR-0012). Pod wątkami wirtualnymi blokujące wywołanie jest tanie — zablokowany wirtualny wątek nie trzyma wątku platformy, tylko go zwalnia (unmount). „Wyczerpanie puli wątków platformy" przestaje być realnym zagrożeniem, bo platforma nie jest już wąskim gardłem. Dlatego pełna izolacja przez osobną pulę (ThreadPoolBulkhead) traci większość uzasadnienia — a do tego kłóci się z modelem wątków wirtualnych, narzucając z powrotem ograniczoną pulę platformową. Jeśli już sięgać po Bulkhead w tym świecie, to po **SemaphoreBulkhead** jako ogranicznik współbieżności wobec konkretnej zależności, nie po izolację pul.

### RateLimiter — token bucket

RateLimiter ogranicza **tempo** wywołań, niezależnie od współbieżności. Resilience4j używa modelu zbliżonego do *token bucket*: w każdym oknie `refresh-period` dostępna jest pula `limit-for-period` zezwoleń. Wywołanie pobiera zezwolenie; gdy pula się wyczerpie, czeka maksymalnie `timeout-duration`, a potem dostaje `RequestNotPermitted`.

```yaml
resilience4j:
  ratelimiter:
    instances:
      tradeRoute:
        limit-for-period: 50        # 50 zezwoleń...
        limit-refresh-period: 1s    # ...co sekundę
        timeout-duration: 25ms      # ile czekać na wolne zezwolenie
```

RateLimiter pełni dwie role naraz. Po pierwsze **chroni zależność** — jeśli `trade-route-planner` deklaruje, że zniesie 50 req/s, nie ma sensu go zalewać; nadmiar lepiej odrzucić u źródła, niż wywołać u niego przeciążenie. Po drugie **chroni nas samych** — limit jest formą kontraktu i samodyscypliny, broni przed sytuacją, w której nasz retry albo nagły skok ruchu zamienia się w niezamierzony atak DoS na sąsiada. Różnica względem Bulkheadu jest istotna: Bulkhead liczy *ile naraz*, RateLimiter liczy *ile na sekundę*. Te dwa wymiary są ortogonalne i często warto je łączyć.

### Kolejność aspektów — rzecz, która zaskakuje seniorów

Teraz najważniejsze. Gdy na jednej metodzie zawiesimy kilka adnotacji Resilience4j, **nie składają się one w dowolnej kolejności**. Spring Boot Starter narzuca ustaloną hierarchię dekoratorów:

```
Retry( CircuitBreaker( RateLimiter( Bulkhead( Function ) ) ) )
```

Czytaj od zewnątrz do środka. **Retry jest najbardziej zewnętrzny**, a sama funkcja biznesowa — najgłębiej. To znaczy, że jedno „wywołanie z perspektywy Retry" zawiera w sobie pełny cykl: sprawdzenie limitu tempa, zajęcie miejsca w grodzi, przejście przez bezpiecznik i dopiero potem właściwą pracę.

Co to oznacza praktycznie, prześledźmy na pojedynczej próbie:

1. **Retry** decyduje, czy w ogóle podejmujemy (kolejną) próbę.
2. **CircuitBreaker** — jeśli obwód jest otwarty, próba zostaje ucięta natychmiast, jeszcze zanim pomyślimy o limicie czy grodzi.
3. **RateLimiter** — pobiera zezwolenie na tempo.
4. **Bulkhead** — zajmuje slot współbieżności.
5. **Function** — dopiero teraz leci realne wywołanie HTTP.

Dlaczego ta kolejność zaskakuje? Bo z niej wynikają nieoczywiste, czasem niepożądane interakcje:

- **Retry na zewnątrz CircuitBreakera** oznacza, że każda ponowiona próba osobno melduje wynik do bezpiecznika. Seria retry na padającej zależności **szybciej otworzy obwód** — co bywa pożądane, ale często ludzie spodziewają się odwrotnie.
- **Retry na zewnątrz RateLimitera** oznacza, że ponowienia **konsumują zezwolenia**. Agresywny retry potrafi sam wyczerpać własny limit tempa i zacząć dostawać `RequestNotPermitted` — klasyczny strzał we własną stopę.
- **CircuitBreaker wewnątrz Retry, a na zewnątrz RateLimitera** sprawia, że gdy obwód jest otwarty, w ogóle nie dotykamy limitera ani grodzi — odrzucenie jest najtańsze z możliwych.

Gdyby kolejność była odwrotna — Bulkhead na zewnątrz Retry — pojedyncza próba trzymałaby slot grodzi przez **wszystkie** ponowienia. Inny obraz, inne właściwości. Stąd morał: zanim założysz, jak system się zachowa pod obciążeniem, **sprawdź, w jakiej kolejności owinięte są dekoratory**, bo to ona, a nie sama obecność adnotacji, definiuje zachowanie.

#### Jak Spring Boot ustala tę kolejność

Każdy aspekt Resilience4j implementuje `Ordered` i ma przypisany `order`. Im **niższa** wartość `order`, tym aspekt jest bardziej **zewnętrzny** (wyższy priorytet, wcześniej w łańcuchu proxy). Domyślne wartości układają się tak, że Retry dostaje najniższy order, a Bulkhead najwyższy — co daje opisaną hierarchię. Wartości można nadpisać właściwościami, np.:

```yaml
resilience4j:
  retry:
    retry-aspect-order: 2000        # niższy = bardziej zewnętrzny
  circuitbreaker:
    circuit-breaker-aspect-order: 1500
```

W praktyce **rzadko** warto to ruszać — domyślna kolejność jest przemyślana i zgodna z dokumentacją. Ale trzeba wiedzieć, że dźwignia istnieje, oraz że to mechanizm `@Order`/`Ordered` Springa, ten sam, który porządkuje dowolne aspekty AOP. Gdy mieszasz adnotacje Resilience4j z własnymi `@Around`, ich `order` współzawodniczy w tej samej skali.

W naszym projekcie cała ta dyskusja jest na razie teoretyczna w jednym punkcie: aktywne są tylko Retry i CircuitBreaker, więc realny łańcuch to `Retry(CircuitBreaker(Function))`. Ale dokładnie ta para jest najczęstszym źródłem niespodzianek — bo to tu „retry karmi bezpiecznik". Resztę warstw znasz teraz jako gotową do dołożenia mapę, ze świadomością, gdzie każda z nich wpina się w kolejność.

### Pytania kontrolne

1. Dlaczego `@TimeLimiter` nie zadziała na synchronicznej metodzie `calculateRoute` i co pełni jego rolę w tym projekcie?
2. Czym różni się SemaphoreBulkhead od ThreadPoolBulkhead i dlaczego pod wątkami wirtualnymi (ADR-0012) ten drugi traci na znaczeniu?
3. Jak Bulkhead chroni pulę wątków serwisu przed wyczerpaniem przez jedną wolną zależność?
4. Wyjaśnij model token bucket RateLimitera na podstawie `limit-for-period` i `limit-refresh-period`. Przed czym chroni nas, a przed czym chroni zależność?
5. Podaj kolejność dekoratorów Resilience4j od najbardziej zewnętrznego do najgłębszego. Dlaczego umiejscowienie Retry *na zewnątrz* RateLimitera bywa pułapką?
6. Którym mechanizmem Springa ustalana jest kolejność aspektów i co oznacza niższa wartość `order`?


---

## Rozdział 5 — Resilience4j w Spring Boot: integracja i konfiguracja

W poprzednim rozdziale ustaliliśmy, *dlaczego* potrzebujemy circuit breakera i retry. Teraz schodzimy na poziom kodu: jak Resilience4j realnie wpina się w aplikację Spring Boot, gdzie kończy się jego odpowiedzialność, a zaczyna nasza, i co dostajemy „za darmo", a co musimy świadomie skonfigurować. Pokażę to na żywym adapterze z naszego projektu — `TradeRoutePlannerHttpAdapter`.

### Dwa światy zależności: starter Spring Cloud kontra biblioteka

Pierwsza decyzja, która myli wielu ludzi: są dwa różne sposoby wciągnięcia Resilience4j i nie są wymienne na poziomie adnotacji.

- **`resilience4j-spring-boot3`** — natywny starter samego Resilience4j. Daje adnotacje z pakietu `io.github.resilience4j.*` (`@CircuitBreaker`, `@Retry`, `@RateLimiter`, `@Bulkhead`, `@TimeLimiter`), konfigurację pod kluczem `resilience4j.*`, własne health indicatory i metryki.
- **`spring-cloud-starter-circuitbreaker-resilience4j`** — abstrakcja Spring Cloud Circuit Breaker. Daje programowy interfejs `CircuitBreakerFactory` / `Customizer<Resilience4JCircuitBreakerFactory>`, niezależny od dostawcy (pod spodem może być Resilience4j albo inny silnik).

W naszym projekcie używamy adnotacji `@CircuitBreaker`/`@Retry` na metodach — a te pochodzą z biblioteki Resilience4j i z mechanizmu AOP startera `resilience4j-spring-boot3`. To kluczowe: sam `spring-cloud-starter-...` **nie** włącza przetwarzania tych adnotacji. Mimo to w `pom.xml` widnieje `spring-cloud-starter-circuitbreaker-resilience4j` — bo ściąga tranzytywnie `resilience4j-spring-boot3` wraz ze spójną wersją zarządzaną przez BOM Spring Cloud, a my korzystamy z konfiguracji `resilience4j.*` i adnotacji. To wygodny, wersjonowo bezpieczny sposób dostania całego zestawu.

```xml
<dependencies>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
  </dependency>
  <dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-circuitbreaker-resilience4j</artifactId>
  </dependency>
  <dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
  </dependency>
</dependencies>
```

Te trzy zależności razem dają komplet: actuator wystawia endpointy, Resilience4j (tranzytywnie) dostarcza adnotacje i AOP, a `micrometer-registry-prometheus` eksportuje metryki w formacie Prometheusa. Zwróćcie uwagę: **nie ma tu żadnej biblioteki do liczników**, którą napisalibyśmy sami.

### Model AOP — adnotacja owija bean, nie wywołanie HTTP

Najważniejsza rzecz do zrozumienia: `@CircuitBreaker` i `@Retry` działają przez proxy AOP Springa. Resilience4j owija **wywołanie metody springowego beana**, a nie konkretne wywołanie sieciowe. Bibliotece jest zupełnie obojętne, co dzieje się w środku metody — czy to `RestClient`, `RestTemplate`, `WebClient`, zapytanie JDBC, czy czysta arytmetyka. Liczy się, że metoda jest publiczna, leży w springowym beanie i jest wywołana *z zewnątrz* (przez proxy, nie self-invocation).

To uwalnia od fałszywego przekonania, że circuit breaker „rozumie HTTP". Nie rozumie. On widzi: rzucony wyjątek albo czas trwania. Jeśli metoda rzuci wyjątek pasujący do konfiguracji `recordExceptions`, breaker zalicza to jako porażkę. Jeśli przejdzie — jako sukces. Dlatego ten sam mechanizm chroni nas niezależnie od warstwy transportu.

W naszym przypadku metoda blokuje wątek na czas żądania HTTP (synchroniczny `RestClient`). I tu wchodzi w grę architektura projektu: pod wątkami wirtualnymi (ADR-0012) blokujące I/O jest tanie — wątek-nośnik nie jest marnowany. Dlatego **sama adnotacja wystarcza**; nie potrzebujemy reaktywnego wariantu ani operatorów. O reaktywności powiem na końcu, bo to istotny wyjątek.

### Realny adapter: `@Retry` + `@CircuitBreaker` + fallback

```java
@Component
class TradeRoutePlannerHttpAdapter implements TradeRoutePlannerPort {

    @Retry(name = "trade-route-planner")
    @CircuitBreaker(name = "trade-route-planner", fallbackMethod = "routeUnavailableFallback")
    public Route calculateRoute(ReserveBayCommand command) {
        return observation.observe(() ->        // Micrometer Observation "reservations.route.plan"
            restClient.post()
                .uri("/api/route-plan")
                .body(toRequest(command))
                .retrieve()
                .body(Route.class)
        );
    }

    private Route routeUnavailableFallback(ReserveBayCommand command, Throwable t) {
        meterRegistry.counter("reservations_route_plan_errors_total",
                "errorType", "circuit_open").increment();
        throw new RouteUnavailableException(
                "Trade route planner unavailable for bay " + command.bayId(), t);
    }
}
```

Kolejność adnotacji ma znaczenie semantyczne. Domyślnie Resilience4j stosuje aspekty wg ustalonego porządku, gdzie **Retry jest na zewnątrz CircuitBreakera** — czyli najpierw breaker decyduje, czy w ogóle wpuścić wywołanie, a retry ponawia całość. Gdy breaker jest otwarty, każda próba retry natychmiast zwraca `CallNotPermittedException`, więc nie zalewamy padającej usługi ponowieniami. To dokładnie to, czego chcemy.

### `fallbackMethod` — sygnatura i degradacja sterowana

Fallback w Resilience4j ma sztywne reguły sygnatury:

- **Te same argumenty co metoda chroniona**, opcjonalnie z **dodatkowym ostatnim parametrem typu `Throwable`** (lub konkretniejszym wyjątkiem). Tu: `(ReserveBayCommand command, Throwable t)` — komplet argumentów oryginału plus przyczyna.
- **Zgodny typ zwracany** — fallback musi zwracać `Route` (lub podtyp). Nie da się „zamienić" kontraktu metody.
- Może być `private` — Resilience4j znajduje ją refleksją w tej samej klasie.

I teraz najważniejszy morał tego rozdziału, spójny z tytułem talku „Działa ≠ działa i ja o tym wiem". **Fallback to nie jest synonim ciszy ani pustego wyniku.** Fallback to *sterowana degradacja* — świadoma decyzja, co system robi, gdy zależność padła. Tutaj świadomie wybieramy: nie zwracamy „jakiejś" trasy, nie zwracamy `null`, nie udajemy sukcesu. Inkrementujemy licznik `reservations_route_plan_errors_total{errorType="circuit_open"}` (audyt i alerting) i **rzucamy wyjątek domenowy** `RouteUnavailableException`, który warstwa kontrolera mapuje na **HTTP 409 Conflict**. Klient dostaje czytelny, honest sygnał: „nie mogę teraz zaplanować trasy", a nie zafałszowaną odpowiedź, która rozłazi się dalej w systemie. To jest właśnie „wiem, że nie działa" zakodowane wprost.

### Hierarchia konfiguracji: `default` plus per-instancja

Konfigurację trzymamy w `application.yml` pod `resilience4j.circuitbreaker.instances` oraz `resilience4j.retry.instances`. Model jest dwupoziomowy: definiujesz `configs.default` (wspólny szablon) i nadpisujesz wybrane pola per instancja. Nazwa instancji (`trade-route-planner`) musi się zgadzać z atrybutem `name` z adnotacji.

```yaml
resilience4j:
  circuitbreaker:
    configs:
      default:
        sliding-window-type: COUNT_BASED
        sliding-window-size: 20
        failure-rate-threshold: 50
        wait-duration-in-open-state: 10s
        permitted-number-of-calls-in-half-open-state: 3
        register-health-indicator: true
    instances:
      trade-route-planner:
        base-config: default
        failure-rate-threshold: 60          # nadpisanie per-instancja
        record-exceptions:
          - org.springframework.web.client.RestClientException
  retry:
    configs:
      default:
        max-attempts: 3
        wait-duration: 200ms
        enable-exponential-backoff: true
        exponential-backoff-multiplier: 2
    instances:
      trade-route-planner:
        base-config: default
```

Instancja dziedziczy z `default` przez `base-config` i punktowo nadpisuje, co trzeba — tu podnosimy próg awaryjności do 60% i zawężamy, które wyjątki liczą się jako porażka (`record-exceptions`). Reszta pól spada z `default`. Ta hierarchia pozwala mieć jedną spójną politykę dla wielu zależności i odstępstwa tylko tam, gdzie są uzasadnione.

### Health indicator — stan breakera w `/actuator/health`

`register-health-indicator: true` sprawia, że stan każdego breakera ląduje w `/actuator/health` (komponent `circuitBreakers`). Przy otwartym obwodzie health pokazuje status `CIRCUIT_OPEN`. Uwaga praktyczna: domyślnie Resilience4j traktuje stan `OPEN`/`FORCED_OPEN` jako `DOWN`, co może zepsuć probe'y Kubernetesa, jeśli `management.endpoint.health.show-details` i grupy są źle dobrane. Trzymamy ten wskaźnik poza grupą `readiness`, żeby otwarty breaker degradował funkcję, a nie ubijał pod.

### Event consumery — `EventPublisher` do logowania i audytu

Każdy `CircuitBreaker` i `Retry` wystawia `EventPublisher`. To miejsce na logowanie zmian stanu czy audyt — bez zaśmiecania logiki biznesowej. Pobieramy instancję z rejestru i podpinamy konsumenty:

```java
@Component
class CircuitBreakerEventLogging {

    CircuitBreakerEventLogging(CircuitBreakerRegistry registry) {
        var cb = registry.circuitBreaker("trade-route-planner");
        cb.getEventPublisher()
            .onStateTransition(e -> log.warn("CB {} : {} -> {}",
                e.getCircuitBreakerName(),
                e.getStateTransition().getFromState(),
                e.getStateTransition().getToState()))
            .onError(e -> log.error("CB {} recorded error after {} ms: {}",
                e.getCircuitBreakerName(),
                e.getElapsedDuration().toMillis(),
                e.getThrowable().toString()));
    }
}
```

`onStateTransition` daje nam dokładny moment `CLOSED → OPEN` (i z powrotem przez `HALF_OPEN`) — bezcenne na korelacji z incydentem. `onError` rejestruje każdą zaliczoną porażkę z czasem trwania. Są też m.in. `onSuccess`, `onCallNotPermitted` (odrzucenie przy otwartym obwodzie) i `onIgnoredError`.

### Wariant reaktywny — gdy naprawdę `WebClient`

Adnotacja działa też na metodach zwracających `Mono`/`Flux`, ale żeby breaker poprawnie liczył sygnały reaktywne, musi być na classpath **`resilience4j-reactor`**. Wtedy aspekt wpina pod spodem operator `CircuitBreakerOperator` (oraz `RetryOperator`). Można je też nakładać ręcznie:

```java
return webClient.get().retrieve().bodyToMono(Route.class)
        .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
        .transformDeferred(RetryOperator.of(retry));
```

U nas tego **nie** ma — blokujący `RestClient` na wątkach wirtualnych załatwia sprawę przy prostszym modelu. Reaktor wciągamy tylko, gdy realnie idziemy strumieniami.

### Metryki za darmo — zero własnych liczników

Na koniec rzecz, którą łatwo przegapić. Dzięki obecności `micrometer-registry-prometheus` i actuatora, Resilience4j **sam** publikuje metryki: `resilience4j_circuitbreaker_state`, `resilience4j_circuitbreaker_calls_total{kind="successful|failed"}`, `resilience4j_circuitbreaker_failure_rate`, `resilience4j_retry_calls_total{kind="successful_with_retry|failed_with_retry|...}` i inne. Trafiają wprost na `/actuator/prometheus`. **Nie piszemy ani jednej linijki kodu liczników** dla samego breakera/retry — to wpada z biblioteki i integracji Micrometera.

Nasz jedyny *własny* licznik — `reservations_route_plan_errors_total` w fallbacku — istnieje nie po to, by liczyć awarie breakera (te już mamy), lecz by uchwycić **biznesową** konsekwencję: ile rezerwacji odbiło się o 409 z powodu otwartego obwodu, oznakowane `errorType`. To metryka domenowa, nie infrastrukturalna. A całe wywołanie owinięte jest w Observation `reservations.route.plan`, dzięki czemu w jednym śladzie spina się trace, czas i wynik — niezależnie od tego, czy breaker zadziałał, czy nie.

### Pytania kontrolne

1. Czym różni się `spring-cloud-starter-circuitbreaker-resilience4j` od `resilience4j-spring-boot3` i który z nich faktycznie włącza przetwarzanie adnotacji `@CircuitBreaker`/`@Retry`?
2. Dlaczego circuit breaker działa tak samo dla `RestClient`, `WebClient` i JDBC? Co konkretnie „widzi" aspekt AOP?
3. Jaką sygnaturę musi mieć metoda fallback względem metody chronionej i dlaczego nasz fallback *rzuca* `RouteUnavailableException` zamiast zwrócić pustą trasę?
4. Jak `base-config: default` współgra z nadpisaniami per-instancja i co się stanie, gdy nazwa instancji w YAML nie zgadza się z `name` z adnotacji?
5. Co włącza `register-health-indicator: true` i na co uważać przy probe'ach readiness Kubernetesa?
6. Kiedy potrzebujemy `resilience4j-reactor` i `CircuitBreakerOperator`, a kiedy sama adnotacja na blokującej metodzie wystarcza — i jak wiążą się z tym wątki wirtualne?
7. Które metryki Resilience4j dostajemy bez pisania kodu, a po co mimo to istnieje nasz własny licznik `reservations_route_plan_errors_total`?


---

## Rozdział 6 — Observability i Micrometer

Tytuł tego talku brzmi „Działa ≠ działa i ja o tym wiem". Cały poprzedni rozdział o Resilience4j dotyczył pierwszej części — *jak sprawić, żeby działało* mimo awarii zależności. Ten rozdział jest o drugiej, trudniejszej części: *i ja o tym wiem*. Bo circuit breaker, który otworzył się o 3:00 w nocy i uratował wam system, jest bezwartościowy, jeśli dowiecie się o tym dopiero z reklamacji klienta o 9:00. Observability to dyscyplina, która zamienia „coś jest nie tak" w „wiem dokładnie co, gdzie i od kiedy".

### Trzy filary: metryki, trace, logi

Mówimy o trzech filarach observability i ważne jest, żeby zrozumieć, że one się nie dublują — każdy odpowiada na inne pytanie.

**Metryki** odpowiadają na pytanie *„czy i jak bardzo?"*. To zagregowane liczby w czasie: ile żądań na sekundę, jaki percentyl latencji, jaki procent wywołań kończy się błędem. Metryki są tanie (kilka bajtów na próbkę), mają stałą kardynalność i dlatego nadają się do alertów oraz dashboardów obejmujących tygodnie historii. Metryka powie wam, że *failure rate skoczył z 2% do 40%*.

**Trace** odpowiada na pytanie *„gdzie konkretnie?"*. Pojedynczy ślad to podróż jednego żądania przez wszystkie mikroserwisy — gateway, starport-registry, outbox, Kafka, telemetry. Trace pokaże wam, że te 40% błędów to timeouty na jednym konkretnym spanie wywołania zewnętrznego API, a nie problem z bazą.

**Logi** odpowiadają na pytanie *„dlaczego dokładnie?"*. To zdarzenia z pełnym kontekstem — stacktrace, payload, identyfikator korelacji. Log powie wam, że timeout wynikał z `SSLHandshakeException` po rotacji certyfikatu.

Filary się uzupełniają, bo działają na różnych poziomach agregacji. Metryka alarmuje (tanio, na całej populacji), trace lokalizuje (na jednym żądaniu), log wyjaśnia (z pełnym kontekstem). Próba zastąpienia jednego drugim kończy się źle: metryki z wysoką kardynalnością to bomba kosztowa, a logi jako źródło wskaźników to wolno i drogo. Ten rozdział skupia się na pierwszym filarze — na metrykach i Micrometerze.

### Micrometer jako fasada — „SLF4J dla metryk"

Najlepsza intuicja dla Micrometera: to jest SLF4J, ale dla metryk. Tak jak piszecie `logger.info(...)` nie wiedząc, czy pod spodem siedzi Logback, Log4j2 czy java.util.logging, tak samo w Micrometerze instrumentujecie kod wobec abstrakcyjnego `MeterRegistry`, nie wiedząc, czy metryki polecą do Prometheusa, OTLP, Datadoga czy CloudWatcha.

```java
@Service
public class RoutePlanningService {

    private final Counter routePlanErrors;

    public RoutePlanningService(MeterRegistry registry) {
        this.routePlanErrors = Counter.builder("reservations.route.plan.errors")
                .description("Nieudane próby zaplanowania trasy")
                .tag("errorType", "unknown")
                .register(registry);
    }
}
```

Zwróćcie uwagę: kod aplikacji zna tylko interfejs `MeterRegistry`. To, że w naszym projekcie wpięliśmy `micrometer-registry-prometheus` i Prometheus scrape'uje endpoint `/actuator/prometheus`, jest decyzją konfiguracyjną, a nie częścią kodu domenowego. Gdybyśmy jutro migrowali na OTLP, klasa `RoutePlanningService` nie zmieniłaby ani jednej linijki — wymienilibyśmy zależność rejestru. To jest sedno fasady: instrumentacja jest jedna, backendy wymienne.

### Typy metryk

Micrometer daje kilka typów meterów i wybór właściwego ma znaczenie, bo określa, jakie szeregi czasowe powstaną.

**Counter** — wartość monotonicznie rosnąca. Liczba żądań, liczba błędów, liczba przetworzonych wiadomości. Nigdy nie maleje (poza restartem procesu, który Prometheus obsługuje przez detekcję resetów). Counter eksportuje się z sufiksem `_total`.

```java
routePlanErrors.increment();
```

**Gauge** — wartość chwilowa, która może rosnąć i maleć: rozmiar kolejki, liczba aktywnych połączeń, zajętość puli. I tu jest **najczęstsza pułapka tego rozdziału**. Gauge nie przechowuje wartości — przechowuje *supplier*, lambdę, którą Micrometer woła **na każdym scrape'u**.

```java
// PUŁAPKA: ten count() po bazie wykona się przy KAŻDYM scrape
Gauge.builder("reservations.pending", this, s -> s.repository.countPending())
     .register(registry);
```

Prometheus scrape'uje co 3–15 sekund, razy liczba replik. Jeśli w lambdzie siedzi zapytanie do bazy albo kosztowne obliczenie, właśnie podpięliście sobie ukryty, nieustanny ruch do bazy proporcjonalny do gęstości scrape'u i liczby instancji. Rozwiązanie: liczcie wartość okresowo w `@Scheduled` i publikujcie przez `MultiGauge.register(...)`, a sam gauge niech tylko czyta scache'owane pole.

```java
@Scheduled(fixedRate = 30_000)
void refreshPending() {
    long value = repository.countPending();   // jedno zapytanie co 30s
    multiGauge.register(List.of(
        MultiGauge.Row.of(Tags.of("state", "pending"), value)), true);
}
```

**Timer** — mierzy czas trwania i częstotliwość krótkich operacji jednocześnie. Jeden Timer daje wam trzy rzeczy naraz: `count` (ile wywołań), `sum` (łączny czas — z czego liczycie średnią) i `max`. Z włączonym histogramem dochodzą buckety, z których Prometheus liczy percentyle (p95, p99).

```java
Timer timer = Timer.builder("route.planning.duration")
        .publishPercentileHistogram()
        .register(registry);

timer.record(() -> planner.plan(request));
```

**DistributionSummary** — jak Timer, ale dla wartości, które nie są czasem: rozmiar payloadu w bajtach, liczba pozycji w koszyku. Też daje count/sum/max i histogram.

**LongTaskTimer** — do zadań *długotrwałych*, które chcecie mierzyć *w trakcie* ich trwania, a nie po zakończeniu. Zwykły Timer zarejestruje batcha trwającego 2 godziny dopiero po jego zakończeniu; LongTaskTimer powie wam *teraz*, że jeden batch leci już 90 minut i ilu takich aktywnych jest w tej chwili.

### Tagi, wymiary i kardynalność

Każdy meter ma nazwę i zbiór tagów (wymiarów). Tagi pozwalają jeden meter rozbić na podserie — `http.server.requests` z tagami `method`, `status`, `uri`. To potężne, ale to też miejsce, gdzie najłatwiej wysadzić system w powietrze.

Kluczowe pojęcie to **kardynalność**: liczba unikalnych kombinacji wartości tagów. Każda kombinacja to osobny szereg czasowy, który Prometheus trzyma w pamięci i na dysku. Tagi muszą być **low-cardinality** — o ograniczonym, znanym zbiorze wartości: `state` (open/closed/half_open), `kind` (successful/failed), `errorType`. Takich wartości są jednostki.

Złota zasada brzmi: **nigdy nie wkładajcie do labela identyfikatora ani danych użytkownika**. ID rezerwacji, email, UUID żądania, ścieżka z parametrem — to są wartości o nieograniczonej kardynalności. Label `userId` w systemie z milionem użytkowników to milion szeregów czasowych z jednego metera. To się nazywa *cardinality explosion* i kończy się OOM-em na Prometheusie. Tożsamość konkretnego żądania należy do trace'a i logów, nie do metryk.

```java
// DOBRZE — skończony, mały zbiór wartości
Counter.builder("reservations.route.plan.errors")
       .tag("errorType", "circuit_open")   // circuit_open|domain|infrastructure|empty_response
       .register(registry);

// ŹLE — eksplozja serii, jeden szereg na rezerwację
Counter.builder("reservations.route.plan.errors")
       .tag("reservationId", reservation.getId())   // NIGDY
       .register(registry);
```

### Base units i konwencja nazw

Micrometer normalizuje jednostki bazowe: czas trzymamy w sekundach, rozmiar w bajtach. Dlatego metryki czasowe eksportują się jako `..._seconds`. Nazwy piszemy kropkowane, hierarchicznie od ogółu do szczegółu (`reservations.route.plan.errors`), a eksporter Prometheusowy sam przetłumaczy kropki na podkreślenia i dołoży sufiks `_total` dla counterów. Pisanie metryki domenowej zgodnie z tą konwencją oszczędza wam później bólu przy mapowaniu nazw na dashboardach.

### Resilience4j wpina się w MeterRegistry automatycznie

Tu domyka się most z poprzedniego rozdziału. Resilience4j, gdy w klasie ścieżki jest `MeterRegistry`, **sam rejestruje swoje metryki** — nie piszecie ani linijki instrumentacji. Dostajecie za darmo:

- `resilience4j_circuitbreaker_state` — stan obwodu (gauge per `state`),
- `resilience4j_circuitbreaker_failure_rate`, `_slow_call_rate` — wskaźniki sterujące przejściami,
- `resilience4j_circuitbreaker_calls_seconds_count{kind}` i `_calls_seconds_max` — liczność i czas wywołań z tagiem `kind`,
- `resilience4j_circuitbreaker_not_permitted_calls_total` — wywołania odrzucone przy otwartym obwodzie,
- `resilience4j_retry_calls{kind}` — statystyki ponowień.

To są metryki **techniczne, infrastrukturalne**. Mówią językiem wzorca: „obwód jest otwarty", „failure rate to 60%".

### Metryka domenowa jako uzupełnienie

I tu pojawia się ostatnia, ważna myśl. Metryki R4j mówią językiem *infrastruktury*. Biznes mówi językiem *produktu*. Dlatego w projekcie utrzymujemy **jeden jedyny ręczny licznik**:

```java
reservations_route_plan_errors_total{errorType="circuit_open|domain|infrastructure|empty_response"}
```

Różnica jest fundamentalna. `resilience4j_..._not_permitted_calls_total` powie wam, że *obwód odrzucił wywołanie*. Ale `reservations_route_plan_errors_total{errorType="circuit_open"}` powie wam, że *klient nie dostał trasy, bo obwód był otwarty* — i jednym zapytaniem porównacie to z `errorType="domain"` (poprawne odrzucenie biznesowe) czy `errorType="empty_response"`. Metryka techniczna mówi „mechanizm zadziałał", metryka domenowa mówi „produkt zawiódł i z jakiego powodu". Alertujcie na drugiej.

### Pułapka, którą widać tylko na produkcji: wątki wirtualne

Na koniec konkret, który ilustruje tytuł talku. Po włączeniu wątków wirtualnych metryki `tomcat_threads_*` zaczynają raportować `-1` — są bezużyteczne, bo nie ma już stałej puli wątków do zliczenia. Ktoś, kto trzyma dashboard saturacji webu na `tomcat_threads_busy`, ma zielony wykres pokazujący nieprawdę. Prawdziwą saturację warstwy web pod wątkami wirtualnymi czytajcie z `http_server_requests_active_seconds_count` — liczby żądań przetwarzanych w danej chwili. To jest dokładnie „działa ≠ działa i ja o tym wiem": metryka istnieje, dashboard się renderuje, a mierzy `-1`.

### Pytania kontrolne

1. Na jakie trzy różne pytania odpowiadają metryki, trace i logi — i dlaczego nie da się jednym zastąpić drugiego?
2. Dlaczego nazywamy Micrometer „fasadą" i co konkretnie pozwala nam to zmienić bez dotykania kodu domenowego?
3. Na czym polega pułapka Gauge'a z supplierem czytającym po bazie i jak ją obejść?
4. Co dostajecie z jednego Timera, czego nie da wam Counter, i kiedy sięgnąć po LongTaskTimer zamiast Timera?
5. Dlaczego `userId` albo `reservationId` w labelu to katastrofa, a `state` czy `errorType` to dobry tag?
6. Czym różni się `resilience4j_circuitbreaker_not_permitted_calls_total` od `reservations_route_plan_errors_total{errorType="circuit_open"}` i na której z nich oprzecie alert biznesowy?
7. Dlaczego pod wątkami wirtualnymi `tomcat_threads_busy` kłamie i czym je zastąpić?


---

## Rozdział 7 — Observation API, tracing i OpenTelemetry

Do tej pory mówiliśmy o tym, jak system się broni: timeouty, retry, circuit breaker, bulkhead. Ale obrona bez wglądu jest ślepa. Skąd wiem, że breaker się otworzył akurat dla planera tras, a nie dla rejestru ładunków? Skąd wiem, że te 800 ms latencji to nie mój serwis, tylko serwis trzy skoki dalej? W tym rozdziale pokazuję warstwę, dzięki której „działa ≠ działa i ja o tym wiem" przestaje być sloganem, a staje się panelem, z którego jednym kliknięciem skaczę do konkretnego żądania, które się wywróciło.

### Jedna obserwacja, trzy sygnały

Spring Boot 3 wprowadził Micrometer **Observation API**. Idea jest brutalnie prosta: instrumentujesz fragment kodu **raz**, a z tej jednej obserwacji powstają trzy rzeczy — metryka (timer/licznik), span w tracingu i opcjonalnie wpis logu skorelowany z trace ID. Nie piszesz osobno `Timer.record(...)`, osobno `tracer.spanBuilder(...)`, osobno logowania correlation ID. Jedna instrumentacja, trzy sygnały wychodzące tymi samymi „rurami".

W naszym `starport-registry` wywołanie planera tras jest owinięte dokładnie w taki sposób:

```java
private RoutePlan callTradeRoutePlanner(ReservationRequest req) {
    return Observation
        .createNotStarted("reservations.route.plan", observationRegistry)
        .lowCardinalityKeyValue("planner.region", req.region())
        .observe(() -> tradeRoutePlannerClient.plan(req));
}
```

`observe(...)` robi cztery rzeczy w odpowiedniej kolejności: startuje obserwację (otwiera span, uruchamia timer), wykonuje lambdę, w razie wyjątku oznacza obserwację jako błędną (`error`), a na końcu — niezależnie od wyniku — ją zamyka. Nazwa `reservations.route.plan` staje się jednocześnie nazwą metryki (po translacji `reservations_route_plan_seconds`) i nazwą spana w Tempo. To samo źródło prawdy.

Warto rozdzielić tagi: `lowCardinalityKeyValue` (np. region, status) trafia do metryk jako wymiar — pamiętaj, że każda kombinacja to osobna seria czasowa, więc trzymaj kardynalność nisko. `highCardinalityKeyValue` (np. ID rezerwacji) trafia tylko do spana, gdzie wysoka kardynalność jest pożądana — bo właśnie po takim ID szukasz konkretnego żądania.

### Registry i Handlery — kto zamienia obserwację w sygnały

Mechanizm spina się wokół `ObservationRegistry`. Sama obserwacja niczego nie „wie" o Prometheusie ani o Tempo — ona tylko emituje zdarzenia cyklu życia (start, stop, error, scope open/close). To **ObservationHandlery** zarejestrowane w registry decydują, co z tych zdarzeń powstaje:

- `DefaultMeterObservationHandler` — z każdej obserwacji robi timer i licznik w `MeterRegistry` (→ Prometheus).
- handler tracingu (`PropagatingSenderTracingObservationHandler` i pokrewne, dostarczane przez Micrometer Tracing Bridge dla OpenTelemetry) — z obserwacji robi span i zarządza propagacją kontekstu.

```java
@Configuration
class ObservationConfig {

    @Bean
    ObservationRegistryCustomizer<ObservationRegistry> meterHandler(MeterRegistry registry) {
        return r -> r.observationConfig()
            .observationHandler(new DefaultMeterObservationHandler(registry));
    }
}
```

W praktyce Boot 3 konfiguruje te handlery autokonfiguracją — gdy na classpath jest `micrometer-tracing-bridge-otel` i eksporter OTLP, wszystko składa się samo. Klucz do zapamiętania: **dorzucenie/usunięcie handlera nie zmienia ani jednej linii instrumentacji**. Chcesz logi skorelowane z trace? Dokładasz handler. Wyłączasz tracing na devie? Zdejmujesz handler. Kod biznesowy z `Observation.createNotStarted(...)` zostaje nietknięty.

### Auto-instrumentacja klienta HTTP i propagacja W3C

Najmocniejsza część dzieje się bez naszego kodu. W Boot 3 `RestClient`, `RestTemplate` i `WebClient` są **auto-instrumentowane** przez Observation. Każde wyjście HTTP tworzy span klienta i — co kluczowe — wstrzykuje do żądania nagłówek **W3C `traceparent`**:

```
traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
```

Serwis odbierający (też na Boot 3, z auto-instrumentacją serwera) czyta ten nagłówek, kontynuuje ten sam trace i tworzy span dziecko. Dzięki temu `api-gateway → starport-registry → trade-route-planner` to **jeden ciągły trace**, nie trzy oderwane wyspy. W naszym systemie zweryfikowaliśmy nawet dłuższą ścieżkę: gateway → starport → outbox/Kafka → telemetry — jeden trace od początku do końca.

Jest tu pułapka, na którą wpadliśmy i którą muszę powiedzieć wprost. Jeśli budujesz `RestClient` przez `RestClient.builder()` i ręcznie go konfigurujesz, **omijasz customizery Micrometera** i instrumentacja przepada — span klienta się nie tworzy, `traceparent` nie leci, trace się rozjeżdża na granicy serwisu. Lekarstwem jest przepuszczenie buildera przez `RestClientBuilderConfigurer`:

```java
@Bean
RestClient tradeRoutePlannerRestClient(
        RestClient.Builder builder,           // wstrzyknięty, auto-skonfigurowany
        RestClientBuilderConfigurer configurer) {

    return configurer.configure(builder)      // zachowuje observation/tracing customizery
        .baseUrl("http://trade-route-planner")
        .build();
}
```

Reguła: nie twórz buildera od zera (`RestClient.builder()`), tylko wstrzyknij gotowy `RestClient.Builder` i — jeśli musisz dotknąć go ręcznie — przepuść przez configurer.

### Wątki wirtualne — gdzie ginie kontekst

Druga pułapka, specyficzna dla architektury na wątkach wirtualnych. Kontekst obserwacji (a więc i trace) żyje w `ThreadLocal`-podobnym scope. Gdy zlecasz pracę do **surowego** executora wirtualnych wątków, kontekst nie przenosi się na nowy wątek — span dziecko gubi rodzica, trace się urywa. Rozwiązanie to opakowanie executora w `ContextExecutorService` (z `io.micrometer.context`), który propaguje snapshot kontekstu na wątek wykonujący. Bez tego auto-instrumentacja działa „w 90%", a brakujące 10% to akurat te asynchroniczne skoki, których najbardziej szukasz w incydencie.

### Dwie warstwy: Observation vs metryki Resilience4j

Teraz rzecz, która najczęściej jest myląca, więc mówię ją dosłownie: **Observation i metryki Resilience4j to dwa różne, współpracujące mechanizmy, a nie jeden.**

- **Observation** daje pełen obraz przepływu: trace przez serwisy, timer latencji konkretnej operacji, korelację z logiem. To stąd bierze się ciągły trace gateway→starport→planner.
- **Metryki Resilience4j** to osobny mechanizm: liczniki i gauge stanu odporności — `resilience4j_circuitbreaker_state`, `resilience4j_circuitbreaker_failure_rate`, liczba odrzuceń bulkheada, zajętość retry. R4j **nie produkuje spanów**. Patrząc wyłącznie na metryki R4j widzisz, że breaker jest otwarty i failure rate skoczył do 70% — ale nie zobaczysz, które żądanie i dlaczego umarło.

Uczciwie: **pełny trace pochodzi z auto-instrumentacji Observation, nie z Resilience4j.** R4j mówi „warstwa odporności zadziałała i oto liczby"; Observation mówi „oto dokładne żądanie, które przeszło tę ścieżkę, z każdym skokiem i czasem". Dopiero obie razem dają odpowiedź na pytanie „działa ≠ działa": metryka R4j to alarm, trace to diagnoza.

### Exemplary — most z panelu do trace'u

I tu wchodzi spinający obie warstwy element: **exemplary** (ADR-0033). Exemplar to próbka dołączona do punktu na histogramie metryki, niosąca `trace_id` jednego konkretnego żądania, które ten punkt wytworzyło. W formacie ekspozycji Prometheusa wygląda to tak:

```
reservations_route_plan_seconds_bucket{le="1.0"} 42 # {trace_id="4bf92f...4736"} 0.97 1719580800
```

Praktyczny efekt: stoisz na panelu Grafany, widzisz skok `failure_rate` albo ogon latencji w histogramie, klikasz w kropkę-exemplar — i ląduje cię to w Tempo **na dokładnie tym trace'ie**, który ten outlier wygenerował. To jest fizyczny most metryka↔trace, łączący świat agregatów (R4j, histogramy) ze światem pojedynczych żądań (Observation). Bez exemplarów masz dwa szczelnie zamknięte światy i ręczne zgadywanie po timestampach.

### Sampling: head vs tail i czemu tail w kolektorze

Tracingu wszystkiego nie zapiszesz — to za drogie. Pytanie brzmi: kiedy decydujesz, co zachować?

- **Head sampling** — decyzja zapada na **początku** trace'u, zanim wiesz, jak się skończy. Tanie, ale ślepe: rzucasz kostką na starcie i z prawdopodobieństwem 1% zachowujesz. Problem: akurat ten trace, który się wywrócił z wyjątkiem, statystyka mogła wyrzucić do kosza.
- **Tail sampling** — decyzja zapada **na końcu**, gdy znasz cały trace: jego status, latencję, błędy. Możesz powiedzieć „zachowaj 100% błędów i wszystkiego powyżej progu latencji, a z reszty tylko 1%". To dokładnie te trace'y, które chcesz oglądać.

Tail sampling wymaga zebrania całego trace'u w jednym miejscu przed decyzją — dlatego robi się go w **OpenTelemetry Collectorze**, nie w aplikacji. Nasza polityka (ADR-0037/0035): błędy i wysoka latencja 100%, reszta tracingu 1%. Logi natomiast **nie są samplowane** — 100% trafia do Loki.

### Pipeline: OTLP → Collector → Tempo/Loki/Prometheus

Całość spina się w jeden potok telemetrii:

```
aplikacje (Observation + auto-instrumentacja)
        │  OTLP (gRPC/HTTP)
        ▼
OpenTelemetry Collector
  - tail sampling: błędy + wysoka latencja 100%, reszta traców 1%
  - logi 100%
        ├──────────────► Tempo        (trace)
        ├──────────────► Loki         (logi, skorelowane po trace_id)
        └──────────────► Prometheus + Thanos (metryki + exemplary)
```

Aplikacje mówią jednym protokołem — **OTLP** — i nie wiedzą nic o tym, gdzie ostatecznie ląduje sygnał. Collector jest centralnym punktem polityki: to on robi tail sampling, on rozdziela sygnały do backendów, on jest jedynym miejscem do zmiany decyzji „co zachowujemy", bez dotykania ani jednego serwisu. Tempo trzyma trace'y, Loki logi (skorelowane po `trace_id`), Prometheus + Thanos metryki wraz z exemplarami. Klikasz exemplar w Grafanie → Tempo → a z trace'u przeskakujesz do logów w Loki dla tego samego `trace_id`. Pętla diagnostyczna domyka się w sekundy, nie w minuty grepowania.

### Pytania kontrolne

1. Dlaczego mówimy, że Observation API to „jedna instrumentacja, trzy sygnały"? Co konkretnie powstaje z `Observation.createNotStarted(...).observe(...)`?
2. Jaką rolę pełni `ObservationHandler` i dlaczego dołożenie handlera logów nie wymaga zmiany kodu biznesowego?
3. Co się stanie z tracingiem, jeśli zbudujesz `RestClient` przez `RestClient.builder()` z pominięciem `RestClientBuilderConfigurer`? Jak to naprawić?
4. Wskaż konkretną różnicę między tym, co widać w metrykach Resilience4j, a tym, co daje trace z Observation. Która warstwa jest alarmem, a która diagnozą?
5. Czym jest exemplar i jaki problem rozwiązuje na styku panela metryk i Tempo?
6. Czemu tail sampling musi żyć w Collectorze, a nie w aplikacji? Jaką politykę próbkowania stosujemy (trace vs logi)?
7. Dlaczego pod wątkami wirtualnymi potrzebny jest `ContextExecutorService` i co psuje się bez niego?


---

## Rozdział 8 — Prometheus, PromQL i alerting

Mamy już Resilience4j, który liczy retry, timeouty i stany circuit breakera. Mamy Micrometer, który te liczniki wystawia. Ale liczniki w pamięci procesu są bezużyteczne, dopóki ktoś ich systematycznie nie zbiera, nie przechowuje i nie zadaje im pytań. Tym kimś jest Prometheus. W tym rozdziale pokażę, jak Prometheus widzi nasz system, jak go pytać językiem PromQL, jak zamienić pytanie w alert — i dlaczego połowa cudzych dashboardów „nie działa", choć metryki są na miejscu.

### Model pull: Prometheus odpytuje, aplikacja tylko odpowiada

Pierwsza rzecz, którą trzeba zrozumieć, to kierunek przepływu. Prometheus nie czeka, aż aplikacja coś mu przyśle. To Prometheus aktywnie odpytuje (ang. *pull*, *scrape*) endpoint `/actuator/prometheus` każdej repliki co N sekund — u nas co około 3 sekundy. Pobiera bieżący tekstowy zrzut wszystkich metryk i dokleja do każdej próbki własny znacznik czasu — moment scrape'u.

To ma subtelną konsekwencję. Aplikacja zna „teraz" — w chwili scrape'u zna dokładną wartość każdego licznika. Prometheus zna wyłącznie „przeszłość" — serię próbek rozsianych co ~3 s. Wszystko, co wydarzyło się *między* dwoma scrape'ami, jest dla Prometheusa niewidzialne. Jeśli circuit breaker otworzył się i zamknął w ciągu jednej sekundy między próbkami, Prometheus może tego w ogóle nie zobaczyć. Dlatego liczniki muszą być kumulatywne (monotoniczne) — wtedy nawet rzadkie próbki pozwalają odtworzyć, ile zdarzeń zaszło, jako różnicę między dwiema wartościami. Stan chwilowy (gauge) próbkujemy „na oko" i akceptujemy, że krótkie szpilki przepadną.

Konfigurację scrape'u trzyma `prometheus.yml`. To stamtąd bierze się etykieta `job="starport-registry"` — to nazwa zadania scrape'owego, nie nazwa aplikacji. Prometheus sam dokłada też `instance` (adres konkretnej repliki), a aplikacja przez Micrometer dorzuca własne tagi, np. `name="trade-route-planner"` dla konkretnego circuit breakera.

### Cztery typy metryk

Prometheus rozróżnia cztery typy, choć fizycznie wszystko to szeregi czasowe (`__name__` + zestaw etykiet → punkty w czasie):

- **Counter** — wartość tylko rośnie (albo resetuje się do zera przy restarcie procesu). Liczba wywołań, błędów, retry. Sama wartość jest nudna; interesuje nas tempo jej wzrostu.
- **Gauge** — wartość, która swobodnie rośnie i maleje: temperatura, liczba aktywnych żądań, stan circuit breakera (0/1/2). Czytamy wartość bieżącą.
- **Histogram** — zestaw kubełków (buckets) zliczających, ile obserwacji wpadło poniżej danego progu, plus `_sum` i `_count`. Z tego liczymy kwantyle (p99) po stronie serwera Prometheus.
- **Summary** — podobny, ale kwantyle liczone są po stronie aplikacji i przychodzą gotowe; nie da się ich potem agregować między replikami. Dlatego dla latencji wolimy histogram.

### PromQL: rate() kontra increase()

Najważniejsza para funkcji. Obie operują na *zakresach* (`[5m]` to range vector — wszystkie próbki z ostatnich 5 minut danej serii).

`rate()` zwraca **średnie tempo przyrostu na sekundę** dla countera, automatycznie korygując resety (gdy proces się zrestartował i licznik spadł do zera, `rate()` traktuje to jako wzrost, nie ujemny skok):

```promql
# Żądania na sekundę w oknie 5 min, per instancja
sum by (instance) (
  rate(http_server_requests_seconds_count{job="starport-registry"}[5m])
)
```

`increase()` zwraca **łączny przyrost w całym oknie** — to po prostu `rate() × długość okna`, też z korektą resetów:

```promql
# Ile retry policzono w ostatnich 5 minutach
increase(resilience4j_retry_calls{job="starport-registry", name="trade-route-planner"}[5m])
```

Reguła kciuka: `rate()` do wykresów i alertów na „ile na sekundę" (gładkie, niezależne od długości okna), `increase()` gdy pytasz „ile sztuk w tym oknie" (liczba całkowita, czytelna dla człowieka).

I teraz pułapka tego projektu. `resilience4j_retry_calls` **nie jest counterem** — Micrometer eksportuje go jako **gauge kumulatywny**. Wartość rośnie, ale typ to gauge, więc `rate()` *nie zastosuje korekty resetów* w sposób, na jaki liczysz — a co gorsza semantyka „na sekundę" dla gauge bywa myląca. Dlatego dla `retry_calls` używamy `increase()` na zakresie albo po prostu różnicy wartości / wartości bieżącej z podziałem po etykiecie `kind` (`successful_with_retry`, `failed_with_retry`, ...). Zawsze sprawdź typ metryki, zanim odruchowo nałożysz `rate()`.

### histogram_quantile, sum i max by

Kwantyle z histogramu liczymy serwerowo. Klasyczny p99 latencji:

```promql
histogram_quantile(0.99,
  sum by (le) (
    rate(http_server_requests_seconds_bucket{job="starport-registry"}[5m])
  )
)
```

Tu zagrały trzy rzeczy naraz: `rate()` na kubełkach (bo to countery), `sum by (le)` żeby zsumować kubełki ze wszystkich replik zachowując oś progów (`le` = *less or equal*), i dopiero `histogram_quantile` interpoluje wynik. Etykieta `le` jest święta — zgubisz ją w agregacji, kwantyl się nie policzy.

`sum by (...)` i `max by (...)` to agregatory grupujące. `by` zostawia wymienione etykiety i zwija resztę; `without` robi odwrotnie. `max` przyda się za chwilę przy alercie na stan circuit breakera.

### Recording rules kontra alert rules

Prometheus zna dwa rodzaje reguł w plikach reguł. **Recording rules** wyliczają wyrażenie cyklicznie i zapisują wynik pod nową nazwą metryki — używamy ich, gdy ciężkie wyrażenie (np. p99 po wielu replikach) jest odpytywane przez wiele paneli; liczymy raz, czytamy wielokrotnie. **Alert rules** wyliczają wyrażenie i, jeśli zwróci niepuste serie, generują alert.

### Reguła alertu: expr, for, labels, annotations

Nasz sztandarowy alert — otwarty circuit breaker plannera trasy:

```yaml
groups:
  - name: trade-route-resilience
    rules:
      - alert: TradeRoutePlannerCircuitOpen
        expr: max(resilience4j_circuitbreaker_state{job="starport-registry", name="trade-route-planner", state="open"}) == 1
        for: 30s
        labels:
          severity: critical
        annotations:
          summary: "Circuit breaker trade-route-planner jest OTWARTY"
          description: "Co najmniej jedna replika ma otwarty CB od >=30s. Ruch do plannera jest odcinany."
```

Rozbiórka:

- **`expr`** — `max(...) == 1`. Metryka `resilience4j_circuitbreaker_state` to gauge: po jednej serii na każdy stan (`closed`/`open`/`half_open`), wartość 1 oznacza „CB jest w tym stanie". Bierzemy serię `state="open"` i `max(...)` po wszystkich instancjach — wystarczy, że *jedna* replika ma CB otwarty, by `max` dał 1. Porównanie `== 1` zamienia liczbę na warunek: dla serii spełniających warunek wyrażenie zwraca próbkę, dla reszty — pustkę.
- **`for: 30s`** — alert nie odpala się natychmiast. Gdy `expr` zaczyna zwracać wynik, alert wchodzi w stan **pending**. Dopiero gdy warunek utrzyma się nieprzerwanie przez 30 s, przechodzi w **firing**. To filtr na chwilowe migotanie — pojedynczy krótki skok CB nie obudzi nikogo w nocy.
- **`labels`** — `severity: critical` to etykieta doklejana do alertu; służy późniejszemu routingowi (kto i jak ma być powiadomiony).
- **`annotations`** — `summary`/`description` to tekst dla człowieka. To nie etykiety identyfikujące alert, więc można w nich bezpiecznie używać szablonów i pełnych zdań.

Oprócz tego mamy alert pochodny: **TradeRoutePlannerHighFailureRate** (odsetek nieudanych wywołań ≥ 25%; próg otwarcia CB to 50%, więc to wczesne ostrzeżenie — „zbliżamy się do ściany").

### Brak Alertmanagera — świadoma decyzja

W tym stacku **nie ma Alertmanagera**. Alerty są realne — Prometheus je wylicza i pokazuje ich stan na `/alerts` (pending/firing) oraz w Grafanie. Czego nie ma, to *routingu*: wysyłki na Slacka, PagerDuty, dyżur. To celowo osobny krok produkcyjny. Na demo i w dev wystarczy zobaczyć, że reguła poprawnie wykrywa awarię. Retencja Prometheusa jest krótka — około 6 godzin — a długoterminowe składowanie przejmuje Thanos. Alertmanager dokładamy, gdy alerty mają faktycznie kogoś budzić.

### Pułapki translacji Micrometer → Prometheus

Tu ginie najwięcej godzin. Micrometer ma własne nazwy metryk (z kropkami), a przy eksporcie do Prometheusa przepuszcza je przez translator, który **zmienia nazwy**. Trzy nawarstwiające się pułapki:

1. **Kropki → podkreślenia.** `http.server.requests` staje się `http_server_requests`. To akurat oczywiste.
2. **Końcówka `.total` jest ZJADANA.** Micrometer dla counterów (i części gauge) ucina sufiks `.total`. Metryka, której w kodzie szukasz jako `...total`, w Prometheusie nazywa się bez niego. Szukasz `foo_total`, a jest `foo` — i odwrotnie.
3. **Base unit dokleja sufiks.** Jeśli metryka ma jednostkę bazową (sekundy), translator dorzuca `_seconds`: stąd `http_server_requests_seconds_count`, a nie `http_server_requests_count`.

Wniosek operacyjny, który ratuje życie: **zanim wepniesz cokolwiek do dashboardu, sprawdź realną nazwę** w rejestrze `__name__`:

```bash
curl -s prometheus:9090/api/v1/label/__name__/values | grep resilience4j
```

To jedyne źródło prawdy. Nie zgaduj nazwy z kodu Javy ani z dokumentacji Micrometera — przeczytaj, co Prometheus naprawdę ma u siebie.

### Dlaczego cudzy dashboard (Grafana 21307) nie działa

Klasyk. Pobierasz gotowy dashboard Resilience4j z Grafana.com (np. ID 21307), importujesz — i wszystkie panele puste. Metryki są, alert działa, a panele martwe. Powód: ten dashboard filtruje swoje zapytania po etykietach `application` i `cluster`, których **nasz Prometheus w ogóle nie nadaje**. Nasze serie mają `job`, `instance` i `name`, a nie `application`/`cluster`. PromQL z dashboardu szuka `{application="$app"}` i nie trafia w żadną serię — stąd pustka.

To nie jest awaria metryk. To niezgodność konwencji etykietowania. Albo przepiszesz selektory dashboardu na nasze etykiety (`job`/`name`), albo dodasz brakujące etykiety w konfiguracji scrape'u. My poszliśmy pierwszą drogą i mamy własny dashboard `resilience4j-trade-route` dopasowany do realnych nazw i etykiet. Morał całego rozdziału: między „metryka istnieje" a „panel/alert ją widzi" stoi warstwa nazw i etykiet — i to tam, a nie w kodzie aplikacji, mieszka większość problemów obserwowalności.

### Pytania kontrolne

1. Dlaczego w modelu pull aplikacja „zna teraz", a Prometheus tylko „przeszłość" — i jaką to ma konsekwencję dla krótkich szpilek stanu circuit breakera?
2. Kiedy użyjesz `rate()`, a kiedy `increase()`? Czemu dla `resilience4j_retry_calls` (gauge kumulatywny) nie sięgasz odruchowo po `rate()`?
3. Po co `sum by (le)` przed `histogram_quantile(0.99, ...)` i co się stanie, gdy zgubisz etykietę `le`?
4. Wytłumacz przejście alertu pending → firing. Po co `for: 30s` w `TradeRoutePlannerCircuitOpen` i czemu w `expr` jest `max(...) == 1`, a nie sama metryka?
5. Wymień trzy pułapki translacji Micrometer → Prometheus i podaj komendę, którą potwierdzisz realną nazwę metryki przed wpięciem dashboardu.
6. Dlaczego dashboard Grafana 21307 pokazuje puste panele mimo obecnych metryk — i jakie są dwa sposoby naprawy?


---

## Rozdział 9 — Architektura systemu: discovery, load balancing, wątki wirtualne, telemetria

Zanim zaczniemy mówić o tym, *jak* mierzymy, że „działa", musimy zrozumieć, *co* właściwie mierzymy. Bez mapy topologii każdy wykres jest tylko ładnym obrazkiem. Ten rozdział rysuje tę mapę: jak ruch wchodzi do systemu, jak znajduje swoje cele, na jakich wątkach jest obsługiwany i jakim kanałem wycieka z niego telemetria. To fundament pod całą resztę wykładu — bo „wiem, że działa" zaczyna się od „wiem, jak to jest zbudowane".

### Topologia: jedno wejście, wiele instancji

Cały system ma **dokładnie jedno publiczne wejście** — `api-gateway` na porcie 8080. To świadoma decyzja: jeden punkt uwierzytelnienia, jeden punkt observability na brzegu, jedna granica zaufania. Za bramą żyją usługi domenowe, każda w dwóch replikach:

- `starport-registry` ×2 (port wewnętrzny 8081) — rejestr portów kosmicznych i rezerwacji,
- `trade-route-planner` ×2 (port 8082) — planer tras handlowych,
- `Eureka` — rejestr usług, serce discovery.

```
                          ┌──────────────────────────────┐
   klient  ───8080───▶    │        api-gateway           │  (jedyne publiczne wejście)
                          └───────────────┬──────────────┘
                                          │  lb://starport-registry
                          ┌───────────────┴──────────────┐
                          ▼                               ▼
                 ┌─────────────────┐            ┌─────────────────┐
                 │ starport-registry│            │ starport-registry│   ×2  (8081)
                 │   instancja #1   │            │   instancja #2   │
                 └────────┬─────────┘            └────────┬─────────┘
                          │  lb://trade-route-planner      │
                          ▼                                ▼
                 ┌─────────────────┐            ┌─────────────────┐
                 │ trade-route-    │            │ trade-route-    │   ×2  (8082)
                 │ planner  #1     │            │ planner  #2     │
                 └─────────────────┘            └─────────────────┘
                          ▲                                ▲
                          └────────────┬───────────────────┘
                                       │  rejestracja / heartbeat
                                ┌──────┴───────┐
                                │   Eureka     │   (rejestr usług)
                                └──────────────┘

   Telemetria (OTLP) z każdej usługi ──▶ OpenTelemetry Collector ──▶ Tempo / Loki / Prometheus(+Thanos)
```

Kluczowa obserwacja: nigdzie w kodzie nie ma adresu IP ani portu instancji `trade-route-planner`. `starport-registry` woła usługę po **logicznej nazwie**, a o tym, którą fizyczną instancję trafi, decyduje load balancer po stronie klienta.

### Client-side load balancing kontra server-side

W modelu **server-side** (klasyczny reverse proxy, sprzętowy LB) klient zna jeden adres — adres balancera — i to balancer rozdziela ruch. W modelu **client-side**, który stosujemy tu przez **Spring Cloud LoadBalancer**, to *sam klient* pyta Eurekę „gdzie żyje `trade-route-planner`?", dostaje listę żywych instancji i sam wybiera jedną (domyślnie round-robin). Nie ma pośrednika, nie ma dodatkowego skoku sieciowego, nie ma centralnego wąskiego gardła.

Konkretnie: `starport-registry` ma skonfigurowany `RestClient` z bazowym URL-em `http://trade-route-planner`. To nie jest prawdziwy host DNS — to nazwa usługi. Interceptor `@LoadBalanced` przechwytuje wywołanie, tłumaczy `http://trade-route-planner` na `lb://trade-route-planner`, rozwiązuje listę instancji z Eureki i podmienia URL na konkretne `http://10.x.x.x:8082`. Timeouty są dobrane defensywnie: **connect-timeout 200 ms, read-timeout 800 ms** — bo wolimy szybko polec i zadziałać przez circuit breaker niż wisieć i zatykać pulę.

```java
@Configuration
class TradeRouteClientConfig {

    // KLUCZOWE: RestClientBuilderConfigurer, nie "goły" RestClient.builder().
    // Configurer nakłada autokonfigurację Spring Boota — w tym
    // customizery Micrometera/Observation. Bez niego trace się rozjeżdża:
    // wychodzące żądanie nie dostaje propagacji kontekstu (traceparent),
    // a span klienta nie podpina się pod span serwera.
    @Bean
    @LoadBalanced
    RestClient.Builder tradeRouteRestClientBuilder(
            RestClientBuilderConfigurer configurer) {
        return configurer.configure(RestClient.builder());
    }

    @Bean
    RestClient tradeRouteRestClient(
            @LoadBalanced RestClient.Builder builder,
            ClientHttpRequestFactorySettings ignored) {

        var factory = ClientHttpRequestFactoryBuilder.detect()
            .build(ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofMillis(200))
                .withReadTimeout(Duration.ofMillis(800)));

        return builder
            .baseUrl("http://trade-route-planner")  // logiczna nazwa → lb://
            .requestFactory(factory)
            .build();
    }
}
```

Dlaczego `RestClientBuilderConfigurer` jest *obowiązkowy*, a nie kosmetyczny? Bo jeśli zbudujesz builder od zera (`RestClient.builder()`), tracisz wszystkie customizery, które Spring Boot dokłada automatycznie — w tym instrumentację Observation. Efekt jest podstępny: aplikacja działa, odpowiedzi wracają, testy przechodzą. Ale w Tempo trace urywa się na granicy `starport → planner`, bo nagłówek `traceparent` nigdy nie został doszyty. To jest dokładnie ten gatunek „działa, ale nie wiem, czy działa", o którym jest cały ten wykład.

### Discovery to nie to samo co registration

Dwa pojęcia, które łatwo pomylić, a które robią różnicę w demie:

- **Registration** — usługa *ogłasza się* w Eurece: „jestem `starport-registry`, żyję pod tym adresem, oto mój heartbeat". Dzięki temu *inni* mogą mnie znaleźć.
- **Discovery** — usługa *pyta* Eurekę o cudze adresy, żeby kogoś zawołać.

To są niezależne osie. W naszym demie odporności robimy zabieg chirurgiczny: **odpinamy load balancing tylko po stronie klienta plannera** (psujemy *discovery* trasy `starport → planner`), ale **rejestracja `starport-registry` w Eurece zostaje nietknięta**. Dzięki temu `api-gateway` nadal widzi `starport` i kieruje do niego ruch — możemy więc obserwować, jak `starport` zachowuje się, gdy *jego* zależność znika, zamiast wywalać cały system. Gdybyśmy wyrejestrowali `starport`, brama przestałaby go widzieć i nie byłoby czego demonstrować.

### Wątki wirtualne (Project Loom, ADR-0012)

System chodzi na **Javie 21 z wątkami wirtualnymi**. Zysk jest podwójny: piszemy zwykły, czytelny **kod blokujący** (`restClient.get()...retrieve()`), bez kaskady `CompletableFuture` ani reaktywnych łańcuchów, a mimo to nie płacimy ceną wątków platformowych — wirtualny wątek przy blokadzie I/O parkuje się tanio i zwalnia nośnik. Skalowanie do tysięcy współbieżnych żądań przestaje wymagać puli na sto wątków.

Ale Loom zmienia to, *czego nie wolno mierzyć*. Klasyczne metryki `tomcat_threads_*` (busy/current/max) pod wątkami wirtualnymi raportują **−1** — są bezużyteczne, bo nie ma już ograniczonej puli, którą warto obserwować. Saturacji warstwy webowej nie czytamy więc z puli wątków, tylko z **`http_server_requests_active_seconds_count`** — liczby żądań aktualnie w locie. To jest prawdziwa miara „ile równolegle obsługujemy".

Druga pułapka jest groźniejsza: **utrata kontekstu Observation w surowym executorze**. Jeśli ręcznie odpalisz `Executors.newVirtualThreadPerTaskExecutor()` i wrzucisz na niego zadanie, kontekst trace'a *nie przeniesie się* na nowy wątek — span się urwie, a logi zgubią `traceId`. Lekarstwem jest opakowanie executora w `ContextExecutorService` (Micrometer Context Propagation), który snapshotuje i przywraca kontekst przy przekazaniu zadania. To znów ten sam wzorzec: kod „działa", ale telemetria po cichu kłamie.

### Stan circuit breakera jest per-instancja

Circuit breaker (Resilience4j) trzyma swój stan — OPEN/HALF_OPEN/CLOSED, okno liczników — **w pamięci JVM danej instancji**. To znaczy, że dwie repliki `starport-registry` mają **dwa niezależne** circuit breakery. Replika #1 może mieć otwarty CB do plannera, a #2 w tej samej chwili zamknięty, bo trafiała na zdrowszą instancję. To nie jest błąd — to fizyka procesu.

Konsekwencja dla dashboardów: panele biznesowe **nie sumują** stanu CB, tylko biorą **`max()` po instancjach** — „czy *którakolwiek* replika ma problem". Gdybyśmy chcieli stanu globalnego, współdzielonego między replikami, musielibyśmy wyciągnąć go z JVM do np. Redisa — a to znacznie cięższa decyzja architektoniczna (nowa zależność, nowy punkt awarii, opóźnienie sieciowe na ścieżce decyzji breakera). Świadomie tego nie robimy: lokalny stan jest tańszy i bardziej odporny, a agregację załatwiamy w warstwie zapytań Prometheusa.

### Pipeline telemetrii

Każda usługa eksportuje sygnały protokołem **OTLP** do **OpenTelemetry Collectora** — to jeden, wspólny lejek. Collector robi **tail sampling** (ADR-0035): decyzję o zatrzymaniu trace'a podejmuje *po* zobaczeniu całego śladu, więc 100% błędów i wolnych żądań przeżywa, a ze zwykłego ruchu zostaje ~1%. Stąd sygnały rozchodzą się do trzech magazynów (ADR-0037, z `decision_wait` na zebranie pełnego trace'a):

- **Tempo** — trace'y,
- **Loki** — logi (niesamplowane, 100%),
- **Prometheus** — metryki, z retencją tylko **6 godzin**; długoterminowo przejmuje je **Thanos**.

Warto pamiętać o jeszcze jednym fakcie operacyjnym: **Postgres jest efemeryczny (tmpfs)** — każdy restart stacka czyści bazę i Flyway odgrywa seed od nowa. To celowe dla demo i CI; nie próbuj tego „naprawiać" do nazwanego wolumenu.

To jest cała pętla zwrotna: ruch wchodzi bramą, rozprasza się przez client-side LB, biegnie na wątkach wirtualnych przez breakery o lokalnym stanie, a każdy krok zostawia ślad, który przez Collector trafia do Tempo/Loki/Prometheusa. Dopiero mając tę pętlę domkniętą, możemy uczciwie powiedzieć: „działa — i ja o tym wiem".

### Pytania kontrolne

1. Czym różni się *registration* od *discovery* i dlaczego w demie odporności psujemy tylko discovery klienta plannera, a rejestrację `starport` zostawiamy?
2. Dlaczego budowa `RestClient.builder()` „od zera" zamiast przez `RestClientBuilderConfigurer` rozspaja trace, mimo że aplikacja nadal działa?
3. Pod wątkami wirtualnymi `tomcat_threads_*` pokazuje −1. Z jakiej metryki odczytasz saturację warstwy webowej i dlaczego?
4. Stan circuit breakera jest per-instancja JVM. Jak panel biznesowy powinien agregować ten stan po replikach i jaki byłby koszt zrobienia stanu globalnym?
5. Na czym polega tail sampling w Collectorze (ADR-0035) i czemu wymaga parametru `decision_wait`?
6. Po co stosujemy klientowy load balancing zamiast server-side i jakie ma to zalety przy timeoutach 200/800 ms?


---

## Rozdział 10 — Chaos engineering i testowanie odporności

Załóżmy, że w poprzednich rozdziałach zrobiliśmy wszystko „po bożemu": opletliśmy wywołanie do `trade-route-planner` Circuit Breakerem, dodaliśmy retry z exponential backoffem, napisaliśmy ładny fallback rzucający `RouteUnavailableException`, które kontroler tłumaczy na HTTP 409. Pytanie, które chcę dzisiaj postawić brzmi: skąd wiesz, że to działa?

Bo Circuit Breaker to nie magia rozpięta nad architekturą — to jest **kod jak każdy inny**. To są warunki, progi, liczniki okien przesuwnych, stany OPEN/HALF_OPEN/CLOSED i przejścia między nimi. Jeśli tego kodu nigdy nie wykonałeś w warunkach, do których został napisany — czyli pod awarią — to nie masz ani jednego dowodu, że robi to, co myślisz. Co gorsza, kod odpornościowy ma paskudną właściwość: na zielonej ścieżce jest **niewidoczny**. Aplikacja, w której CB jest źle skonfigurowany (albo nie łapie wyjątku, który powinien), wygląda na produkcji identycznie jak ta poprawna — aż do dnia, w którym downstream pada. To jest dokładnie sedno tytułu tego wykładu: „działa" w sensie „testy przechodzą i ruch leci" to nie to samo, co „działa i ja o tym wiem".

### Trzy poziomy testowania odporności

Odporność testujemy na trzech poziomach, idąc od najtańszego do najdroższego:

1. **Testy jednostkowe i akceptacyjne fallbacku** — szybkie, deterministyczne, w CI. Symulujemy awarię downstreamu i sprawdzamy, że nasza aplikacja degraduje się tak, jak zaprojektowaliśmy.
2. **Fault injection na żywym stacku** — wstrzykiwanie usterek w działającą architekturę z Eureką, load balancerem, Prometheusem i alertami. Tu testujemy nie tylko kod, ale całą konfigurację.
3. **Chaos engineering jako dowód** — wygenerowanie kontrolowanej awarii i udowodnienie metryką, że mechanizm zadziałał (CB się otworzył, alert wystrzelił).

Zacznijmy od poziomu pierwszego. W projekcie mamy już WireMock wpięty w `BaseAcceptanceTest`, więc test fallbacku jest tani. WireMock udaje `trade-route-planner` i zwraca to, czego my chcemy — 5xx, opóźnienie, albo połączenie zerwane. Stub na opóźnioną piątkę wygląda tak:

```java
stubFor(post(urlPathEqualTo("/api/v1/routes/plan"))
    .willReturn(aResponse()
        .withStatus(503)
        .withFixedDelay(900)          // > read-timeout 800 ms → timeout
        .withHeader("Content-Type", "application/json")
        .withBody("{\"error\":\"planner overloaded\"}")));
```

A test akceptacyjny sprawdza kontrakt degradacji — nie „czy planner padł", tylko „czy my zachowaliśmy się poprawnie gdy padł":

```java
@Test
void whenPlannerReturns5xx_thenRouteUnavailable409() {
    stubFor(post(urlPathEqualTo("/api/v1/routes/plan"))
        .willReturn(aResponse().withStatus(503)));

    given()
        .contentType(JSON).body(reservationRequest())
    .when()
        .post("/api/v1/reservations")
    .then()
        .statusCode(409)
        .body("code", equalTo("ROUTE_UNAVAILABLE"));
}
```

To jest dobry test, ale ma sufit: WireMock zwraca dokładnie to, co mu każesz. Prawdziwa sieć nie jest tak grzeczna. Stąd poziomy 2 i 3.

### Fault injection: twardy zgon vs. degradacja

Najprostszy sposób na awarię to `docker stop trade-route-planner-1`. Kontener znika, połączenia dostają `connection refused`, CB ładnie się otwiera. To jest **najczystsze dydaktycznie** — efekt jest natychmiastowy i binarny, świetnie się pokazuje na slajdzie. Problem w tym, że to **najmniej realny** scenariusz produkcyjny. Procesy rzadko padają tak uprzejmie.

To, co naprawdę boli na produkcji, to **szara strefa**: serwis odpowiada, ale wolno. Albo odpowiada poprawnie w 60% przypadków, a w 40% rzuca 503. Albo łącze jest zapchane i pakiety wloką się przez bufor. To są usterki, które najtrudniej wykryć i najłatwiej przegapić w testach, bo aplikacja „technicznie działa". Dlatego do poważnego fault injection nie wystarczy `docker stop` — potrzebny jest sterowalny proxy w warstwie TCP.

### Toxiproxy

Toxiproxy to programowalny proxy TCP od Shopify. Stawiamy go **między** klientem a serwisem, a ruch psujemy doczepiając do proxy tzw. **toxics**. Najważniejsze z nich:

- `latency` — dokłada opóźnienie (z opcjonalnym jitterem),
- `timeout` — po `timeout` ms zamyka połączenie; przy `timeout: 0` **trzyma połączenie w nieskończoność**, aż klient sam odpadnie na własnym read-timeoucie,
- `bandwidth` — ogranicza przepustowość (zapchane łącze),
- `reset_peer` — wysyła RST (twarde zerwanie),
- `slicer` — tnie pakiety na kawałki z opóźnieniem.

Każdy toxic ma `toxicity` w zakresie 0.0–1.0 — to **prawdopodobieństwo**, że dane połączenie zostanie dotknięte. `toxicity: 0.5` to właśnie nasze „częściowe 5xx": połowa połączeń psuta, połowa zdrowa. Sterujemy tym przez REST API Toxiproxy (domyślnie port 8474). Najpierw definiujemy proxy:

```bash
curl -X POST http://toxiproxy:8474/proxies -d '{
  "name": "planner",
  "listen": "0.0.0.0:8666",
  "upstream": "trade-route-planner:8080",
  "enabled": true
}'
```

a potem doczepiamy toxic — np. 600 ms latencji na całym ruchu downstream:

```bash
curl -X POST http://toxiproxy:8474/proxies/planner/toxics -d '{
  "name": "slow",
  "type": "latency",
  "stream": "downstream",
  "toxicity": 1.0,
  "attributes": { "latency": 600, "jitter": 0 }
}'
```

#### Dlaczego to trudne przy client-side LB + Eureka

Tu jest haczyk, który zjada ludziom popołudnia. W naszej architekturze klient robi **client-side load balancing**: pobiera z Eureki listę instancji `trade-route-planner` i sam wybiera, do której pojechać. Jeśli wepniesz Toxiproxy, a load balancer dalej działa, to klient ominie twoje proxy i pojedzie prosto do instancji z rejestru. Proxy stoi puste, a ty zachodzisz w głowę, czemu toxic nic nie robi.

Żeby celować w proxy, trzeba **odpiąć LB dla tego klienta** i kazać mu strzelać pod stały adres proxy. U nas robi to overlay `docker-compose.demo.yml`, który wpina Toxiproxy między `starport` a `planner`, plus profil `demo`:

```properties
app.trade-route-planner.base-url=http://toxiproxy:8666
app.trade-route-planner.load-balanced=false
```

Discovery przez Eurekę **zostaje** (serwis nadal jest w rejestrze, healthcheck żyje) — wyłączamy tylko load-balancing po stronie tego konkretnego klienta, żeby cały jego ruch przeszedł przez proxy, które kontrolujemy.

### Jak UDOWODNIĆ, że Circuit Breaker się otwiera

Najważniejsze zdanie tego rozdziału: usterka to nie dowód. Dowodem jest **zaobserwowana reakcja systemu**. Sama procedura ma cztery kroki:

1. **Generuj ruch** — CB liczy okno; przy pustym ruchu nic się nie wydarzy. Pamiętaj o `min-calls` (u nas 5) — poniżej progu CB w ogóle nie ocenia.
2. **Czekaj, aż stan się zmieni** — pollujemy metrykę `resilience4j_circuitbreaker_state{state="open"}` aż osiągnie `1`.
3. **Sprawdź, że alert jest FIRING** — przejście CB powinno zapalić alert w Prometheusie; jeśli reguła jest źle napisana, CB się otworzy, a nikt się nie dowie.
4. **Przywróć i obserwuj zamknięcie** — usuń usterkę, poczekaj na HALF_OPEN → CLOSED.

Skrypt `preflight-demo.ps1 -Full` robi dokładnie to przed demem — to **smoke test**, który łapie zepsutą konfigurację zanim zrobi to publiczność:

```powershell
docker stop trade-route-planner-1 trade-route-planner-2   # twardy wariant
Start-GeneratedTraffic -Rps 10
# poll aż CB OPEN
do {
    $v = (Invoke-PrometheusQuery `
        'resilience4j_circuitbreaker_state{name="planner",state="open"}').value
    Start-Sleep -Seconds 2
} while ($v -ne 1)

# alert musi być FIRING
$alert = (Invoke-PrometheusQuery 'ALERTS{alertname="CircuitBreakerOpen",alertstate="firing"}')
if (-not $alert) { throw "CB otwarty, ale alert NIE wystrzelił — reguła zepsuta" }

docker start trade-route-planner-1 trade-route-planner-2  # heal
```

### Drabina eskalacji: slow → partial → down → heal

Demo prowadzimy po drabinie eskalacji, od najsubtelniejszej usterki do najtwardszej, każdy szczebel z innym toxikiem. Tak właśnie działa `demo-resilience.ps1 -Chaos`:

- **slow** — `latency` 600 ms, downstream, `toxicity: 1.0`. 600 ms < 800 ms read-timeout, więc wywołania są **wolne, ale udane**. To podnosi `slow_call_rate` — CB może się otworzyć od wolnych wywołań, nie tylko od błędów.
- **partial** — `timeout` z `toxicity: 0.5` i `timeout: 0`. Połączenie wisi aż klient odpadnie na read-timeoucie 800 ms → błąd → uruchamia się **retry**. Połowa ruchu zdrowa, połowa boli — najbardziej produkcyjny ze scenariuszy.
- **down** — wyłączamy proxy (`enabled: false`) → `connection refused` → CB idzie w **OPEN**.
- **heal** — czyścimy wszystkie toxyny i włączamy proxy z powrotem; obserwujemy HALF_OPEN → CLOSED.

Wariant twardy podmienia mechanizm usterki na `docker stop trade-route-planner-1/-2` (kill) i `docker start` (restore) — ten sam dowód metryczny, inne źródło bólu.

Puenta: jeśli przeszedłeś tę drabinę i widziałeś, jak `slow_call_rate` rośnie, jak retry łapie częściowe błędy, jak CB przeskakuje w OPEN i jak alert robi się FIRING — wtedy, i dopiero wtedy, masz prawo powiedzieć „to działa i ja o tym wiem".

### Pytania kontrolne

1. Dlaczego dobrze przetestowany na zielonej ścieżce serwis z Circuit Breakerem może i tak zawieść pod awarią downstreamu?
2. Czym różni się dydaktycznie i produkcyjnie `docker stop` od degradacji przez Toxiproxy? Kiedy którego użyć?
3. Co oznacza `toxicity: 0.5` i jaki realny scenariusz produkcyjny modeluje?
4. Dlaczego przy client-side load balancingu z Eureką samo wpięcie Toxiproxy nie wystarczy, by ruch przez nie przeszedł? Co trzeba zmienić, a co zostawić?
5. Czemu toxic `timeout` z `timeout: 0` powoduje błąd po stronie klienta, skoro „nie ma timeoutu"?
6. Wymień cztery kroki dowodu, że CB faktycznie się otwiera. Dlaczego sprawdzenie stanu metryki nie wystarcza bez sprawdzenia alertu?
7. Jaką rolę pełni `min-calls=5` i dlaczego bez generowania ruchu demo nie zadziała?


---

## Rozdział 11 — Obrona przed trudnymi pytaniami

Ten rozdział to nie ściąga. To arsenał. Sala seniorów nie pyta, żeby się dowiedzieć — pyta, żeby sprawdzić, czy ty wiesz. Każde z poniższych pytań brzmi jak zarzut. Każde ma w sobie ziarno prawdy — i dlatego jest groźne. Twoja przewaga polega na tym, że rozróżniasz warstwy, których pytający świadomie nie rozdziela. Odpowiadaj krótko, potem rozwijaj. Nigdy nie kłóć się z faktem — kłóć się z wnioskiem.

**„Po co circuit breaker, skoro mam timeout?"**
Timeout chroni jeden request; circuit breaker chroni cały system.
Read-timeout 800 ms i connect-timeout 200 ms robią dokładnie jedno: ucinają pojedyncze wiszące wywołanie, żeby wątek nie wisiał w nieskończoność. To konieczne, ale niewystarczające. Kiedy zależność leży, każdy z setek przychodzących requestów grzecznie czeka swoje 800 ms, zanim dostanie timeout — a w tym czasie trzyma wątek, połączenie i pamięć. Timeout nie ma pamięci: nie wie, że poprzednie dziewięćdziesiąt wywołań też padło. Circuit breaker ma pamięć — po przekroczeniu failure-rate 50% w oknie 10 wywołań przechodzi w OPEN i zwraca błąd natychmiast, fail-fast, bez czekania 800 ms. Timeout tnie objaw u jednego pacjenta; CB stawia diagnozę dla całej kolejki.

**„Po co CB, skoro Eureka i load balancer robią healthcheck co 5 sekund?"**
Healthcheck usuwa trupa; circuit breaker radzi sobie z żywym, który kłamie.
To są trzy różne warstwy obrony i mylenie ich to klasyczny błąd. Healthcheck odpowiada na pytanie „czy instancja żyje?" — jeśli nie, LB ją wyrzuca z puli. Ale awarie produkcyjne rzadko są tak uprzejme. Instancja często żyje, odpowiada na `/health` zielono, a jednocześnie zwraca 500-tki albo odpowiada w 3 sekundy zamiast 50 ms — bo wyczerpała pulę połączeń do bazy albo wpadła w GC. Dla healthchecka to zdrowy węzeł. Drabina jest taka: trup → załatwia LB/Eureka; pojedynczy glitch → załatwia timeout i retry; powtarzalny wzorzec degradacji → załatwia CB. Każda warstwa łapie to, czego nie widzą pozostałe.

**„Retry storm — nie pogarszacie awarii, dokładając ruch do leżącej usługi?"**
To realne ryzyko i dlatego retry jest u nas okiełznany, nie naiwny.
Po pierwsze, max-attempts=2 — to jedna ponowna próba, absolutne minimum, a nie agresywne pięciokrotne dobijanie. Po drugie, i to jest sedno, kolejność dekoratorów to Retry(CircuitBreaker(...)): retry opakowuje breakera od zewnątrz. Gdy CB jest OPEN, wywołanie **nie schodzi do zależności** — breaker fast-failuje na wejściu (`CallNotPermittedException`) w mikrosekundach. Czyli nawet jeśli retry wystartuje, bije w otwarty obwód, a nie w martwą usługę — load na padający serwis nie rośnie ani o jeden request. (Precyzyjny niuans tego configu, na wypadek dociekliwego seniora: fallback na `@CircuitBreaker` mapuje `CallNotPermittedException` na `RouteUnavailableException`, która jest na liście `retry-exceptions` — więc jedna ponowna próba i tak się odpala, ale bezpiecznie, bo trafia w breakera, nie w zależność. Gdybyśmy chcieli zero ponowień przy OPEN, dodalibyśmy `CallNotPermittedException` do `ignore-exceptions` albo nie mapowali go w fallbacku.) Wait 100 ms między próbami, a na produkcji dokładamy jitter, żeby repliki nie zsynchronizowały ponowień w jedną falę. Retry bez CB faktycznie bywa benzyną na pożar; Retry(CircuitBreaker) jest gaśnicą.

**„Czemu nie service mesh, czemu nie Istio? Tam macie retry i CB bez linijki kodu."**
Bo mesh nie zna semantyki mojej domeny, a biblioteka tak — i one się nie wykluczają.
Mesh działa na L7: widzi kody HTTP, latencje, może robić retry i CB transparentnie, bez dotykania aplikacji. To realna zaleta przy heterogenicznym zoo języków. Ale mesh nie wie, że dla mnie 4xx z plannera to **odrzucenie domenowe** (trasy nie da się policzyć dla tej pary portów — liczę je jako `errorType=domain` i zwracam 409), a 5xx to **awaria infrastruktury** (`errorType=infrastructure`) — i że te dwie ścieżki traktuję inaczej. Dla sidecara to tylko kody HTTP bez znaczenia domenowego; nie odróżni „odmowy biznesowej" od „zależność się sypie". Mesh nie poda też fallbacku z wartością — nie zwróci ostatniej znanej trasy z cache'a, bo nie ma pojęcia, czym jest trasa. I dokłada sidecar do każdego poda: kolejny hop, kolejna pamięć, kolejny element do debugowania. Biblioteka w aplikacji podejmuje decyzje z pełnym kontekstem domenowym. W dojrzałej architekturze mesh i biblioteka współistnieją: mesh robi grube, generyczne cięcia infrastrukturalne, biblioteka — precyzyjne, semantyczne.

**„CB per-instancja czy globalny? Macie dwie repliki — czy one się o tym dogadują?"**
Per-instancja, świadomie, bo stan trzymany w pamięci JVM to zero zależności sieciowej na gorącej ścieżce.
Każda replika ma własnego breakera, własne okno, własny licznik i decyduje niezależnie — i to jest zaleta, nie wada. Jeśli jedna replika ma zerwane połączenie do zależności, a druga nie, chcę, żeby tylko ta pierwsza otworzyła obwód; globalny stan zmusiłby zdrową replikę do cierpienia za chorą. Cena per-instancji jest taka, że na panelach trzeba agregować: dla biznesowego widoku „czy ktokolwiek cierpi" biorę `max()` po instancjach, a nie średnią, która rozmyłaby problem jednej repliki. Globalny CB oparty o Redis dokłada wywołanie sieciowe do każdej decyzji o przepuszczeniu requestu — czyli stawiasz zależność sieciową na najszybszej ścieżce, którą właśnie próbujesz chronić przed zależnościami sieciowymi. To samobójstwo architektoniczne.

**„»Metryki za darmo« to marketingowy slogan — płacisz kardynalnością, scrape'em i retencją."**
Masz rację co do kosztu — „za darmo" znaczy zero kodu, nie zero zasobów.
Kiedy mówię „metryki za darmo", mam na myśli dokładnie jedno: nie napisałem ani linijki, żeby je wyprodukować — Resilience4j i Micrometer eksponują je same. To nie jest twierdzenie o kosztach infrastruktury. I owszem, te koszty istnieją: każdy unikalny zestaw labeli to osobny szereg czasowy, więc labele trzymam low-cardinality — `job`, `instance`, `name`, nigdy nie ID requestu ani trasy. Histogramy mają kilka bucketów dobranych pod progi SLO, a nie pięćdziesiąt. A kluczowy szczegół: gauge Resilience4j, jak `failure_rate`, to odczyt liczby z pamięci w O(1) na scrape, a nie supplier, który przy każdym scrape'ie wali do bazy. Senior, który widział drożejący Prometheus z kardynalnością wybuchłą po ID użytkownika, ma absolutną rację — i właśnie dlatego o tym myślałem zawczasu.

**„failure_rate=-1, retry_calls jest gauge zamiast counter, znikające `.total` — narzędzie jest dziurawe?"**
To są konwencje, nie błędy, a ich znajomość jest dowodem, że naprawdę czytałem te metryki, a nie tylko je włączyłem.
`failure_rate = -1` to sentinel: „za mało danych, żeby liczyć procent" — dopóki nie zbierze się min-calls=5 w oknie, breaker nie ma podstawy statystycznej, więc zwraca -1 zamiast udawać 0%, co byłoby kłamstwem sugerującym zdrowie. `retry_calls` jako kumulatywny gauge zamiast countera to decyzja projektowa Resilience4j — wartość rośnie monotonicznie, ale typ Prometheusa to **gauge, nie counter**, więc na dashboardzie czytasz **wartość albo `increase()`**, a nie `rate()` (które zakłada semantykę countera z obsługą resetów). To dokładnie ta pułapka z rozdziału o Prometheusie — odpytanie złą funkcją albo złą nazwą daje pusty panel. Znikające `.total` to z kolei translacja nazw Micrometer → Prometheus, która zjada ten przyrostek — klasyczna pułapka, przez którą panel pokazuje pustkę, jeśli odpytasz o złą nazwę. Żadne z tego nie jest bugiem narzędzia. To są konwencje, które boli się raz, a potem zna na pamięć — i ta wiedza odróżnia kogoś, kto wdrożył observability, od kogoś, kto ją skonfigurował i nie spojrzał.

**„docker stop to ściema — prawdziwe awarie są częściowe i wolne, nie binarnie zgaszone."**
Zgoda — i dlatego docker stop to tylko ostatni, najczystszy szczebel drabiny, a nie cały test.
Najgroźniejsze awarie produkcyjne nie są wyłączeniem — są degradacją: usługa odpowiada, ale w 2 sekundy, albo co trzeci request rzuca 500. Dlatego testuję drabiną Toxiproxy: slow (dosypana latencja), partial (część wywołań pada), down (zerwane połączenie) i heal (powrót). To slow-call-rate 50% i slow-call-duration 500 ms łapią właśnie tę wolną degradację, a histogram pokazuje rozjazd ogona — binarny CB na samym failure-rate by ją przegapił. Docker stop zostawiam na koniec, bo jest najczystszy dydaktycznie: publiczność widzi natychmiastowe, jednoznaczne otwarcie obwodu, bez szumu. Czepialski senior ma rację, że to nie jest realistyczna awaria — i właśnie dlatego nie jest jedynym testem, tylko finałem sekwencji.

**„Wasz fallback rzuca wyjątek i zwraca 409 — gdzie tu jakakolwiek degradacja? To zwykły błąd."**
Odporność to kontrola nad sposobem zawodzenia, a 409 to zawodzenie kontrolowane.
Kiedy zależność leży, mam do wyboru: zawiść niewidzialnie i paskudnie — timeoutem po 800 ms, wątkiem zżartym, wreszcie generycznym 500-tką po długim czekaniu — albo zawieść szybko i czytelnie. Fallback przechwytuje awarię, zamienia ją na `RouteUnavailableException` i zwraca 409 natychmiast: bez dobijania plannera, bez trzymania wątku, z jednoznacznym kodem, który klient umie obsłużyć, i który jest policzony w metrykach. To jest degradacja: system dalej działa, tylko ta jedna funkcja mówi uczciwie „chwilowo nie". Tam, gdzie biznes na to pozwala, fallback oddaje wartość — cache, ostatnią znaną trasę, sensowny default. Tu biznes nie pozwala oddać zmyślonej trasy, więc najlepsza dostępna degradacja to szybkie, czytelne, policzone „nie" zamiast wolnego, niewidzialnego „nie wiem".

**„Mylicie resilience z observability — to dwie różne rzeczy, a wy je zlepiacie w jeden talk."**
Są ortogonalne i właśnie na tym stoi cała teza — pointa nie leży w żadnej z nich, tylko w ich przecięciu.
Resilience to mechanizmy: circuit breaker, retry, fallback, timeout — sprawiają, że system zawodzi w kontrolowany sposób. Observability to zupełnie inny wymiar: metryki, ślady, logi — sprawiają, że widać, co się dzieje. Można mieć jedno bez drugiego: odporny system, który degraduje po cichu, albo doskonale obserwowalny system, który nie ma żadnej obrony. Tytuł talku — „Działa ≠ działa i ja o tym wiem" — to dokładnie ten iloczyn. Bo odporność, której nie widać, jest nie do odróżnienia od jej braku: jeśli breaker otworzył się o trzeciej w nocy, a żaden wykres tego nie pokazał, to z punktu widzenia inżyniera on się nie otworzył. Nie mylę tych pojęć — celowo kładę je obok siebie, bo dopiero razem dają zdanie „działa i ja o tym wiem".

### Zasada nadrzędna

Gdy senior ma rację — przyznaj to w pierwszym zdaniu, zanim cokolwiek dodasz. „Masz rację co do kosztu", „Zgoda", „To realne ryzyko" rozbrajają zarzut szybciej niż jakakolwiek riposta i kupują ci prawo do rozwinięcia. Obrona nie polega na tym, że nigdy się nie mylisz — polega na tym, że wiesz dokładnie, gdzie przebiega granica między twoją decyzją a jej kosztem. A gdy nie znasz odpowiedzi — nie zmyślaj. „Nie wiem, sprawdzę i wrócę" kosztuje cię trzy sekundy wstydu; zmyślony fakt, który ktoś z sali obali na żywo, kosztuje cię wiarygodność całego wykładu. Sala seniorów wybacza niewiedzę. Nie wybacza ściemy.


---

## Rozdział 12 — Przyszłość: Spring Boot 4, Project Loom, mesh vs biblioteka

Przez jedenaście rozdziałów budowaliśmy odporność na konkretnym stosie: Resilience4j przez `spring-cloud-starter-circuitbreaker-resilience4j`, adnotacje `@Retry` i `@CircuitBreaker` na metodach, Java 21 z wątkami wirtualnymi (ADR-0012) i blokującym `RestClient` zamiast reaktywnego klienta. Dashboard i alerty stoją na seriach `resilience4j_*`. To działa i — co ważniejsze dla tego talku — widać, kiedy się psuje. Ale prelegent, który kończy słowami „a tak będzie zawsze", kłamie. Ten rozdział jest świadomym spojrzeniem do przodu: co zmienia Spring Boot 4, co zmienia Loom, i dlaczego mimo tych zmian zasada, którą głosimy, się nie rusza.

### Spring Boot 4 / Spring Framework 7: odporność wchodzi do rdzenia

Najważniejsza nowość dla nas: Spring Framework 7 i Spring Boot 4 wnoszą **wbudowane** mechanizmy odporności do rdzenia frameworka. Funkcjonalność, która historycznie żyła w projekcie `spring-retry`, została podniesiona i rozszerzona — dostajemy `@Retryable` jako obywatela pierwszej kategorii oraz nowy `@ConcurrencyLimit` do ograniczania liczby równoczesnych wywołań metody. Włącza się je przez `@EnableResilientMethods` na konfiguracji (kierunek; szczegóły nazw i pakietów mogą się jeszcze różnić między milestone'ami a GA).

```java
@Configuration
@EnableResilientMethods
class ResilienceConfig { }

@Service
class TradeRouteClient {

    // Wbudowany retry — backoff wykładniczy + jitter, bez zewnętrznej biblioteki
    @Retryable(
        includes = TransientTradeException.class,
        maxAttempts = 3,
        delay = 200, multiplier = 2.0, maxDelay = 2000, jitter = 100)
    public Quote fetchQuote(RouteId id) {
        return restClient.get()
            .uri("/quotes/{id}", id)
            .retrieve()
            .body(Quote.class);
    }

    // Recovery = fallback dla tej samej sygnatury
    @Recover
    Quote fallback(TransientTradeException ex, RouteId id) {
        return Quote.cached(id);
    }

    // Bramka współbieżności — odpowiednik prostego bulkheada
    @ConcurrencyLimit(8)
    public void settle(Trade trade) {
        ledger.post(trade);
    }
}
```

> **Uwaga precyzyjna (potwierdź w dokumentacji GA przed użyciem w kodzie).** Rdzeniowe `@Retryable`, `@ConcurrencyLimit` i `@EnableResilientMethods` żyją w nowym pakiecie `org.springframework.resilience` (Spring Framework 7) — to NIE to samo co `@Retryable` ze `spring-retry`. Atrybuty też się różnią: nowa adnotacja używa `includes`/`excludes`/`delay`/`multiplier`, a nie `retryFor`/`value` znanych ze `spring-retry`. Z kolei `@Recover` pochodzi ze `spring-retry` — czy rdzeniowy `@Retryable` Boot 4 wspiera identyczny mechanizm odzyskiwania, zweryfikuj w GA, zanim się na nim oprzesz. Powyższy listing pokazuje *kierunek i intencję*, nie gwarantowaną sygnaturę API.

Trzy niuanse, o które dopytuje sala seniorów — warto je mieć w głowie, zanim padną:

- **Jitter siedzi na samej adnotacji, nie tylko w wariancie programowym.** `@Retryable` ma atrybut `jitter` (powyżej `jitter = 100` = ±100 ms losowego rozrzutu na backoffie). Częste nieporozumienie brzmi „żeby mieć jitter, muszę zejść do `RetryTemplate`/`RetryPolicy`" — nieprawda. Deklaratywny `@Retryable` rozprasza thundering herd równie dobrze jak API programowe; po `RetryTemplate` sięgasz dla *bloków kodu* i pełnej obserwowalności (`RetryListener`), a nie po to, by w ogóle dostać jitter.
- **`@ConcurrencyLimit` jest BLOKUJĄCY, nie fail-fast — i nie ma timeoutu oczekiwania.** Gdy limit jest wyczerpany, kolejne wątki *czekają* na zwolnienie miejsca, a nie dostają natychmiastowego odrzucenia. To istotna różnica wobec semaforowego `Bulkhead` z Resilience4j, który ma `maxWaitDuration`: w R4j możesz powiedzieć „czekaj najwyżej 50 ms, potem rzuć `BulkheadFullException`". Rdzeniowy `@ConcurrencyLimit` takiego zaworu (na ten moment) nie daje — pełny limit oznacza oczekiwanie ograniczone dopiero timeoutem wyżej w stosie (np. read-timeout klienta). Jeśli potrzebujesz „odrzuć, gdy pełno" zamiast „czekaj, aż się zwolni" — to argument za Bulkheadem R4j, nie za built-inem.
- **Nie mieszaj `@Retryable` (Spring) z `@CircuitBreaker` (Resilience4j) na JEDNEJ metodzie.** Kolejność aspektów *wewnątrz* Resilience4j jest zdefiniowana (`Retry(CircuitBreaker(...))`, sterowalna `*AspectOrder`). Ale gdy złożysz na jednej metodzie adnotacje z **dwóch różnych frameworków**, ich wzajemne zagnieżdżenie zależy od kolejności BeanPostProcessorów / `Ordered` obu warstw i nie jest oczywiste ani gwarantowane — łatwo o subtelny błąd (np. retry liczony na zewnątrz CB, który zawyża metryki failed-calls — patrz osobny wykład o kolejności aspektów). Jeśli musisz złożyć retry built-in z CB Resilience4j, rozdziel je na osobne warstwy/beany (jeden bean robi retry, inny — chroniony CB), gdzie kolejność jest jawna. W praktyce dla tej ścieżki trzymaj się jednego dostawcy.

Co to oznacza w praktyce? Dla **prostych** przypadków — „ponów trzy razy z backoffem", „nie wpuszczaj więcej niż osiem wątków do tej metody" — przestajesz potrzebować zewnętrznej zależności. Mniej BOM-ów do pilnowania, mniej dryfu wersji (a o dryf wersji w tym projekcie potykaliśmy się przy OTel — patrz rozdziały o pipeline), spójny model programistyczny z resztą Springa.

### Co z Resilience4j?

Resilience4j nie znika i nie należy go chować. Wbudowany mechanizm pokrywa retry i prosty limit współbieżności, ale Resilience4j pozostaje silniejszy wszędzie tam, gdzie zaczyna się prawdziwa inżynieria odporności:

- **slow-call detection** — circuit breaker otwierany nie tylko od błędów, ale od wywołań wolnych ponad próg (klucz do łapania „działa, ale umiera" — szarej strefy z tytułu wykładu);
- **bulkhead** w obu wariantach (semaphore i thread-pool), z kolejkowaniem i czasami oczekiwania, a nie tylko twardym limitem;
- **rate limiter** jako osobny, konfigurowalny pierwiastek;
- **bogata telemetria** — gotowe serie `resilience4j_circuitbreaker_state`, `_calls`, `_buffered_calls`, na których stoją nasze dashboardy i alerty.

Praktyczna konkluzja: dla nowego, banalnego retry sięgnij po built-in. Dla circuit breakera, który ma realnie chronić system i którego stan chcesz widzieć na ekranie — Resilience4j wciąż jest mocniejszym narzędziem.

### Zmienia się dostawca adnotacji — zasada zostaje

To jest sedno rozdziału i klamra całego wykładu. Migracja z `@CircuitBreaker` Resilience4j na `@Retryable` Springa **nie jest zmianą filozofii**. To zmiana dostawcy adnotacji. Zasada „instrumentuj to, czemu ufasz" obowiązuje identycznie: jeśli wkładasz między siebie a świat mechanizm, który po cichu ponawia, otwiera obwód albo odrzuca wywołanie, to ten mechanizm **musi** emitować metryki, bo inaczej budujesz nową, ładnie opakowaną ciemną strefę.

Dashboard i alerty operują na metrykach, nie na nazwach klas adnotacji. Dlatego migracja sprowadza się do jednej, mechanicznej operacji: **zmiany nazw serii czasowych**. Tam, gdzie dziś alert pyta o `resilience4j_circuitbreaker_state`, jutro zapyta o serię wystawioną przez warstwę Springa (jej dokładna nazwa to rzecz do sprawdzenia w GA — kierunek). Zmienia się selektor w zapytaniu PromQL i etykieta na panelu. Nie zmienia się to, *po co* tam patrzymy. Jeśli nowy dostawca nie wystawia metryki, której potrzebujesz — to jest argument, żeby przy nim *nie* migrować, a nie żeby przestać patrzeć.

### Project Loom: tani kod blokujący i koniec dylematu „reactive dla skali"

Drugi wektor zmian to wątki wirtualne, które w tym projekcie mamy włączone od ADR-0012. Loom robi rzecz prostą i głęboką jednocześnie: **blokowanie staje się tanie**. Wątek wirtualny zablokowany na I/O nie trzyma wątku systemowego — platforma odpina go i podpina inny. Znika historyczny powód, dla którego sięgało się po programowanie reaktywne: skala. Przez lata WebFlux i Reactor były odpowiedzią na pytanie „jak obsłużyć dziesiątki tysięcy równoczesnych połączeń bez puli setek wątków". Loom odpowiada na to pytanie inaczej — pisz zwykły, blokujący, czytelny kod i skaluj go wątkami wirtualnymi. Stąd nasza decyzja o `RestClient` zamiast `WebClient`: imperatywny kod, który czyta się liniowo, debuguje normalnym stosem i instrumentuje bez reaktywnych operatorów na ścieżce.

Loom przynosi też dwa narzędzia, które warto znać:

- **structured concurrency** (`StructuredTaskScope`) — zadania potomne mają jasny cykl życia związany z zakresem; jeśli jedno padnie, rodzeństwo jest anulowane, a błędy propagują się przewidywalnie. To czyni rozgałęzianie równoległych wywołań tak czytelnym jak pętla;
- **scoped values** — następca `ThreadLocal` dopasowany do milionów tanich wątków; niemutowalny kontekst dziedziczony przez zadania potomne (m.in. nośnik kontekstu trace'a, co dla nas, ludzi od obserwowalności, jest istotne).

Uwaga, którą zapłaciliśmy własnym czasem: wątki wirtualne potrafią wywrócić założenia metryk. Pod Loomem serie `tomcat_threads_*` raportują `-1` i są bezużyteczne; saturację warstwy web czytamy z `http_server_requests_active_seconds_count`. To dokładnie ta sama lekcja co przy migracji adnotacji — gdy zmieniasz silnik wykonania, zweryfikuj, że twoje liczniki dalej znaczą to, co myślisz.

### Reactive vs imperative w 2026

Reaktywność nie umiera, ale przestaje być domyślną odpowiedzią na „bo skala". W 2026 wybór jest węższy i uczciwszy: reaktywny stos ma sens, gdy potrzebujesz prawdziwego **backpressure** w strumieniu, składasz złożone pipeline'y zdarzeń albo integrujesz się z reaktywnym API od końca do końca. Dla typowego mikroserwisu request-response — kontroler, wywołanie w dół, baza — imperatywny kod na wątkach wirtualnych jest prostszy, tańszy w utrzymaniu i łatwiejszy do zaobserwowania. A „łatwiejszy do zaobserwowania" to w tym wykładzie nie jest miły dodatek, to kryterium.

### Mesh vs biblioteka — jeszcze raz, jako decyzja architektoniczna

Wracamy do pytania z wcześniejszych rozdziałów, bo przyszłość go nie usuwa, tylko zaostrza. Service mesh (Istio, Linkerd) potrafi robić retry, circuit breaking i timeouty **poza** aplikacją, na poziomie sidecara — bez linijki kodu w serwisie. Biblioteka (Resilience4j albo built-in Springa) robi to **wewnątrz** procesu, z dostępem do kontekstu domenowego.

To nie jest „albo-albo". Te warstwy są komplementarne:

- **Mesh = gruby, bezkontekstowy circuit breaker.** Infrastruktura wie, że host nie odpowiada, i odcina ruch. Nie wie, że dla zapytania o cenę trasy istnieje sensowny cache do oddania.
- **Aplikacja = subtelny fallback.** Kod wie, że `TransientTradeException` można pokryć ostatnią znaną wyceną, a `settle()` nigdy nie wolno ponawiać na ślepo. Tej wiedzy sidecar nie ma.

| Potrzeba | Sięgnij po |
|---|---|
| Retry/timeout/CB jednolicie dla wielu języków, bez zmian w kodzie | Service mesh |
| MTLS, polityki ruchu, ważone routowanie, observability sieci | Service mesh |
| Fallback z logiką domenową (cache, wartość zastępcza) | Biblioteka w aplikacji |
| Prosty retry z backoffem, limit współbieżności | Built-in Spring Boot 4 |
| Slow-call CB, bulkhead, rate limiter, bogate metryki | Resilience4j |
| Zespół zna Spring, brak platformy mesh | Biblioteka |

Reguła kciuka: niech mesh łapie awarie „grube" i wspólne dla całej floty, a aplikacja niech robi to, co wymaga znajomości domeny. I — przewidywalnie — obie warstwy muszą emitować metryki, bo dwa niewidoczne mechanizmy odporności to dwa razy więcej ciemności.

### Pytania kontrolne

1. Czym różni się migracja z `@CircuitBreaker` (Resilience4j) na `@Retryable` (Spring Boot 4) na poziomie dashboardów i alertów — co konkretnie trzeba zmienić, a co zostaje bez zmian?
2. Wymień trzy zdolności, w których Resilience4j wciąż przewyższa wbudowaną odporność Spring Boot 4. Dla każdej podaj scenariusz, w którym ma znaczenie.
3. Dlaczego wątki wirtualne (Loom) podważają historyczny argument „reactive dla skali"? Kiedy mimo to wybrałbyś stos reaktywny?
4. Podaj jeden przykład awarii, którą lepiej obsłuży service mesh, i jeden, którą lepiej obsłuży biblioteka w aplikacji. Uzasadnij, odwołując się do dostępu do kontekstu domenowego.
5. Co łączy lekcję „`tomcat_threads_*` raportuje -1 pod Loomem" z lekcją „migracja adnotacji to zmiana nazw serii"?
6. Czym różni się zachowanie `@ConcurrencyLimit` (Spring Boot 4) od semaforowego `@Bulkhead` (Resilience4j), gdy limit jest wyczerpany — i kiedy ta różnica ma znaczenie? Dlaczego nie warto składać `@Retryable` (Spring) i `@CircuitBreaker` (R4j) na jednej metodzie?

---

Niezależnie od tego, czy odporność dostarczy Resilience4j, rdzeń Spring Boot 4, czy sidecar w mesh — narzędzia będą się zmieniać, a jedno zdanie zostaje: **nie chodzi o to, żeby się nie psuło — chodzi o to, żeby psucie było widać.**


---

## Zakończenie — jedno zdanie i lista źródeł

Cały ten wykład sprowadza się do jednego zdania, które jest też mantrą talku:

> **Nie chodzi o to, żeby się nie psuło — chodzi o to, żeby psucie było widać.**

Wzorce z rozdziałów 1–5 (circuit breaker, retry, bulkhead, time limiter, rate limiter) sprawiają, że system **zawodzi w kontrolowany sposób**. Obserwowalność z rozdziałów 6–8 (Micrometer, Observation, Prometheus) sprawia, że to zawodzenie jest **widzialne**. Dopiero **przecięcie** tych dwóch rzeczy daje wartość: odporność, której nie widać, jest nie do odróżnienia od jej braku — dopóki nie jest za późno. Reszta (architektura, chaos, obrona, przyszłość) to konsekwencje i konteksty tej jednej zasady.

### Źródła do pogłębienia

- **Michael T. Nygard, *Release It!* (2nd ed.)** — biblia wzorców stabilności: circuit breaker, bulkhead, timeout, steady state. Punkt wyjścia dla rozdziałów 1–4.
- **Google SRE Book / SRE Workbook** (sre.google/books) — SLO, error budgets, cascading failures, alerting na symptomach nie przyczynach. Kontekst dla rozdziałów 1 i 8.
- **Resilience4j — dokumentacja** (resilience4j.readme.io) — konfiguracja modułów, metryki, integracja ze Spring Boot. Rozdziały 2–5.
- **Spring Boot — Observability / Actuator** (docs.spring.io) — Observation API, auto-instrumentacja, Micrometer Tracing. Rozdziały 5–7.
- **Micrometer — dokumentacja** (micrometer.io/docs) — typy metryk, konwencje nazw, naming translation do Prometheusa. Rozdziały 6, 8.
- **Prometheus — docs + PromQL** (prometheus.io/docs) — model danych, funkcje, reguły, Alertmanager. Rozdział 8.
- **OpenTelemetry — docs** (opentelemetry.io) — OTLP, kolektor, sampling (head vs tail), propagacja W3C Trace Context. Rozdział 7.
- **Toxiproxy** (github.com/Shopify/toxiproxy) — fault injection na warstwie TCP. Rozdział 10.
- **JEP 444 (Virtual Threads), JEP 453 (Structured Concurrency)** — Project Loom. Rozdziały 9, 12.
- **ADR-y projektu** (`adr/`) — 0012 (wątki wirtualne), 0014 (http-resilience), 0030 (metrics naming), 0033 (exemplary), 0035/0037 (pipeline trace/log), 0038 (resilience observability). Najbliższy, najbardziej konkretny kontekst.

> Materiały sceniczne: `slides/RUNBOOK-scena.md` (instrukcja krok-po-kroku), `slides/QA-cheatsheet.md` i `slides/QA-roleplay-junior-senior.md` (trening Q&A), `scripts/DEMO-resilience.md` (runbook demo).
