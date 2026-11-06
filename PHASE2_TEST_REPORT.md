# Phase 2 verification report

Date: 2026-11-01

## Automated checks

- Backend: `docker compose --profile test run --rm backend-tests` — **passed**, 3 integration tests, 0 failures, 0 errors. Tests use the dedicated `phase2_test` PostgreSQL schema and do not reset the application's data volume.
- Frontend: `npm test` — **passed**, 3 tests for registration, login/onboarding, and protected-route redirection.
- Frontend production bundle: `npm run build` — **passed**.
- Backend container packaging: `docker compose build backend` — **passed**.
- Frontend container build: `docker compose build frontend` — **passed**.
- Live Compose smoke check — **passed**: health reported database `UP`, frontend returned HTTP 200, local ShopSmart administrator signed in as `COMPANY_ADMIN`, and unauthenticated `/api/users` returned 401.

The integration scenarios cover expired access JWT rejection; company registration and duplicate/malformed registration; unauthenticated access denial; successful and failed login; Company A/B knowledge and ticket isolation; cross-company transcript, ticket, user, and agent-list denial; ignoring a forged tenant slug on a staff request; company-specific widget configuration; cross-origin widget config; widget tenant resolution and ignored forged slugs; widget conversation/ticket ownership; cross-widget transcript and continuation denial; invite validation/acceptance/reuse/expiry; role assignment restrictions; agent access denial; suspended-account login denial; and refresh rotation, replay rejection, and logout.

## Data preservation

Phase 2 uses an additive Flyway migration. Before changes, a source snapshot and PostgreSQL custom-format dump were saved under `backups/phase2-before-2026-11-01/`. The existing `supportiq_pgdata` volume was retained. Integration tests use their own schema and random test accounts.

## Limits

These checks validate application-level flows in local Docker. They are not an external penetration test or a production deployment review. Email verification/reset delivery, production TLS and secret operations, monitoring, and customer/order ownership integration are not implemented. See [SECURITY_ARCHITECTURE.md](SECURITY_ARCHITECTURE.md) for the session and tenant boundary details.
