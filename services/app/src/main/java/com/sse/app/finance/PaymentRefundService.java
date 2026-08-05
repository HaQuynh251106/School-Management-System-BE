package com.sse.app.finance;

import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.event.DomainEventPublisher;
import com.sse.app.finance.FinanceDtos.PaymentRefundResponse;
import com.sse.app.identity.UserService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class PaymentRefundService {
    private static final ZoneId SCHOOL_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter REFUND_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Set<String> REFUND_METHODS = Set.of("MB_BANK_TRANSFER", "CASH", "OTHER");

    private final PaymentRefundRepository refunds;
    private final PaymentRepository payments;
    private final InvoiceRepository invoices;
    private final UserService users;
    private final DomainEventPublisher events;

    public PaymentRefundService(PaymentRefundRepository refunds,
                                PaymentRepository payments,
                                InvoiceRepository invoices,
                                UserService users,
                                DomainEventPublisher events) {
        this.refunds = refunds;
        this.payments = payments;
        this.invoices = invoices;
        this.users = users;
        this.events = events;
    }

    @Transactional
    public PaymentRefundResponse request(String paymentId, long amount, String reason, String requestedBy) {
        Payment payment = payments.findByIdForUpdate(paymentId)
                .orElseThrow(() -> ApiException.notFound("Payment"));
        if (!"SUCCESS".equals(payment.getStatus())) {
            throw ApiException.conflict("Chỉ giao dịch đã thanh toán thành công mới được yêu cầu hoàn tiền");
        }
        long completedBefore = refunds.sumAmountByPaymentIdAndStatusIn(paymentId, List.of("COMPLETED"));
        long reserved = refunds.sumAmountByPaymentIdAndStatusIn(paymentId, List.of("REQUESTED", "COMPLETED"));
        long available = payment.getAmount() - reserved;
        if (amount <= 0) throw ApiException.badRequest("Số tiền hoàn phải lớn hơn 0");
        if (amount > available) {
            throw ApiException.conflict("Số tiền có thể hoàn còn lại là " + available + " VND");
        }

        Invoice invoice = invoices.findById(payment.getInvoiceId())
                .orElseThrow(() -> ApiException.notFound("Hóa đơn"));
        if (invoice.getPaidAmount() < amount) {
            throw ApiException.conflict("Số dư đã thu của hóa đơn không đủ; hãy chạy đối soát trước khi hoàn tiền");
        }
        Instant now = Instant.now();
        String id = Ids.gen("refund");
        PaymentRefund refund = PaymentRefund.builder()
                .id(id)
                .refundNumber(refundNumber(id, now))
                .paymentId(payment.getId())
                .invoiceId(invoice.getId())
                .invoiceCode(invoice.getCode())
                .studentId(invoice.getStudentId())
                .studentCode(studentCode(invoice.getStudentId()))
                .studentName(invoice.getStudentName())
                .parentId(invoice.getParentId())
                .amount(amount)
                .refundType(amount == available ? "FULL" : "PARTIAL")
                .paymentAmount(payment.getAmount())
                .refundedAmountBefore(completedBefore)
                .reason(reason.trim())
                .status("REQUESTED")
                .requestedBy(requestedBy)
                .requestedAt(now)
                .updatedAt(now)
                .build();
        return toResponse(refunds.save(refund));
    }

    @Transactional
    public PaymentRefundResponse approve(String refundId, String method, String reference, String approvedBy) {
        PaymentRefund refund = refundForUpdate(refundId);
        if ("COMPLETED".equals(refund.getStatus())) return toResponse(refund);
        requireRequested(refund);
        requireIndependentReviewer(refund, approvedBy);

        String normalizedMethod = normalizeMethod(method);
        String normalizedReference = normalizeReference(normalizedMethod, reference);
        if (normalizedReference != null
                && refunds.existsByRefundMethodAndRefundReferenceIgnoreCaseAndStatus(
                normalizedMethod, normalizedReference, "COMPLETED")) {
            throw ApiException.conflict("Mã tham chiếu hoàn tiền đã được dùng cho một yêu cầu khác");
        }
        Payment payment = payments.findByIdForUpdate(refund.getPaymentId())
                .orElseThrow(() -> ApiException.notFound("Payment"));
        Invoice invoice = invoices.findByIdForUpdate(refund.getInvoiceId())
                .orElseThrow(() -> ApiException.notFound("Hóa đơn"));
        if (!Set.of("SUCCESS", "REVERSED").contains(payment.getStatus())) {
            throw ApiException.conflict("Trạng thái payment không cho phép hoàn tiền");
        }
        long completedBefore = refunds.sumAmountByPaymentIdAndStatusIn(payment.getId(), List.of("COMPLETED"));
        if (completedBefore + refund.getAmount() > payment.getAmount()) {
            throw ApiException.conflict("Tổng hoàn tiền vượt quá số tiền của giao dịch");
        }
        if (invoice.getPaidAmount() < refund.getAmount()) {
            throw ApiException.conflict("Số dư đã thu của hóa đơn không đủ; hãy chạy đối soát trước khi duyệt");
        }

        Instant now = Instant.now();
        long invoicePaidBefore = invoice.getPaidAmount();
        String invoiceStatusBefore = invoice.getStatus();
        long completedAfter = completedBefore + refund.getAmount();
        refund.setStatus("COMPLETED");
        refund.setApprovedBy(approvedBy);
        refund.setApprovedAt(now);
        refund.setRefundMethod(normalizedMethod);
        refund.setRefundReference(normalizedReference);
        refund.setRefundType(completedAfter == payment.getAmount() ? "FULL" : "PARTIAL");
        refund.setPaymentAmount(payment.getAmount());
        refund.setRefundedAmountBefore(completedBefore);
        refund.setRefundedAmountAfter(completedAfter);
        refund.setInvoicePaidAmountBefore(invoicePaidBefore);
        refund.setInvoiceStatusBefore(invoiceStatusBefore);
        refund.setCompletedAt(now);
        refund.setUpdatedAt(now);

        invoice.setPaidAmount(invoice.getPaidAmount() - refund.getAmount());
        invoice.setStatus(invoiceStatus(invoice));
        refund.setInvoicePaidAmountAfter(invoice.getPaidAmount());
        refund.setInvoiceStatusAfter(invoice.getStatus());
        invoices.save(invoice);

        if (completedAfter == payment.getAmount()) {
            payment.setStatus("REVERSED");
        }
        payment.setUpdatedAt(now);
        payments.save(payment);
        try {
            refunds.saveAndFlush(refund);
        } catch (DataIntegrityViolationException duplicateReference) {
            throw ApiException.conflict("Mã tham chiếu hoàn tiền đã được dùng cho một yêu cầu khác");
        }
        publishCompleted(refund, invoice, approvedBy);
        return toResponse(refund);
    }

    @Transactional
    public PaymentRefundResponse reject(String refundId, String reason, String rejectedBy) {
        PaymentRefund refund = refundForUpdate(refundId);
        if ("REJECTED".equals(refund.getStatus())) return toResponse(refund);
        requireRequested(refund);
        requireIndependentReviewer(refund, rejectedBy);
        Instant now = Instant.now();
        refund.setStatus("REJECTED");
        refund.setRejectedBy(rejectedBy);
        refund.setRejectedAt(now);
        refund.setRejectionReason(reason.trim());
        refund.setUpdatedAt(now);
        return toResponse(refunds.save(refund));
    }

    @Transactional
    public PaymentRefundResponse cancel(String refundId, String reason, String cancelledBy) {
        PaymentRefund refund = refundForUpdate(refundId);
        if ("CANCELLED".equals(refund.getStatus())) return toResponse(refund);
        requireRequested(refund);
        Instant now = Instant.now();
        refund.setStatus("CANCELLED");
        refund.setCancelledBy(cancelledBy);
        refund.setCancelledAt(now);
        refund.setCancellationReason(reason.trim());
        refund.setUpdatedAt(now);
        return toResponse(refunds.save(refund));
    }

    public List<PaymentRefundResponse> list(String studentId, String parentId, String status) {
        List<PaymentRefund> rows;
        if (!isBlank(studentId)) rows = refunds.findByStudentIdOrderByRequestedAtDesc(studentId.trim());
        else if (!isBlank(parentId)) rows = refunds.findByParentIdOrderByRequestedAtDesc(parentId.trim());
        else rows = refunds.findAllByOrderByRequestedAtDesc();
        return rows.stream()
                .filter(row -> isBlank(status) || status.trim().equalsIgnoreCase(row.getStatus()))
                .map(this::toResponse)
                .toList();
    }

    public PaymentRefund get(String refundId) {
        return refunds.findById(refundId).orElseThrow(() -> ApiException.notFound("Yêu cầu hoàn tiền"));
    }

    public PaymentRefundResponse toResponse(PaymentRefund refund) {
        return new PaymentRefundResponse(
                refund.getId(), refund.getRefundNumber(), refund.getPaymentId(), refund.getInvoiceId(),
                refund.getInvoiceCode(), refund.getStudentId(), refund.getStudentCode(), refund.getStudentName(),
                refund.getParentId(), refund.getAmount(), refund.getRefundType(), refund.getPaymentAmount(),
                refund.getRefundedAmountBefore(), refund.getRefundedAmountAfter(),
                refund.getInvoicePaidAmountBefore(), refund.getInvoicePaidAmountAfter(),
                refund.getInvoiceStatusBefore(), refund.getInvoiceStatusAfter(), refund.getReason(), refund.getStatus(),
                refund.getRequestedBy(), actorName(refund.getRequestedBy()), refund.getRequestedAt(),
                refund.getApprovedBy(), actorName(refund.getApprovedBy()), refund.getApprovedAt(),
                refund.getRejectedBy(), refund.getRejectedAt(), refund.getRejectionReason(),
                refund.getCancelledBy(), refund.getCancelledAt(), refund.getCancellationReason(),
                refund.getRefundMethod(), refund.getRefundReference(), refund.getCompletedAt(), refund.getUpdatedAt());
    }

    private PaymentRefund refundForUpdate(String refundId) {
        return refunds.findByIdForUpdate(refundId)
                .orElseThrow(() -> ApiException.notFound("Yêu cầu hoàn tiền"));
    }

    private void requireRequested(PaymentRefund refund) {
        if (!"REQUESTED".equals(refund.getStatus())) {
            throw ApiException.conflict("Yêu cầu hoàn tiền đã được xử lý với trạng thái " + refund.getStatus());
        }
    }

    private void requireIndependentReviewer(PaymentRefund refund, String reviewerId) {
        if (Objects.equals(refund.getRequestedBy(), reviewerId)) {
            throw ApiException.conflict(
                    "Admin tạo yêu cầu không thể tự duyệt hoặc từ chối; hãy đăng nhập bằng Admin khác");
        }
    }

    private String normalizeMethod(String method) {
        String value = method == null ? "" : method.trim().toUpperCase(Locale.ROOT);
        if (!REFUND_METHODS.contains(value)) {
            throw ApiException.badRequest("Phương thức hoàn tiền phải là MB_BANK_TRANSFER, CASH hoặc OTHER");
        }
        return value;
    }

    private String normalizeReference(String method, String reference) {
        String value = trimToNull(reference);
        if (!"CASH".equals(method) && value == null) {
            throw ApiException.badRequest("Hoàn qua chuyển khoản hoặc phương thức khác bắt buộc có mã tham chiếu");
        }
        return value;
    }

    private String invoiceStatus(Invoice invoice) {
        if (invoice.getPaidAmount() >= invoice.getTotalAmount()) return "PAID";
        if (invoice.getDueDate() != null && invoice.getDueDate().isBefore(LocalDate.now(SCHOOL_ZONE))) {
            return "OVERDUE";
        }
        return invoice.getPaidAmount() > 0 ? "PARTIAL" : "PENDING";
    }

    private void publishCompleted(PaymentRefund refund, Invoice invoice, String actorId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("studentId", invoice.getStudentId());
        if (invoice.getParentId() != null) payload.put("parentId", invoice.getParentId());
        payload.put("paymentId", refund.getPaymentId());
        payload.put("refundNumber", refund.getRefundNumber());
        payload.put("amount", refund.getAmount());
        payload.put("refundType", refund.getRefundType());
        payload.put("refundMethod", refund.getRefundMethod());
        if (refund.getRefundReference() != null) payload.put("refundReference", refund.getRefundReference());
        payload.put("remainingPaymentAmount", Math.max(0,
                refund.getPaymentAmount() - refund.getRefundedAmountAfter()));
        payload.put("message", String.format("Khoản hoàn %s trị giá %,d VND đã được xác nhận. Còn lại %,d VND.",
                refund.getRefundNumber(), refund.getAmount(),
                Math.max(0, refund.getPaymentAmount() - refund.getRefundedAmountAfter())));
        events.publish("finance.payment.refunded", actorId, "payment_refund", refund.getId(), payload);
    }

    private String studentCode(String studentId) {
        try {
            return users.getById(studentId).getStudentCode();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String refundNumber(String id, Instant now) {
        String token = id.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        if (token.length() > 14) token = token.substring(token.length() - 14);
        return "SSE-RF-" + REFUND_DATE.format(now.atZone(SCHOOL_ZONE)) + "-" + token;
    }

    private String trimToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private String actorName(String userId) {
        return isBlank(userId) ? null : users.fullNameOf(userId);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
