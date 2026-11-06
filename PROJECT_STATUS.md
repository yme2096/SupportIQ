# SupportIQ project status

Updated 2026-11-06 (Gemini integration verification).

## Completed

- Company registration, authentication, role-based access, workspace onboarding, agent invitations, public chat, and tenant-scoped APIs.
- Company-specific public widget IDs, install snippets, customizable name/logo/welcome/color, settings preview, hosted customer chat, and React/plain HTML/CMS installation guidance.
- Additive V3 schema for document lifecycle, versions, original uploads, pgvector chunks, full-text fallback, source references, and company AI settings.
- PDF, DOCX, TXT, and Markdown validation/extraction; section/page-aware chunking; asynchronous processing; duplicate detection; upload status/error and retry/reprocess/archive APIs.
- Gemini embedding provider, 768-dimensional vectors, pgvector cosine retrieval, tenant-filtered top-K and threshold, grounded answer generation, citations, safe fallback, and handoff reuse across workspace, widget, and hosted chat.
- Functional company knowledge manager with upload progress, search/status filter, extracted preview, processing details, retry/reprocess, and archive controls.
- Company AI configuration UI for provider/model, language/tone, chunk count, threshold, unknown-answer handoff, and fallback text. Provider credentials remain server-side.
- Pre-migration source/database backup in `backups/phase3-before-2026-11-01/`.
- V4 real-time agent workspace: authenticated STOMP chat, capability-scoped customer sessions, message idempotency/read state, presence, assignment strategies, internal notes, notifications, SLA targets, ticket lifecycle/history, feedback, and tenant-scoped attachments.
- Company-specific SLA and assignment controls, responsive queue/thread workspace, hosted customer chat, and embedded widget with realtime reconnect/poll fallback.
- Pre-V4 database backup in `backups/phase4-before-2026-11-01/`.
- V5 company-owned orders and refunds, with the legacy demo order table retained and its records migrated as demo orders.
- Customer order verification and short-lived order-scoped access, order history/status portal, widget order lookup/refund request flow, and AI order answers restricted to verified order context.
- Company order management and tracking dashboard, refund policy controls, eligibility and balance checks, agent review, Company Admin approval, and audit history.
- DEMO-only payment provider with signed, idempotent webhook reconciliation; initiation does not imply credit and no live payment processor is configured.
- Pre-V5 database backup in `backups/phase5-before-2026-11-01/`.
- V6 company subscription plans and trials, lifecycle and grace handling, invoice/payment/history records, usage metering, company analytics/CSV, and protected platform admin APIs and workspaces. Subscription billing remains DEMO only.
- Server-side plan limits for AI conversations, agent invitations/reactivation, documents, and stored bytes; suspended and expired workspaces retain data while billing recovery stays available.
- Company billing and usage UI, public pricing page, date-filtered analytics dashboard, and a separate platform operator console. The operator console is provisioned only through explicit environment configuration.
- No-key embedding fallback uses deterministic 768-dimensional local hashing for indexing and retrieval. Without Gemini, matching retrieved passages produce a short extractive answer; unknown questions keep the configured safe fallback and handoff. Company Settings reports current server AI configuration.
- Optional dedicated worker service is available through the Compose `dedicated-worker` profile. Default local processing remains in the backend.
- Windows `START_SUPPORTIQ.bat`, `CHECK_SUPPORTIQ.bat`, and `STOP_SUPPORTIQ.bat` wrap startup, health checks, and a volume-preserving stop.

## Verification

- Gemini integration uses `gemini-3.8-flash` generation and `gemini-embedding-2` embeddings requested at 768 dimensions to match the existing pgvector schema. Requests have explicit connect/read timeouts, bounded transient retries and sanitized error logging. Vector retrieval filters to the active embedding provider/model so vectors from different spaces are never compared; legacy documents remain untouched and retain full-text fallback until explicitly reprocessed. 12 mocked Gemini API/provider checks and a mismatched-vector isolation regression pass in a 27/27 backend suite. No local Gemini key is configured, so live provider checks remain BLOCKED; public documents/vectors were not changed. See [FINAL_LOCAL_READINESS_REPORT.md](FINAL_LOCAL_READINESS_REPORT.md).

- 2026-11-06 backend suite after Gemini hardening: 27/27 passed in isolated `phase7_test`; Surefire resolved; backend and worker images rebuilt successfully. The current production frontend and local Compose stack are healthy. See [FINAL_LOCAL_READINESS_REPORT.md](FINAL_LOCAL_READINESS_REPORT.md) for checks and remaining limits.
- Isolated worker test document reached READY with one processed chunk; retrieval cited the matching text. No-key fallback and cross-company isolation passed. The public database still has 2 companies, 3 READY documents, 3 knowledge chunks, 5 conversations, 11 messages, 3 tickets, 2 company orders, 2 demo orders, 0 refunds, 2 subscriptions, and 2 refund policies; the existing Docker volume remains mounted.
- Signed-in browser walkthrough passed for isolated Company Admin, Agent, Customer, and Super Admin views, including agent reply/private note and agent denial from the Super Admin route. Live Gemini provider checks remain blocked because no API key is configured. The Windows stop/start scripts both completed successfully and retained the existing data volume.

- Frontend (current final run): 5 tests passed; Vite production build succeeded; `npm audit --audit-level=high` found zero vulnerabilities; embedded widget JavaScript syntax check passed.
- Backend: 9 tests passed (5 authentication/multi-tenant/realtime/attachment scenarios and 4 ingestion/chunking tests), 0 failures/errors. Coverage includes two-company document/vector retrieval and conversation isolation, live customer-agent WebSocket exchange, duplicate-message idempotency, private notes, assignment, lifecycle, feedback, and attachment tenant access.
- Phase 5 backend integration suite: 10 tests passed, 0 failures/errors in isolated schema `phase5_test_v2`; includes order ownership/customer capability, refund policy and approval, DEMO status handling, and webhook signature/idempotency/reconciliation scenarios. Frontend: 3 tests passed, production build and widget syntax check succeeded.
- Phase 6 backend integration suite: 11 tests passed, 0 failures/errors in isolated schema `phase5_test_v2`; covers billing lifecycle/quotas and platform company isolation alongside the existing multi-tenant/realtime and ingestion coverage. Frontend: 3 tests passed; production build and widget syntax check succeeded.
- Isolated test-schema and live Flyway migration V4 succeeded. pgvector remains installed and available to the live application's search path.
- Isolated and live Flyway migration V5 succeeded. The pre-migration database backup was verified as a PostgreSQL custom-format dump. Live V5 created 2 customer orders from the 2 legacy demo rows, retained both legacy rows, and created 2 default company refund policies.
- Isolated and live Flyway V6 migration succeeded. A custom-format pre-V6 backup was verified at `backups/phase6-before-2026-11-01/`. Before/after counts match: 2 companies, 3 documents, 5 conversations, 3 tickets, 2 company orders, 2 legacy demo orders, and 2 refund policies; V6 created 2 trial subscriptions. Health, public plans, landing, pricing, and platform SPA routes return successfully. See [PHASE6_TEST_REPORT.md](PHASE6_TEST_REPORT.md) and [BILLING_ARCHITECTURE.md](BILLING_ARCHITECTURE.md).
- Docker Compose backend and frontend are running; the API health endpoint, frontend home, embedded widget JavaScript, and direct customer order route return successfully. The existing PostgreSQL volume was kept. Before and after V5 there are 2 companies, 3 knowledge documents, 5 conversations, and 3 tickets. Details are in [PHASE4_TEST_REPORT.md](PHASE4_TEST_REPORT.md) and [PHASE5_TEST_REPORT.md](PHASE5_TEST_REPORT.md).

## Configuration and limitations

- No Gemini key is configured locally. Live Gemini embeddings and generated answers require `GEMINI_API_KEY`, or a separate `GEMINI_EMBEDDING_API_KEY` for embeddings. Without a key, documents are processed with local deterministic embeddings and matching sources receive an extractive answer; unsupported questions use the configured safe fallback/handoff.
- Image-only scanned PDFs are not OCR processed. Malware scanning and external file storage are not included.
- Email verification and invitation/reset email delivery, production TLS/secrets/monitoring, trusted payment integrations, and external penetration testing remain follow-up work. Local browser end-to-end coverage is recorded in the current readiness report.
- Subscription billing and the V5 order payment provider are DEMO only. Production processor adapters, credentials, and live webhook setup are not configured. No Super Admin account is enabled unless both explicit environment values are provided. Email/SMS order status notifications are not configured. See [PAYMENT_INTEGRATION.md](PAYMENT_INTEGRATION.md), [BILLING_ARCHITECTURE.md](BILLING_ARCHITECTURE.md), and [PHASE5_TEST_REPORT.md](PHASE5_TEST_REPORT.md).

This project remains an MVP. See [SECURITY_ARCHITECTURE.md](SECURITY_ARCHITECTURE.md), [AI_RAG_ARCHITECTURE.md](AI_RAG_ARCHITECTURE.md), [PHASE3_TEST_REPORT.md](PHASE3_TEST_REPORT.md), and [PHASE4_TEST_REPORT.md](PHASE4_TEST_REPORT.md).

## Phase 7 — Render production preparation

- Added `render.yaml` for a static frontend, Java 21 Docker API, private PostgreSQL 16 database, and background document worker. The API binds to Render `PORT`; Docker runs as a non-root user; production settings use graceful shutdown and bounded database pools.
- Production document processing uses durable database queue state. The worker claims uploaded rows idempotently and recovers stale processing attempts. Knowledge originals and chat attachments remain database-backed `BYTEA`, so no ephemeral container disk dependency was introduced.
- Added PR and manually approved deployment GitHub Actions, with isolated backend migrations/tests, frontend checks, Docker builds, and post-deploy health/route smoke checks.
- Added Render deployment, environment, database, and backup/recovery guides. Render has not been provisioned or deployed; paid resources, domain purchases, external credentials, production data imports, and live payment processing remain untouched.
- Verification: backend 11/11 tests passed in isolated `phase7_test`; frontend 3/3 passed; Vite production build passed; widget JavaScript syntax check passed; high-severity npm audit reported zero vulnerabilities. Backend and frontend Docker builds and worker-mode startup passed. Local public schema counts remain unchanged (2 companies, 3 documents, 5 conversations, 3 tickets, 2 company orders, 2 legacy demo orders, 2 refund policies). Details are in [PHASE7_TEST_REPORT.md](PHASE7_TEST_REPORT.md).
- The automated suite and worker smoke test used the isolated `phase7_test` schema. The existing public schema counts are unchanged; local company/customer data and Compose volume were preserved.
