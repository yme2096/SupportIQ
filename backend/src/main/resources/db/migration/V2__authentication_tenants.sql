ALTER TABLE companies
    ADD COLUMN public_id UUID NOT NULL DEFAULT gen_random_uuid(),
    ADD COLUMN business_email VARCHAR(320),
    ADD COLUMN phone VARCHAR(40),
    ADD COLUMN website VARCHAR(255),
    ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now();
CREATE UNIQUE INDEX uq_companies_public_id ON companies(public_id);

CREATE TABLE roles (
    id SMALLSERIAL PRIMARY KEY,
    name VARCHAR(30) NOT NULL UNIQUE
);
INSERT INTO roles(name) VALUES ('SUPER_ADMIN'), ('COMPANY_ADMIN'), ('AGENT'), ('CUSTOMER');

CREATE TABLE app_users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id UUID REFERENCES companies(id) ON DELETE RESTRICT,
    role_id SMALLINT NOT NULL REFERENCES roles(id),
    email VARCHAR(320) NOT NULL,
    full_name VARCHAR(160) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    phone VARCHAR(40),
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','SUSPENDED','PENDING_VERIFICATION')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_platform_user_company CHECK ((role_id = 1 AND company_id IS NULL) OR (role_id <> 1 AND company_id IS NOT NULL)),
    CONSTRAINT uq_app_user_id_company UNIQUE(id, company_id)
);
CREATE UNIQUE INDEX uq_app_users_email_ci ON app_users(lower(email));
CREATE INDEX idx_app_users_company ON app_users(company_id, created_at DESC);

CREATE TABLE company_settings (
    company_id UUID PRIMARY KEY REFERENCES companies(id) ON DELETE CASCADE,
    settings JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE refresh_tokens (
    token_hash CHAR(64) PRIMARY KEY,
    family_id UUID NOT NULL,
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    issued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    replaced_by_hash CHAR(64)
);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens(user_id, expires_at DESC);
CREATE INDEX idx_refresh_tokens_family ON refresh_tokens(family_id);

CREATE TABLE password_reset_tokens (
    token_hash CHAR(64) PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ
);

CREATE TABLE agent_invitations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id UUID NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    invited_by UUID NOT NULL REFERENCES app_users(id) ON DELETE RESTRICT,
    email VARCHAR(320) NOT NULL,
    role_id SMALLINT NOT NULL REFERENCES roles(id),
    token_hash CHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    accepted_at TIMESTAMPTZ,
    accepted_by UUID REFERENCES app_users(id) ON DELETE SET NULL
);
CREATE INDEX idx_agent_invitations_company ON agent_invitations(company_id, created_at DESC);

CREATE TABLE auth_rate_limits (
    key_hash CHAR(64) PRIMARY KEY,
    window_started TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id UUID REFERENCES companies(id) ON DELETE SET NULL,
    actor_id UUID REFERENCES app_users(id) ON DELETE SET NULL,
    action VARCHAR(80) NOT NULL,
    target_type VARCHAR(80),
    target_id VARCHAR(100),
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_logs_company_created ON audit_logs(company_id, created_at DESC);

ALTER TABLE conversations ADD COLUMN public_access_hash CHAR(64);
ALTER TABLE conversations ADD CONSTRAINT uq_conversations_tenant_id UNIQUE(company_id, id);
ALTER TABLE messages ADD COLUMN company_id UUID;
UPDATE messages m SET company_id=c.company_id FROM conversations c WHERE c.id=m.conversation_id;
ALTER TABLE messages ALTER COLUMN company_id SET NOT NULL;
ALTER TABLE messages ADD CONSTRAINT fk_messages_tenant_conversation
    FOREIGN KEY(company_id, conversation_id) REFERENCES conversations(company_id, id) ON DELETE CASCADE;
CREATE INDEX idx_messages_tenant_conversation ON messages(company_id, conversation_id, created_at);
ALTER TABLE support_tickets ADD CONSTRAINT fk_tickets_tenant_conversation
    FOREIGN KEY(company_id, conversation_id) REFERENCES conversations(company_id, id) ON DELETE CASCADE;
CREATE UNIQUE INDEX uq_one_open_ticket_per_conversation ON support_tickets(conversation_id) WHERE status <> 'RESOLVED';
CREATE INDEX idx_knowledge_tenant_status ON knowledge_documents(company_id, status, created_at DESC);
CREATE INDEX idx_orders_tenant ON demo_orders(company_id, order_ref);

INSERT INTO company_settings(company_id, settings)
SELECT id, '{"workspaceName":"Support workspace","timezone":"Asia/Kolkata"}'::jsonb FROM companies
ON CONFLICT (company_id) DO NOTHING;
