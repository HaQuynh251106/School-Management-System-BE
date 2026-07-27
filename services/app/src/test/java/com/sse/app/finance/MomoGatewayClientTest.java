package com.sse.app.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MomoGatewayClientTest {
    private static final String SECRET = "momo-test-secret-key";
    private final MomoGatewayClient client = new MomoGatewayClient(
            new ObjectMapper(),
            "https://test-payment.momo.vn/v2/gateway/api/create",
            "MOMO_TEST",
            "ACCESS_TEST",
            SECRET,
            "http://127.0.0.1:5173/?payment=momo#/D4",
            "https://example.test/payments/momo/ipn");

    @Test
    void verifiesOfficialMomoIpnCanonicalSignatureAndRejectsTampering() throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("partnerCode", "MOMO_TEST");
        payload.put("orderId", "MOMO123");
        payload.put("requestId", "MOMO123");
        payload.put("amount", 125000L);
        payload.put("orderInfo", "Thanh toan hoa don HD001");
        payload.put("orderType", "momo_wallet");
        payload.put("transId", 987654321L);
        payload.put("resultCode", 0);
        payload.put("message", "Successful.");
        payload.put("payType", "webApp");
        payload.put("responseTime", 1785140000000L);
        payload.put("extraData", "");

        String canonical = "accessKey=ACCESS_TEST&amount=125000&extraData=&message=Successful."
                + "&orderId=MOMO123&orderInfo=Thanh toan hoa don HD001&orderType=momo_wallet"
                + "&partnerCode=MOMO_TEST&payType=webApp&requestId=MOMO123"
                + "&responseTime=1785140000000&resultCode=0&transId=987654321";
        payload.put("signature", sign(canonical));
        assertTrue(client.verifyIpn(payload));

        payload.put("amount", 125001L);
        assertFalse(client.verifyIpn(payload));
    }

    private static String sign(String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }
}
