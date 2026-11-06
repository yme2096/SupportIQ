-- Additive order/refund model. Keep demo_orders and all legacy rows intact.
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE TABLE customer_orders (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  company_id UUID NOT NULL REFERENCES companies(id) ON DELETE RESTRICT,
  customer_id UUID REFERENCES app_users(id) ON DELETE SET NULL,
  order_number VARCHAR(64) NOT NULL,
  customer_name VARCHAR(160) NOT NULL,
  customer_email VARCHAR(320) NOT NULL,
  verification_postal_hash CHAR(64) NOT NULL,
  ordered_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  delivered_at TIMESTAMPTZ,
  items JSONB NOT NULL DEFAULT '[]'::jsonb,
  total_amount NUMERIC(12,2) NOT NULL CHECK(total_amount > 0),
  currency CHAR(3) NOT NULL,
  payment_status VARCHAR(24) NOT NULL CHECK(payment_status IN ('PENDING','PAID','FAILED','PARTIALLY_REFUNDED','REFUNDED')),
  fulfillment_status VARCHAR(24) NOT NULL CHECK(fulfillment_status IN ('PLACED','CONFIRMED','PROCESSING','SHIPPED','OUT_FOR_DELIVERY','DELIVERED','CANCELLED','RETURNED')),
  delivery_address JSONB NOT NULL DEFAULT '{}'::jsonb,
  tracking_number VARCHAR(120),
  is_demo BOOLEAN NOT NULL DEFAULT false,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_order_company_id UNIQUE(company_id,id),
  CONSTRAINT uq_order_company_number UNIQUE(company_id,order_number),
  CONSTRAINT fk_order_customer_company FOREIGN KEY(customer_id,company_id) REFERENCES app_users(id,company_id) ON DELETE RESTRICT
);
CREATE INDEX idx_customer_orders_company_date ON customer_orders(company_id,ordered_at DESC);
CREATE INDEX idx_customer_orders_customer_date ON customer_orders(company_id,customer_email,ordered_at DESC);

CREATE TABLE company_refund_policies (
  company_id UUID PRIMARY KEY REFERENCES companies(id) ON DELETE CASCADE,
  return_window_days INTEGER NOT NULL DEFAULT 7 CHECK(return_window_days BETWEEN 0 AND 365),
  eligible_conditions JSONB NOT NULL DEFAULT '["UNOPENED","UNUSED","DAMAGED","WRONG_ITEM"]'::jsonb,
  excluded_categories JSONB NOT NULL DEFAULT '[]'::jsonb,
  auto_approve_limit NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK(auto_approve_limit >= 0),
  updated_by UUID REFERENCES app_users(id) ON DELETE SET NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE FUNCTION create_default_refund_policy() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  INSERT INTO company_refund_policies(company_id) VALUES(NEW.id) ON CONFLICT(company_id) DO NOTHING;
  RETURN NEW;
END;
$$;
CREATE TRIGGER trg_company_default_refund_policy AFTER INSERT ON companies
FOR EACH ROW EXECUTE FUNCTION create_default_refund_policy();
INSERT INTO company_refund_policies(company_id) SELECT id FROM companies ON CONFLICT DO NOTHING;

CREATE TABLE refunds (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  company_id UUID NOT NULL,
  order_id UUID NOT NULL,
  customer_id UUID REFERENCES app_users(id) ON DELETE SET NULL,
  requested_amount NUMERIC(12,2) NOT NULL CHECK(requested_amount > 0),
  approved_amount NUMERIC(12,2) CHECK(approved_amount > 0),
  currency CHAR(3) NOT NULL,
  reason VARCHAR(1000) NOT NULL,
  product_condition VARCHAR(32),
  product_category VARCHAR(80) NOT NULL DEFAULT 'GENERAL',
  status VARCHAR(24) NOT NULL DEFAULT 'REQUESTED' CHECK(status IN ('REQUESTED','UNDER_REVIEW','APPROVED','INITIATED','PROCESSING','CREDITED','FAILED','REJECTED','UNKNOWN')),
  eligibility JSONB NOT NULL DEFAULT '{}'::jsonb,
  reviewed_by UUID REFERENCES app_users(id) ON DELETE SET NULL,
  reviewed_at TIMESTAMPTZ,
  approval_note VARCHAR(1000),
  provider VARCHAR(24) NOT NULL DEFAULT 'DEMO',
  provider_reference VARCHAR(160),
  arn VARCHAR(160),
  idempotency_key VARCHAR(120) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT fk_refund_order_company FOREIGN KEY(company_id,order_id) REFERENCES customer_orders(company_id,id) ON DELETE RESTRICT,
  CONSTRAINT uq_refund_company_id UNIQUE(company_id,id),
  CONSTRAINT uq_refund_idempotency UNIQUE(company_id,idempotency_key)
);
CREATE INDEX idx_refunds_company_status ON refunds(company_id,status,created_at DESC);
CREATE INDEX idx_refunds_order ON refunds(company_id,order_id,created_at DESC);

CREATE TABLE refund_events (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  company_id UUID NOT NULL REFERENCES companies(id) ON DELETE RESTRICT,
  refund_id UUID NOT NULL,
  event_type VARCHAR(48) NOT NULL,
  actor_id UUID REFERENCES app_users(id) ON DELETE SET NULL,
  details JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY(company_id,refund_id) REFERENCES refunds(company_id,id) ON DELETE CASCADE
);
CREATE INDEX idx_refund_events_history ON refund_events(company_id,refund_id,created_at);

CREATE TABLE order_lookup_limits (
  key_hash CHAR(64) PRIMARY KEY,
  window_started TIMESTAMPTZ NOT NULL DEFAULT now(),
  attempts INTEGER NOT NULL DEFAULT 0,
  blocked_until TIMESTAMPTZ
);
CREATE TABLE order_access_tokens (
  token_hash CHAR(64) PRIMARY KEY,
  company_id UUID NOT NULL,
  order_id UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at TIMESTAMPTZ NOT NULL,
  revoked_at TIMESTAMPTZ,
  FOREIGN KEY(company_id,order_id) REFERENCES customer_orders(company_id,id) ON DELETE CASCADE
);

CREATE TABLE payment_webhook_events (
  provider VARCHAR(24) NOT NULL,
  event_id VARCHAR(180) NOT NULL,
  payload_hash CHAR(64) NOT NULL,
  received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  processed_at TIMESTAMPTZ,
  PRIMARY KEY(provider,event_id)
);

-- Retain the old demo order table and rows. Add explicit ownership-safe demo records
-- in the new model for existing sample references; the customer address data is synthetic.
INSERT INTO customer_orders(company_id,order_number,customer_name,customer_email,verification_postal_hash,ordered_at,delivered_at,items,total_amount,currency,payment_status,fulfillment_status,delivery_address,is_demo)
SELECT d.company_id,d.order_ref,'Demo customer',lower(replace(d.order_ref,'-','')) || '@shopsmart.demo',
       encode(digest('00000','sha256'),'hex'),now()-interval '14 days',
       CASE WHEN d.order_status='DELIVERED' THEN now()-interval '2 days' ELSE NULL END,
       jsonb_build_array(jsonb_build_object('name','Demo item','quantity',1,'amount',d.amount,'category','GENERAL')),
       d.amount,d.currency,
       CASE WHEN d.refund_status='INITIATED' THEN 'PAID' ELSE 'PAID' END,
       CASE WHEN d.order_status='REFUND_INITIATED' THEN 'DELIVERED' ELSE d.order_status END,
       '{"city":"Demo City","postalCode":"00000"}'::jsonb,true
FROM demo_orders d ON CONFLICT(company_id,order_number) DO NOTHING;
