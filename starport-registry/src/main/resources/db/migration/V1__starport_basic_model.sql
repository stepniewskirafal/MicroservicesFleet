
CREATE SEQUENCE IF NOT EXISTS starport_id_seq      START WITH 1 INCREMENT BY 10;
CREATE SEQUENCE IF NOT EXISTS docking_bay_id_seq   START WITH 1 INCREMENT BY 10;  -- fixed name
CREATE SEQUENCE IF NOT EXISTS customer_id_seq      START WITH 1 INCREMENT BY 10;
CREATE SEQUENCE IF NOT EXISTS ship_id_seq          START WITH 1 INCREMENT BY 10;
CREATE SEQUENCE IF NOT EXISTS reservation_id_seq   START WITH 1 INCREMENT BY 10;
CREATE SEQUENCE IF NOT EXISTS route_id_seq         START WITH 1 INCREMENT BY 10;

-- =============================
-- Tables
-- =============================

-- Starport: basic registry of bases
CREATE TABLE IF NOT EXISTS starport (
                                        id           BIGINT PRIMARY KEY DEFAULT nextval('starport_id_seq'),
                                        code         TEXT,            -- short code for APIs (e.g., "ALPHA")
                                        name         TEXT,            -- display name
                                        description  TEXT,
                                        created_at   TIMESTAMPTZ DEFAULT now(),
                                        updated_at   TIMESTAMPTZ
);

-- Docking bay: parking spots within a starport
CREATE TABLE IF NOT EXISTS docking_bay (
                                           id           BIGINT PRIMARY KEY DEFAULT nextval('docking_bay_id_seq'),
                                           starport_id  BIGINT,          -- references starport.id (no FK by design)
                                           bay_label    TEXT,            -- human label, e.g., "A-12"
                                           ship_class   TEXT,            -- SCOUT | FREIGHTER | CRUISER (not enforced)
                                           status       TEXT,            -- ACTIVE | OUT_OF_SERVICE (not enforced)
                                           created_at   TIMESTAMPTZ DEFAULT now(),
                                           updated_at   TIMESTAMPTZ
);

-- Customer: who owns ships
CREATE TABLE IF NOT EXISTS customer (
                                        id            BIGINT PRIMARY KEY DEFAULT nextval('customer_id_seq'),
                                        customer_code TEXT,           -- optional external/customer-facing ID
                                        name          TEXT,
                                        created_at    TIMESTAMPTZ DEFAULT now(),
                                        updated_at    TIMESTAMPTZ
);

-- Ship: fleet entries per customer
CREATE TABLE IF NOT EXISTS ship (
                                    id           BIGINT PRIMARY KEY DEFAULT nextval('ship_id_seq'),
                                    customer_id  BIGINT,          -- references customer.id (no FK)
                                    ship_code    TEXT,            -- business identifier, e.g., "SS-Enterprise-01"
                                    ship_class   TEXT,            -- SCOUT | FREIGHTER | CRUISER (not enforced)
                                    created_at   TIMESTAMPTZ DEFAULT now(),
                                    updated_at   TIMESTAMPTZ
);

-- Reservation: booking a bay for a ship & customer
CREATE TABLE IF NOT EXISTS reservation (
                                           id              BIGINT PRIMARY KEY DEFAULT nextval('reservation_id_seq'),
                                           starport_id     BIGINT,          -- references starport.id (no FK)
                                           docking_bay_id  BIGINT,          -- references docking_bay.id (no FK)
                                           customer_id     BIGINT,          -- redundancy for fast lookups (no FK)
                                           ship_id         BIGINT,          -- references ship.id (no FK)

                                           start_at        TIMESTAMPTZ,
                                           end_at          TIMESTAMPTZ,
                                           fee_charged     NUMERIC(14,2),
                                           status          TEXT,

                                           created_at      TIMESTAMPTZ DEFAULT now(),
                                           updated_at      TIMESTAMPTZ
);

-- Route: active computed route assigned to a reservation
CREATE TABLE IF NOT EXISTS route (
                                     id                      BIGINT PRIMARY KEY DEFAULT nextval('route_id_seq'),
                                     reservation_id          BIGINT,          -- references reservation.id (no FK)
                                     route_code              TEXT,            -- business identifier, e.g., "ROUTE-0001"
                                     start_port_code         TEXT,            -- e.g., "ALPHA" (not enforced)
                                     destination_port_code   TEXT,            -- e.g., "BETA" (not enforced)
                                     eta_light_years         DOUBLE PRECISION,   -- Estimated travel distance/time in LY
                                     risk_score              DOUBLE PRECISION,
                                     created_at              TIMESTAMPTZ DEFAULT now(),
                                     updated_at              TIMESTAMPTZ
);
