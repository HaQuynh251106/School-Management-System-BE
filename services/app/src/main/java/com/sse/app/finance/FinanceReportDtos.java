package com.sse.app.finance;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class FinanceReportDtos {
    private FinanceReportDtos() {}

    public record FinanceReportFilter(
            LocalDate fromDate,
            LocalDate toDate,
            String feePeriodId,
            String gradeLevel,
            String classId,
            String studentId,
            String method,
            String feeType,
            String semesterId,
            String settlementStatus) {
        public FinanceReportFilter(
                LocalDate fromDate,
                LocalDate toDate,
                String feePeriodId,
                String gradeLevel,
                String classId,
                String studentId,
                String method) {
            this(fromDate, toDate, feePeriodId, gradeLevel, classId, studentId, method, null, null, null);
        }
    }

    public record FinanceReportSummary(
            int invoiceCount,
            int paidInvoiceCount,
            int outstandingInvoiceCount,
            int overdueInvoiceCount,
            long totalReceivable,
            long currentPaidAmount,
            long outstandingAmount,
            long overdueAmount,
            int paymentCount,
            long grossCollected,
            int refundCount,
            long refundAmount,
            long netRevenue) {}

    public record FinanceCashFlowRow(
            LocalDate date,
            int paymentCount,
            long grossCollected,
            int refundCount,
            long refundAmount,
            long netRevenue) {}

    public record FinanceMethodRow(
            String method,
            int paymentCount,
            long grossCollected,
            int refundCount,
            long refundAmount,
            long netRevenue) {}

    public record FinanceDebtGroupRow(
            String dimension,
            String key,
            String code,
            String name,
            int invoiceCount,
            int debtorCount,
            int overdueInvoiceCount,
            long totalReceivable,
            long currentPaidAmount,
            long outstandingAmount,
            long overdueAmount) {}

    public record FinanceDebtDetailRow(
            String invoiceId,
            String invoiceCode,
            String feePeriodId,
            String feePeriodCode,
            String feePeriodName,
            String studentId,
            String studentCode,
            String studentName,
            String gradeLevel,
            String classId,
            String classCode,
            long totalAmount,
            long paidAmount,
            long outstandingAmount,
            LocalDate dueDate,
            boolean overdue,
            String status) {}

    public record FinanceReportResponse(
            FinanceReportFilter filters,
            Instant generatedAt,
            FinanceReportSummary summary,
            List<FinanceCashFlowRow> dailyCashFlow,
            List<FinanceMethodRow> byMethod,
            List<FinanceDebtGroupRow> debtByFeePeriod,
            List<FinanceDebtGroupRow> debtByGrade,
            List<FinanceDebtGroupRow> debtByClass,
            List<FinanceDebtDetailRow> debts) {}

    public record FinanceReportFile(
            String filename,
            String contentType,
            byte[] content) {}
}
