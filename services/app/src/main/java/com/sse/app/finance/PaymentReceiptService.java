package com.sse.app.finance;

import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.file.FileDtos.PresignDownloadResponse;
import com.sse.app.file.FileStorageService;
import com.sse.app.file.StoredFile;
import com.sse.app.finance.FinanceDtos.PaymentReceiptDownloadResponse;
import com.sse.app.finance.FinanceDtos.PaymentReceiptResponse;
import com.sse.app.identity.User;
import com.sse.app.identity.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@Service
public class PaymentReceiptService {
    private static final Logger log = LoggerFactory.getLogger(PaymentReceiptService.class);
    private static final DateTimeFormatter RECEIPT_DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
            .withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    private final PaymentReceiptRepository receipts;
    private final PaymentRepository payments;
    private final InvoiceRepository invoices;
    private final FeePeriodRepository feePeriods;
    private final UserService users;
    private final FileStorageService storage;
    private final PaymentReceiptPdfRenderer renderer;

    public PaymentReceiptService(PaymentReceiptRepository receipts,
                                 PaymentRepository payments,
                                 InvoiceRepository invoices,
                                 FeePeriodRepository feePeriods,
                                 UserService users,
                                 FileStorageService storage,
                                 PaymentReceiptPdfRenderer renderer) {
        this.receipts = receipts;
        this.payments = payments;
        this.invoices = invoices;
        this.feePeriods = feePeriods;
        this.users = users;
        this.storage = storage;
        this.renderer = renderer;
    }

    @Transactional
    public PaymentReceipt issueForPayment(String paymentId, String issuedBy) {
        Payment payment = payments.findByIdForUpdate(paymentId)
                .orElseThrow(() -> ApiException.notFound("Payment"));
        if (!"SUCCESS".equals(payment.getStatus())) {
            throw ApiException.conflict("Chỉ giao dịch thành công mới được phát hành biên nhận");
        }
        Invoice invoice = invoices.findById(payment.getInvoiceId())
                .orElseThrow(() -> ApiException.notFound("Hóa đơn"));
        return issueAfterSettlement(payment, invoice, issuedBy);
    }

    /** Receipt failures are persisted for retry and never roll back a successful payment. */
    public PaymentReceipt issueAfterSettlement(Payment payment, Invoice invoice, String issuedBy) {
        PaymentReceipt receipt = receipts.findByPaymentId(payment.getId()).orElseGet(() -> {
            Instant issuedAt = payment.getPaidAt() == null ? Instant.now() : payment.getPaidAt();
            return PaymentReceipt.builder()
                    .id(Ids.gen("receipt"))
                    .receiptNumber(receiptNumber(payment.getId(), issuedAt))
                    .paymentId(payment.getId())
                    .invoiceId(invoice.getId())
                    .invoiceCode(invoice.getCode())
                    .studentId(invoice.getStudentId())
                    .studentName(invoice.getStudentName())
                    .parentId(invoice.getParentId())
                    .amount(payment.getAmount())
                    .method(payment.getMethod())
                    .status("PENDING")
                    .issuedBy(normalizeIssuer(issuedBy))
                    .issuedAt(issuedAt)
                    .generationAttempts(0)
                    .revision(1)
                    .build();
        });
        if ("ISSUED".equals(receipt.getStatus()) && receipt.getFileId() != null) return receipt;
        if ("VOID".equals(receipt.getStatus())) return receipt;

        receipt.setStatus("PENDING");
        receipt.setIssuedBy(normalizeIssuer(issuedBy));
        receipt.setGenerationAttempts(receipt.getGenerationAttempts() + 1);
        receipt.setGenerationError(null);
        receipts.save(receipt);

        try {
            User student = users.getById(invoice.getStudentId());
            FeePeriod period = invoice.getFeePeriodId() == null ? null
                    : feePeriods.findById(invoice.getFeePeriodId()).orElse(null);
            byte[] pdf = renderer.render(new PaymentReceiptPdfRenderer.ReceiptData(
                    receipt.getReceiptNumber(), payment.getId(), invoice.getCode(),
                    period == null ? null : period.getCode(), period == null ? null : period.getName(),
                    student.getStudentCode(), invoice.getStudentName(), payment.getAmount(), payment.getMethod(),
                    payment.getTxnRef(), payment.getPaidAt() == null ? receipt.getIssuedAt() : payment.getPaidAt(),
                    receipt.getIssuedAt(), payment.getNote()));
            StoredFile file = storage.storeGeneratedReceipt(receipt.getReceiptNumber() + ".pdf", pdf,
                    normalizeIssuer(issuedBy));
            receipt.setStudentCode(student.getStudentCode());
            receipt.setFileId(file.getId());
            receipt.setStatus("ISSUED");
            receipt.setGeneratedAt(Instant.now());
            receipt.setGenerationError(null);
        } catch (Exception ex) {
            receipt.setStatus("FAILED");
            receipt.setGenerationError(limit(ex.getMessage(), 500));
            log.warn("Receipt generation failed for payment {}: {}", payment.getId(), ex.getMessage());
        }
        return receipts.save(receipt);
    }

    @Transactional
    public PaymentReceipt voidForPayment(
            String paymentId, String reason, String actorId) {
        PaymentReceipt receipt = getForPayment(paymentId);
        if (!"ISSUED".equals(receipt.getStatus())) {
            throw ApiException.conflict(
                    "Chỉ biên nhận đã phát hành mới được thu hồi");
        }
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("Bắt buộc nhập lý do thu hồi biên nhận");
        }
        receipt.setStatus("VOID");
        receipt.setPreviousFileId(receipt.getFileId());
        receipt.setVoidedBy(normalizeIssuer(actorId));
        receipt.setVoidedAt(Instant.now());
        receipt.setVoidReason(reason.trim());
        return receipts.save(receipt);
    }

    @Transactional
    public PaymentReceipt reissueForPayment(String paymentId, String actorId) {
        Payment payment = payments.findByIdForUpdate(paymentId)
                .orElseThrow(() -> ApiException.notFound("Payment"));
        if (!"SUCCESS".equals(payment.getStatus())) {
            throw ApiException.conflict(
                    "Chỉ giao dịch thành công mới được cấp lại biên nhận");
        }
        Invoice invoice = invoices.findById(payment.getInvoiceId())
                .orElseThrow(() -> ApiException.notFound("Hóa đơn"));
        PaymentReceipt receipt = getForPayment(paymentId);
        if (!"VOID".equals(receipt.getStatus())) {
            throw ApiException.conflict(
                    "Phải thu hồi biên nhận cũ trước khi cấp lại");
        }
        int revision = receipt.getRevision() == null
                ? 2 : receipt.getRevision() + 1;
        receipt.setRevision(revision);
        receipt.setReceiptNumber(
                receiptNumber(paymentId, Instant.now()) + "-R" + revision);
        receipt.setFileId(null);
        receipt.setStatus("PENDING");
        receipt.setIssuedBy(normalizeIssuer(actorId));
        receipt.setIssuedAt(Instant.now());
        receipt.setGeneratedAt(null);
        receipt.setGenerationError(null);
        receipt.setVoidedBy(null);
        receipt.setVoidedAt(null);
        receipt.setVoidReason(null);
        receipts.save(receipt);
        return issueAfterSettlement(payment, invoice, actorId);
    }

    public PaymentReceipt getForPayment(String paymentId) {
        return receipts.findByPaymentId(paymentId)
                .orElseThrow(() -> ApiException.notFound("Biên nhận thanh toán"));
    }

    public PaymentReceiptDownloadResponse downloadForPayment(String paymentId) {
        PaymentReceipt receipt = getForPayment(paymentId);
        if (!"ISSUED".equals(receipt.getStatus()) || receipt.getFileId() == null) {
            throw ApiException.conflict("Biên nhận chưa được tạo thành công");
        }
        PresignDownloadResponse download = storage.createDownloadUrlForAuthorizedAccess(receipt.getFileId());
        return new PaymentReceiptDownloadResponse(toResponse(receipt), download.downloadUrl(), download.expiresAt());
    }

    public PaymentReceiptResponse toResponse(PaymentReceipt receipt) {
        return new PaymentReceiptResponse(receipt.getId(), receipt.getReceiptNumber(), receipt.getPaymentId(),
                receipt.getInvoiceId(), receipt.getInvoiceCode(), receipt.getStudentId(), receipt.getStudentCode(),
                receipt.getStudentName(), receipt.getAmount(), receipt.getMethod(), receipt.getStatus(),
                receipt.getFileId(), receipt.getIssuedBy(), receipt.getIssuedAt(), receipt.getGeneratedAt(),
                receipt.getGenerationAttempts(), receipt.getGenerationError(),
                receipt.getRevision(), receipt.getPreviousFileId(), receipt.getVoidedBy(),
                receipt.getVoidedAt(), receipt.getVoidReason());
    }

    private String receiptNumber(String paymentId, Instant issuedAt) {
        String token = paymentId.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        if (token.length() > 14) token = token.substring(token.length() - 14);
        return "SSE-REC-" + RECEIPT_DATE.format(issuedAt) + "-" + token;
    }

    private String normalizeIssuer(String issuedBy) {
        return issuedBy == null || issuedBy.isBlank() ? "SYSTEM" : issuedBy.trim();
    }

    private String limit(String value, int maxLength) {
        String normalized = value == null || value.isBlank() ? "Không thể tạo biên nhận" : value.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }
}
