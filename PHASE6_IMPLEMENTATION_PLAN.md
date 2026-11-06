# Phase 6 implementation plan

## Current state

- Spring Boot, PostgreSQL, and React with V1–V5 additive Flyway migrations.
- Tenant and role claims are in signed JWTs; `SUPER_ADMIN` role exists in the database, but no platform admin workflow or protected platform provisioning exists.
- Company overview analytics are limited to counts; no subscription, invoice, payment, metering, or plan enforcement exists.
- V5 refunds use a DEMO-only provider. Subscription billing must remain independently DEMO unless a real provider and secrets are explicitly configured.

## Implementation

1. Add V6 subscription, plan, payment, invoice, usage, webhook, and lifecycle history schema; seed database-configurable plans and trials without replacing existing customer data.
2. Implement subscription lifecycle, DEMO billing adapter, signed/idempotent billing webhook, invoices, payment history, audit records, and secure `SUPER_ADMIN` provisioning and APIs.
3. Meter AI conversations, agents, documents, storage, and monthly usage. Enforce relevant limits server-side and gate writes for suspended/expired/grace subscriptions while retaining data and allowing billing recovery.
4. Build real company analytics with date windows, formulas, time series, CSV export; build platform overview, company/plan/audit management, pricing, billing, usage, and subscription pages.
5. Add integration coverage for tenant boundaries, platform authorization, lifecycle, billing signatures/idempotency, quota enforcement, suspension, and DEMO transactions. Run tests, build, isolated migration, backup and apply V6 in place, and smoke-check routes.
6. Update README, PROJECT_STATUS, PHASE6_TEST_REPORT, and BILLING_ARCHITECTURE with observed results and real-payment prerequisites.

## Progress

- [x] Inspect Phase 5 materials and existing auth, tenant, migration, company, analytics, billing, and frontend code.
- [x] Add additive V6 billing and metering schema.
- [x] Implement backend subscription lifecycle, provider boundary, usage limits, analytics, and Super Admin operations.
- [x] Add company billing/analytics and platform admin interfaces.
- [x] Verify tests, builds, migration safety, and two-company isolation.
- [x] Update README, status, test report, and billing architecture.

## Safety

- Preserve the named PostgreSQL volume and all V1–V5 rows; take a custom-format backup immediately before live V6.
- Do not process real payments without an explicitly configured production provider and credentials. No secrets enter browser code or database billing logs.
- Super Admins get platform metadata and audit views, not customer transcripts or document contents.
- Subscription expiry restricts new work according to the configured grace policy; it never deletes workspace data.
