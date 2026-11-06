# SupportIQ security review

**Reviewed:** 2026-11-05
**Scope:** Local source/configuration review, 15 backend integration tests, 5 frontend tests, isolated tenant checks, and signed-in browser walkthroughs for Company Admin, Agent, Customer, and Super Admin. This is not an external penetration test.

## Controls observed

- JWT decoder requires HS256 signature, issuer `supportiq`, and audience `supportiq-api`. The signing key is rejected below 32 bytes. Company scope is resolved from authenticated claims in staff APIs.
- BCrypt password hashing, role-based route/method protection, HttpOnly refresh cookies, refresh rotation/revocation, Origin checks on auth mutation endpoints, and generic reset responses are present in the backend implementation.
- SQL calls use `JdbcTemplate` bind parameters in reviewed service/controller paths. Tenant-owned conversations, documents, messages, tickets, orders, refunds, subscriptions, and team relationships have company scoping/foreign-key protections in migrations. Integration tests exercise cross-company denial.
- WebSocket/STOMP authorization checks staff role and conversation/company membership; public customer chat uses a per-conversation capability token rather than a SupportIQ login.
- Public widget CORS is credential-free and limited to public widget paths. Credentialed staff API CORS uses a configured frontend origin.
- Uploads are size/extension/signature checked. Knowledge documents and conversation attachments are stored in PostgreSQL BYTEA, so they survive container replacement with the database backup/volume.
- Gemini keys are server-side only. The new company AI status endpoint reports provider availability/model names but returns no keys or secret values.
- Payments and subscription charges default to DEMO; live payments are disabled by local Compose. Secrets and local artifacts are ignored by `.gitignore`.

## Findings and limits

1. Local Compose defaults contain known development passwords/signing and demo webhook secrets. They are suitable only for a private local environment. Production must supply unique secrets, HTTPS, secure cookies, a trusted frontend origin, and managed rotation.
2. The local fallback is deterministic hashing, not a pretrained semantic model. It supports inexpensive feature retrieval without external credentials; it can return a matching approved source sentence extractively, but does not generate answers. Unsupported or weakly matched questions use the configured safe fallback/handoff.
3. The static frontend Nginx config does not currently set a full Content Security Policy or Permissions Policy. Add production headers after validating the widget, API, and required inline behavior against a policy.
4. A company widget logo URL is administrator-configurable external content. Operators should only use trusted HTTPS image sources; browser image policy and content security headers should be tested before deployment.
5. Rate limiting is application/database backed for selected auth/order flows; confirm capacity and distributed behavior for a multi-replica production deployment.
6. Live Gemini key validity, quotas, timeout/rate-limit behavior, email delivery, live payment provider behavior, production TLS, backups/restore drills, and external penetration testing remain unverified here.
7. GitHub readiness cannot be confirmed from this workspace: `git rev-parse --show-toplevel` resolves to `C:\`, and its configured `origin` is `https://github.com/yme2096/DSA-REVISION.git`, which is unrelated to SupportIQ. A scoped source scan found no matching API/private-key patterns, and no project `.env` file is present, but no SupportIQ repository was staged or pushed.

## Practical production prerequisites

- Keep `AUTH_COOKIE_SECURE=true`, TLS, and a single trusted `FRONTEND_ORIGIN` in production.
- Use unique secret-manager values for `SUPPORTIQ_JWT_SECRET`, webhook keys, Gemini keys, and database credentials.
- Run the backend and worker with bounded DB pools, graceful shutdown, and persistent managed PostgreSQL storage/backups. Current file bytes reside in PostgreSQL, not ephemeral container files.
- Keep live payments disabled until provider callbacks, signing, reconciliation, idempotency, refunds and operational alerting are reviewed end to end.
- Validate security headers, CORS, WebSocket origin policy, upload limits and role access in the actual hosted environment before public traffic.
