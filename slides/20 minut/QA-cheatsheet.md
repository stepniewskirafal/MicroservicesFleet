# Q&A — ściąga na trudne pytania (sala seniorów)

Krótkie, gotowe odpowiedzi. Każda osadzona w realnej konfiguracji demo
(`starport-registry → trade-route-planner`). Format: **pytanie → 1 zdanie pointy → uzasadnienie**.

> **Zgrane z deckiem** `Działa-nie-równa-się-działa_TED.pptx` (22 slajdy) **i** `RUNBOOK-scena.md`. Tam, gdzie warto pokazać ekran w trakcie odpowiedzi, dopisana jest slajd-kotwica (**→ slajd N**).

---

### 1. „Po co circuit breaker, skoro mam timeout?"
**Timeout chroni JEDEN request, circuit breaker chroni SYSTEM.**
Timeout (u mnie read-timeout 800ms na `RestClient`, connect 200ms) ucina pojedyncze wiszące wywołanie. Ale jeśli zależność leży, każdy request i tak czeka pełne 800ms, zżera wątki i dobija padający serwis. CB po przekroczeniu progu (50% z 10 wywołań) **przestaje w ogóle pukać** — fail-fast w mikrosekundach + fallback. Timeout to warunek wykrycia błędu; CB to reakcja na wzorzec błędów. Działają razem, nie zamiennie. *(→ slajd 8 — trzy stany bezpiecznika.)*

---

### 2. „Retry storm / thundering herd — nie pogarszacie awarii?"
**Dlatego retry jest minimalny i siedzi POD circuit breakerem.**
U mnie `max-attempts: 2`, `wait-duration: 100ms` — czyli maks. jedna dodatkowa próba. Kolejność aspektów w Springu to `Retry(CircuitBreaker(...))`: gdy circuit jest OPEN, retry dostaje natychmiastowy `CallNotPermittedException` i **nie retry'uje w martwą zależność**. To właśnie CB rozbraja retry storm. Bez CB gołe retry faktycznie dolewa oliwy — i to jest argument ZA tym setupem, nie przeciw. (W produkcji dodatkowo: jitter na backoffie.) *(→ slajd 7 — kolejność aspektów `Retry( CircuitBreaker( … ) )`.)*

---

### 3. „Czemu nie service mesh / Istio? To samo daje na poziomie infry."
**Mesh daje retry/CB na L7, ale nie zna semantyki domenowej i nie ma fallbacku.**
Mesh świetnie robi retry/timeout/CB bez dotykania kodu — ale: (a) nie odróżni „4xx = odrzucenie domenowe" od „5xx = infra" (u mnie te ścieżki liczą różne błędy i różnie reagują), (b) nie poda **degradacji** (fallback zwracający sensowny wynik), (c) dokłada sidecar + operację. Resilience4j to decyzje **w aplikacji**, gdzie jest kontekst biznesowy. Mesh i biblioteka nie wykluczają się — często warstwa infra robi grube CB, a aplikacja subtelne fallbacki.

---

### 4. „Dlaczego failure_rate pokazuje -1?"
**To nie błąd — to „za mało danych, by liczyć".**
CB liczy failure rate dopiero po `minimum-number-of-calls` (u mnie 5) w oknie `sliding-window-size: 10`. Przed tym progiem zwraca `-1`, żeby nie otwierać circuita na podstawie jednego pecha. Dashboard mapuje `-1 → "—"`. Dlatego przed demem robię warm-up ruchem.

---

### 5. „CB jest per-instance czy globalny? Macie 2 repliki."
**Per-instance — każda replika ma własny circuit i własne metryki.**
Stan CB żyje w pamięci JVM, więc `starport-registry-1` i `-2` otwierają się niezależnie (widać to w `instance` na dashboardzie). To celowe: replika, która trafia na zdrowy egzemplarz planera, nie musi cierpieć przez pecha drugiej. Na panelach „biznesowych" agreguję przez `max(...)` po instancjach — „czy KTÓRAKOLWIEK replika ma problem". Globalny, współdzielony stan (np. przez Redis) to inna, cięższa decyzja — tu niepotrzebna.

---

### 6. „Fallback rzuca wyjątek — to nie jest żadna degradacja?"
**Słuszna uwaga — to degradacja sterowana, nie cisza.**
Mój `routeUnavailableFallback` liczy metrykę `errorType="circuit_open"` i rzuca `RouteUnavailableException` → kontroler zwraca **409**, a nie 500/timeout. Czyli: rezerwacja bez trasy jest świadomie odrzucana z czytelnym kodem, klient wie co się stało, a my mamy sygnał. Gdyby biznes pozwalał, fallback mógłby zwrócić trasę z cache albo „best-effort" — wzorzec ten sam, treść inna. Klucz: fallback zamienia **niewidzialny timeout w widzialną, policzalną decyzję**. *(→ slajd 13 — fallback w kodzie: `incrementErrorCounter` + `throw` → 409.)*

---

### 7. „Po co osobna metryka `reservations_route_plan_errors_total`, skoro macie metryki R4j?"
**Bo to dwa różne poziomy: techniczny vs domenowy.**
Metryki Resilience4j mówią „CB jest otwarty / 60% wywołań failed" — język infrastruktury. Mój licznik z `errorType` (`domain` / `infrastructure` / `circuit_open` / `empty_response`) mówi „ile REZERWACJI straciło trasę i dlaczego" — język produktu. Pierwsze dla SRE, drugie do rozmowy z biznesem o wpływie. (→ ADR-0030: nazwa po domenie, nie po serwisie.)

---

### 8. „Metryki, trace'y, logi z R4j — to jeden mechanizm?"
**Nie. Resilience4j daje metryki Micrometera; tercet metryka+trace+log dla samego HTTP daje Observation API.**
To dwie współpracujące warstwy. R4j wystawia liczniki/gauge (+ event-consumery). Pełny trace wywołania (i exemplar łączący metrykę z trace'em — ADR-0033) pochodzi z auto-instrumentacji `RestClient` przez Observation w Spring Boot 3. Grają razem, ale to nie ten sam kod — i lepiej powiedzieć to wprost niż dać się złapać. *(→ slajd 10 — Micrometer jako fasada; „Micrometer jest dla metryk tym, czym SLF4J jest dla logów".)*

---

### 9. „Czemu sliding-window COUNT_BASED, nie TIME_BASED?"
**Bo ruch na tej ścieżce jest nierównomierny.**
COUNT_BASED (ostatnie N=10 wywołań) reaguje na *proporcję* błędów niezależnie od tego, czy leci 1 czy 100 req/s. TIME_BASED (ostatnie N sekund) lepiej pasuje do stałego, wysokiego ruchu. Przy zmiennym ruchu okno czasowe albo zbyt wolno reaguje przy niskim natężeniu, albo zbiera za mało próbek. Dla wywołania zależności o zmiennej częstości COUNT_BASED jest bezpieczniejszy.

---

### 10. „Ile to kosztuje? Scrape co 3s × 2 repliki — drogo?"
**Metryki R4j są tanie: gauge'e czytane z pamięci, nie obliczenia.**
`resilience4j_circuitbreaker_state` itp. to wartości trzymane w MeterRegistry, odczyt przy scrape jest O(1) — to nie lambda licząca coś po DB przy każdym scrape (ta pułapka dotyczy źle napisanych `Gauge` supplierów). Histogramy `calls_seconds` mają ograniczone buckety. Koszt kardynalności trzymamy nisko: low-cardinality labele (`name`, `state`, `kind`), zero ID w labelach (→ ADR-0030).

---

### 11. „Co z Spring Boot 4 — to wszystko do wyrzucenia?"
**Nie — zasada zostaje, zmienia się dostawca adnotacji.**
Boot 4 wnosi wbudowaną odporność (`@Retryable`, `@ConcurrencyLimit`, `@EnableResilientMethods`) bez zewnętrznej biblioteki. Ale instrumentacja przez Micrometer/Observation i cała pointa — „instrumentuj to, czemu ufasz" — są niezmienne. Dashboard i alerty operują na metrykach, więc migracja to zmiana nazw serii, nie filozofii. **To podzbiór, nie zamiennik:** brak CircuitBreakera, RateLimitera, TimeLimitera, slow-call i fallbacku deklaratywnego — po nie wciąż idziesz do R4j. *(→ slajd 21 — opcjonalny forward na koniec: zmienia się dostawca adnotacji, zasada zostaje.)*

---

### 11 (cd.) — trzy doprecyzowania, gdy ktoś dopyta o szczegóły Spring Boot 4

- **(a) `@ConcurrencyLimit` jest blokujący, nie fail-fast** — przy pełnym limicie wątki *czekają*, bo nie ma odpowiednika `maxWaitDuration` z semaforowego `Bulkhead` R4j. Chcesz „odrzuć, gdy pełno" zamiast „czekaj, aż się zwolni" — to argument za R4j.
- **(b) `jitter` jest atrybutem samej adnotacji `@Retryable`** — nie trzeba schodzić do `RetryTemplate`, żeby rozproszyć backoff.
- **(c) Nie mieszaj `@Retryable` (Spring) z `@CircuitBreaker` (R4j) na jednej metodzie** — kolejność aspektów *między* frameworkami nie jest gwarantowana (zależy od `Ordered`/BPP obu warstw). Rozdziel je na osobne beany albo trzymaj jednego dostawcę.

---

### 12. „Testujecie to? Skąd wiecie, że CB faktycznie się otwiera?"
**Tak — i mam smoke test, który to sprawdza przed demem.**
`scripts/preflight-demo.ps1 -Full` realnie zatrzymuje obie repliki planera, generuje ruch, czeka aż `state{state="open"}==1` i aż alert dojdzie do FIRING, potem przywraca. Plus testy jednostkowe/akceptacyjne na fallback (WireMock zwraca 5xx → oczekujemy `RouteUnavailableException`/409). Jak ktoś chce: pokażę kod adaptera na żywo. *(→ RUNBOOK-scena.md, krok T-15: to dokładnie ten sam `preflight-demo.ps1 -Full`, który odpalasz przed wejściem na scenę.)*
