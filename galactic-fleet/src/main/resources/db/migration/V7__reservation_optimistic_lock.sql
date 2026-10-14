-- ============================================================
-- V7: Optimistic locking for the reservation aggregate (@Version).
--
-- Guards against lost updates when the confirm path and the compensation / HOLD-reaper path touch
-- the same reservation concurrently: a conflicting commit fails fast instead of silently
-- overwriting. Existing rows (none at migration time — reservations are created at runtime) default
-- to 0; Hibernate writes 0 on insert and increments the value on every managed update.
-- ============================================================

ALTER TABLE reservation ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
