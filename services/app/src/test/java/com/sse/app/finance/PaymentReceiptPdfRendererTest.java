package com.sse.app.finance;

import org.apache.pdfbox.Loader;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentReceiptPdfRendererTest {
    private final PaymentReceiptPdfRenderer renderer = new PaymentReceiptPdfRenderer();

    @Test
    void rendersReadableSinglePagePdf() throws Exception {
        byte[] pdf = renderer.render(new PaymentReceiptPdfRenderer.ReceiptData(
                "SSE-REC-20260721-PAYMENT1", "payment-1", "INV-HK1-001",
                "HK1-2026", "Học phí học kỳ 1", "HS2601001", "Nguyễn Minh Khang",
                1_250_000, "MB_BANK_TRANSFER", "MB-TRANSACTION-001",
                Instant.parse("2026-07-21T06:00:00Z"), Instant.parse("2026-07-21T06:00:00Z"),
                "Admin đã đối chiếu và xác nhận giao dịch"));

        assertTrue(pdf.length > 10_000);
        assertEquals("%PDF-", new String(pdf, 0, 5, StandardCharsets.US_ASCII));
        try (var document = Loader.loadPDF(pdf)) {
            assertEquals(1, document.getNumberOfPages());
        }
    }
}
