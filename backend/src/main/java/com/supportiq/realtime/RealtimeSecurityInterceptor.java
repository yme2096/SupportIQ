package com.supportiq.realtime;

import com.supportiq.service.SupportService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.UUID;

@Component
public class RealtimeSecurityInterceptor implements ChannelInterceptor {
    private final JwtDecoder jwtDecoder;
    private final JdbcTemplate db;
    private final SupportService support;

    public RealtimeSecurityInterceptor(JwtDecoder jwtDecoder, JdbcTemplate db, SupportService support) {
        this.jwtDecoder=jwtDecoder; this.db=db; this.support=support;
    }

    @Override public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor=MessageHeaderAccessor.getAccessor(message,StompHeaderAccessor.class);
        if(accessor==null || accessor.getCommand()==null) return message;
        StompCommand command=accessor.getCommand();
        if(command==StompCommand.CONNECT) {
            accessor.setUser(authenticate(accessor));
            return message;
        }
        RealtimeSession session=accessor.getUser() instanceof RealtimeSession principal?principal:null;
        if(session==null) throw denied();
        String destination=accessor.getDestination();
        if(command==StompCommand.SUBSCRIBE) authorizeSubscribe(session,destination);
        else if(command==StompCommand.SEND) authorizeSend(session,destination);
        else if(command!=StompCommand.DISCONNECT && command!=StompCommand.UNSUBSCRIBE && command!=StompCommand.ACK && command!=StompCommand.NACK)
            throw denied();
        return message;
    }

    private RealtimeSession authenticate(StompHeaderAccessor accessor) {
        List<String> authorization=accessor.getNativeHeader("Authorization");
        if(authorization!=null&&!authorization.isEmpty()&&authorization.getFirst().startsWith("Bearer ")) {
            Jwt jwt;
            try { jwt=jwtDecoder.decode(authorization.getFirst().substring(7)); } catch(Exception ex) { throw denied(); }
            String role=jwt.getClaimAsString("role"); String subject=jwt.getSubject();
            if(!List.of("AGENT","COMPANY_ADMIN","SUPER_ADMIN").contains(role)) throw denied();
            UUID userId,companyId;
            try { userId=UUID.fromString(subject); companyId=UUID.fromString(jwt.getClaimAsString("companyId")); }
            catch(Exception ex) { throw denied(); }
            List<Integer> active=db.query("select 1 from app_users u join roles r on r.id=u.role_id where u.id=? and u.company_id=? and u.status='ACTIVE' and r.name=?",(rs,n)->rs.getInt(1),userId,companyId,role);
            if(active.isEmpty()) throw denied();
            if("AGENT".equals(role)) db.update("insert into agent_presence(company_id,agent_id,online,last_seen_at,updated_at) values(?,?,true,now(),now()) on conflict(company_id,agent_id) do update set online=true,last_seen_at=now(),updated_at=now()",companyId,userId);
            return new RealtimeSession(userId.toString(),companyId,userId,role,null,null,null);
        }
        String publicId=header(accessor,"X-Company-Public-Id");
        String conversation=header(accessor,"X-Conversation-Id");
        String token=header(accessor,"X-Conversation-Token");
        UUID conversationId;
        try { conversationId=UUID.fromString(conversation); } catch(Exception ex) { throw denied(); }
        UUID companyId=support.authorizeWidgetConversation(publicId,conversation,token);
        return new RealtimeSession("customer:"+conversationId,companyId,null,null,conversationId,publicId,token);
    }

    private void authorizeSubscribe(RealtimeSession session,String destination) {
        if(destination==null) throw denied();
        if(destination.matches("/topic/conversations/[0-9a-fA-F-]{36}")) {
            UUID conversationId=parse(destination.substring("/topic/conversations/".length()));
            if(session.customer()) {
                if(!conversationId.equals(session.customerConversationId())) throw denied();
                support.authorizeWidgetConversation(session.publicCompanyId(),conversationId.toString(),session.capabilityToken());
            } else if(db.queryForList("select id from conversations where id=? and company_id=?",conversationId,session.companyId()).isEmpty()) throw denied();
            return;
        }
        if(destination.matches("/topic/staff/conversations/[0-9a-fA-F-]{36}")&&session.staff()) {
            UUID conversationId=parse(destination.substring("/topic/staff/conversations/".length()));
            if(db.queryForList("select id from conversations where id=? and company_id=?",conversationId,session.companyId()).isEmpty()) throw denied();
            return;
        }
        if(destination.equals("/user/queue/notifications")&&session.staff()) return;
        throw denied();
    }

    private void authorizeSend(RealtimeSession session,String destination) {
        if(destination==null) throw denied();
        String[] parts=destination.split("/");
        if(parts.length==6&&"app".equals(parts[1])&&"conversations".equals(parts[3])) {
            UUID id=parse(parts[4]);
            if(session.customer()) {
                if(!id.equals(session.customerConversationId())||!("customer".equals(parts[2])||"ack".equals(parts[2]))) throw denied();
                support.authorizeWidgetConversation(session.publicCompanyId(),id.toString(),session.capabilityToken());
            } else {
                if(!List.of("agent","ack").contains(parts[2])) throw denied();
                if(!List.of("AGENT","COMPANY_ADMIN","SUPER_ADMIN").contains(session.role())) throw denied();
                if(db.queryForList("select id from conversations where id=? and company_id=?",id,session.companyId()).isEmpty()) throw denied();
            }
            if(!List.of("send","reply","typing","read").contains(parts[5])) throw denied();
            return;
        }
        throw denied();
    }

    private String header(StompHeaderAccessor accessor,String name) { List<String> value=accessor.getNativeHeader(name); return value==null||value.isEmpty()?null:value.getFirst(); }
    private UUID parse(String value){try{return UUID.fromString(value);}catch(Exception ex){throw denied();}}
    private ResponseStatusException denied(){return new ResponseStatusException(HttpStatus.FORBIDDEN,"Realtime access is not authorized.");}
}
