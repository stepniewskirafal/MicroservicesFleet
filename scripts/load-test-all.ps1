# ------------------------------------------------------------------
# load-test-all.ps1  –  Launch 5 load-test scripts in parallel
#
# Each script gets a unique ScriptId (1-5) with non-overlapping
# time windows, so all 500 requests (5 x 100) can run concurrently
# without date collisions.
#
# Usage:  powershell -ExecutionPolicy Bypass -File scripts\load-test-all.ps1
#         powershell -ExecutionPolicy Bypass -File scripts\load-test-all.ps1 -Base http://localhost:8084
#         powershell -ExecutionPolicy Bypass -File scripts\load-test-all.ps1 -WithRoutes
# ------------------------------------------------------------------
param(
    [string]$Base = "http://localhost:8081",
    [switch]$WithRoutes
)

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$loadTest  = Join-Path $scriptDir "load-test.ps1"

Write-Host "============================================================"
Write-Host " Launching 5 load-test scripts in parallel"
Write-Host " Target: $Base"
Write-Host " Routes: $(if ($WithRoutes) { 'ENABLED' } else { 'DISABLED' })"
Write-Host ""
Write-Host " Time bands (no overlap between scripts):"
Write-Host "   Script 1: offsets 1000-1400h   Script 2: offsets 2000-2400h"
Write-Host "   Script 3: offsets 3000-3400h   Script 4: offsets 4000-4400h"
Write-Host "   Script 5: offsets 5000-5400h"
Write-Host "============================================================"
Write-Host ""

$jobs = @()

for ($id = 1; $id -le 5; $id++) {
    $routeFlag = if ($WithRoutes) { "-WithRoutes" } else { "" }
    $jobs += Start-Job -Name "LoadTest-$id" -ScriptBlock {
        param($script, $base, $scriptId, $routeFlag)
        if ($routeFlag -eq "-WithRoutes") {
            & $script -Base $base -ScriptId $scriptId -WithRoutes
        } else {
            & $script -Base $base -ScriptId $scriptId
        }
    } -ArgumentList $loadTest, $Base, $id, $routeFlag
    Write-Host "[LAUNCHER] Started Script $id (Job: $($jobs[-1].Id))"
}

Write-Host ""
Write-Host "[LAUNCHER] Waiting for all 5 scripts to finish..."
Write-Host ""

# Wait and collect results
$jobs | Wait-Job | Out-Null

$totalSuccess = 0
$totalClientErr = 0
$totalOther = 0
$totalMismatch = 0

foreach ($job in $jobs) {
    $output = Receive-Job -Job $job
    Write-Host "------------------------------------------------------------"
    Write-Host "[RESULT] $($job.Name):"
    Write-Host "------------------------------------------------------------"
    $output | ForEach-Object { Write-Host $_ }

    # Parse summary line from output
    $summaryLine = $output | Where-Object { $_ -match "Done!" } | Select-Object -First 1
    if ($summaryLine -match "success=(\d+)\s+client-error=(\d+)\s+other=(\d+)") {
        $totalSuccess   += [int]$Matches[1]
        $totalClientErr += [int]$Matches[2]
        $totalOther     += [int]$Matches[3]
    }
    $mismatchLine = $output | Where-Object { $_ -match "Mismatched" } | Select-Object -First 1
    if ($mismatchLine -match "Mismatched expectations:\s+(\d+)") {
        $totalMismatch += [int]$Matches[1]
    }

    Remove-Job -Job $job
}

$total = $totalSuccess + $totalClientErr + $totalOther

Write-Host ""
Write-Host "============================================================"
Write-Host " AGGREGATE RESULTS (5 scripts x 100 requests = 500 total)"
Write-Host "============================================================"
Write-Host (" success={0}  client-error={1}  other={2}  total={3}" -f $totalSuccess, $totalClientErr, $totalOther, $total)
Write-Host (" Mismatched expectations: {0}" -f $totalMismatch)
Write-Host ""
Write-Host " Expected: ~250 success (good requests) + ~250 client-error (bad requests)"
Write-Host "============================================================"
