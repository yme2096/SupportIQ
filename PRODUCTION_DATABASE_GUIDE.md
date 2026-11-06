# Production database initialization

## Create a clean production database

Use the new Render PostgreSQL 16 resource declared in `render.yaml`, in the same Singapore region as the API and worker. The Blueprint restricts inbound database access to private networking (`ipAllowList: []`). Do not use a local `.env`, local Compose hostname, local database, or local backup as its initial contents. Render private services should use the internal connection string; do not enable an external database IP allow list for normal application traffic. See [Render PostgreSQL](https://render.com/docs/postgresql-creating-connecting) and [database networking](https://render.com/docs/private-network).

Knowledge originals and chat attachments are stored in PostgreSQL `BYTEA`, so database backups include them. Track database growth and plan object storage before document/attachment volume becomes large.

## First startup and migration checks

The Spring Boot service runs Flyway automatically on startup. The migrations V1–V6 create the schema and enable pgvector; no manual schema seeding or copying from local is required. Review deploy logs for successful Flyway validation and migration before opening registrations.

From an authorized database session, verify:

```sql
SELECT version FROM flyway_schema_history WHERE success = true ORDER BY installed_rank;
SELECT extversion FROM pg_extension WHERE extname = 'vector';
SELECT current_database(), current_user;
```

The extension query must return a version. The API health endpoint `/api/v1/health` checks `SELECT 1` and should report database `UP`. Render PostgreSQL supports pgvector; the migration enables it as an extension. See [Render pgvector guidance](https://render.com/docs/postgresql-extensions).

## Connection security and capacity

The web service pool is capped at 8 connections and worker at 3 (11 maximum application connections before administrative/migration overhead). Revisit these values against your selected database plan and observed concurrency. Use the internal URL only inside Render. Keep credentials out of shell history and avoid printing environment dumps.

The Blueprint sets the database external IP allow list empty. If an administrator temporarily needs an external connection, add only a trusted fixed IP, use TLS, and remove the rule immediately afterward. Prefer running inspection from a private Render service or the dashboard.

## Migration discipline

Back up before future schema changes. Flyway migrations are forward-only; add a new migration rather than editing an applied migration. Test it against an isolated database/schema first. Do not run destructive reset, drop, or truncate operations against production. Review [Flyway's migration guidance](https://documentation.red-gate.com/flyway/flyway-concepts/migrations).
