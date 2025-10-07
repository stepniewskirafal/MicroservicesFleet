BEGIN;

-- === STARPORTS (UPSERT po code) ===
INSERT INTO starport (id, name, code)
VALUES
    (gen_random_uuid(), 'Alpha Base City', 'ABC'),
    (gen_random_uuid(), 'Xylith Prime',    'XYZ')
ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name;

WITH
-- Id portów po UPSERcie
sp_abc AS (SELECT id FROM starport WHERE code = 'ABC'),
sp_xyz AS (SELECT id FROM starport WHERE code = 'XYZ'),

-- === DOCKING_BAYS: wstawiamy i od razu zbieramy ich ID ===
ins_bays AS (
    INSERT INTO docking_bay (starport_id, ship_class, status)
        -- ABC: 2x SCOUT, 2x FREIGHTER, 1x CRUISER
        SELECT id, 'SCOUT',     'ACTIVE' FROM sp_abc UNION ALL
        SELECT id, 'SCOUT',     'ACTIVE' FROM sp_abc UNION ALL
        SELECT id, 'FREIGHTER', 'ACTIVE' FROM sp_abc UNION ALL
        SELECT id, 'FREIGHTER', 'ACTIVE' FROM sp_abc UNION ALL
        SELECT id, 'CRUISER',   'ACTIVE' FROM sp_abc UNION ALL
        -- XYZ: SCOUT, FREIGHTER, SCOUT
        SELECT id, 'SCOUT',     'ACTIVE' FROM sp_xyz UNION ALL
        SELECT id, 'FREIGHTER', 'ACTIVE' FROM sp_xyz UNION ALL
        SELECT id, 'SCOUT',     'ACTIVE' FROM sp_xyz
        RETURNING id, starport_id, ship_class
),

-- Wyznaczamy po JEDNYM id zatoki na potrzeby seedowych rezerwacji
abc_scout AS (
    SELECT id FROM ins_bays
    WHERE starport_id = (SELECT id FROM sp_abc) AND ship_class = 'SCOUT'
    ORDER BY id LIMIT 1
),
abc_freighter AS (
    SELECT id FROM ins_bays
    WHERE starport_id = (SELECT id FROM sp_abc) AND ship_class = 'FREIGHTER'
    ORDER BY id LIMIT 1
),
xyz_freighter AS (
    SELECT id FROM ins_bays
    WHERE starport_id = (SELECT id FROM sp_xyz) AND ship_class = 'FREIGHTER'
    ORDER BY id LIMIT 1
)

-- === RESERVATIONS (3 wpisy, każdy z pojedynczym bay_id) ===
INSERT INTO reservation (docking_bay_id, ship_id, ship_class, start_at, end_at, fee_amount)
SELECT id, 'SS-ALPHA', 'SCOUT',
       TIMESTAMPTZ '2025-10-05 06:00:00+00',
       TIMESTAMPTZ '2025-10-05 09:00:00+00',
       100.00 FROM abc_scout
UNION ALL
SELECT id, 'SS-GAMMA', 'FREIGHTER',
       TIMESTAMPTZ '2025-10-05 00:00:00+00',
       TIMESTAMPTZ '2025-10-05 07:00:00+00',
       200.00 FROM abc_freighter
UNION ALL
SELECT id, 'SS-Z-2', 'FREIGHTER',
       TIMESTAMPTZ '2025-10-05 09:00:00+00',
       TIMESTAMPTZ '2025-10-05 10:30:00+00',
       80.00 FROM xyz_freighter;

COMMIT;
