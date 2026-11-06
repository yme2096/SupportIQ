# Phase 7 — Render preparation test report

Date: 2026-11-02

## Results

| Check | Result | Notes |
|---|---|---|
| Backend integration suite | Passed | 11 tests, 0 failures/errors; ran through Compose against isolated PostgreSQL schema `phase7_test`. |
| Fresh Flyway migration check | Passed | Isolated schema applied V1 through V6; pgvector extension was available. |
| Frontend tests | Passed | 3 tests, 0 failures. |
| Frontend production build | Passed | Vite build generated `frontend/dist`. |
| Widget JavaScript syntax | Passed | `node --check public/supportiq-widget.js`. |
| Dependency audit | Passed | `npm audit --audit-level=high`: 0 vulnerabilities reported. |
| Docker images | Passed | Java 21 backend image and static frontend image both built successfully. Backend image runs as UID 10001. |
| Worker startup smoke | Passed | Worker mode started against the isolated `phase7_test` schema, connected with the Render-style Postgres URI conversion, and reached application startup; container was stopped cleanly. |
| Local API health | Passed | `/api/v1/health` returned `status=UP` and `database=UP`. |
| Static container route smoke | Passed | `/`, `/pricing`, `/platform`, `/chat/shopsmart`, `/orders/shopsmart`, and `/supportiq-widget.js` each returned HTTP 200. |
| YAML and Compose configuration | Passed | Prettier parsed/formatted the Render and GitHub workflow YAML; `docker compose --profile test config --quiet` passed. |
| Local data preservation | Passed | Existing public schema counts remained 2 companies, 3 documents, 5 conversations, 3 tickets, 2 company orders, 2 legacy demo orders, and 2 refund policies. |
| Render Blueprint and cloud deployment | Not run | No Render credentials, paid resources, or live services were used. Blueprint still needs dashboard validation at first sync. |
| Production provider checks | Not run | Gemini, email, object storage, and live payment providers were not configured or represented as verified. |

## Scope and isolation

The integration suite uses a dedicated `phase7_test` schema on the local Compose PostgreSQL service. It applies schema migrations in that schema only; the existing local `supportiq` application schema and named volume are not reset or imported. No production database exists yet. The local development database was not migrated by this phase.

## Coverage inherited from the suite

The backend suite covers authentication, two-company tenant separation, live customer/agent WebSocket exchange, message idempotency and private notes, assignments, lifecycle, feedback, attachment ownership, and document ingestion/chunking. Existing Phase 5 and Phase 6 reports document order/refund, billing lifecycle, quota, analytics, and platform-admin checks.

## Remaining checks before first production use

- Apply the Blueprint only after reviewing service plans and price in the Render dashboard.
- Verify Render-assigned frontend URL values, database connectivity, startup migrations, health endpoint, SPA routes, widget asset, and a real `wss://` connection after provisioning.
- Configure Gemini credentials if document embeddings and generated responses are needed; no key was available locally.
- Configure a separate email provider integration before promising email-based invitations or password resets; it is not implemented.
- Keep payment and subscription processing in DEMO mode. Live gateway integration and end-to-end payment verification are not implemented.
- Add an edge/WAF rate policy for public widget/chat traffic before high-volume public exposure.
