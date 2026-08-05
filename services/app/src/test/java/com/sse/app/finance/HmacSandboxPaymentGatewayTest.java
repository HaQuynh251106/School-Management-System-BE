package com.sse.app.finance;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HmacSandboxPaymentGatewayTest {

    @Test
    void verifiesUntamperedCallbackAndRejectsChangedAmount() {
        PaymentProperties properties = new PaymentProperties();
        properties.setSandboxSecret("unit-test-secret");
        HmacSandboxPaymentGateway gateway = new HmacSandboxPaymentGateway(properties);
        Map<String, String> callback = callback();
        callback.put("signature", gateway.sign(callback));

        PaymentGateway.GatewayVerification valid = gateway.verifyCallback("VNPAY", callback);
        assertTrue(valid.signatureValid());
        assertTrue(valid.successful());
        assertTrue(valid.terminal());

        callback.put("amount", "1");
        PaymentGateway.GatewayVerification tampered = gateway.verifyCallback("VNPAY", callback);
        assertFalse(tampered.signatureValid());
        assertEquals("SIGNATURE_INVALID", tampered.errorCode());
    }

    @Test
    void initiationReturnsDisplayOnlyReturnAndServerIpnUrls() {
        PaymentProperties properties = new PaymentProperties();
        properties.setPublicBaseUrl("http://127.0.0.1:4000/");
        HmacSandboxPaymentGateway gateway = new HmacSandboxPaymentGateway(properties);

        PaymentGateway.GatewayInitiation initiated = gateway.initiate(new PaymentGateway.PaymentContext(
                "pay-1", "invoice-1", "VNPAY-tx-1", 500_000, "VNPAY", "127.0.0.1"));

        assertEquals("http://127.0.0.1:4000/payments/vnpay/return?paymentId=pay-1", initiated.paymentUrl());
        assertEquals("http://127.0.0.1:4000/payments/vnpay/ipn", initiated.callbackUrl());
        assertEquals("PENDING", initiated.responsePayload().get("gatewayStatus"));
        assertNotNull(initiated.requestPayload().get("signature"));
    }

    private Map<String, String> callback() {
        Map<String, String> callback = new LinkedHashMap<>();
        callback.put("provider", "VNPAY");
        callback.put("txnRef", "VNPAY-tx-1");
        callback.put("amount", "500000");
        callback.put("status", "SUCCESS");
        callback.put("providerTransactionId", "provider-1");
        callback.put("responseCode", "00");
        return callback;
    }
}
