-- ============================================================
-- V2: Test data — Star Wars themed fleet
--
-- 10 starports
-- 1000 docking bays (100 per starport: 30 CRUISER + 40 FREIGHTER + 30 SCOUT)
--  200 customers across 4 iconic factions
--  200 ships with 6 iconic Star Wars designs, ship_class aligned to design
--
-- Consolidated from the original V2 + V5 (V5 file removed).
-- Designed to run against a freshly dropped database.
-- ============================================================


-- =============================
-- Starports — 10 named locations
-- =============================
INSERT INTO starport (code, name, description) VALUES
  ('TATOO',  'Mos Eisley Spaceport',   'Outer Rim — Tatooine. Wretched hive of scum and villainy.'),
  ('CORUS',  'Coruscant Galactic Hub', 'Core Worlds — capital of Republic and Empire.'),
  ('ALDER',  'Alderaan Skydock',       'Core Worlds — home of House Organa.'),
  ('HOTH',   'Echo Base',              'Outer Rim — frozen Rebel hideout.'),
  ('ENDOR',  'Bright Tree Village',    'Forest Moon — home of the Ewoks.'),
  ('NABOO',  'Theed Royal Spaceport',  'Mid Rim — capital of Naboo.'),
  ('DAGO',   'Dagobah Hidden Pad',     'Outer Rim — swamp world; Yoda exile.'),
  ('BESPIN', 'Cloud City',             'Outer Rim — Tibanna gas mining colony.'),
  ('KAMINO', 'Tipoca City Spaceport',  'Wild Space — clone production world.'),
  ('MUSTAF', 'Mustafar Forge',         'Outer Rim — volcanic Imperial fortress.');


-- =============================
-- Docking bays — 100 per starport (30 CRUISER + 40 FREIGHTER + 30 SCOUT)
-- bay_label scoped per starport: C-001..C-030, F-001..F-040, S-001..S-030
-- =============================
INSERT INTO docking_bay (starport_id, bay_label, ship_class, status)
SELECT s.id, 'C-' || lpad(g::text, 3, '0'), 'CRUISER', 'AVAILABLE'
FROM   starport s
CROSS JOIN generate_series(1, 30) AS g;

INSERT INTO docking_bay (starport_id, bay_label, ship_class, status)
SELECT s.id, 'F-' || lpad(g::text, 3, '0'), 'FREIGHTER', 'AVAILABLE'
FROM   starport s
CROSS JOIN generate_series(1, 40) AS g;

INSERT INTO docking_bay (starport_id, bay_label, ship_class, status)
SELECT s.id, 'S-' || lpad(g::text, 3, '0'), 'SCOUT', 'AVAILABLE'
FROM   starport s
CROSS JOIN generate_series(1, 30) AS g;


-- =============================
-- Customers — 200 across 4 factions, 50 each
-- customer_code: CUST-001 .. CUST-200
-- =============================
INSERT INTO customer (customer_code, name)
SELECT 'CUST-' || lpad(g::text, 3, '0'),
       CASE
           WHEN g <=  50 THEN 'Rebel Alliance Convoy '     || lpad(g::text, 3, '0')
           WHEN g <= 100 THEN 'Galactic Empire Logistics ' || lpad((g -  50)::text, 3, '0')
           WHEN g <= 150 THEN 'Hutt Cartel Trader '        || lpad((g - 100)::text, 3, '0')
           ELSE               'Mandalorian Clan '          || lpad((g - 150)::text, 3, '0')
       END
FROM generate_series(1, 200) AS g;


-- =============================
-- Ships — 200, one per customer (1:1).
-- 6 iconic Star Wars designs cycled; ship_class matches the design.
--   X-WING            → SCOUT
--   FALCON            → FREIGHTER
--   STAR-DESTROYER    → CRUISER
--   TIE-FIGHTER       → SCOUT
--   SLAVE-I           → FREIGHTER
--   VENATOR           → CRUISER
-- Distribution across 200 ships: ~67 SCOUT, ~67 FREIGHTER, ~66 CRUISER.
-- =============================
INSERT INTO ship (customer_id, ship_code, ship_class)
SELECT c.id,
       CASE (g - 1) % 6
           WHEN 0 THEN 'X-WING-'         || lpad(g::text, 3, '0')
           WHEN 1 THEN 'FALCON-'         || lpad(g::text, 3, '0')
           WHEN 2 THEN 'STAR-DESTROYER-' || lpad(g::text, 3, '0')
           WHEN 3 THEN 'TIE-FIGHTER-'    || lpad(g::text, 3, '0')
           WHEN 4 THEN 'SLAVE-I-'        || lpad(g::text, 3, '0')
           WHEN 5 THEN 'VENATOR-'        || lpad(g::text, 3, '0')
       END,
       CASE (g - 1) % 6
           WHEN 0 THEN 'SCOUT'
           WHEN 1 THEN 'FREIGHTER'
           WHEN 2 THEN 'CRUISER'
           WHEN 3 THEN 'SCOUT'
           WHEN 4 THEN 'FREIGHTER'
           WHEN 5 THEN 'CRUISER'
       END
FROM generate_series(1, 200) AS g
JOIN customer c ON c.customer_code = 'CUST-' || lpad(g::text, 3, '0');
