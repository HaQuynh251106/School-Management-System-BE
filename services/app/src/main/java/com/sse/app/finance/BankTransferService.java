package com.sse.app.finance;

import com.sse.app.common.ApiException;
import com.sse.app.finance.FinanceDtos.BankTransferInstructions;
import com.sse.app.identity.User;
import com.sse.app.identity.UserService;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;

@Service
public class BankTransferService {
    private static final int MAX_TRANSFER_CONTENT_LENGTH = 50;

    private final PaymentProperties properties;
    private final UserService users;

    public BankTransferService(PaymentProperties properties, UserService users) {
        this.properties = properties;
        this.users = users;
    }

    public boolean enabled() {
        return properties.getBankTransfer().isEnabled();
    }

    public BankTransferInstructions instructions(Invoice invoice, Payment payment) {
        PaymentProperties.BankTransfer config = properties.getBankTransfer();
        if (!config.isEnabled()) {
            throw ApiException.badRequest("Chuyển khoản MB chưa được bật");
        }
        if (isBlank(config.getAccountNumber()) || isBlank(config.getAccountName())) {
            throw ApiException.badRequest("Chưa cấu hình tài khoản nhận chuyển khoản MB");
        }

        User student = users.getById(invoice.getStudentId());
        String studentCode = cleanPart(student.getStudentCode());
        String studentName = cleanPart(invoice.getStudentName());
        if (studentCode.isBlank() || studentName.isBlank()) {
            throw ApiException.badRequest("Học sinh phải có mã học sinh và họ tên trước khi thanh toán");
        }

        String transferContent = transferContent(config.getTransferPrefix(), studentCode,
                studentName, invoice.getCode());
        String imageName = cleanPathPart(config.getBankId()) + "-" + config.getAccountNumber().trim()
                + "-compact2.png";
        String qrImageUrl = UriComponentsBuilder.fromUriString(config.getQrBaseUrl())
                .pathSegment(imageName)
                .queryParam("amount", payment.getAmount())
                .queryParam("addInfo", transferContent)
                .queryParam("accountName", config.getAccountName().trim())
                .build()
                .encode(StandardCharsets.UTF_8)
                .toUriString();

        return new BankTransferInstructions(
                config.getBankId().trim(),
                config.getBankName().trim(),
                config.getAccountNumber().trim(),
                config.getAccountName().trim(),
                payment.getAmount(),
                transferContent,
                qrImageUrl,
                studentCode,
                invoice.getStudentName(),
                invoice.getCode());
    }

    String transferContent(String prefixValue, String studentCode, String studentName, String invoiceCode) {
        String prefix = cleanPart(prefixValue);
        String required = String.join(" ", prefix, studentCode, studentName).trim().replaceAll("\\s+", " ");
        if (required.length() > MAX_TRANSFER_CONTENT_LENGTH) {
            throw ApiException.badRequest("Mã học sinh và tên học sinh vượt giới hạn nội dung chuyển khoản");
        }
        String withInvoice = (required + " " + cleanPart(invoiceCode)).trim();
        return withInvoice.length() <= MAX_TRANSFER_CONTENT_LENGTH ? withInvoice : required;
    }

    private String cleanPart(String value) {
        if (value == null) return "";
        String ascii = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('Đ', 'D')
                .replace('đ', 'd');
        return ascii.toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9 ]", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private String cleanPathPart(String value) {
        String cleaned = cleanPart(value).replace(" ", "");
        if (cleaned.isBlank()) throw ApiException.badRequest("Mã ngân hàng VietQR không hợp lệ");
        return URLEncoder.encode(cleaned, StandardCharsets.UTF_8);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
