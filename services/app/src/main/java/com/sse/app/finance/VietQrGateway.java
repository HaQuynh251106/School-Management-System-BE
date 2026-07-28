package com.sse.app.finance;

import com.sse.app.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Tạo Quick Link VietQR theo đặc tả công khai của VietQR.
 * Mã QR chỉ khởi tạo lệnh chuyển khoản; giao dịch vẫn phải được đối soát
 * trước khi hóa đơn được ghi nhận đã thanh toán.
 */
@Component
public class VietQrGateway {
    private final String bankId;
    private final String accountNo;
    private final String accountName;
    private final String template;

    public VietQrGateway(
            @Value("${sse.payments.vietqr.bank-id:}") String bankId,
            @Value("${sse.payments.vietqr.account-no:}") String accountNo,
            @Value("${sse.payments.vietqr.account-name:}") String accountName,
            @Value("${sse.payments.vietqr.template:compact2}") String template) {
        this.bankId = bankId == null ? "" : bankId.trim();
        this.accountNo = accountNo == null ? "" : accountNo.replaceAll("\\s+", "");
        this.accountName = accountName == null ? "" : accountName.trim();
        this.template = template == null || template.isBlank() ? "compact2" : template.trim();
    }

    public VietQrPayment create(String transactionRef, long amount) {
        requireConfigured();
        if (amount <= 0) throw ApiException.badRequest("Số tiền VietQR phải lớn hơn 0");

        String transferContent = normalizeTransferContent(transactionRef);
        String imageUrl = "https://img.vietqr.io/image/"
                + path(bankId) + "-" + path(accountNo) + "-" + path(template) + ".png"
                + "?amount=" + amount
                + "&addInfo=" + query(transferContent)
                + "&accountName=" + query(accountName);
        return new VietQrPayment(imageUrl, bankId, accountNo, accountName, transferContent);
    }

    private void requireConfigured() {
        if (bankId.isBlank() || accountNo.isBlank() || accountName.isBlank()) {
            throw ApiException.serviceUnavailable(
                    "VietQR chưa có tài khoản thụ hưởng. Cần cấu hình mã ngân hàng, số tài khoản và tên tài khoản.");
        }
        if (!accountNo.matches("[0-9]{6,19}")) {
            throw ApiException.serviceUnavailable("Số tài khoản VietQR phải gồm 6 đến 19 chữ số");
        }
    }

    private String normalizeTransferContent(String value) {
        String normalized = value == null ? "" : value.toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]", "");
        if (normalized.isBlank()) normalized = "THANHTOAN";
        return normalized.substring(0, Math.min(25, normalized.length()));
    }

    private String path(String value) {
        return query(value).replace("+", "%20");
    }

    private String query(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public record VietQrPayment(
            String qrImageUrl,
            String bankId,
            String accountNo,
            String accountName,
            String transferContent) {}
}
