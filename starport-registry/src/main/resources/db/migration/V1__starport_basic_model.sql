-- Flyway migration: V1__starport_basic_model.sql
-- Purpose: Minimal KISS schema for a starport parking reservations app.
-- Notes:
--  - Primary keys use explicit sequences + nextval defaults.
--  - Per request: NO foreign keys, NO unique checks, NO NOT NULL, NO indexes.
--  - Text enums (ship_class, status) are plain TEXT for easy early evolution.
--  - Timestamps use timestamptz; amounts use NUMERIC(14,2).

-- =============================
-- Sequences
-- =============================
CREATE SEQUENCE IF NOT EXISTS starport_seq START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS docking_bay_seq START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS customer_seq START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS ship_seq START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS reservation_seq START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS route_seq START WITH 1 INCREMENT BY 1;

-- =============================
-- Tables
-- =============================

-- Starport: basic registry of bases
CREATE TABLE IF NOT EXISTS starport (
                                        id            BIGINT PRIMARY KEY DEFAULT nextval('starport_seq'),
                                        code          TEXT,              -- short code used by APIs (e.g., "ABC")
                                        name          TEXT,              -- display name
                                        description   TEXT,
                                        created_at    TIMESTAMPTZ DEFAULT now(),
                                        updated_at    TIMESTAMPTZ
);

-- Docking bay: parking spots within a starport
CREATE TABLE IF NOT EXISTS docking_bay (
                                           id            BIGINT PRIMARY KEY DEFAULT nextval('docking_bay_seq'),
                                           starport_id   BIGINT,            -- references starport.id (no FK by design)
                                           bay_label     TEXT,              -- human label, e.g., "A-12"
                                           ship_class    TEXT,              -- SCOUT | FREIGHTER | CRUISER (not enforced)
                                           status        TEXT,              -- ACTIVE | OUT_OF_SERVICE (not enforced)
                                           created_at    TIMESTAMPTZ DEFAULT now(),
                                           updated_at    TIMESTAMPTZ
);

-- Customer: who owns ships
CREATE TABLE IF NOT EXISTS customer (
                                        id            BIGINT PRIMARY KEY DEFAULT nextval('customer_seq'),
                                        client_code   TEXT,              -- external/customer-facing ID if needed
                                        name          TEXT,
                                        created_at    TIMESTAMPTZ DEFAULT now(),
                                        updated_at    TIMESTAMPTZ
);

-- Ship: fleet entries per customer
CREATE TABLE IF NOT EXISTS ship (
                                    id            BIGINT PRIMARY KEY DEFAULT nextval('ship_seq'),
                                    customer_id   BIGINT,            -- references customer.id (no FK)
                                    ship_id       TEXT,              -- business identifier, e.g., "SS-Enterprise-01"
                                    ship_name     TEXT,              -- display name if different
                                    ship_class    TEXT,              -- SCOUT | FREIGHTER | CRUISER (not enforced)
                                    created_at    TIMESTAMPTZ DEFAULT now(),
                                    updated_at    TIMESTAMPTZ
);

-- Reservation: booking a bay for a ship & customer
CREATE TABLE IF NOT EXISTS reservation (
                                           id               BIGINT PRIMARY KEY DEFAULT nextval('reservation_seq'),
                                           docking_bay_id   BIGINT,            -- references docking_bay.id (no FK)
                                           customer_id      BIGINT,            -- redundancy for fast lookups (no FK)
                                           ship_id          TEXT,              -- references ship.id (no FK)
                                           ship_class       TEXT,              -- SCOUT | FREIGHTER | CRUISER (not enforced)

                                           start_at         TIMESTAMPTZ,
                                           end_at           TIMESTAMPTZ,
                                           fee_charged      NUMERIC(14,2),
                                           status           TEXT,

                                           created_at       TIMESTAMPTZ DEFAULT now(),
                                           updated_at       TIMESTAMPTZ
);

-- Route: active computed route assigned to a reservation
CREATE TABLE IF NOT EXISTS route (
                                     id                 BIGINT PRIMARY KEY DEFAULT nextval('route_seq'),
                                     reservation_id     BIGINT,            -- references reservation.id (no FK)
                                     destination_code   TEXT,              -- e.g., "ALPHA-BASE" (not enforced)
                                     eta_ly             NUMERIC(10,2),     -- Estimated travel distance/time in LY
                                     risk_score         NUMERIC(5,2),
                                     created_at         TIMESTAMPTZ DEFAULT now(),
                                     updated_at         TIMESTAMPTZ
);


-- INSERT INTO starport (code, name) VALUES ('ABC', 'Alpha Base Central');
-- INSERT INTO docking_bay (starport_id, bay_label, ship_class, status) VALUES (1, 'A-01', 'SCOUT', 'AVAILABLE');
-- INSERT INTO customer (client_code, name) VALUES ('CUST-001', 'Weyland-Yutani');
-- INSERT INTO ship (customer_id, ship_id, ship_name, ship_class) VALUES (1, 'SS-Enterprise-01', 'Enterprise', 'SCOUT');
-- INSERT INTO reservation (docking_bay_id, customer_id, ship_id, ship_class, start_at, end_at, feeCharged)
--   VALUES (1, 1, 1, 'SS-Enterprise-01', 'SCOUT', '2025-12-05T02:00:00Z', '2025-12-05T03:00:00Z', 120.50);
-- INSERT INTO route (reservation_id, destination_code, eta_ly, risk_score)
--   VALUES (1, 'ALPHA-BASE', 18.70, 0.40);
