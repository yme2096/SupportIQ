$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $root
docker info *> $null
if ($LASTEXITCODE -ne 0) { throw 'Docker Desktop is not ready. Start Docker Desktop before stopping SupportIQ.' }
Write-Host 'Stopping SupportIQ services. PostgreSQL data and volumes will be preserved.'
docker compose --profile dedicated-worker stop postgres backend frontend worker
if ($LASTEXITCODE -ne 0) { throw 'One or more services did not stop cleanly. Inspect docker compose ps and logs.' }
Write-Host 'SupportIQ is stopped. The PostgreSQL volume was preserved.' -ForegroundColor Green
