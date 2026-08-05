package com.sse.app.finance;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Deterministic HMAC gateway used while real VNPAY/MoMo adapters are deferred to P3.
 * It exercises the same trust boundary: only a signed server callback can settle money.
 */
@Component
public class HmacSandboxPaymentGateway implements PaymentGateway {

    private static final Set<String> PROVIDERS = Set.of("VNPAY", "MOMO");
    private final PaymentProperties properties;

    public HmacSandboxPaymentGateway(PaymentProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean supports(String provider) {
        if (provider == null) return false;
        String normalized = provider.toUpperCase(Locale.ROOT);
        if ("VNPAY".equals(normalized)) return !properties.getVnpay().isEnabled();
        return "MOMO".equals(normalized) && !properties.getMomo().isEnabled();
    }

    @Override
    public GatewayInitiation initiate(PaymentContext context) {
        String provider = normalizeProvider(context.provider());
        String providerPath = provider.toLowerCase(Locale.ROOT);
        String callbackUrl = baseUrl() + "/payments/" + providerPath + "/ipn";
        String paymentUrl = baseUrl() + "/payments/" + providerPath + "/return?paymentId="
                + encode(context.paymentId());

        Map<String, String> request = new LinkedHashMap<>();
        request.put("provider", provider);
        request.put("txnRef", context.txnRef());
        request.put("invoiceId", context.invoiceId());
        request.put("amount", Long.toString(context.amount()));
        request.put("returnUrl", paymentUrl);
        request.put("ipnUrl", callbackUrl);
        request.put("signature", sign(request));

        Map<String, String> response = new LinkedHashMap<>();
        response.put("gatewayStatus", "PENDING");
        response.put("paymentUrl", paymentUrl);
        response.put("callbackUrl", callbackUrl);
        return new GatewayInitiation(paymentUrl, callbackUrl, request, response);
    }

    @Override
    public GatewayVerification verifyCallback(String providerValue, Map<String, String> payload) {
        String provider = normalizeProvider(providerValue);
        Map<String, String> values = new LinkedHashMap<>();
        if (payload != null) {
            payload.forEach((key, value) -> values.put(key, value == null ? "" : value));
        }

        String suppliedSignature = values.getOrDefault("signature", "");
        boolean signatureValid = secureEquals(sign(values), suppliedSignature);
        String txnRef = trimToNull(values.get("txnRef"));
        String payloadProvider = trimToNull(values.get("provider"));
        String providerTransactionId = trimToNull(values.get("providerTransactionId"));
        String status = values.getOrDefault("status", "").trim().toUpperCase(Locale.ROOT);
        String responseCode = trimToNull(values.get("responseCode"));
        Long amount = parseAmount(values.get("amount"));

        if (!signatureValid) {
            return invalid(false, txnRef, amount, providerTransactionId, responseCode,
                    "SIGNATURE_INVALID", "Chữ ký callback không hợp lệ");
        }
        if (payloadProvider == null || !provider.equals(payloadProvider.toUpperCase(Locale.ROOT))) {
            return invalid(true, txnRef, amount, providerTransactionId, responseCode,
                    "PROVIDER_MISMATCH", "Nhà cung cấp trong callback không khớp");
        }
        if (txnRef == null || amount == null || amount <= 0 || providerTransactionId == null) {
            return invalid(true, txnRef, amount, providerTransactionId, responseCode,
                    "CALLBACK_INVALID", "Callback thiếu mã giao dịch, số tiền hoặc mã giao dịch cổng");
        }

        boolean successful = "SUCCESS".equals(status)
                && (responseCode == null || "00".equals(responseCode));
        boolean terminal = successful || "FAILED".equals(status) || "CANCELLED".equals(status);
        if (!terminal) {
            return new GatewayVerification(true, false, false, txnRef, amount,
                    providerTransactionId, responseCode, null, null);
        }
        return new GatewayVerification(true, successful, true, txnRef, amount,
                providerTransactionId, responseCode,
                successful ? null : (responseCode == null ? "GATEWAY_FAILED" : responseCode),
                successful ? null : "Cổng thanh toán trả về trạng thái thất bại");
    }

    String sign(Map<String, String> payload) {
        String canonical = payload.entrySet().stream()
                .filter(entry -> !"signature".equalsIgnoreCase(entry.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + (entry.getValue() == null ? "" : entry.getValue()))
                .collect(Collectors.joining("&"));
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getSandboxSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return toHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Không thể ký payload thanh toán", ex);
        }
    }

    private GatewayVerification invalid(boolean signatureValid, String txnRef, Long amount, String providerTransactionId,
                                        String responseCode, String code, String message) {
        return new GatewayVerification(signatureValid, false, false, txnRef, amount,
                providerTransactionId, responseCode, code, message);
    }

    private String normalizeProvider(String provider) {
        String normalized = provider == null ? "" : provider.trim().toUpperCase(Locale.ROOT);
        if (!PROVIDERS.contains(normalized)) {
            throw new IllegalArgumentException("Cổng thanh toán không được hỗ trợ");
        }
        return normalized;
    }

    private String baseUrl() {
        String value = properties.getPublicBaseUrl();
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private Long parseAmount(String value) {
        try {
            return value == null ? null : Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    private boolean secureEquals(String expected, String supplied) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value));
        return result.toString();
    }
}
