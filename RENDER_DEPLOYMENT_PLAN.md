# Render deployment preparation plan

## Inspection findings

- **Application:** Spring Boot 3.5 / Java 21 APIs and a Vite/React SPA; public customer widget, hosted company chat, tenant-authenticated staff UI, STOMP WebSockets, Flyway migrations V1–V6, pgvector search, order/payment/refund handling, subscriptions, analytics, and Super Admin routes.
- **Data safety:** The local PostgreSQL database is separate from Render. Production must start with a new, empty managed Render Postgres instance. Never point the hosted service at the local database and never use the local volume as production storage.
- **Files:** Knowledge originals (`original_file`) and conversation attachments (`file_data`) are PostgreSQL `BYTEA`, not container filesystem files. They persist with the database, so an S3 migration is not needed for the initial Render deployment. Keep backup/restore and database growth monitoring in place; move blobs to object storage before volume growth or throughput makes database storage unsuitable.
- **Processing:** New documents are first stored as `UPLOADED`. The existing local path hands work to a process-local executor, which can lose queued work during a restart. Production preparation adds a separate Render worker that polls the durable document state, claims work with the existing conditional `UPLOADED` → `PROCESSING` update, and recovers stale processing attempts. The web service stops dispatching these jobs locally. The Render worker has no public Render URL or inbound traffic route; it runs the shared Spring application because the document-processing services are wired alongside the real-time workspace.
- **Runtime gaps:** Spring previously listened only to `SERVER_PORT`, the Docker image ran as root, and the frontend API URL was a local default. These are being corrected for Render's `PORT`, a non-root image, and Blueprint service references.
- **Security gaps/limits:** JWT, secure refresh cookie, origin checks, and tenant capability checks exist. WebSocket origins allow all sites by design for externally embedded customer widgets; customer STOMP subscriptions still require public company and conversation capability credentials, while staff require a short-lived JWT. Auth and order lookup have database rate-limit controls; a general edge/WAF rate limit for public chat and widget traffic is still recommended before high-traffic launch. File type/signature checks exist; malware scanning does not.
- **External services:** Gemini is optional and stays server-side. Subscription and order refund processors are DEMO only. Email delivery has no provider implementation. These must not be represented as production-connected integrations.

## Target topology

1. `supportiq-frontend`: Render Static Site in the global CDN, React/Vite build, SPA rewrites, widget asset included, and build-time API base URL referenced from the backend's Render URL.
2. `supportiq-backend`: Java 21 Docker Web Service, same Render region as Postgres and the worker, internal PostgreSQL URI converted to JDBC at startup, Flyway on startup, WebSocket `/ws`, HTTP health check, and strict frontend/CORS configuration.
3. `supportiq-db`: a new PostgreSQL 16 Render database, pgvector enabled by Flyway, private-only inbound allow list, and same region as backend/worker.
4. `supportiq-worker`: same backend Docker image in worker mode, no public Render URL, same database/Gemini settings, and durable polling for uploaded documents. It is declared in Render Blueprint, not created in this workspace.

The Blueprint uses a paid production-capable baseline for the backend, worker, and database. Applying it provisions new cloud resources and incurs charges; review the selected plans and Render's current pricing in the dashboard before syncing. No Render resources have been created from this repository.

## Execution sequence

1. Complete backend/container and worker runtime changes; keep Compose behavior unchanged by default.
2. Add a Render Blueprint with no secrets, no local database references, private Postgres access, same-region services, frontend/backend URL references, and manual deploy triggers.
3. Add deployment, environment, database, backup/recovery, and CI/CD runbooks.
4. Add PR CI and an explicitly approved, manual production deploy workflow. Use the Render API key stored as a protected GitHub Environment secret; never commit it.
5. Validate local tests, worker startup and durable queue configuration, Docker builds, YAML structure, V1–V6 isolated migrations, pgvector availability, frontend build/routes/widget assets, API health, and local data preservation.
6. Stop before Render Dashboard actions: the owner must select the Render workspace/region/plans, provide Gemini/email/payment credentials if available, approve paid services, connect the Git repository, and apply the Blueprint. Do not import local data into production as part of this preparation.

## Explicitly out of scope until configured by the owner

- Creating or paying for Render services, purchasing a domain, registering email delivery, enabling a real payment processor, or applying a production database migration.
- Migrating local company/customer data or file blobs to the new production database.
- Claiming provider credentials, production deployment, email delivery, real-money checkout, or Render health as verified.
