# SupportIQ local setup (Windows 11)

## Requirements

- Docker Desktop for Windows with Linux containers and Docker Compose v2.
- Network access to Docker Hub and Maven Central for first builds.
- Optional Node.js 20+ if running frontend tests outside Docker.
- 16 GB RAM is sufficient for the included PostgreSQL, Spring Boot, Nginx, and test workloads. No GPU or local model is required.

## One-click commands

From the repository root, double-click `START_SUPPORTIQ.bat`; use `CHECK_SUPPORTIQ.bat` to inspect health, and `STOP_SUPPORTIQ.bat` to stop the services. The stop script uses `docker compose stop` and preserves PostgreSQL data. It never removes volumes.

## Manual startup

```powershell
Set-Location '<path-to-cloned-SupportIQ>'
if (!(Test-Path .env)) { Copy-Item .env.example .env } # optional; preserves any existing local settings
docker compose up -d --build postgres backend frontend
docker compose ps
Invoke-RestMethod http://localhost:8080/api/v1/health
```

Compose defaults permit running without `.env`; keep any existing `.env` instead of replacing it. Open:

- Frontend: `http://localhost:5173`
- Backend health: `http://localhost:8080/api/v1/health`
- Widget JavaScript: `http://localhost:5173/supportiq-widget.js`
- Hosted company support pages: `http://localhost:5173/chat/{company-slug}`
- Pricing: `http://localhost:5173/pricing`
- OpenAPI UI: `http://localhost:8080/swagger-ui/index.html`

The frontend image is served by Nginx. The local profile uses `DB_URL=jdbc:postgresql://postgres:5432/supportiq`, local HTTP cookie settings and DEMO billing. The persistent volume is `supportiq_mvp_supportiq_pgdata`.

## Local AI and documents

Without Gemini keys, SupportIQ uses deterministic local hash embeddings for document indexing and retrieval. When a retrieved approved passage contains a sufficiently matching sentence, the assistant returns a short extractive answer and its source; otherwise it returns the configured safe response and recommends human handoff. This fallback is intentionally lightweight and does not provide broad semantic understanding or generated answers. Set `GEMINI_API_KEY` to enable Gemini answer generation and embeddings, or set `GEMINI_EMBEDDING_API_KEY` separately for Gemini embeddings. The company settings status shows whether those services are configured.

The default local mode processes document jobs inside the backend. For an optional dedicated worker, put `AI_PROCESSING_DISPATCH_LOCALLY=false` in `.env`, then run `docker compose --profile dedicated-worker up -d --build`. The worker uses the same backend image and polls the durable UPLOADED queue. Do not enable both local dispatch and a dedicated worker if you want a single processor. `STOP_SUPPORTIQ.bat` safely stops both modes.

## Automated tests and builds

```powershell
docker compose --profile test run --rm backend-tests
Push-Location frontend
npm ci
npm test -- --run
npm run build
npm audit --audit-level=high
Pop-Location
docker compose build backend worker frontend
```

Integration tests run in the isolated `phase7_test` schema. Keep test configuration pointed at that schema; never reset the `public` schema.

## Stop safely

```powershell
docker compose stop postgres backend frontend worker
```

This stops containers and keeps their images and the named PostgreSQL volume. Never run `docker compose down -v`, `docker volume prune`, or database reset commands against this project.

## Optional Super Admin bootstrap

Set both `SUPER_ADMIN_EMAIL` and `SUPER_ADMIN_PASSWORD` in the ignored local `.env`; the password must be at least 16 characters. Restart the backend to seed a global platform account. Do not reuse a customer account or commit `.env`. The seeder refuses to elevate an existing non-platform account.
