$ErrorActionPreference = 'Continue'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $root
docker info *> $null
if ($LASTEXITCODE -ne 0) { Write-Host 'Docker Desktop is not ready.' -ForegroundColor Red; exit 1 }
docker compose --profile dedicated-worker ps

$backendPort = (docker compose port backend 8080 | Select-Object -First 1) -replace '^.*:', ''
$frontendPort = (docker compose port frontend 80 | Select-Object -First 1) -replace '^.*:', ''
$failed = $false
if ($backendPort) {
    try {
        $health = Invoke-RestMethod -Uri "http://localhost:$backendPort/api/v1/health" -TimeoutSec 5
        if ($health.status -eq 'UP' -and $health.database -eq 'UP') { Write-Host "PASS backend and database: UP ($backendPort)" -ForegroundColor Green }
        else { Write-Host "FAIL backend health: $($health | ConvertTo-Json -Compress)" -ForegroundColor Red; $failed = $true }
    } catch { Write-Host "FAIL backend health: $($_.Exception.Message)" -ForegroundColor Red; $failed = $true }
} else { Write-Host 'FAIL backend port is not published.' -ForegroundColor Red; $failed = $true }

if ($frontendPort) {
    foreach ($route in @('/', '/supportiq-widget.js', '/chat/shopsmart')) {
        try { $response=Invoke-WebRequest -Uri "http://localhost:$frontendPort$route" -TimeoutSec 5 -UseBasicParsing; if ($response.StatusCode -eq 200) { Write-Host "PASS frontend $route" -ForegroundColor Green } else { Write-Host "FAIL frontend $route returned $($response.StatusCode)" -ForegroundColor Red; $failed=$true } }
        catch { Write-Host "FAIL frontend $route : $($_.Exception.Message)" -ForegroundColor Red; $failed=$true }
    }
} else { Write-Host 'FAIL frontend port is not published.' -ForegroundColor Red; $failed=$true }

$workerState = docker compose --profile dedicated-worker ps --status running --services worker 2>$null
if ($workerState) { Write-Host 'PASS dedicated worker container is running.' -ForegroundColor Green }
else { Write-Host 'INFO no dedicated worker container is running; default local mode processes jobs in the backend.' -ForegroundColor Yellow }

if ($failed) {
    Write-Host 'Recent service logs:' -ForegroundColor Yellow
    docker compose --profile dedicated-worker logs --tail 60 postgres backend frontend worker
    exit 1
}
Write-Host 'Local service checks passed.' -ForegroundColor Green
