package com.sse.app.finance;

import com.sse.app.common.ApiException;
import com.sse.app.event.DomainEventPublisher;
import com.sse.app.identity.User;
import com.sse.app.identity.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentRefundServiceTest {
    @Mock private PaymentRefundRepository refunds;
    @Mock private PaymentRepository payments;
    @Mock private InvoiceRepository invoices;
    @Mock private UserService users;
    @Mock private DomainEventPublisher events;

    private PaymentRefundService service;

    @BeforeEach
    void setUp() {
        service = new PaymentRefundService(refunds, payments, invoices, users, events);
        org.mockito.Mockito.lenient().when(refunds.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void partialRefundReducesInvoiceButKeepsPaymentSuccessful() {
        Payment payment = payment(1_000_000);
        Invoice invoice = invoice(1_000_000, 1_000_000);
        when(payments.findByIdForUpdate(payment.getId())).thenReturn(Optional.of(payment));
        when(invoices.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(invoices.findByIdForUpdate(invoice.getId())).thenReturn(Optional.of(invoice));
        when(refunds.sumAmountByPaymentIdAndStatusIn(payment.getId(), List.of("REQUESTED", "COMPLETED")))
                .thenReturn(0L);
        when(refunds.sumAmountByPaymentIdAndStatusIn(payment.getId(), List.of("COMPLETED")))
                .thenReturn(0L);
        when(users.getById("student-1")).thenReturn(User.builder().id("student-1").studentCode("HS001").build());

        var requested = service.request(payment.getId(), 400_000, "Hoàn khoản thu thừa", "admin-1");
        PaymentRefund entity = refundEntity(requested, invoice);
        when(refunds.findByIdForUpdate(requested.id())).thenReturn(Optional.of(entity));

        var completed = service.approve(requested.id(), "MB_BANK_TRANSFER", "MB-REF-001", "admin-2");

        assertEquals("COMPLETED", completed.status());
        assertEquals(600_000, invoice.getPaidAmount());
        assertEquals("PARTIAL", invoice.getStatus());
        assertEquals("SUCCESS", payment.getStatus());
        assertEquals("PARTIAL", completed.refundType());
        assertEquals(1_000_000, completed.paymentAmount());
        assertEquals(0, completed.refundedAmountBefore());
        assertEquals(400_000, completed.refundedAmountAfter());
        assertEquals(1_000_000, completed.invoicePaidAmountBefore());
        assertEquals(600_000, completed.invoicePaidAmountAfter());
        assertEquals("PAID", completed.invoiceStatusBefore());
        assertEquals("PARTIAL", completed.invoiceStatusAfter());
        assertEquals("admin-1", completed.requestedBy());
        assertEquals("admin-2", completed.approvedBy());
        verify(events).publish(org.mockito.ArgumentMatchers.eq("finance.payment.refunded"),
                org.mockito.ArgumentMatchers.eq("admin-2"), org.mockito.ArgumentMatchers.eq("payment_refund"),
                org.mockito.ArgumentMatchers.eq(requested.id()), anyMap());
    }

    @Test
    void fullRefundReversesPaymentAndReturnsInvoiceToPending() {
        Payment payment = payment(1_000_000);
        Invoice invoice = invoice(1_000_000, 1_000_000);
        PaymentRefund refund = PaymentRefund.builder()
                .id("refund-1").refundNumber("SSE-RF-1").paymentId(payment.getId())
                .invoiceId(invoice.getId()).studentId("student-1").studentName("Student")
                .amount(1_000_000).reason("Hủy khoản thu").status("REQUESTED")
                .requestedBy("admin-1").requestedAt(java.time.Instant.now()).build();
        when(refunds.findByIdForUpdate(refund.getId())).thenReturn(Optional.of(refund));
        when(payments.findByIdForUpdate(payment.getId())).thenReturn(Optional.of(payment));
        when(invoices.findByIdForUpdate(invoice.getId())).thenReturn(Optional.of(invoice));
        when(refunds.sumAmountByPaymentIdAndStatusIn(payment.getId(), List.of("COMPLETED"))).thenReturn(0L);

        var completed = service.approve(refund.getId(), "CASH", null, "admin-2");

        assertEquals(0, invoice.getPaidAmount());
        assertEquals("PENDING", invoice.getStatus());
        assertEquals("REVERSED", payment.getStatus());
        assertEquals("FULL", completed.refundType());
        assertEquals(1_000_000, completed.refundedAmountAfter());
        assertEquals(0, completed.invoicePaidAmountAfter());
        verify(events).publish(org.mockito.ArgumentMatchers.eq("finance.payment.refunded"),
                org.mockito.ArgumentMatchers.eq("admin-2"), org.mockito.ArgumentMatchers.eq("payment_refund"),
                org.mockito.ArgumentMatchers.eq(refund.getId()),
                argThat(payload -> payload.values().stream().noneMatch(java.util.Objects::isNull)
                        && !payload.containsKey("refundReference")));
    }

    @Test
    void requestRejectsAmountAlreadyReservedOrRefunded() {
        Payment payment = payment(1_000_000);
        when(payments.findByIdForUpdate(payment.getId())).thenReturn(Optional.of(payment));
        when(refunds.sumAmountByPaymentIdAndStatusIn(payment.getId(), List.of("COMPLETED")))
                .thenReturn(0L);
        when(refunds.sumAmountByPaymentIdAndStatusIn(payment.getId(), List.of("REQUESTED", "COMPLETED")))
                .thenReturn(800_000L);

        ApiException error = assertThrows(ApiException.class,
                () -> service.request(payment.getId(), 300_000, "Quá số tiền", "admin-1"));

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        verify(invoices, never()).findById(any());
    }

    @Test
    void approvingCompletedRefundIsIdempotent() {
        PaymentRefund completed = PaymentRefund.builder()
                .id("refund-1").refundNumber("SSE-RF-1").paymentId("payment-1")
                .invoiceId("invoice-1").studentId("student-1").amount(100_000)
                .reason("Đã hoàn").status("COMPLETED").requestedBy("admin-1")
                .requestedAt(java.time.Instant.now()).approvedBy("admin-2").build();
        when(refunds.findByIdForUpdate(completed.getId())).thenReturn(Optional.of(completed));

        var response = service.approve(completed.getId(), "CASH", null, "admin-2");

        assertEquals("COMPLETED", response.status());
        verify(payments, never()).findByIdForUpdate(any());
        verify(invoices, never()).findByIdForUpdate(any());
    }

    @Test
    void nonCashRefundRequiresReference() {
        PaymentRefund requested = requestedRefund();
        when(refunds.findByIdForUpdate(requested.getId())).thenReturn(Optional.of(requested));

        ApiException error = assertThrows(ApiException.class,
                () -> service.approve(requested.getId(), "MB_BANK_TRANSFER", " ", "admin-2"));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        verify(payments, never()).findByIdForUpdate(any());
    }

    @Test
    void completedReferenceCannotBeReused() {
        PaymentRefund requested = requestedRefund();
        when(refunds.findByIdForUpdate(requested.getId())).thenReturn(Optional.of(requested));
        when(refunds.existsByRefundMethodAndRefundReferenceIgnoreCaseAndStatus(
                "MB_BANK_TRANSFER", "MB-REF-DUPLICATE", "COMPLETED")).thenReturn(true);

        ApiException error = assertThrows(ApiException.class,
                () -> service.approve(requested.getId(), "MB_BANK_TRANSFER", "MB-REF-DUPLICATE", "admin-2"));

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        verify(payments, never()).findByIdForUpdate(any());
    }

    @Test
    void requesterCannotApproveOwnRefund() {
        PaymentRefund requested = requestedRefund();
        when(refunds.findByIdForUpdate(requested.getId())).thenReturn(Optional.of(requested));

        ApiException error = assertThrows(ApiException.class,
                () -> service.approve(requested.getId(), "CASH", null, "admin-1"));

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        verify(payments, never()).findByIdForUpdate(any());
    }

    @Test
    void requesterCannotRejectOwnRefund() {
        PaymentRefund requested = requestedRefund();
        when(refunds.findByIdForUpdate(requested.getId())).thenReturn(Optional.of(requested));

        ApiException error = assertThrows(ApiException.class,
                () -> service.reject(requested.getId(), "Không duyệt", "admin-1"));

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
    }

    private Payment payment(long amount) {
        return Payment.builder().id("payment-1").invoiceId("invoice-1").amount(amount)
                .method("MB_BANK_TRANSFER").status("SUCCESS").build();
    }

    private Invoice invoice(long total, long paid) {
        return Invoice.builder().id("invoice-1").code("INV-001").studentId("student-1")
                .studentName("Student").parentId("parent-1").totalAmount(total).paidAmount(paid)
                .status("PAID").dueDate(LocalDate.now().plusDays(10)).build();
    }

    private PaymentRefund refundEntity(FinanceDtos.PaymentRefundResponse response, Invoice invoice) {
        return PaymentRefund.builder()
                .id(response.id()).refundNumber(response.refundNumber()).paymentId(response.paymentId())
                .invoiceId(invoice.getId()).invoiceCode(invoice.getCode()).studentId(invoice.getStudentId())
                .studentCode(response.studentCode()).studentName(invoice.getStudentName()).parentId(invoice.getParentId())
                .amount(response.amount()).reason(response.reason()).status(response.status())
                .requestedBy(response.requestedBy()).requestedAt(response.requestedAt()).updatedAt(response.updatedAt())
                .build();
    }

    private PaymentRefund requestedRefund() {
        return PaymentRefund.builder()
                .id("refund-requested")
                .refundNumber("SSE-RF-REQUESTED")
                .paymentId("payment-1")
                .invoiceId("invoice-1")
                .studentId("student-1")
                .amount(100_000)
                .reason("Hoàn thừa")
                .status("REQUESTED")
                .requestedBy("admin-1")
                .requestedAt(java.time.Instant.now())
                .build();
    }
}
