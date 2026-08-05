package com.sse.app.finance;

import com.sse.app.common.ApiException;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** VNPAY PAY 2.1.0 adapter for the official sandbox and production-compatible callback contract. */
@Component
public class VnpayPaymentGateway implements PaymentGateway {

    static final String PROVIDER = "VNPAY";
    private static final DateTimeFormatter VNPAY_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final PaymentProperties properties;

    public VnpayPaymentGateway(PaymentProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean supports(String provider) {
        return properties.getVnpay().isEnabled()
                && provider != null
                && PROVIDER.equals(provider.trim().toUpperCase(Locale.ROOT));
    }

    @Override
    public GatewayInitiation initiate(PaymentContext context) {
        assertConfigured();
        PaymentProperties.Vnpay config = properties.getVnpay();
        ZonedDateTime createdAt = ZonedDateTime.now(VIETNAM_ZONE);

        Map<String, String> request = new LinkedHashMap<>();
        request.put("vnp_Version", "2.1.0");
        request.put("vnp_Command", "pay");
        request.put("vnp_TmnCode", config.getTmnCode().trim());
        request.put("vnp_Amount", Long.toString(Math.multiplyExact(context.amount(), 100L)));
        request.put("vnp_CurrCode", "VND");
        request.put("vnp_TxnRef", context.txnRef());
        request.put("vnp_OrderInfo", "Thanh toan hoc phi hoa don " + asciiReference(context.invoiceId()));
        request.put("vnp_OrderType", "other");
        request.put("vnp_Locale", "vn");
        request.put("vnp_ReturnUrl", config.getReturnUrl().trim());
        request.put("vnp_IpAddr", normalizeIp(context.clientIp()));
        request.put("vnp_CreateDate", VNPAY_TIME.format(createdAt));
        request.put("vnp_ExpireDate", VNPAY_TIME.format(createdAt.plusMinutes(config.getExpireMinutes())));

        String hashData = canonical(request);
        String secureHash = hmacSha512(config.getHashSecret(), hashData);
        request.put("vnp_SecureHash", secureHash);

        String paymentUrl = appendQuery(config.getPaymentUrl(), hashData + "&vnp_SecureHash=" + secureHash);
        String ipnUrl = configuredIpnUrl();
        Map<String, String> response = new LinkedHashMap<>();
        response.put("gatewayStatus", "PENDING");
        response.put("gateway", PROVIDER);
        response.put("mode", "SANDBOX");
        response.put("paymentUrl", paymentUrl);
        response.put("ipnUrl", ipnUrl);
        return new GatewayInitiation(paymentUrl, ipnUrl, request, response);
    }

    @Override
    public GatewayVerification verifyCallback(String provider, Map<String, String> payload) {
        assertConfigured();
        Map<String, String> values = onlyVnpayFields(payload);
        String suppliedHash = trimToNull(values.remove("vnp_SecureHash"));
        values.remove("vnp_SecureHashType");

        String txnRef = trimToNull(values.get("vnp_TxnRef"));
        Long amount = parseAmount(values.get("vnp_Amount"));
        String providerTransactionId = trimToNull(values.get("vnp_TransactionNo"));
        String responseCode = trimToNull(values.get("vnp_ResponseCode"));
        String transactionStatus = trimToNull(values.get("vnp_TransactionStatus"));

        boolean signatureValid = suppliedHash != null
                && secureEquals(hmacSha512(properties.getVnpay().getHashSecret(), canonical(values)), suppliedHash);
        if (!signatureValid) {
            return invalid(false, txnRef, amount, providerTransactionId, responseCode,
                    "SIGNATURE_INVALID", "Chu ky VNPAY khong hop le");
        }
        String callbackTmnCode = trimToNull(values.get("vnp_TmnCode"));
        if (callbackTmnCode == null
                || !properties.getVnpay().getTmnCode().trim().equals(callbackTmnCode)) {
            return invalid(true, txnRef, amount, providerTransactionId, responseCode,
                    "MERCHANT_MISMATCH", "Ma merchant VNPAY khong khop");
        }
        if (txnRef == null || amount == null || amount <= 0 || providerTransactionId == null
                || responseCode == null || transactionStatus == null) {
            return invalid(true, txnRef, amount, providerTransactionId, responseCode,
                    "CALLBACK_INVALID", "Callback VNPAY thieu du lieu bat buoc");
        }

        boolean successful = "00".equals(responseCode) && "00".equals(transactionStatus);
        return new GatewayVerification(true, successful, true, txnRef, amount,
                providerTransactionId, responseCode,
                successful ? null : responseCode,
                successful ? null : responseMessage(responseCode));
    }

    String canonical(Map<String, String> payload) {
        return new TreeMap<>(payload).entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("vnp_"))
                .filter(entry -> !"vnp_SecureHash".equals(entry.getKey()))
                .filter(entry -> !"vnp_SecureHashType".equals(entry.getKey()))
                .filter(entry -> entry.getValue() != null && !entry.getValue().isBlank())
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
    }

    String hmacSha512(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            byte[] digest = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) result.append(String.format("%02x", value));
            return result.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Khong the ky du lieu VNPAY", ex);
        }
    }

    private Map<String, String> onlyVnpayFields(Map<String, String> payload) {
        Map<String, String> values = new LinkedHashMap<>();
        if (payload != null) {
            payload.forEach((key, value) -> {
                if (key != null && key.startsWith("vnp_")) {
                    values.put(key, value == null ? "" : value);
                }
            });
        }
        return values;
    }

    private Long parseAmount(String value) {
        try {
            long raw = Long.parseLong(value);
            return raw > 0 && raw % 100 == 0 ? raw / 100 : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private String normalizeIp(String value) {
        String ip = trimToNull(value);
        if (ip == null || "0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip)) return "127.0.0.1";
        int separator = ip.indexOf(',');
        if (separator >= 0) ip = ip.substring(0, separator).trim();
        return ip.length() <= 45 ? ip : ip.substring(0, 45);
    }

    private String asciiReference(String value) {
        if (value == null) return "SSE";
        String normalized = value.replaceAll("[^A-Za-z0-9]", "");
        return normalized.isBlank() ? "SSE" : normalized;
    }

    private String configuredIpnUrl() {
        String configured = trimToNull(properties.getVnpay().getIpnUrl());
        if (configured != null) return configured;
        String base = properties.getPublicBaseUrl();
        return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base)
                + "/payments/vnpay/ipn";
    }

    private String appendQuery(String url, String query) {
        return url + (url.contains("?") ? "&" : "?") + query;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.US_ASCII);
    }

    private boolean secureEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII),
                actual.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }

    private GatewayVerification invalid(boolean signatureValid, String txnRef, Long amount,
                                        String providerTransactionId, String responseCode,
                                        String errorCode, String errorMessage) {
        return new GatewayVerification(signatureValid, false, false, txnRef, amount,
                providerTransactionId, responseCode, errorCode, errorMessage);
    }

    private String responseMessage(String code) {
        return switch (code) {
            case "07" -> "Giao dich bi nghi ngo gian lan";
            case "09" -> "Tai khoan chua dang ky Internet Banking";
            case "10" -> "Xac thuc thong tin khong dung qua so lan cho phep";
            case "11" -> "Da het han cho thanh toan";
            case "24" -> "Khach hang da huy giao dich";
            case "51" -> "Tai khoan khong du so du";
            case "75" -> "Ngan hang dang bao tri";
            default -> "VNPAY tu choi giao dich (ma " + code + ")";
        };
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void assertConfigured() {
        PaymentProperties.Vnpay config = properties.getVnpay();
        if (!config.isEnabled() || config.getTmnCode() == null || config.getTmnCode().isBlank()
                || config.getHashSecret() == null || config.getHashSecret().isBlank()
                || config.getPaymentUrl() == null || config.getPaymentUrl().isBlank()
                || config.getReturnUrl() == null || config.getReturnUrl().isBlank()) {
            throw ApiException.badRequest(
                    "VNPAY chua duoc cau hinh. Can SSE_VNPAY_TMN_CODE, SSE_VNPAY_HASH_SECRET va Return URL");
        }
        if (config.getExpireMinutes() < 5 || config.getExpireMinutes() > 60) {
            throw ApiException.badRequest("Thoi gian het han VNPAY phai tu 5 den 60 phut");
        }
    }
}
