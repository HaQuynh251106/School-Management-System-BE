package com.sse.app.finance;

import com.sse.app.common.ApiException;
import com.sse.app.event.DomainEventPublisher;
import com.sse.app.file.FileStorageService;
import com.sse.app.file.StoredFile;
import com.sse.app.finance.FinanceDtos.PaymentInitResponse;
import com.sse.app.identity.User;
import com.sse.app.identity.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentProofServiceTest {
    @Mock private PaymentProofRepository proofs;
    @Mock private PaymentRepository payments;
    @Mock private InvoiceRepository invoices;
    @Mock private FileStorageService storage;
    @Mock private PaymentService paymentService;
    @Mock private UserService users;
    @Mock private DomainEventPublisher events;

    private PaymentProofService service;

    @BeforeEach
    void setUp() {
        service = new PaymentProofService(proofs, payments, invoices, storage,
                paymentService, users, events);
    }

    @Test
    void parentSubmitsReadyOwnedReceiptAndPaymentStaysPending() {
        Payment payment = payment();
        Invoice invoice = invoice();
        when(payments.findByIdForUpdate("payment-1")).thenReturn(Optional.of(payment));
        when(invoices.findById("invoice-1")).thenReturn(Optional.of(invoice));
        when(storage.requireReadyOwnedFile("file-1", "PAYMENT_PROOF", "parent-1"))
                .thenReturn(file());
        when(users.getById("student-1")).thenReturn(User.builder().studentCode("HS1001").build());
        when(users.userIdsByRole("ADMIN")).thenReturn(List.of("admin-1"));
        when(proofs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        payment.setAutoProvisioned(true);
        var result = service.submit("payment-1", "file-1", "parent-1", "PARENT");

        assertEquals("SUBMITTED", result.status());
        assertEquals("HS1001", result.studentCode());
        assertEquals("PENDING", payment.getStatus());
        assertFalse(payment.isAutoProvisioned());
        assertTrue(payment.getNote().contains("chờ Admin"));
        verify(events).publish(eq("finance.payment.proof_submitted"), eq("admin-1"),
                eq("payment_proof"), eq(result.id()), anyMap());
    }

    @Test
    void adminCannotSubmitReceiptOnBehalfOfParent() {
        when(payments.findByIdForUpdate("payment-1")).thenReturn(Optional.of(payment()));
        when(invoices.findById("invoice-1")).thenReturn(Optional.of(invoice()));

        ApiException error = assertThrows(ApiException.class,
                () -> service.submit("payment-1", "file-1", "admin-1", "ADMIN"));

        assertEquals(org.springframework.http.HttpStatus.FORBIDDEN, error.getStatus());
        verifyNoInteractions(storage);
    }

    @Test
    void sameStoredReceiptCannotBeSubmittedTwice() {
        when(payments.findByIdForUpdate("payment-1")).thenReturn(Optional.of(payment()));
        when(invoices.findById("invoice-1")).thenReturn(Optional.of(invoice()));
        when(storage.requireReadyOwnedFile("file-1", "PAYMENT_PROOF", "parent-1"))
                .thenReturn(file());
        when(proofs.existsByFileId("file-1")).thenReturn(true);

        ApiException error = assertThrows(ApiException.class,
                () -> service.submit("payment-1", "file-1", "parent-1", "PARENT"));

        assertEquals(org.springframework.http.HttpStatus.CONFLICT, error.getStatus());
        verify(proofs, never()).save(any());
    }

    @Test
    void approvingReceiptUsesLockedPaymentSettlement() {
        PaymentProof proof = proof();
        Payment payment = payment();
        Invoice invoice = invoice();
        payment.setStatus("SUCCESS");
        invoice.setPaidAmount(invoice.getTotalAmount());
        invoice.setStatus("PAID");
        when(proofs.findByIdForUpdate("proof-1")).thenReturn(Optional.of(proof));
        when(paymentService.confirmBankTransfer("payment-1", "admin-1"))
                .thenReturn(new PaymentInitResponse(payment, invoice, "SUCCESS", null, null, null));
        when(proofs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.approve("proof-1", "admin-1");

        assertEquals("APPROVED", result.proof().status());
        assertEquals("SUCCESS", result.payment().getStatus());
        assertEquals("PAID", result.invoice().getStatus());
        verify(paymentService, times(1)).confirmBankTransfer("payment-1", "admin-1");
    }

    @Test
    void requestingRepaymentRequiresReasonAndDoesNotSettlePayment() {
        PaymentProof proof = proof();
        Payment payment = payment();
        when(proofs.findByIdForUpdate("proof-1")).thenReturn(Optional.of(proof));
        when(payments.findByIdForUpdate("payment-1")).thenReturn(Optional.of(payment));
        when(invoices.findById("invoice-1")).thenReturn(Optional.of(invoice()));
        when(proofs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThrows(ApiException.class, () -> service.requestRepayment("proof-1", "admin-1", " "));
        var result = service.requestRepayment("proof-1", "admin-1", "Sai số tiền");

        assertEquals("RETRY_REQUIRED", result.proof().status());
        assertEquals("PENDING", result.payment().getStatus());
        assertEquals("Sai số tiền", result.proof().reviewReason());
        verifyNoInteractions(paymentService);
    }

    private Payment payment() {
        return Payment.builder().id("payment-1").invoiceId("invoice-1").amount(500_000)
                .method("MB_BANK_TRANSFER").status("PENDING").txnRef("MB-tx-1").build();
    }

    private Invoice invoice() {
        return Invoice.builder().id("invoice-1").code("INV-1").studentId("student-1")
                .studentName("Nguyen Van An").parentId("parent-1").totalAmount(500_000)
                .paidAmount(0).status("PENDING").build();
    }

    private StoredFile file() {
        return StoredFile.builder().id("file-1").scope("PAYMENT_PROOF").originalName("receipt.png")
                .contentType("image/png").sizeBytes(10_000).uploadedBy("parent-1")
                .status("READY").build();
    }

    private PaymentProof proof() {
        return PaymentProof.builder().id("proof-1").paymentId("payment-1").invoiceId("invoice-1")
                .invoiceCode("INV-1").parentId("parent-1").studentId("student-1")
                .studentCode("HS1001").studentName("Nguyen Van An").amount(500_000)
                .fileId("file-1").fileName("receipt.png").contentType("image/png")
                .sizeBytes(10_000).status("SUBMITTED").submittedBy("parent-1")
                .submittedAt(Instant.now()).build();
    }
}
