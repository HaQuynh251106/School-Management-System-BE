package com.sse.app.finance;

import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.finance.FinanceDtos.ReconciliationIssueResponse;
import com.sse.app.finance.FinanceDtos.ReconciliationMethodSummaryResponse;
import com.sse.app.finance.FinanceDtos.ReconciliationRequest;
import com.sse.app.finance.FinanceDtos.ReconciliationResponse;
import com.sse.app.identity.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PaymentReconciliationService {
    private static final ZoneId SCHOOL_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Set<String> SETTLED_PAYMENT_STATUSES = Set.of("SUCCESS", "REVERSED");
    private static final Set<String> SUPPORTED_METHODS = Set.of(
            "VNPAY", "MOMO", "CASH", "MB_BANK_TRANSFER");
    private static final int MAX_RANGE_DAYS = 31;

    private final PaymentReconciliationRunRepository runs;
    private final PaymentReconciliationIssueRepository issues;
    private final PaymentReconciliationMethodSummaryRepository methodSummaries;
    private final PaymentRepository payments;
    private final PaymentRefundRepository refunds;
    private final InvoiceRepository invoices;
    private final PaymentReceiptRepository receipts;
    private final PaymentGatewayTransactionRepository gatewayTransactions;
    private final PaymentProofRepository paymentProofs;
    private final UserService users;

    public PaymentReconciliationService(PaymentReconciliationRunRepository runs,
                                        PaymentReconciliationIssueRepository issues,
                                        PaymentReconciliationMethodSummaryRepository methodSummaries,
                                        PaymentRepository payments,
                                        PaymentRefundRepository refunds,
                                        InvoiceRepository invoices,
                                        PaymentReceiptRepository receipts,
                                        PaymentGatewayTransactionRepository gatewayTransactions,
                                        PaymentProofRepository paymentProofs,
                                        UserService users) {
        this.runs = runs;
        this.issues = issues;
        this.methodSummaries = methodSummaries;
        this.payments = payments;
        this.refunds = refunds;
        this.invoices = invoices;
        this.receipts = receipts;
        this.gatewayTransactions = gatewayTransactions;
        this.paymentProofs = paymentProofs;
        this.users = users;
    }

    @Transactional
    public ReconciliationResponse run(LocalDate date, String runBy) {
        return run(new ReconciliationRequest(date, null, null, null, null, null), runBy);
    }

    @Transactional
    public ReconciliationResponse run(ReconciliationRequest request, String runBy) {
        ReconciliationScope scope = normalizeScope(request);
        Instant from = scope.fromDate().atStartOfDay(SCHOOL_ZONE).toInstant();
        Instant to = scope.toDate().plusDays(1).atStartOfDay(SCHOOL_ZONE).toInstant();

        List<Payment> paidAtRows = payments.findByPaidAtGreaterThanEqualAndPaidAtLessThan(from, to).stream()
                .filter(scope::matches)
                .toList();
        List<Payment> settledRows = paidAtRows.stream()
                .filter(payment -> SETTLED_PAYMENT_STATUSES.contains(payment.getStatus()))
                .toList();

        List<PaymentRefund> refundRows = refunds
                .findByStatusAndCompletedAtGreaterThanEqualAndCompletedAtLessThanOrderByCompletedAtAsc(
                        "COMPLETED", from, to);
        Map<String, Payment> refundPayments = loadPaymentsForRefunds(refundRows);
        List<PaymentRefund> completedRefunds = refundRows.stream()
                .filter(refund -> !scope.hasPaymentFilters()
                        || scope.matches(refundPayments.get(refund.getPaymentId())))
                .toList();

        PaymentReconciliationRun run = runs.findByScopeKeyForUpdate(scope.key()).orElse(null);
        if (run == null) {
            run = PaymentReconciliationRun.builder()
                    .id(Ids.gen("recon"))
                    .runCount(1)
                    .build();
        } else {
            run.setRunCount(run.getRunCount() + 1);
            issues.deleteByRunId(run.getId());
            methodSummaries.deleteByRunId(run.getId());
            issues.flush();
            methodSummaries.flush();
        }

        run.setReconciliationDate(scope.fromDate());
        run.setFromDate(scope.fromDate());
        run.setToDate(scope.toDate());
        run.setMinAmount(scope.minAmount());
        run.setMaxAmount(scope.maxAmount());
        run.setMethod(scope.method());
        run.setScopeKey(scope.key());

        long grossAmount = settledRows.stream().mapToLong(Payment::getAmount).sum();
        long refundAmount = completedRefunds.stream().mapToLong(PaymentRefund::getAmount).sum();
        List<PaymentReconciliationIssue> detected = detectIssues(
                run.getId(), paidAtRows, settledRows, completedRefunds);
        List<PaymentReconciliationMethodSummary> summaries = summarizeMethods(
                run.getId(), settledRows, completedRefunds, refundPayments);

        run.setStatus(detected.isEmpty() ? "BALANCED" : "DISCREPANCY");
        run.setPaymentCount(settledRows.size());
        run.setGrossAmount(grossAmount);
        run.setRefundCount(completedRefunds.size());
        run.setRefundAmount(refundAmount);
        run.setNetAmount(grossAmount - refundAmount);
        run.setDiscrepancyCount(detected.size());
        run.setRunBy(runBy);
        run.setRunAt(Instant.now());
        runs.save(run);
        if (!summaries.isEmpty()) methodSummaries.saveAll(summaries);
        if (!detected.isEmpty()) issues.saveAll(detected);
        return toResponse(run, detected, summaries);
    }

    public List<ReconciliationResponse> list() {
        return runs.findAllByOrderByRunAtDesc().stream()
                .map(run -> toResponse(run, List.of(), List.of()))
                .toList();
    }

    public ReconciliationResponse get(String runId) {
        PaymentReconciliationRun run = runs.findById(runId)
                .orElseThrow(() -> ApiException.notFound("Phiên đối soát"));
        return toResponse(run, issues.findByRunIdOrderByCreatedAtAsc(runId),
                methodSummaries.findByRunIdOrderByMethodAsc(runId));
    }

    private ReconciliationScope normalizeScope(ReconciliationRequest request) {
        if (request == null) throw ApiException.badRequest("Thiếu phạm vi đối soát");
        LocalDate fromDate = request.fromDate() != null ? request.fromDate() : request.date();
        if (fromDate == null) throw ApiException.badRequest("Phải chọn ngày bắt đầu đối soát");
        LocalDate toDate = request.toDate() != null ? request.toDate() : fromDate;
        LocalDate today = LocalDate.now(SCHOOL_ZONE);
        if (fromDate.isAfter(toDate)) {
            throw ApiException.badRequest("Ngày bắt đầu không được sau ngày kết thúc");
        }
        if (toDate.isAfter(today)) {
            throw ApiException.badRequest("Không thể đối soát ngày trong tương lai");
        }
        if (ChronoUnit.DAYS.between(fromDate, toDate) >= MAX_RANGE_DAYS) {
            throw ApiException.badRequest("Mỗi lần chỉ được đối soát tối đa 31 ngày");
        }

        Long minAmount = request.minAmount();
        Long maxAmount = request.maxAmount();
        if (minAmount != null && minAmount < 0 || maxAmount != null && maxAmount < 0) {
            throw ApiException.badRequest("Khoảng tiền đối soát không được âm");
        }
        if (minAmount != null && maxAmount != null && minAmount > maxAmount) {
            throw ApiException.badRequest("Số tiền tối thiểu không được lớn hơn số tiền tối đa");
        }

        String method = request.method() == null || request.method().isBlank()
                ? null : request.method().trim().toUpperCase(Locale.ROOT);
        if (method != null && !SUPPORTED_METHODS.contains(method)) {
            throw ApiException.badRequest("Phương thức đối soát không hợp lệ");
        }
        return new ReconciliationScope(fromDate, toDate, minAmount, maxAmount, method);
    }

    private Map<String, Payment> loadPaymentsForRefunds(List<PaymentRefund> refundRows) {
        List<String> paymentIds = refundRows.stream()
                .map(PaymentRefund::getPaymentId)
                .distinct()
                .toList();
        if (paymentIds.isEmpty()) return Map.of();
        return payments.findAllById(paymentIds).stream()
                .collect(Collectors.toMap(Payment::getId, Function.identity()));
    }

    private List<PaymentReconciliationMethodSummary> summarizeMethods(
            String runId,
            List<Payment> settledRows,
            List<PaymentRefund> completedRefunds,
            Map<String, Payment> refundPayments) {
        Map<String, MethodTotals> totals = new LinkedHashMap<>();
        for (Payment payment : settledRows) {
            MethodTotals total = totals.computeIfAbsent(valueOrUnknown(payment.getMethod()), ignored -> new MethodTotals());
            total.paymentCount++;
            total.grossAmount += payment.getAmount();
        }
        for (PaymentRefund refund : completedRefunds) {
            Payment payment = refundPayments.get(refund.getPaymentId());
            String method = payment == null ? "UNKNOWN" : valueOrUnknown(payment.getMethod());
            MethodTotals total = totals.computeIfAbsent(method, ignored -> new MethodTotals());
            total.refundCount++;
            total.refundAmount += refund.getAmount();
        }
        return totals.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> PaymentReconciliationMethodSummary.builder()
                        .id(Ids.gen("recon-method"))
                        .runId(runId)
                        .method(entry.getKey())
                        .paymentCount(entry.getValue().paymentCount)
                        .grossAmount(entry.getValue().grossAmount)
                        .refundCount(entry.getValue().refundCount)
                        .refundAmount(entry.getValue().refundAmount)
                        .netAmount(entry.getValue().grossAmount - entry.getValue().refundAmount)
                        .build())
                .toList();
    }

    private List<PaymentReconciliationIssue> detectIssues(String runId,
                                                           List<Payment> paidAtRows,
                                                           List<Payment> settledRows,
                                                           List<PaymentRefund> dailyRefunds) {
        List<PaymentReconciliationIssue> result = new ArrayList<>();
        for (Payment payment : paidAtRows) {
            if (!SETTLED_PAYMENT_STATUSES.contains(payment.getStatus())) {
                result.add(issue(runId, "PAYMENT_STATUS_INVALID", "ERROR", "PAYMENT", payment.getId(),
                        null, null, "Giao dịch có paidAt nhưng trạng thái là " + payment.getStatus()));
            }
        }

        detectSettlementEvidence(runId, settledRows, result);

        Map<String, PaymentReceipt> receiptByPayment = settledRows.isEmpty() ? Map.of()
                : receipts.findByPaymentIdIn(settledRows.stream().map(Payment::getId).toList()).stream()
                .collect(Collectors.toMap(PaymentReceipt::getPaymentId, Function.identity(), (left, right) -> left));
        for (Payment payment : settledRows) {
            PaymentReceipt receipt = receiptByPayment.get(payment.getId());
            if (receipt == null || !"ISSUED".equals(receipt.getStatus()) || receipt.getFileId() == null) {
                result.add(issue(runId, "RECEIPT_NOT_ISSUED", "WARNING", "PAYMENT", payment.getId(),
                        payment.getAmount(), null, "Giao dịch đã thu nhưng chưa có biên nhận PDF hợp lệ"));
            }
        }

        Set<String> touchedInvoiceIds = new LinkedHashSet<>();
        settledRows.forEach(payment -> touchedInvoiceIds.add(payment.getInvoiceId()));
        dailyRefunds.forEach(refund -> touchedInvoiceIds.add(refund.getInvoiceId()));
        if (touchedInvoiceIds.isEmpty()) return sortedIssues(result);

        Map<String, Invoice> invoiceById = invoices.findAllById(touchedInvoiceIds).stream()
                .collect(Collectors.toMap(Invoice::getId, Function.identity()));
        List<Payment> invoicePayments = payments.findByInvoiceIdInOrderByCreatedAtDesc(touchedInvoiceIds);
        Map<String, List<Payment>> paymentsByInvoice = invoicePayments.stream()
                .collect(Collectors.groupingBy(Payment::getInvoiceId));
        List<String> paymentIds = invoicePayments.stream().map(Payment::getId).toList();
        Map<String, List<PaymentRefund>> refundsByPayment = paymentIds.isEmpty() ? Map.of()
                : refunds.findByPaymentIdIn(paymentIds).stream()
                .filter(refund -> "COMPLETED".equals(refund.getStatus()))
                .collect(Collectors.groupingBy(PaymentRefund::getPaymentId));

        for (String invoiceId : touchedInvoiceIds) {
            Invoice invoice = invoiceById.get(invoiceId);
            if (invoice == null) {
                result.add(issue(runId, "INVOICE_NOT_FOUND", "ERROR", "INVOICE", invoiceId,
                        null, null, "Không tìm thấy hóa đơn của giao dịch trong phạm vi đối soát"));
                continue;
            }
            long expectedPaid = 0;
            for (Payment payment : paymentsByInvoice.getOrDefault(invoiceId, List.of())) {
                if (!SETTLED_PAYMENT_STATUSES.contains(payment.getStatus())) continue;
                long completed = refundsByPayment.getOrDefault(payment.getId(), List.of()).stream()
                        .mapToLong(PaymentRefund::getAmount).sum();
                expectedPaid += payment.getAmount() - completed;
                if (completed > payment.getAmount()) {
                    result.add(issue(runId, "REFUND_EXCEEDS_PAYMENT", "ERROR", "PAYMENT", payment.getId(),
                            payment.getAmount(), completed, "Tổng hoàn tiền vượt số tiền giao dịch"));
                }
                if ("REVERSED".equals(payment.getStatus()) && completed != payment.getAmount()) {
                    result.add(issue(runId, "PAYMENT_REVERSAL_MISMATCH", "ERROR", "PAYMENT", payment.getId(),
                            payment.getAmount(), completed,
                            "Payment REVERSED nhưng chưa được hoàn đủ toàn bộ số tiền"));
                }
                if ("SUCCESS".equals(payment.getStatus()) && completed == payment.getAmount()) {
                    result.add(issue(runId, "PAYMENT_REVERSAL_MISMATCH", "ERROR", "PAYMENT", payment.getId(),
                            payment.getAmount(), completed,
                            "Payment đã hoàn toàn bộ nhưng vẫn mang trạng thái SUCCESS"));
                }
            }
            if (expectedPaid != invoice.getPaidAmount()) {
                result.add(issue(runId, "INVOICE_PAID_AMOUNT_MISMATCH", "ERROR", "INVOICE", invoiceId,
                        expectedPaid, invoice.getPaidAmount(), "Số đã thu trên hóa đơn lệch với sổ payment/refund"));
            }
            if (expectedPaid < 0 || expectedPaid > invoice.getTotalAmount()) {
                result.add(issue(runId, "INVOICE_BALANCE_INVALID", "ERROR", "INVOICE", invoiceId,
                        invoice.getTotalAmount(), expectedPaid, "Số dư đã thu nằm ngoài khoảng hợp lệ của hóa đơn"));
            }
            if (!Set.of("CANCELLED", "VOID").contains(invoice.getStatus())) {
                String expectedStatus = expectedInvoiceStatus(invoice, expectedPaid);
                if (!expectedStatus.equals(invoice.getStatus())) {
                    result.add(issue(runId, "INVOICE_STATUS_MISMATCH", "ERROR", "INVOICE", invoiceId,
                            expectedPaid, invoice.getPaidAmount(),
                            "Trạng thái hóa đơn phải là " + expectedStatus
                                    + " nhưng hiện tại là " + invoice.getStatus()));
                }
            }
        }
        return sortedIssues(result);
    }

    private void detectSettlementEvidence(String runId,
                                          List<Payment> settledRows,
                                          List<PaymentReconciliationIssue> result) {
        List<Payment> gatewayPayments = settledRows.stream()
                .filter(payment -> Set.of("VNPAY", "MOMO").contains(payment.getMethod()))
                .toList();
        if (!gatewayPayments.isEmpty()) {
            Map<String, List<PaymentGatewayTransaction>> byPayment = gatewayTransactions
                    .findByPaymentIdIn(gatewayPayments.stream().map(Payment::getId).toList()).stream()
                    .collect(Collectors.groupingBy(PaymentGatewayTransaction::getPaymentId));
            for (Payment payment : gatewayPayments) {
                List<PaymentGatewayTransaction> providerRows = byPayment
                        .getOrDefault(payment.getId(), List.of()).stream()
                        .filter(transaction -> payment.getMethod().equals(transaction.getProvider()))
                        .toList();
                if (providerRows.isEmpty()) {
                    result.add(issue(runId, "GATEWAY_TRANSACTION_MISSING", "ERROR", "PAYMENT", payment.getId(),
                            payment.getAmount(), null,
                            "Payment " + payment.getMethod() + " đã thành công nhưng không có giao dịch cổng tương ứng"));
                    continue;
                }
                boolean signatureVerified = providerRows.stream()
                        .anyMatch(transaction -> Boolean.TRUE.equals(transaction.getSignatureValid()));
                if (!signatureVerified) {
                    result.add(issue(runId, "GATEWAY_SIGNATURE_NOT_VERIFIED", "ERROR", "PAYMENT", payment.getId(),
                            payment.getAmount(), null,
                            "Chưa có callback/IPN " + payment.getMethod() + " với chữ ký hợp lệ"));
                    continue;
                }
                boolean confirmed = providerRows.stream().anyMatch(transaction ->
                        Boolean.TRUE.equals(transaction.getSignatureValid())
                                && transaction.isProcessed()
                                && transaction.getProviderTransactionId() != null
                                && !transaction.getProviderTransactionId().isBlank()
                                && transaction.getErrorCode() == null);
                if (!confirmed) {
                    boolean providerIdMissing = providerRows.stream().anyMatch(transaction ->
                            Boolean.TRUE.equals(transaction.getSignatureValid())
                                    && transaction.isProcessed()
                                    && (transaction.getProviderTransactionId() == null
                                    || transaction.getProviderTransactionId().isBlank()));
                    result.add(issue(runId,
                            providerIdMissing ? "GATEWAY_PROVIDER_ID_MISSING" : "GATEWAY_IPN_NOT_PROCESSED",
                            "ERROR", "PAYMENT", payment.getId(), payment.getAmount(), null,
                            providerIdMissing
                                    ? "IPN hợp lệ nhưng thiếu mã giao dịch từ cổng thanh toán"
                                    : "IPN hợp lệ chưa được xử lý hoàn tất hoặc đang có lỗi cổng thanh toán"));
                }
            }
        }

        List<Payment> bankPayments = settledRows.stream()
                .filter(payment -> "MB_BANK_TRANSFER".equals(payment.getMethod()))
                .toList();
        if (!bankPayments.isEmpty()) {
            Set<String> approvedPaymentIds = paymentProofs
                    .findByPaymentIdIn(bankPayments.stream().map(Payment::getId).toList()).stream()
                    .filter(proof -> "APPROVED".equals(proof.getStatus()))
                    .map(PaymentProof::getPaymentId)
                    .collect(Collectors.toSet());
            for (Payment payment : bankPayments) {
                if (!approvedPaymentIds.contains(payment.getId())) {
                    result.add(issue(runId, "MB_PROOF_NOT_APPROVED", "ERROR", "PAYMENT", payment.getId(),
                            payment.getAmount(), null,
                            "Chuyển khoản MB đã ghi nhận thành công nhưng không có ảnh biên lai được Admin duyệt"));
                }
            }
        }
    }

    private List<PaymentReconciliationIssue> sortedIssues(List<PaymentReconciliationIssue> result) {
        result.sort(Comparator.comparing(PaymentReconciliationIssue::getSeverity)
                .thenComparing(PaymentReconciliationIssue::getEntityType)
                .thenComparing(PaymentReconciliationIssue::getEntityId)
                .thenComparing(PaymentReconciliationIssue::getIssueType));
        return result;
    }

    private String expectedInvoiceStatus(Invoice invoice, long paidAmount) {
        if (paidAmount >= invoice.getTotalAmount()) return "PAID";
        if (invoice.getDueDate() != null && invoice.getDueDate().isBefore(LocalDate.now(SCHOOL_ZONE))) {
            return "OVERDUE";
        }
        return paidAmount > 0 ? "PARTIAL" : "PENDING";
    }

    private PaymentReconciliationIssue issue(String runId, String type, String severity,
                                             String entityType, String entityId,
                                             Long expectedAmount, Long actualAmount, String message) {
        return PaymentReconciliationIssue.builder()
                .id(Ids.gen("recon-issue"))
                .runId(runId)
                .issueType(type)
                .severity(severity)
                .entityType(entityType)
                .entityId(entityId)
                .expectedAmount(expectedAmount)
                .actualAmount(actualAmount)
                .message(message)
                .createdAt(Instant.now())
                .build();
    }

    private ReconciliationResponse toResponse(PaymentReconciliationRun run,
                                              List<PaymentReconciliationIssue> runIssues,
                                              List<PaymentReconciliationMethodSummary> summaries) {
        LocalDate fromDate = run.getFromDate() == null ? run.getReconciliationDate() : run.getFromDate();
        LocalDate toDate = run.getToDate() == null ? run.getReconciliationDate() : run.getToDate();
        return new ReconciliationResponse(
                run.getId(), run.getReconciliationDate(), fromDate, toDate,
                run.getMinAmount(), run.getMaxAmount(), run.getMethod(), run.getStatus(),
                run.getPaymentCount(), run.getGrossAmount(), run.getRefundCount(), run.getRefundAmount(),
                run.getNetAmount(), run.getDiscrepancyCount(), run.getRunBy(), users.fullNameOf(run.getRunBy()),
                run.getRunAt(), run.getRunCount(), summaries.stream().map(this::toMethodSummaryResponse).toList(),
                runIssues.stream().map(this::toIssueResponse).toList());
    }

    private ReconciliationMethodSummaryResponse toMethodSummaryResponse(
            PaymentReconciliationMethodSummary summary) {
        return new ReconciliationMethodSummaryResponse(
                summary.getMethod(), summary.getPaymentCount(), summary.getGrossAmount(),
                summary.getRefundCount(), summary.getRefundAmount(), summary.getNetAmount());
    }

    private ReconciliationIssueResponse toIssueResponse(PaymentReconciliationIssue issue) {
        return new ReconciliationIssueResponse(issue.getId(), issue.getIssueType(), issue.getSeverity(),
                issue.getEntityType(), issue.getEntityId(), issue.getExpectedAmount(), issue.getActualAmount(),
                issue.getMessage(), issue.getCreatedAt());
    }

    private String valueOrUnknown(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value;
    }

    private record ReconciliationScope(
            LocalDate fromDate,
            LocalDate toDate,
            Long minAmount,
            Long maxAmount,
            String method) {
        boolean matches(Payment payment) {
            if (payment == null) return false;
            if (method != null && !method.equals(payment.getMethod())) return false;
            if (minAmount != null && payment.getAmount() < minAmount) return false;
            return maxAmount == null || payment.getAmount() <= maxAmount;
        }

        boolean hasPaymentFilters() {
            return method != null || minAmount != null || maxAmount != null;
        }

        String key() {
            return fromDate + "|" + toDate + "|" + (method == null ? "ALL" : method)
                    + "|" + (minAmount == null ? "*" : minAmount)
                    + "|" + (maxAmount == null ? "*" : maxAmount);
        }
    }

    private static final class MethodTotals {
        private int paymentCount;
        private long grossAmount;
        private int refundCount;
        private long refundAmount;
    }
}
