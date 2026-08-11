package com.sse.app.finance;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDate;
import java.time.Instant;
import java.util.List;

public final class FinanceDtos {
    private FinanceDtos() {}

    public record CreateFeePeriodRequest(
            String id, @NotBlank String code, String name, String academicYearId,
            String applyToGrades, LocalDate dueDate, String scopeType,
            String scopeGradeLevel, String scopeClassId, List<String> studentIds) {}

    public record UpdateFeePeriodRequest(
            @NotBlank String name, String academicYearId,
            String applyToGrades, LocalDate dueDate, String scopeType,
            String scopeGradeLevel, String scopeClassId, List<String> studentIds) {}

    public record AddFeeItemRequest(String id, @NotBlank String name, @NotNull @Positive Long amount, String gradeLevel) {}

    public record FeePeriodAdjustmentRequest(
            @NotBlank String studentId, @NotBlank String type,
            Long amount, String reason) {}

    public record FeePeriodRecipientPreview(
            String studentId, String studentName, String classId, String classCode,
            String gradeLevel, boolean parentLinked, boolean excluded,
            long discountAmount, long totalAmount, boolean invoiceExists) {}

    public record FeePeriodPreview(
            String feePeriodId, String status, int recipientCount,
            int invoiceCount, long totalAmount, boolean valid,
            List<String> errors, List<FeePeriodRecipientPreview> recipients) {}

    public record PayRequest(@NotBlank String invoiceId, String method) {}

    public record CashPaymentRequest(
            @NotBlank String invoiceId, @Positive Long amount,
            String payerName, String note) {}

    public record RefundInvoiceRequest(@NotNull @Positive Long amount, @NotBlank String reason) {}

    public record VietQrConfirmationRequest(String bankTransactionRef) {}

    /** Tổng hợp công nợ theo lớp để Admin điều hành và GVCN theo dõi lớp mình. */
    public record FinanceClassSummary(
            String classId, String classCode, String gradeLevel,
            String homeroomTeacherId, String homeroomTeacherName,
            int invoiceCount, int paidCount, int partialCount, int overdueCount,
            long totalAmount, long paidAmount, long outstanding,
            double collectionRate, boolean completed, boolean completionNotified,
            boolean reminderSentToday) {}

    public record ClassReminderResult(int invoiceCount, int recipientCount, Instant sentAt) {}

    public record HomeroomDebtReminderRequest(String periodId, List<String> classIds) {}

    public record HomeroomDebtReminderResult(
            int classCount, int recipientCount, int skippedCount, Instant sentAt) {}

    public record PaymentCallbackRequest(
            @NotBlank String txnRef,
            @NotBlank String status,
            @NotNull @Positive Long amount,
            @NotBlank String signature) {}
}
