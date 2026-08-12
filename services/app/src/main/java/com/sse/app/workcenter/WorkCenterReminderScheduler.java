package com.sse.app.workcenter;

import com.sse.app.common.Ids;
import com.sse.app.identity.*;
import com.sse.app.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Component @RequiredArgsConstructor
public class WorkCenterReminderScheduler {
    private static final Set<String> TERMINAL = Set.of("COMPLETED", "REJECTED", "CANCELLED");
    private final OperationTaskRepository tasks;
    private final OperationTaskReminderRepository reminders;
    private final OperationTaskHistoryRepository history;
    private final UserRepository users;
    private final NotificationService notifications;

    @Scheduled(cron = "0 */10 * * * *")
    @Transactional
    public void scheduleAndDispatch() {
        LocalDate today = LocalDate.now();
        Instant now = Instant.now();
        Instant todayKey = today.atStartOfDay(ZoneId.systemDefault()).toInstant();
        for (OperationTask task : tasks.findAll()) {
            if (TERMINAL.contains(task.getStatus()) || task.getDueDate() == null) continue;
            if (task.getSnoozedUntil() != null && task.getSnoozedUntil().isAfter(now)) continue;
            long days = java.time.temporal.ChronoUnit.DAYS.between(today, task.getDueDate());
            String type = days == 3 ? "T_MINUS_3" : days == 1 ? "T_MINUS_1" : days == 0 ? "DUE_TODAY" : days < 0 ? "OVERDUE" : null;
            if (type == null) continue;
            if (days < 0 && !"OVERDUE".equals(task.getStatus())) markOverdue(task, now);
            if (!reminders.existsByTaskIdAndReminderTypeAndScheduledFor(task.getId(), type, todayKey)) {
                reminders.save(OperationTaskReminder.builder().id(Ids.gen("trem")).taskId(task.getId())
                        .reminderType(type).scheduledFor(todayKey).status("PENDING").attempts(0)
                        .createdAt(now).updatedAt(now).build());
            }
        }
        dispatch(now);
    }

    private void markOverdue(OperationTask task, Instant now) {
        String from = task.getStatus();
        task.setPreviousStatus(from); task.setStatus("OVERDUE"); task.setSlaLevel("OVERDUE");
        task.setUpdatedAt(now); task.setLastEscalatedAt(now); tasks.save(task);
        history.save(OperationTaskHistory.builder().id(Ids.gen("thist")).taskId(task.getId())
                .actorId("SYSTEM").actorName("Hệ thống").action("MARKED_OVERDUE")
                .fromStatus(from).toStatus("OVERDUE").detail("Quá hạn nhưng chưa hoàn thành")
                .createdAt(now).build());
    }

    private void dispatch(Instant now) {
        for (OperationTaskReminder reminder : reminders
                .findTop100ByStatusInAndScheduledForLessThanEqualOrderByScheduledForAsc(List.of("PENDING", "FAILED"), now)) {
            if (reminder.getAttempts() >= 3) continue;
            OperationTask task = tasks.findById(reminder.getTaskId()).orElse(null);
            if (task == null || TERMINAL.contains(task.getStatus())) {
                reminder.setStatus("CANCELLED"); reminder.setUpdatedAt(now); reminders.save(reminder); continue;
            }
            try {
                List<String> recipients = recipients(task, "OVERDUE".equals(reminder.getReminderType()));
                String title = title(reminder.getReminderType());
                String body = task.getTitle() + " · Hạn " + task.getDueDate();
                for (String recipient : recipients) notifications.notifyUserOnce(recipient, "WORK_TASK_REMINDER",
                        "OVERDUE".equals(reminder.getReminderType()) ? "URGENT" : "IMPORTANT", title, body,
                        "OPERATION_TASK_REMINDER", task.getId() + ":" + reminder.getReminderType() + ":" + reminder.getScheduledFor(),
                        taskUrl(recipient, task.getId()));
                reminder.setStatus("SENT"); reminder.setSentAt(now); reminder.setLastError(null);
            } catch (Exception ex) {
                reminder.setStatus("FAILED"); reminder.setLastError(shortMessage(ex));
            }
            reminder.setAttempts(reminder.getAttempts() + 1); reminder.setUpdatedAt(now); reminders.save(reminder);
        }
    }

    private List<String> recipients(OperationTask task, boolean escalate) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (task.getAssignedTo() != null) ids.add(task.getAssignedTo());
        else users.findByRole(task.getAssignedRole()).stream().filter(u -> "ACTIVE".equals(u.getStatus())).map(User::getId).forEach(ids::add);
        if (escalate) {
            ids.add(task.getCreatedBy());
            users.findByRole("ADMIN").stream().filter(u -> "ACTIVE".equals(u.getStatus())).map(User::getId).forEach(ids::add);
        }
        ids.remove(null); ids.remove("SYSTEM");
        return List.copyOf(ids);
    }

    private String title(String type) {
        return switch (type) {
            case "T_MINUS_3" -> "Công việc còn 3 ngày đến hạn";
            case "T_MINUS_1" -> "Công việc sẽ đến hạn ngày mai";
            case "DUE_TODAY" -> "Công việc đến hạn hôm nay";
            default -> "Công việc đã quá hạn";
        };
    }

    private String taskUrl(String userId, String taskId) {
        String role = users.findById(userId).map(User::getRole).orElse("ADMIN");
        String path = switch (role) {
            case "ACADEMIC_STAFF" -> "giao-vu/cong-viec-hoc-vu";
            case "ACCOUNTANT" -> "ke-toan/cong-viec-tai-chinh";
            case "TEACHER" -> "giao-vien/viec-can-lam";
            default -> "quan-tri/trung-tam-cong-viec";
        };
        return "#/" + path + "?task=" + taskId;
    }

    private String shortMessage(Exception ex) {
        String value = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        return value.length() > 1900 ? value.substring(0, 1900) : value;
    }
}
