package com.supportiq.realtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.supportiq.service.SupportService;
import com.supportiq.service.TicketWorkspaceService;
import com.supportiq.service.BillingService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.*;

@Service
public class RealtimeChatService {
    private final JdbcTemplate db;
    private final SupportService support;
    private final RealtimePublisher publisher;
    private final TicketWorkspaceService workspace;
    private final BillingService billing;
    private final ObjectMapper json=new ObjectMapper();
    public RealtimeChatService(JdbcTemplate db,SupportService support,RealtimePublisher publisher,TicketWorkspaceService workspace,BillingService billing){this.db=db;this.support=support;this.publisher=publisher;this.workspace=workspace;this.billing=billing;}

    public void customerMessage(RealtimeSession session,String conversationId,Map<String,Object> body){
        billing.assertWritableForRequest(session.companyId());
        UUID id=uuid(conversationId), clientId=uuid(Objects.toString(body.get("clientMessageId"),""));
        String message=Objects.toString(body.get("message"),"").trim();
        if(message.isBlank()||message.length()>4000) throw new IllegalArgumentException("Message must be between 1 and 4000 characters.");
        int claimed=db.update("insert into realtime_message_receipts(company_id,conversation_id,client_message_id) values(?,?,?) on conflict do nothing",session.companyId(),id,clientId);
        if(claimed==0) return;
        try {
            String current=db.queryForObject("select status from conversations where id=? and company_id=?",String.class,id,session.companyId());
            if(List.of("WAITING_FOR_AGENT","AGENT_ACTIVE").contains(current)){
                List<UUID> messageId=db.query("insert into messages(company_id,conversation_id,sender_type,content,client_message_id) values(?,?, 'CUSTOMER',?,?) on conflict(company_id,conversation_id,sender_type,client_message_id) where client_message_id is not null do nothing returning id",(rs,n)->rs.getObject(1,UUID.class),session.companyId(),id,message,clientId);
                if(!messageId.isEmpty()) db.update("update conversations set updated_at=now() where id=? and company_id=?",id,session.companyId());
                Map<String,Object> result=Map.of("messageId",messageId.isEmpty()?"duplicate":messageId.getFirst().toString());
                db.update("update realtime_message_receipts set response=?::jsonb where company_id=? and conversation_id=? and client_message_id=?",json(result),session.companyId(),id,clientId);
                publishCustomerSnapshot(session,id);
                workspace.notifyCustomerMessage(id);
                return;
            }
            Map<String,Object> request=new LinkedHashMap<>(); request.put("message",message); request.put("conversationId",id.toString());
            Map<String,Object> result=support.widgetChat(session.publicCompanyId(),request,session.capabilityToken());
            db.update("update realtime_message_receipts set response=?::jsonb where company_id=? and conversation_id=? and client_message_id=?",json(result),session.companyId(),id,clientId);
            publishCustomerSnapshot(session,id);
        } catch(RuntimeException ex){db.update("delete from realtime_message_receipts where company_id=? and conversation_id=? and client_message_id=? and response is null",session.companyId(),id,clientId);throw ex;}
    }

    public void agentReply(RealtimeSession session,String conversationId,Map<String,Object> body){
        billing.assertWritableForRequest(session.companyId());
        UUID id=uuid(conversationId),clientId=uuid(Objects.toString(body.get("clientMessageId"),""));
        String content=Objects.toString(body.get("message"),"").trim();
        if(content.isBlank()||content.length()>4000) throw new IllegalArgumentException("Reply must be between 1 and 4000 characters.");
        if(!exists(session.companyId(),id)) throw notFound();
        List<UUID> inserted=db.query("insert into messages(company_id,conversation_id,sender_type,content,client_message_id,author_id,delivery_status) values(?,?,?,?,?,?, 'SENT') on conflict(company_id,conversation_id,sender_type,client_message_id) where client_message_id is not null do nothing returning id",(rs,n)->rs.getObject(1,UUID.class),session.companyId(),id,"AGENT",content,clientId,session.userId());
        if(inserted.isEmpty()) return;
        db.update("update conversations set status='AGENT_ACTIVE',updated_at=now() where id=? and company_id=?",id,session.companyId());
        db.update("update support_tickets set status='IN_PROGRESS',updated_at=now() where conversation_id=? and company_id=? and status in ('OPEN','ASSIGNED','WAITING_FOR_AGENT')",id,session.companyId());
        publisher.conversation(id,Map.of("type","MESSAGE","message",Map.of("id",inserted.getFirst().toString(),"sender_type","AGENT","content",content,"created_at",new Date().toInstant().toString(),"delivery_status","SENT","author",db.queryForObject("select full_name from app_users where id=? and company_id=?",String.class,session.userId(),session.companyId()))));
    }

    public void typing(RealtimeSession session,String conversationId,boolean active){
        UUID id=uuid(conversationId); if(!exists(session.companyId(),id)) throw notFound();
        publisher.conversation(id,Map.of("type","TYPING","senderType",session.customer()?"CUSTOMER":"AGENT","active",active));
    }

    public void markRead(RealtimeSession session,String conversationId){
        UUID id=uuid(conversationId); if(!exists(session.companyId(),id)) throw notFound();
        if(session.customer()) {
            db.update("update conversations set customer_last_read_at=now() where id=? and company_id=?",id,session.companyId());
            db.update("update messages set delivery_status='READ',read_at=now() where company_id=? and conversation_id=? and sender_type='AGENT' and visibility='CUSTOMER' and delivery_status<>'READ'",session.companyId(),id);
        } else {
            db.update("update conversations set agent_last_read_at=now() where id=? and company_id=?",id,session.companyId());
            db.update("update messages set delivery_status='READ',read_at=now() where company_id=? and conversation_id=? and sender_type='CUSTOMER' and visibility='CUSTOMER' and delivery_status<>'READ'",session.companyId(),id);
        }
        publisher.conversation(id,Map.of("type","READ","reader",session.customer()?"CUSTOMER":"AGENT","at",new Date().toInstant().toString()));
    }

    public void publishCustomerSnapshot(RealtimeSession session,UUID conversationId){
        List<Map<String,Object>> messages=support.widgetMessages(session.publicCompanyId(),conversationId.toString(),session.capabilityToken());
        publisher.conversation(conversationId,Map.of("type","MESSAGE_SNAPSHOT","messages",messages));
    }
    public void publishCustomerSnapshot(String publicId,UUID conversationId,String token){
        List<Map<String,Object>> messages=support.widgetMessages(publicId,conversationId.toString(),token);
        publisher.conversation(conversationId,Map.of("type","MESSAGE_SNAPSHOT","messages",messages));
    }
    public void publishAgentSnapshot(UUID conversationId,UUID companyId){
        List<Map<String,Object>> messages=db.queryForList("select m.id,m.sender_type,m.content,m.visibility,m.delivery_status,m.created_at,a.id attachment_id,a.file_name attachment_name,a.mime_type attachment_type,a.file_size attachment_size from messages m left join conversation_attachments a on a.message_id=m.id and a.company_id=m.company_id where m.conversation_id=? and m.company_id=? order by m.created_at",conversationId,companyId);
        publisher.staffConversation(conversationId,Map.of("type","MESSAGE_SNAPSHOT","messages",messages));
    }

    private boolean exists(UUID company,UUID conversation){return !db.queryForList("select id from conversations where id=? and company_id=?",conversation,company).isEmpty();}
    private String json(Object value){try{return json.writeValueAsString(value);}catch(Exception e){return "{}";}}
    private UUID uuid(String value){try{return UUID.fromString(value);}catch(Exception e){throw new IllegalArgumentException("A valid conversation or message ID is required.");}}
    private ResponseStatusException notFound(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"Conversation not found.");}
}
