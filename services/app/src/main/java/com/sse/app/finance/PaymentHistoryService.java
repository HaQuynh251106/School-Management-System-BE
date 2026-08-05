package com.sse.app.finance;

import com.sse.app.finance.FinanceDtos.PaymentHistoryResponse;
import com.sse.app.identity.UserService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PaymentHistoryService {
    private final PaymentRepository payments;
    private final InvoiceRepository invoices;
    private final FeePeriodRepository feePeriods;
    private final PaymentGatewayTransactionRepository gatewayTransactions;
    private final PaymentReceiptRepository receipts;
    private final PaymentRefundRepository refunds;
    private final UserService users;

    public PaymentHistoryService(PaymentRepository payments,
                                 InvoiceRepository invoices,
                                 FeePeriodRepository feePeriods,
                                 PaymentGatewayTransactionRepository gatewayTransactions,
                                 PaymentReceiptRepository receipts,
                                 PaymentRefundRepository refunds,
                                 UserService users) {
        this.payments = payments;
        this.invoices = invoices;
        this.feePeriods = feePeriods;
        this.gatewayTransactions = gatewayTransactions;
        this.receipts = receipts;
        this.refunds = refunds;
        this.users = users;
    }

    public List<PaymentHistoryResponse> list(String studentId, String parentId, String status, String method) {
        List<Invoice> accessibleInvoices;
        boolean allInvoices = isBlank(studentId) && isBlank(parentId);
        if (!isBlank(studentId)) accessibleInvoices = invoices.findByStudentId(studentId.trim());
        else if (!isBlank(parentId)) accessibleInvoices = invoices.findByParentId(parentId.trim());
        else accessibleInvoices = invoices.findAll();

        Map<String, Invoice> invoiceById = accessibleInvoices.stream()
                .collect(Collectors.toMap(Invoice::getId, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        if (invoiceById.isEmpty()) return List.of();

        List<Payment> rows = allInvoices
                ? payments.findAllByOrderByCreatedAtDesc()
                : payments.findByInvoiceIdInOrderByCreatedAtDesc(invoiceById.keySet());
        rows = rows.stream()
                .filter(payment -> invoiceById.containsKey(payment.getInvoiceId()))
                .filter(payment -> isBlank(status) || status.trim().equalsIgnoreCase(payment.getStatus()))
                .filter(payment -> isBlank(method) || method.trim().equalsIgnoreCase(payment.getMethod()))
                .sorted(Comparator.comparing(this::paymentHistoryTime,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        if (rows.isEmpty()) return List.of();

        List<String> paymentIds = rows.stream().map(Payment::getId).toList();
        Map<String, PaymentReceipt> receiptByPayment = receipts.findByPaymentIdIn(paymentIds).stream()
                .collect(Collectors.toMap(PaymentReceipt::getPaymentId, Function.identity(), (left, right) -> left));
        Map<String, RefundSummary> refundByPayment = summarizeRefunds(paymentIds);
        Map<String, GatewaySummary> gatewayByPayment = summarizeGatewayTransactions(paymentIds);
        Map<String, FeePeriod> periodById = feePeriods.findAll().stream()
                .collect(Collectors.toMap(FeePeriod::getId, Function.identity(), (left, right) -> left));
        Map<String, String> studentCodes = studentCodes(accessibleInvoices);

        return rows.stream().map(payment -> {
            Invoice invoice = invoiceById.get(payment.getInvoiceId());
            FeePeriod period = invoice.getFeePeriodId() == null ? null : periodById.get(invoice.getFeePeriodId());
            PaymentReceipt receipt = receiptByPayment.get(payment.getId());
            GatewaySummary gateway = gatewayByPayment.get(payment.getId());
            RefundSummary refund = refundByPayment.getOrDefault(payment.getId(), new RefundSummary(0, 0));
            return new PaymentHistoryResponse(
                    payment.getId(), invoice.getId(), invoice.getCode(), invoice.getFeePeriodId(),
                    period == null ? null : period.getCode(), invoice.getStudentId(),
                    studentCodes.get(invoice.getStudentId()), invoice.getStudentName(), payment.getAmount(),
                    payment.getMethod(), payment.getStatus(), payment.getTxnRef(), payment.getNote(),
                    payment.getCreatedAt(), payment.getUpdatedAt(), payment.getPaidAt(),
                    gateway == null ? null : gateway.providerTransactionId,
                    gateway == null ? null : gateway.errorCode,
                    gateway == null ? null : gateway.errorMessage,
                    gateway == null ? 0 : gateway.callbackCount,
                    receipt == null ? null : receipt.getId(),
                    receipt == null ? null : receipt.getReceiptNumber(),
                    receipt == null ? null : receipt.getStatus(),
                    receipt == null ? null : receipt.getIssuedAt(),
                    refund.completedAmount, refund.pendingAmount,
                    Math.max(0, payment.getAmount() - refund.completedAmount));
        }).toList();
    }

    private Map<String, RefundSummary> summarizeRefunds(List<String> paymentIds) {
        Map<String, long[]> totals = new HashMap<>();
        for (PaymentRefund refund : refunds.findByPaymentIdIn(paymentIds)) {
            long[] values = totals.computeIfAbsent(refund.getPaymentId(), ignored -> new long[2]);
            if ("COMPLETED".equals(refund.getStatus())) values[0] += refund.getAmount();
            else if ("REQUESTED".equals(refund.getStatus())) values[1] += refund.getAmount();
        }
        Map<String, RefundSummary> result = new HashMap<>();
        totals.forEach((paymentId, values) -> result.put(paymentId, new RefundSummary(values[0], values[1])));
        return result;
    }

    private Map<String, GatewaySummary> summarizeGatewayTransactions(List<String> paymentIds) {
        Map<String, List<PaymentGatewayTransaction>> grouped = gatewayTransactions.findByPaymentIdIn(paymentIds)
                .stream().filter(transaction -> transaction.getPaymentId() != null)
                .collect(Collectors.groupingBy(PaymentGatewayTransaction::getPaymentId));
        Map<String, GatewaySummary> result = new HashMap<>();
        grouped.forEach((paymentId, transactions) -> {
            List<PaymentGatewayTransaction> sorted = new ArrayList<>(transactions);
            sorted.sort(Comparator.comparing(this::transactionTime,
                    Comparator.nullsLast(Comparator.reverseOrder())));
            PaymentGatewayTransaction latest = sorted.get(0);
            int callbackCount = sorted.stream().mapToInt(PaymentGatewayTransaction::getCallbackCount).sum();
            String providerTransactionId = sorted.stream()
                    .map(PaymentGatewayTransaction::getProviderTransactionId)
                    .filter(value -> value != null && !value.isBlank()).findFirst().orElse(null);
            result.put(paymentId, new GatewaySummary(providerTransactionId, latest.getErrorCode(),
                    latest.getErrorMessage(), callbackCount));
        });
        return result;
    }

    private Map<String, String> studentCodes(List<Invoice> invoiceRows) {
        Map<String, String> result = new HashMap<>();
        invoiceRows.stream().map(Invoice::getStudentId).distinct().forEach(studentId -> {
            try {
                result.put(studentId, users.getById(studentId).getStudentCode());
            } catch (RuntimeException ignored) {
                result.put(studentId, null);
            }
        });
        return result;
    }

    private Instant transactionTime(PaymentGatewayTransaction transaction) {
        return transaction.getUpdatedAt() == null ? transaction.getCreatedAt() : transaction.getUpdatedAt();
    }

    private Instant paymentHistoryTime(Payment payment) {
        if (payment.getPaidAt() != null) return payment.getPaidAt();
        if (payment.getUpdatedAt() != null) return payment.getUpdatedAt();
        return payment.getCreatedAt();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private record GatewaySummary(String providerTransactionId, String errorCode,
                                  String errorMessage, int callbackCount) {}
    private record RefundSummary(long completedAmount, long pendingAmount) {}
}
