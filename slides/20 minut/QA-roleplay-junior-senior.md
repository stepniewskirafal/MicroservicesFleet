# Q&A roleplay — junior (ciekawy) vs senior (czepialski)

Trening na ogień z sali. Dwie role, po 10 pytań, każde z **wzorcową odpowiedzią**.
Osadzone w realnym kodzie demo (`starport-registry → trade-route-planner`).

> **Zgrane z deckiem** `Działa-nie-równa-się-działa_TED.pptx` (22 slajdy) **i** `RUNBOOK-scena.md`. Kluczowe odpowiedzi mają slajd-kotwicę (**→ slajd N**) — slajd, na który możesz przeskoczyć, odpowiadając. Nowe slajdy warte zapamiętania: **8** (bezpiecznik, 3 stany), **16** (drabina eskalacji „przed → po"), **17** (alert domyka pętlę).

**Zasada nadrzędna na scenie:** gdy senior ma rację — **przyznaj ją w pierwszym zdaniu**.
Obrona przegranego punktu kosztuje więcej wiarygodności niż sam błąd. Najmocniejszy
prelegent to ten, który mówi „dobry punkt, tu upraszczam — oto dlaczego".

---

## CZĘŚĆ 1 — Junior developer (miły, ciekawy, chce zrozumieć)

> Ton: życzliwy, uczący się. Pytania proste, ale jeśli na nie nie odpowiesz prosto —
> stracisz pół sali, która myśli tak samo, tylko się nie odzywa.

### J1. „Co to właściwie jest circuit breaker — w jednym zdaniu?"
To bezpiecznik jak w domu: gdy zależność zaczyna sypać błędami powyżej progu,
breaker „przepala się" (OPEN) i przez chwilę **w ogóle nie próbuje** dzwonić,
żeby nie dobijać padającej usługi i nie kazać użytkownikom czekać w nieskończoność.
Po chwili próbuje ostrożnie wrócić (half-open). *(→ slajd 8 — dokładnie ta metafora domowego bezpiecznika i trzy stany.)*

### J2. „Mówisz »metryki za darmo« — muszę coś napisać, żeby je mieć?"
Nie piszesz ani jednego licznika. Dodajesz trzy zależności (`actuator`,
`resilience4j`, `micrometer-registry-prometheus`) i adnotacje `@CircuitBreaker`/`@Retry`.
Resilience4j sam rejestruje metryki w `MeterRegistry`, a actuator wystawia je pod
`/actuator/prometheus`. Jedyny ręczny licznik w moim kodzie (`reservations_route_plan_errors_total`)
to świadoma metryka **domenowa**, nie techniczna — i to też jedna linijka.

### J3. „Retry i circuit breaker brzmią podobnie — czym się różnią?"
Retry mówi „spróbuj jeszcze raz **to samo wywołanie**" (pojedynczy request).
Circuit breaker patrzy na **wzorzec wielu wywołań** i decyduje „przestań w ogóle próbować".
Retry leczy chwilowy glitch (zgubiony pakiet), CB chroni przed trwałą awarią.
U mnie współpracują: `Retry(CircuitBreaker(...))` — gdy CB jest otwarty, retry nawet nie próbuje.

### J4. „Co to znaczy »half-open«?"
Po `wait-duration-in-open-state` (u mnie 10s) breaker nie wraca od razu do pełnego
ruchu — wpuszcza **kilka próbnych wywołań** (`permitted-number-of-calls-in-half-open-state: 3`).
Jeśli przejdą → zamyka się (CLOSED, zdrowo). Jeśli dalej failują → znów OPEN.
To ostrożne „sprawdzam, czy już bezpiecznie", zamiast zalania wracającej usługi ruchem. *(→ slajd 8 — stan PÓŁOTWARTY; ten sam powrót widać na żywo w demie, Krok 5 „heal".)*

### J5. „Czemu Twój fallback **rzuca wyjątek**? Myślałem, że fallback ma coś zwrócić."
Bardzo dobre pytanie — fallback nie musi rzucać. Mój zwraca błąd domenowy świadomie:
biznes mówi „rezerwacja bez policzonej trasy nie ma sensu", więc degradacją jest
**czyste, szybkie odrzucenie (409)** zamiast wiszącego timeoutu (500). Gdyby biznes
pozwalał, fallback mógłby zwrócić trasę z cache albo wartość domyślną — wzorzec ten sam,
treść inna. Kluczowe: zamieniam niewidzialny timeout w **widzialną, policzalną decyzję**. *(→ slajd 13 — fallback liczy metrykę i oddaje 409; komentarz „degradacja, nie cisza".)*

### J6. „Skąd wziąłeś te liczby — 50%, okno 10, minimum 5? Jakie dać u siebie?"
To są wartości startowe, nie prawda objawiona. Logika: `minimum-number-of-calls: 5`
żeby nie otwierać na podstawie jednego pecha; okno 10 żeby reagować szybko; próg 50%
bo dla tej ścieżki połowa błędów to już realny problem. U siebie zacznij od podobnych
i **dostrój patrząc na dashboard** — jeśli circuit otwiera się za czule, podnieś próg
albo okno. Te liczby kalibruje się danymi, nie zgadywaniem.

### J7. „Mam u siebie stary `RestTemplate`, nie `RestClient` — zadziała?"
Tak. `@CircuitBreaker`/`@Retry` działają na **dowolnej metodzie Springa** (to AOP),
niezależnie od tego, czym robisz HTTP. RestTemplate też jest auto-instrumentowany
Observation w Boot 3, więc metryki i trace masz tak samo. Adnotacje nie obchodzi,
co jest w środku metody.

### J8. „A z WebClient / reactive to działa?"
Działa, ale potrzebujesz dodatkowo `resilience4j-reactor` i operatorów
(`.transformDeferred(CircuitBreakerOperator.of(cb))`) zamiast samej adnotacji,
bo świat reactive nie lubi blokujących aspektów. U mnie wywołanie jest blokujące
na wątkach wirtualnych (ADR-0012), więc adnotacja wystarcza.

### J9. „Te metryki »mieszkają« w aplikacji czy w Prometheusie?"
Żyją w aplikacji, w `MeterRegistry` (pamięć JVM). Prometheus co 3s **odpytuje**
`/actuator/prometheus` i kopiuje sobie ich wartość ze znacznikiem czasu — tak buduje
historię. Aplikacja zna tylko „teraz", Prometheus zna „przeszłość". Grafana rysuje
to, co ma Prometheus (u nas przez Thanos).

### J10. „Chcę to u siebie — od czego zacząć w poniedziałek?"
Trzy kroki, jedna zależność: (1) dodaj `resilience4j` + `actuator` +
`micrometer-registry-prometheus`; (2) owiń **jeden, najważniejszy** call zewnętrzny
w `@CircuitBreaker` + `@Retry` z fallbackiem; (3) zaimportuj dashboard i postaw
**jeden** alert na `state="open"`. Nie rób tego naraz wszędzie — od jednej krytycznej
zależności, naucz się czytać wykres, potem rozszerzaj. *(→ slajd 20 „Konkret na poniedziałek" — te same trzy kroki.)*

---

## CZĘŚĆ 2 — Senior developer (czepialski, chce obalić)

> Ton: konfrontacyjny, szuka nadmiarowości, dziur i luk w Twojej wiedzy.
> Strategia: **nie broń się odruchowo**. Oddziel ziarno prawdy (przyznaj) od przesady
> (odbij konkretem). Senior, który widzi, że rozumiesz jego zarzut głębiej niż on sam,
> przestaje atakować.

### S1. „Masz timeout 800ms, LoadBalancer health-check co 5s i Eureka. Circuit breaker to nadmiarowość. Po co go dokładasz?"
Trzy różne warstwy, trzy różne problemy. Eureka/LB health-check usuwa **trupa**
(instancję, która nie odpowiada na `/actuator/health`) — ale nie pomoże, gdy instancja
**żyje i odpowiada błędami** albo wolno. Timeout chroni **pojedynczy** request, ale
przy leżącej zależności każdy i tak czeka pełne 800ms i zżera wątki. CB widzi **wzorzec**
i przestaje pukać — fail-fast w mikrosekundach. To nie nadmiarowość, to trzy poziomy:
trup (LB), pojedynczy glitch (timeout/retry), trwały wzorzec błędów (CB).

### S2. „`max-attempts: 2`, `wait: 100ms` — jeden retry po 100 ms to placebo. Co to realnie ratuje?"
Słusznie, że to **mało** — i to celowo. Retry ratuje wyłącznie **przejściowy** błąd:
zgubiony pakiet, krótki GC pause, rolling restart jednej repliki planera. Dla takich
przypadków jedna ponowna próba na **drugą** instancję (mam load balancing) wystarcza.
Czego retry świadomie **nie** próbuje leczyć, to trwała awaria — od tego jest CB.
Większy retry przy leżącej zależności to retry storm, więc agresywny retry byłby
tu błędem, nie zaletą. To nie placebo — to celowo wąski zakres.

### S3. „Demo przez `docker stop` to ściema. Prawdziwe awarie są częściowe i wolne, nie »wszystko padło«. Pokazujesz najłatwiejszy przypadek."
Dlatego demo to **drabina eskalacji** — pięć szczebli widocznych na **slajdzie 16**, nie jedno
`docker stop`. Wpinam **Toxiproxy** między starport a planner i pokazuję po kolei:
`-Chaos slow -SlowMs 300` (latencja rośnie do ~350 ms, ale **pod** progiem slow-call 500 ms →
`slow_call_rate` wciąż 0%, breaker **trzyma** — to jest szara strefa), potem
`-Chaos slow -SlowMs 600` (nad progiem → `slow_call_rate` dochodzi do 50% i **CB otwiera się
bez ani jednego błędu**; CB ma osobny próg slow-call: `slow-call-rate-threshold: 50`,
`slow-call-duration-threshold: 500ms`), potem `-Chaos partial` (~50% wywołań w read-timeout →
failure rate ~50%, widać pracujący retry), a dopiero na końcu `-Chaos down` (pełny zgon →
CB OPEN na błędach, fallback 409). Twardy `docker stop` (`-Chaos kill`) zostaje jako wariant
zapasowy. Masz rację, że degradacja jest częstsza niż zgon — dlatego pokazuję ją jako pierwszą,
a `slow_call_rate` i histogram `calls_seconds` ją łapią.

### S4. „CB per-instance, okno COUNT 10, minimum 5, dwie repliki za LB. To statystycznie szum, nie sygnał."
Zgoda, że okno 10 to **mała próbka** — i to jest świadomy trade-off między czułością
a stabilnością, nie przeoczenie. Per-instance jest celowe: stan CB to pamięć JVM,
współdzielenie go (np. przez Redis) dokłada zależność sieciową na ścieżce, która ma
być szybka. Skutek „szumu" tłumię dwojako: `minimum-number-of-calls` blokuje otwarcie
na małej próbce, a na dashboardzie agreguję po instancjach. Dla **wysokiego** ruchu
przełączyłbym na `TIME_BASED` — przy zmiennym ruchu COUNT jest bezpieczniejszy.
To dostrajalne, nie wyryte w kamieniu.

### S5. „Cała teza »to była awaria widoczności« jest naciągana. Kontrakt test albo healthcheck na właściwy URL i nie strzelałbyś 3 miesiące w zły adres. Leczysz objaw, nie przyczynę."
Najmocniejszy zarzut i częściowo masz rację: **kontrakt test wyłapałby zły URL** —
i powinien był. Ale to dwie różne klasy problemów. Test wyłapuje to, co przewidziałeś
**przed** wdrożeniem. Observability łapie to, czego **nie** przewidziałeś, w runtime:
zależność, która działała i przestała, partner, który zmienił zachowanie, degradację
o 3 nad ranem. Mój morał nie brzmi „nie piszcie testów" — brzmi „**założenie, że coś
działa, musi być mierzone, nie zakładane**". Test i metryka to nie alternatywa, to dwie
warstwy tej samej dyscypliny. Dorzucę ten niuans przy tezie (**slajd 6**) — dobry punkt.

### S6. „Mylisz resilience z observability. To ortogonalne rzeczy, a Ty je sklejasz, żeby talk brzmiał spójnie."
Są ortogonalne jako mechanizmy — i właśnie **na tym** stoi cała teza. Możesz mieć
resilience bez observability (retry, którego nie widać — i to jest pułapka, którą
pokazuję) i observability bez resilience (widzisz, że pada, ale nic z tym nie robisz).
Pointa talku to dokładnie ich **przecięcie**: odporność, której **nie widać**, jest
nie do odróżnienia od jej braku, dopóki nie jest za późno. Nie sklejam dwóch tematów —
pokazuję, że dopiero **razem** dają wartość. To teza, nie pomyłka kategorialna.

### S7. „»Metryki za darmo« to marketing. Płacisz kardynalnością, scrape co 3s × repliki × histogramy, retencją w Thanos. Powiedz prawdę o koszcie."
Racja, „za darmo" to skrót — chodzi o **zero kodu**, nie zero kosztu. Koszt jest i go
kontroluję: labele są low-cardinality (`name`, `state`, `kind` — żadnych ID, to osobne
ADR), histogram ma kilka bucketów SLO zamiast ~90 domyślnych, a 3s scrape i retencja
to świadoma decyzja całego stacku (Prometheus 6h + Thanos długoterminowo). Same gauge'e
R4j to odczyt z pamięci, O(1) — to nie supplier liczący po DB przy każdym scrape, bo
**to** byłaby realna pułapka kosztowa. Koszt jest znany i ograniczony, nie zerowy.

### S8. „W swoich notatkach masz `failure_rate = -1`, `retry_calls` to gauge nie counter, znikające `.total`. To znaczy, że narzędzie jest dziurawe, a Ty uczysz ludzi obejść jego buble."
Te „buble" to **konwencje**, nie błędy — i dobrze, że je znam, bo to różnica między
„skopiowałem dashboard z neta" a „rozumiem, co mierzę". `-1` to celowy sentinel
„za mało danych" (lepszy niż fałszywe 0%). `retry_calls` jako gauge wynika z natury
Micrometera. Znikające `.total` to reguła translacji nazw Prometheus/Micrometer.
To, że je wymieniam, jest **dowodem rzetelności**, nie słabości narzędzia — pokazuję
sali dokładnie to, na czym sam się przejechałem, żeby oni nie musieli.

### S9. „Resilience4j jest de facto w trybie maintenance, a Spring Boot 4 wnosi `@Retryable`/`@EnableResilientMethods` wbudowane. Uczysz legacy na konferencji w 2026."
Mam to jako świadomy forward przy klamrze (**slajd 21**, opcjonalna wstawka na koniec), nie przeoczenie. Resilience4j
to dziś najczęstsze, co ludzie mają **w produkcji** — uczę narzędzia, które publiczność
realnie ma w classpathie dziś, nie tego, co będą mieli po migracji na Boot 4. I to jest
clou: zmienia się dostawca adnotacji, **zasada zostaje identyczna** — instrumentuj to,
czemu ufasz. Dashboard i alerty operują na metrykach, więc migracja to zmiana nazw serii,
nie filozofii. Pokazuję rzecz aktualną i mówię wprost, dokąd to idzie.

### S10. „Fallback, który robi `throw new RouteUnavailableException`, to nie odporność — to przemalowanie 500 na 409. Użytkownik dalej nie dostał trasy. Gdzie tu »degradacja«?"
Trafny i lubię go, bo zmusza do precyzji. Masz rację, że **funkcjonalnie** użytkownik
trasy nie dostał — ale „odporność" nie znaczy „magicznie dostarcz wynik mimo awarii".
Znaczy „**kontroluj sposób, w jaki zawodzisz**". Różnica między moim 409 a gołym 500/timeout
jest realna: szybko (fail-fast, nie 800ms wisienia), z czytelnym kodem (klient wie:
„spróbuj później", nie „coś się zepsuło"), bez dobijania planera, i **policzone**
(metryka `circuit_open`). Gdzie biznes pozwala na cache/wartość domyślną — fallback
ją zwróci. Tu nie pozwala, więc kontrolowane odrzucenie **jest** degradacją. Cisza vs
sterowana porażka — to nie kosmetyka. *(→ slajd 13 — ten fallback w kodzie: liczy metrykę, rzuca wyjątek → 409.)*

---

## BONUS — szybkie kontry (gdy ktoś rzuci jednym zdaniem)

| Zaczepka | Kontra w jednym oddechu |
|---|---|
| „Alert bez Alertmanagera = zabawka." | Zgoda — routing notyfikacji to osobny krok prod, świadomie poza zakresem 20-min talku. Reguła i sygnał są realne (**slajd 17** pokazuje sam warunek PromQL + FIRING); podpięcie Slacka to 10 linii configu, nie zmiana koncepcji. |
| „Blocking RestClient na virtual threads? Czemu nie reactive?" | Bo Loom (ADR-0012) daje czytelny, blokujący kod bez kosztu wątków. Reactive dokłada złożoność, której ta ścieżka nie potrzebuje. CB działa na obu. |
| „`max()` po instancjach ukrywa, że tylko jedna replika padła." | Celowo — panel biznesowy odpowiada „czy KTÓRAKOLWIEK replika ma problem". Panel dev rozbija po `instance`. Dwie publiczności, dwa widoki, ten sam sygnał. |
| „Pokazujesz happy path chaosu." | Nie — demo to drabina (**slajd 16**): `slow 300` (pod progiem, breaker trzyma) → `slow 600` (nad progiem, OPEN bez błędów) → `partial` (timeout ~50%) → `down` (zgon). Slow-call rate i histogram łapią wersję trudną. |
| „To wszystko jest w dokumentacji Resilience4j." | Jest. Talk nie jest o tym, ŻE to istnieje — jest o tym, że odporność bez metryki jest niewidzialna, i co to kosztuje. Narzędzie to środek, nie temat. |

---

## Jak reagować, gdy NIE znasz odpowiedzi
Nie improwizuj faktów. Wybierz jedno:
1. **„Nie wiem na pewno — strzelam, że X, ale sprawdzę i odpiszę."** (uczciwe, buduje zaufanie)
2. **„Dobry punkt, to jest poza tym, co testowałem — masz doświadczenie z tym?"** (oddaj głos, zamień atak w rozmowę)
3. **„To zależy od [konkretny wymiar] — w moim przypadku [fakt], u Ciebie może być inaczej."**

Senior, który Cię złapie na zmyślaniu, wygrał. Senior, któremu powiesz „nie wiem,
sprawdzę" — przestaje atakować, bo nie ma czego.
