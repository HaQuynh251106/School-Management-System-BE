package com.sse.app.finance;

import com.sse.app.common.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class MomoPaymentGatewayTest {

    private PaymentProperties properties;

    @BeforeEach
    void setUp() {
        properties = new PaymentProperties();
        properties.getMomo().setEnabled(true);
        properties.getMomo().setPartnerCode("TESTPARTNER");
        properties.getMomo().setAccessKey("test-access");
        properties.getMomo().setSecretKey("test-secret");
        properties.getMomo().setCreateUrl("https://test-payment.momo.vn/v2/gateway/api/create");
        properties.getMomo().setRedirectUrl("http://127.0.0.1:5173/?paymentReturn=momo");
        properties.getMomo().setIpnUrl("https://merchant.example/payments/momo/ipn");
    }

    @Test
    void usesOfficialCreateSignatureOrderAndHmacSha256() {
        MomoPaymentGateway gateway = gateway(successClient());
        Map<String, String> fields = createSignatureFields();
        String raw = gateway.createSignatureData(fields);

        assertEquals("accessKey=test-access&amount=100000&extraData="
                + "&ipnUrl=https://merchant.example/payments/momo/ipn"
                + "&orderId=MOMOpay1&orderInfo=Thanh toan hoc phi hoa don invoice1"
                + "&partnerCode=TESTPARTNER"
                + "&redirectUrl=http://127.0.0.1:5173/?paymentReturn=momo"
                + "&requestId=MOMOpay1&requestType=captureWallet", raw);
        assertEquals("7d44fea6151b2856f51228de17d42a0bf371eff1a9c2f959c65d37b73a6e887d",
                gateway.hmacSha256("test-secret", raw));
    }

    @Test
    void initiationCallsMomoCreateApiAndReturnsPayUrl() {
        AtomicReference<Map<String, Object>> captured = new AtomicReference<>();
        MomoApiClient client = (url, payload) -> {
            assertEquals("https://test-payment.momo.vn/v2/gateway/api/create", url);
            captured.set(payload);
            return successResponse();
        };
        MomoPaymentGateway gateway = gateway(client);

        PaymentGateway.GatewayInitiation result = gateway.initiate(context());

        assertEquals("https://test-payment.momo.vn/v2/gateway/pay?t=test", result.paymentUrl());
        assertEquals("https://merchant.example/payments/momo/ipn", result.callbackUrl());
        assertEquals(100_000L, captured.get().get("amount"));
        assertEquals(true, captured.get().get("autoCapture"));
        assertEquals("captureWallet", captured.get().get("requestType"));
        assertEquals(64, String.valueOf(captured.get().get("signature")).length());
        assertEquals("PENDING", result.responsePayload().get("gatewayStatus"));
    }

    @Test
    void validatesSuccessfulNotification() {
        MomoPaymentGateway gateway = gateway(successClient());
        Map<String, String> callback = callback();
        callback.put("signature", gateway.hmacSha256("test-secret", gateway.callbackSignatureData(callback)));

        PaymentGateway.GatewayVerification result = gateway.verifyCallback("MOMO", callback);

        assertTrue(result.signatureValid());
        assertTrue(result.successful());
        assertTrue(result.terminal());
        assertEquals(100_000L, result.amount());
        assertEquals("4088878653", result.providerTransactionId());
    }

    @Test
    void rejectsTamperedNotificationAndAnotherPartner() {
        MomoPaymentGateway gateway = gateway(successClient());
        Map<String, String> callback = callback();
        callback.put("signature", gateway.hmacSha256("test-secret", gateway.callbackSignatureData(callback)));
        callback.put("amount", "1");
        assertFalse(gateway.verifyCallback("MOMO", callback).signatureValid());

        callback = callback();
        callback.put("partnerCode", "OTHERPARTNER");
        callback.put("signature", gateway.hmacSha256("test-secret", gateway.callbackSignatureData(callback)));
        PaymentGateway.GatewayVerification mismatch = gateway.verifyCallback("MOMO", callback);
        assertTrue(mismatch.signatureValid());
        assertEquals("MERCHANT_MISMATCH", mismatch.errorCode());
    }

    @Test
    void authorizedResultIsNotSettledAsSuccessfulPayment() {
        MomoPaymentGateway gateway = gateway(successClient());
        Map<String, String> callback = callback();
        callback.put("resultCode", "9000");
        callback.put("signature", gateway.hmacSha256("test-secret", gateway.callbackSignatureData(callback)));

        PaymentGateway.GatewayVerification result = gateway.verifyCallback("MOMO", callback);

        assertTrue(result.signatureValid());
        assertFalse(result.successful());
        assertFalse(result.terminal());
    }

    @Test
    void invalidCreateResponseFailsWithoutReturningRedirectUrl() {
        MomoPaymentGateway gateway = gateway((url, payload) -> Map.of(
                "partnerCode", "TESTPARTNER", "orderId", "MOMOpay1", "requestId", "MOMOpay1",
                "amount", 100_000L, "resultCode", 1006, "message", "Partner denied"));

        assertThrows(ApiException.class, () -> gateway.initiate(context()));
    }

    private MomoPaymentGateway gateway(MomoApiClient client) {
        return new MomoPaymentGateway(properties, client);
    }

    private MomoApiClient successClient() {
        return (url, payload) -> successResponse();
    }

    private Map<String, Object> successResponse() {
        return Map.of(
                "partnerCode", "TESTPARTNER",
                "orderId", "MOMOpay1",
                "requestId", "MOMOpay1",
                "amount", 100_000L,
                "resultCode", 0,
                "message", "Successful.",
                "payUrl", "https://test-payment.momo.vn/v2/gateway/pay?t=test");
    }

    private PaymentGateway.PaymentContext context() {
        return new PaymentGateway.PaymentContext(
                "pay-1", "invoice-1", "MOMOpay1", 100_000, "MOMO", "127.0.0.1");
    }

    private Map<String, String> createSignatureFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("accessKey", "test-access");
        fields.put("amount", "100000");
        fields.put("extraData", "");
        fields.put("ipnUrl", "https://merchant.example/payments/momo/ipn");
        fields.put("orderId", "MOMOpay1");
        fields.put("orderInfo", "Thanh toan hoc phi hoa don invoice1");
        fields.put("partnerCode", "TESTPARTNER");
        fields.put("redirectUrl", "http://127.0.0.1:5173/?paymentReturn=momo");
        fields.put("requestId", "MOMOpay1");
        fields.put("requestType", "captureWallet");
        return fields;
    }

    private Map<String, String> callback() {
        Map<String, String> callback = new LinkedHashMap<>();
        callback.put("partnerCode", "TESTPARTNER");
        callback.put("orderId", "MOMOpay1");
        callback.put("requestId", "MOMOpay1");
        callback.put("amount", "100000");
        callback.put("orderInfo", "Thanh toan hoc phi hoa don invoice1");
        callback.put("orderType", "momo_wallet");
        callback.put("transId", "4088878653");
        callback.put("resultCode", "0");
        callback.put("message", "Successful.");
        callback.put("payType", "qr");
        callback.put("responseTime", "1721720663942");
        callback.put("extraData", "");
        return callback;
    }
}
