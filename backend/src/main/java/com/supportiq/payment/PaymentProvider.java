package com.supportiq.payment;

import java.math.BigDecimal;

public interface PaymentProvider {
    String name();
    Initiation initiate(String refundId, BigDecimal amount, String currency, String idempotencyKey);
    record Initiation(String status, String providerReference, String arn, boolean demo) {}
}
