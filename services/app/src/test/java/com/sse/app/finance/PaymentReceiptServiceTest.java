package com.sse.app.finance;

import com.sse.app.common.ApiException;
import com.sse.app.file.FileStorageService;
import com.sse.app.file.StoredFile;
import com.sse.app.identity.User;
import com.sse.app.identity.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentReceiptServiceTest {
    @Mock private PaymentReceiptRepository receipts;
    @Mock private PaymentRepository payments;
    @Mock private InvoiceRepository invoices;
    @Mock private FeePeriodRepository feePeriods;
    @Mock private UserService users;
    @Mock private FileStorageService storage;
    @Mock private PaymentReceiptPdfRenderer renderer;

    private PaymentReceiptService service;

    @BeforeEach
    void setUp() {
        service = new PaymentReceiptService(receipts, payments, invoices, feePeriods, users, storage, renderer);
    }

    @Test
    void issuesOnePdfForSuccessfulPayment() {
        Payment payment = payment("SUCCESS");
        Invoice invoice = invoice();
        when(receipts.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(receipts.findByPaymentId("payment-1")).thenReturn(Optional.empty());
        when(users.getById("student-1")).thenReturn(User.builder().id("student-1").studentCode("HS2601001").build());
        when(feePeriods.findById("period-1")).thenReturn(Optional.of(FeePeriod.builder()
                .id("period-1").code("HK1-2026").name("Học kỳ 1").build()));
        when(renderer.render(any())).thenReturn("%PDF-test".getBytes());
        when(storage.storeGeneratedReceipt(anyString(), any(), anyString())).thenReturn(StoredFile.builder()
                .id("file-receipt-1").status("READY").build());

        PaymentReceipt result = service.issueAfterSettlement(payment, invoice, "admin-1");

        assertEquals("ISSUED", result.getStatus());
        assertEquals("file-receipt-1", result.getFileId());
        assertEquals(1, result.getGenerationAttempts());
        assertNotNull(result.getReceiptNumber());
        verify(storage).storeGeneratedReceipt(result.getReceiptNumber() + ".pdf", "%PDF-test".getBytes(), "admin-1");
    }

    @Test
    void repeatedIssueReturnsExistingReceiptWithoutNewFile() {
        PaymentReceipt existing = PaymentReceipt.builder()
                .id("receipt-1").paymentId("payment-1").receiptNumber("SSE-REC-1")
                .status("ISSUED").fileId("file-1").build();
        when(receipts.findByPaymentId("payment-1")).thenReturn(Optional.of(existing));

        PaymentReceipt result = service.issueAfterSettlement(payment("SUCCESS"), invoice(), "admin-1");

        assertEquals("receipt-1", result.getId());
        verify(renderer, never()).render(any());
        verify(storage, never()).storeGeneratedReceipt(anyString(), any(), anyString());
    }

    @Test
    void storageFailureIsRecordedWithoutThrowing() {
        when(receipts.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(receipts.findByPaymentId("payment-1")).thenReturn(Optional.empty());
        when(users.getById("student-1")).thenReturn(User.builder().id("student-1").studentCode("HS2601001").build());
        when(feePeriods.findById("period-1")).thenReturn(Optional.empty());
        when(renderer.render(any())).thenReturn("%PDF-test".getBytes());
        when(storage.storeGeneratedReceipt(anyString(), any(), anyString()))
                .thenThrow(new IllegalStateException("MinIO unavailable"));

        PaymentReceipt result = service.issueAfterSettlement(payment("SUCCESS"), invoice(), "SYSTEM:VNPAY");

        assertEquals("FAILED", result.getStatus());
        assertEquals("MinIO unavailable", result.getGenerationError());
        assertEquals(1, result.getGenerationAttempts());
    }

    @Test
    void adminCannotIssueReceiptForPendingPayment() {
        when(payments.findByIdForUpdate("payment-1")).thenReturn(Optional.of(payment("PENDING")));

        ApiException error = assertThrows(ApiException.class,
                () -> service.issueForPayment("payment-1", "admin-1"));

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        verify(invoices, never()).findById(anyString());
    }

    @Test
    void voidRequiresReasonAndKeepsPreviousFileAsEvidence() {
        PaymentReceipt receipt = PaymentReceipt.builder()
                .id("receipt-1").paymentId("payment-1").receiptNumber("SSE-REC-1")
                .status("ISSUED").fileId("file-original").revision(1).build();
        when(receipts.findByPaymentId("payment-1")).thenReturn(Optional.of(receipt));
        when(receipts.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ApiException missingReason = assertThrows(ApiException.class,
                () -> service.voidForPayment("payment-1", " ", "admin-1"));
        assertEquals(HttpStatus.BAD_REQUEST, missingReason.getStatus());

        PaymentReceipt result = service.voidForPayment(
                "payment-1", "Sai tên người nộp", "admin-1");

        assertEquals("VOID", result.getStatus());
        assertEquals("file-original", result.getPreviousFileId());
        assertEquals("admin-1", result.getVoidedBy());
        assertEquals("Sai tên người nộp", result.getVoidReason());
        assertNotNull(result.getVoidedAt());
    }

    @Test
    void reissueIncrementsRevisionAndGeneratesNewPdf() {
        Payment payment = payment("SUCCESS");
        Invoice invoice = invoice();
        PaymentReceipt receipt = PaymentReceipt.builder()
                .id("receipt-1").paymentId("payment-1").invoiceId("invoice-1")
                .receiptNumber("SSE-REC-OLD").status("VOID")
                .fileId("file-original").previousFileId("file-original")
                .revision(1).voidReason("Sai thông tin").build();
        when(payments.findByIdForUpdate("payment-1")).thenReturn(Optional.of(payment));
        when(invoices.findById("invoice-1")).thenReturn(Optional.of(invoice));
        when(receipts.findByPaymentId("payment-1")).thenReturn(Optional.of(receipt));
        when(receipts.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(users.getById("student-1")).thenReturn(
                User.builder().id("student-1").studentCode("HS2601001").build());
        when(feePeriods.findById("period-1")).thenReturn(Optional.empty());
        when(renderer.render(any())).thenReturn("%PDF-reissued".getBytes());
        when(storage.storeGeneratedReceipt(anyString(), any(), anyString()))
                .thenReturn(StoredFile.builder().id("file-reissued").status("READY").build());

        PaymentReceipt result = service.reissueForPayment("payment-1", "admin-2");

        assertEquals("ISSUED", result.getStatus());
        assertEquals(2, result.getRevision());
        assertTrue(result.getReceiptNumber().endsWith("-R2"));
        assertEquals("file-reissued", result.getFileId());
        assertEquals("file-original", result.getPreviousFileId());
        assertNull(result.getVoidReason());
        assertNull(result.getVoidedAt());
    }

    private Payment payment(String status) {
        Instant paidAt = Instant.parse("2026-07-21T06:00:00Z");
        return Payment.builder().id("payment-1").invoiceId("invoice-1").amount(1_250_000)
                .method("MB_BANK_TRANSFER").status(status).txnRef("MB-TX-1")
                .note("Đã xác nhận").createdAt(paidAt.minusSeconds(60)).updatedAt(paidAt).paidAt(paidAt).build();
    }

    private Invoice invoice() {
        return Invoice.builder().id("invoice-1").code("INV-HK1-001").studentId("student-1")
                .studentName("Nguyễn Minh Khang").parentId("parent-1").feePeriodId("period-1")
                .totalAmount(1_250_000).paidAmount(1_250_000).status("PAID").build();
    }
}
