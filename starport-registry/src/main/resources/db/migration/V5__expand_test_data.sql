-- ============================================================
-- V5: Expand test data — add starports, bays, customers, ships
-- that were missing from the original V2 migration.
-- Uses NOT EXISTS guards so this migration is safe to run
-- regardless of database state.
-- ============================================================

-- =============================
-- Starports
-- =============================
INSERT INTO starport (code, name)
SELECT 'BETA-PORT', 'Beta Trading Post'
WHERE NOT EXISTS (SELECT 1 FROM starport WHERE code = 'BETA-PORT');

-- =============================
-- Customers
-- =============================
INSERT INTO customer (customer_code, name)
SELECT 'CUST-002', 'Tyrell Corporation'
WHERE NOT EXISTS (SELECT 1 FROM customer WHERE customer_code = 'CUST-002');

INSERT INTO customer (customer_code, name)
SELECT 'CUST-003', 'Umbrella Corp'
WHERE NOT EXISTS (SELECT 1 FROM customer WHERE customer_code = 'CUST-003');

-- =============================
-- Docking bays for ABC (starport_id from subquery)
-- Original V2 only had 1 SCOUT bay (A-01)
-- =============================
INSERT INTO docking_bay (starport_id, bay_label, ship_class, status)
SELECT s.id, 'A-02', 'SCOUT', 'AVAILABLE'
FROM starport s WHERE s.code = 'ABC'
  AND NOT EXISTS (SELECT 1 FROM docking_bay WHERE bay_label = 'A-02' AND starport_id = s.id);

INSERT INTO docking_bay (starport_id, bay_label, ship_class, status)
SELECT s.id, 'A-03', 'SCOUT', 'AVAILABLE'
FROM starport s WHERE s.code = 'ABC'
  AND NOT EXISTS (SELECT 1 FROM docking_bay WHERE bay_label = 'A-03' AND starport_id = s.id);

INSERT INTO docking_bay (starport_id, bay_label, ship_class, status)
SELECT s.id, 'A-04', 'SCOUT', 'AVAILABLE'
FROM starport s WHERE s.code = 'ABC'
  AND NOT EXISTS (SELECT 1 FROM docking_bay WHERE bay_label = 'A-04' AND starport_id = s.id);

INSERT INTO docking_bay (starport_id, bay_label, ship_class, status)
SELECT s.id, 'A-05', 'SCOUT', 'AVAILABLE'
FROM starport s WHERE s.code = 'ABC'
  AND NOT EXISTS (SELECT 1 FROM docking_bay WHERE bay_label = 'A-05' AND starport_id = s.id);

INSERT INTO docking_bay (starport_id, bay_label, ship_class, status)
SELECT s.id, 'A-06', 'SCOUT', 'AVAILABLE'
FROM starport s WHERE s.code = 'ABC'
  AND NOT EXISTS (SELECT 1 FROM docking_bay WHERE bay_label = 'A-06' AND starport_id = s.id);

INSERT INTO docking_bay (starport_id, bay_label, ship_class, status)
SELECT s.id, 'B-01', 'FREIGHTER', 'AVAILABLE'
FROM starport s WHERE s.code = 'ABC'
  AND NOT EXISTS (SELECT 1 FROM docking_bay WHERE bay_label = 'B-01' AND starport_id = s.id);

INSERT INTO docking_bay (starport_id, bay_label, ship_class, status)
SELECT s.id, 'B-02', 'FREIGHTER', 'AVAILABLE'
FROM starport s WHERE s.code = 'ABC'
  AND NOT EXISTS (SELECT 1 FROM docking_bay WHERE bay_label = 'B-02' AND starport_id = s.id);

INSERT INTO docking_bay (starport_id, bay_label, ship_class, status)
SELECT s.id, 'B-03', 'FREIGHTER', 'AVAILABLE'
FROM starport s WHERE s.code = 'ABC'
  AND NOT EXISTS (SELECT 1 FROM docking_bay WHERE bay_label = 'B-03' AND starport_id = s.id);

INSERT INTO docking_bay (starport_id, bay_label, ship_class, status)
SELECT s.id, 'B-04', 'FREIGHTER', 'AVAILABLE'
FROM starport s WHERE s.code = 'ABC'
  AND NOT EXISTS (SELECT 1 FROM docking_bay WHERE bay_label = 'B-04' AND starport_id = s.id);

INSERT INTO docking_bay (starport_id, bay_label, ship_class, status)
SELECT s.id, 'C-01', 'CRUISER', 'AVAILABLE'
FROM starport s WHERE s.code = 'ABC'
  AND NOT EXISTS (SELECT 1 FROM docking_bay WHERE bay_label = 'C-01' AND starport_id = s.id);

INSERT INTO docking_bay (starport_id, bay_label, ship_class, status)
SELECT s.id, 'C-02', 'CRUISER', 'AVAILABLE'
FROM starport s WHERE s.code = 'ABC'
  AND NOT EXISTS (SELECT 1 FROM docking_bay WHERE bay_label = 'C-02' AND starport_id = s.id);

-- =============================
-- Docking bays for ALPHA-BASE
-- =============================
INSERT INTO docking_bay (starport_id, bay_label, ship_class, status)
SELECT s.id, 'D-01', 'SCOUT', 'AVAILABLE'
FROM starport s WHERE s.code = 'ALPHA-BASE'
  AND NOT EXISTS (SELECT 1 FROM docking_bay WHERE bay_label = 'D-01' AND starport_id = s.id);

INSERT INTO docking_bay (starport_id, bay_label, ship_class, status)
SELECT s.id, 'D-02', 'SCOUT', 'AVAILABLE'
FROM starport s WHERE s.code = 'ALPHA-BASE'
  AND NOT EXISTS (SELECT 1 FROM docking_bay WHERE bay_label = 'D-02' AND starport_id = s.id);

INSERT INTO docking_bay (starport_id, bay_label, ship_class, status)
SELECT s.id, 'D-03', 'SCOUT', 'AVAILABLE'
FROM starport s WHERE s.code = 'ALPHA-BASE'
  AND NOT EXISTS (SELECT 1 FROM docking_bay WHERE bay_label = 'D-03' AND starport_id = s.id);

INSERT INTO docking_bay (starport_id, bay_label, ship_class, status)
SELECT s.id, 'E-01', 'FREIGHTER', 'AVAILABLE'
FROM starport s WHERE s.code = 'ALPHA-BASE'
  AND NOT EXISTS (SELECT 1 FROM docking_bay WHERE bay_label = 'E-01' AND starport_id = s.id);

INSERT INTO docking_bay (starport_id, bay_label, ship_class, status)
SELECT s.id, 'E-02', 'FREIGHTER', 'AVAILABLE'
FROM starport s WHERE s.code = 'ALPHA-BASE'
  AND NOT EXISTS (SELECT 1 FROM docking_bay WHERE bay_label = 'E-02' AND starport_id = s.id);

-- =============================
-- Docking bays for BETA-PORT
-- =============================
INSERT INTO docking_bay (starport_id, bay_label, ship_class, status)
SELECT s.id, 'F-01', 'SCOUT', 'AVAILABLE'
FROM starport s WHERE s.code = 'BETA-PORT'
  AND NOT EXISTS (SELECT 1 FROM docking_bay WHERE bay_label = 'F-01' AND starport_id = s.id);

INSERT INTO docking_bay (starport_id, bay_label, ship_class, status)
SELECT s.id, 'F-02', 'SCOUT', 'AVAILABLE'
FROM starport s WHERE s.code = 'BETA-PORT'
  AND NOT EXISTS (SELECT 1 FROM docking_bay WHERE bay_label = 'F-02' AND starport_id = s.id);

INSERT INTO docking_bay (starport_id, bay_label, ship_class, status)
SELECT s.id, 'G-01', 'CRUISER', 'AVAILABLE'
FROM starport s WHERE s.code = 'BETA-PORT'
  AND NOT EXISTS (SELECT 1 FROM docking_bay WHERE bay_label = 'G-01' AND starport_id = s.id);

-- =============================
-- Ships (lookup customer by customer_code, not hardcoded ID)
-- =============================
-- Weyland-Yutani (CUST-001) fleet
INSERT INTO ship (customer_id, ship_code, ship_class)
SELECT c.id, 'SS-Falcon-02', 'FREIGHTER'
FROM customer c WHERE c.customer_code = 'CUST-001'
  AND NOT EXISTS (SELECT 1 FROM ship WHERE ship_code = 'SS-Falcon-02');

INSERT INTO ship (customer_id, ship_code, ship_class)
SELECT c.id, 'SS-Destroyer-03', 'CRUISER'
FROM customer c WHERE c.customer_code = 'CUST-001'
  AND NOT EXISTS (SELECT 1 FROM ship WHERE ship_code = 'SS-Destroyer-03');

-- Tyrell Corporation (CUST-002) fleet
INSERT INTO ship (customer_id, ship_code, ship_class)
SELECT c.id, 'TR-Nexus-01', 'SCOUT'
FROM customer c WHERE c.customer_code = 'CUST-002'
  AND NOT EXISTS (SELECT 1 FROM ship WHERE ship_code = 'TR-Nexus-01');

INSERT INTO ship (customer_id, ship_code, ship_class)
SELECT c.id, 'TR-Hauler-02', 'FREIGHTER'
FROM customer c WHERE c.customer_code = 'CUST-002'
  AND NOT EXISTS (SELECT 1 FROM ship WHERE ship_code = 'TR-Hauler-02');

-- Umbrella Corp (CUST-003) fleet
INSERT INTO ship (customer_id, ship_code, ship_class)
SELECT c.id, 'UC-Raven-01', 'SCOUT'
FROM customer c WHERE c.customer_code = 'CUST-003'
  AND NOT EXISTS (SELECT 1 FROM ship WHERE ship_code = 'UC-Raven-01');

INSERT INTO ship (customer_id, ship_code, ship_class)
SELECT c.id, 'UC-Carrier-02', 'CRUISER'
FROM customer c WHERE c.customer_code = 'CUST-003'
  AND NOT EXISTS (SELECT 1 FROM ship WHERE ship_code = 'UC-Carrier-02');
