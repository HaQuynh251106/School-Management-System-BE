package com.sse.app.academic.attendance;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Gửi một lần thông báo nhắc điểm danh khi tiết học bắt đầu. */
@Component
@ConditionalOnProperty(name = "sse.attendance-reminders.enabled", havingValue = "true", matchIfMissing = true)
public class AttendanceReminderScheduler {
    private static final ZoneId SCHOOL_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private final AttendanceService attendance;

    public AttendanceReminderScheduler(AttendanceService attendance) {
        this.attendance = attendance;
    }

    @Scheduled(
            fixedDelayString = "${sse.attendance-reminders.interval-ms:60000}",
            initialDelayString = "${sse.attendance-reminders.initial-delay-ms:15000}")
    public void remindDueSessions() {
        attendance.sendDueReminders(ZonedDateTime.now(SCHOOL_ZONE));
    }
}
