$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $root

function Fail-WithLogs([string]$message) {
    Write-Host "ERROR: $message" -ForegroundColor Red
    docker compose --profile dedicated-worker logs --tail 80 postgres backend frontend worker
    exit 1
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Docker CLI is unavailable. Install/start Docker Desktop first.' }
docker info *> $null
if ($LASTEXITCODE -ne 0) { throw 'Docker Desktop is not ready. Start Docker Desktop, wait until it says Running, then retry.' }
docker compose config --quiet
if ($LASTEXITCODE -ne 0) { throw 'Compose configuration is invalid. Check .env and docker-compose.yml.' }

Write-Host 'Starting SupportIQ without removing or resetting the PostgreSQL volume...'
docker compose up -d --build postgres backend frontend
if ($LASTEXITCODE -ne 0) { Fail-WithLogs 'Docker Compose could not start the local services.' }

$deadline = (Get-Date).AddSeconds(120)
do {
    $dbHealth = docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' supportiq_mvp-postgres-1 2>$null
    if ($dbHealth -eq 'healthy') { break }
    Start-Sleep -Seconds 2
} while ((Get-Date) -lt $deadline)
if ($dbHealth -ne 'healthy') { Fail-WithLogs 'PostgreSQL did not become healthy within 120 seconds.' }

$backendPort = (docker compose port backend 8080 | Select-Object -First 1) -replace '^.*:', ''
$frontendPort = (docker compose port frontend 80 | Select-Object -First 1) -replace '^.*:', ''
if (-not $backendPort -or -not $frontendPort) { Fail-WithLogs 'Could not resolve published local ports from Compose.' }
$apiUrl = "http://localhost:$backendPort/api/v1/health"
$frontUrl = "http://localhost:$frontendPort/"
$widgetUrl = "http://localhost:$frontendPort/supportiq-widget.js"

$deadline = (Get-Date).AddSeconds(120)
$apiReady = $false
do {
    try { $health = Invoke-RestMethod -Uri $apiUrl -TimeoutSec 4; if ($health.status -eq 'UP' -and $health.database -eq 'UP') { $apiReady = $true; break } } catch { }
    Start-Sleep -Seconds 2
} while ((Get-Date) -lt $deadline)
if (-not $apiReady) { Fail-WithLogs 'Backend/database health did not become UP within 120 seconds.' }

$frontReady = $false
foreach ($url in @($frontUrl, $widgetUrl)) {
    try { $response = Invoke-WebRequest -Uri $url -TimeoutSec 8 -UseBasicParsing; if ($response.StatusCode -ne 200) { Fail-WithLogs "Local route failed: $url" } }
    catch { Fail-WithLogs "Local route failed: $url" }
}
$frontReady = $true

Write-Host 'SupportIQ is ready.' -ForegroundColor Green
Write-Host "Frontend: $frontUrl"
Write-Host "Backend health: $apiUrl"
Write-Host "Website widget asset: $widgetUrl"
Write-Host 'The default local document processor runs inside the backend. Use the dedicated-worker Compose profile only after setting AI_PROCESSING_DISPATCH_LOCALLY=false in .env.'
