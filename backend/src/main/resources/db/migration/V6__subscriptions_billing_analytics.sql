ALTER TABLE companies ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','SUSPENDED'));

CREATE TABLE subscription_plans (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  code VARCHAR(24) NOT NULL UNIQUE,
  name VARCHAR(80) NOT NULL,
  description VARCHAR(500) NOT NULL DEFAULT '',
  monthly_price NUMERIC(12,2),
  annual_price NUMERIC(12,2),
  max_agents INTEGER NOT NULL CHECK(max_agents=-1 OR max_agents>=0),
  max_ai_conversations INTEGER NOT NULL CHECK(max_ai_conversations=-1 OR max_ai_conversations>=0),
  max_knowledge_documents INTEGER NOT NULL CHECK(max_knowledge_documents=-1 OR max_knowledge_documents>=0),
  max_storage_bytes BIGINT NOT NULL CHECK(max_storage_bytes=-1 OR max_storage_bytes>=0),
  analytics_level VARCHAR(24) NOT NULL CHECK(analytics_level IN ('BASIC','ADVANCED','CUSTOM')),
  trial_period_days INTEGER NOT NULL DEFAULT 14 CHECK(trial_period_days BETWEEN 0 AND 365),
  active BOOLEAN NOT NULL DEFAULT TRUE,
  custom_pricing BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO subscription_plans(code,name,description,monthly_price,annual_price,max_agents,max_ai_conversations,max_knowledge_documents,max_storage_bytes,analytics_level,trial_period_days,custom_pricing) VALUES
('STARTER','Starter','For small teams getting started with customer support.',999,9990,2,1000,25,1073741824,'BASIC',14,false),
('GROWTH','Growth','For growing support teams.',2999,29990,10,5000,100,5368709120,'ADVANCED',14,false),
('BUSINESS','Business','For established support operations.',5999,59990,25,15000,500,26843545600,'ADVANCED',14,false),
('ENTERPRISE','Enterprise','Custom limits and pricing for larger organizations.',NULL,NULL,-1,-1,-1,-1,'CUSTOM',14,true);

CREATE TABLE company_subscriptions (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  company_id UUID NOT NULL UNIQUE REFERENCES companies(id) ON DELETE RESTRICT,
  plan_id UUID NOT NULL REFERENCES subscription_plans(id) ON DELETE RESTRICT,
  billing_interval VARCHAR(12) NOT NULL DEFAULT 'MONTHLY' CHECK(billing_interval IN ('MONTHLY','ANNUAL')),
  status VARCHAR(20) NOT NULL CHECK(status IN ('TRIALING','ACTIVE','PAST_DUE','CANCELLED','EXPIRED')),
  started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  trial_ends_at TIMESTAMPTZ,
  current_period_start TIMESTAMPTZ NOT NULL DEFAULT now(),
  current_period_end TIMESTAMPTZ NOT NULL,
  grace_ends_at TIMESTAMPTZ,
  cancel_at_period_end BOOLEAN NOT NULL DEFAULT FALSE,
  cancelled_at TIMESTAMPTZ,
  provider VARCHAR(24) NOT NULL DEFAULT 'DEMO',
  provider_subscription_id VARCHAR(160),
  pending_plan_id UUID REFERENCES subscription_plans(id) ON DELETE RESTRICT,
  pending_billing_interval VARCHAR(12) CHECK(pending_billing_interval IN ('MONTHLY','ANNUAL')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_company_subscriptions_status_period ON company_subscriptions(status,current_period_end);

CREATE TABLE subscription_history (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(), company_id UUID NOT NULL REFERENCES companies(id) ON DELETE RESTRICT,
  subscription_id UUID NOT NULL REFERENCES company_subscriptions(id) ON DELETE CASCADE,
  actor_id UUID REFERENCES app_users(id) ON DELETE SET NULL,
  event_type VARCHAR(40) NOT NULL, old_status VARCHAR(20), new_status VARCHAR(20),
  old_plan VARCHAR(24), new_plan VARCHAR(24), details JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_subscription_history_company ON subscription_history(company_id,created_at DESC);

CREATE TABLE subscription_payments (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(), company_id UUID NOT NULL REFERENCES companies(id) ON DELETE RESTRICT,
  subscription_id UUID NOT NULL REFERENCES company_subscriptions(id) ON DELETE RESTRICT,
  amount NUMERIC(12,2) NOT NULL CHECK(amount>=0), currency CHAR(3) NOT NULL DEFAULT 'INR',
  status VARCHAR(20) NOT NULL CHECK(status IN ('PENDING','SUCCEEDED','FAILED','REFUNDED')),
  provider VARCHAR(24) NOT NULL DEFAULT 'DEMO', provider_reference VARCHAR(160), idempotency_key VARCHAR(120) NOT NULL,
  period_start TIMESTAMPTZ NOT NULL, period_end TIMESTAMPTZ NOT NULL, failure_reason VARCHAR(500),
  demo BOOLEAN NOT NULL DEFAULT TRUE, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), paid_at TIMESTAMPTZ,
  UNIQUE(company_id,idempotency_key)
);
CREATE INDEX idx_subscription_payments_company ON subscription_payments(company_id,created_at DESC);

CREATE TABLE billing_invoices (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(), company_id UUID NOT NULL REFERENCES companies(id) ON DELETE RESTRICT,
  payment_id UUID NOT NULL UNIQUE REFERENCES subscription_payments(id) ON DELETE RESTRICT,
  invoice_number VARCHAR(40) NOT NULL UNIQUE, status VARCHAR(20) NOT NULL CHECK(status IN ('OPEN','PAID','VOID')),
  currency CHAR(3) NOT NULL DEFAULT 'INR', subtotal NUMERIC(12,2) NOT NULL, tax NUMERIC(12,2) NOT NULL DEFAULT 0,
  total NUMERIC(12,2) NOT NULL, description VARCHAR(240) NOT NULL, period_start TIMESTAMPTZ NOT NULL, period_end TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(), paid_at TIMESTAMPTZ
);
CREATE INDEX idx_billing_invoices_company ON billing_invoices(company_id,created_at DESC);

CREATE TABLE usage_records (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(), company_id UUID NOT NULL REFERENCES companies(id) ON DELETE RESTRICT,
  metric VARCHAR(32) NOT NULL CHECK(metric IN ('AI_CONVERSATION','AI_MESSAGE','AI_TOKENS','AGENT','KNOWLEDGE_DOCUMENT','STORAGE_BYTES')),
  quantity BIGINT NOT NULL DEFAULT 1 CHECK(quantity>=0), source_key VARCHAR(160) NOT NULL,
  period_start TIMESTAMPTZ NOT NULL, period_end TIMESTAMPTZ NOT NULL, metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(company_id,metric,source_key)
);
CREATE INDEX idx_usage_records_company_period ON usage_records(company_id,period_start,metric);

CREATE TABLE billing_webhook_events (
  provider VARCHAR(24) NOT NULL, event_id VARCHAR(160) NOT NULL, payload_hash CHAR(64) NOT NULL,
  processed_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY(provider,event_id)
);

CREATE SEQUENCE billing_invoice_number_seq START 1001;

CREATE OR REPLACE FUNCTION supportiq_create_trial_subscription() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE plan UUID; trial_days INTEGER;
BEGIN
  SELECT id,trial_period_days INTO plan,trial_days FROM subscription_plans WHERE code='STARTER' AND active;
  INSERT INTO company_subscriptions(company_id,plan_id,status,trial_ends_at,current_period_start,current_period_end)
    VALUES(NEW.id,plan,CASE WHEN trial_days>0 THEN 'TRIALING' ELSE 'ACTIVE' END,
      CASE WHEN trial_days>0 THEN now()+make_interval(days=>trial_days) ELSE NULL END,
      now(),now()+make_interval(days=>GREATEST(trial_days,30)));
  INSERT INTO subscription_history(company_id,subscription_id,event_type,new_status,new_plan,details)
    SELECT NEW.id,s.id,'TRIAL_STARTED',s.status,'STARTER',jsonb_build_object('trialDays',trial_days) FROM company_subscriptions s WHERE s.company_id=NEW.id;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_company_trial_subscription AFTER INSERT ON companies FOR EACH ROW EXECUTE FUNCTION supportiq_create_trial_subscription();

CREATE OR REPLACE FUNCTION supportiq_meter_ai_message() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE pstart TIMESTAMPTZ; pend TIMESTAMPTZ;
BEGIN
  IF NEW.sender_type='AI' AND NEW.visibility='CUSTOMER' THEN
    SELECT current_period_start,current_period_end INTO pstart,pend FROM company_subscriptions WHERE company_id=NEW.company_id;
    IF pstart IS NOT NULL THEN
      INSERT INTO usage_records(company_id,metric,quantity,source_key,period_start,period_end)
        VALUES(NEW.company_id,'AI_CONVERSATION',1,NEW.conversation_id::text,pstart,pend) ON CONFLICT DO NOTHING;
      INSERT INTO usage_records(company_id,metric,quantity,source_key,period_start,period_end)
        VALUES(NEW.company_id,'AI_MESSAGE',1,NEW.id::text,pstart,pend) ON CONFLICT DO NOTHING;
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_meter_ai_message AFTER INSERT ON messages FOR EACH ROW EXECUTE FUNCTION supportiq_meter_ai_message();

-- Existing tenants get a trial on migration. Customer data and V1-V5 tables remain unchanged.
INSERT INTO company_subscriptions(company_id,plan_id,status,trial_ends_at,current_period_start,current_period_end)
SELECT c.id,p.id,CASE WHEN p.trial_period_days>0 THEN 'TRIALING' ELSE 'ACTIVE' END,
 CASE WHEN p.trial_period_days>0 THEN now()+make_interval(days=>p.trial_period_days) ELSE NULL END,
 now(),now()+make_interval(days=>GREATEST(p.trial_period_days,30))
FROM companies c CROSS JOIN subscription_plans p WHERE p.code='STARTER'
ON CONFLICT(company_id) DO NOTHING;
INSERT INTO subscription_history(company_id,subscription_id,event_type,new_status,new_plan,details)
SELECT s.company_id,s.id,'TRIAL_STARTED',s.status,'STARTER','{"source":"V6 migration"}'::jsonb
FROM company_subscriptions s WHERE NOT EXISTS(SELECT 1 FROM subscription_history h WHERE h.subscription_id=s.id);

INSERT INTO audit_logs(company_id,action,target_type,target_id,details)
SELECT company_id,'TRIAL_STARTED','SUBSCRIPTION',id::text,'{"source":"V6 migration"}'::jsonb FROM company_subscriptions
WHERE NOT EXISTS(SELECT 1 FROM audit_logs a WHERE a.target_type='SUBSCRIPTION' AND a.target_id=company_subscriptions.id::text);
