package com.sse.app.finance;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvoiceStateMachineTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 11);

    @Test
    void resolvesCollectionStatesFromAmountsAndDueDate() {
        assertEquals("UNPAID", resolve(1_000, 0, 0, TODAY.plusDays(1), "UNPAID"));
        assertEquals("OVERDUE", resolve(1_000, 0, 0, TODAY.minusDays(1), "UNPAID"));
        assertEquals("PARTIAL", resolve(1_000, 250, 0, TODAY.plusDays(1), "UNPAID"));
        assertEquals("PARTIAL", resolve(1_000, 250, 0, TODAY.minusDays(1), "OVERDUE"));
        assertEquals("PAID", resolve(1_000, 1_000, 0, TODAY.minusDays(1), "PARTIAL"));
    }

    @Test
    void resolvesRefundStatesAndKeepsCancellationTerminal() {
        assertEquals("PARTIALLY_REFUNDED", resolve(1_000, 1_000, 300, TODAY, "PAID"));
        assertEquals("REFUNDED", resolve(1_000, 1_000, 1_000, TODAY, "PARTIALLY_REFUNDED"));
        assertEquals("CANCELLED", resolve(1_000, 0, 0, TODAY.minusDays(1), "CANCELLED"));
    }

    @Test
    void exposesAllowedFinancialActionsForEachState() {
        assertTrue(InvoiceStateMachine.isCollectable("UNPAID"));
        assertTrue(InvoiceStateMachine.isCollectable("PARTIAL"));
        assertTrue(InvoiceStateMachine.isCollectable("OVERDUE"));
        assertFalse(InvoiceStateMachine.isCollectable("PAID"));
        assertTrue(InvoiceStateMachine.isRefundable("PAID"));
        assertTrue(InvoiceStateMachine.isRefundable("PARTIALLY_REFUNDED"));
        assertFalse(InvoiceStateMachine.isRefundable("PARTIAL"));
        assertFalse(InvoiceStateMachine.isRefundable("REFUNDED"));
        assertFalse(InvoiceStateMachine.isRefundable("CANCELLED"));
    }

    private String resolve(long total, long paid, long refunded, LocalDate dueDate, String current) {
        return InvoiceStateMachine.resolve(total, paid, refunded, dueDate, current, TODAY);
    }
}
