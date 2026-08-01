package com.sse.app.academic.structure;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/** Bảo vệ dữ liệu khỏi trạng thái ACTIVE trước ngày bắt đầu thực tế. */
@Component
public class AcademicStatusSynchronizer {
    private static final ZoneId SCHOOL_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final AcademicYearRepository years;
    private final SemesterRepository semesters;
    private final Clock clock;

    public AcademicStatusSynchronizer(AcademicYearRepository years, SemesterRepository semesters, Clock clock) {
        this.years = years;
        this.semesters = semesters;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(cron = "${sse.academic.status-sync-cron:0 5 0 * * *}", zone = "Asia/Ho_Chi_Minh")
    @Transactional
    public void correctFutureActiveStatuses() {
        LocalDate today = LocalDate.now(clock.withZone(SCHOOL_ZONE));
        years.findByStatus("ACTIVE").stream()
                .filter(item -> item.getStartDate() != null && today.isBefore(item.getStartDate()))
                .forEach(item -> item.setStatus("PLANNED"));
        semesters.findByStatus("ACTIVE").stream()
                .filter(item -> item.getStartDate() != null && today.isBefore(item.getStartDate()))
                .forEach(item -> item.setStatus("PLANNED"));
    }
}
