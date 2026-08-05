package com.sse.app.finance;

import com.sse.app.identity.User;
import com.sse.app.identity.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentHistoryServiceTest {
    @Mock private PaymentRepository payments;
    @Mock private InvoiceRepository invoices;
    @Mock private FeePeriodRepository feePeriods;
    @Mock private PaymentGatewayTransactionRepository gatewayTransactions;
    @Mock private PaymentReceiptRepository receipts;
    @Mock private PaymentRefundRepository refunds;
    @Mock private UserService users;

    @Test
    void returnsNewestBusinessEventFirstWithGatewayAndReceiptDetails() {
        PaymentHistoryService service = new PaymentHistoryService(payments, invoices, feePeriods,
                gatewayTransactions, receipts, refunds, users);
        Invoice firstInvoice = invoice("invoice-1", "student-1", "Student One");
        Invoice secondInvoice = invoice("invoice-2", "student-2", "Student Two");
        Payment createdLaterButPaidEarlier = Payment.builder()
                .id("payment-1").invoiceId("invoice-1").amount(500_000).method("VNPAY")
                .status("SUCCESS").txnRef("TX-1")
                .createdAt(Instant.parse("2026-07-21T05:00:00Z"))
                .paidAt(Instant.parse("2026-07-21T05:10:00Z")).build();
        Payment createdEarlierButPaidLater = Payment.builder()
                .id("payment-2").invoiceId("invoice-2").amount(700_000).method("MOMO")
                .status("SUCCESS").txnRef("TX-2")
                .createdAt(Instant.parse("2026-07-21T04:00:00Z"))
                .paidAt(Instant.parse("2026-07-21T06:00:00Z")).build();

        when(invoices.findAll()).thenReturn(List.of(firstInvoice, secondInvoice));
        when(payments.findAllByOrderByCreatedAtDesc())
                .thenReturn(List.of(createdLaterButPaidEarlier, createdEarlierButPaidLater));
        when(feePeriods.findAll()).thenReturn(List.of(FeePeriod.builder()
                .id("period-1").code("HK1-2026").name("Semester 1").build()));
        when(users.getById("student-1")).thenReturn(User.builder().id("student-1").studentCode("HS001").build());
        when(users.getById("student-2")).thenReturn(User.builder().id("student-2").studentCode("HS002").build());
        when(receipts.findByPaymentIdIn(anyCollection())).thenReturn(List.of(PaymentReceipt.builder()
                .id("receipt-2").paymentId("payment-2").receiptNumber("SSE-REC-2")
                .status("ISSUED").issuedAt(Instant.parse("2026-07-21T06:00:00Z")).build()));
        when(gatewayTransactions.findByPaymentIdIn(anyCollection())).thenReturn(List.of(
                PaymentGatewayTransaction.builder().id("gateway-2").paymentId("payment-2")
                        .provider("MOMO").merchantTxnRef("TX-2").providerTransactionId("MOMO-001")
                        .callbackCount(3).processed(true).createdAt(Instant.parse("2026-07-21T06:00:00Z")).build()));

        var result = service.list(null, null, null, null);

        assertEquals(List.of("payment-2", "payment-1"), result.stream().map(row -> row.paymentId()).toList());
        assertEquals("MOMO-001", result.get(0).providerTransactionId());
        assertEquals(3, result.get(0).callbackCount());
        assertEquals("SSE-REC-2", result.get(0).receiptNumber());
        assertEquals("HS002", result.get(0).studentCode());
        assertNull(result.get(1).receiptNumber());
    }

    private Invoice invoice(String id, String studentId, String studentName) {
        return Invoice.builder().id(id).code("INV-" + id).studentId(studentId).studentName(studentName)
                .parentId("parent-1").feePeriodId("period-1").totalAmount(1_000_000).status("PAID").build();
    }
}
