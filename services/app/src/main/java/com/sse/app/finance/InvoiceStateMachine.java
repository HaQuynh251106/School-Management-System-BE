package com.sse.app.finance;

import java.time.LocalDate;
import java.util.Set;

final class InvoiceStateMachine {
    static final String UNPAID = "UNPAID";
    static final String PARTIAL = "PARTIAL";
    static final String PAID = "PAID";
    static final String OVERDUE = "OVERDUE";
    static final String PARTIALLY_REFUNDED = "PARTIALLY_REFUNDED";
    static final String REFUNDED = "REFUNDED";
    static final String CANCELLED = "CANCELLED";

    private static final Set<String> COLLECTABLE = Set.of(UNPAID, PARTIAL, OVERDUE);
    private static final Set<String> REFUNDABLE = Set.of(PAID, PARTIALLY_REFUNDED);

    private InvoiceStateMachine() {}

    static String resolve(long totalAmount, long paidAmount, long refundedAmount,
                          LocalDate dueDate, String currentStatus, LocalDate today) {
        if (CANCELLED.equals(currentStatus)) return CANCELLED;
        if (refundedAmount > 0) {
            return refundedAmount >= paidAmount ? REFUNDED : PARTIALLY_REFUNDED;
        }
        if (paidAmount >= totalAmount) return PAID;
        if (paidAmount > 0) return PARTIAL;
        return dueDate != null && dueDate.isBefore(today) ? OVERDUE : UNPAID;
    }

    static boolean isCollectable(String status) {
        return COLLECTABLE.contains(status);
    }

    static boolean isRefundable(String status) {
        return REFUNDABLE.contains(status);
    }
}
