# Payment and refund integration

## Current behavior

SupportIQ currently ships with the `DEMO` payment provider only. The provider records a refund initiation and returns a clearly marked `DEMO-...` reference; it does not connect to a processor, bank, or card network and does not move money. Refund status is not marked credited at initiation. Only a verified provider webhook with a `CREDITED` event updates credited totals and the order's `PARTIALLY_REFUNDED` or `REFUNDED` payment state. An ARN is displayed only when a provider supplies one.

The order/refund model supports review and reconciliation states (`UNDER_REVIEW`, `APPROVED`, `REJECTED`, `INITIATED`, `PROCESSING`, `CREDITED`, `FAILED`, and `UNKNOWN`). Company Admin approval is required before provider initiation. Agents may review but cannot approve or initiate. Refund amounts are bounded by the approved request, company policy, and the remaining refundable balance. Unknown or ambiguous data stays under review.

## Local configuration

Compose defaults to:

```dotenv
PAYMENT_PROVIDER=DEMO
PAYMENT_LIVE_ENABLED=false
PAYMENT_WEBHOOK_SECRET=local-demo-webhook-secret-change-before-deploy
```

Keep live payments disabled. For a shared or deployed environment, replace the webhook secret with a high-entropy secret held in the deployment's secret manager. Do not expose it to the browser. Webhook signatures use HMAC-SHA256 over the exact raw request body; event IDs are idempotent and a repeated ID with a changed payload is rejected. The webhook endpoint is `/api/v1/public/payments/webhook`.

## Production integration required

No production payment processor is configured. Before processing real refunds, implement and review a provider adapter for the selected processor, configure credentials and webhook registration in its secure secret manager, require HTTPS, confirm signature canonicalization against the provider's official documentation, and run provider sandbox and reconciliation tests. Provider initiation responses must remain `INITIATED`/`PROCESSING` until a signed event or authoritative reconciliation confirms credit. Configure operational alerting and customer notification delivery as well. The current customer portal exposes order/refund status; it does not send email or SMS notifications.

Do not set `PAYMENT_LIVE_ENABLED=true` until a real adapter and its corresponding production configuration are installed and reviewed. This setting alone does not create a real provider integration.
