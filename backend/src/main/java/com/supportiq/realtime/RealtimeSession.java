package com.supportiq.realtime;

import java.security.Principal;
import java.util.UUID;

public record RealtimeSession(String name, UUID companyId, UUID userId, String role,
                              UUID customerConversationId, String publicCompanyId, String capabilityToken) implements Principal {
    @Override public String getName() { return name; }
    public boolean customer() { return customerConversationId != null; }
    public boolean staff() { return role != null; }
}
