package com.sse.app.finance;

import com.sse.app.finance.FinanceDtos.FinanceReminderRunResponse;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;

@Service
public class FinanceReminderService {
    private static final ZoneId SCHOOL_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final InvoiceRepository invoices;
    private final FinanceService finance;

    public FinanceReminderService(
            InvoiceRepository invoices, FinanceService finance) {
        this.invoices = invoices;
        this.finance = finance;
    }

    @Scheduled(cron = "${sse.finance.reminders.cron:0 0 8 * * *}",
            zone = "Asia/Ho_Chi_Minh")
    public void scheduledRun() {
        run();
    }

    public FinanceReminderRunResponse run() {
        var rows = invoices.findAll();
        LocalDate today = LocalDate.now(SCHOOL_ZONE);
        int reminded = 0;
        int skipped = 0;
        for (Invoice invoice : rows) {
            if (invoice.getDueDate() == null
                    || !invoice.getDueDate().isBefore(today)
                    || !Set.of("PENDING", "PARTIAL", "OVERDUE")
                    .contains(invoice.getStatus())) {
                skipped++;
                continue;
            }
            if (invoice.getLastReminderAt() != null
                    && invoice.getLastReminderAt().atZone(SCHOOL_ZONE)
                    .toLocalDate().equals(today)) {
                skipped++;
                continue;
            }
            finance.remindOverdue(invoice.getId());
            reminded++;
        }
        return new FinanceReminderRunResponse(
                rows.size(), reminded, skipped, Instant.now());
    }
}
