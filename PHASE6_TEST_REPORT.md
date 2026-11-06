# Phase 6 verification report

Date: 2026-11-01

## Automated verification

- Backend: `docker compose --profile test run --rm backend-tests` — 11 tests passed, 0 failures, 0 errors in isolated schema `phase5_test_v2`.
- Phase 6 integration scenario passed for trial provisioning, tenant-scoped usage and analytics, AI and agent quotas, DEMO checkout success/failure, signed webhook replay protection and signature rejection, invoice/history, cancellation/reactivation, platform authorization, and company suspension.
- Frontend: `npm test` — 3 tests passed; `npm run build` — production bundle succeeded; `node --check public/supportiq-widget.js` — passed.
- Production Docker images for backend and frontend built successfully.

## Live migration and retained data

Before migration, the public schema was at Flyway V5. A custom-format PostgreSQL backup was created at `backups/phase6-before-2026-11-01/supportiq-phase6-before.dump` and its archive table of contents was read successfully. Pre-migration counts: 2 companies, 3 knowledge documents, 5 conversations, 3 tickets, 2 customer orders, 2 legacy demo order rows, and 2 company refund policies.

The running Compose backend applied V6 successfully. Post-migration counts are unchanged: 2 companies, 3 knowledge documents, 5 conversations, 3 tickets, 2 customer orders, 2 legacy demo order rows, and 2 company refund policies. V6 provisioned 2 trial subscriptions for the existing companies. Flyway is at V6. The API health endpoint and public plans endpoint return HTTP 200; the public pricing, landing, and platform app routes return the frontend shell (HTTP 200). The named PostgreSQL volume was retained.

## Scope and caveats

Subscription payments remain DEMO only. This verifies the signed simulator and idempotent reconciliation, not real-money processing. A live provider adapter, credentials, tax configuration, deployment secrets, and live processor webhooks are not configured. No Super Admin account is provisioned by default; configure the environment values described in `BILLING_ARCHITECTURE.md` when a platform operator is needed.
