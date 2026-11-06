# Known Limitations

## External credentials and services

- No Gemini API key is configured in this local workspace. Live embedding generation and grounded Gemini generation have not been verified. Local hash embeddings and extractive matching/fallback are available.
- Payment and subscription billing are DEMO only. No live charge or real refund is sent.
- Email delivery is not implemented. Invitation and password-reset flows do not send email; local link exposure is intentionally configurable.
- SMS, object storage, malware scanning, OCR for image-only PDFs, and an external production rate-limit/WAF are not included.
- Render resources have not been created or deployed. Production TLS, hosted WebSocket behavior, managed backup/restore, and production database migration have not been verified.

## Local and security scope

- Local Compose intentionally has development defaults, including a demo user and local signing/webhook values. These must never be used in production. Treat the local instance as a private demo.
- The no-key embedding model is deterministic hashing, not a pretrained semantic model. Extractive answer matching requires overlap with approved text; other questions safely hand off.
- Public widget CORS permits cross-origin embedding by design. Public routes are credential-free, while transcript access requires a conversation capability. Add edge rate limiting before high-traffic use.
- Configurable external logo URLs can load third-party image content. Use trusted HTTPS origins.
- Nginx does not yet set a fully tested Content-Security-Policy or Permissions-Policy. Malware scanning and external penetration testing remain outstanding.
- Uploaded originals and conversation attachments are PostgreSQL BYTEA. This gives local-volume durability but increases database backup size; production storage/retention sizing needs review.
- A single database-polling worker and bounded local executor are adequate for the current demonstration scope. Queue throughput and multi-replica capacity have not been load-tested.
- Performance claims and capacity benchmarks are not available; no latency, AI accuracy, or scale metrics should be claimed.

## GitHub and completion state

- There is no configured project-specific Git remote. The current shell resolves Git to `C:\`, so GitHub readiness is not asserted until a project-owned repository boundary, secret scan, and private destination are verified.
- The current local browser walkthrough and test evidence are in [FINAL_LOCAL_READINESS_REPORT.md](FINAL_LOCAL_READINESS_REPORT.md). Super Admin UI was exercised on an isolated regression schema; do not mistake those test rows for production data.
