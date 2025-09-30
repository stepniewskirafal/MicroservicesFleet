-- === STARPORT ===
CREATE TABLE IF NOT EXISTS starport (
                                        id    uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
                                        name  varchar(64) NOT NULL UNIQUE,
                                        code  varchar(64) NOT NULL UNIQUE
);

-- === DOCKING_BAY ===
CREATE TABLE IF NOT EXISTS docking_bay (
                                           id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
                                           starport_id uuid        NOT NULL REFERENCES starport(id),
                                           ship_class  varchar(32) NOT NULL,
                                           status      varchar(16) NOT NULL DEFAULT 'ACTIVE',
                                           CONSTRAINT chk_docking_bay_status CHECK (status IN ('ACTIVE','OUT_OF_SERVICE'))
);

-- RESERVATION
CREATE TABLE IF NOT EXISTS reservation (
                                           id             uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
                                           docking_bay_id uuid         NOT NULL REFERENCES docking_bay(id) ON DELETE CASCADE,
                                           ship_id        varchar(128) NOT NULL,
                                           ship_class     varchar(32)  NOT NULL,
                                           start_at       timestamptz  NOT NULL,
                                           end_at         timestamptz  NOT NULL,
                                           status         varchar(32)  NOT NULL DEFAULT 'CONFIRMED',
                                           fee_amount     numeric(19,4),
                                           created_at     timestamptz  NOT NULL DEFAULT now(),
                                           updated_at     timestamptz  NOT NULL DEFAULT now(),
                                           CONSTRAINT chk_time_window CHECK (start_at < end_at),
                                           CONSTRAINT chk_fee_positive CHECK (fee_amount IS NULL OR fee_amount >= 0),
                                           CONSTRAINT chk_res_status CHECK (status IN ('HOLD','CONFIRMED','CANCELLED','EXPIRED'))
);

-- === ROUTE ===
CREATE TABLE IF NOT EXISTS route (
                                     id              uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
                                     reservation_id  uuid          NOT NULL,
                                     eta_ly          float         NOT NULL,
                                     risk_score      float         NOT NULL,
                                     is_active       boolean       NOT NULL DEFAULT TRUE,
                                     created_at      timestamptz   NOT NULL DEFAULT now(),
                                     updated_at      timestamptz   NOT NULL DEFAULT now()
);
