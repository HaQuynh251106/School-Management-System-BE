package com.sse.app.finance;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentConfigurationValidatorTest {
    @Test
    void productionRejectsSandbox() {
        var validator = new PaymentConfigurationValidator(
                "production", "disabled", true, "", "", "");

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SANDBOX_ENABLED");
    }

    @Test
    void productionRejectsIncompleteVietQrBeneficiary() {
        var validator = new PaymentConfigurationValidator(
                "production", "vietqr", false,
                "970407", "replace-with-beneficiary-account-number", "replace-with-beneficiary-name");

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ACCOUNT_NO");
    }

    @Test
    void productionAcceptsDisabledOrCompleteVietQrAndDemoKeepsSandbox() {
        assertThatCode(() -> new PaymentConfigurationValidator(
                "production", "disabled", false, "", "", "").validate()).doesNotThrowAnyException();
        assertThatCode(() -> new PaymentConfigurationValidator(
                "production", "vietqr", false,
                "970407", "0123456789", "TRUONG HOC SO").validate()).doesNotThrowAnyException();
        assertThatCode(() -> new PaymentConfigurationValidator(
                "demo", "vietqr", true, "MB", "0123456789", "TRUONG HOC SO DEMO")
                .validate()).doesNotThrowAnyException();
    }
}
