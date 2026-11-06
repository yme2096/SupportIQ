# Phase 3 implementation plan — semantic knowledge and RAG

## Existing state inspected

- Spring Boot 3.5.7 / Java 21, `JdbcTemplate`, PostgreSQL 16 in the `pgvector/pgvector:pg16` image, Flyway migrations V1/V2.
- Knowledge documents currently keep one whole-text `content` field and support TXT/MD upload; upload is synchronous, with no versioning, preview, deletion, or lifecycle/retry controls.
- Chat retrieves by simple word overlap and calls Gemini `generateContent` only when configured. The AI fallback is deterministic. Existing staff and public/widget flows already resolve company scope securely and create tenant-bound agent handoff tickets.
- Existing database volume is `supportiq_mvp_supportiq_pgdata`. Phase 3 must migrate additively and preserve old documents, conversations, and tickets.

## Work plan

1. Back up the current project/database and add additive schema V3 for pgvector, document lifecycle/version metadata, original file data, chunks and references, vector/full-text indexes, and company AI settings.
2. Add PDF, DOCX, TXT and MD extraction, validation, normalization, section/page aware chunking, asynchronous processing, duplicate detection, retry handling, and accurate processing states.
3. Add a configurable embedding provider (Gemini REST API, 768 dimensions) and store/query native pgvector values. Use tenant-filtered retrieval, configurable top-K/threshold, and safe lexical fallback only when embeddings are unavailable.
4. Replace chat's keyword-only knowledge path with shared, tenant-scoped retrieval and grounded response generation; preserve refund controls, widgets, hosted chat, source references, safe fallback, and handoff.
5. Add Company Admin document lifecycle APIs and a functional responsive knowledge manager with upload progress, search/filter, preview, status/error, retry, delete, and version history. Add AI settings without exposing credentials.
6. Add unit/integration tests for parsing, processing, retrieval, citations, tenant isolation, safety fallback, and handoff. Run migration on the existing DB, backend/frontend checks, and preserve the volume.
7. Update README, environment example, status, API notes, and produce `AI_RAG_ARCHITECTURE.md` and `PHASE3_TEST_REPORT.md`.

## Progress

- [x] Inspect existing code, database schema, and runtime.
- [x] Create pre-change backup.
- [x] Add migration and pgvector schema.
- [x] Build ingestion and retry pipeline.
- [x] Add vector embeddings and tenant-filtered retrieval.
- [x] Integrate grounded answers and company AI settings in every chat channel.
- [x] Add knowledge management UI and lifecycle APIs.
- [x] Add/execute Phase 3 tests and migrate the existing volume.
- [x] Update documentation and report.

## Provider choices

- Google Gemini Embeddings 2 (`gemini-embedding-2`) with 768 dimensions; embedding credentials remain server-side. The existing Gemini API key will be shared by default, with a separate optional embedding key supported.
- PostgreSQL `vector(768)` and cosine similarity; the current Docker image provides the extension. Tenant ID is always part of the SQL predicate in addition to vector similarity.
- Apache Tika for file extraction, with PDFBox page extraction for accurate PDF page references.
- Asynchronous Spring task executor backed by PostgreSQL document state. A retry endpoint safely re-queues failed processing.

## Preservation and safety

- V3 is additive. Existing document rows remain and are indexed asynchronously when configured; existing content remains readable if embeddings are not configured.
- Never send cross-company chunks to the LLM. Retrieval, preview, retry, delete, version, and document search all use the authenticated company claim.
- Treat customer messages and retrieved text as untrusted data; use an explicit grounding prompt and disclose no prompt internals.
- External provider failures must not fabricate answers or citations; fail processing with a useful status and route unknown customer questions to agents.
