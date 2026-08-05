package com.sse.app.finance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VnpayPaymentGatewayTest {

    private PaymentProperties properties;
    private VnpayPaymentGateway gateway;

    @BeforeEach
    void setUp() {
        properties = new PaymentProperties();
        properties.getVnpay().setEnabled(true);
        properties.getVnpay().setTmnCode("TESTCODE");
        properties.getVnpay().setHashSecret("test-secret");
        properties.getVnpay().setPaymentUrl("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html");
        properties.getVnpay().setReturnUrl("http://127.0.0.1:5173/?paymentReturn=vnpay");
        properties.getVnpay().setIpnUrl("https://merchant.example/payments/vnpay/ipn");
        gateway = new VnpayPaymentGateway(properties);
    }

    @Test
    void usesKnownHmacSha512Vector() {
        assertEquals(
                "9aee177f4697217bf0314e9b0601f895ed0f2d7b04010bb14813464e136d0456"
                        + "06be82ccc1d67fe564b5f4bd99a4f7a0b33d6123bf4fa867ba9fc733fd4a81e6",
                gateway.hmacSha512("test-secret", "vnp_Amount=100000000&vnp_TxnRef=SSEpay1"));
    }

    @Test
    void initiationBuildsOfficialVnpayPayRequest() {
        PaymentGateway.GatewayInitiation result = gateway.initiate(new PaymentGateway.PaymentContext(
                "pay-1", "invoice-1", "SSEpay1", 1_000_000, "VNPAY", "::1"));

        assertTrue(result.paymentUrl().startsWith(
                "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?"));
        assertEquals("100000000", result.requestPayload().get("vnp_Amount"));
        assertEquals("127.0.0.1", result.requestPayload().get("vnp_IpAddr"));
        assertEquals("TESTCODE", result.requestPayload().get("vnp_TmnCode"));
        assertEquals("SSEpay1", result.requestPayload().get("vnp_TxnRef"));
        assertEquals("2.1.0", result.requestPayload().get("vnp_Version"));
        assertEquals("https://merchant.example/payments/vnpay/ipn", result.callbackUrl());
        assertEquals(128, result.requestPayload().get("vnp_SecureHash").length());
        assertTrue(result.paymentUrl().contains("vnp_ReturnUrl=http%3A%2F%2F127.0.0.1%3A5173%2F%3FpaymentReturn%3Dvnpay"));
    }

    @Test
    void validatesOfficialCallbackAndConvertsAmountBackToVnd() {
        Map<String, String> callback = callback();
        callback.put("vnp_SecureHash", gateway.hmacSha512("test-secret", gateway.canonical(callback)));

        PaymentGateway.GatewayVerification result = gateway.verifyCallback("VNPAY", callback);

        assertTrue(result.signatureValid());
        assertTrue(result.successful());
        assertTrue(result.terminal());
        assertEquals(1_000_000L, result.amount());
        assertEquals("SSEpay1", result.txnRef());
        assertEquals("14587425", result.providerTransactionId());
    }

    @Test
    void rejectsTamperedAmountBeforeBusinessValidation() {
        Map<String, String> callback = callback();
        callback.put("vnp_SecureHash", gateway.hmacSha512("test-secret", gateway.canonical(callback)));
        callback.put("vnp_Amount", "1");

        PaymentGateway.GatewayVerification result = gateway.verifyCallback("VNPAY", callback);

        assertFalse(result.signatureValid());
        assertEquals("SIGNATURE_INVALID", result.errorCode());
    }

    @Test
    void rejectsSignedCallbackForAnotherMerchant() {
        Map<String, String> callback = callback();
        callback.put("vnp_TmnCode", "OTHERCOD");
        callback.put("vnp_SecureHash", gateway.hmacSha512("test-secret", gateway.canonical(callback)));

        PaymentGateway.GatewayVerification result = gateway.verifyCallback("VNPAY", callback);

        assertTrue(result.signatureValid());
        assertFalse(result.successful());
        assertEquals("MERCHANT_MISMATCH", result.errorCode());
    }

    private Map<String, String> callback() {
        Map<String, String> callback = new LinkedHashMap<>();
        callback.put("vnp_Amount", "100000000");
        callback.put("vnp_BankCode", "NCB");
        callback.put("vnp_PayDate", "20260720120000");
        callback.put("vnp_ResponseCode", "00");
        callback.put("vnp_TmnCode", "TESTCODE");
        callback.put("vnp_TransactionNo", "14587425");
        callback.put("vnp_TransactionStatus", "00");
        callback.put("vnp_TxnRef", "SSEpay1");
        return callback;
    }
}
