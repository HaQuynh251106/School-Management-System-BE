package com.sse.app.finance;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/** Dừng ứng dụng production sớm nếu cấu hình thanh toán có thể gây mất an toàn. */
@Component
class PaymentConfigurationValidator {
    private final String environment;
    private final String mode;
    private final boolean sandboxEnabled;
    private final String bankId;
    private final String accountNo;
    private final String accountName;

    PaymentConfigurationValidator(
            @Value("${sse.environment:local}") String environment,
            @Value("${sse.payments.mode:disabled}") String mode,
            @Value("${sse.payments.sandbox.enabled:false}") boolean sandboxEnabled,
            @Value("${sse.payments.vietqr.bank-id:}") String bankId,
            @Value("${sse.payments.vietqr.account-no:}") String accountNo,
            @Value("${sse.payments.vietqr.account-name:}") String accountName) {
        this.environment = normalized(environment);
        this.mode = normalized(mode);
        this.sandboxEnabled = sandboxEnabled;
        this.bankId = clean(bankId);
        this.accountNo = clean(accountNo).replaceAll("\\s+", "");
        this.accountName = clean(accountName);
    }

    @PostConstruct
    void validate() {
        if (!"production".equals(environment)) return;
        if (!Set.of("disabled", "vietqr").contains(mode)) {
            throw invalid("SSE_PAYMENT_MODE chỉ được là disabled hoặc vietqr trong production");
        }
        if (sandboxEnabled) {
            throw invalid("Không được bật SSE_PAYMENT_SANDBOX_ENABLED trong production");
        }
        if ("vietqr".equals(mode)) {
            if (bankId.isBlank() || placeholder(bankId)) {
                throw invalid("Thiếu SSE_VIETQR_BANK_ID production");
            }
            if (!accountNo.matches("[0-9]{6,19}") || placeholder(accountNo)) {
                throw invalid("SSE_VIETQR_ACCOUNT_NO phải gồm 6 đến 19 chữ số");
            }
            if (accountName.isBlank() || placeholder(accountName)) {
                throw invalid("Thiếu SSE_VIETQR_ACCOUNT_NAME production");
            }
        }
    }

    private boolean placeholder(String value) {
        String lowered = value.toLowerCase(Locale.ROOT);
        return lowered.contains("replace-with") || lowered.contains("change-me")
                || lowered.contains("example");
    }

    private IllegalStateException invalid(String message) {
        return new IllegalStateException("Cấu hình thanh toán không an toàn: " + message);
    }

    private static String normalized(String value) {
        return clean(value).toLowerCase(Locale.ROOT);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
