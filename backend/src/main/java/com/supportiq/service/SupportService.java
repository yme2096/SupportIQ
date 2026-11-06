package com.supportiq.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.supportiq.rag.CompanyAiSettings;
import com.supportiq.rag.CompanyAiSettingsService;
import com.supportiq.rag.GroundedAnswerService;
import com.supportiq.rag.KnowledgeChunk;
import com.supportiq.rag.KnowledgeRetrievalService;
import com.supportiq.security.TenantContext;
import com.supportiq.realtime.RealtimePublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SupportService {
    private final JdbcTemplate db;
    private final ObjectMapper json = new ObjectMapper();
    private final KnowledgeRetrievalService retrieval;
    private final CompanyAiSettingsService aiSettings;
    private final GroundedAnswerService answerService;
    private final ObjectProvider<TicketWorkspaceService> workspace;
    private final ObjectProvider<RealtimePublisher> realtime;
    private final ObjectProvider<OrderRefundService> orderRefunds;
    private final BillingService billing;

    public SupportService(JdbcTemplate db, KnowledgeRetrievalService retrieval, CompanyAiSettingsService aiSettings, GroundedAnswerService answerService, ObjectProvider<TicketWorkspaceService> workspace,ObjectProvider<RealtimePublisher> realtime,ObjectProvider<OrderRefundService> orderRefunds,BillingService billing) {
        this.db=db; this.retrieval=retrieval; this.aiSettings=aiSettings; this.answerService=answerService; this.workspace=workspace;this.realtime=realtime;this.orderRefunds=orderRefunds;this.billing=billing;
    }

    public Map<String,Object> health() {
        return Map.of("status", "UP", "database", db.queryForObject("select 1", Integer.class) == 1 ? "UP" : "DOWN", "product", "SupportIQ");
    }

    private UUID tenant() { return TenantContext.requireCompanyId(); }

    public Map<String,Object> chat(Map<String,Object> body, String suppliedChatToken) {
        return chatForCompany(body, suppliedChatToken, resolveChatCompany(body), TenantContext.isStaff());
    }

    public Map<String,Object> widgetChat(String publicId, Map<String,Object> body, String suppliedChatToken) {
        UUID companyId = companyIdForPublicId(publicId);
        return chatForCompany(body, suppliedChatToken, companyId, false);
    }

    private Map<String,Object> chatForCompany(Map<String,Object> body, String suppliedChatToken, UUID companyId, boolean staff) {
        String question = Objects.toString(body.get("message"), "").trim();
        if (question.isBlank()) throw new IllegalArgumentException("Message cannot be empty.");
        String conversationValue = Objects.toString(body.get("conversationId"), "");
        boolean created = conversationValue.isBlank();
        UUID conversationId;
        String chatToken = null;
        if (created) {
            conversationId = UUID.randomUUID();
            chatToken = randomToken();
            billing.assertAiConversation(companyId,conversationId);
            db.update("insert into conversations(id,company_id,customer_name,public_access_hash) values(?,?,?,?)",
                    conversationId, companyId, Objects.toString(body.getOrDefault("customerName", "Guest customer")), hash(chatToken));
        } else {
            try { conversationId = UUID.fromString(conversationValue); }
            catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found."); }
            if (!staff && !validConversationToken(conversationId, companyId, suppliedChatToken))
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found.");
            List<Map<String,Object>> owned = db.queryForList("select status from conversations where id=? and company_id=?", conversationId, companyId);
            if (owned.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found.");
            billing.assertAiConversation(companyId,conversationId);
            String currentStatus = Objects.toString(owned.get(0).get("status"));
            if ("WAITING_FOR_AGENT".equals(currentStatus) || "AGENT_ACTIVE".equals(currentStatus))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A human agent owns this conversation. Please wait for their reply.");
        }

        db.update("insert into messages(company_id,conversation_id,sender_type,content) values(?,?,?,?)", companyId, conversationId, "CUSTOMER", question);
        String lower = question.toLowerCase(Locale.ROOT);
        boolean refund = has(lower, "arn", "credited", "refund status", "where is my refund", "refund delayed", "refund not", "money back", "refund initiated", "has my refund", "my refund", "refund received");
        boolean orderQuestion = has(lower,"where is my order","track my order","order status","has my order","my order shipped","order shipped","shipping status","delivery status");
        boolean handoff = has(lower, "human", "agent", "representative", "talk to someone", "speak to someone");
        boolean unhappy = has(lower, "wrong answer", "not correct", "incorrect", "not helpful", "useless", "still not solved", "not getting correct");
        boolean damage = has(lower, "damaged", "broken", "defective", "wrong item", "replacement");
        Matcher matcher = Pattern.compile("\\bSS-\\d{5}\\b", Pattern.CASE_INSENSITIVE).matcher(question);
        String orderRef = matcher.find() ? matcher.group().toUpperCase(Locale.ROOT) : null;
        Map<String,Object> order = Map.of();
        String orderAccessToken=Objects.toString(body.get("orderAccessToken"),"");
        if(!orderAccessToken.isBlank()) try { order=orderRefunds.getObject().assistantOrderContext(orderAccessToken,companyId); } catch(ResponseStatusException ignored) { order=Map.of(); }
        if(orderRef!=null&&!orderRef.equalsIgnoreCase(Objects.toString(order.get("order_number"),""))) order=Map.of();
        boolean unknownRefund = refund && order.isEmpty();
        boolean unknownOrder = orderQuestion && order.isEmpty();
        boolean unknownSensitive = unknownRefund || unknownOrder;
        boolean escalate = handoff || unhappy || damage || unknownRefund;
        String reason = handoff ? "Customer requested a human agent" : unhappy ? "Customer reported an incorrect or unhelpful answer" :
                damage ? "Damaged/defective item requires review" : unknownSensitive ? "Order/refund information requires customer verification" : "";
        String answer;
        List<String> sources = new ArrayList<>();
        List<Map<String,Object>> sourceReferences = new ArrayList<>();
        Double retrievalScore = null;
        if (unknownSensitive) escalate=true;
        if (escalate) {
            answer = unknownSensitive
                    ? "I can't securely verify that order from this chat. Please use the company's secure order lookup with your order number and matching customer details, or I can connect you with a support agent."
                    : "I'm sorry this hasn't been resolved. I'm connecting you with a human support agent and sharing this conversation so you won't need to repeat the details.";
            createTicket(companyId, conversationId, reason, refund ? "Refund assistance required" : "Order assistance required", refund ? "HIGH" : "NORMAL");
        } else if ((refund||orderQuestion) && !order.isEmpty()) {
            String number=Objects.toString(order.get("order_number"),"verified order");
            if(refund) {
                @SuppressWarnings("unchecked") List<Map<String,Object>> items=(List<Map<String,Object>>)order.getOrDefault("refunds",List.of());
                if(items.isEmpty()) answer="I verified order "+number+". No refund request is recorded for this order. I can explain the company's policy or help submit a request.";
                else {
                    Map<String,Object> latest=items.getLast(); String state=Objects.toString(latest.get("status"),"UNKNOWN");
                    answer="I verified order "+number+". The latest refund status is "+state.replace('_',' ').toLowerCase(Locale.ROOT)+".";
                    if("INITIATED".equals(state)||"PROCESSING".equals(state)) answer+=" This does not confirm that the funds have been credited.";
                    if("CREDITED".equals(state)) answer+=" The provider has confirmed the refund as credited.";
                    if(latest.get("arn")!=null&&!Objects.toString(latest.get("arn")).isBlank()) answer+=" ARN: "+latest.get("arn")+".";
                }
            } else {
                answer="I verified order "+number+". Its current status is "+Objects.toString(order.get("fulfillment_status"),"unknown").replace('_',' ').toLowerCase(Locale.ROOT)+".";
                if(order.get("tracking_number")!=null) answer+=" Tracking number: "+order.get("tracking_number")+".";
            }
            sources.add("Verified order service · "+number);
        } else {
            CompanyAiSettings options=aiSettings.forCompany(companyId);
            if(!options.enabled()) {
                answer=options.fallbackResponse(); escalate=options.handoffOnUnknown(); reason="Company AI assistant is disabled";
            } else {
                KnowledgeRetrievalService.RetrievalResult result=retrieval.retrieve(companyId,question,options);
                List<KnowledgeChunk> relevant=result.chunks();
                sources.addAll(relevant.stream().map(KnowledgeChunk::documentName).distinct().toList());
                sourceReferences.addAll(relevant.stream().map(this::sourceReference).toList());
                if(result.vectorSearch()&&!relevant.isEmpty()) retrievalScore=relevant.getFirst().similarity();
                if(relevant.isEmpty()) {
                    answer=options.fallbackResponse();
                    escalate=options.handoffOnUnknown();
                    reason="No sufficiently relevant approved knowledge was found";
                } else if(retrieval.hasPotentialConflict(relevant,question)) {
                    answer="I found conflicting information in the approved company documents, so I cannot choose between them. I will connect you with a support agent to confirm the correct information.";
                    escalate=options.handoffOnUnknown();
                    reason="Conflicting knowledge sources require human review";
                } else {
                    GroundedAnswerService.AnswerResult generated=answerService.answer(question,relevant,options);
                    answer=generated.answer();
                    if(GroundedAnswerService.NO_MATCHING_SOURCE_REASON.equals(generated.reason())) {
                        sources.clear();
                        sourceReferences.clear();
                        retrievalScore=null;
                    }
                    escalate=generated.handoffRecommended()&&options.handoffOnUnknown();
                    if(generated.handoffRecommended()) reason=Objects.toString(generated.reason(),"A support agent should review this response");
                }
            }
            if(escalate) createTicket(companyId,conversationId,reason.isBlank()?"Knowledge gap requires review":reason,"Knowledge support follow-up","NORMAL");
        }
        db.update("insert into messages(company_id,conversation_id,sender_type,content,sources,source_refs,retrieval_score) values(?,?,?,?,?::jsonb,?::jsonb,?)",
                companyId,conversationId,"AI",answer,json(sources),json(sourceReferences),retrievalScore);
        String status = escalate ? "WAITING_FOR_AGENT" : "BOT_ACTIVE";
        db.update("update conversations set status=?,escalation_reason=?,updated_at=now() where id=? and company_id=?", status, reason.isBlank() ? null : reason, conversationId, companyId);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("conversationId", conversationId.toString());
        result.put("answer", answer);
        result.put("sources", sources);
        result.put("sourceReferences",sourceReferences);
        if(retrievalScore!=null) result.put("relevanceScore",retrievalScore);
        result.put("handoffRecommended",escalate);
        result.put("escalated", escalate);
        result.put("escalationReason", reason);
        result.put("status", status);
        if (chatToken != null) result.put("chatAccessToken", chatToken);
        return result;
    }

    public List<Map<String,Object>> widgetMessages(String publicId, String conversationId, String chatToken) {
        UUID companyId = companyIdForPublicId(publicId);
        UUID id = parseUuid(conversationId);
        if (!validConversationToken(id, companyId, chatToken))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found.");
        List<Map<String,Object>> rows = db.queryForList("select m.id,m.sender_type,m.content,m.sources,m.source_refs,m.retrieval_score,m.created_at,m.visibility,m.delivery_status,a.id attachment_id,a.file_name attachment_name,a.mime_type attachment_type,a.file_size attachment_size,c.status conversation_status from messages m join conversations c on c.id=m.conversation_id left join conversation_attachments a on a.message_id=m.id and a.company_id=m.company_id where c.id=? and c.company_id=? and m.company_id=? and m.visibility='CUSTOMER' order by m.created_at", id, companyId, companyId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found.");
        return normalizeMessages(rows);
    }

    public UUID companyIdForPublicId(String publicId) {
        UUID parsed;
        try { parsed = UUID.fromString(publicId); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Company widget not found."); }
        try { return db.queryForObject("select id from companies where public_id=?", UUID.class, parsed); }
        catch (org.springframework.dao.EmptyResultDataAccessException ex) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Company widget not found."); }
    }

    public UUID authorizeWidgetConversation(String publicId,String conversationId,String token) {
        UUID companyId=companyIdForPublicId(publicId);
        UUID id=parseUuid(conversationId);
        if(!validConversationToken(id,companyId,token)) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Realtime customer access is not authorized.");
        return companyId;
    }

    private UUID resolveChatCompany(Map<String,Object> body) {
        UUID authenticatedTenant = TenantContext.companyIdOrNull();
        if (authenticatedTenant != null && TenantContext.isStaff()) return authenticatedTenant;
        String slug = Objects.toString(body.get("companySlug"), "").trim().toLowerCase(Locale.ROOT);
        if (slug.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Company workspace is required.");
        try { return db.queryForObject("select id from companies where slug=?", UUID.class, slug); }
        catch (org.springframework.dao.EmptyResultDataAccessException ex) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Company workspace not found."); }
    }

    private boolean validConversationToken(UUID conversationId, UUID companyId, String token) {
        if (token == null || token.isBlank()) return false;
        List<String> values = db.query("select public_access_hash from conversations where id=? and company_id=? and public_access_hash is not null",
                (rs, n) -> rs.getString(1), conversationId, companyId);
        return !values.isEmpty() && MessageDigest.isEqual(values.get(0).getBytes(StandardCharsets.US_ASCII), hash(token).getBytes(StandardCharsets.US_ASCII));
    }

    public List<Map<String,Object>> conversations() {
        UUID companyId = tenant();
        return db.queryForList("select id,customer_name,status,escalation_reason,created_at from conversations where company_id=? order by updated_at desc limit 100", companyId);
    }

    public List<Map<String,Object>> messages(String id, String chatToken) {
        UUID companyId = TenantContext.companyIdOrNull();
        boolean staff = TenantContext.isStaff();
        if (!staff) {
            if (companyId == null) {
                if (chatToken == null || chatToken.isBlank()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found.");
                List<Map<String,Object>> rows = db.queryForList("select m.id,m.sender_type,m.content,m.sources,m.source_refs,m.retrieval_score,m.created_at,m.visibility,m.delivery_status,a.id attachment_id,a.file_name attachment_name,a.mime_type attachment_type,a.file_size attachment_size,c.status conversation_status from messages m join conversations c on c.id=m.conversation_id left join conversation_attachments a on a.message_id=m.id and a.company_id=m.company_id where c.id=? and c.public_access_hash=? and m.visibility='CUSTOMER' order by m.created_at", parseUuid(id), hash(chatToken));
                if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found.");
                return normalizeMessages(rows);
            }
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Customer accounts must use their conversation access token.");
        }
        List<Map<String,Object>> rows = db.queryForList("select m.id,m.sender_type,m.content,m.sources,m.source_refs,m.retrieval_score,m.created_at,c.status conversation_status from messages m join conversations c on c.id=m.conversation_id where c.id=? and c.company_id=? and m.company_id=? order by m.created_at", parseUuid(id), companyId, companyId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found.");
        return normalizeMessages(rows);
    }

    private List<Map<String,Object>> normalizeMessages(List<Map<String,Object>> rows) {
        for (Map<String,Object> row : rows) {
            Object src = row.get("sources");
            try { row.put("sources", src == null ? List.of() : json.readValue(src.toString(), List.class)); }
            catch (Exception ex) { row.put("sources", List.of()); }
            Object refs=row.get("source_refs");
            try { row.put("source_refs",refs==null?List.of():json.readValue(refs.toString(),List.class)); }
            catch(Exception ex) { row.put("source_refs",List.of()); }
            row.put("escalated","AI".equals(row.get("sender_type"))&&List.of("WAITING_FOR_AGENT","AGENT_ACTIVE").contains(row.get("conversation_status")));
        }
        return rows;
    }

    private Map<String,Object> sourceReference(KnowledgeChunk chunk) {
        Map<String,Object> ref=new LinkedHashMap<>();
        ref.put("documentId",chunk.documentId().toString()); ref.put("documentName",chunk.documentName());
        ref.put("pageNumber",chunk.pageNumber()); ref.put("section",chunk.sectionTitle());
        ref.put("relevanceScore",chunk.similarity()); return ref;
    }

    public List<Map<String,Object>> tickets() {
        return db.queryForList("select t.id,t.title,t.reason,t.priority,t.status,t.assigned_agent,t.created_at,c.customer_name,c.id conversation_id from support_tickets t join conversations c on c.id=t.conversation_id where t.company_id=? order by t.created_at desc", tenant());
    }

    public Map<String,Object> reply(String id, Map<String,Object> body) {
        UUID companyId = tenant();
        String message = Objects.toString(body.get("message"), "").trim();
        if (message.isBlank()) throw new IllegalArgumentException("Reply cannot be empty.");
        UUID conversationId = parseUuid(id);
        if (db.queryForList("select id from conversations where id=? and company_id=?", conversationId, companyId).isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found.");
        UUID messageId=db.queryForObject("insert into messages(company_id,conversation_id,sender_type,content,author_id,delivery_status) values(?,?,?, ?,?,'SENT') returning id",UUID.class,companyId,conversationId,"AGENT",message,TenantContext.userIdOrNull());
        db.update("update conversations set status='AGENT_ACTIVE',updated_at=now() where id=? and company_id=?", conversationId, companyId);
        realtime.getObject().conversation(conversationId,Map.of("type","MESSAGE","message",Map.of("id",messageId.toString(),"sender_type","AGENT","content",message,"created_at",java.time.Instant.now().toString(),"delivery_status","SENT","author",db.queryForObject("select full_name from app_users where id=? and company_id=?",String.class,TenantContext.userIdOrNull(),companyId))));
        return Map.of("message", message, "sender", "AGENT");
    }

    public Map<String,Object> update(String id, Map<String,Object> body) {
        UUID companyId = tenant();
        String status = Objects.toString(body.getOrDefault("status", "IN_PROGRESS"));
        if (!List.of("OPEN", "IN_PROGRESS", "RESOLVED").contains(status)) throw new IllegalArgumentException("Invalid status.");
        UUID ticketId = parseUuid(id);
        int updated = db.update("update support_tickets set status=?,assigned_agent=coalesce(assigned_agent,'Support agent'),updated_at=now() where id=? and company_id=?", status, ticketId, companyId);
        if (updated == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket not found.");
        if ("RESOLVED".equals(status)) db.update("update conversations set status='RESOLVED',updated_at=now() where id=(select conversation_id from support_tickets where id=? and company_id=?) and company_id=?", ticketId, companyId, companyId);
        return Map.of("id", id, "status", status);
    }

    public Map<String,Object> analytics() {
        UUID companyId = tenant();
        return Map.of("conversations", db.queryForObject("select count(*) from conversations where company_id=?", Integer.class, companyId),
                "pendingTickets", db.queryForObject("select count(*) from support_tickets where company_id=? and status not in ('RESOLVED','CLOSED')", Integer.class, companyId),
                "resolvedTickets", db.queryForObject("select count(*) from support_tickets where company_id=? and status='RESOLVED'", Integer.class, companyId),
                "knowledgeDocuments", db.queryForObject("select count(*) from knowledge_documents where company_id=?", Integer.class, companyId));
    }

    private void createTicket(UUID companyId, UUID conversationId, String reason, String title, String priority) {
        List<UUID> inserted=db.query("insert into support_tickets(company_id,conversation_id,title,reason,priority) values(?,?,?,?,?) on conflict(company_id,conversation_id) where status not in ('RESOLVED','CLOSED') do nothing returning id",(rs,n)->rs.getObject(1,UUID.class),companyId,conversationId,title,reason,priority);
        if(!inserted.isEmpty())workspace.getObject().onNewTicket(companyId,conversationId,inserted.getFirst());
    }

    private boolean has(String text, String... terms) { for (String term : terms) if (text.contains(term)) return true; return false; }
    private String json(Object value) { try { return json.writeValueAsString(value); } catch (Exception ex) { return "[]"; } }
    private String randomToken() { return UUID.randomUUID() + "." + UUID.randomUUID(); }
    private UUID parseUuid(String value) { try { return UUID.fromString(value); } catch (Exception ex) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Record not found."); } }
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }
}
