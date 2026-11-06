package com.supportiq.realtime;

import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AgentPresenceEvents {
    private final JdbcTemplate db;
    private final ConcurrentHashMap<UUID, Set<String>> sessions = new ConcurrentHashMap<>();

    public AgentPresenceEvents(JdbcTemplate db) { this.db = db; }

    @EventListener
    public void connected(SessionConnectedEvent event) {
        if (!(event.getUser() instanceof RealtimeSession session) || !"AGENT".equals(session.role())) return;
        String sessionId = StompHeaderAccessor.wrap(event.getMessage()).getSessionId();
        if (sessionId == null) return;
        sessions.computeIfAbsent(session.userId(), ignored -> ConcurrentHashMap.newKeySet()).add(sessionId);
        db.update("update agent_presence set online=true,last_seen_at=now(),updated_at=now() where company_id=? and agent_id=?", session.companyId(), session.userId());
    }

    @EventListener
    public void disconnected(SessionDisconnectEvent event) {
        if (!(event.getUser() instanceof RealtimeSession session) || !"AGENT".equals(session.role())) return;
        String sessionId = StompHeaderAccessor.wrap(event.getMessage()).getSessionId();
        Set<String> active = sessions.get(session.userId());
        if (active != null && sessionId != null) {
            active.remove(sessionId);
            if (!active.isEmpty()) return;
            sessions.remove(session.userId(), active);
        }
        db.update("update agent_presence set online=false,availability=case when availability='AVAILABLE' then 'OFFLINE' else availability end,last_seen_at=now(),updated_at=now() where company_id=? and agent_id=?", session.companyId(), session.userId());
    }
}
