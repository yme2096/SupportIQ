# SupportIQ Resume and Interview Guide

## Resume project description

**SupportIQ — Multi-tenant AI customer-support SaaS (Java 21, Spring Boot, React, PostgreSQL, pgvector, Docker)**

Built a company-scoped support platform with JWT/role-based access, anonymous tenant-bound chat, WebSocket agent handoff, policy document ingestion and pgvector retrieval, durable document processing, order/refund guardrails, usage-metered demo billing, and platform administration. Verified with isolated PostgreSQL integration tests, frontend tests/build, clean container builds, and local role walkthroughs. Gemini, email, live payments, and public deployment remain credential/configuration dependent.

## Two-minute explanation

SupportIQ gives each business an isolated workspace and public widget identifier. Staff use short-lived signed access tokens and company scope comes from those validated claims. Anonymous customers start a company-specific chat and receive a separate capability token to continue reading that conversation. Agents use STOMP/WebSocket plus REST history and can send customer replies or staff-only notes.

Company policy uploads are validated, stored in PostgreSQL, and queued durably. A backend executor handles local development; a dedicated worker polls the same database queue for deployment. It extracts supported file types, chunks text with page/section metadata, writes vector embeddings, and marks status. Retrieval is company-filtered and citations are constructed from retrieved rows. Gemini generation is optional; without it, a short matching source sentence can be returned extractively, with safe fallback and handoff otherwise.

Orders, refunds, and subscriptions have explicit ownership and eligibility controls. Simulated payment providers are marked DEMO and do not send real money. The project includes a Render Blueprint, but has not been deployed.

## Architecture and data design

- **Tenancy:** company-owned rows include `company_id`; staff tenant context derives from authenticated JWT claims. Cross-company IDs are tested against scoped service/API access.
- **Authentication:** BCrypt password hashes, HS256 JWT with issuer/audience validation, hashed rotating refresh tokens in strict HttpOnly cookies, and separate Company Admin, Agent, Customer, and Super Admin roles.
- **RAG:** validated PDF/DOCX/TXT/Markdown upload → extraction → section/page-aware chunks → 768-dimensional vectors → company-scoped pgvector cosine ranking → source-grounded answer or safe handoff.
- **Worker:** durable `UPLOADED` state → conditional `PROCESSING` claim → extraction/embedding → transactional chunks + READY → stale recovery or bounded retry failure.
- **Realtime:** customer capability and company public ID or staff JWT are checked on STOMP subscriptions/sends; persistent messages publish after storage; REST history is a reconnect fallback.
- **Containers:** React/Nginx, Spring Boot API, PostgreSQL/pgvector, and optional separate worker. Flyway V1–V6 owns the schema.

## Twenty likely interview questions

1. **How do you isolate tenants?** Staff tenant context comes from the validated JWT; tenant-owned queries bind that company ID. Public widget calls resolve company from the company public ID and pair the conversation with a high-entropy capability token.
2. **Why use both a public company ID and a conversation token?** The company identifier routes a new chat; it does not grant transcript access. The per-conversation capability authorizes only that conversation.
3. **How does JWT validation work?** Spring Resource Server verifies an HS256 signature plus configured issuer and audience, and the auth key must be at least 32 bytes.
4. **How are refresh tokens stored?** Random tokens are hashed in the database, placed in HttpOnly SameSite=Strict cookies, rotated on refresh, and revocable.
5. **How is an agent assigned?** Company staff may assign explicitly or use round-robin/least-loaded strategy, with availability and workload tracked in the workspace.
6. **How do WebSockets stay tenant-scoped?** The STOMP interceptor checks staff role/tenant or customer company ID/conversation capability before permitting subscriptions and sends.
7. **What happens when the socket disconnects?** The client reconnects and can reload history through REST; message writes are idempotency-aware and persist before broadcasts.
8. **How does document processing survive a restart?** The uploaded document row itself is the durable queue entry. A worker reclaims `UPLOADED` jobs and recovers sufficiently stale `PROCESSING` rows.
9. **How do you prevent duplicate workers?** Each processor conditionally updates a document from UPLOADED to PROCESSING and proceeds only if exactly one row was claimed.
10. **What file types are supported?** PDF, DOCX, TXT, and Markdown, with size, extension, signature, extraction, and chunking checks.
11. **How does RAG avoid cross-company retrieval?** Retrieval filters chunks by the authenticated/resolved company and joins documents on company ownership before ranking.
12. **What if Gemini is unavailable?** Local hash embeddings support low-cost retrieval; only sufficiently matching approved sentences are returned extractively. Otherwise the configured response and handoff are used.
13. **How are citations prevented from being fabricated?** The model is instructed not to produce its own citations; source references are attached from the retrieved database rows. No-key responses with no match clear references.
14. **Does an answer imply an order/refund action?** No. Order context is supplied only after order verification; refunds require policy eligibility and staff review, and initiation is separate from confirmed credit.
15. **Does demo checkout charge money?** No. The included provider simulates signed, idempotent payment events and is deliberately separate from live processors.
16. **How are plan limits enforced?** Service methods check company subscription/usage before resource-creating operations; frontend limits are display aids, not the enforcement boundary.
17. **What does Super Admin see?** Platform company/subscription/usage/audit metadata and administrative controls, not customer transcripts or source-document contents.
18. **How do migrations work?** Flyway V1–V6 is the schema authority; Hibernate DDL is disabled. Tests use isolated schemas.
19. **What is the production deployment state?** Render configuration is prepared, but no services or production DB have been created. Production requires separate secrets, managed backups, exact HTTPS origins, and validation.
20. **What would you improve next?** Add email delivery, edge rate limiting, tested CSP/security headers, malware scanning, managed object storage if PostgreSQL blobs outgrow the DB, and load/restore/penetration testing.

Do not claim usage scale, model accuracy, performance metrics, real payments, email delivery, or a production deployment: those have not been measured or configured.
