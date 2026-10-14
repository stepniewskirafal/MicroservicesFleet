-- Performance indexes for concurrent reservation handling.
-- Without these, findFreeBay's NOT EXISTS subquery does a full table scan.

CREATE INDEX idx_reservation_bay_time
    ON reservation (docking_bay_id, start_at, end_at);

CREATE INDEX idx_docking_bay_starport_class
    ON docking_bay (starport_id, ship_class, status);
