# Production environment variables

The Blueprint contains no literal credentials. Render generates JWT and webhook secrets. Keep all secret values in Render's encrypted environment settings and rotate them if exposed.

| Variable | Service | Required | Secret? | Purpose/source |
|---|---|---:|---:|---|
| `DATABASE_URL` | Backend, worker | Yes | Yes | Render private PostgreSQL `connectionString`; startup maps `postgres://` to JDBC without logging it. |
| `PORT` | Backend | Yes | No | Render web port (`10000`); Spring binds to `PORT`. |
| `FRONTEND_ORIGIN` | Backend, worker | Yes | No | Exact HTTPS static-site origin; controls CORS/auth origin checks. |
| `FRONTEND_URL` | Backend, worker | Yes | No | Exact HTTPS frontend base URL for auth links/redirects. |
| `SUPPORTIQ_JWT_SECRET` | Backend, worker | Yes | Yes | Render-generated signing key shared by the API and worker. |
| `AUTH_COOKIE_SECURE` | Backend | Yes | No | `true` for HTTPS refresh cookies. |
| `LOCAL_AUTH_LINK_EXPOSURE` | Backend | Yes | No | `false`; do not return reset/invitation secrets in API responses. |
| `SEED_DEMO_ADMIN` | Backend | Yes | No | `false`; production must not create the local ShopSmart demo admin. |
| `GEMINI_API_KEY` | Backend, worker | Optional | Yes | Real Gemini generation credential, entered in Render only. Blank uses configured safe answer fallback. |
| `GEMINI_EMBEDDING_API_KEY` | Backend, worker | Optional | Yes | Embedding credential; can use a separate key. Blank means new document embedding cannot complete. |
| `EMBEDDING_PROVIDER` | Backend, worker | Yes | No | `gemini`. |
| `GEMINI_MODEL` | Backend | Yes | No | Answer-generation model selection; adjust to an available model in the provider account. |
| `GEMINI_EMBEDDING_MODEL` | Backend, worker | Yes | No | `gemini-embedding-2`, configured for 768 dimensions by the application. |
| `RAG_CHUNK_SIZE`, `RAG_CHUNK_OVERLAP` | Backend | Yes | No | Chunk tuning values (`1800`, `240`). |
| `DB_POOL_MAX_SIZE`, `DB_POOL_MIN_IDLE` | Backend | Yes | No | Hikari pool (`8`, `2`). Keep within managed database connection limits. |
| `DB_POOL_MAX_SIZE`, `DB_POOL_MIN_IDLE` | Worker | Yes | No | Worker Hikari pool (`3`, `1`). |
| `AI_PROCESSING_DISPATCH_LOCALLY` | Backend, worker | Yes | No | `false`; durable jobs are claimed by the worker. |
| `AI_WORKER_ENABLED` | Backend / worker | Yes | No | `false` on web service, `true` on worker. |
| `AI_WORKER_POLL_MS`, `AI_WORKER_STALE_MINUTES` | Backend, worker | Yes | No | Worker polling/recovery cadence (`5000`, `30`). |
| `PAYMENT_PROVIDER`, `BILLING_PROVIDER` | Backend | Yes | No | `DEMO`; do not enable live processing with this codebase. |
| `PAYMENT_LIVE_ENABLED` | Backend | Yes | No | `false`. |
| `PAYMENT_WEBHOOK_SECRET`, `BILLING_WEBHOOK_SECRET` | Backend | Yes | Yes | Render-generated signing secrets for DEMO webhook verification. |
| `BILLING_GRACE_DAYS` | Backend | Yes | No | Subscription grace window (`7`). |

There is no production email provider configuration in the application. Super Admin creation is optional and must be configured separately by an authorized owner using `SUPER_ADMIN_EMAIL` and a unique `SUPER_ADMIN_PASSWORD` (at least 16 characters); do not enable it casually. The app's defaults keep it disabled. Never copy `.env` or local fallback credentials to Render.
