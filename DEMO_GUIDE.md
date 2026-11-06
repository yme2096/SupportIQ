# SupportIQ Demo Walkthrough

## Start the local demo

From PowerShell in the project directory:

```powershell
docker compose up -d postgres backend frontend
Invoke-RestMethod http://localhost:8080/api/v1/health
```

Open `http://localhost:5173`. Use `CHECK_SUPPORTIQ.bat` if you want a quick service and route check. The only documented demo credential is the local ShopSmart administrator in README; change it before sharing this instance. Do not reuse it outside local demonstration.

## Suggested 8–10 minute tour

1. **Customer experience:** open `/chat/shopsmart`, send a general support question, then ask for an agent. Explain that anonymous customers receive a separate conversation capability rather than staff accounts.
2. **Company onboarding:** register a disposable test workspace, note its public widget ID and hosted-chat route, and inspect its tenant-specific setup checklist.
3. **Knowledge:** upload an approved TXT/Markdown file, show UPLOADED/PROCESSING/READY status, preview its extracted text, then ask a question that matches a sentence in the document. With no Gemini key, explain that the answer is extractive and not generated.
4. **Widget:** open Company settings, show the preview and snippet, and try the separate test website at `.local-readiness/widget-site/index.html` if its test host is running. Verify the widget uses that workspace’s public identifier.
5. **Agent handoff:** sign in using an isolated agent account, open the new inbox item, send a customer-visible reply, and add a private note. Explain that internal notes do not appear to customers.
6. **Orders/refunds:** use the fictitious ShopSmart order demo, verify with its required order details, and show policy eligibility and the DEMO provider label. An approval/request does not mean money moved or a refund was credited.
7. **Billing and quotas:** show trial plan, usage meters, and server-side quota behavior. Explain that checkout/webhook flows simulate results and do not charge money.
8. **Platform console:** on a non-public isolated test database, configure a unique `SUPER_ADMIN_EMAIL` and 16+ character password outside source control, restart the API, then show overview, companies, subscriptions, plans, and audit. Never seed a test operator into a shared customer environment.

## Interview framing

State the tenant boundary, authorization source, durable queue, no-key behavior, and demo payment limitations plainly. Do not claim production deployment, measured latency/scale, AI accuracy, real refund/payment processing, or external email delivery. See [ARCHITECTURE.md](ARCHITECTURE.md) and [INTERVIEW_GUIDE.md](INTERVIEW_GUIDE.md).
