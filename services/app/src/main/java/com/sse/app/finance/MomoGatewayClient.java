package com.sse.app.finance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sse.app.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * MoMo test gateway adapter. Secret keys are read from environment variables and
 * are never returned to the frontend or persisted in transaction payloads.
 */
@Component
public class MomoGatewayClient {
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final String endpoint;
    private final String partnerCode;
    private final String accessKey;
    private final String secretKey;
    private final String redirectUrl;
    private final String ipnUrl;

    public MomoGatewayClient(
            ObjectMapper json,
            @Value("${sse.payments.momo.endpoint:https://test-payment.momo.vn/v2/gateway/api/create}") String endpoint,
            @Value("${sse.payments.momo.partner-code:}") String partnerCode,
            @Value("${sse.payments.momo.access-key:}") String accessKey,
            @Value("${sse.payments.momo.secret-key:}") String secretKey,
            @Value("${sse.payments.momo.redirect-url:}") String redirectUrl,
            @Value("${sse.payments.momo.ipn-url:}") String ipnUrl) {
        this.json = json;
        this.endpoint = endpoint;
        this.partnerCode = partnerCode;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.redirectUrl = redirectUrl;
        this.ipnUrl = ipnUrl;
    }

    public MomoCreateResult createPayment(String orderId, long amount, String invoiceCode) {
        requireConfigured();
        String requestId = orderId;
        String orderInfo = "Thanh toan hoa don " + invoiceCode;
        String requestType = "captureWallet";
        String extraData = "";
        String rawSignature = "accessKey=" + accessKey
                + "&amount=" + amount
                + "&extraData=" + extraData
                + "&ipnUrl=" + ipnUrl
                + "&orderId=" + orderId
                + "&orderInfo=" + orderInfo
                + "&partnerCode=" + partnerCode
                + "&redirectUrl=" + redirectUrl
                + "&requestId=" + requestId
                + "&requestType=" + requestType;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("partnerCode", partnerCode);
        payload.put("partnerName", "Truong hoc so");
        payload.put("storeId", "SmartSchool");
        payload.put("requestId", requestId);
        payload.put("amount", amount);
        payload.put("orderId", orderId);
        payload.put("orderInfo", orderInfo);
        payload.put("redirectUrl", redirectUrl);
        payload.put("ipnUrl", ipnUrl);
        payload.put("lang", "vi");
        payload.put("requestType", requestType);
        payload.put("autoCapture", true);
        payload.put("extraData", extraData);
        payload.put("signature", hmacSha256(rawSignature));

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw ApiException.serviceUnavailable("MoMo Sandbox không phản hồi thành công (HTTP " + response.statusCode() + ")");
            }
            Map<String, Object> body = json.readValue(response.body(), new TypeReference<>() {});
            int resultCode = intValue(body.get("resultCode"), -1);
            String payUrl = text(body.get("payUrl"));
            if (resultCode != 0 || payUrl.isBlank()) {
                String message = text(body.get("message"));
                throw ApiException.serviceUnavailable("MoMo từ chối tạo giao dịch"
                        + (message.isBlank() ? " (mã " + resultCode + ")" : ": " + message));
            }
            return new MomoCreateResult(payUrl, resultCode, text(body.get("message")));
        } catch (ApiException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw ApiException.serviceUnavailable("Kết nối MoMo Sandbox bị gián đoạn");
        } catch (Exception exception) {
            throw ApiException.serviceUnavailable("Không thể kết nối MoMo Sandbox: " + exception.getMessage());
        }
    }

    public boolean verifyIpn(Map<String, Object> payload) {
        requireConfigured();
        String received = text(payload.get("signature")).toLowerCase();
        if (received.isBlank()) return false;
        String rawSignature = "accessKey=" + accessKey
                + "&amount=" + text(payload.get("amount"))
                + "&extraData=" + text(payload.get("extraData"))
                + "&message=" + text(payload.get("message"))
                + "&orderId=" + text(payload.get("orderId"))
                + "&orderInfo=" + text(payload.get("orderInfo"))
                + "&orderType=" + text(payload.get("orderType"))
                + "&partnerCode=" + text(payload.get("partnerCode"))
                + "&payType=" + text(payload.get("payType"))
                + "&requestId=" + text(payload.get("requestId"))
                + "&responseTime=" + text(payload.get("responseTime"))
                + "&resultCode=" + text(payload.get("resultCode"))
                + "&transId=" + text(payload.get("transId"));
        byte[] expected = hmacSha256(rawSignature).getBytes(StandardCharsets.US_ASCII);
        byte[] actual = received.getBytes(StandardCharsets.US_ASCII);
        return java.security.MessageDigest.isEqual(expected, actual);
    }

    public String safeCallbackPayload(Map<String, Object> payload) {
        Map<String, Object> safe = new LinkedHashMap<>(payload);
        safe.remove("signature");
        try {
            String value = json.writeValueAsString(safe);
            return value.substring(0, Math.min(value.length(), 4000));
        } catch (Exception ignored) {
            return "{\"payload\":\"unavailable\"}";
        }
    }

    public Map<String, Object> ipnResponse(String orderId, String requestId, int resultCode, String message) {
        requireConfigured();
        long responseTime = System.currentTimeMillis();
        String extraData = "";
        String rawSignature = "accessKey=" + accessKey
                + "&extraData=" + extraData
                + "&message=" + message
                + "&orderId=" + orderId
                + "&partnerCode=" + partnerCode
                + "&requestId=" + requestId
                + "&responseTime=" + responseTime
                + "&resultCode=" + resultCode;
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("partnerCode", partnerCode);
        response.put("requestId", requestId);
        response.put("orderId", orderId);
        response.put("resultCode", resultCode);
        response.put("message", message);
        response.put("responseTime", responseTime);
        response.put("extraData", extraData);
        response.put("signature", hmacSha256(rawSignature));
        return response;
    }

    private void requireConfigured() {
        if (!endpoint.startsWith("https://test-payment.momo.vn/")
                || partnerCode.isBlank() || accessKey.isBlank() || secretKey.length() < 8
                || redirectUrl.isBlank() || ipnUrl.isBlank()) {
            throw ApiException.serviceUnavailable(
                    "Thiếu cấu hình MoMo Sandbox: Partner Code, Access Key, Secret Key, Redirect URL và IPN URL");
        }
    }

    private String hmacSha256(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Không thể ký yêu cầu MoMo", exception);
        }
    }

    private static String text(Object value) {
        return Objects.toString(value, "");
    }

    private static int intValue(Object value, int fallback) {
        try {
            return Integer.parseInt(text(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    public record MomoCreateResult(String payUrl, int resultCode, String message) {}
}
