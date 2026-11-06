# Phase 4 implementation and verification report

Date: 2026-11-01

## Scope delivered

Phase 4 adds real-time customer and agent messaging, a professional ticket workspace, tenant-safe assignment and presence, internal notes, ticket lifecycle and history, notifications, company SLA settings, customer feedback, and protected attachments. The widget and hosted chat use the real-time service with reconnect and safe polling fallback.

## Automated verification

- Backend Maven package and test compilation succeeded.
- Backend integration suite: 9 tests passed, 0 failures, 0 errors. The suite includes 5 auth/tenant/realtime/attachment tests and 4 document-ingestion tests.
- Coverage includes customer-to-agent WebSocket messaging in both directions, idempotent message persistence, rejection of cross-company conversation subscriptions, private staff notes, automatic/manual assignment, ticket resolve/reopen, customer feedback, and attachment access limited to the owning company.
- Frontend test suite: 3 tests passed.
- Vite production build succeeded; `supportiq-widget.js` passes the JavaScript syntax check.
- V1–V4 migrations applied successfully in the isolated backend test schema.

## Existing installation

- A pre-migration database dump is retained at `backups/phase4-before-2026-11-01/supportiq-phase4-before.dump`.
- The existing PostgreSQL volume was preserved. Flyway reports version 4 after the live migration.
- Before and after the migration, the live database contains 2 companies, 5 conversations, 3 support tickets, and 3 knowledge documents.
- Docker Compose backend, frontend, and PostgreSQL services are running. The application health endpoint reports `UP` with database `UP`; the frontend and public widget asset both return HTTP 200.

## Security and behavior notes

- Agent STOMP sessions authenticate with a staff JWT and enforce active user, role, company, and conversation ownership checks on subscriptions and sends.
- Customer sessions require the public company identifier and a conversation capability token. Public destinations bind to that one conversation; staff-only notes and workspace topics are unavailable to customers.
- Messages and notes are stored before broadcast; duplicate client message IDs are deduplicated. Company identifiers are resolved server-side, and attachment downloads are company-scoped.
- Attachment uploads are limited to 5 MB and supported PNG, JPEG, PDF, and UTF-8 text formats. Malware scanning is not implemented.

## Configuration

AI responses continue to use the existing Phase 3 provider configuration. Real Gemini generation and embeddings require `GEMINI_API_KEY` (or the separate `GEMINI_EMBEDDING_API_KEY` for embeddings); the safe configured fallback remains available without those credentials.
