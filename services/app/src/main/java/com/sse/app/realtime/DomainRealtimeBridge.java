package com.sse.app.realtime;

import com.sse.app.event.DomainEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Locale;

/** Converts persisted domain changes into payload-free cache invalidations. */
@Component
public class DomainRealtimeBridge {
    private final RealtimeEventHub realtime;

    public DomainRealtimeBridge(RealtimeEventHub realtime) {
        this.realtime = realtime;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onDomainEvent(DomainEvent event) {
        realtime.broadcastInvalidation(
                eventTypeFor(event.name()), event.entityType(), event.name());
    }

    static String eventTypeFor(String eventName) {
        String value = eventName == null ? "" : eventName.toLowerCase(Locale.ROOT);
        if (value.contains("assignment") || value.contains("submission")) return "ASSIGNMENT_UPDATED";
        if (value.contains("grade") && !value.contains("exam")) return "GRADE_UPDATED";
        if (value.contains("exam")) return "EXAM_UPDATED";
        if (value.contains("timetable")) return "TIMETABLE_PUBLISHED";
        if (value.contains("attendance") || value.contains("excuse")) return "ATTENDANCE_UPDATED";
        if (value.contains("payment") || value.contains("invoice") || value.contains("refund")
                || value.contains("finance")) return "PAYMENT_STATUS_UPDATED";
        if (value.contains("year_result") || value.contains("year_review")
                || value.contains("promotion")) return "YEAR_RESULT_UPDATED";
        if (value.contains("education_plan") || value.contains("training_plan")) {
            return "ACADEMIC_PLAN_UPDATED";
        }
        if (value.contains("club") || value.contains("extracurricular")) return "CLUB_UPDATED";
        return "DOMAIN_UPDATED";
    }
}
