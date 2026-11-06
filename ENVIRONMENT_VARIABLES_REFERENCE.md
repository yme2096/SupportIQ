# Environment variables

Compose supplies working local-only defaults, so `.env` is optional for the default local stack. Copy `.env.example` only when you want to override settings. Never commit `.env` or real credentials.

| Variable | Required locally | Purpose / default |
|---|---|---|
| `POSTGRES_PASSWORD` | No | Local PostgreSQL password; default is development-only. Must match the existing database initialization. Changing it does not rotate the password in an already initialized volume. |
| `SUPPORTIQ_JWT_SECRET` | No | HMAC signing secret, at least 32 bytes. Compose default is local-only and must never be used in production. |
| `AUTH_COOKIE_SECURE` | No | `false` for local HTTP; production must use HTTPS and `true`. |
| `FRONTEND_ORIGIN` | No | Credentialed staff API/browser origin. Local default `http://localhost:5173`; allow a single trusted origin. |
| `FRONTEND_URL` | No | Base URL used for local invite/reset links. Local default `http://localhost:5173`. |
| `SEED_DEMO_ADMIN` | No | Enables local demo administrator seeding in Compose. Default `true`; use a unique password before sharing the local instance. |
| `DEMO_ADMIN_EMAIL`, `DEMO_ADMIN_PASSWORD`, `DEMO_ADMIN_NAME` | No | Local demo account identity. Compose development defaults only. Never use these outside local development. |
| `GEMINI_API_KEY` | No | Server-side Google Gemini key for grounded answer generation and default embedding key. Blank enables safe fallback and local embeddings. Never expose it to Vite/client variables. |
| `GEMINI_EMBEDDING_API_KEY` | No | Optional separate server-side embedding key; falls back to `GEMINI_API_KEY` if blank. |
| `EMBEDDING_PROVIDER` | No | `gemini` (default) uses Gemini when keyed and deterministic local hash embeddings when unkeyed; `local` forces local embeddings. |
| `GEMINI_MODEL` | No | Gemini generation model; default `gemini-3.8-flash`; low thinking level, 512 output-token cap, 5-second connect timeout, and 20-second response timeout are used. Transient errors are retried at most twice after the initial attempt. |
| `GEMINI_EMBEDDING_MODEL` | No | Gemini embedding model; default `gemini-embedding-2`; requests 768 dimensions to match existing pgvector storage. |
| `RAG_CHUNK_SIZE`, `RAG_CHUNK_OVERLAP` | No | Chunker bounds; defaults 1800 characters and 240 overlap. |
| `AI_PROCESSING_DISPATCH_LOCALLY` | No | Default `true` processes jobs in the backend. Set `false` when running the `dedicated-worker` profile. |
| `AI_WORKER_ENABLED`, `AI_WORKER_POLL_MS`, `AI_WORKER_STALE_MINUTES` | No | Worker activation/poll/recovery. Compose worker sets enabled; default poll is 5000 ms and stale recovery is 30 minutes. |
| `PAYMENT_PROVIDER`, `PAYMENT_LIVE_ENABLED` | No | Local mode is `DEMO` / `false`. Do not enable live payment without explicit production setup and approval. |
| `PAYMENT_WEBHOOK_SECRET`, `BILLING_WEBHOOK_SECRET` | No | Local demo webhook signing keys. Use unique secret-manager values in production. |
| `BILLING_PROVIDER`, `BILLING_GRACE_DAYS` | No | Local provider is DEMO; grace defaults to 7 days. |
| `SUPER_ADMIN_EMAIL`, `SUPER_ADMIN_PASSWORD`, `SUPER_ADMIN_NAME` | No | Optional platform operator bootstrap. Set both email and a unique password of at least 16 characters; keep them outside source control. |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | No in Compose | Backend JDBC settings. Compose uses the `postgres` service. Avoid setting `DATABASE_URL` in local `.env`. |
| `DATABASE_URL`, `PORT` | Production only | Render database connection string and assigned port. Keep unset for the local Compose profile. |
| `VITE_API_BASE_URL` | Build-time only | Frontend API URL; local Compose builds it as `http://localhost:8080`. |

Production also requires a trusted `FRONTEND_ORIGIN`/`FRONTEND_URL`, TLS, strong independent JWT and webhook secrets, Gemini keys if live AI is intended, managed database backups, and a persistent storage strategy (current uploads live in PostgreSQL BYTEA columns). Local defaults are not production secrets.

For optional local Gemini testing, set `GEMINI_API_KEY` in the ignored local `.env` file (or set `GEMINI_EMBEDDING_API_KEY` separately), then recreate the backend and optional worker containers so they receive it. Do not set a `VITE_` key or place the credential in browser code. If no key is configured, the app continues using local embeddings and the safe extractive/fallback response path.
