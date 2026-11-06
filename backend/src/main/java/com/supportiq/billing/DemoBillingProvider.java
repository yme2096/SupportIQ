package com.supportiq.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@Component
public class DemoBillingProvider implements BillingProvider {
    private final ObjectMapper json = new ObjectMapper();
    @Value("${app.billing.webhook-secret:local-demo-billing-secret-change-before-deploy}") private String secret;
    @Override public String name() { return "DEMO"; }
    @Override public Checkout createCheckout(UUID paymentId, BigDecimal amount, String currency, String description) {
        return new Checkout("DEMO-BILLING-" + paymentId, "/billing/demo-checkout/" + paymentId, true);
    }
    @Override public SignedEvent demoEvent(UUID paymentId, String eventId, String type) {
        try {
            String body = json.writeValueAsString(Map.of("eventId", eventId, "type", type, "paymentId", paymentId.toString(), "providerReference", "DEMO-BILLING-" + paymentId, "failureReason", "PAYMENT_FAILED".equals(type) ? "Simulated demo payment failure" : ""));
            return new SignedEvent(body, sign(body));
        } catch (Exception e) { throw new IllegalStateException("Could not create local demo billing event.", e); }
    }
    @Override public boolean verifyEvent(String body, String signature) {
        if (body == null || signature == null) return false;
        return java.security.MessageDigest.isEqual(sign(body).getBytes(StandardCharsets.US_ASCII), signature.trim().getBytes(StandardCharsets.US_ASCII));
    }
    private String sign(String body) {
        try { Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256")); return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
}
