package com.sse.app.academic.timetable;

import com.sse.app.common.SchedulerExecutionRegistry;
import com.sse.app.notification.Notification;
import com.sse.app.notification.NotificationService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.Executor;

/** Xử lý outbox sau commit, an toàn khi tiến trình chạy lại hoặc kênh gửi tạm thời lỗi. */
@Service
public class TimetablePublicationOutboxProcessor {
    private static final int MAX_ATTEMPTS = 3;
    private final JdbcTemplate jdbc;
    private final NotificationService notifications;
    private final SchedulerExecutionRegistry executions;
    private final Executor executor;

    public TimetablePublicationOutboxProcessor(JdbcTemplate jdbc, NotificationService notifications,
                                               SchedulerExecutionRegistry executions,
                                               @Qualifier("notificationExecutor") Executor executor) {
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.executions = executions;
        this.executor = executor;
    }

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCommit(TimetablePublicationNotificationService.PublicationQueued event) {
        process(event.eventId());
    }

    @Scheduled(fixedDelayString = "${sse.timetable-publication.retry-interval-ms:30000}",
            initialDelayString = "${sse.timetable-publication.retry-initial-delay-ms:12000}")
    public void retryPending() {
        executions.run("timetable-publication-notification-retry", "Gửi lại thông báo thời khóa biểu", () ->
                jdbc.queryForList("""
                        select id from timetable_publication_events
                        where status in ('PENDING','RETRYING','PARTIAL') and attempts < ?
                          and (next_attempt_at is null or next_attempt_at<=?)
                        order by created_at limit 50
                        """, String.class, MAX_ATTEMPTS, Timestamp.from(Instant.now()))
                        .forEach(id -> executor.execute(() -> process(id))));
    }

    public void process(String eventId) {
        Instant now = Instant.now();
        int claimed = jdbc.update("""
                update timetable_publication_events set status='PROCESSING',attempts=attempts+1,updated_at=?
                where id=? and status in ('PENDING','RETRYING','PARTIAL') and attempts < ?
                  and (next_attempt_at is null or next_attempt_at<=?)
                """, Timestamp.from(now), eventId, MAX_ATTEMPTS, Timestamp.from(now));
        if (claimed == 0) return;
        List<RecipientJob> jobs = jdbc.query("""
                select id,recipient_id,recipient_role,context_key,title,body,action_url,attempts
                from timetable_publication_recipients
                where event_id=? and status in ('PENDING','FAILED') and attempts < ? order by created_at
                """, (rs, ignored) -> new RecipientJob(rs.getString("id"), rs.getString("recipient_id"),
                rs.getString("recipient_role"), rs.getString("context_key"), rs.getString("title"),
                rs.getString("body"), rs.getString("action_url"), rs.getInt("attempts")), eventId, MAX_ATTEMPTS);
        String lastError = null;
        for (RecipientJob job : jobs) {
            try {
                Notification notification = notifications.notifyUserOnce(job.recipientId(), "TIMETABLE",
                        "IMPORTANT", job.title(), job.body(), "TIMETABLE_PUBLICATION",
                        eventId + ":" + job.contextKey(), job.actionUrl());
                if (notification == null) throw new IllegalStateException("Không thể tạo thông báo trong ứng dụng");
                jdbc.update("""
                        update timetable_publication_recipients set status='DELIVERED',notification_id=?,
                            attempts=attempts+1,last_error=null,delivered_at=?,updated_at=? where id=?
                        """, notification.getId(), Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), job.id());
            } catch (Exception exception) {
                lastError = abbreviate(exception.getMessage());
                jdbc.update("""
                        update timetable_publication_recipients set status='FAILED',attempts=attempts+1,
                            last_error=?,updated_at=? where id=?
                        """, lastError, Timestamp.from(Instant.now()), job.id());
            }
        }
        int delivered = count("select count(*) from timetable_publication_recipients where event_id=? and status='DELIVERED'", eventId);
        int failed = count("select count(*) from timetable_publication_recipients where event_id=? and status='FAILED'", eventId);
        int total = count("select count(*) from timetable_publication_recipients where event_id=?", eventId);
        int attempts = count("select attempts from timetable_publication_events where id=?", eventId);
        String status;
        Timestamp nextAttempt = null;
        if (delivered == total) {
            status = "COMPLETED";
        } else if (attempts >= MAX_ATTEMPTS) {
            status = "FAILED";
        } else {
            status = delivered > 0 ? "PARTIAL" : "RETRYING";
            nextAttempt = Timestamp.from(Instant.now().plus(attempts == 1 ? 30 : 120, ChronoUnit.SECONDS));
        }
        Instant completedAt = Instant.now();
        jdbc.update("""
                update timetable_publication_events set status=?,delivered_recipient_count=?,
                    failed_recipient_count=?,last_error=?,next_attempt_at=?,processed_at=?,updated_at=? where id=?
                """, status, delivered, failed, lastError, nextAttempt, Timestamp.from(completedAt),
                Timestamp.from(completedAt), eventId);
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private String abbreviate(String value) {
        String result = value == null || value.isBlank() ? "Không xác định được nguyên nhân" : value;
        return result.length() <= 2000 ? result : result.substring(0, 2000);
    }

    private record RecipientJob(String id, String recipientId, String role, String contextKey,
                                String title, String body, String actionUrl, int attempts) {}
}
