# ------------------------------------------------------------------
# load-test.ps1  –  Generate 100 interleaved reservation requests
#
# Usage:  powershell -ExecutionPolicy Bypass -File scripts\load-test.ps1
#         powershell -ExecutionPolicy Bypass -File scripts\load-test.ps1 -ScriptId 2
#         powershell -ExecutionPolicy Bypass -File scripts\load-test.ps1 -WithRoutes
#
# Run all 5 in parallel:
#   powershell -ExecutionPolicy Bypass -File scripts\load-test-all.ps1
#
# ScriptId 1-5 gives each script a separate time window so dates
# never overlap between scripts (each gets its own 500-hour band).
# ------------------------------------------------------------------
param(
    [string]$Base = "http://localhost:8081",
    [ValidateRange(1,5)][int]$ScriptId = 1,
    [switch]$WithRoutes  # only enable route planning if trade-route-planner is running
)

$API = "$Base/api/v1/starports"
$ok = 0; $fail = 0; $err = 0; $mismatch = 0

# ── Time offset strategy ─────────────────────────────────────────
# Each ScriptId gets a 500-hour band. Within each band, requests are
# spaced 8 hours apart (max duration = 4h), so no overlap is possible.
#
#   Script 1: offsets  1000 –  1400
#   Script 2: offsets  2000 –  2400
#   Script 3: offsets  3000 –  3400
#   Script 4: offsets  4000 –  4400
#   Script 5: offsets  5000 –  5400
#
$offsetBase = $ScriptId * 1000

function Fire {
    param(
        [int]$Num,
        [string]$Label,
        [int]$Expect,
        [string]$Url,
        [string]$Body = ""
    )

    try {
        $params = @{
            Uri             = $Url
            Method          = "POST"
            UseBasicParsing = $true
            ErrorAction     = "Stop"
        }
        if ($Body -ne "") {
            $params.Body        = [System.Text.Encoding]::UTF8.GetBytes($Body)
            $params.ContentType = "application/json"
        }

        $response = Invoke-WebRequest @params
        $code = $response.StatusCode
        $respBody = ""
    }
    catch {
        if ($_.Exception.Response) {
            $code = [int]$_.Exception.Response.StatusCode
            try { $respBody = $_.ErrorDetails.Message } catch { $respBody = "" }
        } else {
            $code = 0
            $respBody = $_.Exception.Message
        }
    }

    $match = if ($code -eq $Expect) { " OK " } else { "MISS" }
    $detail = ""
    if ($match -eq "MISS" -and $code -ne 0) {
        $detail = "  <<<  got $code"
        if ($respBody) { $detail += " | $($respBody.Substring(0, [Math]::Min(120, $respBody.Length)))" }
    }
    Write-Host ("[{0,3}/100]  {1}  {2,3} (exp {3})  {4}{5}" -f $Num, $match, $code, $Expect, $Label, $detail)

    if     ($code -ge 200 -and $code -lt 300) { $script:ok++ }
    elseif ($code -ge 400 -and $code -lt 500) { $script:fail++ }
    else                                       { $script:err++ }
    if ($match -eq "MISS") { $script:mismatch++ }
}

function FutureTime {
    param([int]$OffsetHours, [int]$DurationHours = 1)
    $start = (Get-Date).ToUniversalTime().AddHours($OffsetHours).ToString("yyyy-MM-ddTHH:mm:ssZ")
    $end   = (Get-Date).ToUniversalTime().AddHours($OffsetHours + $DurationHours).ToString("yyyy-MM-ddTHH:mm:ssZ")
    return @{ Start = $start; End = $end }
}

function Reservation {
    param(
        [int]$OffsetHours,
        [int]$DurationHours = 1,
        [string]$CustomerCode = "CUST-001",
        [string]$ShipCode = "SS-Enterprise-01",
        [string]$ShipClass = "SCOUT",
        [string]$Route = "false",
        [string]$Origin = ""
    )
    $t = FutureTime -OffsetHours $OffsetHours -DurationHours $DurationHours
    $originField = if ($Origin -ne "") { "`"originPortId`": `"$Origin`"" } else { "`"originPortId`": null" }
    return @"
{
  "customerCode": "$CustomerCode",
  "shipCode": "$ShipCode",
  "shipClass": "$ShipClass",
  "startAt": "$($t.Start)",
  "endAt": "$($t.End)",
  "requestRoute": $Route,
  $originField
}
"@
}

# ── Valid ship class + starport combinations ────────────────────
# Only pick combinations that actually have docking bays!
#   ABC:        SCOUT(6), FREIGHTER(4), CRUISER(2)
#   ALPHA-BASE: SCOUT(3), FREIGHTER(2)
#   BETA-PORT:  SCOUT(2), CRUISER(1)

$validCombinations = @(
    @{ Dest = "ABC";        Classes = @("SCOUT", "FREIGHTER", "CRUISER") },
    @{ Dest = "ALPHA-BASE"; Classes = @("SCOUT", "FREIGHTER") },
    @{ Dest = "BETA-PORT";  Classes = @("SCOUT", "CRUISER") }
)

# Map ship class to valid ships per customer
$shipsByClass = @{
    "SCOUT"     = @(
        @{ Cust = "CUST-001"; Ship = "SS-Enterprise-01" },
        @{ Cust = "CUST-002"; Ship = "TR-Nexus-01" },
        @{ Cust = "CUST-003"; Ship = "UC-Raven-01" }
    )
    "FREIGHTER" = @(
        @{ Cust = "CUST-001"; Ship = "SS-Falcon-02" },
        @{ Cust = "CUST-002"; Ship = "TR-Hauler-02" }
    )
    "CRUISER"   = @(
        @{ Cust = "CUST-001"; Ship = "SS-Destroyer-03" },
        @{ Cust = "CUST-003"; Ship = "UC-Carrier-02" }
    )
}

# ── Build 50 GOOD requests ─────────────────────────────────────

$goodCalls = @()

for ($i = 1; $i -le 50; $i++) {
    # Pick a random valid combination
    $combo = $validCombinations[(Get-Random -Minimum 0 -Maximum $validCombinations.Count)]
    $dest  = $combo.Dest
    $class = $combo.Classes[(Get-Random -Minimum 0 -Maximum $combo.Classes.Count)]

    # Pick a random ship of the right class
    $ships   = $shipsByClass[$class]
    $shipDef = $ships[(Get-Random -Minimum 0 -Maximum $ships.Count)]

    # Unique time window: ScriptId determines band, $i determines slot within band
    # 8h spacing guarantees no overlap even with max 4h duration
    $offset   = $offsetBase + ($i * 8)
    $duration = Get-Random -Minimum 1 -Maximum 4

    # Route planning: only if -WithRoutes flag is set
    $wantRoute = "false"
    $originVal = ""
    if ($WithRoutes) {
        if ((Get-Random -Minimum 0 -Maximum 3) -ne 0) {
            $wantRoute = "true"
            $otherPorts = $validCombinations | Where-Object { $_.Dest -ne $dest }
            $originVal = ($otherPorts | Get-Random).Dest
        }
    }

    $goodCalls += ,@{
        Label  = "GOOD  $class $($shipDef.Cust)/$($shipDef.Ship) -> $dest  ${duration}h"
        Expect = 201
        Url    = "$API/$dest/reservations"
        Body   = (Reservation -OffsetHours $offset -DurationHours $duration `
                    -CustomerCode $shipDef.Cust -ShipCode $shipDef.Ship -ShipClass $class `
                    -Route $wantRoute -Origin $originVal)
    }
}

# ── Build 50 BAD requests ──────────────────────────────────────

$badCalls = @()

# Wrong URLs (1-7)
$badCalls += ,@{ Label = "BAD   wrong path /reservationz";         Expect = 404; Url = "$API/ABC/reservationz";           Body = (Reservation -OffsetHours 9000) }
$badCalls += ,@{ Label = "BAD   path /api/v2/ships/dock";          Expect = 404; Url = "$Base/api/v2/ships/dock";          Body = (Reservation -OffsetHours 9001) }
$badCalls += ,@{ Label = "BAD   path /reservation (singular)";     Expect = 404; Url = "$API/ABC/reservation";             Body = (Reservation -OffsetHours 9002) }
$badCalls += ,@{ Label = "BAD   path /bookings";                   Expect = 404; Url = "$API/ABC/bookings";                Body = (Reservation -OffsetHours 9003) }
$badCalls += ,@{ Label = "BAD   path /api/v1/docks";               Expect = 404; Url = "$Base/api/v1/docks";               Body = (Reservation -OffsetHours 9004) }
$badCalls += ,@{ Label = "BAD   path /api/v1/reserve";             Expect = 404; Url = "$Base/api/v1/reserve";             Body = (Reservation -OffsetHours 9005) }
$badCalls += ,@{ Label = "BAD   double slash in path";             Expect = 404; Url = "$Base/api/v1/starports//reservations"; Body = (Reservation -OffsetHours 9006) }

# Non-existent starports (8-13)
$badCalls += ,@{ Label = "BAD   starport ZZZZ";          Expect = 404; Url = "$API/ZZZZ/reservations";         Body = (Reservation -OffsetHours 9010) }
$badCalls += ,@{ Label = "BAD   starport MARS";          Expect = 404; Url = "$API/MARS/reservations";         Body = (Reservation -OffsetHours 9011) }
$badCalls += ,@{ Label = "BAD   starport DEEP-SPACE-9";  Expect = 404; Url = "$API/DEEP-SPACE-9/reservations"; Body = (Reservation -OffsetHours 9012) }
$badCalls += ,@{ Label = "BAD   starport TATOOINE";      Expect = 404; Url = "$API/TATOOINE/reservations";     Body = (Reservation -OffsetHours 9013) }
$badCalls += ,@{ Label = "BAD   starport VULCAN";        Expect = 404; Url = "$API/VULCAN/reservations";       Body = (Reservation -OffsetHours 9014) }
$badCalls += ,@{ Label = "BAD   starport CORUSCANT";     Expect = 404; Url = "$API/CORUSCANT/reservations";    Body = (Reservation -OffsetHours 9015) }

# Missing required fields (14-20)
$badCalls += ,@{ Label = "BAD   empty JSON {}";          Expect = 422; Url = "$API/ABC/reservations"; Body = '{}' }
$badCalls += ,@{ Label = "BAD   missing customerCode";   Expect = 422; Url = "$API/ABC/reservations"; Body = '{"shipCode":"SS-Enterprise-01","shipClass":"SCOUT","startAt":"2031-01-01T00:00:00Z","endAt":"2031-01-01T01:00:00Z","requestRoute":false}' }
$badCalls += ,@{ Label = "BAD   missing shipCode";       Expect = 422; Url = "$API/ABC/reservations"; Body = '{"customerCode":"CUST-001","shipClass":"SCOUT","startAt":"2031-01-01T00:00:00Z","endAt":"2031-01-01T01:00:00Z","requestRoute":false}' }
$badCalls += ,@{ Label = "BAD   missing shipClass";      Expect = 422; Url = "$API/ABC/reservations"; Body = '{"customerCode":"CUST-001","shipCode":"SS-Enterprise-01","startAt":"2031-01-01T00:00:00Z","endAt":"2031-01-01T01:00:00Z","requestRoute":false}' }
$badCalls += ,@{ Label = "BAD   missing startAt";        Expect = 422; Url = "$API/ABC/reservations"; Body = '{"customerCode":"CUST-001","shipCode":"SS-Enterprise-01","shipClass":"SCOUT","endAt":"2031-01-01T01:00:00Z","requestRoute":false}' }
$badCalls += ,@{ Label = "BAD   missing endAt";          Expect = 422; Url = "$API/ABC/reservations"; Body = '{"customerCode":"CUST-001","shipCode":"SS-Enterprise-01","shipClass":"SCOUT","startAt":"2031-01-01T00:00:00Z","requestRoute":false}' }
$badCalls += ,@{ Label = "BAD   missing requestRoute";   Expect = 422; Url = "$API/ABC/reservations"; Body = '{"customerCode":"CUST-001","shipCode":"SS-Enterprise-01","shipClass":"SCOUT","startAt":"2031-01-01T00:00:00Z","endAt":"2031-01-01T01:00:00Z"}' }

# Invalid field values (21-28)
$badCalls += ,@{ Label = "BAD   past startAt 2020";       Expect = 422; Url = "$API/ABC/reservations"; Body = '{"customerCode":"CUST-001","shipCode":"SS-Enterprise-01","shipClass":"SCOUT","startAt":"2020-01-01T00:00:00Z","endAt":"2020-01-01T01:00:00Z","requestRoute":false}' }
$badCalls += ,@{ Label = "BAD   startAt > endAt";         Expect = 422; Url = "$API/ABC/reservations"; Body = '{"customerCode":"CUST-001","shipCode":"SS-Enterprise-01","shipClass":"SCOUT","startAt":"2031-06-01T10:00:00Z","endAt":"2031-06-01T08:00:00Z","requestRoute":false}' }
$badCalls += ,@{ Label = "BAD   shipClass BATTLESTAR";    Expect = 400; Url = "$API/ABC/reservations"; Body = '{"customerCode":"CUST-001","shipCode":"SS-Enterprise-01","shipClass":"BATTLESTAR","startAt":"2031-01-01T00:00:00Z","endAt":"2031-01-01T01:00:00Z","requestRoute":false}' }
$badCalls += ,@{ Label = "BAD   blank customerCode";      Expect = 422; Url = "$API/ABC/reservations"; Body = '{"customerCode":"","shipCode":"SS-Enterprise-01","shipClass":"SCOUT","startAt":"2031-01-01T00:00:00Z","endAt":"2031-01-01T01:00:00Z","requestRoute":false}' }
$badCalls += ,@{ Label = "BAD   blank shipCode";          Expect = 422; Url = "$API/ABC/reservations"; Body = '{"customerCode":"CUST-001","shipCode":"","shipClass":"SCOUT","startAt":"2031-01-01T00:00:00Z","endAt":"2031-01-01T01:00:00Z","requestRoute":false}' }
$badCalls += ,@{ Label = "BAD   same start=end";          Expect = 422; Url = "$API/ABC/reservations"; Body = '{"customerCode":"CUST-001","shipCode":"SS-Enterprise-01","shipClass":"SCOUT","startAt":"2031-10-01T00:00:00Z","endAt":"2031-10-01T00:00:00Z","requestRoute":false}' }
$badCalls += ,@{ Label = "BAD   invalid date format";     Expect = 400; Url = "$API/ABC/reservations"; Body = '{"customerCode":"CUST-001","shipCode":"SS-Enterprise-01","shipClass":"SCOUT","startAt":"not-a-date","endAt":"also-not","requestRoute":false}' }
$badCalls += ,@{ Label = "BAD   past endAt only";         Expect = 422; Url = "$API/ABC/reservations"; Body = '{"customerCode":"CUST-001","shipCode":"SS-Enterprise-01","shipClass":"SCOUT","startAt":"2031-01-01T00:00:00Z","endAt":"2019-01-01T00:00:00Z","requestRoute":false}' }

# Malformed body (29-34)
$badCalls += ,@{ Label = "BAD   broken JSON";             Expect = 400; Url = "$API/ABC/reservations"; Body = '{broken json!!!' }
$badCalls += ,@{ Label = "BAD   XML body";                Expect = 400; Url = "$API/ABC/reservations"; Body = '<reservation><code>X</code></reservation>' }
$badCalls += ,@{ Label = "BAD   array body [1,2,3]";      Expect = 400; Url = "$API/ABC/reservations"; Body = '[1,2,3]' }
$badCalls += ,@{ Label = "BAD   just a string";           Expect = 400; Url = "$API/ABC/reservations"; Body = '"hello"' }
$badCalls += ,@{ Label = "BAD   number as body";          Expect = 400; Url = "$API/ABC/reservations"; Body = '42' }
$badCalls += ,@{ Label = "BAD   empty body POST";         Expect = 400; Url = "$API/ABC/reservations"; Body = '' }

# Non-existent customer/ship (35-40)
$badCalls += ,@{ Label = "BAD   unknown customer NOBODY";   Expect = 404; Url = "$API/ABC/reservations"; Body = '{"customerCode":"NOBODY","shipCode":"SS-Enterprise-01","shipClass":"SCOUT","startAt":"2031-07-01T00:00:00Z","endAt":"2031-07-01T01:00:00Z","requestRoute":false}' }
$badCalls += ,@{ Label = "BAD   unknown customer ACME";     Expect = 404; Url = "$API/ABC/reservations"; Body = '{"customerCode":"ACME-999","shipCode":"SS-Enterprise-01","shipClass":"SCOUT","startAt":"2031-07-02T00:00:00Z","endAt":"2031-07-02T01:00:00Z","requestRoute":false}' }
$badCalls += ,@{ Label = "BAD   unknown ship GHOST";        Expect = 404; Url = "$API/ABC/reservations"; Body = '{"customerCode":"CUST-001","shipCode":"GHOST-SHIP","shipClass":"SCOUT","startAt":"2031-07-03T00:00:00Z","endAt":"2031-07-03T01:00:00Z","requestRoute":false}' }
$badCalls += ,@{ Label = "BAD   unknown ship PHANTOM";      Expect = 404; Url = "$API/ABC/reservations"; Body = '{"customerCode":"CUST-001","shipCode":"PHANTOM-X","shipClass":"SCOUT","startAt":"2031-07-04T00:00:00Z","endAt":"2031-07-04T01:00:00Z","requestRoute":false}' }
$badCalls += ,@{ Label = "BAD   unknown customer+ship";     Expect = 404; Url = "$API/ABC/reservations"; Body = '{"customerCode":"WHO","shipCode":"WHAT","shipClass":"SCOUT","startAt":"2031-07-05T00:00:00Z","endAt":"2031-07-05T01:00:00Z","requestRoute":false}' }
$badCalls += ,@{ Label = "BAD   unknown ship DERELICT";     Expect = 404; Url = "$API/ABC/reservations"; Body = '{"customerCode":"CUST-001","shipCode":"DERELICT-7","shipClass":"SCOUT","startAt":"2031-07-06T00:00:00Z","endAt":"2031-07-06T01:00:00Z","requestRoute":false}' }

# Route-related errors (41-46)
$badCalls += ,@{ Label = "BAD   route=true no originPortId";  Expect = 404; Url = "$API/ABC/reservations"; Body = '{"customerCode":"CUST-001","shipCode":"SS-Enterprise-01","shipClass":"SCOUT","startAt":"2031-08-01T00:00:00Z","endAt":"2031-08-01T01:00:00Z","requestRoute":true}' }
$badCalls += ,@{ Label = "BAD   route origin PLUTO";          Expect = 404; Url = "$API/ABC/reservations"; Body = '{"customerCode":"CUST-001","shipCode":"SS-Enterprise-01","shipClass":"SCOUT","startAt":"2031-08-02T00:00:00Z","endAt":"2031-08-02T01:00:00Z","requestRoute":true,"originPortId":"PLUTO"}' }
$badCalls += ,@{ Label = "BAD   route origin ENDOR";          Expect = 404; Url = "$API/ABC/reservations"; Body = '{"customerCode":"CUST-001","shipCode":"SS-Enterprise-01","shipClass":"SCOUT","startAt":"2031-08-03T00:00:00Z","endAt":"2031-08-03T01:00:00Z","requestRoute":true,"originPortId":"ENDOR"}' }
$badCalls += ,@{ Label = "BAD   route origin KESSEL";         Expect = 404; Url = "$API/ABC/reservations"; Body = '{"customerCode":"CUST-001","shipCode":"SS-Enterprise-01","shipClass":"SCOUT","startAt":"2031-08-04T00:00:00Z","endAt":"2031-08-04T01:00:00Z","requestRoute":true,"originPortId":"KESSEL"}' }
$badCalls += ,@{ Label = "BAD   route origin HOTH";           Expect = 404; Url = "$API/ABC/reservations"; Body = '{"customerCode":"CUST-001","shipCode":"SS-Enterprise-01","shipClass":"SCOUT","startAt":"2031-08-05T00:00:00Z","endAt":"2031-08-05T01:00:00Z","requestRoute":true,"originPortId":"HOTH"}' }
$badCalls += ,@{ Label = "BAD   route origin NABOO";          Expect = 404; Url = "$API/ABC/reservations"; Body = '{"customerCode":"CUST-001","shipCode":"SS-Enterprise-01","shipClass":"SCOUT","startAt":"2031-08-06T00:00:00Z","endAt":"2031-08-06T01:00:00Z","requestRoute":true,"originPortId":"NABOO"}' }

# More invalid values (47-50)
$badCalls += ,@{ Label = "BAD   shipClass DREADNOUGHT";   Expect = 400; Url = "$API/ABC/reservations"; Body = '{"customerCode":"CUST-001","shipCode":"SS-Enterprise-01","shipClass":"DREADNOUGHT","startAt":"2031-01-02T00:00:00Z","endAt":"2031-01-02T01:00:00Z","requestRoute":false}' }
$badCalls += ,@{ Label = "BAD   shipClass CARRIER";       Expect = 400; Url = "$API/ABC/reservations"; Body = '{"customerCode":"CUST-001","shipCode":"SS-Enterprise-01","shipClass":"CARRIER","startAt":"2031-01-03T00:00:00Z","endAt":"2031-01-03T01:00:00Z","requestRoute":false}' }
$badCalls += ,@{ Label = "BAD   null shipClass";          Expect = 422; Url = "$API/ABC/reservations"; Body = '{"customerCode":"CUST-001","shipCode":"SS-Enterprise-01","shipClass":null,"startAt":"2031-01-05T00:00:00Z","endAt":"2031-01-05T01:00:00Z","requestRoute":false}' }
$badCalls += ,@{ Label = "BAD   negative duration";       Expect = 422; Url = "$API/ABC/reservations"; Body = '{"customerCode":"CUST-001","shipCode":"SS-Enterprise-01","shipClass":"SCOUT","startAt":"2031-12-31T23:00:00Z","endAt":"2031-01-01T00:00:00Z","requestRoute":false}' }

# ── Interleave: good, bad, good, bad, ... ────────────────────────

Write-Host "============================================"
Write-Host " Starport Registry Load Test (100 requests)"
Write-Host " Target:    $Base"
Write-Host " Script ID: $ScriptId / 5  (offset band: $($offsetBase)–$($offsetBase + 400)h)"
Write-Host " Routes:    $(if ($WithRoutes) { 'ENABLED (needs trade-route-planner)' } else { 'DISABLED' })"
Write-Host " Pattern:   GOOD, BAD, GOOD, BAD, ..."
Write-Host "============================================"
Write-Host ""

$gi = 0; $bi = 0; $n = 0

while ($n -lt 100) {
    if ($gi -lt $goodCalls.Count -and $n -lt 100) {
        $n++
        $c = $goodCalls[$gi]; $gi++
        Fire -Num $n -Label $c.Label -Expect $c.Expect -Url $c.Url -Body $c.Body
    }
    if ($bi -lt $badCalls.Count -and $n -lt 100) {
        $n++
        $c = $badCalls[$bi]; $bi++
        Fire -Num $n -Label $c.Label -Expect $c.Expect -Url $c.Url -Body $c.Body
    }
}

Write-Host ""
Write-Host "============================================"
Write-Host (" Done!  success={0}  client-error={1}  other={2}  total={3}" -f $ok, $fail, $err, ($ok+$fail+$err))
Write-Host (" Mismatched expectations: {0}" -f $mismatch)
Write-Host "============================================"
