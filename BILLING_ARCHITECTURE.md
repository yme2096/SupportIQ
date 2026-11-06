# Subscription billing and usage architecture

## Storage and lifecycle

V6 is an additive PostgreSQL migration. It adds database-managed plans, one subscription per company, payment attempts, printable invoices, lifecycle history, usage records, billing webhook idempotency, and company status. New companies receive a 14-day Starter trial from a database trigger. Existing companies receive the same trial without changing their existing workspace records. Plan amounts and limits are persisted in `subscription_plans`; platform operators can edit seeded plans in the platform workspace.

Subscription states are `TRIALING`, `ACTIVE`, `PAST_DUE`, `CANCELLED`, and `EXPIRED`. Failed payments enter a configurable grace period (`BILLING_GRACE_DAYS`, seven days by default). Expiry restricts writes and preserves all company data. Billing endpoints remain reachable so admins can recover; suspended companies are restricted by the same company access filter. Cancellation can take effect immediately or at the current period end, and can be reversed before it takes effect.

## Provider boundary and safety

The active provider is `DEMO`. It creates no charge and accepts no card data. A checkout attempt is stored as pending; only a server-generated, HMAC-signed DEMO event can complete it. External webhook processing verifies the exact raw body, records provider event IDs, rejects signature errors, and makes event replay idempotent. The DEMO secret is configured with `BILLING_WEBHOOK_SECRET`; production deployments must replace the local fallback with a high-entropy secret held by the deployment secret manager.

`RazorpaySignatureVerifier` provides the raw-body HMAC verification primitive, but a live Razorpay adapter, checkout client, production webhook setup, and live refunds are not implemented or enabled. Setting `BILLING_PROVIDER` to a value other than `DEMO` disables checkout with a service-unavailable response. Before enabling real billing, implement and review a provider adapter, configure credentials outside source control, set up verified webhook delivery and reconciliation, and test the full charge/refund lifecycle in the provider's test environment. Never treat a browser redirect as proof of payment.

DEMO-only refunds may reverse a successful DEMO subscription payment and void its invoice. Refunds through an actual processor are unavailable. The existing V5 order-refund provider is separate from subscription billing.

## Metering and enforcement

AI conversation usage is recorded once per company and conversation with a unique idempotency key, and AI messages are separately counted by the database trigger. Company admin invitations and agent reactivation check active plus pending agents. Document uploads and conversation attachments check document and total stored-byte limits. These checks run on the server. Usage is recorded by billing period and analytics read tenant-scoped operational tables.

Seeded monthly plans:

| Plan | Price/month | Agents | AI conversations/month | Documents | Storage | Analytics |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| Starter | ₹999 | 2 | 1,000 | 25 | 1 GB | Basic |
| Growth | ₹2,999 | 10 | 5,000 | 100 | 5 GB | Advanced |
| Business | ₹5,999 | 25 | 15,000 | 500 | 25 GB | Advanced |
| Enterprise | Custom | Unlimited | Unlimited | Unlimited | Unlimited | Custom |

Annual options are seeded at ten monthly payments. Prices and quotas are editable by platform administrators; verify taxes and commercial terms before a real launch.

## Authorization and analytics

Company billing, invoices, usage, subscription history, and analytics derive company scope from the authenticated tenant context. There is no company ID accepted from client input for those resources. Platform routes require the global `SUPER_ADMIN` role. Platform views expose account/subscription metadata and audit events, not customer messages or document text. Configure both `SUPER_ADMIN_EMAIL` and a password of at least 16 characters to provision an initial platform operator; leaving either blank creates no operator. Existing users are never elevated automatically.

Company analytics support date-windowed daily, weekly, or monthly series, conversation totals, resolution and handoff rates, response and resolution durations, satisfaction, ticket priorities and agent workload, refund status/amount by currency, and AI usage. CSV export uses the same company-filtered report. Metric definitions appear in the dashboard and API.

## Routes

- `GET /api/public/billing/plans`
- `/api/v1/billing/*` for company admins: subscription, usage, checkout, DEMO result, payment/invoice/history, cancellation, reactivation, and DEMO refund
- `/api/public/billing/webhook/{provider}` for verified provider events
- `/api/v1/analytics/report` and `/api/v1/analytics/export.csv` for company analytics
- `/api/platform/*` for protected platform reporting, company status, plan editing, and audit metadata

The public pricing page is `/pricing`; company billing and analytics are in the admin workspace; the separate operator workspace is `/platform`.
