package com.sse.app.notification;

import com.sse.app.common.Ids;
import com.sse.app.identity.User;
import com.sse.app.identity.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/** Gửi các kênh ngoài ứng dụng; mọi kết quả đều được ghi rõ, không báo thành công giả. */
@Service
class NotificationChannelDispatcher {
    private final NotificationPreferenceRepository preferences;
    private final NotificationDeliveryLogRepository logs;
    private final UserDeviceRepository devices;
    private final UserService users;
    private final JavaMailSender mailSender;
    private final boolean mailEnabled;
    private final String mailFrom;

    NotificationChannelDispatcher(NotificationPreferenceRepository preferences,
                                  NotificationDeliveryLogRepository logs,
                                  UserDeviceRepository devices, UserService users,
                                  JavaMailSender mailSender,
                                  @Value("${sse.mail.enabled:false}") boolean mailEnabled,
                                  @Value("${sse.mail.from:no-reply@smartschool.local}") String mailFrom) {
        this.preferences = preferences;
        this.logs = logs;
        this.devices = devices;
        this.users = users;
        this.mailSender = mailSender;
        this.mailEnabled = mailEnabled;
        this.mailFrom = mailFrom;
    }

    @Async("notificationExecutor")
    public void dispatch(String recipientId, String notificationId, String title, String body) {
        dispatchEmail(recipientId, notificationId, title, body);
        dispatchPush(recipientId, notificationId);
    }

    private void dispatchEmail(String recipientId, String notificationId, String title, String body) {
        if (!enabled(recipientId, "EMAIL")) return;
        User user = users.getById(recipientId);
        if (!mailEnabled) {
            log(notificationId, recipientId, "EMAIL", "SKIPPED", "Dịch vụ email chưa được cấu hình");
            return;
        }
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            log(notificationId, recipientId, "EMAIL", "SKIPPED", "Người dùng chưa có địa chỉ email");
            return;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(mailFrom);
            message.setTo(user.getEmail());
            message.setSubject(title);
            message.setText(body);
            mailSender.send(message);
            log(notificationId, recipientId, "EMAIL", "DELIVERED", null);
        } catch (MailException exception) {
            log(notificationId, recipientId, "EMAIL", "FAILED", exception.getMessage());
        }
    }

    private void dispatchPush(String recipientId, String notificationId) {
        if (!enabled(recipientId, "PUSH")) return;
        List<UserDevice> activeDevices = devices.findByUserIdAndActiveTrue(recipientId);
        String detail = activeDevices.isEmpty()
                ? "Người dùng chưa đăng ký thiết bị"
                : "Chưa cấu hình nhà cung cấp push; có " + activeDevices.size() + " thiết bị đang hoạt động";
        log(notificationId, recipientId, "PUSH", "SKIPPED", detail);
    }

    private boolean enabled(String userId, String channel) {
        return preferences.findByUserIdAndChannel(userId, channel)
                .map(NotificationPreference::isEnabled).orElse(false);
    }

    private void log(String notificationId, String recipientId, String channel, String status, String detail) {
        logs.save(NotificationDeliveryLog.builder().id(Ids.gen("ndl"))
                .notificationId(notificationId).recipientId(recipientId).channel(channel)
                .status(status).attempts(1).detail(detail).createdAt(Instant.now()).build());
    }
}
