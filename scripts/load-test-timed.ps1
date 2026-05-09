# ------------------------------------------------------------------
# load-test-timed.ps1  -  Time-bounded reservation load generator
#
# Differs from load-test.ps1 (count-based, 100 requests):
#   - Runs for a fixed duration (-DurationMinutes 5/10/30/...)
#   - Stoppable any time with 'Q' or Esc (clean summary on exit)
#   - 90% good / 10% bad mix (configurable with -ErrorRatio)
#   - Throttled (default 5 req/s = 300/min, configurable)
#   - Each good request gets a globally unique 1h time slot far in the
#     future, so good reservations NEVER overlap on the same bay
#     (only intentional bad requests overlap or break things).
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts\load-test-timed.ps1 -DurationMinutes 5
#   powershell -ExecutionPolicy Bypass -File scripts\load-test-timed.ps1 -DurationMinutes 30 -RatePerSecond 8
#   powershell -ExecutionPolicy Bypass -File scripts\load-test-timed.ps1 -DurationMinutes 10 -ErrorRatio 0.20
#
# Why 5 req/s default?
#   Benchmarked the running stack (gateway -> 2x starport-registry -> Postgres)
#   and saw ~70ms mean / ~92ms p95 latency per reservation. At 5 req/s the
#   slot is 200ms - request completes in <100ms, leaving >100ms headroom.
#   Tomcat `threads.max=60` per instance × 2 instances = 120 worker threads;
#   we are nowhere near saturation. Push higher with -RatePerSecond if you
#   want to exercise queueing behaviour.
#
# Data assumptions (matches V2 migration after drop+reapply):
#   - 10 starports: TATOO, CORUS, ALDER, HOTH, ENDOR, NABOO, DAGO, BESPIN, KAMINO, MUSTAF
#   - 200 customers: CUST-001 .. CUST-200
#   - 200 ships: name follows the V2 cycle (X-WING/FALCON/STAR-DESTROYER/TIE-FIGHTER/SLAVE-I/VENATOR)
#     ship_class matches the design.
# ------------------------------------------------------------------
param(
    [string]$Base = "http://localhost:8080",

    [Parameter(Mandatory=$true)]
    [ValidateRange(1, 1440)]
    [int]$DurationMinutes,

    [ValidateRange(0.1, 50.0)]
    [double]$RatePerSecond = 5.0,

    [ValidateRange(0.0, 1.0)]
    [double]$ErrorRatio = 0.10
)

$ErrorActionPreference = "Stop"
$API = "$Base/api/v1/starports"

# --- counters ---
$script:sent           = 0     # total HTTP attempts
$script:ok             = 0     # 2xx
$script:clientErr      = 0     # 4xx
$script:serverErr      = 0     # 5xx
$script:netErr         = 0     # transport errors / DNS / refused
$script:expectedMatch  = 0     # status matched the expectation we sent
$script:expectedMiss   = 0     # status did not match expectation

# --- data: starports + ship designs (must match V2 migration) ---
$STARPORTS = @("TATOO", "CORUS", "ALDER", "HOTH", "ENDOR", "NABOO", "DAGO", "BESPIN", "KAMINO", "MUSTAF")

# Index is (customerIdx - 1) % 6.  Returns @{Code=...; Class=...} for that customer's ship.
function Get-ShipForCustomer {
    param([int]$CustomerIdx)
    $designIdx = ($CustomerIdx - 1) % 6
    $suffix = $CustomerIdx.ToString("000")
    switch ($designIdx) {
        0 { return @{ Code = "X-WING-$suffix";         Class = "SCOUT"     } }
        1 { return @{ Code = "FALCON-$suffix";         Class = "FREIGHTER" } }
        2 { return @{ Code = "STAR-DESTROYER-$suffix"; Class = "CRUISER"   } }
        3 { return @{ Code = "TIE-FIGHTER-$suffix";    Class = "SCOUT"     } }
        4 { return @{ Code = "SLAVE-I-$suffix";        Class = "FREIGHTER" } }
        5 { return @{ Code = "VENATOR-$suffix";        Class = "CRUISER"   } }
    }
}

# Builds a body for a guaranteed-valid reservation request.
# Each call gets a globally unique 1h time slot derived from $SlotIdx
# (base = today + 100 years; +$SlotIdx hours). No two good requests
# can overlap on any docking bay.
function New-GoodBody {
    param(
        [int]$SlotIdx,
        [int]$CustomerIdx
    )
    $baseTime = (Get-Date).ToUniversalTime().Date.AddYears(100).AddHours(12)
    $startDt  = $baseTime.AddHours($SlotIdx)
    $endDt    = $startDt.AddHours(1)
    $startStr = $startDt.ToString("yyyy-MM-ddTHH:mm:ssZ")
    $endStr   = $endDt.ToString("yyyy-MM-ddTHH:mm:ssZ")

    $ship = Get-ShipForCustomer -CustomerIdx $CustomerIdx
    $customerCode = "CUST-" + $CustomerIdx.ToString("000")

    return @"
{
  "customerCode": "$customerCode",
  "shipCode":     "$($ship.Code)",
  "shipClass":    "$($ship.Class)",
  "startAt":      "$startStr",
  "endAt":        "$endStr",
  "requestRoute": false,
  "originPortId": null
}
"@
}

# Returns one bad-call template @{Url; Body; Expect; Label} chosen at random.
# Each kind exercises a different error path the GlobalExceptionHandler covers.
function New-BadCall {
    $port  = $STARPORTS | Get-Random
    $rid   = Get-Random -Minimum 1000 -Maximum 999999
    $kind  = Get-Random -Minimum 0 -Maximum 8

    switch ($kind) {
        0 {  # unknown starport → 404
            return @{
                Label = "BAD  starport=ZZZ$rid"
                Url   = "$API/ZZZ$rid/reservations"
                Body  = (New-GoodBody -SlotIdx 999000 -CustomerIdx 1)
                Expect = 404
            }
        }
        1 {  # unknown customer → 404
            return @{
                Label = "BAD  customerCode=NOBODY"
                Url   = "$API/$port/reservations"
                Body  = '{"customerCode":"NOBODY","shipCode":"X-WING-001","shipClass":"SCOUT","startAt":"2125-01-01T00:00:00Z","endAt":"2125-01-01T01:00:00Z","requestRoute":false}'
                Expect = 404
            }
        }
        2 {  # unknown ship → 404
            return @{
                Label = "BAD  shipCode=GHOST-SHIP"
                Url   = "$API/$port/reservations"
                Body  = '{"customerCode":"CUST-001","shipCode":"GHOST-SHIP","shipClass":"SCOUT","startAt":"2125-01-01T00:00:00Z","endAt":"2125-01-01T01:00:00Z","requestRoute":false}'
                Expect = 404
            }
        }
        3 {  # missing customerCode → 422
            return @{
                Label = "BAD  missing customerCode"
                Url   = "$API/$port/reservations"
                Body  = '{"shipCode":"X-WING-001","shipClass":"SCOUT","startAt":"2125-01-01T00:00:00Z","endAt":"2125-01-01T01:00:00Z","requestRoute":false}'
                Expect = 422
            }
        }
        4 {  # invalid shipClass enum → 400
            return @{
                Label = "BAD  shipClass=BATTLESTAR"
                Url   = "$API/$port/reservations"
                Body  = '{"customerCode":"CUST-001","shipCode":"X-WING-001","shipClass":"BATTLESTAR","startAt":"2125-01-01T00:00:00Z","endAt":"2125-01-01T01:00:00Z","requestRoute":false}'
                Expect = 400
            }
        }
        5 {  # malformed JSON → 400
            return @{
                Label = "BAD  malformed JSON"
                Url   = "$API/$port/reservations"
                Body  = '{broken json!!!'
                Expect = 400
            }
        }
        6 {  # startAt > endAt → 422
            return @{
                Label = "BAD  startAt > endAt"
                Url   = "$API/$port/reservations"
                Body  = '{"customerCode":"CUST-001","shipCode":"X-WING-001","shipClass":"SCOUT","startAt":"2125-06-01T10:00:00Z","endAt":"2125-06-01T08:00:00Z","requestRoute":false}'
                Expect = 422
            }
        }
        7 {  # past startAt → 422
            return @{
                Label = "BAD  past startAt"
                Url   = "$API/$port/reservations"
                Body  = '{"customerCode":"CUST-001","shipCode":"X-WING-001","shipClass":"SCOUT","startAt":"2020-01-01T00:00:00Z","endAt":"2020-01-01T01:00:00Z","requestRoute":false}'
                Expect = 422
            }
        }
    }
}

function Send-Reservation {
    param([string]$Url, [string]$Body, [int]$Expect, [string]$Label)

    try {
        $params = @{
            Uri             = $Url
            Method          = "POST"
            UseBasicParsing = $true
            ErrorAction     = "Stop"
            Body            = [System.Text.Encoding]::UTF8.GetBytes($Body)
            ContentType     = "application/json"
        }
        $response = Invoke-WebRequest @params
        $code = [int]$response.StatusCode
    }
    catch {
        if ($_.Exception.Response) {
            $code = [int]$_.Exception.Response.StatusCode
        } else {
            $code = 0
        }
    }

    $script:sent++
    if     ($code -eq 0)                       { $script:netErr++ }
    elseif ($code -ge 200 -and $code -lt 300)  { $script:ok++ }
    elseif ($code -ge 400 -and $code -lt 500)  { $script:clientErr++ }
    elseif ($code -ge 500)                     { $script:serverErr++ }

    if ($code -eq $Expect) { $script:expectedMatch++ } else { $script:expectedMiss++ }

    return $code
}

function Write-Summary {
    $elapsed = $script:stopwatch.Elapsed
    $rate    = if ($elapsed.TotalSeconds -gt 0) { $script:sent / $elapsed.TotalSeconds } else { 0 }
    Write-Host ""
    Write-Host "============================================================"
    Write-Host (" Done.  duration={0,5:N0}s  sent={1}  rate={2:N2} req/s" -f $elapsed.TotalSeconds, $script:sent, $rate)
    Write-Host (" 2xx (ok)        : {0}" -f $script:ok)
    Write-Host (" 4xx (client err): {0}" -f $script:clientErr)
    Write-Host (" 5xx (server err): {0}   <-- watch this; should be 0" -f $script:serverErr)
    Write-Host (" transport err   : {0}" -f $script:netErr)
    Write-Host (" status matched expectation : {0}" -f $script:expectedMatch)
    Write-Host (" status did NOT match       : {0}" -f $script:expectedMiss)
    Write-Host "============================================================"
}

# --- main ---

$intervalMs   = [int](1000.0 / $RatePerSecond)
$endTime      = (Get-Date).AddMinutes($DurationMinutes)
$slotIdx      = 0
$script:stopwatch = [System.Diagnostics.Stopwatch]::StartNew()

Write-Host "============================================================"
Write-Host " Time-Bounded Reservation Load Test"
Write-Host "============================================================"
Write-Host (" Target:        {0}" -f $Base)
Write-Host (" Duration:      {0} minutes (until {1:HH:mm:ss})" -f $DurationMinutes, $endTime)
Write-Host (" Rate:          {0} req/s  (slot = {1} ms)" -f $RatePerSecond, $intervalMs)
Write-Host (" Error ratio:   {0:P0} bad / {1:P0} good" -f $ErrorRatio, (1 - $ErrorRatio))
Write-Host (" Stop early:    press Q or Esc")
Write-Host "============================================================"
Write-Host ""

try {
    while ((Get-Date) -lt $endTime) {

        # Cooperative stop. KeyAvailable throws InvalidOperationException
        # when there's no real console (piped, redirected, started with no
        # window) - silently skip the check in that case.
        try {
            if ([Console]::KeyAvailable) {
                $k = [Console]::ReadKey($true)
                if ($k.Key -eq 'Q' -or $k.Key -eq 'Escape') {
                    Write-Host ("`n[STOP] {0} pressed - finishing up..." -f $k.Key)
                    break
                }
            }
        } catch [System.InvalidOperationException] { }

        $tickStart = $script:stopwatch.ElapsedMilliseconds

        $isBad = (Get-Random -Minimum 0.0 -Maximum 1.0) -lt $ErrorRatio
        if ($isBad) {
            $call = New-BadCall
            $url    = $call.Url
            $body   = $call.Body
            $expect = $call.Expect
            $label  = $call.Label
        } else {
            $port    = $STARPORTS | Get-Random
            $custIdx = Get-Random -Minimum 1 -Maximum 201
            $url     = "$API/$port/reservations"
            $body    = New-GoodBody -SlotIdx $slotIdx -CustomerIdx $custIdx
            $expect  = 201
            $label   = "GOOD $port  CUST-$($custIdx.ToString('000'))  slot=$slotIdx"
            $slotIdx++
        }

        $code = Send-Reservation -Url $url -Body $body -Expect $expect -Label $label

        # Progress line every 50 requests
        if ($script:sent % 50 -eq 0) {
            $elapsed = $script:stopwatch.Elapsed
            $remain  = ($endTime - (Get-Date)).TotalSeconds
            Write-Host (" [progress] sent={0,5}  ok={1,5}  client-err={2,4}  server-err={3,3}  rate={4:N2}/s  remain={5,4:N0}s" -f $script:sent, $script:ok, $script:clientErr, $script:serverErr, ($script:sent / [Math]::Max($elapsed.TotalSeconds, 0.001)), $remain)
        }

        # Throttle: sleep until next slot
        $tickElapsed   = $script:stopwatch.ElapsedMilliseconds - $tickStart
        $remainingTick = $intervalMs - $tickElapsed
        if ($remainingTick -gt 0) {
            Start-Sleep -Milliseconds $remainingTick
        }
    }
}
finally {
    Write-Summary
}
