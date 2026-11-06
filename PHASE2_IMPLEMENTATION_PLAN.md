# Phase 2 implementation plan

## Existing application

- Java 21 / Spring Boot 3.5.7 REST service using `JdbcTemplate` and Flyway, with one `V1__init.sql` migration and a hard-coded ShopSmart company ID.
- React/Vite single page with customer chat, knowledge upload, agent inbox, and overview. No router or authentication exists yet.
- Compose uses a persistent PostgreSQL volume. The running database contains one company, five conversations, and three tickets.
- No Git history is available. Before editing, a source snapshot and custom-format database dump were saved under `backups/phase2-before-2026-11-01/`.

## Changes

1. Add a non-destructive Flyway migration for role/user records, company profile/settings/public identifier, hashed refresh/reset/invitation tokens, rate limits and audit records. Strengthen ticket/conversation tenant relationships while preserving existing IDs and rows.
2. Add Spring Security JWT resource-server validation, short-lived HMAC-signed access tokens, BCrypt passwords, HttpOnly refresh cookie rotation/revocation, CSRF/origin safeguards, and role checks.
3. Implement company registration, login/logout/refresh/me, password change/reset, team listing and invitation create/validate/accept, company profile/settings, with DTO validation and generic reset responses.
4. Resolve tenant identity only from the validated JWT for staff APIs. Remove the hard-coded tenant from all staff service queries. Keep public customer chat reachable only via company public slug and a per-conversation random capability token stored as a digest; preserve the existing demo transcript for authenticated staff.
5. Add a React router and public registration/login/reset/invite pages plus protected role-aware workspace. Keep access tokens in memory, refresh tokens in HttpOnly cookies, and retain the existing chat/inbox/knowledge/overview.
6. Add automated backend tests and frontend tests for auth and tenant isolation. Run Maven verification, frontend build/tests, start Compose without deleting volumes, apply Flyway, and exercise Company A/B access controls.
7. Update API/security documentation, README, status, and a factual Phase 2 test report.

## Local account and email decisions

- Seed the pre-existing ShopSmart workspace with a local-only administrator from environment variables using BCrypt; do not seed a credential in SQL or Java source.
- Company registration can sign in immediately in this local demo; outbound email verification/reset delivery is not configured. Password reset and invite links are available only in local-development mode and only to the authorized requester/administrator response.
- No third-party email, payment, or account service is required for the local Phase 2 flows.

## Security boundary

- Staff APIs require JWT and company scope from the token. The client cannot select a staff tenant by request body or query parameter.
- Public chat company slugs are discoverable by design; conversation IDs alone are insufficient to read or continue a conversation. A per-conversation capability token is required for public transcript access.
- `SUPER_ADMIN` has no implicit tenant bypass. Any platform-wide operation must be explicit and audited; no broad cross-tenant data API is planned in this phase.
- Flyway migration is additive. No Docker volumes are removed or reset.

## Progress

- [x] Inspect current files/runtime/data and create source/database backups.
- [x] Write this implementation plan.
- [x] Add schema migration and backend authentication/authorization.
- [x] Tenant-scope existing APIs and secure public chat access.
- [x] Add frontend authentication/onboarding/team routes.
- [x] Add and execute automated security tests.
- [x] Complete docs and test report.
