# SupportIQ Phase 2 security architecture

## Identity and session flow

- `POST /api/auth/register-company` creates a company and its first `COMPANY_ADMIN`; duplicate email addresses are rejected case-insensitively.
- Passwords are BCrypt hashes. Login issues a 10-minute HS256 access JWT. The browser keeps it in memory; it is not persisted in local storage.
- A 14-day random refresh token is stored only as a digest in PostgreSQL and delivered in an HttpOnly, SameSite=Strict cookie scoped to `/api/auth`. Refresh rotates the token. Reuse of a rotated token revokes its family. Logout revokes the active token.
- Password change revokes refresh tokens. Reset tokens are stored as digests, expire after 30 minutes, and are single-use. Login/reset requests use database-backed rate-limit records.
- Company Admin invitations create a one-use, seven-day token for an `AGENT`. Invite acceptance sets the password and signs the new user in.

## Tenant boundary and roles

For staff requests, `TenantContext` reads the validated JWT's user, company, and role claims. Controllers and service methods do not accept a client-selected company ID as staff scope. Conversations, messages, tickets, knowledge documents, analytics, users, and settings are filtered by that scope. Database constraints additionally bind messages and tickets to the conversation's company, and a partial unique index prevents duplicate active tickets for one conversation.

`COMPANY_ADMIN` manages company profile/settings and team membership. `AGENT` uses the inbox and replies to conversations. Admin-only knowledge and analytics endpoints reject agents. Company Admin cannot assign `SUPER_ADMIN`; registration and invitations do not create that role. There is no cross-tenant Super Admin data API in this phase.

Customer chat remains public. The company public slug selects a workspace; a cryptographically random per-conversation capability is returned to the browser and only its SHA-256 digest is stored. Chat transcript reads and follow-up messages require that capability in `X-Conversation-Token`. Knowing a conversation ID alone is insufficient. Signed-in staff requests always use their JWT tenant regardless of a supplied public slug.

Website widgets use the existing unique `companies.public_id` as a public widget identifier. Public configuration is limited to company brand fields and its standalone chat path. Widget-specific chat and transcript routes bind the request to the company resolved from that identifier, ignore tenant slugs in request bodies, and use the same per-conversation capability digest as public chat. Widget requests do not send SupportIQ refresh cookies or rely on a customer login. Widget CORS is limited to public, non-credentialed GET/POST routes; the API checks the conversation capability before returning customer messages.

## Browser and HTTP controls

- CORS allows the configured frontend origin and credentials, with explicit headers for authorization and the chat capability.
- Authentication endpoints reject a supplied `Origin` that differs from the configured frontend origin. The refresh cookie is HttpOnly and SameSite=Strict. The service is stateless and does not accept session cookies as staff authentication.
- Production defaults set the cookie Secure flag and hide reset/invite links from API responses. Local Compose explicitly overrides these for HTTP localhost and local demonstration.
- Error responses avoid exposing SQL/exception details. Audit records cover authentication and key company/user actions.

## Operational configuration and limits

Set `SUPPORTIQ_JWT_SECRET` to a private, high-entropy value, configure HTTPS and `AUTH_COOKIE_SECURE=true`, set exact `FRONTEND_ORIGIN`/`FRONTEND_URL`, set `LOCAL_AUTH_LINK_EXPOSURE=false`, and set `SEED_DEMO_ADMIN=false` in deployment. Never use Compose's development fallback secret/password in a deployed environment.

This phase does not integrate outbound email, so registration activates immediately and reset/invitation links are not emailed. Access JWTs remain valid until their short expiration even after account suspension; refresh and subsequent login are blocked. A production deployment should add delivery and verification workflows, operational monitoring, secret management, TLS termination, and deployment-specific abuse controls. Existing public demo order/refund data is fictitious and is not tied to authenticated customer ownership.

## Main endpoints

| Endpoint | Access |
| --- | --- |
| `POST /api/auth/register-company`, `/login`, `/refresh`, `/logout`, `/forgot-password`, `/reset-password` | Public (refresh/logout use cookie) |
| `GET /api/auth/me`, `POST /api/auth/change-password` | Signed-in user |
| `/api/company/profile`, `/api/company/settings` | Company Admin |
| `/api/users/**`, `/api/invitations` | Company Admin |
| `POST /api/invitations/validate`, `/accept` | Public one-use invite flow |
| `/api/v1/**` staff inbox, knowledge, analytics | Signed-in user with role checks |
| `/api/public/companies/{slug}` | Public company chat configuration |
| `/api/public/widget/{publicId}/config` | Public, company-specific widget branding |
| `/api/public/widget/{publicId}/chat` | Public chat; new conversation creation |
| `/api/public/widget/{publicId}/conversations/{id}/messages` | Public chat capability required |

Full runtime API documentation is exposed through Swagger UI at `/swagger-ui/index.html`.
