package com.supportiq.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.supportiq.billing.BillingProvider;
import com.supportiq.security.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.*;

@Service
public class BillingService {
    private final JdbcTemplate db;
    private final BillingProvider provider;
    private final ObjectMapper json = new ObjectMapper();
    @Value("${app.billing.mode:DEMO}") private String billingMode;
    @Value("${app.billing.grace-days:7}") private int graceDays;

    public BillingService(JdbcTemplate db, BillingProvider provider) { this.db=db; this.provider=provider; }

    public List<Map<String,Object>> publicPlans() { return db.queryForList("select code,name,description,monthly_price,annual_price,max_agents,max_ai_conversations,max_knowledge_documents,max_storage_bytes,analytics_level,trial_period_days,custom_pricing from subscription_plans where active order by case code when 'STARTER' then 1 when 'GROWTH' then 2 when 'BUSINESS' then 3 else 4 end"); }
    public List<Map<String,Object>> platformPlans() { requirePlatformAdmin(); return db.queryForList("select * from subscription_plans order by code"); }

    @Transactional public Map<String,Object> subscription() { UUID company=company(); refreshLifecycle(company); return db.queryForMap("select s.id,s.status,s.billing_interval,s.started_at,s.trial_ends_at,s.current_period_start,s.current_period_end,s.grace_ends_at,s.cancel_at_period_end,s.cancelled_at,s.provider,s.pending_billing_interval,(select code from subscription_plans where id=s.pending_plan_id) pending_plan_code,p.code plan_code,p.name plan_name,p.monthly_price,p.annual_price,p.max_agents,p.max_ai_conversations,p.max_knowledge_documents,p.max_storage_bytes,p.analytics_level,p.custom_pricing from company_subscriptions s join subscription_plans p on p.id=s.plan_id where s.company_id=?",company); }

    @Transactional public Map<String,Object> usage() {
        UUID c=company(); refreshLifecycle(c);
        Map<String,Object> sub=subscriptionRow(c);
        Map<String,Object> result=new LinkedHashMap<>(); result.put("subscription",sub);
        Map<String,Long> metrics=new LinkedHashMap<>();
        metrics.put("aiConversations",number("select count(*) from usage_records where company_id=? and metric='AI_CONVERSATION' and period_start=(select current_period_start from company_subscriptions where company_id=?)",c,c));
        metrics.put("aiMessages",number("select count(*) from usage_records where company_id=? and metric='AI_MESSAGE' and period_start=(select current_period_start from company_subscriptions where company_id=?)",c,c));
        metrics.put("aiTokens",number("select coalesce(sum(quantity),0) from usage_records where company_id=? and metric='AI_TOKENS' and period_start=(select current_period_start from company_subscriptions where company_id=?)",c,c));
        metrics.put("agents",number("select count(*) from app_users u join roles r on r.id=u.role_id where u.company_id=? and u.status='ACTIVE' and r.name='AGENT'",c));
        metrics.put("knowledgeDocuments",number("select count(*) from knowledge_documents where company_id=? and status<>'ARCHIVED'",c));
        metrics.put("storageBytes",number("select coalesce((select sum(file_size) from knowledge_documents where company_id=? and status<>'ARCHIVED'),0)+coalesce((select sum(file_size) from conversation_attachments where company_id=?),0)",c,c));
        result.put("usage",metrics);
        Map<String,Object> limits=db.queryForMap("select max_agents,max_ai_conversations,max_knowledge_documents,max_storage_bytes from subscription_plans where id=?",sub.get("plan_id"));
        result.put("limits",limits);
        result.put("warnings",Map.of("agents",warning(metrics.get("agents"),limits.get("max_agents")),"aiConversations",warning(metrics.get("aiConversations"),limits.get("max_ai_conversations")),"knowledgeDocuments",warning(metrics.get("knowledgeDocuments"),limits.get("max_knowledge_documents")),"storageBytes",warning(metrics.get("storageBytes"),limits.get("max_storage_bytes"))));
        return result;
    }

    private boolean warning(long used,Object limit) { long max=((Number)limit).longValue(); return max>=0&&used>=Math.ceil(max*.8); }

    @Transactional public Map<String,Object> startCheckout(String planCode,String interval,String idempotencyKey) {
        UUID c=company(), actor=TenantContext.userIdOrNull(); requireCompanyAdmin(); refreshLifecycle(c);
        String cycle=normalizeInterval(interval);
        if(!"DEMO".equalsIgnoreCase(billingMode)) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"No live subscription billing provider is configured.");
        String key=normalizeIdempotency(idempotencyKey);
        List<Map<String,Object>> prior=db.queryForList("select p.id,p.status,p.provider_reference,i.invoice_number from subscription_payments p left join billing_invoices i on i.payment_id=p.id where p.company_id=? and p.idempotency_key=?",c,key);
        if(!prior.isEmpty()) return paymentResponse(prior.getFirst(),true);
        Map<String,Object> plan=plan(planCode,true);
        BigDecimal amount=price(plan,cycle);
        Map<String,Object> sub=lockedSubscription(c);
        String currentCode=Objects.toString(sub.get("plan_code"));
        BigDecimal currentPrice=currentPrice(sub);
        if(plan.get("monthly_price")==null && plan.get("annual_price")==null) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Enterprise plans require a custom quote before checkout.");
        boolean fulfillsScheduledDowngrade=planCode.equalsIgnoreCase(Objects.toString(sub.get("pending_plan_code"),""));
        if(!fulfillsScheduledDowngrade&&!currentCode.equalsIgnoreCase(planCode) && amount.compareTo(currentPrice)<0 && Set.of("ACTIVE","TRIALING").contains(Objects.toString(sub.get("status")))) {
            db.update("update company_subscriptions set pending_plan_id=?,pending_billing_interval=?,updated_at=now() where company_id=?",plan.get("id"),cycle,c);
            history(c,(UUID)sub.get("id"),actor,"DOWNGRADE_SCHEDULED",Objects.toString(sub.get("status")),Objects.toString(sub.get("status")),currentCode,planCode,Map.of("effectiveAt",Objects.toString(sub.get("current_period_end"))));
            audit(c,actor,"SUBSCRIPTION_DOWNGRADE_SCHEDULED",(UUID)sub.get("id"),Map.of("plan",planCode,"interval",cycle));
            return Map.of("scheduled",true,"effectiveAt",sub.get("current_period_end"),"planCode",planCode,"message","Downgrade scheduled for the next renewal. Current access continues until then.");
        }
        OffsetDateTime start=OffsetDateTime.now(); OffsetDateTime end=cycle.equals("ANNUAL")?start.plusYears(1):start.plusMonths(1);
        UUID payment=db.queryForObject("insert into subscription_payments(company_id,subscription_id,amount,currency,status,provider,idempotency_key,period_start,period_end,demo) values(?,?,?,'INR','PENDING','DEMO',?,?,?,true) returning id",UUID.class,c,sub.get("id"),amount,key,start,end);
        db.update("update company_subscriptions set pending_plan_id=?,pending_billing_interval=?,updated_at=now() where company_id=?",plan.get("id"),cycle,c);
        String invoiceNo="SI-"+start.getYear()+"-"+String.format(Locale.ROOT,"%06d",db.queryForObject("select nextval('billing_invoice_number_seq')",Long.class));
        String description=Objects.toString(plan.get("name"))+" "+cycle.toLowerCase(Locale.ROOT)+" subscription";
        db.update("insert into billing_invoices(company_id,payment_id,invoice_number,status,currency,subtotal,tax,total,description,period_start,period_end) values(?, ?, ?, 'OPEN','INR',?,0,?,?,?,?)",c,payment,invoiceNo,amount,amount,description,start,end);
        BillingProvider.Checkout checkout=provider.createCheckout(payment,amount,"INR",description);
        audit(c,actor,"SUBSCRIPTION_CHECKOUT_CREATED",payment,Map.of("plan",planCode,"interval",cycle,"amount",amount,"provider","DEMO"));
        return Map.of("paymentId",payment,"status","PENDING","invoiceNumber",invoiceNo,"amount",amount,"currency","INR","planCode",planCode,"billingInterval",cycle,"checkoutUrl",checkout.checkoutUrl(),"providerReference",checkout.providerReference(),"demo",checkout.demo());
    }

    @Transactional public Map<String,Object> simulateDemoPayment(UUID paymentId,String outcome) {
        if(!"DEMO".equalsIgnoreCase(billingMode)||!"DEMO".equals(provider.name())) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Demo checkout simulation is disabled.");
        UUID c=company(); requireCompanyAdmin();
        Map<String,Object> payment=payment(paymentId,c);
        if(!"PENDING".equals(payment.get("status"))) return paymentResponse(payment,false);
        String type="FAILED".equalsIgnoreCase(outcome)?"PAYMENT_FAILED":"PAYMENT_SUCCEEDED";
        String eventId="demo_"+UUID.randomUUID();
        BillingProvider.SignedEvent event=provider.demoEvent(paymentId,eventId,type);
        return processWebhook("DEMO",event.body(),event.signature(),true);
    }

    @Transactional public Map<String,Object> processWebhook(String providerName,String raw,String signature,boolean internallyGenerated) {
        if(!"DEMO".equalsIgnoreCase(providerName)||!"DEMO".equalsIgnoreCase(billingMode)) throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,"This billing provider is not configured.");
        if(!provider.verifyEvent(raw,signature)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Billing webhook signature is invalid.");
        Map<String,Object> event;
        try { event=json.readValue(raw,new TypeReference<>(){}); } catch(Exception e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid webhook payload."); }
        String eventId=Objects.toString(event.get("eventId"),"").trim(), type=Objects.toString(event.get("type"),"").toUpperCase(Locale.ROOT);
        if(eventId.isBlank()||eventId.length()>160) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Webhook event ID is required.");
        String hash=sha256(raw);
        int inserted=db.update("insert into billing_webhook_events(provider,event_id,payload_hash) values('DEMO',?,?) on conflict do nothing",eventId,hash);
        if(inserted==0) {
            String prior=db.queryForObject("select payload_hash from billing_webhook_events where provider='DEMO' and event_id=?",String.class,eventId);
            if(!MessageDigest.isEqual(prior.getBytes(StandardCharsets.US_ASCII),hash.getBytes(StandardCharsets.US_ASCII))) throw new ResponseStatusException(HttpStatus.CONFLICT,"Billing event ID was reused with a different payload.");
            return Map.of("received",true,"duplicate",true);
        }
        UUID paymentId;
        try { paymentId=UUID.fromString(Objects.toString(event.get("paymentId"))); } catch(Exception e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Webhook payment reference is invalid."); }
        Map<String,Object> rows=payment(paymentId,null);
        UUID c=(UUID)rows.get("company_id"); Map<String,Object> sub=lockedSubscription(c);
        if(type.equals("PAYMENT_SUCCEEDED")) {
            if(!"PENDING".equals(rows.get("status"))) return Map.of("received",true,"ignored",true);
            db.update("update subscription_payments set status='SUCCEEDED',provider_reference=?,paid_at=now() where id=? and company_id=? and status='PENDING'",Objects.toString(event.get("providerReference"),"DEMO-BILLING-"+paymentId),paymentId,c);
            db.update("update billing_invoices set status='PAID',paid_at=now() where payment_id=?",paymentId);
            String old=Objects.toString(sub.get("status")); String newPlan=Objects.toString(sub.get("pending_plan_code"),Objects.toString(sub.get("plan_code")));
            UUID newPlanId=(UUID)Objects.requireNonNullElse(sub.get("pending_plan_id"),sub.get("plan_id"));
            String interval=Objects.toString(sub.get("pending_billing_interval"),Objects.toString(sub.get("billing_interval")));
            OffsetDateTime end=interval.equals("ANNUAL")?OffsetDateTime.now().plusYears(1):OffsetDateTime.now().plusMonths(1);
            db.update("update company_subscriptions set plan_id=?,billing_interval=?,status='ACTIVE',trial_ends_at=null,current_period_start=now(),current_period_end=?,grace_ends_at=null,cancel_at_period_end=false,cancelled_at=null,pending_plan_id=null,pending_billing_interval=null,updated_at=now() where company_id=?",newPlanId,interval,end,c);
            history(c,(UUID)sub.get("id"),null,"PAYMENT_SUCCEEDED",old,"ACTIVE",Objects.toString(sub.get("plan_code")),newPlan,Map.of("paymentId",paymentId.toString(),"demo",true));
            audit(c,null,"BILLING_PAYMENT_SUCCEEDED",paymentId,Map.of("amount",rows.get("amount"),"provider","DEMO","demo",true));
        } else if(type.equals("PAYMENT_FAILED")) {
            if(!"PENDING".equals(rows.get("status"))) return Map.of("received",true,"ignored",true);
            db.update("update subscription_payments set status='FAILED',failure_reason=?,provider_reference=? where id=?",Objects.toString(event.get("failureReason"),"Payment failed"),Objects.toString(event.get("providerReference"),"DEMO-BILLING-"+paymentId),paymentId);
            db.update("update billing_invoices set status='VOID' where payment_id=?",paymentId);
            String old=Objects.toString(sub.get("status")); db.update("update company_subscriptions set status='PAST_DUE',grace_ends_at=now()+make_interval(days=>?),pending_plan_id=null,pending_billing_interval=null,updated_at=now() where company_id=?",graceDays,c);
            history(c,(UUID)sub.get("id"),null,"PAYMENT_FAILED",old,"PAST_DUE",Objects.toString(sub.get("plan_code")),Objects.toString(sub.get("plan_code")),Map.of("paymentId",paymentId.toString()));
            audit(c,null,"BILLING_PAYMENT_FAILED",paymentId,Map.of("provider","DEMO","failureReason",Objects.toString(event.get("failureReason"),"Payment failed")));
        } else throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unsupported billing event type.");
        db.update("update billing_webhook_events set processed_at=now() where provider='DEMO' and event_id=?",eventId);
        Map<String,Object> out=new LinkedHashMap<>(); out.put("received",true); out.put("duplicate",false); out.put("payment",payment(paymentId,c)); return out;
    }

    @Transactional public Map<String,Object> cancel(boolean endOfPeriod) {
        UUID c=company(),actor=TenantContext.userIdOrNull();requireCompanyAdmin(); Map<String,Object> sub=lockedSubscription(c);
        if(!Set.of("ACTIVE","TRIALING","PAST_DUE").contains(Objects.toString(sub.get("status")))) throw new ResponseStatusException(HttpStatus.CONFLICT,"This subscription cannot be cancelled in its current state.");
        db.update("update company_subscriptions set cancel_at_period_end=?,cancelled_at=case when ? then null else now() end,status=case when ? then status else 'CANCELLED' end,updated_at=now() where company_id=?",endOfPeriod,endOfPeriod,endOfPeriod,c);
        history(c,(UUID)sub.get("id"),actor,"CANCELLED",Objects.toString(sub.get("status")),endOfPeriod?Objects.toString(sub.get("status")):"CANCELLED",Objects.toString(sub.get("plan_code")),Objects.toString(sub.get("plan_code")),Map.of("atPeriodEnd",endOfPeriod));audit(c,actor,"SUBSCRIPTION_CANCELLED",(UUID)sub.get("id"),Map.of("atPeriodEnd",endOfPeriod));
        return subscription();
    }
    @Transactional public Map<String,Object> reactivate() {
        UUID c=company(),actor=TenantContext.userIdOrNull();requireCompanyAdmin();
        int n=db.update("update company_subscriptions set status=case when status='ACTIVE' and current_period_end>now() then 'ACTIVE' when trial_ends_at>now() then 'TRIALING' else 'PAST_DUE' end,cancel_at_period_end=false,cancelled_at=null,grace_ends_at=case when current_period_end>now() then null else now()+make_interval(days=>?) end,updated_at=now() where company_id=? and (status in ('CANCELLED','EXPIRED','PAST_DUE') or cancel_at_period_end=true)",graceDays,c);
        if(n==0) throw new ResponseStatusException(HttpStatus.CONFLICT,"Subscription is already active. Start a checkout to restore a paid plan if access has expired.");
        Map<String,Object>s=lockedSubscription(c);history(c,(UUID)s.get("id"),actor,"REACTIVATED",Objects.toString(s.get("status")),Objects.toString(s.get("status")),Objects.toString(s.get("plan_code")),Objects.toString(s.get("plan_code")),Map.of());audit(c,actor,"SUBSCRIPTION_REACTIVATED",(UUID)s.get("id"),Map.of());return subscription();
    }

    public List<Map<String,Object>> payments() { UUID c=company();return db.queryForList("select id,amount,currency,status,provider,provider_reference,demo,failure_reason,period_start,period_end,created_at,paid_at from subscription_payments where company_id=? order by created_at desc limit 100",c); }
    public List<Map<String,Object>> invoices() { UUID c=company();return db.queryForList("select id,invoice_number,status,currency,subtotal,tax,total,description,period_start,period_end,created_at,paid_at from billing_invoices where company_id=? order by created_at desc limit 100",c); }
    public Map<String,Object> invoice(UUID id) { UUID c=company();List<Map<String,Object>>r=db.queryForList("select i.*,p.provider,p.provider_reference,p.demo,c.name company_name,c.business_email from billing_invoices i join subscription_payments p on p.id=i.payment_id and p.company_id=i.company_id join companies c on c.id=i.company_id where i.id=? and i.company_id=?",id,c);if(r.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Invoice not found.");return r.getFirst(); }
    public List<Map<String,Object>> subscriptionHistory(){return db.queryForList("select event_type,old_status,new_status,old_plan,new_plan,details,created_at from subscription_history where company_id=? order by created_at desc limit 100",company());}
    @Transactional public Map<String,Object> refundPayment(UUID paymentId,String reason){UUID c=company(),actor=TenantContext.userIdOrNull();requireCompanyAdmin();Map<String,Object>p=payment(paymentId,c);if(!Boolean.TRUE.equals(p.get("demo"))||!"DEMO".equals(p.get("provider")))throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"A live billing refund provider is not configured.");if(!"SUCCEEDED".equals(p.get("status")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Only a successful payment can be refunded.");String note=Objects.toString(reason,"").trim();if(note.isBlank()||note.length()>500)throw new IllegalArgumentException("A refund reason is required (maximum 500 characters).");db.update("update subscription_payments set status='REFUNDED',failure_reason=? where id=? and company_id=?",note,paymentId,c);db.update("update billing_invoices set status='VOID' where payment_id=?",paymentId);Map<String,Object>s=lockedSubscription(c);String old=Objects.toString(s.get("status"));db.update("update company_subscriptions set status='CANCELLED',cancel_at_period_end=false,cancelled_at=now(),updated_at=now() where company_id=?",c);history(c,(UUID)s.get("id"),actor,"PAYMENT_REFUNDED",old,"CANCELLED",Objects.toString(s.get("plan_code")),Objects.toString(s.get("plan_code")),Map.of("paymentId",paymentId.toString(),"reason",note));audit(c,actor,"BILLING_PAYMENT_REFUNDED",paymentId,Map.of("amount",p.get("amount"),"provider","DEMO","reason",note));return payment(paymentId,c);}

    @Transactional public void assertAiConversation(UUID companyId,UUID conversationId) {
        Map<String,Object> s=lockedSubscription(companyId); refreshLifecycle(companyId); s=lockedSubscription(companyId); assertCompanyWritable(companyId,Objects.toString(s.get("status")));
        String source=conversationId.toString(); Integer existing=db.queryForObject("select count(*) from usage_records where company_id=? and metric='AI_CONVERSATION' and source_key=?",Integer.class,companyId,source);
        if(existing!=null&&existing>0)return;
        int max=((Number)s.get("max_ai_conversations")).intValue();long used=number("select count(*) from usage_records where company_id=? and metric='AI_CONVERSATION' and period_start=?",companyId,s.get("current_period_start"));
        if(max>=0&&used>=max) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Your AI conversation allowance is used for this billing period. Upgrade your plan or wait for the next period.");
        db.update("insert into usage_records(company_id,metric,quantity,source_key,period_start,period_end) values(?,'AI_CONVERSATION',1,?,?,?) on conflict do nothing",companyId,source,s.get("current_period_start"),s.get("current_period_end"));
    }

    @Transactional public void assertAgentCapacity(UUID companyId) {
        Map<String,Object>s=lockedSubscription(companyId); refreshLifecycle(companyId);s=lockedSubscription(companyId);assertCompanyWritable(companyId,Objects.toString(s.get("status")));
        int max=((Number)s.get("max_agents")).intValue();long active=number("select count(*) from app_users u join roles r on r.id=u.role_id where u.company_id=? and r.name='AGENT' and u.status='ACTIVE'",companyId);
        long pending=number("select count(*) from agent_invitations where company_id=? and accepted_at is null and expires_at>now()",companyId);
        if(max>=0&&active+pending>=max) throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,"Your plan's agent limit has been reached. Upgrade the plan or remove a pending invitation.");
    }
    @Transactional public void assertInvitedAgentAcceptance(UUID companyId) {
        Map<String,Object>s=lockedSubscription(companyId);refreshLifecycle(companyId);s=lockedSubscription(companyId);assertCompanyWritable(companyId,Objects.toString(s.get("status")));
        int max=((Number)s.get("max_agents")).intValue();long active=number("select count(*) from app_users u join roles r on r.id=u.role_id where u.company_id=? and r.name='AGENT' and u.status='ACTIVE'",companyId);
        if(max>=0&&active>=max)throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,"This workspace has reached its agent limit. Upgrade the plan before accepting another agent.");
    }

    @Transactional public void assertKnowledgeCapacity(UUID companyId,long newBytes) {
        Map<String,Object>s=lockedSubscription(companyId);refreshLifecycle(companyId);s=lockedSubscription(companyId);assertCompanyWritable(companyId,Objects.toString(s.get("status")));
        long docs=number("select count(*) from knowledge_documents where company_id=? and status<>'ARCHIVED'",companyId),bytes=number("select coalesce((select sum(file_size) from knowledge_documents where company_id=? and status<>'ARCHIVED'),0)+coalesce((select sum(file_size) from conversation_attachments where company_id=?),0)",companyId,companyId);
        int docLimit=((Number)s.get("max_knowledge_documents")).intValue();long storageLimit=((Number)s.get("max_storage_bytes")).longValue();
        if(docLimit>=0&&docs>=docLimit)throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,"Your plan's knowledge document limit has been reached.");
        if(storageLimit>=0&&bytes+newBytes>storageLimit)throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,"Your plan's storage limit would be exceeded.");
    }
    @Transactional public void assertStorageCapacity(UUID companyId,long newBytes) {
        Map<String,Object>s=lockedSubscription(companyId);refreshLifecycle(companyId);s=lockedSubscription(companyId);assertCompanyWritable(companyId,Objects.toString(s.get("status")));
        long bytes=number("select coalesce((select sum(file_size) from knowledge_documents where company_id=? and status<>'ARCHIVED'),0)+coalesce((select sum(file_size) from conversation_attachments where company_id=?),0)",companyId,companyId);
        long limit=((Number)s.get("max_storage_bytes")).longValue();if(limit>=0&&bytes+newBytes>limit)throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,"Your plan's storage limit would be exceeded.");
    }

    @Transactional public Map<String,Object> companyAnalytics(String from,String to,String interval) {
        UUID c=company(); requireCompanyAdmin();
        String grain=Set.of("DAY","WEEK","MONTH").contains(Objects.toString(interval,"DAY").toUpperCase(Locale.ROOT))?Objects.toString(interval,"DAY").toUpperCase(Locale.ROOT):"DAY";
        java.sql.Date start=date(from,java.time.LocalDate.now().minusDays(29)), end=date(to,java.time.LocalDate.now());
        if(start.after(end))throw new IllegalArgumentException("The start date must not be after the end date.");
        String trunc=grain.equals("WEEK")?"week":grain.toLowerCase(Locale.ROOT);
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("range",Map.of("from",start.toString(),"to",end.toString(),"interval",grain));
        result.put("summary",db.queryForMap("""
          select count(*) total_conversations,
          count(*) filter(where c.created_at::date between ? and ?) new_conversations,
          count(*) filter(where c.created_at::date between ? and ? and c.status in ('RESOLVED','CLOSED')) resolved_conversations,
          round(100.0*count(*) filter(where c.created_at::date between ? and ? and not exists(select 1 from support_tickets ht where ht.company_id=c.company_id and ht.conversation_id=c.id))/nullif(count(*) filter(where c.created_at::date between ? and ?),0),1) ai_resolution_rate,
          round(100.0*count(*) filter(where c.created_at::date between ? and ? and exists(select 1 from support_tickets ht where ht.company_id=c.company_id and ht.conversation_id=c.id))/nullif(count(*) filter(where c.created_at::date between ? and ?),0),1) human_handoff_rate,
          (select round(avg(extract(epoch from (first_agent.first_response_at-c0.created_at))/60.0)::numeric,1) from conversations c0 join lateral(select min(m.created_at) first_response_at from messages m where m.company_id=c0.company_id and m.conversation_id=c0.id and m.sender_type='AGENT' and m.visibility='CUSTOMER') first_agent on first_agent.first_response_at is not null where c0.company_id=? and c0.created_at::date between ? and ?) average_first_response_minutes,
          (select round(avg(extract(epoch from (t.resolved_at-t.created_at))/60.0)::numeric,1) from support_tickets t where t.company_id=? and t.resolved_at is not null and t.resolved_at::date between ? and ?) average_resolution_minutes,
          (select round(avg(f.rating)::numeric,2) from customer_feedback f where f.company_id=? and f.created_at::date between ? and ?) average_satisfaction
          from conversations c where c.company_id=?
          """,start,end,start,end,start,end,start,end,start,end,start,end,c,start,end,c,start,end,c,start,end,c));
        result.put("daily",db.queryForList("select date_trunc('"+trunc+"',created_at)::date period,count(*) conversations,count(*) filter(where status in ('RESOLVED','CLOSED')) resolved from conversations where company_id=? and created_at::date between ? and ? group by 1 order by 1",c,start,end));
        result.put("priority",db.queryForList("select priority,count(*) count from support_tickets where company_id=? and created_at::date between ? and ? group by priority order by priority",c,start,end));
        result.put("agentWorkload",db.queryForList("select coalesce(u.full_name,'Unassigned') agent,count(*) filter(where t.status not in ('RESOLVED','CLOSED')) open_tickets,count(*) total_tickets from support_tickets t left join app_users u on u.id=t.assigned_agent_id and u.company_id=t.company_id where t.company_id=? and t.created_at::date between ? and ? group by u.full_name order by open_tickets desc,agent",c,start,end));
        result.put("refunds",db.queryForMap("select count(*) total,count(*) filter(where status='CREDITED') credited,count(*) filter(where status in ('UNDER_REVIEW','APPROVED','INITIATED','PROCESSING','UNKNOWN')) pending from refunds where company_id=? and created_at::date between ? and ?",c,start,end));
        result.put("refundAmountsByCurrency",db.queryForList("select currency,coalesce(sum(approved_amount),0) credited_amount from refunds where company_id=? and created_at::date between ? and ? and status='CREDITED' group by currency order by currency",c,start,end));
        result.put("aiUsage",Map.of("conversations",number("select count(*) from usage_records where company_id=? and metric='AI_CONVERSATION' and created_at::date between ? and ?",c,start,end),"messages",number("select count(*) from usage_records where company_id=? and metric='AI_MESSAGE' and created_at::date between ? and ?",c,start,end),"tokens",number("select coalesce(sum(quantity),0) from usage_records where company_id=? and metric='AI_TOKENS' and created_at::date between ? and ?",c,start,end)));
        return result;
    }

    public String analyticsCsv(String from,String to,String interval) { Map<String,Object> report=companyAnalytics(from,to,interval);StringBuilder out=new StringBuilder("section,metric,value\r\n");((Map<?,?>)report.get("summary")).forEach((k,v)->csvRow(out,"summary",k,v));for(Object row:(List<?>)report.get("daily")){Map<?,?>m=(Map<?,?>)row;csvRow(out,"trend",m.get("period"),m.get("conversations"));csvRow(out,"trend_resolved",m.get("period"),m.get("resolved"));}for(Object row:(List<?>)report.get("priority")){Map<?,?>m=(Map<?,?>)row;csvRow(out,"priority",m.get("priority"),m.get("count"));}for(Object row:(List<?>)report.get("agentWorkload")){Map<?,?>m=(Map<?,?>)row;csvRow(out,"agent_open_tickets",m.get("agent"),m.get("open_tickets"));csvRow(out,"agent_total_tickets",m.get("agent"),m.get("total_tickets"));}for(Object row:(List<?>)report.get("refundAmountsByCurrency")){Map<?,?>m=(Map<?,?>)row;csvRow(out,"credited_refunds_"+m.get("currency"),m.get("currency"),m.get("credited_amount"));}((Map<?,?>)report.get("refunds")).forEach((k,v)->csvRow(out,"refunds",k,v));((Map<?,?>)report.get("aiUsage")).forEach((k,v)->csvRow(out,"ai_usage",k,v));return out.toString(); }

    public Map<String,Object> platformDashboard() {
        requirePlatformAdmin(); Map<String,Object> m=new LinkedHashMap<>();
        m.put("companies",db.queryForMap("select count(*) total,count(*) filter(where status='ACTIVE') active,count(*) filter(where status='SUSPENDED') suspended from companies"));
        m.put("subscriptions",db.queryForMap("select count(*) filter(where s.status in ('ACTIVE','TRIALING')) active,count(*) filter(where s.status='TRIALING') trial,count(*) filter(where s.status='PAST_DUE') past_due,count(*) filter(where s.status='CANCELLED') cancelled,count(*) filter(where s.status='EXPIRED') expired,count(*) filter(where s.status='PAST_DUE' and s.grace_ends_at>now()) grace from company_subscriptions s"));
        m.put("revenue",db.queryForMap("with latest_paid as (select distinct on(subscription_id) subscription_id,amount,paid_at from subscription_payments where status='SUCCEEDED' order by subscription_id,paid_at desc nulls last) select coalesce(sum(case when s.status='ACTIVE' then case when s.billing_interval='ANNUAL' then l.amount/12 else l.amount end else 0 end),0) mrr,coalesce(sum(case when s.status='ACTIVE' then case when s.billing_interval='ANNUAL' then l.amount else l.amount*12 end else 0 end),0) annual_recurring_revenue,(select count(*) from subscription_payments where status='FAILED') payment_failures from latest_paid l join company_subscriptions s on s.id=l.subscription_id"));
        m.put("usage",db.queryForMap("select count(distinct company_id) companies_metered,coalesce(sum(quantity) filter(where metric='AI_CONVERSATION'),0) ai_conversations,coalesce(sum(quantity) filter(where metric='AI_MESSAGE'),0) ai_messages from usage_records where created_at>=date_trunc('month',now())"));
        return m;
    }
    public List<Map<String,Object>> platformCompanies(String search) { requirePlatformAdmin();String q="%"+Objects.toString(search,"").trim()+"%";return db.queryForList("select c.id,c.name,c.slug,c.business_email,c.status,c.created_at,s.status subscription_status,s.trial_ends_at,s.current_period_end,s.cancel_at_period_end,p.code plan_code,p.name plan_name,(select count(*) from app_users u join roles r on r.id=u.role_id where u.company_id=c.id and r.name='AGENT' and u.status='ACTIVE') active_agents from companies c left join company_subscriptions s on s.company_id=c.id left join subscription_plans p on p.id=s.plan_id where c.name ilike ? or c.slug ilike ? or coalesce(c.business_email,'') ilike ? order by c.created_at desc limit 500",q,q,q); }
    @Transactional public Map<String,Object> setCompanyStatus(UUID id,String status) { requirePlatformAdmin();if(!Set.of("ACTIVE","SUSPENDED").contains(status))throw new IllegalArgumentException("Status must be ACTIVE or SUSPENDED.");int n=db.update("update companies set status=? where id=?",status,id);if(n==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Company not found.");audit(null,TenantContext.userIdOrNull(),"PLATFORM_COMPANY_"+status,id,Map.of("status",status));return Map.of("companyId",id,"status",status); }
    public List<Map<String,Object>> platformSubscriptions(){requirePlatformAdmin();return db.queryForList("select c.id company_id,c.name company_name,s.id subscription_id,s.status,s.billing_interval,s.current_period_start,s.current_period_end,s.grace_ends_at,s.cancel_at_period_end,p.code plan_code,p.name plan_name,p.monthly_price,p.annual_price from company_subscriptions s join companies c on c.id=s.company_id join subscription_plans p on p.id=s.plan_id order by s.updated_at desc limit 500");}
    public List<Map<String,Object>> platformAudit(){requirePlatformAdmin();return db.queryForList("select a.id,a.company_id,c.name company_name,a.actor_id,u.email actor_email,a.action,a.target_type,a.target_id,a.created_at from audit_logs a left join companies c on c.id=a.company_id left join app_users u on u.id=a.actor_id order by a.created_at desc limit 500");}

    @Transactional public Map<String,Object> savePlan(String code,Map<String,Object>b) {requirePlatformAdmin();String norm=code.toUpperCase(Locale.ROOT);if(!norm.matches("[A-Z][A-Z0-9_]{1,23}"))throw new IllegalArgumentException("Invalid plan code.");String name=Objects.toString(b.get("name"),"").trim();if(name.isBlank()||name.length()>80)throw new IllegalArgumentException("Plan name is required (maximum 80 characters).");int agents=integer(b,"maxAgents"),ai=integer(b,"maxAiConversations"),docs=integer(b,"maxKnowledgeDocuments");long storage=longValue(b,"maxStorageBytes");if(agents< -1||ai< -1||docs< -1||storage< -1)throw new IllegalArgumentException("Limits must be -1 (unlimited) or zero and above.");BigDecimal monthly=decimal(b.get("monthlyPrice")),annual=decimal(b.get("annualPrice"));String analytics=Objects.toString(b.getOrDefault("analyticsLevel","BASIC")).toUpperCase(Locale.ROOT);if(!Set.of("BASIC","ADVANCED","CUSTOM").contains(analytics))throw new IllegalArgumentException("Analytics level is invalid.");int days=integer(b,"trialPeriodDays");boolean active=!Boolean.FALSE.equals(b.get("active")),custom=Boolean.TRUE.equals(b.get("customPricing"));
        if(days<0||days>365)throw new IllegalArgumentException("Trial days must be between 0 and 365.");
        db.update("insert into subscription_plans(code,name,description,monthly_price,annual_price,max_agents,max_ai_conversations,max_knowledge_documents,max_storage_bytes,analytics_level,trial_period_days,active,custom_pricing,updated_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?,now()) on conflict(code) do update set name=excluded.name,description=excluded.description,monthly_price=excluded.monthly_price,annual_price=excluded.annual_price,max_agents=excluded.max_agents,max_ai_conversations=excluded.max_ai_conversations,max_knowledge_documents=excluded.max_knowledge_documents,max_storage_bytes=excluded.max_storage_bytes,analytics_level=excluded.analytics_level,trial_period_days=excluded.trial_period_days,active=excluded.active,custom_pricing=excluded.custom_pricing,updated_at=now()",norm,name,Objects.toString(b.getOrDefault("description","")),monthly,annual,agents,ai,docs,storage,analytics,days,active,custom);audit(null,TenantContext.userIdOrNull(),"PLATFORM_PLAN_SAVED",null,Map.of("code",norm));return db.queryForMap("select * from subscription_plans where code=?",norm); }

    @Transactional public void refreshLifecycle(UUID company) {
        Map<String,Object> before=subscriptionRow(company); if(before.isEmpty())return;
        OffsetDateTime now=OffsetDateTime.now();String status=Objects.toString(before.get("status"));
        if("TRIALING".equals(status)&&before.get("trial_ends_at")!=null&&!instant(before.get("trial_ends_at")).isAfter(now.toInstant())) { db.update("update company_subscriptions set status='PAST_DUE',grace_ends_at=now()+make_interval(days=>?),updated_at=now() where company_id=? and status='TRIALING'",graceDays,company); status="PAST_DUE"; }
        if("ACTIVE".equals(status)&&before.get("current_period_end")!=null&&!instant(before.get("current_period_end")).isAfter(now.toInstant())) { if(Boolean.TRUE.equals(before.get("cancel_at_period_end")))db.update("update company_subscriptions set status='CANCELLED',cancelled_at=now(),updated_at=now() where company_id=?",company);else db.update("update company_subscriptions set status='PAST_DUE',grace_ends_at=now()+make_interval(days=>?),updated_at=now() where company_id=?",graceDays,company);status=Boolean.TRUE.equals(before.get("cancel_at_period_end"))?"CANCELLED":"PAST_DUE"; }
        if("PAST_DUE".equals(status)&&before.get("grace_ends_at")!=null&&!instant(before.get("grace_ends_at")).isAfter(now.toInstant())) { db.update("update company_subscriptions set status='EXPIRED',updated_at=now() where company_id=? and status='PAST_DUE'",company);status="EXPIRED"; }
        if(!status.equals(Objects.toString(before.get("status")))) { Map<String,Object>after=subscriptionRow(company);history(company,(UUID)after.get("id"),null,"STATUS_"+status,Objects.toString(before.get("status")),status,Objects.toString(before.get("plan_code")),Objects.toString(after.get("plan_code")),Map.of());audit(company,null,"SUBSCRIPTION_STATUS_"+status,(UUID)after.get("id"),Map.of()); }
    }
    public void assertCompanyWritable(UUID company,String status) {assertCompanyActive(company);if(Set.of("EXPIRED","CANCELLED").contains(status))throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,"This workspace is read-only. Reactivate a subscription from Billing to restore access; your data is retained.");if("PAST_DUE".equals(status)){Map<String,Object> row=subscriptionRow(company);if(row.get("grace_ends_at")==null||instant(row.get("grace_ends_at")).isBefore(java.time.Instant.now()))throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,"The billing grace period ended. Restore billing to resume workspace changes; your data is retained.");}}
    public void assertCompanyActive(UUID company){String status=db.queryForObject("select status from companies where id=?",String.class,company);if(!"ACTIVE".equals(status))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"This company workspace is suspended. Contact SupportIQ billing support to restore access.");}
    public void assertWritableForRequest(UUID company) {refreshLifecycle(company);Map<String,Object>s=subscriptionRow(company);assertCompanyWritable(company,Objects.toString(s.get("status")));}

    private Map<String,Object> lockedSubscription(UUID company){List<Map<String,Object>>r=db.queryForList("select s.*,p.code plan_code,p.name plan_name,p.monthly_price,p.annual_price,p.max_agents,p.max_ai_conversations,p.max_knowledge_documents,p.max_storage_bytes,p.analytics_level,(select code from subscription_plans where id=s.pending_plan_id) pending_plan_code from company_subscriptions s join subscription_plans p on p.id=s.plan_id where s.company_id=? for update of s",company);if(r.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Company subscription was not found.");return r.getFirst();}
    private Map<String,Object> subscriptionRow(UUID c){List<Map<String,Object>>r=db.queryForList("select s.*,p.code plan_code,p.name plan_name,p.monthly_price,p.annual_price,p.max_agents,p.max_ai_conversations,p.max_knowledge_documents,p.max_storage_bytes,p.analytics_level,(select code from subscription_plans where id=s.pending_plan_id) pending_plan_code from company_subscriptions s join subscription_plans p on p.id=s.plan_id where s.company_id=?",c);return r.isEmpty()?Map.of():r.getFirst();}
    private Map<String,Object> plan(String code,boolean active){List<Map<String,Object>>r=db.queryForList("select * from subscription_plans where code=? "+(active?"and active":""),Objects.toString(code,"").toUpperCase(Locale.ROOT));if(r.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Subscription plan not found.");return r.getFirst();}
    private BigDecimal price(Map<String,Object>p,String interval){Object v=p.get(interval.equals("ANNUAL")?"annual_price":"monthly_price");if(v==null)throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Choose a plan with a configured price. Contact sales for Enterprise pricing.");return (BigDecimal)v;}
    private BigDecimal currentPrice(Map<String,Object>s){Object x=Objects.equals(s.get("billing_interval"),"ANNUAL")?s.get("annual_price"):s.get("monthly_price");return x instanceof BigDecimal d?d:BigDecimal.ZERO;}
    private Map<String,Object> payment(UUID id,UUID company){List<Map<String,Object>>r=company==null?db.queryForList("select p.*,i.invoice_number from subscription_payments p left join billing_invoices i on i.payment_id=p.id where p.id=?",id):db.queryForList("select p.*,i.invoice_number from subscription_payments p left join billing_invoices i on i.payment_id=p.id where p.id=? and p.company_id=?",id,company);if(r.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Billing payment not found.");return r.getFirst();}
    private Map<String,Object> paymentResponse(Map<String,Object>row,boolean duplicate){Map<String,Object>r=new LinkedHashMap<>(row);r.put("duplicate",duplicate);return r;}
    private void history(UUID c,UUID s,UUID actor,String event,String oldStatus,String newStatus,String oldPlan,String newPlan,Map<String,Object>details){try{db.update("insert into subscription_history(company_id,subscription_id,actor_id,event_type,old_status,new_status,old_plan,new_plan,details) values(?,?,?,?,?,?,?, ?,?::jsonb)",c,s,actor,event,oldStatus,newStatus,oldPlan,newPlan,json.writeValueAsString(details));}catch(Exception e){throw new IllegalStateException(e);}}
    private void audit(UUID c,UUID actor,String action,UUID target,Map<String,Object>details){try{db.update("insert into audit_logs(company_id,actor_id,action,target_type,target_id,details) values(?,?,?,?,?,?::jsonb)",c,actor,action,"BILLING",target==null?null:target.toString(),json.writeValueAsString(details));}catch(Exception e){throw new IllegalStateException(e);}}
    private long number(String sql,Object...args){Long n=db.queryForObject(sql,Long.class,args);return n==null?0:n;}
    private UUID company(){return TenantContext.requireCompanyId();}
    private void requireCompanyAdmin(){if(!"COMPANY_ADMIN".equals(TenantContext.role()))throw new org.springframework.security.access.AccessDeniedException("Company Admin permission required.");}
    private void requirePlatformAdmin(){if(!"SUPER_ADMIN".equals(TenantContext.role()))throw new org.springframework.security.access.AccessDeniedException("Super Admin permission required.");}
    private String normalizeInterval(String i){String v=Objects.toString(i,"MONTHLY").toUpperCase(Locale.ROOT);if(!Set.of("MONTHLY","ANNUAL").contains(v))throw new IllegalArgumentException("Billing interval must be MONTHLY or ANNUAL.");return v;}
    private String normalizeIdempotency(String k){String v=Objects.toString(k,"").trim();if(v.isBlank())v=UUID.randomUUID().toString();if(v.length()>120)throw new IllegalArgumentException("Idempotency key is too long.");return v;}
    private java.sql.Date date(String value,java.time.LocalDate fallback){try{return value==null||value.isBlank()?java.sql.Date.valueOf(fallback):java.sql.Date.valueOf(java.time.LocalDate.parse(value));}catch(Exception e){throw new IllegalArgumentException("Dates must use YYYY-MM-DD.");}}
    private String csv(Object x){String s=Objects.toString(x,"").replace("\"","\"\"");return "\""+s+"\"";}
    private void csvRow(StringBuilder out,Object section,Object metric,Object value){out.append(csv(section)).append(',').append(csv(metric)).append(',').append(csv(value)).append("\r\n");}
    private String sha256(String x){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(x.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private java.time.Instant instant(Object value){if(value instanceof OffsetDateTime t)return t.toInstant();if(value instanceof java.sql.Timestamp t)return t.toInstant();if(value instanceof java.util.Date d)return d.toInstant();throw new IllegalStateException("Database timestamp was unavailable.");}
    private int integer(Map<String,Object>b,String k){try{return Integer.parseInt(Objects.toString(b.getOrDefault(k,0)));}catch(Exception e){throw new IllegalArgumentException(k+" must be an integer.");}}
    private long longValue(Map<String,Object>b,String k){try{return Long.parseLong(Objects.toString(b.getOrDefault(k,0)));}catch(Exception e){throw new IllegalArgumentException(k+" must be an integer.");}}
    private BigDecimal decimal(Object x){if(x==null||Objects.toString(x).isBlank())return null;try{BigDecimal d=new BigDecimal(x.toString());if(d.signum()<0||d.scale()>2)throw new NumberFormatException();return d;}catch(Exception e){throw new IllegalArgumentException("Plan prices must be nonnegative amounts with at most two decimal places.");}}
}
