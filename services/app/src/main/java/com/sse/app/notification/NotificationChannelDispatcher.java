package com.sse.app.notification;

import com.sse.app.common.Ids;
import com.sse.app.identity.User;
import com.sse.app.identity.UserService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/** Hàng đợi gửi email/push có theo dõi và tự thử lại tối đa ba lần. */
@Service
class NotificationChannelDispatcher {
    private static final int MAX_ATTEMPTS = 3;
    private final NotificationPreferenceRepository preferences;
    private final NotificationDeliveryLogRepository logs;
    private final UserDeviceRepository devices;
    private final UserService users;
    private final JavaMailSender mailSender;
    private final FirebasePushSender pushSender;
    private final Executor executor;
    private final boolean mailEnabled;
    private final String mailFrom;

    NotificationChannelDispatcher(NotificationPreferenceRepository preferences,
                                  NotificationDeliveryLogRepository logs,
                                  UserDeviceRepository devices, UserService users,
                                  JavaMailSender mailSender, FirebasePushSender pushSender,
                                  @Qualifier("notificationExecutor") Executor executor,
                                  @Value("${sse.mail.enabled:false}") boolean mailEnabled,
                                  @Value("${sse.mail.from:no-reply@smartschool.local}") String mailFrom) {
        this.preferences = preferences;
        this.logs = logs;
        this.devices = devices;
        this.users = users;
        this.mailSender = mailSender;
        this.pushSender = pushSender;
        this.executor = executor;
        this.mailEnabled = mailEnabled;
        this.mailFrom = mailFrom;
    }

    public void dispatch(String recipientId, String notificationId, String title, String body) {
        if (enabled(recipientId, "EMAIL")) queueEmailOrSkip(recipientId, notificationId, title, body);
        if (enabled(recipientId, "PUSH")) {
            if (pushSender.configured()) enqueue("PUSH", recipientId, notificationId, title, body);
            else logSkipped(notificationId, recipientId, "PUSH", "Firebase Cloud Messaging chưa được cấu hình");
        }
    }

    public void dispatchTransactionalEmail(String recipientId, String notificationId, String title, String body) {
        queueEmailOrSkip(recipientId, notificationId, title, body);
    }

    private void queueEmailOrSkip(String recipientId, String notificationId, String title, String body) {
        if (!mailEnabled) {
            logSkipped(notificationId, recipientId, "EMAIL", "Dịch vụ email chưa được cấu hình");
            return;
        }
        User user = users.getById(recipientId);
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            logSkipped(notificationId, recipientId, "EMAIL", "Người dùng chưa có địa chỉ email");
            return;
        }
        enqueue("EMAIL", recipientId, notificationId, title, body);
    }

    private void enqueue(String channel, String recipientId, String notificationId, String title, String body) {
        Instant now = Instant.now();
        NotificationDeliveryLog job = logs.save(NotificationDeliveryLog.builder()
                .id(Ids.gen("ndl")).notificationId(notificationId).recipientId(recipientId)
                .channel(channel).status("PENDING").attempts(0).title(title).payload(body)
                .nextAttemptAt(now).createdAt(now).updatedAt(now).build());
        executor.execute(() -> process(job.getId()));
    }

    @Scheduled(fixedDelayString = "${sse.notification-delivery.retry-interval-ms:30000}",
            initialDelayString = "${sse.notification-delivery.retry-initial-delay-ms:10000}")
    public void retryPending() {
        logs.findTop100ByStatusInAndAttemptsLessThanAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
                List.of("PENDING", "RETRYING"), MAX_ATTEMPTS, Instant.now())
                .forEach(job -> process(job.getId()));
    }

    private synchronized void process(String id) {
        NotificationDeliveryLog job = logs.findById(id).orElse(null);
        if (job == null || !List.of("PENDING", "RETRYING").contains(job.getStatus())
                || job.getAttempts() >= MAX_ATTEMPTS
                || job.getNextAttemptAt() != null && job.getNextAttemptAt().isAfter(Instant.now())) return;
        job.setStatus("PROCESSING");
        job.setAttempts(job.getAttempts() + 1);
        job.setUpdatedAt(Instant.now());
        logs.save(job);
        try {
            if ("EMAIL".equals(job.getChannel())) sendEmail(job);
            else if ("PUSH".equals(job.getChannel())) sendPush(job);
            job.setStatus("DELIVERED");
            job.setDetail(null);
            job.setNextAttemptAt(null);
        } catch (Exception exception) {
            job.setDetail(abbreviate(exception.getMessage()));
            if (job.getAttempts() >= MAX_ATTEMPTS) {
                job.setStatus("FAILED");
                job.setNextAttemptAt(null);
            } else {
                job.setStatus("RETRYING");
                long delaySeconds = job.getAttempts() == 1 ? 30 : 120;
                job.setNextAttemptAt(Instant.now().plus(delaySeconds, ChronoUnit.SECONDS));
            }
        }
        job.setUpdatedAt(Instant.now());
        logs.save(job);
    }

    private void sendEmail(NotificationDeliveryLog job) {
        if (!mailEnabled) throw new IllegalStateException("Dịch vụ email chưa được cấu hình");
        User user = users.getById(job.getRecipientId());
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            throw new IllegalStateException("Người dùng chưa có địa chỉ email");
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(mailFrom);
        message.setTo(user.getEmail());
        message.setSubject(job.getTitle());
        message.setText(job.getPayload());
        mailSender.send(message);
    }

    private void sendPush(NotificationDeliveryLog job) throws Exception {
        List<UserDevice> activeDevices = devices.findByUserIdAndActiveTrue(job.getRecipientId());
        if (activeDevices.isEmpty()) throw new IllegalStateException("Người dùng chưa đăng ký thiết bị");
        for (UserDevice device : activeDevices) {
            pushSender.send(device.getDeviceToken(), job.getTitle(), job.getPayload());
        }
    }

    Map<String, Boolean> capabilities() {
        return Map.of("IN_APP", true, "EMAIL", mailEnabled, "PUSH", pushSender.configured());
    }

    private boolean enabled(String userId, String channel) {
        return preferences.findByUserIdAndChannel(userId, channel)
                .map(NotificationPreference::isEnabled).orElse(false);
    }

    private void logSkipped(String notificationId, String recipientId, String channel, String detail) {
        Instant now = Instant.now();
        logs.save(NotificationDeliveryLog.builder().id(Ids.gen("ndl"))
                .notificationId(notificationId).recipientId(recipientId).channel(channel)
                .status("SKIPPED").attempts(0).detail(detail).createdAt(now).updatedAt(now).build());
    }

    private String abbreviate(String value) {
        if (value == null) return "Không xác định được nguyên nhân";
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }
}
