# 0020 — Concurrent Reservation Safety (Pessimistic Row Lock + SKIP LOCKED)

**Status:** Accepted
**Date:** 2026-04-17

---

## Context

A reservation system has an obvious concurrency hazard: two requests racing to book the same
`docking_bay` for an overlapping time window must not both succeed. This is the single most
important invariant of `starport-registry` — violating it silently double-books a bay and
corrupts the schedule.

The service targets hundreds of concurrent reservation requests (plan in
`plany/plan-obsluga-setek-requestow.md`), uses virtual threads (ADR-0012), a small HikariCP
pool (ADR-0013), and runs ≥2 application replicas behind Eureka (ADR-0008). Any safety
strategy that relies on a single JVM (`synchronized`, `ReentrantLock`, in-memory sets) is
wrong by construction — a bay can be selected by replica A while replica B is in the middle
of booking it.

The naive solutions are also wrong:

- **Optimistic lock (`@Version`)** on `DockingBayEntity`: fails with `OptimisticLockException`
  only if two writers actually race on the same row — but here two writers race on different
  rows (different bays) that the *read phase* has already deemed "free". Optimistic lock on
  the bay row does not detect that the `reservation` row written by the other thread has
  invalidated our decision.
- **Unique constraint** on `(docking_bay_id, time_range)`: PostgreSQL `EXCLUDE USING gist`
  would work but requires `btree_gist` extension and a range type; complex to maintain and
  expensive for the write path.
- **Application-level locking** (distributed lock via Redis / Zookeeper): extra
  infrastructure, and correctness still depends on the lock outliving the DB transaction.

---

## Decision

Prevent double-booking with a **pessimistic row-level lock** taken by the `SELECT` query
that picks the bay. The lock is held for the duration of the enclosing `@Transactional`
method, released on commit/rollback, and — critically — uses `SKIP LOCKED` so concurrent
searchers see each other's in-flight rows as "unavailable" rather than blocking on them.

### The query

```java
// starport-registry/src/main/java/com/galactic/starport/repository/DockingBayRepository.java:10-34
@Query(value = """
    SELECT db.*
    FROM docking_bay db
    WHERE db.starport_id = :starportId
      AND db.class = :shipClass
      AND NOT EXISTS (
          SELECT 1 FROM reservation r
          WHERE db.id = r.docking_bay_id
            AND r.start_at < :endAt
            AND r.end_at   > :startAt
      )
    LIMIT 1
    FOR UPDATE SKIP LOCKED
    """, nativeQuery = true)
Optional<DockingBayEntity> findFreeBay(...);
```

Three features combine to make this correct:

1. **Range-overlap test** `start < :endAt AND end > :startAt` — the standard mathematical
   check for "two half-open intervals intersect". Runs as part of the `NOT EXISTS` subquery,
   not as a separate read — the "is it free" decision and the "take the lock" step happen
   atomically.
2. **`FOR UPDATE`** — acquires `ROW SHARE` → `ROW EXCLUSIVE` → row-level lock on the returned
   `docking_bay` row. Released at transaction commit.
3. **`SKIP LOCKED`** — if another transaction already holds the row, don't wait; skip and
   try the next candidate. Turns a possible deadlock/starvation problem into a simple
   "pick the next free bay" scan.

### Why this is safe under concurrency

Scenario: requests A and B both want any free class-C bay at 10:00–12:00. Two bays (7, 11)
match.

| Step | Request A                                                   | Request B                                                 |
|---|---|---|
| T1   | `BEGIN; SELECT ... FOR UPDATE SKIP LOCKED LIMIT 1` → bay 7 (locked) | (waits no-one — `SKIP LOCKED`)                                  |
| T2   |                                                             | `BEGIN; SELECT ... FOR UPDATE SKIP LOCKED LIMIT 1` — bay 7 is locked, skip; returns bay 11 |
| T3   | `INSERT INTO reservation (bay 7, 10:00, 12:00, HOLD)`       | `INSERT INTO reservation (bay 11, 10:00, 12:00, HOLD)`    |
| T4   | `COMMIT`                                                    | `COMMIT`                                                  |

No double-booking, no blocking, no deadlock.

Scenario: only bay 7 is free. B arrives during A's transaction.

| Step | Request A                                            | Request B                                                |
|---|---|---|
| T1   | `SELECT FOR UPDATE SKIP LOCKED` → bay 7              |                                                           |
| T2   |                                                      | `SELECT FOR UPDATE SKIP LOCKED` — bay 7 is locked → 0 rows |
| T3   | `INSERT ... COMMIT`                                  | Returns `Optional.empty()` → controller → 409 Conflict    |

B fails fast with `NoDockingBaysAvailableException` (→ HTTP 409, ADR-0015). The user sees an
immediate, actionable error rather than a 30 s timeout on a row-lock wait.

### Reservation state machine

```java
// starport-registry/src/main/java/com/galactic/starport/service/Reservation.java:26-30
public enum ReservationStatus {
    HOLD,        // bay reserved, fee not yet calculated
    CONFIRMED,   // fee + (optional) route computed and persisted
    CANCELLED    // (reserved for future use — see gaps)
}
```

The reservation is written in two transactions by design (not one):

```
TX1:  createHoldReservation
  SELECT FOR UPDATE SKIP LOCKED (bay)     ← pessimistic lock acquired
  INSERT reservation (status = HOLD)
  COMMIT                                   ← lock released; row persisted
─── no DB connection held ───
  feeCalculator.calculateFee(...)         ← concurrent virtual-thread work
  routePlanner.calculateRoute(...)        ← HTTP to trade-route-planner
─── fee + route now known ───
TX2:  confirmReservation
  SELECT reservation WHERE id = ?
  UPDATE reservation SET fee = ?, route_id = ?, status = CONFIRMED
  COMMIT
```

This split is deliberate (ADR-0013) — it lets the slow work (fee, HTTP) run **without**
holding a DB connection, which is how 30 HikariCP connections can serve hundreds of
concurrent requests.

---

## How the codebase enforces this

1. **Repository** — `DockingBayRepository.findFreeBay()` is the **only** query that picks a
   bay; all reservation creation goes through it. No alternative "list free bays" API exists.
2. **Transaction boundary** — `JpaStarportRepository.createHoldReservation()` is
   `@Transactional`. The lock is acquired *inside* this method and released on commit;
   external callers never see the lock.
3. **State transition** — `ReservationEntity.confirmReservation()` (`ReservationEntity.java:87-96`)
   is the sole path from `HOLD → CONFIRMED`. It sets `status = CONFIRMED` inside a
   `@Transactional` method (`JpaStarportRepository.confirmReservation`, see ADR-0013 line 107).
4. **Parallel compute between transactions** — `ReservationCalculationService` runs
   `feeCalculator` and `routePlanner` on a dedicated virtual-thread executor (ADR-0012 §
   "explicit virtual thread executor"), gated only by `CompletableFuture.join()` — no DB
   work between TX1 and TX2.
5. **Tests** — `CreateHoldReservationServiceRepositoryTest` declares
   `@Execution(ExecutionMode.CONCURRENT)` with a shared Testcontainers Postgres
   (`@ResourceLock("DB_TRUNCATE", mode = READ)`), i.e. the test itself exercises the
   concurrency path. `ParallelRoutePlannerTest` covers B under 40 concurrent threads
   (ADR-0012).

---

## Consequences

### Benefits

- **Correct by construction.** The "is this bay free?" check and the lock acquisition are a
  single `SELECT` — race-free.
- **Works across replicas.** The lock is in PostgreSQL, so a two-replica deployment
  (ADR-0008) behaves identically to a single one.
- **No waiting.** `SKIP LOCKED` means a busy bay is treated as unavailable, not as a reason
  to block. Latency stays predictable under heavy contention.
- **Connection-friendly.** The pessimistic lock is only held during TX1 (a single fast
  `SELECT` + `INSERT`). The long work (fee + HTTP) does not hold the lock, so the
  30-connection pool is not a bottleneck (ADR-0013).
- **Deterministic failure mode.** No free bay → `Optional.empty()` → controller returns 409
  immediately; no 30 s `lock_timeout` wait.

### Trade-offs and known gaps

- **HOLD rows can leak.** If TX1 commits but TX2 never runs (application crash, fee
  calculator throws, circuit breaker opens → `RouteUnavailableException`), a row with
  `status = HOLD` occupies the bay for the full booking window. There is **no scheduler**
  today to expire stale HOLDs. The `CANCELLED` enum value exists but is unused; a cleanup
  job is a deliberate TODO.
- **No idempotency key.** `POST /api/v1/starports/{code}/reservations` is not protected
  against client retries after a timeout. If the client retries a request that succeeded
  server-side, it creates a **second** reservation with a different `id`. Adding an
  `Idempotency-Key` header contract is tracked as a gap in `adr/README.md`.
- **Range-overlap test is not index-backed today.** The `NOT EXISTS` subquery uses
  `r.docking_bay_id` (indexed) and a time-range scan. Under very high contention per bay,
  consider a GiST index over `tstzrange(start_at, end_at)`. V4 and V6 add supporting
  indexes (ADR-0018); a future GiST index migration is possible without code changes.
- **`SKIP LOCKED` is Postgres-specific.** Not portable to MySQL < 8.0.1. ADR-0007 pins
  PostgreSQL, so this is not currently a concern.
- **Two-phase commit is not a real two-phase commit.** If TX2's `UPDATE` is interrupted
  after it logically committed, the outbox write (ADR-0010) might still succeed at next
  poll — the design tolerates this because consumers are idempotent (ADR-0016).

---

## Alternatives Considered

1. **`@Version` optimistic lock on `DockingBayEntity`.** Rejected — optimistic lock detects
   concurrent writes to the *bay* row, not conflicting reservations against the bay. The
   lock would almost never fire, because neither thread writes to `docking_bay` — they
   both only read it.
2. **`EXCLUDE USING gist` constraint** on `(docking_bay_id WITH =, tstzrange(start_at,
   end_at) WITH &&)`. Correct and declarative, but requires the `btree_gist` extension, a
   column-type change to `tstzrange`, and harder-to-read failure paths (constraint violation
   → 409 translation). Worth revisiting if the overlap query becomes a bottleneck.
3. **`SELECT ... FOR UPDATE` without `SKIP LOCKED`.** Under contention, waiting threads pile
   up on the same bay row; P99 explodes. `SKIP LOCKED` converts contention into "pick
   another bay".
4. **Distributed lock (Redis / ZooKeeper / `pg_advisory_lock`).** Extra moving parts for no
   gain — the DB lock we need already exists on `docking_bay`.
5. **Serializable isolation** (`SERIALIZABLE` TX isolation). Would catch the race, but
   forces transaction restarts under contention and globally slows every transaction in the
   service. Overkill for a local problem.

---

## References

- ADR-0007 — Database Choice (PostgreSQL enables `SKIP LOCKED`, `TIMESTAMPTZ`)
- ADR-0010 — Transactional Outbox (consumers must be idempotent on duplicates)
- ADR-0012 — Virtual Threads (parallel fee + route between TX1 and TX2)
- ADR-0013 — Open Session in View disabled (why TX1 ≠ TX2 is safe for pool sizing)
- ADR-0018 — Flyway (V4, V6 — supporting indexes)
- ADR-0024 — DTO / Domain / Entity mapping (state machine lives on `ReservationEntity`)
- PostgreSQL `SKIP LOCKED` —
  https://www.postgresql.org/docs/current/sql-select.html#SQL-FOR-UPDATE-SHARE
- "What's the deal with SELECT ... FOR UPDATE SKIP LOCKED?" — Louis Jenkins —
  https://blog.crunchydata.com/blog/skip-locked-row-level-locking-in-postgres
