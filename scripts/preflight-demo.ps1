# =============================================================================
# PRE-FLIGHT: sprawdza, czy demo Resilience4j zadziała PRZED wejściem na scenę.
# =============================================================================
#
# Twój bezpiecznik przeciw "demo effect". Uruchom 10 min przed talkiem.
#
#   .\scripts\preflight-demo.ps1            # szybki: endpointy + metryki + zdrowy ruch
#   .\scripts\preflight-demo.ps1 -Full      # pełny: realnie otwiera circuit i sprawdza alert, potem przywraca
#
# Kod wyjścia 0 = wszystko zielone. != 0 = coś wymaga uwagi (szczegóły w logu).
# =============================================================================

[CmdletBinding()]
param(
    [string]$Gateway     = "http://localhost:8080",
    [string]$Prometheus  = "http://localhost:9090",
    [string]$Grafana     = "http://localhost:3000",
    [string]$GrafanaUser = "admin",
    [string]$GrafanaPass = "admin",
    [string]$DashboardUid = "r4j-trade-route",
    [switch]$Full
)

$ErrorActionPreference = "Stop"
$script:fail = 0

function Ok($m)   { Write-Host "  [OK]   $m" -ForegroundColor Green }
function Warn($m) { Write-Host "  [WARN] $m" -ForegroundColor Yellow }
function Bad($m)  { Write-Host "  [FAIL] $m" -ForegroundColor Red; $script:fail++ }
function Section($m) { Write-Host "`n=== $m ===" -ForegroundColor Cyan }

function Get-PromScalar([string]$query) {
    $u = "$Prometheus/api/v1/query?query=" + [uri]::EscapeDataString($query)
    $r = Invoke-RestMethod -Uri $u -TimeoutSec 10
    if ($r.status -ne "success") { return $null }
    if ($r.data.result.Count -eq 0) { return $null }
    return [double]$r.data.result[0].value[1]
}

function Send-RouteRequest {
    $startAt = (Get-Date).ToUniversalTime().AddDays(1 + (Get-Random -Max 3000)).AddHours((Get-Random -Max 20))
    $body = @{
        customerCode = "CUST-00$(Get-Random -Min 1 -Max 6)"
        shipCode = "X-WING-001"; shipClass = "SCOUT"
        startAt = $startAt.ToString("yyyy-MM-ddTHH:mm:ssZ")
        endAt   = $startAt.AddHours(1).ToString("yyyy-MM-ddTHH:mm:ssZ")
        requestRoute = $true; originPortId = "TATOO"
    } | ConvertTo-Json -Compress
    try {
        $r = Invoke-WebRequest -Uri "$Gateway/api/v1/starports/CORUS/reservations" -Method Post `
                -ContentType "application/json" -Body $body -TimeoutSec 10 -UseBasicParsing
        return [int]$r.StatusCode
    } catch {
        # Windows PowerShell 5.1 nie ma -SkipHttpErrorCheck: 4xx/5xx rzuca wyjatek.
        # Wyluskaj prawdziwy kod HTTP z odpowiedzi; brak odpowiedzi = -1 (np. timeout/connection).
        $resp = $_.Exception.Response
        if ($resp -and $resp.StatusCode) { return [int]$resp.StatusCode }
        return -1
    }
}

# ── 1. Czy stack żyje ────────────────────────────────────────────────────────
Section "1. Dostępność usług"
try { Invoke-RestMethod "$Gateway/actuator/health" -TimeoutSec 5 | Out-Null; Ok "api-gateway zdrowy ($Gateway)" }
catch { Bad "api-gateway nie odpowiada na $Gateway — czy stack jest podniesiony?" }

try { (Invoke-RestMethod "$Prometheus/-/healthy" -TimeoutSec 5) | Out-Null; Ok "Prometheus zdrowy ($Prometheus)" }
catch { Bad "Prometheus nie odpowiada na $Prometheus" }

try { Invoke-RestMethod "$Grafana/api/health" -TimeoutSec 5 | Out-Null; Ok "Grafana zdrowa ($Grafana)" }
catch { Bad "Grafana nie odpowiada na $Grafana" }

# ── 2. Dashboard wczytany ────────────────────────────────────────────────────
Section "2. Dashboard demo"
try {
    $pair = "$($GrafanaUser):$($GrafanaPass)"
    $auth = "Basic " + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes($pair))
    $d = Invoke-RestMethod "$Grafana/api/dashboards/uid/$DashboardUid" -Headers @{ Authorization = $auth } -TimeoutSec 10
    Ok "Dashboard wczytany: '$($d.dashboard.title)' (uid=$DashboardUid)"
} catch {
    Bad "Dashboard uid=$DashboardUid nie znaleziony w Grafanie (provisioning nie wstał?)"
}

# ── 3. Metryki Resilience4j istnieją ─────────────────────────────────────────
Section "3. Metryki Resilience4j (job=starport-registry)"
$state = Get-PromScalar 'count(resilience4j_circuitbreaker_state{job="starport-registry", name="trade-route-planner"})'
if ($state) { Ok "resilience4j_circuitbreaker_state obecne ($state serii)" }
else { Bad "Brak resilience4j_circuitbreaker_state — Prometheus nie scrape'uje albo zła nazwa/label" }

$retry = Get-PromScalar 'count(resilience4j_retry_calls{job="starport-registry", name="trade-route-planner"})'
if ($retry) { Ok "resilience4j_retry_calls obecne ($retry serii)" } else { Warn "Brak resilience4j_retry_calls (pojawi się po pierwszym wywołaniu)" }

# ── 4. Reguły alertowe załadowane ────────────────────────────────────────────
Section "4. Reguły alertowe"
try {
    $rules = Invoke-RestMethod "$Prometheus/api/v1/rules" -TimeoutSec 10
    $names = $rules.data.groups.rules.name | Sort-Object -Unique
    foreach ($expected in @("TradeRoutePlannerCircuitOpen","TradeRoutePlannerHighFailureRate")) {
        if ($names -contains $expected) { Ok "Alert załadowany: $expected" }
        else { Bad "Alert NIE załadowany: $expected (sprawdź rule_files w prometheus.yml)" }
    }
} catch { Bad "Nie udało się pobrać reguł z Prometheusa" }

# ── 5. Zdrowa ścieżka działa (warm-up okna ślizgowego) ───────────────────────
Section "5. Zdrowy ruch (10 wywołań, warm-up)"
$codes = 1..10 | ForEach-Object { Send-RouteRequest }
$ok201 = ($codes | Where-Object { $_ -eq 201 }).Count
if ($ok201 -ge 8) { Ok "$ok201/10 wywołań 201 — ścieżka starport->planner działa" }
elseif ($ok201 -ge 1) { Warn "$ok201/10 wywołań 201 (reszta: $($codes -join ','))" }
else { Bad "0/10 wywołań 201 — ścieżka rezerwacji nie działa (kody: $($codes -join ','))" }

# ── 6. (opcjonalnie) Pełna weryfikacja: realnie otwórz circuit i sprawdź alert ─
if ($Full) {
    Section "6. Pełny chaos test (otwieram circuit i sprawdzam alert)"
    Warn "Zatrzymuję trade-route-planner — to wywoła awarię (i ją przywróci na końcu)."
    docker stop trade-route-planner-1 trade-route-planner-2 | Out-Null

    Write-Host "  Generuję ruch, czekam aż circuit przejdzie w OPEN..." -ForegroundColor Gray
    $opened = $false
    for ($i = 0; $i -lt 30; $i++) {
        1..3 | ForEach-Object { Send-RouteRequest | Out-Null }
        $openState = Get-PromScalar 'max(resilience4j_circuitbreaker_state{job="starport-registry", name="trade-route-planner", state="open"})'
        if ($openState -eq 1) { $opened = $true; break }
        Start-Sleep -Seconds 2
    }
    if ($opened) { Ok "Circuit przeszedł w OPEN — demo 'po' zadziała" }
    else { Bad "Circuit NIE otworzył się w ~60s — sprawdź konfigurację (minimum-number-of-calls / failure-rate-threshold)" }

    Write-Host "  Czekam na alert TradeRoutePlannerCircuitOpen (for: 20s)..." -ForegroundColor Gray
    $alerting = $false
    for ($i = 0; $i -lt 20; $i++) {
        try {
            $a = Invoke-RestMethod "$Prometheus/api/v1/alerts" -TimeoutSec 5
            $cb = $a.data.alerts | Where-Object { $_.labels.alertname -eq "TradeRoutePlannerCircuitOpen" }
            if ($cb -and ($cb.state -in @("pending","firing"))) {
                if ($cb.state -eq "firing") { $alerting = $true; break }
            }
        } catch {}
        Start-Sleep -Seconds 3
    }
    if ($alerting) { Ok "Alert TradeRoutePlannerCircuitOpen = FIRING" }
    else { Warn "Alert nie osiągnął FIRING w oknie testu (może być PENDING — sprawdź /alerts ręcznie)" }

    Section "Przywracam zależność"
    docker start trade-route-planner-1 trade-route-planner-2 | Out-Null
    Ok "trade-route-planner wraca — circuit zamknie się po ~10s (wait-duration-in-open-state)"
}

# ── Podsumowanie ─────────────────────────────────────────────────────────────
Section "PODSUMOWANIE"
if ($script:fail -eq 0) {
    Write-Host "  Wszystko zielone. Demo gotowe do scenicznego użycia." -ForegroundColor Green
    exit 0
} else {
    Write-Host "  $($script:fail) problem(ów) wymaga uwagi przed wejściem na scenę." -ForegroundColor Red
    exit 1
}
