-- =============================
-- Starports
-- =============================
INSERT INTO starport (code, name) VALUES ('ABC', 'Alpha Base Central');
INSERT INTO starport (code, name) VALUES ('ALPHA-BASE', 'Alpha Base');
INSERT INTO starport (code, name) VALUES ('BETA-PORT', 'Beta Trading Post');

-- =============================
-- Docking bays  (starport_id 1 = ABC, 2 = ALPHA-BASE, 3 = BETA-PORT)
-- =============================
-- ABC: 6 SCOUT + 4 FREIGHTER + 2 CRUISER = 12 bays
INSERT INTO docking_bay (starport_id, bay_label, ship_class, status) VALUES (1, 'A-01', 'SCOUT',     'AVAILABLE');
INSERT INTO docking_bay (starport_id, bay_label, ship_class, status) VALUES (1, 'A-02', 'SCOUT',     'AVAILABLE');
INSERT INTO docking_bay (starport_id, bay_label, ship_class, status) VALUES (1, 'A-03', 'SCOUT',     'AVAILABLE');
INSERT INTO docking_bay (starport_id, bay_label, ship_class, status) VALUES (1, 'A-04', 'SCOUT',     'AVAILABLE');
INSERT INTO docking_bay (starport_id, bay_label, ship_class, status) VALUES (1, 'A-05', 'SCOUT',     'AVAILABLE');
INSERT INTO docking_bay (starport_id, bay_label, ship_class, status) VALUES (1, 'A-06', 'SCOUT',     'AVAILABLE');
INSERT INTO docking_bay (starport_id, bay_label, ship_class, status) VALUES (1, 'B-01', 'FREIGHTER', 'AVAILABLE');
INSERT INTO docking_bay (starport_id, bay_label, ship_class, status) VALUES (1, 'B-02', 'FREIGHTER', 'AVAILABLE');
INSERT INTO docking_bay (starport_id, bay_label, ship_class, status) VALUES (1, 'B-03', 'FREIGHTER', 'AVAILABLE');
INSERT INTO docking_bay (starport_id, bay_label, ship_class, status) VALUES (1, 'B-04', 'FREIGHTER', 'AVAILABLE');
INSERT INTO docking_bay (starport_id, bay_label, ship_class, status) VALUES (1, 'C-01', 'CRUISER',   'AVAILABLE');
INSERT INTO docking_bay (starport_id, bay_label, ship_class, status) VALUES (1, 'C-02', 'CRUISER',   'AVAILABLE');

-- ALPHA-BASE: 3 SCOUT + 2 FREIGHTER = 5 bays
INSERT INTO docking_bay (starport_id, bay_label, ship_class, status) VALUES (2, 'D-01', 'SCOUT',     'AVAILABLE');
INSERT INTO docking_bay (starport_id, bay_label, ship_class, status) VALUES (2, 'D-02', 'SCOUT',     'AVAILABLE');
INSERT INTO docking_bay (starport_id, bay_label, ship_class, status) VALUES (2, 'D-03', 'SCOUT',     'AVAILABLE');
INSERT INTO docking_bay (starport_id, bay_label, ship_class, status) VALUES (2, 'E-01', 'FREIGHTER', 'AVAILABLE');
INSERT INTO docking_bay (starport_id, bay_label, ship_class, status) VALUES (2, 'E-02', 'FREIGHTER', 'AVAILABLE');

-- BETA-PORT: 2 SCOUT + 1 CRUISER = 3 bays
INSERT INTO docking_bay (starport_id, bay_label, ship_class, status) VALUES (3, 'F-01', 'SCOUT',     'AVAILABLE');
INSERT INTO docking_bay (starport_id, bay_label, ship_class, status) VALUES (3, 'F-02', 'SCOUT',     'AVAILABLE');
INSERT INTO docking_bay (starport_id, bay_label, ship_class, status) VALUES (3, 'G-01', 'CRUISER',   'AVAILABLE');

-- =============================
-- Customers
-- =============================
INSERT INTO customer (customer_code, name) VALUES ('CUST-001', 'Weyland-Yutani');
INSERT INTO customer (customer_code, name) VALUES ('CUST-002', 'Tyrell Corporation');
INSERT INTO customer (customer_code, name) VALUES ('CUST-003', 'Umbrella Corp');

-- =============================
-- Ships
-- =============================
-- Weyland-Yutani fleet
INSERT INTO ship (customer_id, ship_code, ship_class) VALUES (1, 'SS-Enterprise-01', 'SCOUT');
INSERT INTO ship (customer_id, ship_code, ship_class) VALUES (1, 'SS-Falcon-02',     'FREIGHTER');
INSERT INTO ship (customer_id, ship_code, ship_class) VALUES (1, 'SS-Destroyer-03',  'CRUISER');

-- Tyrell Corporation fleet
INSERT INTO ship (customer_id, ship_code, ship_class) VALUES (11, 'TR-Nexus-01',      'SCOUT');
INSERT INTO ship (customer_id, ship_code, ship_class) VALUES (11, 'TR-Hauler-02',     'FREIGHTER');

-- Umbrella Corp fleet
INSERT INTO ship (customer_id, ship_code, ship_class) VALUES (21, 'UC-Raven-01',      'SCOUT');
INSERT INTO ship (customer_id, ship_code, ship_class) VALUES (21, 'UC-Carrier-02',    'CRUISER');