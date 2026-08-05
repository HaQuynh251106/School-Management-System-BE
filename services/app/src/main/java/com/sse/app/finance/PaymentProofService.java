package com.sse.app.finance;

import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.event.DomainEventPublisher;
import com.sse.app.file.FileStorageService;
import com.sse.app.file.StoredFile;
import com.sse.app.finance.FinanceDtos.PaymentProofDecisionResponse;
import com.sse.app.finance.FinanceDtos.PaymentProofResponse;
import com.sse.app.identity.User;
import com.sse.app.identity.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PaymentProofService {
    private final PaymentProofRepository proofs;
    private final PaymentRepository payments;
    private final InvoiceRepository invoices;
    private final FileStorageService storage;
    private final PaymentService paymentService;
    private final UserService users;
    private final DomainEventPublisher events;

    public PaymentProofService(PaymentProofRepository proofs,
                               PaymentRepository payments,
                               InvoiceRepository invoices,
                               FileStorageService storage,
                               PaymentService paymentService,
                               UserService users,
                               DomainEventPublisher events) {
        this.proofs = proofs;
        this.payments = payments;
        this.invoices = invoices;
        this.storage = storage;
        this.paymentService = paymentService;
        this.users = users;
        this.events = events;
    }

    @Transactional
    public PaymentProofResponse submit(String paymentId, String fileId, String actorId, String role) {
        Payment payment = payments.findByIdForUpdate(paymentId)
                .orElseThrow(() -> ApiException.notFound("Payment"));
        if (!"MB_BANK_TRANSFER".equals(payment.getMethod())) {
            throw ApiException.badRequest("Chỉ chuyển khoản MB mới sử dụng biên lai");
        }
        if (!"PENDING".equals(payment.getStatus())) {
            throw ApiException.conflict("Giao dịch không còn chờ xác nhận");
        }
        Invoice invoice = invoices.findById(payment.getInvoiceId())
                .orElseThrow(() -> ApiException.notFound("Hóa đơn"));
        assertSubmitAccess(invoice, actorId, role);
        if (proofs.existsByPaymentIdAndStatus(paymentId, "SUBMITTED")) {
            throw ApiException.conflict("Biên lai đang chờ Admin duyệt");
        }

        StoredFile file = storage.requireReadyOwnedFile(fileId, "PAYMENT_PROOF", actorId);
        if (proofs.existsByFileId(file.getId())) {
            throw ApiException.conflict("Ảnh biên lai này đã được gửi trước đó");
        }
        User student = users.getById(invoice.getStudentId());
        Instant now = Instant.now();
        PaymentProof proof = proofs.save(PaymentProof.builder()
                .id(Ids.gen("proof"))
                .paymentId(payment.getId())
                .invoiceId(invoice.getId())
                .invoiceCode(invoice.getCode())
                .parentId(invoice.getParentId())
                .studentId(invoice.getStudentId())
                .studentCode(student.getStudentCode())
                .studentName(invoice.getStudentName())
                .amount(payment.getAmount())
                .fileId(file.getId())
                .fileName(file.getOriginalName())
                .contentType(file.getContentType())
                .sizeBytes(file.getSizeBytes())
                .status("SUBMITTED")
                .submittedBy(actorId)
                .submittedAt(now)
                .build());
        payment.setAutoProvisioned(false);
        payment.setNote("Phụ huynh đã gửi biên lai, chờ Admin đối chiếu tài khoản MB");
        payment.setUpdatedAt(now);
        payments.save(payment);

        Map<String, Object> payload = payload(proof, "Có biên lai chuyển khoản mới chờ duyệt");
        for (String adminId : users.userIdsByRole("ADMIN")) {
            events.publish("finance.payment.proof_submitted", adminId, "payment_proof", proof.getId(), payload);
        }
        return toResponse(proof);
    }

    public List<PaymentProofResponse> listForParent(String parentId) {
        return proofs.findByParentIdOrderBySubmittedAtDesc(parentId).stream().map(this::toResponse).toList();
    }

    public List<PaymentProofResponse> listForAdmin(String status) {
        String normalized = status == null ? null : status.trim().toUpperCase();
        return proofs.findAllByOrderBySubmittedAtDesc().stream()
                .filter(proof -> normalized == null || normalized.isBlank() || normalized.equals(proof.getStatus()))
                .sorted(Comparator
                        .comparingInt((PaymentProof proof) -> statusRank(proof.getStatus()))
                        .thenComparing(PaymentProof::getSubmittedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::toResponse)
                .toList();
    }

    public List<PaymentProofResponse> listForPayment(String paymentId) {
        return proofs.findByPaymentIdOrderBySubmittedAtDesc(paymentId).stream().map(this::toResponse).toList();
    }

    @Transactional
    public PaymentProofDecisionResponse approve(String proofId, String adminId) {
        PaymentProof proof = proofForUpdate(proofId);
        if ("APPROVED".equals(proof.getStatus())) {
            Payment payment = payment(paymentId(proof));
            return new PaymentProofDecisionResponse(toResponse(proof), payment, invoice(proof.getInvoiceId()));
        }
        requireSubmitted(proof);

        var result = paymentService.confirmBankTransfer(proof.getPaymentId(), adminId);
        proof.setStatus("APPROVED");
        proof.setReviewedBy(adminId);
        proof.setReviewedAt(Instant.now());
        proof.setReviewReason(null);
        proofs.save(proof);
        publishParent(proof, "Biên lai đã được xác nhận, hóa đơn đã cập nhật");
        return new PaymentProofDecisionResponse(toResponse(proof), result.payment(), result.invoice());
    }

    @Transactional
    public PaymentProofDecisionResponse requestRepayment(String proofId, String adminId, String reasonValue) {
        String reason = reasonValue == null ? "" : reasonValue.trim();
        if (reason.isBlank()) throw ApiException.badRequest("Bắt buộc nhập lý do yêu cầu thanh toán lại");

        PaymentProof proof = proofForUpdate(proofId);
        requireSubmitted(proof);
        Payment payment = payments.findByIdForUpdate(proof.getPaymentId())
                .orElseThrow(() -> ApiException.notFound("Payment"));
        if (!"PENDING".equals(payment.getStatus())) {
            throw ApiException.conflict("Giao dịch không còn chờ xác nhận");
        }

        proof.setStatus("RETRY_REQUIRED");
        proof.setReviewedBy(adminId);
        proof.setReviewedAt(Instant.now());
        proof.setReviewReason(reason);
        proofs.save(proof);
        payment.setNote("Admin yêu cầu thanh toán lại: " + reason);
        payment.setUpdatedAt(Instant.now());
        payments.save(payment);
        publishParent(proof, "Yêu cầu thanh toán lại: " + reason);
        return new PaymentProofDecisionResponse(toResponse(proof), payment, invoice(proof.getInvoiceId()));
    }

    private void assertSubmitAccess(Invoice invoice, String actorId, String role) {
        if (!"PARENT".equals(role)) throw ApiException.forbidden("Chỉ phụ huynh được gửi biên lai");
        if (!actorId.equals(invoice.getParentId())) {
            users.assertParentOf(actorId, invoice.getStudentId());
        }
    }

    private void requireSubmitted(PaymentProof proof) {
        if (!"SUBMITTED".equals(proof.getStatus())) {
            throw ApiException.conflict("Chỉ biên lai đang chờ duyệt mới được xử lý");
        }
    }

    private PaymentProof proofForUpdate(String proofId) {
        return proofs.findByIdForUpdate(proofId).orElseThrow(() -> ApiException.notFound("Biên lai"));
    }

    private Payment payment(String paymentId) {
        return payments.findById(paymentId).orElseThrow(() -> ApiException.notFound("Payment"));
    }

    private String paymentId(PaymentProof proof) {
        return proof.getPaymentId();
    }

    private Invoice invoice(String invoiceId) {
        return invoices.findById(invoiceId).orElseThrow(() -> ApiException.notFound("Hóa đơn"));
    }

    private void publishParent(PaymentProof proof, String message) {
        if (proof.getParentId() == null) return;
        events.publish("finance.payment.proof_reviewed", proof.getParentId(), "payment_proof", proof.getId(),
                payload(proof, message));
    }

    private Map<String, Object> payload(PaymentProof proof, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("paymentId", proof.getPaymentId());
        payload.put("invoiceId", proof.getInvoiceId());
        payload.put("invoiceCode", proof.getInvoiceCode());
        payload.put("studentId", proof.getStudentId());
        payload.put("studentName", proof.getStudentName());
        payload.put("amount", proof.getAmount());
        payload.put("status", proof.getStatus());
        payload.put("message", message);
        return payload;
    }

    private int statusRank(String status) {
        return switch (status) {
            case "SUBMITTED" -> 0;
            case "RETRY_REQUIRED" -> 1;
            case "APPROVED" -> 2;
            default -> 3;
        };
    }

    private PaymentProofResponse toResponse(PaymentProof proof) {
        return new PaymentProofResponse(
                proof.getId(), proof.getPaymentId(), proof.getInvoiceId(), proof.getInvoiceCode(),
                proof.getParentId(), proof.getStudentId(), proof.getStudentCode(), proof.getStudentName(),
                proof.getAmount(), proof.getFileId(), proof.getFileName(), proof.getContentType(),
                proof.getSizeBytes(), proof.getStatus(), proof.getSubmittedBy(), proof.getSubmittedAt(),
                proof.getReviewedBy(), proof.getReviewedAt(), proof.getReviewReason());
    }
}
