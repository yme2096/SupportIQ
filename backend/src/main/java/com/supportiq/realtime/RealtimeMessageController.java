package com.supportiq.realtime;

import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;

import java.util.Map;

@Controller
public class RealtimeMessageController {
    private final RealtimeChatService chat;
    public RealtimeMessageController(RealtimeChatService chat){this.chat=chat;}
    @MessageMapping("/customer/conversations/{id}/send")
    public void customerSend(@DestinationVariable String id,Map<String,Object> body,SimpMessageHeaderAccessor headers){chat.customerMessage(session(headers),id,body);}
    @MessageMapping("/agent/conversations/{id}/reply")
    public void agentReply(@DestinationVariable String id,Map<String,Object> body,SimpMessageHeaderAccessor headers){chat.agentReply(session(headers),id,body);}
    @MessageMapping("/{audience}/conversations/{id}/typing")
    public void typing(@DestinationVariable String audience,@DestinationVariable String id,Map<String,Object> body,SimpMessageHeaderAccessor headers){chat.typing(session(headers),id,Boolean.TRUE.equals(body.get("active")));}
    @MessageMapping("/{audience}/conversations/{id}/read")
    public void read(@DestinationVariable String audience,@DestinationVariable String id,SimpMessageHeaderAccessor headers){chat.markRead(session(headers),id);}
    private RealtimeSession session(SimpMessageHeaderAccessor headers){if(headers.getUser() instanceof RealtimeSession session)return session;throw new IllegalStateException("Unauthorized realtime session.");}
}
