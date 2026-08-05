package com.sse.app.academic.assignment;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
public class AssignmentReminderScheduler {
    private final AssignmentRepository assignments;
    private final AssignmentService service;

    public AssignmentReminderScheduler(
            AssignmentRepository assignments, AssignmentService service) {
        this.assignments = assignments;
        this.service = service;
    }

    @Scheduled(cron = "${sse.assignments.reminders.cron:0 15 * * * *}",
            zone = "Asia/Ho_Chi_Minh")
    public void remindAssignmentsDueSoon() {
        Instant now = Instant.now();
        Instant threshold = now.plus(24, ChronoUnit.HOURS);
        assignments.findAll().stream()
                .filter(assignment -> "PUBLISHED".equals(assignment.getStatus()))
                .filter(assignment -> assignment.getDeadline() != null
                        && assignment.getDeadline().isAfter(now)
                        && !assignment.getDeadline().isAfter(threshold))
                .filter(assignment -> assignment.getLastReminderAt() == null
                        || assignment.getLastReminderAt()
                        .isBefore(now.minus(12, ChronoUnit.HOURS)))
                .forEach(assignment -> service.remindDue(
                        assignment.getId(), assignment.getTeacherId(), false));
    }
}
