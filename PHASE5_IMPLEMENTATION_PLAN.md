# Phase 5 implementation plan — order management and refunds

## Existing system

- Spring Boot/PostgreSQL use additive Flyway migrations; support and AI are JDBC based.
- V1 contains only a small `demo_orders` table with fabricated status fields and no customer identity. V1–V4 contain no transactional refund engine or payment provider integration.
- Staff authentication supplies a validated company and role through JWT claims. Public visitors currently use a company widget ID and conversation capability; those capabilities do not establish order ownership.
- React workspace has navigation for overview, inbox, knowledge and settings; hosted chat/widget are public and accountless.

## Work plan

1. Add an additive V5 migration for company/customer-owned orders and line items, configurable refund policies, immutable refund and audit history, idempotency, provider webhooks, and lookup rate limits. Preserve legacy demo rows by converting them into clearly marked demo records.
2. Implement tenant-scoped order APIs, deterministic eligibility evaluation, customer verification and rate limiting, refund request/review/approval/initiation/reconciliation, idempotent provider/webhook boundaries, and audit events.
3. Add a payment-provider abstraction with a DEMO-only mock; prohibit live transactions unless an explicitly configured provider and production mode are supplied. AI remains read-only and uses verified backend results only.
4. Build order/refund workspace screens, customer order lookup/history and refund status in hosted chat/widget, and an agent order panel.
5. Add backend security/workflow tests and frontend coverage; run compilation, migrations in an isolated schema, tests, production build, widget syntax checks, and live health checks while keeping the existing volume.
6. Update README, PROJECT_STATUS, PAYMENT_INTEGRATION, and PHASE5_TEST_REPORT with actual verification and remaining configuration.

## Guardrails

- Company scope comes from staff JWT claims; public access requires order number plus matching customer verification data, is rate limited, and produces a short-lived capability rather than accepting an order UUID alone.
- Refund approvals and executions are deterministic backend actions. Agents cannot approve unless explicitly granted; only company admins can approve in this phase.
- All refund amounts are bounded transactionally by paid amount minus prior approved/initiated/credited refunds. Idempotency and DB constraints prevent duplicate requests/provider actions.
- DEMO provider operations are labeled DEMO. No live payment transactions are enabled by default. A refund is CREDITED only after a verified trusted provider status; ARN is nullable and never synthesized.
- Migrations are additive. Never drop or recreate the existing PostgreSQL volume or existing customer data.

## Progress

- [x] Inspect existing orders/refunds, auth, tenant boundaries, migrations, and project documentation.
- [x] Add V5 order/refund schema and preserve demo order records.
- [x] Implement secure order management and public customer verification.
- [x] Implement refund policy, eligibility, approval, provider, webhook, and audit workflow.
- [x] Integrate verified order/refund context with assistant and customer surfaces.
- [x] Build company, agent, and customer order/refund interfaces.
- [x] Add and execute backend/frontend/payment/isolation tests; migrate and smoke-check.
- [x] Update README, PROJECT_STATUS, PAYMENT_INTEGRATION, and Phase 5 report.

V5 was applied to the existing database after creating `backups/phase5-before-2026-11-01/supportiq-phase5-before.dump`. The database remains on its original volume; V1–V5 are present, the two legacy demo rows remain in `demo_orders` and were also copied to `customer_orders`, and the pre-existing company, conversation, document, and ticket counts are unchanged. See `PHASE5_TEST_REPORT.md` for test results.
