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
single query that picks the bay (`DockingBayRepository.findFreeBay`), using `SKIP LOCKED`
so concurrent searchers see locked rows as "unavailable" rather than blocking.

```sql
-- DockingBayRepository.findFreeBay (native, joins starport.code → docking_bay)
SELECT docking_bay.* FROM starport
JOIN docking_bay ON starport.id = docking_bay.starport_id
WHERE starport.code = :starportCode
  AND docking_bay.status = 'AVAILABLE'
  AND docking_bay.ship_class = :shipClass
  AND NOT EXISTS (
      SELECT 1 FROM reservation r
      WHERE docking_bay.id = r.docking_bay_id
        AND r.status <> 'CANCELLED'              -- reaped/compensated HOLDs free the bay
        AND r.start_at < :endAt AND r.end_at > :startAt
  )
FOR UPDATE SKIP LOCKED
LIMIT 1
```

The "is free?" check and the lock acquisition happen in a single atomic statement. The
lock is held only for the duration of TX1 (`createHoldReservation`). Slow work (fee
calculation, route HTTP call) runs **between** TX1 and TX2 with no DB connection held —
this is what lets 30 HikariCP connections serve hundreds of concurrent requests
(ADR-0013).

The state machine is `HOLD → CONFIRMED → CANCELLED`; **all three states are live**.
`CANCELLED` is set when a HOLD is released, and the `status <> 'CANCELLED'` predicate
above is what makes a released bay immediately reusable.

A **second, optimistic lock** guards the HOLD→CONFIRMED transition itself:
`ReservationEntity.version` (`@Version`, added by Flyway **V7**). The confirm path and
the compensation/reaper path can touch the same row concurrently; a conflicting commit
fails fast instead of one silently overwriting the other.

When all matching bays are locked, the query returns 0 rows →
`NoDockingBaysAvailableException` → HTTP 409 (ADR-0015), no waiting.

**Orphaned HOLDs are reclaimed two ways:**

1. **Inline compensation** — `ReservationService.reserveBay` wraps confirm in a
   `try/finally`; if confirm fails (route unavailable, breaker open, fee error) the
   `finally` calls `cancelHold`, flipping the HOLD to `CANCELLED` in the same request.
2. **`HoldReaper`** — a `@Scheduled(fixedDelay…)` backstop (default 60 s, TTL 120 s) for
   the crash case where the process dies before the `finally` runs; it bulk-cancels
   HOLDs older than the TTL and increments `reservations.hold.reaped`.

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

- **No idempotency key** on `POST /reservations` — a client retry after timeout can
  create a duplicate. Tracked in `adr/README.md`.
- Overlap subquery is not GiST-indexed today; the V4 `(docking_bay_id, start_at, end_at)`
  index covers the common cases.

---

## Alternatives

- **`@Version` optimistic lock on `DockingBayEntity`** — never fires for the *allocation*
  race; that conflict is on the `reservation` row, not the bay (handled by the pessimistic
  lock above). `@Version` *is* used on `ReservationEntity` for the confirm/compensate race.
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
- ADR-0010 — Resilience Patterns (transactional outbox; CONFIRMED event publish)
- ADR-0012 — Virtual Threads
- ADR-0013 — OSIV disabled (why TX1 ≠ TX2)
- ADR-0018 — Flyway (V7 adds the `version` column)
- ADR-0030 — Metrics Naming (`reservations.hold.reaped`)
- PostgreSQL `SKIP LOCKED` —
  https://www.postgresql.org/docs/current/sql-select.html#SQL-FOR-UPDATE-SHARE
