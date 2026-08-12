package com.sse.app.finance;

import com.sse.app.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Component
class SandboxPaymentGateway {
    static final String GATEWAY = "SSE_SANDBOX";
    static final String CURRENCY = "VND";

    private final String merchantCode;
    private final String secret;
    private final String publicBaseUrl;
    private final boolean enabled;

    SandboxPaymentGateway(
            @Value("${sse.payments.sandbox.merchant-code:SSE_SCHOOL}") String merchantCode,
            @Value("${sse.payments.sandbox.secret:local-sandbox-secret-change-me}") String secret,
            @Value("${sse.payments.public-base-url:http://127.0.0.1:4000}") String publicBaseUrl,
            @Value("${sse.payments.sandbox.enabled:false}") boolean enabled) {
        this.merchantCode = merchantCode;
        this.secret = secret;
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
        this.enabled = enabled;
    }

    void requireEnabled() {
        if (!enabled) throw ApiException.serviceUnavailable("Cổng thanh toán sandbox chưa được bật");
    }

    String merchantCode() {
        return merchantCode;
    }

    String paymentUrl(String txnRef) {
        return publicBaseUrl + "/payments/sandbox/checkout?txnRef=" + txnRef;
    }

    String sign(String txnRef, String gatewayTransactionId, String callbackEventId,
                long amount, String currency, String status) {
        return hmac(canonical(merchantCode, txnRef, gatewayTransactionId,
                callbackEventId, amount, currency, status));
    }

    void verify(FinanceDtos.GatewayCallbackRequest request) {
        if (!merchantCode.equals(request.merchantCode())) {
            throw ApiException.badRequest("Merchant thanh toán không hợp lệ");
        }
        String expected = hmac(canonical(request.merchantCode(), request.txnRef(),
                request.gatewayTransactionId(), request.callbackEventId(), request.amount(),
                request.currency(), request.status()));
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                request.signature().toLowerCase().getBytes(StandardCharsets.US_ASCII))) {
            throw ApiException.badRequest("Chữ ký callback không hợp lệ");
        }
    }

    private String canonical(String merchant, String txnRef, String gatewayTransactionId,
                             String callbackEventId, long amount, String currency, String status) {
        return String.join("|", merchant, txnRef, gatewayTransactionId, callbackEventId,
                Long.toString(amount), currency.toUpperCase(), status.toUpperCase());
    }

    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("Không thể ký giao dịch sandbox", error);
        }
    }
}
