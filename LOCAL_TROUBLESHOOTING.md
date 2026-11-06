# Local troubleshooting

## Docker is unavailable

Open Docker Desktop and wait for its engine to report ready. Check with `docker info` and `docker compose version`, then run `CHECK_SUPPORTIQ.bat`.

## Startup does not become healthy

```powershell
docker compose ps
docker compose logs --tail 120 postgres backend frontend
```

Wait for PostgreSQL to show `healthy`. Backend startup runs Flyway and may take several seconds. Check `http://localhost:8080/api/v1/health` after it starts. The startup helper waits up to two minutes and prints recent logs on failure.

## Port 5173 or 8080 is already in use

Inspect the process/container using that port before changing configuration. Compose publishes frontend port 5173 and backend port 8080. Do not stop unrelated services without identifying them. Change the host-side port mapping in `docker-compose.yml` only if needed, and update the frontend's `VITE_API_BASE_URL` build arg if the backend host port changes.

## Maven cannot download Surefire or another dependency

The clean image build requires access to Docker Hub and `repo.maven.apache.org`. Check `docker run --rm maven:3.9-eclipse-temurin-21 getent hosts repo.maven.apache.org`; retry when DNS/network is available. Do not downgrade Surefire to hide a transient network problem.

## Login cookie or JWT errors

Local HTTP requires `AUTH_COOKIE_SECURE=false` and a `SUPPORTIQ_JWT_SECRET` of at least 32 bytes. Compose supplies local-only defaults. If you provide a custom `.env`, check values without printing the secret. Restart the backend after configuration changes.

## Documents remain UPLOADED or become FAILED

Check backend/worker logs and `processing_error` in the company knowledge manager. Without Gemini keys, local hash embeddings now support indexing and retrieval. If `EMBEDDING_PROVIDER=gemini` and a key is present, check network access, key validity, quota, and the configured model. With the dedicated-worker profile, ensure `AI_PROCESSING_DISPATCH_LOCALLY=false` is set for the backend as well as the worker.

## Chat shows safe fallback

This is expected when Gemini answer generation is not configured or retrieval finds no sufficiently matching approved sentence. Upload an approved document, wait until READY, and ask a question that shares meaningful terms with a source sentence. The no-key path returns a short extractive answer when the overlap threshold is met; otherwise it returns the configured safe fallback and recommends human handoff. It does not generate free-form answers.

## Widget fails on another origin

Use the exact public company ID and set the snippet's `data-api-base` to the backend URL. The widget public endpoints allow credential-free cross-origin requests. Staff APIs require the configured `FRONTEND_ORIGIN`. Do not broaden credentialed staff CORS to `*`.

## Tests fail to connect to PostgreSQL

Run `docker compose up -d postgres`, confirm healthy status, and rerun `docker compose --profile test run --rm backend-tests`. Tests use the isolated `phase7_test` schema. Do not repoint tests at `public`.

## Database preservation

Use `docker compose stop` to stop services. The named `supportiq_mvp_supportiq_pgdata` volume is the local database. Do not use `docker compose down -v`, `docker volume prune`, `DROP DATABASE`, or `TRUNCATE` against public application tables. For diagnosis, capture a custom-format `pg_dump` before schema operations.
