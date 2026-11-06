# Phase 4 implementation plan — real-time chat and agent workspace

## Existing implementation

- Spring Boot 3.5 / Java 21, PostgreSQL, additive V1–V3 migrations, bearer JWT staff auth, public widget conversation capability tokens, and RAG/AI handoff.
- Chat persists messages over REST and the widget polls every four seconds. Agent workspace lists tickets and polls only when manually refreshed; no assignment ownership, internal notes, presence, notifications, or ticket state history exists.
- Tickets store an `assigned_agent` display string and only support OPEN/IN_PROGRESS/RESOLVED. Message history is company scoped for staff and capability scoped for public customers.
- Frontend uses an in-memory access token with refresh-cookie renewal. WebSocket authentication must therefore use explicit STOMP CONNECT headers; browser cookies alone are not sufficient.

## Work plan

1. Add additive V4 tables/columns for message idempotency/delivery/read state, private notes, assignment/presence/workload, status/assignment history, notifications, feedback, and SLA settings.
2. Add authenticated STOMP channel authorization: validate staff JWTs, verify staff tenant/conversation ownership, validate public company ID + conversation capability for customers, and gate every subscription/send destination.
3. Persist real-time replies/notes before publishing, enforce idempotency and valid lifecycle transitions, tenant-scope every operation, and publish assignment/message/resolve notifications only after writes.
4. Add REST APIs for conversation queues/search/pagination, assignment/reassignment/round-robin, availability, notes, lifecycle/priority/reopen, read markers, notification center, feedback, SLA, and secure attachments.
5. Replace the basic inbox with a responsive three-column agent workspace; add realtime client subscriptions, typing, delivery/unread signals, reconnect/offline states, customer-history access, internal-note UI, attachment controls, notifications and SLA indicators. Update hosted chat and embed widget to use realtime when available and retain safe reconnect fallback.
6. Add integration/security and frontend tests; run backend suite in an isolated schema, local frontend suite/build, live additive migration, Compose health checks; preserve existing volume and document provider/configuration limits.
7. Update README, PROJECT_STATUS, and produce PHASE4_TEST_REPORT.md.

## Guardrails

- Customers can subscribe only to the one conversation bound to their public capability token. Public REST/WebSocket history never includes internal notes or attachment metadata for internal notes.
- Staff JWT identity and company claims are validated by Spring Security. Role, active status, company ownership, and conversation ownership are checked server side on all STOMP frames and HTTP mutations.
- Assignment uses a database transaction and row lock; only active company agents within workload limits participate in automatic assignment.
- Preserve existing AI/RAG flow and refund protections. AI handoff remains a ticket, and no vector/document or conversation data is dropped.
- Create a fresh database dump before V4 and use a separate test schema. Never remove the existing Compose volume.

## Progress

- [x] Inspect current implementation and documentation.
- [x] Create pre-V4 database backup.
- [x] Add additive migration V4.
- [x] Add secure realtime messaging and idempotency.
- [x] Implement assignment, presence, lifecycle, notes, notifications, SLA, feedback, and attachments APIs.
- [x] Build responsive inbox and customer realtime states.
- [x] Add and run backend/frontend tests; migrate and smoke-check the existing stack.
- [x] Update README, status, and Phase 4 test report.
