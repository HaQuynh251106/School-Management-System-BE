package com.sse.app.finance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BankStatementImportServiceTest {
    @Mock private BankStatementEntryRepository entries;
    @Mock private InvoiceRepository invoices;
    @Mock private PaymentRepository payments;

    private BankStatementImportService service;

    @BeforeEach
    void setUp() {
        service = new BankStatementImportService(entries, invoices, payments);
    }

    @Test
    void matchesInvoiceAndPendingPaymentByContentAndAmount() {
        when(entries.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        Invoice invoice = Invoice.builder().id("invoice-1").code("INV-001")
                .totalAmount(1_000_000).status("PENDING").build();
        Payment payment = Payment.builder().id("payment-1").invoiceId("invoice-1")
                .amount(1_000_000).status("PENDING").build();
        when(invoices.findAll()).thenReturn(List.of(invoice));
        when(payments.findByInvoiceId("invoice-1")).thenReturn(List.of(payment));

        var response = service.importFile(csv(
                "TXN001,1000000,2026-07-29T01:00:00Z,Thanh toan INV-001"),
                "admin");

        assertEquals(1, response.matched());
        assertEquals("MATCHED", response.entries().get(0).status());
        assertEquals("payment-1", response.entries().get(0).matchedPaymentId());
    }

    @Test
    void repeatedTransactionReferenceIsIdempotent() {
        BankStatementEntry existing = BankStatementEntry.builder()
                .id("entry-1").bankCode("MB")
                .transactionReference("TXN001").amount(1_000_000)
                .status("MATCHED").build();
        when(entries.findByBankCodeAndTransactionReference("MB", "TXN001"))
                .thenReturn(Optional.of(existing));
        when(invoices.findAll()).thenReturn(List.of());

        var response = service.importFile(csv(
                "TXN001,1000000,2026-07-29T01:00:00Z,Thanh toan INV-001"),
                "admin");

        assertEquals(1, response.duplicates());
        assertEquals(0, response.matched());
    }

    private MockMultipartFile csv(String row) {
        String content = "transactionReference,amount,transferredAt,content\n" + row;
        return new MockMultipartFile("file", "mb.csv", "text/csv",
                content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
