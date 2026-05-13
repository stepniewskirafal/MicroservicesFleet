# ------------------------------------------------------------------
# seed-demo-occupancy.ps1  -  Demo dock occupancy seeder
#
# Wstawia jeden aktywny rezerwacyjny rekord ("start_at <= NOW() AND end_at >
# NOW()") dla wybranej liczby doków w każdym starporcie tak, żeby gauge
# `reservations_docks_occupied_ratio` per port pokazał z góry zaplanowane
# poziomy obłożenia. Idealne pod demo dashboardu
# "Biznes - Przychody i zajętość doków".
#
# Założenia:
#   - Postgres działa w kontenerze (domyślnie nazwany 'postgres').
#   - Migracja V2 wgrała 10 starportów po 100 doków każdy.
#   - Wstawione rezerwacje nie kolidują z normalnym ruchem - są oznaczone
#     statusem 'DEMO_OCCUPANCY', dzięki czemu kolejne uruchomienie skryptu
#     wymiata tylko swoje rekordy. Inne rezerwacje (load-test, ręczne)
#     zostają nietknięte.
#   - DockOccupancyObserver liczy doki, nie rezerwacje (COUNT DISTINCT
#     docking_bay_id) - więc nawet jeśli inna rezerwacja siedzi na tym
#     samym doku, gauge pokaże 1 zajęcie, a nie 2.
#
# Profil obłożenia (suma 100 doków per port = bezpośrednie procenty):
#   TATOO  95%   bustling Mos Eisley
#   CORUS  80%   capital of the galaxy
#   BESPIN 70%   gas mining trade hub
#   NABOO  65%   royal traffic
#   ALDER  50%   - próg czerwone/zielone -
#   MUSTAF 40%   niche volcanic forge
#   HOTH   30%   frozen, low traffic
#   KAMINO 25%   remote, niche clientele
#   ENDOR  15%   quiet forest moon
#   DAGO   10%   hidden swamp
# Daje 5 zielonych (>=50%) i 5 czerwonych (<50%), z szerokim spektrum
# wartości - widać też pełną gamę wskaźników gauge.
#
# Użycie:
#   powershell -ExecutionPolicy Bypass -File scripts\seed-demo-occupancy.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\seed-demo-occupancy.ps1 -DurationHours 6
#   powershell -ExecutionPolicy Bypass -File scripts\seed-demo-occupancy.ps1 -Reset
# ------------------------------------------------------------------
param(
    [switch]$Reset,

    [ValidateRange(1, 168)]
    [int]$DurationHours = 2,

    [string]$Container = "postgres",
    [string]$Database  = "starports",
    [string]$DbUser    = "postgres"
)

$ErrorActionPreference = "Stop"

# Per-starport target dock occupancy (each port has 100 bays in V2 seed
# -> these numbers map 1:1 to percentages).
$targets = [ordered]@{
    TATOO  = 95
    CORUS  = 80
    BESPIN = 70
    NABOO  = 65
    ALDER  = 50
    MUSTAF = 40
    HOTH   = 30
    KAMINO = 25
    ENDOR  = 15
    DAGO   = 10
}

function Test-Container {
    param([string]$Name)
    $running = docker ps --filter "name=^/$Name$" --format "{{.Names}}" 2>$null
    if (-not $running) {
        Write-Host ""
        Write-Host "[ERROR] Kontener '$Name' nie jest uruchomiony." -ForegroundColor Red
        Write-Host "        Uruchom stack: docker compose -f infra/docker/docker-compose.yml up -d postgres" -ForegroundColor Red
        exit 1
    }
}

function Invoke-Sql {
    param([string]$Sql)
    $sql | docker exec -i $Container psql -U $DbUser -d $Database -v ON_ERROR_STOP=1 -q
    if ($LASTEXITCODE -ne 0) {
        Write-Host ""
        Write-Host "[ERROR] psql zwrócił kod wyjścia $LASTEXITCODE - przerywam." -ForegroundColor Red
        exit $LASTEXITCODE
    }
}

# -----------------------------------------------------------------
# Reset: wyrzuca tylko demo-rezerwacje i pokazuje świeży stan.
# -----------------------------------------------------------------
if ($Reset) {
    Test-Container -Name $Container
    Write-Host "============================================================"
    Write-Host " Reset demo-rezerwacji (status = 'DEMO_OCCUPANCY')"
    Write-Host "============================================================"

    $resetSql = @"
BEGIN;
DELETE FROM reservation WHERE status = 'DEMO_OCCUPANCY';
COMMIT;

SELECT  s.code AS starport,
        COUNT(DISTINCT CASE
                          WHEN r.start_at <= NOW() AND r.end_at > NOW()
                          THEN r.docking_bay_id
                       END) AS occupied,
        COUNT(DISTINCT db.id) AS total,
        ROUND(100.0 * COUNT(DISTINCT CASE
                                       WHEN r.start_at <= NOW() AND r.end_at > NOW()
                                       THEN r.docking_bay_id
                                     END) / NULLIF(COUNT(DISTINCT db.id), 0), 1) AS pct
FROM starport s
JOIN docking_bay db ON db.starport_id = s.id
LEFT JOIN reservation r ON r.docking_bay_id = db.id
GROUP BY s.code
ORDER BY pct DESC NULLS LAST, starport;
"@

    Invoke-Sql -Sql $resetSql
    Write-Host ""
    Write-Host "Gotowe. Demo-rezerwacje wyczyszczone." -ForegroundColor Green
    Write-Host "(Inne rezerwacje, np. z load-test, zostały nietknięte.)"
    return
}

# -----------------------------------------------------------------
# Seed: wstawia rezerwacje aktywne TERAZ wg planu z $targets.
# -----------------------------------------------------------------
Test-Container -Name $Container

Write-Host "============================================================"
Write-Host " Demo dock occupancy seed"
Write-Host "============================================================"
Write-Host (" Container: {0}" -f $Container)
Write-Host (" Database:  {0}" -f $Database)
Write-Host (" Duration:  {0}h (rezerwacje wygasną o {1:HH:mm})" -f $DurationHours, (Get-Date).AddHours($DurationHours))
Write-Host ""
Write-Host " Plan obłożenia (cel = N% z 100 doków/port):"
foreach ($k in $targets.Keys) {
    $pct = $targets[$k]
    $color = if ($pct -ge 50) { "Green" } else { "Red" }
    $bar   = ("#" * [Math]::Floor($pct / 5)).PadRight(20, '.')
    Write-Host ("   {0,-7} {1,3}%  [{2}]" -f $k, $pct, $bar) -ForegroundColor $color
}
Write-Host "============================================================"
Write-Host ""

# Buduje listę krotek do CTE 'targets'.
$valuesList = ($targets.GetEnumerator() | ForEach-Object {
    "('{0}', {1})" -f $_.Key, $_.Value
}) -join ", "

$seedSql = @"
BEGIN;

-- Idempotencja: usuwamy wyłącznie nasz wcześniejszy seed.
DELETE FROM reservation WHERE status = 'DEMO_OCCUPANCY';

-- Wstawiamy po jednej aktywnej rezerwacji dla pierwszych N doków każdego
-- starportu (wg planu w 'targets'). DockOccupancyObserver liczy
-- COUNT(DISTINCT docking_bay_id WHERE NOW pomiędzy start_at i end_at) -
-- inne kolumny rezerwacji są dla niego nieistotne, ale wypełniamy je
-- realnymi customer/ship, żeby dane wyglądały sensownie przy debugowaniu.
WITH targets(starport_code, target_count) AS (
    VALUES $valuesList
),
ranked_bays AS (
    SELECT  db.id           AS bay_id,
            s.id            AS starport_id,
            s.code          AS starport_code,
            db.ship_class   AS bay_class,
            ROW_NUMBER() OVER (PARTITION BY s.code ORDER BY db.id) AS rn
    FROM    docking_bay db
    JOIN    starport s ON s.id = db.starport_id
    JOIN    targets  t ON t.starport_code = s.code
),
to_reserve AS (
    SELECT  r.bay_id, r.starport_id, r.bay_class
    FROM    ranked_bays r
    JOIN    targets t ON t.starport_code = r.starport_code
    WHERE   r.rn <= t.target_count
),
ship_by_class AS (
    -- Najniższe id statku w każdej klasie -> stabilny wybór.
    SELECT ship_class, MIN(id) AS ship_id FROM ship GROUP BY ship_class
),
fallback_ship  AS (SELECT MIN(id) AS ship_id     FROM ship),
fallback_cust  AS (SELECT MIN(id) AS customer_id FROM customer)
INSERT INTO reservation (
    starport_id, docking_bay_id, customer_id, ship_id,
    start_at, end_at, fee_charged, status, created_at
)
SELECT  r.starport_id,
        r.bay_id,
        (SELECT customer_id FROM fallback_cust),
        COALESCE(s.ship_id, (SELECT ship_id FROM fallback_ship)),
        NOW() - INTERVAL '5 minutes',
        NOW() + INTERVAL '$DurationHours hours',
        0,
        'DEMO_OCCUPANCY',
        NOW()
FROM    to_reserve r
LEFT JOIN ship_by_class s ON s.ship_class = r.bay_class;

COMMIT;

-- Weryfikacja: dokładnie to samo zapytanie co JpaDockOccupancyQuery.
-- Pokazuje rzeczywiste obłożenie - jeśli na tym samym doku siedzi też
-- inna rezerwacja (np. z load-test), gauge zliczy go tylko raz.
SELECT  s.code AS starport,
        COUNT(DISTINCT CASE
                          WHEN r.start_at <= NOW() AND r.end_at > NOW()
                          THEN r.docking_bay_id
                       END) AS occupied,
        COUNT(DISTINCT db.id) AS total,
        ROUND(100.0 * COUNT(DISTINCT CASE
                                       WHEN r.start_at <= NOW() AND r.end_at > NOW()
                                       THEN r.docking_bay_id
                                     END) / NULLIF(COUNT(DISTINCT db.id), 0), 1) AS pct
FROM starport s
JOIN docking_bay db ON db.starport_id = s.id
LEFT JOIN reservation r ON r.docking_bay_id = db.id
GROUP BY s.code
ORDER BY pct DESC NULLS LAST, starport;
"@

Invoke-Sql -Sql $seedSql

Write-Host ""
Write-Host "============================================================"
Write-Host " Gotowe." -ForegroundColor Green
Write-Host "============================================================"
Write-Host " Następne kroki:"
Write-Host "   1. Odczekaj <=15s na refresh observera (Scheduled co 10s)"
Write-Host "      + scrape Prometheusa."
Write-Host "   2. Otwórz Grafanę: http://localhost:3000"
Write-Host "      Dashboard: 'Biznes - Przychody i zajętość doków'."
Write-Host "   3. Wyzeruj demo:"
Write-Host "        powershell -File scripts\seed-demo-occupancy.ps1 -Reset"
Write-Host ""
Write-Host " Uwaga: rezerwacje wygasną automatycznie po $DurationHours h"
Write-Host " (end_at < NOW() -> gauge sam spadnie do zera)."
Write-Host "============================================================"
