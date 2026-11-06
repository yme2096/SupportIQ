# Deployment Guide

## Current state

SupportIQ has local Docker Compose configuration and a Render Blueprint. **Nothing has been deployed.** The Blueprint provisions a new paid database and services; review current prices and service configuration in the Render dashboard before applying it. No local database, customer data, or local backup should be copied to production by default.

## Local Docker Compose

```powershell
Set-Location '<path-to-cloned-SupportIQ>'
docker compose up -d postgres backend frontend
Invoke-RestMethod http://localhost:8080/api/v1/health
```

Open `http://localhost:5173`. For default local processing, keep `AI_PROCESSING_DISPATCH_LOCALLY=true`. To enable the dedicated worker, set it to false for the API and run `docker compose --profile dedicated-worker up -d worker`. Stop with `docker compose stop postgres backend frontend worker`; never use `down -v` if retaining data matters.

## Render preparation

1. Create a private Git repository and connect it to Render. Review the project-root/ignore audit and confirm private repository permissions first; this workspace has no configured project remote.
2. Select the project `render.yaml` Blueprint. Review the PostgreSQL, API, worker, and static frontend plan sizes and current cost before applying.
3. Provision a **new empty** PostgreSQL 16 database in the same region as the backend and worker. Enable pgvector through the Flyway migrations. Do not point production at local Compose or import local regression data.
4. Set the exact HTTPS frontend origin in `FRONTEND_ORIGIN` and `FRONTEND_URL`. Use the generated independent JWT and webhook secrets, `AUTH_COOKIE_SECURE=true`, `LOCAL_AUTH_LINK_EXPOSURE=false`, and `SEED_DEMO_ADMIN=false`.
5. Decide whether to configure Gemini. Keep both payment settings in DEMO mode unless a provider adapter has been separately implemented and reviewed. Email delivery is not implemented; invitations and reset links are returned through local flows rather than emailed.
6. Confirm the backend and worker use the managed DB connection string, bounded DB pools, and `AI_PROCESSING_DISPATCH_LOCALLY=false`; only the worker has `AI_WORKER_ENABLED=true`.
7. Build the static frontend with the public backend URL and verify SPA routes and `/supportiq-widget.js`. Confirm HTTPS REST and WSS, CORS, health, and cross-origin widget behavior from the deployed frontend.
8. Verify backups, restore procedure, storage capacity (original documents currently live in PostgreSQL BYTEA), logging, and alerting before importing any data or allowing production traffic.

The worker uses the same backend image with the `worker` command. Render has not been provisioned or contacted. External credentials, costs, live payments, domain registration, and deployment require separate configuration/approval and are not performed by this guide.

## Deployment verification checklist

- [ ] Database is new, empty, private, backed up, and supports pgvector.
- [ ] V1–V6 migrations finish once and validate on each restart.
- [ ] API health returns `status=UP` and `database=UP`.
- [ ] Frontend root, `/pricing`, `/platform`, hosted chat, order routes, and widget JavaScript load.
- [ ] The widget uses the correct API base and company public ID; cross-origin public routes work without staff credentials.
- [ ] Staff auth and WebSocket use the exact frontend origin and HTTPS/WSS.
- [ ] Worker claims and completes an isolated queued document; restart recovery and attempt limits are observed.
- [ ] Object/document storage and backup/restore strategy match expected file sizes and retention.
- [ ] DEMO billing remains enabled until a real provider is deliberately configured.
- [ ] No secret, local dump, private test schema, or local customer data entered the repository or deployment.

Further detail: [RENDER_DEPLOYMENT_GUIDE.md](RENDER_DEPLOYMENT_GUIDE.md), [PRODUCTION_ENVIRONMENT_VARIABLES.md](PRODUCTION_ENVIRONMENT_VARIABLES.md), and [BACKUP_AND_RECOVERY.md](BACKUP_AND_RECOVERY.md).
