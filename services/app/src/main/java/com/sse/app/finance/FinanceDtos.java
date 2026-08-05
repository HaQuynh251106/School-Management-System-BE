package com.sse.app.finance;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.time.Instant;
import java.util.List;

public final class FinanceDtos {
    private FinanceDtos() {}

    public record CreateFeePeriodRequest(
            String id,
            @NotBlank @Size(max = 50) String code,
            @NotBlank @Size(max = 255) String name,
             String academicYearId,
             String applyToGrades,
             LocalDate dueDate,
             String targetType,
             @Size(max = 500) List<@NotBlank String> targetIds,
             String feeType,
             String semesterId) {
        public CreateFeePeriodRequest(
                String id,
                String code,
                String name,
                String academicYearId,
                String applyToGrades,
                LocalDate dueDate,
                String targetType,
                List<String> targetIds) {
            this(id, code, name, academicYearId, applyToGrades, dueDate, targetType, targetIds, null, null);
        }
    }

    public record AddFeeItemRequest(
            String id,
             @NotBlank @Size(max = 255) String name,
             @NotNull @Positive Long amount,
             String gradeLevel,
             String targetType,
             @Size(max = 500) List<@NotBlank String> targetIds) {}

    public record CancelFeePeriodRequest(@Size(max = 500) String reason) {}

    public record UpdateFeePeriodMetadataRequest(
            @NotBlank String feeType,
            @NotBlank String academicYearId,
            @NotBlank String semesterId) {}

    public record InvoicePreviewStudent(
            String studentId,
            String studentName,
            String classId,
            String className,
            int itemCount,
            long totalAmount,
            boolean alreadyIssued) {}

    public record InvoicePreview(
            String feePeriodId,
            String status,
            int targetedStudentCount,
            int billableStudentCount,
            int existingInvoiceCount,
            int newInvoiceCount,
            long existingTotalAmount,
            long newTotalAmount,
            long projectedTotalAmount,
            List<InvoicePreviewStudent> students) {}

    public record PayRequest(@NotBlank String invoiceId, String method) {}

    public record PaymentInitResponse(
            Payment payment,
            Invoice invoice,
            String gatewayStatus,
            String paymentUrl,
            String callbackUrl,
            BankTransferInstructions bankTransfer) {}

    public record BankTransferInstructions(
            String bankId,
            String bankName,
            String accountNumber,
            String accountName,
            long amount,
            String transferContent,
            String qrImageUrl,
            String studentCode,
            String studentName,
            String invoiceCode) {}

    public record SubmitPaymentProofRequest(
            @NotBlank String fileId) {}

    public record ReviewPaymentProofRequest(@Size(max = 500) String reason) {}

    public record PaymentProofResponse(
            String id,
            String paymentId,
            String invoiceId,
            String invoiceCode,
            String parentId,
            String studentId,
            String studentCode,
            String studentName,
            long amount,
            String fileId,
            String fileName,
            String contentType,
            long sizeBytes,
            String status,
            String submittedBy,
            Instant submittedAt,
            String reviewedBy,
            Instant reviewedAt,
            String reviewReason) {}

    public record PaymentProofDecisionResponse(
            PaymentProofResponse proof,
            Payment payment,
            Invoice invoice) {}

    public record PaymentHistoryResponse(
            String paymentId,
            String invoiceId,
            String invoiceCode,
            String feePeriodId,
            String feePeriodCode,
            String studentId,
            String studentCode,
            String studentName,
            long amount,
            String method,
            String status,
            String txnRef,
            String note,
            Instant createdAt,
            Instant updatedAt,
            Instant paidAt,
            String providerTransactionId,
            String gatewayErrorCode,
            String gatewayErrorMessage,
            int callbackCount,
            String receiptId,
            String receiptNumber,
            String receiptStatus,
            Instant receiptIssuedAt,
            long refundedAmount,
            long pendingRefundAmount,
            long netAmount) {}

    public record CreateRefundRequest(
            @NotNull @Positive Long amount,
            @NotBlank @Size(max = 500) String reason) {}

    public record ApproveRefundRequest(
            @NotBlank @Size(max = 40) String method,
            @Size(max = 120) String reference) {}

    public record RejectRefundRequest(@NotBlank @Size(max = 500) String reason) {}

    public record CancelRefundRequest(@NotBlank @Size(max = 500) String reason) {}

    public record PaymentRefundResponse(
            String id,
            String refundNumber,
            String paymentId,
            String invoiceId,
            String invoiceCode,
            String studentId,
            String studentCode,
            String studentName,
            String parentId,
            long amount,
            String refundType,
            Long paymentAmount,
            Long refundedAmountBefore,
            Long refundedAmountAfter,
            Long invoicePaidAmountBefore,
            Long invoicePaidAmountAfter,
            String invoiceStatusBefore,
            String invoiceStatusAfter,
            String reason,
            String status,
            String requestedBy,
            String requestedByName,
            Instant requestedAt,
            String approvedBy,
            String approvedByName,
            Instant approvedAt,
            String rejectedBy,
            Instant rejectedAt,
            String rejectionReason,
            String cancelledBy,
            Instant cancelledAt,
            String cancellationReason,
            String refundMethod,
            String refundReference,
            Instant completedAt,
            Instant updatedAt) {}

    public record ReconciliationRequest(
            LocalDate date,
            LocalDate fromDate,
            LocalDate toDate,
            Long minAmount,
            Long maxAmount,
            String method) {}

    public record ReconciliationMethodSummaryResponse(
            String method,
            int paymentCount,
            long grossAmount,
            int refundCount,
            long refundAmount,
            long netAmount) {}

    public record ReconciliationIssueResponse(
            String id,
            String issueType,
            String severity,
            String entityType,
            String entityId,
            Long expectedAmount,
            Long actualAmount,
            String message,
            Instant createdAt) {}

    public record ReconciliationResponse(
            String id,
            LocalDate reconciliationDate,
            LocalDate fromDate,
            LocalDate toDate,
            Long minAmount,
            Long maxAmount,
            String method,
            String status,
            int paymentCount,
            long grossAmount,
            int refundCount,
            long refundAmount,
            long netAmount,
            int discrepancyCount,
            String runBy,
            String runByName,
            Instant runAt,
            int runCount,
            List<ReconciliationMethodSummaryResponse> methodSummaries,
            List<ReconciliationIssueResponse> issues) {}

    public record PaymentReceiptResponse(
            String id,
            String receiptNumber,
            String paymentId,
            String invoiceId,
            String invoiceCode,
            String studentId,
            String studentCode,
            String studentName,
            long amount,
            String method,
            String status,
            String fileId,
            String issuedBy,
            Instant issuedAt,
            Instant generatedAt,
            int generationAttempts,
            String generationError,
            Integer revision,
            String previousFileId,
            String voidedBy,
            Instant voidedAt,
            String voidReason) {}

    public record PaymentReceiptDownloadResponse(
            PaymentReceiptResponse receipt,
            String downloadUrl,
            Instant expiresAt) {}

    public record GatewayCallbackResponse(
            boolean accepted,
            boolean processed,
            String paymentId,
            String paymentStatus,
            String invoiceStatus,
            int callbackCount,
            String errorCode,
            String message) {}

    public record VnpayIpnResponse(
            @JsonProperty("RspCode") String rspCode,
            @JsonProperty("Message") String message) {}

    public record BrowserReturnResponse(
            String paymentId,
            String provider,
            String status,
            boolean finalStatus,
            String message,
            Boolean signatureValid,
            Boolean gatewaySuccessful,
            String txnRef,
            Long amount,
            String providerTransactionId) {}

    public record FinanceReminderRunResponse(
            int scanned,
            int reminded,
            int skipped,
            Instant executedAt) {}

    public record VoidReceiptRequest(
            @NotBlank @Size(max = 500) String reason) {}

    public record BankStatementEntryResponse(
            String id,
            String transactionReference,
            long amount,
            Instant transferredAt,
            String transferContent,
            String status,
            String matchedInvoiceId,
            String matchedPaymentId,
            String mismatchReason) {}

    public record BankStatementImportResponse(
            String importBatchId,
            int total,
            int matched,
            int unmatched,
            int mismatched,
            int duplicates,
            List<BankStatementEntryResponse> entries) {}
}
