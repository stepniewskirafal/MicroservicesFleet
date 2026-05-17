# 0020 — Concurrent Reservation Safety (Pessimistic Lock + SKIP LOCKED)

**Status:** Accepted
**Date:** 2026-04-17

---

## Context

Two requests racing for the same `docking_bay` over an overlapping window must not both
succeed. The service runs ≥2 replicas (ADR-0008) on virtual threads (ADR-0012) with a
small HikariCP pool (ADR-0013), so single-JVM strategies (`synchronized`, in-memory
sets) are wrong by construction. Optimistic `@Version` on the bay row would not fire —
both writers only read it.

---

## Decision

Prevent double-booking with a **PostgreSQL row-level pessimistic lock** acquired by the
single query that picks the bay, using `SKIP LOCKED` so concurrent searchers see locked
rows as "unavailable" rather than blocking.

```sql
SELECT db.* FROM docking_bay db
WHERE db.starport_id = :starportId AND db.class = :shipClass
  AND NOT EXISTS (
      SELECT 1 FROM reservation r
      WHERE db.id = r.docking_bay_id
        AND r.start_at < :endAt AND r.end_at > :startAt
  )
LIMIT 1
FOR UPDATE SKIP LOCKED
```

The "is free?" check and the lock acquisition happen in a single atomic statement. The
lock is held only for the duration of TX1 (`createHoldReservation`). Slow work (fee
calculation, route HTTP call) runs **between** TX1 and TX2 with no DB connection held —
this is what lets 30 HikariCP connections serve hundreds of concurrent requests
(ADR-0013). The state machine is `HOLD → CONFIRMED`; `CANCELLED` exists but is unused.

When all matching bays are locked, the query returns 0 rows →
`NoDockingBaysAvailableException` → HTTP 409 (ADR-0015), no waiting.

---

## Why

- **Race-free by construction** — overlap check and lock are one statement.
- **Cross-replica correct** — the lock is in PostgreSQL, not in any JVM.
- **No blocking** — `SKIP LOCKED` turns contention into "pick the next bay" instead of
  a queue on one row.
- **Pool-friendly** — lock held only during TX1's fast `SELECT + INSERT`.
- **Deterministic failure** — no free bay → immediate 409, no 30 s lock-wait timeout.

---

## Known gaps

- **HOLD rows can leak** if TX1 commits but TX2 never runs (crash, fee error, breaker
  open). No expiry scheduler today — tracked as TODO.
- **No idempotency key** on `POST /reservations` — a client retry after timeout can
  create a duplicate. Tracked in `adr/README.md`.
- Overlap subquery is not GiST-indexed today; V4/V6 cover the common cases.

---

## Alternatives

- **`@Version` optimistic lock on `DockingBayEntity`** — never fires; conflict is on
  the `reservation` row, not the bay.
- **`EXCLUDE USING gist (docking_bay_id WITH =, tstzrange(...) WITH &&)`** — correct
  and declarative; requires `btree_gist` extension and a column-type change. Revisit if
  overlap becomes a bottleneck.
- **`FOR UPDATE` without `SKIP LOCKED`** — waiters pile up on the same row; P99
  explodes.
- **Distributed lock (Redis / `pg_advisory_lock`)** — extra moving parts for no gain.
- **`SERIALIZABLE` isolation** — globally slows every transaction to fix one local
  race.

---

## References

- ADR-0007 — PostgreSQL (enables `SKIP LOCKED`)
- ADR-0010 — Outbox (consumers must be idempotent)
- ADR-0012 — Virtual Threads
- ADR-0013 — OSIV disabled (why TX1 ≠ TX2)
- PostgreSQL `SKIP LOCKED` —
  https://www.postgresql.org/docs/current/sql-select.html#SQL-FOR-UPDATE-SHARE
