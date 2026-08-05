package com.sse.app.academic.attendance;

import com.sse.app.common.SchedulerExecutionRegistry;
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
    private final SchedulerExecutionRegistry executions;

    public AttendanceReminderScheduler(AttendanceService attendance, SchedulerExecutionRegistry executions) {
        this.attendance = attendance;
        this.executions = executions;
    }

    @Scheduled(
            fixedDelayString = "${sse.attendance-reminders.interval-ms:60000}",
            initialDelayString = "${sse.attendance-reminders.initial-delay-ms:15000}")
    public void remindDueSessions() {
        executions.run("attendance-reminders", "Nhắc giáo viên điểm danh",
                () -> attendance.sendDueReminders(ZonedDateTime.now(SCHOOL_ZONE)));
    }
}
