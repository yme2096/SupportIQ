package com.supportiq.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.supportiq.payment.PaymentProvider;
import com.supportiq.security.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class OrderRefundService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcTemplate db;
    private final PaymentProvider demoProvider;
    @Value("${app.security.jwt-secret}") private String identityHashSecret;
    private final SecureRandom random = new SecureRandom();
    @Value("${app.payments.provider:DEMO}") private String providerMode;
    @Value("${app.payments.webhook-secret:local-demo-webhook-secret-change-before-deploy}") private String webhookSecret;
    @Value("${app.payments.live-enabled:false}") private boolean liveEnabled;

    public OrderRefundService(JdbcTemplate db,PaymentProvider demoProvider) { this.db = db; this.demoProvider=demoProvider; }

    private UUID company() { return TenantContext.requireCompanyId(); }
    private UUID user() { return TenantContext.userIdOrNull(); }
    private void requireAdmin() { if (!"COMPANY_ADMIN".equals(TenantContext.role()) && !"SUPER_ADMIN".equals(TenantContext.role())) throw new AccessDeniedException("Company administrator approval is required."); }
    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND,"Order or refund was not found."); }
    private static String norm(String value) { return Objects.toString(value,"").trim().toLowerCase(Locale.ROOT); }
    private static String sha(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch(Exception e) { throw new IllegalStateException(e); } }
    private String verificationHash(String value) { try { Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(identityHashSecret.getBytes(StandardCharsets.UTF_8),"HmacSHA256")); return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8))); } catch(Exception e) { throw new IllegalStateException(e); } }
    private static String json(Object value) { try { return JSON.writeValueAsString(value); } catch(Exception e) { throw new IllegalArgumentException("Invalid JSON data."); } }
    private Map<String,Object> audit(UUID company, UUID actor, String action, String type, Object id, Map<String,Object> detail) {
        db.update("insert into audit_logs(company_id,actor_id,action,target_type,target_id,details) values(?,?,?,?,?,?::jsonb)",company,actor,action,type,Objects.toString(id),json(detail));
        return detail;
    }

    public List<Map<String,Object>> orders(String search, int page, int size) {
        UUID c=company(); int p=Math.max(0,Math.min(page,10000)),s=Math.max(1,Math.min(size,100)); String q="%"+Objects.toString(search,"").trim().replace("%","\\%").replace("_","\\_")+"%";
        return db.queryForList("select id,order_number,customer_name,customer_email,ordered_at,delivered_at,items,total_amount,currency,payment_status,fulfillment_status,delivery_address,tracking_number,is_demo,created_at,updated_at from customer_orders where company_id=? and (?='%%' or order_number ilike ? or customer_email ilike ? or customer_name ilike ?) order by ordered_at desc limit ? offset ?",c,q,q,q,q,s,p*s);
    }
    public Map<String,Object> order(UUID id) {
        List<Map<String,Object>> rows=db.queryForList("select id,order_number,customer_name,customer_email,ordered_at,delivered_at,items,total_amount,currency,payment_status,fulfillment_status,delivery_address,tracking_number,is_demo,created_at,updated_at from customer_orders where company_id=? and id=?",company(),id);
        if(rows.isEmpty()) throw missing(); return rows.getFirst();
    }
    @Transactional
    public Map<String,Object> createOrder(Map<String,Object> body) {
        UUID c=company(); String number=Objects.toString(body.get("orderNumber"),"").trim(); String name=Objects.toString(body.get("customerName"),"").trim(); String email=norm(Objects.toString(body.get("customerEmail"),"")); String postal=Objects.toString(body.get("postalCode"),"").trim();
        BigDecimal total=amount(body.get("totalAmount")); String currency=Objects.toString(body.get("currency"),"INR").toUpperCase(Locale.ROOT);
        if(number.isBlank()||number.length()>64||name.isBlank()||email.length()>320||!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")||postal.length()<3||postal.length()>16||!currency.matches("[A-Z]{3}")) throw new IllegalArgumentException("Order number, customer identity, postal code and valid currency are required.");
        Object raw=body.get("items"); if(!(raw instanceof List<?> items)||items.isEmpty()||items.size()>100) throw new IllegalArgumentException("Add between 1 and 100 order items.");
        BigDecimal itemTotal=BigDecimal.ZERO;
        for(Object item:items) {
            if(!(item instanceof Map<?,?> map)||Objects.toString(map.get("name"),"").isBlank()) throw new IllegalArgumentException("Each order item needs a name.");
            try { int quantity=Integer.parseInt(Objects.toString(map.get("quantity"))); BigDecimal line=new BigDecimal(Objects.toString(map.get("amount"))).setScale(2,java.math.RoundingMode.UNNECESSARY); if(quantity<1||quantity>10000||line.signum()<=0)throw new Exception(); itemTotal=itemTotal.add(line); }
            catch(Exception ex) { throw new IllegalArgumentException("Each order item needs a positive line amount and quantity."); }
        }
        if(itemTotal.compareTo(total)!=0) throw new IllegalArgumentException("Order item totals must equal the order total.");
        String pay=Objects.toString(body.getOrDefault("paymentStatus","PENDING")); String fulfill=Objects.toString(body.getOrDefault("fulfillmentStatus","PLACED"));
        if(!Set.of("PENDING","PAID","FAILED","PARTIALLY_REFUNDED","REFUNDED").contains(pay)||!Set.of("PLACED","CONFIRMED","PROCESSING","SHIPPED","OUT_FOR_DELIVERY","DELIVERED","CANCELLED","RETURNED").contains(fulfill)) throw new IllegalArgumentException("Order status is invalid.");
        String address=json(body.getOrDefault("deliveryAddress",Map.of()));
        List<UUID> customerIds=db.query("select u.id from app_users u join roles r on r.id=u.role_id where u.company_id=? and lower(u.email)=? and r.name='CUSTOMER' and u.status='ACTIVE'",(rs,n)->rs.getObject(1,UUID.class),c,email);
        UUID customerId=customerIds.isEmpty()?null:customerIds.getFirst();
        UUID id=db.queryForObject("insert into customer_orders(company_id,customer_id,order_number,customer_name,customer_email,verification_postal_hash,items,total_amount,currency,payment_status,fulfillment_status,delivery_address,tracking_number,is_demo) values(?,?,?,?,?,?,?::jsonb,?,?,?,?,?::jsonb,?,false) returning id",UUID.class,c,customerId,number,name,email,verificationHash(postal),json(items),total,currency,pay,fulfill,address,Objects.toString(body.get("trackingNumber"),null));
        audit(c,user(),"ORDER_CREATED","ORDER",id,Map.of("orderNumber",number,"demo",false)); return order(id);
    }
    @Transactional
    public Map<String,Object> updateOrder(UUID id,Map<String,Object> body) {
        UUID c=company(); Map<String,Object> current=order(id); String status=Objects.toString(body.getOrDefault("fulfillmentStatus",current.get("fulfillment_status")));
        if(!Set.of("PLACED","CONFIRMED","PROCESSING","SHIPPED","OUT_FOR_DELIVERY","DELIVERED","CANCELLED","RETURNED").contains(status)) throw new IllegalArgumentException("Order status is invalid.");
        String tracking=body.containsKey("trackingNumber")?Objects.toString(body.get("trackingNumber"),null):Objects.toString(current.get("tracking_number"),null);
        db.update("update customer_orders set fulfillment_status=?,tracking_number=?,delivered_at=case when ?='DELIVERED' then coalesce(delivered_at,now()) else delivered_at end,updated_at=now() where company_id=? and id=?",status,tracking,status,c,id);
        audit(c,user(),"ORDER_UPDATED","ORDER",id,Map.of("fulfillmentStatus",status)); return order(id);
    }

    public Map<String,Object> refundPolicy() {
        UUID c=company(); List<Map<String,Object>> rows=db.queryForList("select company_id,return_window_days,eligible_conditions::text eligible_conditions,excluded_categories::text excluded_categories,auto_approve_limit,updated_at from company_refund_policies where company_id=?",c);
        if(rows.isEmpty()) throw missing(); Map<String,Object> result=new LinkedHashMap<>(rows.getFirst()); result.put("eligible_conditions",parseStringList(Objects.toString(result.get("eligible_conditions"),"[]"))); result.put("excluded_categories",parseStringList(Objects.toString(result.get("excluded_categories"),"[]"))); return result;
    }
    @Transactional
    public Map<String,Object> updateRefundPolicy(Map<String,Object> body) {
        requireAdmin(); UUID c=company(); int days=integer(body.get("returnWindowDays"),0,365); BigDecimal limit=nonnegative(body.getOrDefault("autoApproveLimit",BigDecimal.ZERO));
        List<String> conditions=strings(body.get("eligibleConditions")); List<String> excluded=strings(body.get("excludedCategories"));
        db.update("insert into company_refund_policies(company_id,return_window_days,eligible_conditions,excluded_categories,auto_approve_limit,updated_by,updated_at) values(?,?,?::jsonb,?::jsonb,?,?,now()) on conflict(company_id) do update set return_window_days=excluded.return_window_days,eligible_conditions=excluded.eligible_conditions,excluded_categories=excluded.excluded_categories,auto_approve_limit=excluded.auto_approve_limit,updated_by=excluded.updated_by,updated_at=now()",c,days,json(conditions),json(excluded),limit,user());
        audit(c,user(),"REFUND_POLICY_UPDATED","COMPANY",c,Map.of("returnWindowDays",days)); return refundPolicy();
    }
    private static List<String> strings(Object raw) { if(!(raw instanceof List<?> list)||list.size()>100) throw new IllegalArgumentException("Expected a list of policy values."); return list.stream().map(x->Objects.toString(x,"").trim().toUpperCase(Locale.ROOT)).filter(x->!x.isBlank()&&x.length()<=80).distinct().toList(); }
    private static int integer(Object raw,int min,int max) { try { int x=raw instanceof Number n?n.intValue():Integer.parseInt(Objects.toString(raw)); if(x<min||x>max) throw new Exception(); return x; } catch(Exception e) { throw new IllegalArgumentException("Policy day limit is invalid."); } }
    private static BigDecimal amount(Object raw) { try { BigDecimal n=raw instanceof BigDecimal b?b:new BigDecimal(Objects.toString(raw)); n=n.setScale(2,java.math.RoundingMode.UNNECESSARY); if(n.signum()<=0) throw new Exception(); return n; } catch(Exception e) { throw new IllegalArgumentException("Amount must be a positive value with at most two decimals."); } }
    private static BigDecimal nonnegative(Object raw) { try { BigDecimal n=raw instanceof BigDecimal b?b:new BigDecimal(Objects.toString(raw)); n=n.setScale(2,java.math.RoundingMode.UNNECESSARY); if(n.signum()<0) throw new Exception(); return n; } catch(Exception e) { throw new IllegalArgumentException("Value must be zero or positive with at most two decimals."); } }

    @Transactional(noRollbackFor=ResponseStatusException.class)
    public Map<String,Object> verifyCustomer(String publicCompanyId,String orderNumber,String email,String postal,String remoteAddress) {
        String key=sha(Objects.toString(remoteAddress,"unknown"));
        db.update("insert into order_lookup_limits(key_hash,window_started,attempts) values(?,now(),1) on conflict(key_hash) do update set attempts=case when order_lookup_limits.window_started < now()-interval '15 minutes' then 1 else order_lookup_limits.attempts+1 end,window_started=case when order_lookup_limits.window_started < now()-interval '15 minutes' then now() else order_lookup_limits.window_started end,blocked_until=case when order_lookup_limits.attempts>=8 and order_lookup_limits.window_started>=now()-interval '15 minutes' then now()+interval '30 minutes' else order_lookup_limits.blocked_until end",key);
        Integer attempts=db.queryForObject("select attempts from order_lookup_limits where key_hash=?",Integer.class,key);
        Instant blocked=db.queryForObject("select blocked_until from order_lookup_limits where key_hash=?",(rs,n)->rs.getTimestamp(1)==null?null:rs.getTimestamp(1).toInstant(),key);
        if(blocked!=null&&blocked.isAfter(Instant.now())||attempts!=null&&attempts>10) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Too many lookup attempts. Try again later.");
        List<UUID> companyIds;
        try { companyIds=db.query("select id from companies where public_id=?::uuid",(rs,n)->rs.getObject(1,UUID.class),publicCompanyId); } catch(Exception ignored) { companyIds=List.of(); }
        if(companyIds.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"We couldn't verify those order details.");
        UUID companyId=companyIds.getFirst();
        List<UUID> matches=db.query("select id from customer_orders where company_id=? and order_number=? and lower(customer_email)=? and verification_postal_hash in (?,?)",(rs,n)->rs.getObject(1,UUID.class),companyId,Objects.toString(orderNumber,"").trim(),norm(email),verificationHash(Objects.toString(postal,"").trim()),sha(Objects.toString(postal,"").trim()));
        if(matches.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"We couldn't verify those order details.");
        UUID orderId=matches.getFirst(); String token=randomToken(); db.update("insert into order_access_tokens(token_hash,company_id,order_id,expires_at) select ?,company_id,id,now()+interval '20 minutes' from customer_orders where id=?",sha(token),orderId);
        return Map.of("accessToken",token,"expiresInSeconds",1200,"order",publicOrder(orderId));
    }
    private String randomToken() { byte[] bytes=new byte[32]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private UUID authorizeToken(String token) {
        if(token==null||token.length()<30||token.length()>120) throw missing();
        List<UUID> ids=db.query("select order_id from order_access_tokens where token_hash=? and revoked_at is null and expires_at>now()",(rs,n)->rs.getObject(1,UUID.class),sha(token));
        if(ids.isEmpty()) throw missing(); return ids.getFirst();
    }
    public Map<String,Object> publicOrder(String token) { return publicOrder(authorizeToken(token)); }
    private Map<String,Object> publicOrder(UUID id) {
        List<Map<String,Object>> rows=db.queryForList("select o.id,o.order_number,o.ordered_at,o.delivered_at,o.items,o.total_amount,o.currency,o.payment_status,o.fulfillment_status,o.tracking_number,o.is_demo from customer_orders o where o.id=?",id);
        if(rows.isEmpty()) throw missing(); return rows.getFirst();
    }
    public List<Map<String,Object>> publicRefunds(String token) { UUID id=authorizeToken(token); return db.queryForList("select id,requested_amount,approved_amount,currency,reason,status,provider_reference,arn,created_at,updated_at from refunds where order_id=? order by created_at desc",id); }
    public List<Map<String,Object>> publicRefundHistory(String token,UUID refundId) { UUID order=authorizeToken(token); List<UUID> ids=db.query("select id from refunds where order_id=? and id=?",(rs,n)->rs.getObject(1,UUID.class),order,refundId); if(ids.isEmpty())throw missing(); return db.queryForList("select event_type,details,created_at from refund_events where refund_id=? order by created_at",refundId); }
    public List<Map<String,Object>> publicOrderHistory(String token) {
        UUID id=authorizeToken(token);
        return db.queryForList("select o.id,o.order_number,o.ordered_at,o.delivered_at,o.total_amount,o.currency,o.payment_status,o.fulfillment_status,o.tracking_number,o.is_demo from customer_orders o where o.company_id=(select company_id from customer_orders where id=?) and lower(o.customer_email)=(select lower(customer_email) from customer_orders where id=?) and o.verification_postal_hash=(select verification_postal_hash from customer_orders where id=?) order by o.ordered_at desc limit 50",id,id,id);
    }
    @Transactional
    public Map<String,Object> requestRefund(String token,Map<String,Object> body,String idempotencyKey) {
        UUID id=authorizeToken(token); if(idempotencyKey==null||idempotencyKey.isBlank()||idempotencyKey.length()>120) throw new IllegalArgumentException("A valid idempotency key is required.");
        Map<String,Object> order=db.queryForMap("select * from customer_orders where id=? for update",id);
        List<Map<String,Object>> existing=db.queryForList("select id,order_id,status,requested_amount,currency from refunds where company_id=(select company_id from customer_orders where id=?) and idempotency_key=?",id,idempotencyKey);
        if(!existing.isEmpty()) { if(!id.equals(existing.getFirst().get("order_id"))) throw new ResponseStatusException(HttpStatus.CONFLICT,"The idempotency key was already used for another order."); return existing.getFirst(); }
        BigDecimal requested=amount(body.get("amount")); String reason=Objects.toString(body.get("reason"),"").trim(); String condition=Objects.toString(body.get("condition"),"").trim().toUpperCase(Locale.ROOT); String category=Objects.toString(body.getOrDefault("category","GENERAL"),"GENERAL").trim().toUpperCase(Locale.ROOT);
        if(reason.isBlank()||reason.length()>1000) throw new IllegalArgumentException("A short refund reason is required.");
        Eligibility eligibility=eligibility(order,requested,condition,category);
        String status=eligibility.decision.equals("INELIGIBLE")?"REJECTED":"UNDER_REVIEW";
        UUID c=(UUID)order.get("company_id"); UUID refund=db.queryForObject("insert into refunds(company_id,order_id,customer_id,requested_amount,currency,reason,product_condition,product_category,status,eligibility,provider,idempotency_key) values(?,?,?,?,?,?,?,?,?,?::jsonb,?,?) returning id",UUID.class,c,id,order.get("customer_id"),requested,order.get("currency"),reason,condition,category,status,json(Map.of("decision",eligibility.decision,"reasons",eligibility.reasons)),providerMode.toUpperCase(Locale.ROOT),idempotencyKey);
        event(c,refund,"REQUESTED",null,Map.of("decision",eligibility.decision,"reasons",eligibility.reasons));
        audit(c,null,"REFUND_REQUESTED","REFUND",refund,Map.of("orderId",id.toString(),"decision",eligibility.decision));
        return refund(refund,c);
    }
    private record Eligibility(String decision,List<String> reasons){}
    private Eligibility eligibility(Map<String,Object> order,BigDecimal amount,String condition,String category) {
        return eligibility(order,amount,condition,category,null);
    }
    private Eligibility eligibility(Map<String,Object> order,BigDecimal amount,String condition,String category,UUID excludeRefundId) {
        UUID c=(UUID)order.get("company_id"); BigDecimal total=(BigDecimal)order.get("total_amount"); String pay=Objects.toString(order.get("payment_status")); List<String> reasons=new ArrayList<>();
        List<Map<String,Object>> policy=db.queryForList("select return_window_days,eligible_conditions::text,excluded_categories::text from company_refund_policies where company_id=?",c);
        if(policy.isEmpty()) return new Eligibility("REVIEW_REQUIRED",List.of("Refund policy is not configured."));
        Map<String,Object> p=policy.getFirst(); int days=(Integer)p.get("return_window_days"); List<String> conditions=parseStringList(Objects.toString(p.get("eligible_conditions"))); List<String> excluded=parseStringList(Objects.toString(p.get("excluded_categories")));
        if(!Set.of("PAID","PARTIALLY_REFUNDED").contains(pay)) reasons.add("Order payment is not confirmed as paid.");
        String reserveSql="select coalesce(sum(coalesce(approved_amount,requested_amount)),0) from refunds where company_id=? and order_id=? and status in ('REQUESTED','UNDER_REVIEW','APPROVED','INITIATED','PROCESSING','CREDITED','UNKNOWN')"+(excludeRefundId==null?"":" and id<>?");
        BigDecimal already=excludeRefundId==null?db.queryForObject(reserveSql,BigDecimal.class,c,order.get("id")):db.queryForObject(reserveSql,BigDecimal.class,c,order.get("id"),excludeRefundId);
        if(amount.compareTo(total.subtract(already))>0) reasons.add("Requested amount exceeds the remaining refundable amount.");
        Object delivered=order.get("delivered_at"); if(delivered==null) reasons.add("Delivery date is missing; human review is required.");
        else { Instant at=toInstant(delivered); if(at.plus(Duration.ofDays(days)).isBefore(Instant.now())) reasons.add("Return window has expired."); }
        if(condition.isBlank()||!conditions.contains(condition)) reasons.add("Product condition is missing or not listed in company policy.");
        if(excluded.contains(category.toUpperCase(Locale.ROOT))) reasons.add("Product category is excluded by company policy.");
        if(delivered==null||condition.isBlank()||!conditions.contains(condition)) return new Eligibility("REVIEW_REQUIRED",reasons);
        return new Eligibility(reasons.isEmpty()?"ELIGIBLE":"INELIGIBLE",reasons);
    }
    private static List<String> parseStringList(String s) { try { return JSON.readValue(s,new TypeReference<>(){}); } catch(Exception e) { return List.of(); } }
    private static Instant toInstant(Object value) { if(value instanceof java.sql.Timestamp t)return t.toInstant();if(value instanceof java.time.OffsetDateTime t)return t.toInstant();if(value instanceof Instant t)return t;throw new IllegalStateException("Order delivery timestamp has an unsupported format."); }
    public List<Map<String,Object>> refunds(String status) { UUID c=company(); return db.queryForList("select r.id,r.order_id,o.order_number,r.customer_id,r.requested_amount,r.approved_amount,r.currency,r.reason,r.product_condition,r.status,r.eligibility,r.reviewed_by,r.reviewed_at,r.approval_note,r.provider,r.provider_reference,r.arn,r.created_at,r.updated_at from refunds r join customer_orders o on o.company_id=r.company_id and o.id=r.order_id where r.company_id=? and (?='' or r.status=?) order by r.created_at desc",c,Objects.toString(status,""),Objects.toString(status,"")); }
    public Map<String,Object> refund(UUID id) { return refund(id,company()); }
    private Map<String,Object> refund(UUID id,UUID c) { List<Map<String,Object>> rows=db.queryForList("select id,order_id,requested_amount,approved_amount,currency,reason,product_condition,status,eligibility,reviewed_by,reviewed_at,approval_note,provider,provider_reference,arn,created_at,updated_at from refunds where company_id=? and id=?",c,id); if(rows.isEmpty())throw missing(); return rows.getFirst(); }
    public List<Map<String,Object>> history(UUID id) { UUID c=company(); refund(id,c); return db.queryForList("select event_type,details,created_at from refund_events where company_id=? and refund_id=? order by created_at",c,id); }
    @Transactional
    public Map<String,Object> review(UUID id,String note) { UUID c=company(); Map<String,Object> r=refund(id,c); if(!Set.of("REQUESTED","UNDER_REVIEW").contains(r.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT,"Only pending refunds can be reviewed."); db.update("update refunds set status='UNDER_REVIEW',reviewed_by=?,reviewed_at=now(),approval_note=?,updated_at=now() where company_id=? and id=?",user(),Objects.toString(note,"Agent review requested"),c,id); event(c,id,"UNDER_REVIEW",user(),Map.of("note",Objects.toString(note,""))); audit(c,user(),"REFUND_REVIEWED","REFUND",id,Map.of()); return refund(id,c); }
    @Transactional
    public Map<String,Object> approve(UUID id,BigDecimal approved,String note) {
        requireAdmin(); UUID c=company(); Map<String,Object> r=refund(id,c); if(!Set.of("REQUESTED","UNDER_REVIEW").contains(r.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT,"Only pending refunds can be approved.");
        if(approved==null) approved=(BigDecimal)r.get("requested_amount"); approved=amount(approved);
        if(approved.compareTo((BigDecimal)r.get("requested_amount"))>0) throw new ResponseStatusException(HttpStatus.CONFLICT,"Approved amount cannot exceed the requested amount.");
        Map<String,Object> o=db.queryForMap("select * from customer_orders where company_id=? and id=? for update",c,r.get("order_id"));
        Eligibility check=eligibility(o,approved,Objects.toString(r.get("product_condition"),""),Objects.toString(r.get("product_category"),"GENERAL"),id); if(check.decision.equals("INELIGIBLE")) throw new ResponseStatusException(HttpStatus.CONFLICT,"Current order and policy data do not permit this refund: "+String.join(" ",check.reasons));
        BigDecimal total=(BigDecimal)o.get("total_amount"); BigDecimal reserved=db.queryForObject("select coalesce(sum(coalesce(approved_amount,requested_amount)),0) from refunds where company_id=? and order_id=? and id<>? and status in ('REQUESTED','UNDER_REVIEW','APPROVED','INITIATED','PROCESSING','CREDITED','UNKNOWN')",BigDecimal.class,c,r.get("order_id"),id);
        if(approved.compareTo(total.subtract(reserved))>0) throw new ResponseStatusException(HttpStatus.CONFLICT,"Refund exceeds remaining refundable amount.");
        db.update("update refunds set status='APPROVED',approved_amount=?,reviewed_by=?,reviewed_at=now(),approval_note=?,updated_at=now() where company_id=? and id=?",approved,user(),Objects.toString(note,"Approved by company administrator"),c,id);
        event(c,id,"APPROVED",user(),Map.of("amount",approved.toPlainString())); audit(c,user(),"REFUND_APPROVED","REFUND",id,Map.of("amount",approved.toPlainString())); updatePaymentStatus(c,(UUID)r.get("order_id")); return refund(id,c);
    }
    @Transactional
    public Map<String,Object> reject(UUID id,String reason) { requireAdmin(); UUID c=company(); Map<String,Object> r=refund(id,c); if(!Set.of("REQUESTED","UNDER_REVIEW").contains(r.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT,"Only pending refunds can be rejected."); db.update("update refunds set status='REJECTED',reviewed_by=?,reviewed_at=now(),approval_note=?,updated_at=now() where company_id=? and id=?",user(),Objects.toString(reason,"Rejected by company administrator"),c,id); event(c,id,"REJECTED",user(),Map.of("reason",Objects.toString(reason,""))); audit(c,user(),"REFUND_REJECTED","REFUND",id,Map.of()); return refund(id,c); }
    @Transactional
    public Map<String,Object> initiate(UUID id) {
        requireAdmin(); UUID c=company(); Map<String,Object> r=refund(id,c); if(!"APPROVED".equals(r.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT,"Only approved refunds can be initiated.");
        if(!"DEMO".equalsIgnoreCase(providerMode)||liveEnabled) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"No production payment provider is configured. No transaction was sent.");
        PaymentProvider.Initiation result=demoProvider.initiate(id.toString(),(BigDecimal)r.get("approved_amount"),Objects.toString(r.get("currency")),id.toString());
        db.update("update refunds set status=?,provider=?,provider_reference=?,arn=?,updated_at=now() where company_id=? and id=?",result.status(),demoProvider.name(),result.providerReference(),result.arn(),c,id);
        event(c,id,"PROVIDER_REQUEST",user(),Map.of("provider",demoProvider.name(),"amount",Objects.toString(r.get("approved_amount")))); event(c,id,result.status(),user(),Map.of("provider",demoProvider.name(),"reference",result.providerReference()));
        audit(c,user(),"REFUND_PROVIDER_INITIATED","REFUND",id,Map.of("provider",demoProvider.name(),"demo",result.demo())); updatePaymentStatus(c,(UUID)r.get("order_id")); return refund(id,c);
    }
    private void updatePaymentStatus(UUID c,UUID order) {
        BigDecimal credited=db.queryForObject("select coalesce(sum(approved_amount),0) from refunds where company_id=? and order_id=? and status='CREDITED'",BigDecimal.class,c,order);
        BigDecimal total=db.queryForObject("select total_amount from customer_orders where company_id=? and id=?",BigDecimal.class,c,order);
        String status=credited.compareTo(total)>=0?"REFUNDED":credited.signum()>0?"PARTIALLY_REFUNDED":"PAID";
        db.update("update customer_orders set payment_status=?,updated_at=now() where company_id=? and id=?",status,c,order);
    }
    private void event(UUID c,UUID id,String type,UUID actor,Map<String,Object> detail) { db.update("insert into refund_events(company_id,refund_id,event_type,actor_id,details) values(?,?,?,?,?::jsonb)",c,id,type,actor,json(detail)); }

    @Transactional
    public Map<String,Object> webhook(String raw,String signature) {
        if(!validSignature(raw,signature)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Webhook signature is invalid.");
        try {
            Map<String,Object> body=JSON.readValue(raw,new TypeReference<>(){}); String eventId=Objects.toString(body.get("eventId"),""); UUID id=UUID.fromString(Objects.toString(body.get("refundId"),"")); String status=Objects.toString(body.get("status"),"").toUpperCase(Locale.ROOT);
            if(eventId.isBlank()||eventId.length()>180||!Set.of("PROCESSING","CREDITED","FAILED","UNKNOWN").contains(status)) throw new IllegalArgumentException("Webhook event is invalid.");
            return applyWebhook(eventId,id,status,Objects.toString(body.get("providerReference"),null),Objects.toString(body.get("arn"),null),sha(raw));
        } catch(ResponseStatusException|IllegalArgumentException e) { throw e; } catch(Exception e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Webhook payload is invalid."); }
    }
    private boolean validSignature(String raw,String signature) {
        try { Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8),"HmacSHA256")); byte[] expected=mac.doFinal(raw.getBytes(StandardCharsets.UTF_8)); byte[] supplied=HexFormat.of().parseHex(Objects.toString(signature,"")); return MessageDigest.isEqual(expected,supplied); } catch(Exception e) { return false; }
    }
    private Map<String,Object> applyWebhook(String event,UUID id,String status,String reference,String arn,String payloadHash) {
        List<Map<String,Object>> records=db.queryForList("select company_id,order_id,status,provider_reference from refunds where id=?",id); if(records.isEmpty()) throw missing();
        UUID c=(UUID)records.getFirst().get("company_id"); UUID order=(UUID)records.getFirst().get("order_id");
        int inserted=db.update("insert into payment_webhook_events(provider,event_id,payload_hash) values('DEMO',?,?) on conflict do nothing",event,payloadHash);
        if(inserted==0) {
            String previous=db.queryForObject("select payload_hash from payment_webhook_events where provider='DEMO' and event_id=?",String.class,event);
            if(!MessageDigest.isEqual(previous.getBytes(StandardCharsets.US_ASCII),payloadHash.getBytes(StandardCharsets.US_ASCII))) throw new ResponseStatusException(HttpStatus.CONFLICT,"Webhook event ID was reused with different content.");
            return Map.of("received",true,"duplicate",true);
        }
        String old=Objects.toString(records.getFirst().get("status")); if(!Set.of("INITIATED","PROCESSING","UNKNOWN").contains(old)) { db.update("update payment_webhook_events set processed_at=now() where provider='DEMO' and event_id=?",event); return Map.of("received",true,"ignored",true); }
        db.update("update refunds set status=?,provider='DEMO',provider_reference=coalesce(?,provider_reference),arn=?,updated_at=now() where company_id=? and id=?",status,reference,blankNull(arn),c,id);
        event(c,id,status,null,Map.of("provider","DEMO","webhookEventId",event)); db.update("update payment_webhook_events set processed_at=now() where provider='DEMO' and event_id=?",event); audit(c,null,"REFUND_WEBHOOK_"+status,"REFUND",id,Map.of("provider","DEMO","payloadHash",payloadHash)); updatePaymentStatus(c,order);
        return Map.of("received",true,"duplicate",false,"status",status);
    }
    private static String blankNull(String s) { return s==null||s.isBlank()?null:s.substring(0,Math.min(160,s.length())); }
    public Map<String,Object> assistantOrderContext(String token,UUID companyId) {
        if(token==null||token.isBlank()) return Map.of();
        UUID id=authorizeToken(token); List<Map<String,Object>> rows=db.queryForList("select id,order_number,payment_status,fulfillment_status,tracking_number,total_amount,currency,delivered_at from customer_orders where company_id=? and id=?",companyId,id);
        if(rows.isEmpty()) return Map.of(); Map<String,Object> result=new LinkedHashMap<>(rows.getFirst()); result.put("refunds",db.queryForList("select status,approved_amount,currency,arn,provider_reference from refunds where company_id=? and order_id=? order by created_at desc",companyId,id)); return result;
    }
}
