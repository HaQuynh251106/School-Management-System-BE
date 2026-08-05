package com.sse.app.academic.exam;

import com.sse.app.common.SchedulerExecutionRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Gửi nhiệm vụ khảo thí đúng mốc và không gửi trùng khi scheduler chạy lại. */
@Component
@ConditionalOnProperty(name = "sse.exam-reminders.enabled", havingValue = "true", matchIfMissing = true)
public class ExamDutyReminderScheduler {
    private static final ZoneId SCHOOL_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private final ExamService exams;
    private final SchedulerExecutionRegistry executions;

    public ExamDutyReminderScheduler(ExamService exams, SchedulerExecutionRegistry executions) {
        this.exams = exams;
        this.executions = executions;
    }

    @Scheduled(
            fixedDelayString = "${sse.exam-reminders.interval-ms:3600000}",
            initialDelayString = "${sse.exam-reminders.initial-delay-ms:20000}")
    public void sendDueDuties() {
        executions.run("exam-duty-reminders", "Nhắc nhiệm vụ khảo thí",
                () -> exams.sendDueDutyNotifications(ZonedDateTime.now(SCHOOL_ZONE)));
    }
}
