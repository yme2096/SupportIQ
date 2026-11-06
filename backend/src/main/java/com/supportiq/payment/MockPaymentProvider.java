package com.supportiq.payment;

import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.UUID;

@Component
public class MockPaymentProvider implements PaymentProvider {
    @Override public String name() { return "DEMO"; }
    @Override public Initiation initiate(String refundId, BigDecimal amount, String currency, String idempotencyKey) {
        return new Initiation("INITIATED", "DEMO-REF-" + UUID.randomUUID().toString().substring(0,8).toUpperCase(), null, true);
    }
}
