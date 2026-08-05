package com.sse.app.finance;

import java.util.Map;

/** Provider boundary used by the payment orchestration service. */
public interface PaymentGateway {

    boolean supports(String provider);

    GatewayInitiation initiate(PaymentContext context);

    GatewayVerification verifyCallback(String provider, Map<String, String> payload);

    record PaymentContext(
            String paymentId,
            String invoiceId,
            String txnRef,
            long amount,
            String provider,
            String clientIp) {}

    record GatewayInitiation(
            String paymentUrl,
            String callbackUrl,
            Map<String, String> requestPayload,
            Map<String, String> responsePayload) {}

    record GatewayVerification(
            boolean signatureValid,
            boolean successful,
            boolean terminal,
            String txnRef,
            Long amount,
            String providerTransactionId,
            String responseCode,
            String errorCode,
            String errorMessage) {}
}
