# Phase 3 verification report

Date: 2026-11-01

## Automated checks

- Frontend Vitest: 3 tests passed.
- Frontend production build: passed.
- Backend Docker integration/unit suite: 7 tests passed, 0 failures/errors.
- Compose configuration validation: passed.
- Backend and frontend container images: built successfully.

Backend tests include:

- Registration/authentication, expired token rejection, company widget configuration, cross-company conversation isolation, and role/team boundaries.
- Upload under one company and absence from the other company's knowledge list.
- Deterministic test-only vector embeddings used to prove retrieved source references stay with the owning company; unrelated company chat does not receive the other company's document.
- PDF extraction with page references, DOCX parsing, Markdown headings/normalization, unsupported/invalid/empty file rejection, and bounded page/section-aware chunks.

The test application uses its dedicated `phase3_test` PostgreSQL schema. V3 ran successfully there.

## Existing workspace database

- A pre-migration database dump and source archive were created under `backups/phase3-before-2026-11-01/`.
- Applied Flyway V3 to the existing `public` schema. `pg_extension.vector` is installed in `public` and V3 is recorded successful.
- The existing PostgreSQL volume remains mounted. After migration, the live database still contains 2 companies, 3 knowledge documents, 5 conversations, and 3 tickets.
- Live backend health reports both service and database UP; frontend responds HTTP 200.

## Provider limitation

No Gemini key is configured in this local `.env`, so a real Gemini embedding/generation request was not made. The tenant-retrieval integration scenario uses deterministic test-only embeddings. To enable actual production embeddings and generated grounded answers, configure `GEMINI_API_KEY` (or `GEMINI_EMBEDDING_API_KEY` for embeddings separately), then reprocess any legacy documents that need vectors. Existing text remains available to full-text fallback without that key.

## Outcome

The implementation and migration passed the checks above. The two-company test proves document and retrieved-source isolation, while existing authentication integration assertions cover conversations, tickets, and agents. Agent assignment/availability is not part of this phase; an escalation creates a tenant-bound ticket for the existing inbox flow.
