# Phase 5 test report

Date: 2026-11-01

## Automated verification

- Backend integration suite: 10 tests passed, 0 failures, 0 errors using the isolated PostgreSQL schema `phase5_test_v2`; Flyway V1 through V5 applied successfully there. The suite includes authentication/tenant access scenarios and document ingestion scenarios, plus order/refund assertions for tenant ownership, customer verification, refund idempotency and balance limits, Company Admin approval, DEMO initiation, webhook signature and replay handling, partial/full credit reconciliation, failed provider events, and unverified AI order requests.
- Frontend: 3 tests passed; Vite production build succeeded; `node --check public/supportiq-widget.js` succeeded.
- No real payment was initiated. The provider exercised by the tests is the local DEMO provider.
- Live smoke checks: Flyway public schema advanced to V5; API health reported UP; frontend home, `/supportiq-widget.js`, and `/orders/shopsmart` returned HTTP 200.

## Existing database safety

- Before the live V5 migration, a PostgreSQL custom-format backup was created at `backups/phase5-before-2026-11-01/supportiq-phase5-before.dump` (555,809 bytes; `PGDMP` header).
- V5 is additive. It retains the existing `demo_orders` table/rows and copies legacy demo orders into `customer_orders` with the demo marker set. It adds company-scoped order/refund state and a default policy for companies.
- The isolated test run uses `phase5_test_v2`; it does not write to the application's default schema.
- Before and after live migration, the database had 2 companies, 5 conversations, 2 legacy demo orders, 3 knowledge documents, and 3 support tickets. After migration it also has 2 customer orders (both copied demo rows), 2 default company refund policies, and 0 refunds. The existing named Compose PostgreSQL volume was reused.

The Phase 4 integration report separately covers two-company document/vector retrieval, agent and conversation isolation, WebSocket messages, private notes, and attachment tenant access.
