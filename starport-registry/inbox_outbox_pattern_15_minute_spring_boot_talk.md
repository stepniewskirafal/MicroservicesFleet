# Inbox & Outbox Pattern – 15‑minute Spring Boot talk

**Audience:** Java/Spring Boot devs · **Style:** pragmatic & production‑ready · **Goal:** know when/why/how to ship events safely without distributed transactions

---

## 1) Agenda (15 min)
- Problem: „dual write” i utrata spójności (2’)
- Wzorce: Outbox & Inbox – definicje i przepływy (3’)
- Implementacje w Spring Boot (polling vs. on‑commit vs. CDC) (4’)
- Semantyki dostarczania, kolejność, duplikaty (2’)
- Kiedy choreografia, a kiedy orkiestracja (2’)
- Observability w Kafka + praktyczne checklisty (2’)

---

## 2) Problem do rozwiązania: dual write
**Antywzorzec:** zapis do bazy **i** publikacja do brokera (Kafka) w dwóch niezależnych transakcjach.
- Awaria między „DB commit” a „send to Kafka” ⇒ zdarzenie **ginie**.
- Awaria między „send to Kafka” a „mark sent” ⇒ **duplikaty**.
- Brak globalnej transakcji ⇒ niespójność systemu.

**Potrzeba:** niezawodna publikacja zdarzeń bez XA/2PC.

---

## 3) Outbox – definicja i intuicja
**Outbox Pattern:**
1. W tej **samej** transakcji biznesowej zapisujesz:
   - stan domeny (np. `Reservation`), oraz
   - rekord w tabeli **OUTBOX** (np. `RESERVATION_CONFIRMED`).
2. Osobny proces (poller/CDC) „opróżnia” outbox ⇒ wysyła zdarzenia do brokera.

**Gwarancja:**
- Brak utraty zdarzeń (bo są w DB) ⇒ **co najmniej raz** (at‑least‑once) do brokera.
- Możliwe duplikaty ⇒ konsumenci muszą być idempotentni (→ **Inbox**).

---

## 4) Inbox – definicja i intuicja
**Inbox Pattern:**
- Konsument, zanim zastosuje efekt zdarzenia, sprawdza w tabeli **INBOX** czy dany `messageId` był już przetworzony.
- Jeśli tak → **pomijamy** (idempotencja).
- Jeśli nie → wykonujemy efekt + zapisujemy `messageId` jako „przetworzony” **w tej samej transakcji**.

**Efekt end‑to‑end:** outbox (co najmniej raz) + inbox (idempotencja) ⇒ **efektywnie raz** (exactly‑once effect) w granicach systemu.

---

## 5) Schemat bazy – minimalny przykład
```sql
CREATE TABLE outbox_event (
  id               UUID PRIMARY KEY,
  aggregate_type   VARCHAR(100) NOT NULL,
  aggregate_id     VARCHAR(100) NOT NULL,
  event_type       VARCHAR(100) NOT NULL,
  payload          JSONB NOT NULL,
  headers          JSONB,
  occurred_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  published_at     TIMESTAMPTZ,
  status           VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  attempts         INT NOT NULL DEFAULT 0,
  partition_key    VARCHAR(200) -- np. aggregate_id
);
CREATE INDEX outbox_pending_idx ON outbox_event(status, occurred_at);
CREATE INDEX outbox_partition_idx ON outbox_event(partition_key);

CREATE TABLE inbox_message (
  message_id   VARCHAR(200) PRIMARY KEY,
  received_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  source       VARCHAR(200)
);
```

---

## 6) Spring Boot – zapis outbox w tej samej transakcji
```java
// w serwisie domenowym – jedna @Transactional
@Transactional
public Reservation confirm(Reservation r) {
  Reservation saved = reservations.save(r.confirm());
  OutboxEvent event = OutboxEvent.of(
    UUID.randomUUID(), "Reservation", r.getId().toString(),
    "ReservationConfirmed", toJson(saved), Map.of("traceId", MDC.get("traceId")), r.getId().toString() // partitionKey
  );
  outboxRepository.save(event);
  return saved;
}
```

---

## 7) Opróżnianie outbox – trzy warianty
**A) Polling co jakiś czas (najczęstsze)**
- `@Scheduled` batch: wybiera `PENDING` (np. FIFO po `occurred_at`), próbuje wysłać, aktualizuje `status`.
- + proste sterowanie tempem, retry, DLQ.
- − kilka sekund opóźnienia, osobny worker.

**B) Na commit (after‑commit callback)**
- Rejestracja `TransactionSynchronization` i wysyłka **po** zatwierdzeniu transakcji.
- + minimalna latencja, bez dodatkowego procesu.
- − ryzyko blokad/timeoutu i mniejsza kontrola retry (trzeba fallback do A).

**C) CDC (Debezium)**
- Log binarny DB jako źródło zdarzeń; osobny connector publikuje do Kafki.
- + brak aplikacyjnego pollera; skalowalność.
- − dodatkowa infra/operacja, większa złożoność DevOps.

---

## 8) Przykład pollera w Spring Boot
```java
@Component
@RequiredArgsConstructor
class OutboxPublisher {
  private final OutboxRepository repo;
  private final KafkaTemplate<String, String> kafka;

  @Scheduled(fixedDelayString = "${outbox.poll-interval-ms:1000}")
  @Transactional
  public void publishBatch() {
    List<OutboxEvent> batch = repo.lockNextBatch(100); // SELECT FOR UPDATE SKIP LOCKED
    for (OutboxEvent e : batch) {
      try {
        kafka.send(topicFor(e), e.getPartitionKey(), e.getPayload()).get();
        e.markPublished();
      } catch (Exception ex) {
        e.markFailedWithBackoff();
      }
    }
  }
}
```
> **Uwaga:** użyj `SELECT ... FOR UPDATE SKIP LOCKED` by bezpiecznie współdzielić batch między instancje.

---

## 9) Konsument z Inbox (idempotencja)
```java
@KafkaListener(topics = "reservation-confirmed")
@Transactional
public void onReservationConfirmed(ConsumerRecord<String, String> rec) {
  String messageId = rec.headers().lastHeader("messageId").valueAsString();
  if (inboxRepository.existsById(messageId)) return; // duplikat – pomiń

  ReservationProjection p = mapper.apply(rec.value());
  projections.save(p);

  inboxRepository.save(new InboxMessage(messageId, "reservation-service"));
}
```
**Zasady:**
- `messageId` musi być **stabilne** (np. eventId z outboxa).
- Wstawienie do `inbox_message` i efekt biznesowy **w jednej transakcji**.

---

## 10) Semantyki dostarczania – fakty i mity
- **Outbox → broker:** zwykle **co najmniej raz** (duplikaty możliwe).
- **„Outbox = at‑most‑once” – mit.** At‑most‑once oznacza ryzyko **utraty** wiadomości; outbox istnieje, by temu zapobiec.
- **Broker (Kafka)** może zapewnić idempotentny producent + transakcje (EOS v2), ale nadal konsumenci powinni być idempotentni.
- **End‑to‑end „exactly once”** osiągasz poprzez **Outbox + Inbox + idempotencja**.

> *„Musimy dostarczyć, ale duplikaty nas nie bolą”* ⇒ outbox + inbox to naturalny wybór.

---

## 11) Kolejność zdarzeń
- **Globalna kolejność** (cały system): kosztowna i ogranicza przepustowość (1 partycja).
- **Rekomendacja:** kolejność **per agregat** (partition key = `aggregate_id`).
- W bazie trzymaj `event_sequence` per agregat; publisher wysyła FIFO; konsument może sprawdzać monotoniczność.

---

## 12) Kiedy choreografia, a kiedy orkiestracja?
**Choreografia (eventy, luźna współpraca)**
- Proste przepływy, niezależne reakcje (np. `ReservationConfirmed` → e‑mail, billing, analityka).
- Skalowalne, niskie sprzężenie, naturalnie **asynchroniczne**.
- *Uwaga na „choreografię synchroniczną”*: łańcuch REST‑ów w jednej ścieżce żądania to anty‑wzorzec (krucha latencja i kaskady błędów). Jeśli musisz zsynchronizować – to **orkiestracja**.

**Orkiestracja (saga/koordynator)**
- Złożone, wieloetapowe procesy, zależności/kompensacje.
- Centralny „orchestrator” steruje krokami i czasem oczekiwania.
- Lepsza kontrola błędów, timeouts, polityk retry.

**Połączenie z outbox/inbox:**
- **Choreografia:** każdy serwis publikuje przez **outbox**, konsumuje przez **inbox**.
- **Orkiestracja:** orchestrator też korzysta z outbox/inbox; kroki to eventy/komendy na Kafce.

---

## 13) Observability & „Kafka problems”
**Co mierzyć:**
- **Lag** konsumentów, czas w outbox (occurred→published), odsetek FAILED/RETRY.
- Liczba duplikatów (hit rate inbox).
- Korelacja `traceId`/`messageId` w logach (MDC) i w headerach zdarzeń.

**Praktyki:**
- Dead‑Letter‑Topic + pogląd w narzędziu (Kafka UI).
- Metryki Micrometer + alerty (np. „brak opróżniania outbox > 1 min”).
- Structured logging (JSON) i propagacja kontekstu.

---

## 14) Polityki retry, backoff i „opróżnianie”
- **Poller**: exponential backoff (`attempts`, `next_retry_at`), max attempts, przejście do DLQ.
- **Na commit**: jeżeli „send” nie powiedzie się, **nie** rollbackuj transakcji domenowej; fallback → poller.
- **CDC**: retry po stronie connectora, monitoring stanu tasków.

**Opróżnianie:**
- **Na commit** – minimalny czas dotarcia, ale bezpieczny tylko jako optymalizacja.
- **Pollowanie co jakiś czas** – kontrola tempa, łatwe skalowanie, przewidywalne.

---

## 15) Checklist – produkcyjna implementacja
- [ ] Tabele: `outbox_event`, `inbox_message` + indeksy i `SELECT ... SKIP LOCKED`.
- [ ] Stabilne `messageId`; nagłówki: `messageId`, `traceId`, `eventType`, `eventVersion`.
- [ ] Batch publishing, idempotentny producent, timeouts, `acks=all`.
- [ ] Retencja `inbox` (TTL) i archiwizacja `outbox` (vacuum/cleanup job).
- [ ] Partition key = klucz agregatu (kolejność lokalna zamiast globalnej).
- [ ] DLQ + dashboard (lag, failed, age in outbox, duplicates).
- [ ] Testy: kontrakty schematu eventów, testy idempotencji i równoległości.

---

## 16) Kiedy ten wzorzec się sprawdza (use‑cases)
- E‑commerce: zamówienia, płatności, rezerwacje – *„must deliver, duplicates ok”*.
- Finanse/księgowość: księgowanie zdarzeń do systemów raportowych.
- IoT/telemetria: snapshoty w DB + eventy do streamingu/analityki.
- Microservices: propagacja zmian domenowych bez remote XA.

**Gdy NIE używać:**
- Brak potrzeby integracji asynchronicznej lub finalnie wszystko w jednym monolicie.
- Twarda globalna kolejność wszystkich wiadomości (lepiej przemyśleć model partycji lub odpuścić asynchroniczność).

---

## 17) Kod – repo short‑list (do skopiowania)
**Repository (lockNextBatch):**
```sql
SELECT * FROM outbox_event
 WHERE status = 'PENDING'
   AND (next_retry_at IS NULL OR next_retry_at <= now())
 ORDER BY occurred_at
 FOR UPDATE SKIP LOCKED
 LIMIT :batchSize;
```
**Encja (fragment):**
```java
@Entity
class OutboxEvent {
  @Id UUID id; String aggregateType; String aggregateId; String eventType;
  @Column(columnDefinition = "jsonb") String payload; String partitionKey;
  Instant occurredAt; Instant publishedAt; String status; int attempts; Instant nextRetryAt;
  public void markPublished(){ this.status="PUBLISHED"; this.publishedAt=Instant.now(); }
  public void markFailedWithBackoff(){ this.status="PENDING"; this.attempts++; this.nextRetryAt = Instant.now().plusSeconds((long)Math.pow(2, attempts)); }
}
```

---

## 18) Podsumowanie
- **Outbox** eliminuje utratę zdarzeń, ale wprowadza **duplikaty**.
- **Inbox** zapewnia idempotencję → **efektywnie raz** po stronie konsumenta.
- **Choreografia** do prostych, niezależnych reakcji; **orkiestracja** do złożonych, sterowanych procesów.
- Obserwowalność i operacje są kluczem do „production‑grade”.

**Q&A**

