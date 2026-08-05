package com.sse.app.finance;

import com.sse.app.identity.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentReconciliationServiceTest {
    @Mock private PaymentReconciliationRunRepository runs;
    @Mock private PaymentReconciliationIssueRepository issues;
    @Mock private PaymentReconciliationMethodSummaryRepository methodSummaries;
    @Mock private PaymentRepository payments;
    @Mock private PaymentRefundRepository refunds;
    @Mock private InvoiceRepository invoices;
    @Mock private PaymentReceiptRepository receipts;
    @Mock private PaymentGatewayTransactionRepository gatewayTransactions;
    @Mock private PaymentProofRepository paymentProofs;
    @Mock private UserService users;

    private PaymentReconciliationService service;
    private final LocalDate date = LocalDate.of(2026, 7, 20);

    @BeforeEach
    void setUp() {
        service = new PaymentReconciliationService(runs, issues, methodSummaries, payments, refunds, invoices,
                receipts, gatewayTransactions, paymentProofs, users);
    }

    @Test
    void balancedRunCalculatesGrossRefundAndNet() {
        Fixture fixture = fixture(800_000);
        stub(fixture);

        var result = service.run(date, "admin-1");

        assertEquals("BALANCED", result.status());
        assertEquals(1, result.paymentCount());
        assertEquals(1_000_000, result.grossAmount());
        assertEquals(200_000, result.refundAmount());
        assertEquals(800_000, result.netAmount());
        assertEquals(0, result.discrepancyCount());
        assertEquals(1, result.methodSummaries().size());
        assertEquals("MB_BANK_TRANSFER", result.methodSummaries().get(0).method());
    }

    @Test
    void runDetectsInvoiceAmountAndStatusMismatch() {
        Fixture fixture = fixture(900_000);
        fixture.invoice.setStatus("PAID");
        stub(fixture);

        var result = service.run(date, "admin-1");

        assertEquals("DISCREPANCY", result.status());
        assertEquals(2, result.discrepancyCount());
        assertEquals(List.of("INVOICE_PAID_AMOUNT_MISMATCH", "INVOICE_STATUS_MISMATCH"),
                result.issues().stream().map(FinanceDtos.ReconciliationIssueResponse::issueType).sorted().toList());
    }

    @Test
    void gatewayPaymentWithoutVerifiedIpnIsDiscrepancy() {
        Fixture fixture = fixture(800_000);
        fixture.payment.setMethod("VNPAY");
        stub(fixture);

        var result = service.run(date, "admin-1");

        assertEquals("DISCREPANCY", result.status());
        assertEquals(List.of("GATEWAY_TRANSACTION_MISSING"),
                result.issues().stream().map(FinanceDtos.ReconciliationIssueResponse::issueType).toList());
    }

    @Test
    void gatewayPaymentWithVerifiedProcessedIpnIsBalanced() {
        Fixture fixture = fixture(800_000);
        fixture.payment.setMethod("VNPAY");
        stub(fixture);
        PaymentGatewayTransaction transaction = PaymentGatewayTransaction.builder()
                .id("gateway-1")
                .paymentId(fixture.payment.getId())
                .provider("VNPAY")
                .merchantTxnRef("txn-1")
                .providerTransactionId("provider-txn-1")
                .signatureValid(true)
                .processed(true)
                .build();
        when(gatewayTransactions.findByPaymentIdIn(anyCollection())).thenReturn(List.of(transaction));

        var result = service.run(date, "admin-1");

        assertEquals("BALANCED", result.status());
        assertEquals(0, result.discrepancyCount());
    }

    @Test
    void rejectsRangesLongerThanThirtyOneDays() {
        var request = new FinanceDtos.ReconciliationRequest(
                null, date.minusDays(31), date, null, null, null);

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> service.run(request, "admin-1"));
    }

    private void stub(Fixture fixture) {
        when(runs.findByScopeKeyForUpdate(date + "|" + date + "|ALL|*|*"))
                .thenReturn(Optional.empty());
        when(payments.findByPaidAtGreaterThanEqualAndPaidAtLessThan(any(), any()))
                .thenReturn(List.of(fixture.payment));
        when(refunds.findByStatusAndCompletedAtGreaterThanEqualAndCompletedAtLessThanOrderByCompletedAtAsc(
                eq("COMPLETED"), any(), any())).thenReturn(List.of(fixture.refund));
        when(payments.findAllById(anyCollection())).thenReturn(List.of(fixture.payment));
        when(receipts.findByPaymentIdIn(anyCollection())).thenReturn(List.of(fixture.receipt));
        org.mockito.Mockito.lenient().when(paymentProofs.findByPaymentIdIn(anyCollection()))
                .thenReturn(List.of(fixture.proof));
        org.mockito.Mockito.lenient().when(gatewayTransactions.findByPaymentIdIn(anyCollection()))
                .thenReturn(List.of());
        when(invoices.findAllById(anyCollection())).thenReturn(List.of(fixture.invoice));
        when(payments.findByInvoiceIdInOrderByCreatedAtDesc(anyCollection())).thenReturn(List.of(fixture.payment));
        when(refunds.findByPaymentIdIn(anyCollection())).thenReturn(List.of(fixture.refund));
        when(runs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(methodSummaries.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        org.mockito.Mockito.lenient().when(issues.saveAll(anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(users.fullNameOf("admin-1")).thenReturn("School Administrator");
    }

    private Fixture fixture(long invoicePaidAmount) {
        Instant paidAt = Instant.parse("2026-07-20T03:00:00Z");
        Payment payment = Payment.builder().id("payment-1").invoiceId("invoice-1")
                .amount(1_000_000).status("SUCCESS").method("MB_BANK_TRANSFER").paidAt(paidAt).build();
        PaymentRefund refund = PaymentRefund.builder().id("refund-1").paymentId(payment.getId())
                .invoiceId("invoice-1").studentId("student-1").amount(200_000).status("COMPLETED")
                .completedAt(Instant.parse("2026-07-20T04:00:00Z")).build();
        Invoice invoice = Invoice.builder().id("invoice-1").totalAmount(1_000_000)
                .paidAmount(invoicePaidAmount).status("PARTIAL").dueDate(LocalDate.now().plusDays(30)).build();
        PaymentReceipt receipt = PaymentReceipt.builder().id("receipt-1").paymentId(payment.getId())
                .status("ISSUED").fileId("file-1").build();
        PaymentProof proof = PaymentProof.builder().id("proof-1").paymentId(payment.getId())
                .status("APPROVED").build();
        return new Fixture(payment, refund, invoice, receipt, proof);
    }

    private record Fixture(Payment payment, PaymentRefund refund, Invoice invoice,
                           PaymentReceipt receipt, PaymentProof proof) {}
}
