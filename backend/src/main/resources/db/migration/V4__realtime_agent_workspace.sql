ALTER TABLE conversations
    ADD COLUMN assigned_agent_id UUID,
    ADD COLUMN agent_last_read_at TIMESTAMPTZ,
    ADD COLUMN customer_last_read_at TIMESTAMPTZ;

ALTER TABLE messages
    ADD COLUMN visibility VARCHAR(20) NOT NULL DEFAULT 'CUSTOMER',
    ADD COLUMN author_id UUID,
    ADD COLUMN client_message_id UUID,
    ADD COLUMN delivery_status VARCHAR(20) NOT NULL DEFAULT 'SENT',
    ADD COLUMN delivered_at TIMESTAMPTZ,
    ADD COLUMN read_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_message_visibility CHECK (visibility IN ('CUSTOMER','INTERNAL')),
    ADD CONSTRAINT ck_message_delivery CHECK (delivery_status IN ('SENT','DELIVERED','READ'));
ALTER TABLE messages ADD CONSTRAINT uq_message_company_id UNIQUE(company_id,id);
ALTER TABLE messages ADD CONSTRAINT fk_message_author_company FOREIGN KEY(author_id,company_id) REFERENCES app_users(id,company_id);
CREATE UNIQUE INDEX uq_message_idempotency ON messages(company_id,conversation_id,sender_type,client_message_id)
    WHERE client_message_id IS NOT NULL;
CREATE INDEX idx_messages_customer_history ON messages(company_id,conversation_id,visibility,created_at);

ALTER TABLE support_tickets
    ADD COLUMN assigned_agent_id UUID,
    ADD COLUMN resolution_reason VARCHAR(600),
    ADD COLUMN resolved_at TIMESTAMPTZ,
    ADD COLUMN closed_at TIMESTAMPTZ;
ALTER TABLE support_tickets ADD CONSTRAINT uq_ticket_company_id UNIQUE(company_id,id);
ALTER TABLE support_tickets ADD CONSTRAINT ck_ticket_status_v4
    CHECK (status IN ('OPEN','ASSIGNED','IN_PROGRESS','WAITING_FOR_CUSTOMER','WAITING_FOR_AGENT','RESOLVED','CLOSED'));
ALTER TABLE support_tickets ADD CONSTRAINT ck_ticket_priority_v4
    CHECK (priority IN ('LOW','NORMAL','HIGH','URGENT'));
ALTER TABLE support_tickets ADD CONSTRAINT fk_ticket_agent_company
    FOREIGN KEY(assigned_agent_id,company_id) REFERENCES app_users(id,company_id);
ALTER TABLE conversations ADD CONSTRAINT fk_conversation_agent_company
    FOREIGN KEY(assigned_agent_id,company_id) REFERENCES app_users(id,company_id);
CREATE INDEX idx_tickets_company_status_priority ON support_tickets(company_id,status,priority,updated_at DESC);
CREATE INDEX idx_tickets_assignee_status ON support_tickets(company_id,assigned_agent_id,status,updated_at DESC);
DROP INDEX uq_one_open_ticket_per_conversation;
CREATE UNIQUE INDEX uq_one_open_ticket_per_conversation ON support_tickets(company_id,conversation_id)
    WHERE status NOT IN ('RESOLVED','CLOSED');

CREATE TABLE agent_presence (
    company_id UUID NOT NULL,
    agent_id UUID NOT NULL,
    availability VARCHAR(20) NOT NULL DEFAULT 'OFFLINE' CHECK (availability IN ('AVAILABLE','BUSY','OFFLINE')),
    online BOOLEAN NOT NULL DEFAULT FALSE,
    workload_limit INTEGER NOT NULL DEFAULT 10 CHECK (workload_limit BETWEEN 1 AND 100),
    last_assigned_at TIMESTAMPTZ,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(company_id,agent_id),
    FOREIGN KEY(agent_id,company_id) REFERENCES app_users(id,company_id) ON DELETE CASCADE
);
CREATE INDEX idx_agent_presence_available ON agent_presence(company_id,availability,online,updated_at DESC);

CREATE TABLE ticket_assignment_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), company_id UUID NOT NULL,
    ticket_id UUID NOT NULL, assigned_agent_id UUID, actor_id UUID,
    assignment_type VARCHAR(20) NOT NULL CHECK (assignment_type IN ('MANUAL','AUTO','REASSIGN','UNASSIGN')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY(company_id,ticket_id) REFERENCES support_tickets(company_id,id) ON DELETE CASCADE,
    FOREIGN KEY(assigned_agent_id,company_id) REFERENCES app_users(id,company_id),
    FOREIGN KEY(actor_id,company_id) REFERENCES app_users(id,company_id)
);
CREATE INDEX idx_assignment_history_ticket ON ticket_assignment_history(company_id,ticket_id,created_at DESC);

CREATE TABLE ticket_status_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), company_id UUID NOT NULL,
    ticket_id UUID NOT NULL, actor_id UUID, old_status VARCHAR(30), new_status VARCHAR(30) NOT NULL,
    reason VARCHAR(600), created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY(company_id,ticket_id) REFERENCES support_tickets(company_id,id) ON DELETE CASCADE,
    FOREIGN KEY(actor_id,company_id) REFERENCES app_users(id,company_id)
);
CREATE INDEX idx_ticket_status_history_ticket ON ticket_status_history(company_id,ticket_id,created_at DESC);

CREATE TABLE in_app_notifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), company_id UUID NOT NULL,
    user_id UUID NOT NULL, notification_type VARCHAR(40) NOT NULL,
    conversation_id UUID, ticket_id UUID, summary VARCHAR(240) NOT NULL,
    read_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY(user_id,company_id) REFERENCES app_users(id,company_id) ON DELETE CASCADE,
    FOREIGN KEY(company_id,conversation_id) REFERENCES conversations(company_id,id) ON DELETE CASCADE,
    FOREIGN KEY(company_id,ticket_id) REFERENCES support_tickets(company_id,id) ON DELETE CASCADE
);
CREATE INDEX idx_notifications_user_unread ON in_app_notifications(company_id,user_id,read_at,created_at DESC);

CREATE TABLE customer_feedback (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), company_id UUID NOT NULL,
    ticket_id UUID NOT NULL UNIQUE, rating SMALLINT NOT NULL CHECK (rating BETWEEN 1 AND 5),
    comment VARCHAR(1000), created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY(company_id,ticket_id) REFERENCES support_tickets(company_id,id) ON DELETE CASCADE
);

CREATE TABLE conversation_attachments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), company_id UUID NOT NULL,
    conversation_id UUID NOT NULL, message_id UUID NOT NULL,
    uploaded_by_type VARCHAR(20) NOT NULL CHECK (uploaded_by_type IN ('CUSTOMER','AGENT')),
    file_name VARCHAR(180) NOT NULL, mime_type VARCHAR(100) NOT NULL, file_size BIGINT NOT NULL CHECK (file_size BETWEEN 1 AND 5242880),
    file_data BYTEA NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY(company_id,conversation_id) REFERENCES conversations(company_id,id) ON DELETE CASCADE,
    FOREIGN KEY(company_id,message_id) REFERENCES messages(company_id,id) ON DELETE CASCADE
);
CREATE INDEX idx_attachments_message ON conversation_attachments(company_id,conversation_id,message_id);

CREATE TABLE realtime_message_receipts (
    company_id UUID NOT NULL, conversation_id UUID NOT NULL, client_message_id UUID NOT NULL,
    response JSONB, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(company_id,conversation_id,client_message_id),
    FOREIGN KEY(company_id,conversation_id) REFERENCES conversations(company_id,id) ON DELETE CASCADE
);

INSERT INTO company_settings(company_id,settings)
SELECT id,'{"support":{"firstResponseTargetMinutes":15,"resolutionTargetMinutes":1440,"assignmentStrategy":"ROUND_ROBIN","businessHours":{"timezone":"Asia/Kolkata","weekdays":[1,2,3,4,5],"start":"09:00","end":"18:00"},"priorityTargets":{"URGENT":5,"HIGH":15,"NORMAL":60,"LOW":240}}}'::jsonb FROM companies
ON CONFLICT(company_id) DO UPDATE SET settings=company_settings.settings || EXCLUDED.settings,updated_at=now()
WHERE NOT (company_settings.settings ? 'support');

INSERT INTO ticket_status_history(company_id,ticket_id,old_status,new_status,created_at)
SELECT company_id,id,NULL,status,created_at FROM support_tickets;
