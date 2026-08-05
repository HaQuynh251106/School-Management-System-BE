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
            String id, @NotBlank String code, String name, @NotBlank String academicYearId,
            String applyToGrades, LocalDate dueDate) {}

    public record UpdateFeePeriodRequest(
            @NotBlank String name, @NotBlank String academicYearId,
            String applyToGrades, LocalDate dueDate) {}

    public record AddFeeItemRequest(String id, @NotBlank String name, @NotNull @Positive Long amount, String gradeLevel) {}

    public record PayRequest(@NotBlank String invoiceId, String method) {}

    public record CashPaymentRequest(@NotBlank String invoiceId, @Positive Long amount) {}

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

    public record InvoiceScopeClass(
            String classId, String classCode, String gradeLevel,
            int eligibleStudents, int existingInvoices, int missingParents,
            long amountPerStudent) {}

    /** Xem trước phạm vi phát hành để kế toán kiểm tra trước khi tạo hóa đơn. */
    public record InvoiceGenerationPreview(
            String periodId, String periodCode, String periodName,
            String academicYearId, String academicYearCode, List<String> gradeLevels,
            int classCount, int eligibleStudents, int existingInvoices,
            int invoicesToCreate, int missingParents, long expectedNewReceivable,
            List<InvoiceScopeClass> classes, List<String> warnings) {}

    public record PaymentView(
            String id, String invoiceId, String invoiceCode,
            String studentId, String studentName, String classId, String classCode,
            String feePeriodId, String feePeriodName, String academicYearId,
            long amount, String method, String status, String txnRef,
            Instant createdAt, Instant paidAt) {}

    public record PaymentCallbackRequest(
            @NotBlank String txnRef,
            @NotBlank String status,
            @NotNull @Positive Long amount,
            @NotBlank String signature) {}
}
