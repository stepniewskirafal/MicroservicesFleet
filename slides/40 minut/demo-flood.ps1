# =============================================================================
# demo-flood.ps1 — scenariusze RateLimiter + Bulkhead do talku
# "Działa != działa i ja o tym wiem" (branch: demo)
# =============================================================================
# Dwa tryby:
#   -Mode flood    burst ~40 req/s przez -DurationSeconds (dom. 30 s), równolegle.
#                  Limiter 10/s odbija ~75% -> errorType=rate_limited na dashboardzie.
#   -Mode squeeze  30 rownoleglych klientow na SPOWOLNIONYM plannerze.
#                  UWAGA: squeeze zaklada, ze planner juz jest wolny — odpal
#                  wczesniej: demo-resilience.ps1 -Chaos slow -SlowMs 600.
#                  Bulkhead (8 slotow) zapycha sie -> errorType=bulkhead_full.
#
# Uruchamianie z cmd:
#   powershell -ExecutionPolicy Bypass -File .\scripts\demo-flood.ps1 -Mode flood
#   powershell -ExecutionPolicy Bypass -File .\scripts\demo-flood.ps1 -Mode squeeze
#
# Wspólne parametry z demo-resilience.ps1: -Base (dom. http://localhost:8080).
# Skrypt NICZEGO nie psuje w infrze — generuje tylko ruch. Po zakończeniu ruch
# wraca do normy sam (Terminal A dalej leci swoje ~4 req/s).
# =============================================================================

param(
    [Parameter(Mandatory)][ValidateSet('flood','squeeze')] [string]$Mode,
    [string]$Base = 'http://localhost:8080',
    [int]$DurationSeconds = 30,
    [int]$FloodRatePerSecond = 40,
    [int]$SqueezeParallel = 30
)

$ErrorActionPreference = 'Continue'
$ports = @('TATOO','CORUS','ALDER','HOTH','ENDOR','NABOO','DAGO','BESPIN','KAMINO','MUSTAF')
$ships = @(@('X-WING','SCOUT'), @('FALCON','FREIGHTER'), @('STAR-DESTROYER','CRUISER'))

function New-ReservationBody {
    $dest   = Get-Random -InputObject $ports
    $origin = Get-Random -InputObject ($ports | Where-Object { $_ -ne $dest })
    $ship   = Get-Random -InputObject $ships
    $start  = (Get-Date).AddDays((Get-Random -Minimum 1 -Maximum 3650)).ToString('yyyy-MM-ddTHH:mm:ss')
    @{
        shipId       = $ship[0]
        shipClass    = $ship[1]
        customerId   = "CUST-00$(Get-Random -Minimum 1 -Maximum 6)"
        originPortId = $origin
        startAt      = $start
        requestRoute = $true          # kluczowe: wolamy trade-route-planner
    } | ConvertTo-Json -Compress
    # zwracamy tez destination przez zmienna skryptowa:
    $script:lastDest = $dest
}

function Invoke-Burst {
    param([int]$Parallel, [int]$TotalRequests, [string]$Label)

    Write-Host "== $Label: $TotalRequests zadan, rownoleglosc $Parallel ==" -ForegroundColor Cyan
    $pool = [runspacefactory]::CreateRunspacePool(1, $Parallel)
    $pool.Open()
    $jobs = @()

    $sb = {
        param($Base, $Body, $Dest)
        try {
            $r = Invoke-WebRequest -Uri "$Base/api/v1/starports/$Dest/reservations" `
                 -Method POST -Body $Body -ContentType 'application/json' `
                 -TimeoutSec 5 -UseBasicParsing
            return [int]$r.StatusCode
        } catch {
            $resp = $_.Exception.Response
            if ($resp -and $resp.StatusCode) { return [int]$resp.StatusCode }
            return 0   # timeout / connection error
        }
    }

    for ($i = 0; $i -lt $TotalRequests; $i++) {
        $body = New-ReservationBody
        $ps = [powershell]::Create().AddScript($sb).AddArgument($Base).AddArgument($body).AddArgument($script:lastDest)
        $ps.RunspacePool = $pool
        $jobs += [pscustomobject]@{ PS = $ps; Handle = $ps.BeginInvoke() }
        if ($Mode -eq 'flood') { Start-Sleep -Milliseconds ([int](1000 / $FloodRatePerSecond)) }
    }

    $codes = @{}
    foreach ($j in $jobs) {
        $code = $j.PS.EndInvoke($j.Handle)[0]
        $j.PS.Dispose()
        $codes[$code] = 1 + ($codes[$code] | ForEach-Object { $_ })  # inkrement z domyslnym 0
        if (-not $codes[$code]) { $codes[$code] = 1 }
    }
    $pool.Close(); $pool.Dispose()

    Write-Host "-- Bilans $Label --" -ForegroundColor Yellow
    foreach ($k in ($codes.Keys | Sort-Object)) {
        $desc = switch ([int]$k) {
            201 { 'OK (rezerwacja + trasa)' }
            409 { 'DEGRADACJA (fallback: rate_limited / bulkhead_full / circuit_open)' }
            0   { 'timeout / brak polaczenia' }
            default { 'inny' }
        }
        Write-Host ("  {0}  x{1}  {2}" -f $k, $codes[$k], $desc)
    }
}

switch ($Mode) {
    'flood' {
        $total = $FloodRatePerSecond * $DurationSeconds
        Write-Host "FLOOD: ~$FloodRatePerSecond req/s przez $DurationSeconds s (limiter przepusci 10/s)" -ForegroundColor Magenta
        Write-Host "Patrz: panel 'RateLimiter — pozwolenia i odrzucenia' + kafelek fallbackow." -ForegroundColor DarkGray
        Invoke-Burst -Parallel 16 -TotalRequests $total -Label 'FLOOD'
    }
    'squeeze' {
        Write-Host "SQUEEZE: $SqueezeParallel rownoleglych klientow (gródź = 8 slotow)." -ForegroundColor Magenta
        Write-Host "WYMAGANE WCZESNIEJ: demo-resilience.ps1 -Chaos slow -SlowMs 600 (wolny planner!)" -ForegroundColor DarkYellow
        Write-Host "Patrz: panel 'Bulkhead — wolne sloty' (8 -> 0) + odrzucenia bulkhead_full." -ForegroundColor DarkGray
        # 4 fale po $SqueezeParallel zadan naraz — kazda fala trzyma sloty ~0,6 s
        for ($wave = 1; $wave -le 4; $wave++) {
            Invoke-Burst -Parallel $SqueezeParallel -TotalRequests $SqueezeParallel -Label "SQUEEZE fala $wave/4"
            Start-Sleep -Milliseconds 500
        }
        Write-Host "Po demie przywroc plannera: demo-resilience.ps1 -Chaos heal" -ForegroundColor DarkYellow
    }
}
