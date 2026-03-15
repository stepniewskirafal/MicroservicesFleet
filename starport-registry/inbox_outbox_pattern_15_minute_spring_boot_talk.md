# Inbox & Outbox Pattern – 15‑minute Spring Boot talk

**Audience:** Java/Spring Boot devs · **Style:** pragmatic & production‑ready · **Goal:** know when/why/how to ship events safely without distributed transactions

---

## 1) Agenda (15 min)
- Problem: "dual write" and loss of consistency (2')
- Patterns: Outbox & Inbox – definitions and flows (3')
- Implementations in Spring Boot (polling vs. on‑commit vs. CDC) (4')
- Delivery semantics, ordering, duplicates (2')
- When choreography, and when orchestration (2')
- Observability in Kafka + practical checklists (2')

---

## 2) Problem to solve: dual write
**Anti‑pattern:** writing to the database **and** publishing to a broker (Kafka) in two independent transactions.
- Failure between "DB commit" and "send to Kafka" => event is **lost**.
- Failure between "send to Kafka" and "mark sent" => **duplicates**.
- No global transaction => system inconsistency.

**Need:** reliable event publishing without XA/2PC.

---

## 3) Outbox – definition and intuition
**Outbox Pattern:**
1. In the **same** business transaction you persist:
   - domain state (e.g. `Reservation`), and
   - a record in the **OUTBOX** table (e.g. `RESERVATION_CONFIRMED`).
2. A separate process (poller/CDC) "drains" the outbox => sends events to the broker.

**Guarantee:**
- No event loss (because they are in the DB) => **at‑least‑once** delivery to the broker.
- Duplicates are possible => consumers must be idempotent (-> **Inbox**).

---

## 4) Inbox – definition and intuition
**Inbox Pattern:**
- Before applying the event's effect, the consumer checks the **INBOX** table to see if the given `messageId` has already been processed.
- If yes -> **skip** (idempotency).
- If no -> apply the effect + record the `messageId` as "processed" **in the same transaction**.

**End‑to‑end effect:** outbox (at‑least‑once) + inbox (idempotency) => **effectively once** (exactly‑once effect) within the system boundary.

---

## 5) Database schema – minimal example
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
  partition_key    VARCHAR(200) -- e.g. aggregate_id
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

## 6) Spring Boot – saving outbox in the same transaction
```java
// in the domain service – single @Transactional
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

## 7) Draining the outbox – three variants
**A) Periodic polling (most common)**
- `@Scheduled` batch: selects `PENDING` (e.g. FIFO by `occurred_at`), attempts to send, updates `status`.
- + simple pace control, retry, DLQ.
- − a few seconds of latency, separate worker.

**B) On commit (after‑commit callback)**
- Register a `TransactionSynchronization` and send **after** the transaction commits.
- + minimal latency, no additional process.
- − risk of locks/timeouts and less retry control (needs fallback to A).

**C) CDC (Debezium)**
- Database binary log as the event source; a separate connector publishes to Kafka.
- + no application‑level poller; scalability.
- − additional infra/operations, greater DevOps complexity.

---

## 8) Poller example in Spring Boot
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
> **Note:** use `SELECT ... FOR UPDATE SKIP LOCKED` to safely share batches across instances.

---

## 9) Consumer with Inbox (idempotency)
```java
@KafkaListener(topics = "reservation-confirmed")
@Transactional
public void onReservationConfirmed(ConsumerRecord<String, String> rec) {
  String messageId = rec.headers().lastHeader("messageId").valueAsString();
  if (inboxRepository.existsById(messageId)) return; // duplicate – skip

  ReservationProjection p = mapper.apply(rec.value());
  projections.save(p);

  inboxRepository.save(new InboxMessage(messageId, "reservation-service"));
}
```
**Rules:**
- `messageId` must be **stable** (e.g. eventId from the outbox).
- Inserting into `inbox_message` and the business effect must happen **in a single transaction**.

---

## 10) Delivery semantics – facts and myths
- **Outbox -> broker:** typically **at‑least‑once** (duplicates possible).
- **"Outbox = at‑most‑once" – myth.** At‑most‑once means risk of **losing** messages; the outbox exists to prevent that.
- **Broker (Kafka)** can provide an idempotent producer + transactions (EOS v2), but consumers should still be idempotent.
- **End‑to‑end "exactly once"** is achieved through **Outbox + Inbox + idempotency**.

> *"We must deliver, but duplicates don't hurt us"* => outbox + inbox is the natural choice.

---

## 11) Event ordering
- **Global ordering** (entire system): expensive and limits throughput (1 partition).
- **Recommendation:** ordering **per aggregate** (partition key = `aggregate_id`).
- In the database keep an `event_sequence` per aggregate; the publisher sends FIFO; the consumer can verify monotonicity.

---

## 12) When choreography, and when orchestration?
**Choreography (events, loose collaboration)**
- Simple flows, independent reactions (e.g. `ReservationConfirmed` -> e‑mail, billing, analytics).
- Scalable, low coupling, naturally **asynchronous**.
- *Watch out for "synchronous choreography"*: a chain of REST calls in a single request path is an anti‑pattern (fragile latency and cascading failures). If you need synchronization – that's **orchestration**.

**Orchestration (saga/coordinator)**
- Complex, multi‑step processes, dependencies/compensations.
- A central "orchestrator" controls steps and wait times.
- Better error handling, timeouts, retry policies.

**Combining with outbox/inbox:**
- **Choreography:** each service publishes via **outbox**, consumes via **inbox**.
- **Orchestration:** the orchestrator also uses outbox/inbox; steps are events/commands on Kafka.

---

## 13) Observability & "Kafka problems"
**What to measure:**
- Consumer **lag**, time in outbox (occurred->published), FAILED/RETRY ratio.
- Number of duplicates (inbox hit rate).
- Correlation of `traceId`/`messageId` in logs (MDC) and in event headers.

**Practices:**
- Dead‑Letter‑Topic + visibility in a tool (Kafka UI).
- Micrometer metrics + alerts (e.g. "outbox not drained > 1 min").
- Structured logging (JSON) and context propagation.

---

## 14) Retry policies, backoff, and "draining"
- **Poller**: exponential backoff (`attempts`, `next_retry_at`), max attempts, transition to DLQ.
- **On commit**: if "send" fails, **do not** roll back the domain transaction; fallback -> poller.
- **CDC**: retry on the connector side, monitoring task state.

**Draining:**
- **On commit** – minimal delivery time, but safe only as an optimization.
- **Periodic polling** – pace control, easy scaling, predictable.

---

## 15) Checklist – production implementation
- [ ] Tables: `outbox_event`, `inbox_message` + indexes and `SELECT ... SKIP LOCKED`.
- [ ] Stable `messageId`; headers: `messageId`, `traceId`, `eventType`, `eventVersion`.
- [ ] Batch publishing, idempotent producer, timeouts, `acks=all`.
- [ ] `inbox` retention (TTL) and `outbox` archival (vacuum/cleanup job).
- [ ] Partition key = aggregate key (local ordering instead of global).
- [ ] DLQ + dashboard (lag, failed, age in outbox, duplicates).
- [ ] Tests: event schema contracts, idempotency and concurrency tests.

---

## 16) When this pattern works well (use‑cases)
- E‑commerce: orders, payments, reservations – *"must deliver, duplicates ok"*.
- Finance/accounting: posting events to reporting systems.
- IoT/telemetry: snapshots in DB + events to streaming/analytics.
- Microservices: propagating domain changes without remote XA.

**When NOT to use:**
- No need for asynchronous integration or everything lives in a single monolith.
- Strict global ordering of all messages (better to rethink the partition model or drop asynchronicity).

---

## 17) Code – repo short‑list (ready to copy)
**Repository (lockNextBatch):**
```sql
SELECT * FROM outbox_event
 WHERE status = 'PENDING'
   AND (next_retry_at IS NULL OR next_retry_at <= now())
 ORDER BY occurred_at
 FOR UPDATE SKIP LOCKED
 LIMIT :batchSize;
```
**Entity (fragment):**
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

## 18) Summary
- **Outbox** eliminates event loss but introduces **duplicates**.
- **Inbox** ensures idempotency -> **effectively once** on the consumer side.
- **Choreography** for simple, independent reactions; **orchestration** for complex, controlled processes.
- Observability and operations are the key to "production‑grade".

**Q&A**
