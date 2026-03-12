-- Indexes on lookup columns used in reservation creation flow.
-- Without these, findByCode/findByCustomerCode/findByShipCode trigger full table scans.

CREATE INDEX idx_starport_code ON starport (code);
CREATE INDEX idx_customer_code ON customer (customer_code);
CREATE INDEX idx_ship_code ON ship (ship_code);
