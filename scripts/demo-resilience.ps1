# =============================================================================
# DEMO: Resilience4j — odporność widzialna (starport-registry -> trade-route-planner)
# =============================================================================
#
# Towarzyszy talkowi "Działa != działa i ja o tym wiem".
# Generuje ruch, który REALNIE wywołuje circuit breaker + retry, i pozwala
# wywołać awarię zależności jednym przełącznikiem (docker stop/start planera).
#
# Wymaga: uruchomionego stacku (z roota repo: docker compose up -d --build).
#
# TYPOWY PRZEBIEG DEMA (drabina eskalacji — wymaga overlaya demo, patrz niżej):
#   1) Terminal A:  .\scripts\demo-resilience.ps1 -DurationMinutes 15
#      (leci stały, zdrowy ruch — dashboard zielony)
#   2) Terminal B — SLOW:     .\scripts\demo-resilience.ps1 -Chaos slow
#      -> latencja ~600ms, rośnie 'slow call rate', CB może otworzyć się po slow-call
#   3) Terminal B — PARTIAL:  .\scripts\demo-resilience.ps1 -Chaos partial
#      -> ~50% wywołań w timeout -> błąd -> retry skacze, failure rate na granicy progu
#   4) Terminal B — DOWN:     .\scripts\demo-resilience.ps1 -Chaos down
#      -> pełna awaria: po ~5 wywołaniach circuit OPEN, fail-fast 503 (Retry-After), alert FIRING
#   5) Terminal B — HEAL:     .\scripts\demo-resilience.ps1 -Chaos heal
#      -> po wait-duration-in-open-state=10s circuit -> half_open -> closed
#
# Tryby slow/partial/down/heal sterują Toxiproxy (API :8474) i WYMAGAJĄ stacku
# podniesionego z overlayem demo:
#   docker compose -f infra/docker/docker-compose.yml -f infra/docker/docker-compose.demo.yml up -d --build
#
# Tryby kill/restore (docker stop OBU replik) działają zawsze — twardy wariant bez toxiproxy.
#
# Dashboard:  http://localhost:3000  -> "Resilience4j — starport->trade-route"
# Prometheus alerty:  http://localhost:9090/alerts
# =============================================================================

[CmdletBinding(DefaultParameterSetName = "Traffic")]
param(
    [Parameter(ParameterSetName = "Traffic")]
    [string]$Base = "http://localhost:8080",

    [Parameter(ParameterSetName = "Traffic")]
    [int]$DurationMinutes = 15,

    [Parameter(ParameterSetName = "Traffic")]
    [double]$RatePerSecond = 4,

    [Parameter(ParameterSetName = "Chaos", Mandatory = $true)]
    [ValidateSet("kill", "restore", "status", "slow", "partial", "down", "heal")]
    [string]$Chaos,

    # API Toxiproxy (wystawione na host przez docker-compose.demo.yml). Tryby slow/partial/down/heal
    # działają TYLKO gdy stack podniesiony z overlayem demo (profil demo + kontener toxiproxy).
    [Parameter(ParameterSetName = "Chaos")]
    [string]$ToxiproxyApi = "http://localhost:8474",

    [Parameter(ParameterSetName = "Chaos")]
    [string]$ProxyName = "trade-route-planner",

    # Latencja (ms) dla trybu 'slow'. >500ms (slow-call-duration-threshold), <800ms (read-timeout)
    # => wywołania są "wolne ale udane" i podbijają slow_call_rate, zamiast lecieć w timeout.
    [Parameter(ParameterSetName = "Chaos")]
    [int]$SlowMs = 600,

    # Udział (0..1) wywołań trafionych w trybie 'partial'. 0.5 = co drugie wisi do timeoutu -> błąd -> retry.
    [Parameter(ParameterSetName = "Chaos")]
    [double]$PartialToxicity = 0.5
)

$ErrorActionPreference = "Stop"

# Repliki planera (docker-compose.apps.yml). Zatrzymanie OBU = pełna awaria zależności.
$PlannerReplicas = @("trade-route-planner-1", "trade-route-planner-2")

# Nazwy toxynów, które zakłada ten skrypt — trzymamy je stałe, żeby idempotentnie sprzątać.
$ToxLatency = "demo_latency"
$ToxTimeout = "demo_timeout"

function Invoke-Toxiproxy {
    param([string]$Method, [string]$Path, [object]$Body)
    $uri = "$ToxiproxyApi$Path"
    # UWAGA: Toxiproxy 2.x odrzuca (403 Forbidden) requesty z "przegladarkowym" User-Agentem
    # (ochrona przed DNS-rebinding na lokalne API). Domyslny UA Windows PowerShell zawiera
    # "Mozilla/5.0", wiec MUSIMY wymusic wlasny, nie-przegladarkowy User-Agent.
    $ua = "demo-resilience-ps1"
    try {
        if ($PSBoundParameters.ContainsKey('Body') -and $null -ne $Body) {
            $json = $Body | ConvertTo-Json -Compress
            return Invoke-RestMethod -Uri $uri -Method $Method -Body $json -ContentType "application/json" -TimeoutSec 5 -UserAgent $ua
        }
        return Invoke-RestMethod -Uri $uri -Method $Method -TimeoutSec 5 -UserAgent $ua
    } catch {
        throw "Toxiproxy API $Method $Path nie powiodło się: $($_.Exception.Message). Czy stack jest podniesiony z overlayem demo (docker-compose.demo.yml)?"
    }
}

function Clear-Toxics {
    # Usuwa toxyny założone przez ten skrypt (ignoruje brak — idempotentne).
    foreach ($t in @($ToxLatency, $ToxTimeout)) {
        try { Invoke-Toxiproxy -Method Delete -Path "/proxies/$ProxyName/toxics/$t" | Out-Null } catch {}
    }
}

function Enable-Proxy {
    param([bool]$Enabled)
    Invoke-Toxiproxy -Method Post -Path "/proxies/$ProxyName" -Body @{ enabled = $Enabled } | Out-Null
}

# --- Tryb CHAOS: steruje awarią zależności ----------------------------------
if ($PSCmdlet.ParameterSetName -eq "Chaos") {
    switch ($Chaos) {
        # ── Twarda awaria przez docker (działa zawsze, niezależnie od toxiproxy) ──
        "kill" {
            Write-Host "[CHAOS] Zatrzymuję trade-route-planner (obie repliki)..." -ForegroundColor Red
            docker stop $PlannerReplicas
            Write-Host "[CHAOS] Zależność ODCIĘTA. Patrz na dashboard: failure rate -> circuit OPEN." -ForegroundColor Red
        }
        "restore" {
            Write-Host "[CHAOS] Przywracam trade-route-planner..." -ForegroundColor Green
            docker start $PlannerReplicas
            Write-Host "[CHAOS] Repliki wstają. Po ~10s (wait-duration-in-open-state) circuit -> half_open -> closed." -ForegroundColor Green
        }
        "status" {
            docker ps --filter "name=trade-route-planner" --format "table {{.Names}}\t{{.Status}}"
            Write-Host "`n[TOXIPROXY] Stan proxy '$ProxyName':" -ForegroundColor Cyan
            try {
                $p = Invoke-Toxiproxy -Method Get -Path "/proxies/$ProxyName"
                $toxics = ($p.toxics | ForEach-Object { "$($_.type)($($_.name), toxicity=$($_.toxicity))" }) -join ", "
                Write-Host ("  enabled={0}  listen={1}  upstream={2}  toxyny=[{3}]" -f $p.enabled, $p.listen, $p.upstream, $toxics) -ForegroundColor Gray
            } catch { Write-Host "  (toxiproxy niedostępny — stack bez overlaya demo?)" -ForegroundColor DarkGray }
        }

        # ── Stopniowa degradacja przez Toxiproxy (wymaga overlaya demo) ──
        "slow" {
            # Wolne ale UDANE wywołania: latency pod read-timeoutem -> rośnie slow_call_rate.
            Write-Host "[CHAOS] Wstrzykuję latencję ${SlowMs}ms (downstream) na '$ProxyName'..." -ForegroundColor Yellow
            Clear-Toxics
            Enable-Proxy -Enabled $true
            Invoke-Toxiproxy -Method Post -Path "/proxies/$ProxyName/toxics" -Body @{
                name = $ToxLatency; type = "latency"; stream = "downstream"; toxicity = 1.0
                attributes = @{ latency = $SlowMs; jitter = 50 }
            } | Out-Null
            Write-Host "[CHAOS] Patrz na dashboard: 'slow call rate' rośnie, latencja wywołań ~${SlowMs}ms. CB może otworzyć się po slow-call." -ForegroundColor Yellow
        }
        "partial" {
            # Część wywołań wisi do read-timeoutu -> błąd -> retry. Failure rate skacze, retry rośnie.
            Write-Host "[CHAOS] Wstrzykuję timeout na $([int]($PartialToxicity*100))% wywołań na '$ProxyName'..." -ForegroundColor Yellow
            Clear-Toxics
            Enable-Proxy -Enabled $true
            Invoke-Toxiproxy -Method Post -Path "/proxies/$ProxyName/toxics" -Body @{
                name = $ToxTimeout; type = "timeout"; stream = "upstream"; toxicity = $PartialToxicity
                attributes = @{ timeout = 0 }   # 0 = trzymaj połączenie aż zerwie read-timeout klienta (800ms)
            } | Out-Null
            Write-Host "[CHAOS] Patrz: failure rate ~$([int]($PartialToxicity*100))%, retry (failed/successful_with_retry) rośnie. Na granicy progu CB." -ForegroundColor Yellow
        }
        "down" {
            # Pełna awaria bez zabijania kontenera: proxy odrzuca połączenia.
            Write-Host "[CHAOS] Wyłączam proxy '$ProxyName' (connection refused)..." -ForegroundColor Red
            Clear-Toxics
            Enable-Proxy -Enabled $false
            Write-Host "[CHAOS] Zależność ODCIĘTA. Po >=5 wywołaniach circuit -> OPEN, fail-fast 503 (Retry-After), alert FIRING." -ForegroundColor Red
        }
        "heal" {
            # Powrót do zdrowia: zdejmij toxyny, włącz proxy.
            Write-Host "[CHAOS] Sprzątam toxyny i włączam proxy '$ProxyName'..." -ForegroundColor Green
            Clear-Toxics
            Enable-Proxy -Enabled $true
            Write-Host "[CHAOS] Zależność zdrowa. Po ~10s (wait-duration-in-open-state) circuit -> half_open -> closed." -ForegroundColor Green
        }
    }
    return
}

# --- Tryb TRAFFIC: stały, zdrowy ruch wywołujący planowanie tras ------------

# Pary (ship, klasa) zgodne z seedem (V2__test_data.sql) — klasa statku MUSI
# zgadzać się z klasą w bazie, inaczej rezerwacja odpada na walidacji.
$Ships = @(
    @{ Code = "X-WING-001";          Class = "SCOUT" },
    @{ Code = "FALCON-002";          Class = "FREIGHTER" },
    @{ Code = "STAR-DESTROYER-003";  Class = "CRUISER" }
)
$Customers   = @("CUST-001", "CUST-002", "CUST-003", "CUST-004", "CUST-005")
# Realne kody starportów z seedu. origin != destination.
$Starports   = @("TATOO", "CORUS", "ALDER", "HOTH", "ENDOR", "NABOO", "DAGO", "BESPIN", "KAMINO", "MUSTAF")

$deadline    = (Get-Date).AddMinutes($DurationMinutes)
$delayMs     = [int](1000 / $RatePerSecond)
$rnd         = New-Object System.Random
$sent = 0; $ok = 0; $degraded = 0; $other = 0

Write-Host "[TRAFFIC] $RatePerSecond req/s przez $DurationMinutes min -> $Base (requestRoute=true)" -ForegroundColor Cyan
Write-Host "[TRAFFIC] Ctrl+C aby przerwać. Dashboard: http://localhost:3000`n" -ForegroundColor Cyan

while ((Get-Date) -lt $deadline) {
    $ship   = $Ships[$rnd.Next($Ships.Count)]
    $cust   = $Customers[$rnd.Next($Customers.Count)]
    $dest   = $Starports[$rnd.Next($Starports.Count)]
    do { $origin = $Starports[$rnd.Next($Starports.Count)] } while ($origin -eq $dest)

    # Okno czasowe daleko w przyszłości + losowy offset -> brak kolizji doków.
    $startAt = (Get-Date).ToUniversalTime().AddDays(1 + $rnd.Next(0, 3650)).AddHours($rnd.Next(0, 20))
    $endAt   = $startAt.AddHours(1)

    $body = @{
        customerCode = $cust
        shipCode     = $ship.Code
        shipClass    = $ship.Class
        startAt      = $startAt.ToString("yyyy-MM-ddTHH:mm:ssZ")
        endAt        = $endAt.ToString("yyyy-MM-ddTHH:mm:ssZ")
        requestRoute = $true
        originPortId = $origin
    } | ConvertTo-Json -Compress

    $uri = "$Base/api/v1/starports/$dest/reservations"
    $sent++
    try {
        $resp = Invoke-WebRequest -Uri $uri -Method Post -ContentType "application/json" `
                    -Body $body -TimeoutSec 10 -UseBasicParsing
        $code = [int]$resp.StatusCode
    } catch {
        # PS 5.1 nie ma -SkipHttpErrorCheck: 4xx/5xx (w tym 409/503 degraded) rzuca wyjatek.
        # Wyluskaj prawdziwy kod HTTP, zeby 409/503 trafil do $degraded, a nie do $other.
        $r = $_.Exception.Response
        if ($r -and $r.StatusCode) { $code = [int]$r.StatusCode } else { $code = -1 }
    }
    switch ($code) {
        201     { $ok++ }
        409     { $degraded++ }   # RoutePlanningRejected (odmowa domenowa 4xx plannera)
        503     { $degraded++ }   # RoutePlannerUnavailable (5xx/timeout/circuit open) + Retry-After
        default { $other++ }
    }

    if (($sent % 20) -eq 0) {
        Write-Host ("[{0:HH:mm:ss}] wyslane={1}  201(ok)={2}  409/503(degraded)={3}  inne={4}" -f (Get-Date), $sent, $ok, $degraded, $other) -ForegroundColor Gray
    }
    Start-Sleep -Milliseconds $delayMs
}

Write-Host "`n[TRAFFIC] Koniec. wyslane=$sent  201=$ok  409/503(degraded)=$degraded  inne=$other" -ForegroundColor Cyan
