package com.sse.app.finance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VietQrGatewayTest {
    @Test
    void createsOfficialQuickLinkWithLockedAmountAndTransferContent() {
        VietQrGateway gateway = new VietQrGateway(
                "MB", "0123456789", "TRUONG HOC SO", "compact2");

        VietQrGateway.VietQrPayment payment =
                gateway.create("VQR-ABC-123", 1_250_000);

        assertTrue(payment.qrImageUrl().startsWith(
                "https://img.vietqr.io/image/MB-0123456789-compact2.png"));
        assertTrue(payment.qrImageUrl().contains("amount=1250000"));
        assertTrue(payment.qrImageUrl().contains("addInfo=VQRABC123"));
        assertEquals("VQRABC123", payment.transferContent());
        assertEquals("TRUONG HOC SO", payment.accountName());
    }
}
