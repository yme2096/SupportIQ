# SupportIQ AI knowledge and retrieval

## Ingestion

Company Admins can upload PDF, DOCX, TXT, and Markdown files (maximum 10 MB). The API validates extension, signature/MIME, empty content, and size, stores the original file plus a SHA-256 digest, and adds a tenant-bound document record. Repeated active file contents are rejected. Revisions are tracked by filename and version; a prior ready revision is archived only after its replacement has processed successfully.

An isolated bounded executor moves records through `UPLOADED → PROCESSING → READY` or `FAILED`. PDFBox extracts PDF pages so citations can identify page numbers; Apache Tika parses DOCX. Text normalization and heading detection attach section names. Chunking keeps each page/section together and applies bounded size and overlap. Embeddings are requested in batches and chunk rows are committed transactionally. Processing failures preserve the original upload, set an actionable error, and allow retry/reprocess with an attempt cap. Admins can inspect extracted content and archive documents; archive removes it from retrieval without deleting the audit trail.

## Semantic retrieval and answers

The default provider is Gemini Embeddings 2 using 768-dimensional vectors stored as PostgreSQL `vector(768)`. HNSW cosine indexing supports nearest-neighbor search. Retrieval always filters by the authenticated/resolved company UUID and only reads `READY` documents before sending chunks to the selected text model. Company settings control whether AI is enabled, provider, model, language, tone, top-K, relevance threshold, unknown-answer handoff, and safe fallback copy. Provider secrets are server environment values and are rejected in company settings.

When embeddings are available, retrieval uses vector cosine similarity and the configured relevance threshold. If embedding search is unavailable, PostgreSQL full-text search is used for compatibility with legacy documents and safe degraded operation. It is not presented as semantic similarity: relevance scores are omitted or zero in that mode. The prompt treats stored text and customer questions as untrusted and requires answers to be grounded only in provided excerpts. If evidence is absent, conflicted, or provider generation fails, the assistant returns the configured fallback or recommends an agent; it does not invent a citation. Refund/payment guardrails remain ahead of the AI path.

Source references with document name, page, section, and relevance are stored on AI messages and returned to authenticated workspace and public chat clients. Widget and hosted-page chat share the same retrieval and answer service. Public company identifiers resolve the tenant; a random per-conversation capability token protects transcript reads and continuations. Conversation/message/document/chunk keys and SQL filters enforce tenant ownership.

## Runtime configuration

Set `GEMINI_API_KEY` for generation and embeddings. To use a separate embedding credential, set `GEMINI_EMBEDDING_API_KEY`. Optional settings include `GEMINI_MODEL`, `GEMINI_EMBEDDING_MODEL`, `EMBEDDING_PROVIDER`, `RAG_CHUNK_SIZE`, and `RAG_CHUNK_OVERLAP`. The default model names are `gemini-3.8-flash` and `gemini-embedding-2`. Never put credentials in company settings or client-side widget code.

V3 is additive and keeps existing document rows, content, conversations, and tickets. Existing READY document content is copied into searchable chunks with null vectors; use the admin reprocess action after configuring credentials to create embeddings. A pre-migration database dump and source archive are stored under `backups/phase3-before-2026-11-01/`.

## Boundaries

- No OCR is applied to image-only/scanned PDFs; these fail with a clear no-readable-text status.
- Retrieval is grounded from company documents but is not a guarantee that source documents are current or correct. Conflicts trigger handoff heuristics; review source documents when changing policy.
- Gemini is a network dependency. Missing keys, quota/rate errors, and provider failures are surfaced in document status and use a conservative chat fallback.
- Uploaded documents should be policy-approved. Malware scanning and external storage are future production hardening.
