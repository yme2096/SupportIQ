package com.supportiq.billing;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

public interface BillingProvider {
    String name();
    Checkout createCheckout(UUID paymentId, BigDecimal amount, String currency, String description);
    boolean verifyEvent(String body, String signature);
    record Checkout(String providerReference, String checkoutUrl, boolean demo) {}
    record SignedEvent(String body, String signature) {}
    SignedEvent demoEvent(UUID paymentId, String eventId, String type);
}
