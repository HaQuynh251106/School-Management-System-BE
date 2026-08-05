package com.sse.app.finance;

import com.sse.app.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** MoMo one-time payment adapter using the official v2 create and notification contracts. */
@Component
public class MomoPaymentGateway implements PaymentGateway {

    static final String PROVIDER = "MOMO";
    private final PaymentProperties properties;
    private final MomoApiClient apiClient;

    public MomoPaymentGateway(PaymentProperties properties, MomoApiClient apiClient) {
        this.properties = properties;
        this.apiClient = apiClient;
    }

    @Override
    public boolean supports(String provider) {
        return properties.getMomo().isEnabled()
                && provider != null
                && PROVIDER.equals(provider.trim().toUpperCase(Locale.ROOT));
    }

    @Override
    public GatewayInitiation initiate(PaymentContext context) {
        assertConfigured();
        PaymentProperties.Momo config = properties.getMomo();
        String orderInfo = "Thanh toan hoc phi hoa don " + asciiReference(context.invoiceId());
        String extraData = "";

        Map<String, String> signatureFields = new LinkedHashMap<>();
        signatureFields.put("accessKey", config.getAccessKey());
        signatureFields.put("amount", Long.toString(context.amount()));
        signatureFields.put("extraData", extraData);
        signatureFields.put("ipnUrl", config.getIpnUrl());
        signatureFields.put("orderId", context.txnRef());
        signatureFields.put("orderInfo", orderInfo);
        signatureFields.put("partnerCode", config.getPartnerCode());
        signatureFields.put("redirectUrl", config.getRedirectUrl());
        signatureFields.put("requestId", context.txnRef());
        signatureFields.put("requestType", "captureWallet");
        String signature = hmacSha256(config.getSecretKey(), createSignatureData(signatureFields));

        Map<String, Object> apiRequest = new LinkedHashMap<>();
        apiRequest.put("partnerCode", config.getPartnerCode());
        apiRequest.put("partnerName", config.getPartnerName());
        apiRequest.put("storeId", config.getStoreId());
        apiRequest.put("requestType", "captureWallet");
        apiRequest.put("ipnUrl", config.getIpnUrl());
        apiRequest.put("redirectUrl", config.getRedirectUrl());
        apiRequest.put("orderId", context.txnRef());
        apiRequest.put("amount", context.amount());
        apiRequest.put("lang", "vi");
        apiRequest.put("autoCapture", true);
        apiRequest.put("orderInfo", orderInfo);
        apiRequest.put("requestId", context.txnRef());
        apiRequest.put("extraData", extraData);
        apiRequest.put("signature", signature);

        Map<String, Object> apiResponse = apiClient.createPayment(config.getCreateUrl(), apiRequest);
        int resultCode = intValue(apiResponse.get("resultCode"), -1);
        String payUrl = stringValue(apiResponse.get("payUrl"));
        validateCreateResponse(context, apiResponse, resultCode, payUrl);

        Map<String, String> storedRequest = stringify(apiRequest);
        Map<String, String> storedResponse = stringify(apiResponse);
        storedResponse.put("gatewayStatus", "PENDING");
        storedResponse.put("mode", "SANDBOX");
        return new GatewayInitiation(payUrl, config.getIpnUrl(), storedRequest, storedResponse);
    }

    @Override
    public GatewayVerification verifyCallback(String provider, Map<String, String> payload) {
        assertConfigured();
        Map<String, String> values = payload == null ? Map.of() : payload;
        String suppliedSignature = value(values, "signature");
        String txnRef = value(values, "orderId");
        Long amount = longValue(value(values, "amount"));
        String providerTransactionId = value(values, "transId");
        String responseCode = value(values, "resultCode");

        boolean signatureValid = suppliedSignature != null
                && secureEquals(hmacSha256(properties.getMomo().getSecretKey(), callbackSignatureData(values)),
                suppliedSignature);
        if (!signatureValid) {
            return invalid(false, txnRef, amount, providerTransactionId, responseCode,
                    "SIGNATURE_INVALID", "Chu ky MoMo khong hop le");
        }
        if (!properties.getMomo().getPartnerCode().equals(value(values, "partnerCode"))) {
            return invalid(true, txnRef, amount, providerTransactionId, responseCode,
                    "MERCHANT_MISMATCH", "Ma doi tac MoMo khong khop");
        }
        String requestId = value(values, "requestId");
        Integer resultCode = integerValue(responseCode);
        if (txnRef == null || requestId == null || !txnRef.equals(requestId)
                || amount == null || amount <= 0 || providerTransactionId == null || resultCode == null) {
            return invalid(true, txnRef, amount, providerTransactionId, responseCode,
                    "CALLBACK_INVALID", "Callback MoMo thieu du lieu bat buoc");
        }
        if (resultCode == 9000) {
            return new GatewayVerification(true, false, false, txnRef, amount,
                    providerTransactionId, responseCode, null, null);
        }
        boolean successful = resultCode == 0;
        String message = value(values, "message");
        return new GatewayVerification(true, successful, true, txnRef, amount,
                providerTransactionId, responseCode,
                successful ? null : responseCode,
                successful ? null : (message == null ? "MoMo tu choi giao dich" : message));
    }

    String createSignatureData(Map<String, String> values) {
        return "accessKey=" + valueOrEmpty(values, "accessKey")
                + "&amount=" + valueOrEmpty(values, "amount")
                + "&extraData=" + valueOrEmpty(values, "extraData")
                + "&ipnUrl=" + valueOrEmpty(values, "ipnUrl")
                + "&orderId=" + valueOrEmpty(values, "orderId")
                + "&orderInfo=" + valueOrEmpty(values, "orderInfo")
                + "&partnerCode=" + valueOrEmpty(values, "partnerCode")
                + "&redirectUrl=" + valueOrEmpty(values, "redirectUrl")
                + "&requestId=" + valueOrEmpty(values, "requestId")
                + "&requestType=" + valueOrEmpty(values, "requestType");
    }

    String callbackSignatureData(Map<String, String> values) {
        return "accessKey=" + properties.getMomo().getAccessKey()
                + "&amount=" + valueOrEmpty(values, "amount")
                + "&extraData=" + valueOrEmpty(values, "extraData")
                + "&message=" + valueOrEmpty(values, "message")
                + "&orderId=" + valueOrEmpty(values, "orderId")
                + "&orderInfo=" + valueOrEmpty(values, "orderInfo")
                + "&orderType=" + valueOrEmpty(values, "orderType")
                + "&partnerCode=" + valueOrEmpty(values, "partnerCode")
                + "&payType=" + valueOrEmpty(values, "payType")
                + "&requestId=" + valueOrEmpty(values, "requestId")
                + "&responseTime=" + valueOrEmpty(values, "responseTime")
                + "&resultCode=" + valueOrEmpty(values, "resultCode")
                + "&transId=" + valueOrEmpty(values, "transId");
    }

    String hmacSha256(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Khong the ky du lieu MoMo", ex);
        }
    }

    private void validateCreateResponse(PaymentContext context, Map<String, Object> response,
                                        int resultCode, String payUrl) {
        if (resultCode != 0) {
            String message = stringValue(response.get("message"));
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    message == null ? "MoMo khong tao duoc giao dich" : "MoMo: " + message);
        }
        if (!context.txnRef().equals(stringValue(response.get("orderId")))
                || !context.txnRef().equals(stringValue(response.get("requestId")))
                || context.amount() != longObject(response.get("amount"), -1)
                || !properties.getMomo().getPartnerCode().equals(stringValue(response.get("partnerCode")))
                || payUrl == null || !isMomoUrl(payUrl)) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Phan hoi create payment cua MoMo khong hop le");
        }
    }

    private boolean isMomoUrl(String value) {
        try {
            String host = URI.create(value).getHost();
            return host != null && ("momo.vn".equals(host) || host.endsWith(".momo.vn"));
        } catch (Exception ex) {
            return false;
        }
    }

    private Map<String, String> stringify(Map<String, ?> values) {
        Map<String, String> result = new LinkedHashMap<>();
        values.forEach((key, item) -> result.put(key, item == null ? "" : String.valueOf(item)));
        return result;
    }

    private String asciiReference(String value) {
        if (value == null) return "SSE";
        String normalized = value.replaceAll("[^A-Za-z0-9]", "");
        return normalized.isBlank() ? "SSE" : normalized;
    }

    private String value(Map<String, String> values, String key) {
        String result = values.get(key);
        return result == null || result.isBlank() ? null : result.trim();
    }

    private String valueOrEmpty(Map<String, String> values, String key) {
        String result = values.get(key);
        return result == null ? "" : result;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Long longValue(String value) {
        try { return value == null ? null : Long.parseLong(value); }
        catch (NumberFormatException ex) { return null; }
    }

    private long longObject(Object value, long fallback) {
        Long parsed = longValue(stringValue(value));
        return parsed == null ? fallback : parsed;
    }

    private Integer integerValue(String value) {
        try { return value == null ? null : Integer.parseInt(value); }
        catch (NumberFormatException ex) { return null; }
    }

    private int intValue(Object value, int fallback) {
        Integer parsed = integerValue(stringValue(value));
        return parsed == null ? fallback : parsed;
    }

    private boolean secureEquals(String expected, String supplied) {
        return MessageDigest.isEqual(
                expected.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII),
                supplied.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }

    private GatewayVerification invalid(boolean signatureValid, String txnRef, Long amount,
                                        String providerTransactionId, String responseCode,
                                        String errorCode, String errorMessage) {
        return new GatewayVerification(signatureValid, false, false, txnRef, amount,
                providerTransactionId, responseCode, errorCode, errorMessage);
    }

    private void assertConfigured() {
        PaymentProperties.Momo config = properties.getMomo();
        if (!config.isEnabled() || blank(config.getPartnerCode()) || blank(config.getAccessKey())
                || blank(config.getSecretKey()) || blank(config.getCreateUrl())
                || blank(config.getRedirectUrl()) || blank(config.getIpnUrl())) {
            throw ApiException.badRequest(
                    "MoMo chua duoc cau hinh. Can PartnerCode, AccessKey, SecretKey, redirectUrl va ipnUrl");
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
