CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;
-- A prior isolated test migration may have installed the relocatable extension in its test schema.
ALTER EXTENSION vector SET SCHEMA public;

ALTER TABLE knowledge_documents
    ADD COLUMN content_hash CHAR(64),
    ADD COLUMN original_file BYTEA,
    ADD COLUMN mime_type VARCHAR(160),
    ADD COLUMN file_size BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN version INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN supersedes_id UUID,
    ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN processing_error VARCHAR(600),
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD CONSTRAINT ck_knowledge_document_status CHECK (status IN ('UPLOADED','PROCESSING','READY','FAILED','ARCHIVED')),
    ADD CONSTRAINT ck_knowledge_document_version CHECK (version > 0),
    ADD CONSTRAINT uq_knowledge_document_company_id UNIQUE(company_id, id);

ALTER TABLE knowledge_documents
    ADD CONSTRAINT fk_knowledge_document_supersedes
    FOREIGN KEY(company_id, supersedes_id) REFERENCES knowledge_documents(company_id, id);

CREATE UNIQUE INDEX uq_knowledge_document_active_hash
    ON knowledge_documents(company_id, content_hash)
    WHERE content_hash IS NOT NULL AND status <> 'ARCHIVED';
CREATE INDEX idx_knowledge_document_company_status
    ON knowledge_documents(company_id, status, created_at DESC);

CREATE TABLE knowledge_chunks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id UUID NOT NULL,
    document_id UUID NOT NULL,
    chunk_index INTEGER NOT NULL,
    chunk_text TEXT NOT NULL,
    page_number INTEGER,
    section_title VARCHAR(240),
    embedding VECTOR(768),
    embedding_provider VARCHAR(40),
    embedding_model VARCHAR(100),
    search_vector TSVECTOR GENERATED ALWAYS AS (to_tsvector('simple'::regconfig, chunk_text)) STORED,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_knowledge_chunk_index UNIQUE(company_id, document_id, chunk_index),
    CONSTRAINT fk_knowledge_chunk_document FOREIGN KEY(company_id, document_id)
        REFERENCES knowledge_documents(company_id, id) ON DELETE CASCADE
);
CREATE INDEX idx_knowledge_chunk_tenant_document ON knowledge_chunks(company_id, document_id);
CREATE INDEX idx_knowledge_chunk_search ON knowledge_chunks USING GIN(search_vector);
CREATE INDEX idx_knowledge_chunk_embedding_hnsw ON knowledge_chunks USING hnsw(embedding vector_cosine_ops)
    WHERE embedding IS NOT NULL;

-- Preserve searchable text for documents created before Phase 3. Their vectors remain NULL until an admin reprocesses them.
INSERT INTO knowledge_chunks(company_id, document_id, chunk_index, chunk_text, section_title)
SELECT company_id, id, 0, content, file_name
FROM knowledge_documents
WHERE status='READY' AND content IS NOT NULL AND btrim(content) <> ''
ON CONFLICT(company_id, document_id, chunk_index) DO NOTHING;

ALTER TABLE messages
    ADD COLUMN source_refs JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN retrieval_score DOUBLE PRECISION;

UPDATE company_settings
SET settings = settings || '{"ai":{"enabled":true,"provider":"GEMINI","model":"gemini-3.8-flash","language":"English","tone":"Friendly and concise","maxChunks":4,"relevanceThreshold":0.42,"handoffOnUnknown":true,"fallbackResponse":"I could not find that information in the approved company documents. I can connect you with a support agent."}}'::jsonb,
    updated_at=now()
WHERE NOT (settings ? 'ai');
