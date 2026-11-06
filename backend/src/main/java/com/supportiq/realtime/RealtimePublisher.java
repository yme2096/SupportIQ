package com.supportiq.realtime;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
public class RealtimePublisher {
    private final SimpMessagingTemplate messaging;
    public RealtimePublisher(SimpMessagingTemplate messaging){this.messaging=messaging;}
    public void conversation(UUID id,Map<String,Object> event){messaging.convertAndSend("/topic/conversations/"+id,event);}
    public void staffConversation(UUID id,Map<String,Object> event){messaging.convertAndSend("/topic/staff/conversations/"+id,event);}
    public void user(UUID id,Map<String,Object> event){messaging.convertAndSendToUser(id.toString(),"/queue/notifications",event);}
}
