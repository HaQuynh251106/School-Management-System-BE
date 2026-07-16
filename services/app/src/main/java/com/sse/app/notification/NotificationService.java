package com.sse.app.notification;

import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.notification.NotificationDtos.*;
import com.sse.app.identity.UserDto;
import com.sse.app.identity.UserService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * E2: Điều phối thông báo (bản đồng bộ, in-app). Trong kiến trúc đầy đủ, các service
 * khác phát event lên RabbitMQ và service này consume; ở monolith ta gọi trực tiếp.
 */
@Service
public class NotificationService {

    private final NotificationRepository notifications;
    private final NotificationTemplateRepository templates;
    private final AnnouncementRepository announcements;
    private final UserService users;
    private final NotificationPreferenceRepository preferences;
    private final NotificationDeliveryLogRepository deliveryLogs;
    private final UserDeviceRepository devices;
    private final NotificationChannelDispatcher dispatcher;

    public NotificationService(NotificationRepository notifications,
                               NotificationTemplateRepository templates,
                               AnnouncementRepository announcements,
                               UserService users,
                               NotificationPreferenceRepository preferences,
                               NotificationDeliveryLogRepository deliveryLogs,
                               UserDeviceRepository devices,
                               NotificationChannelDispatcher dispatcher) {
        this.notifications = notifications;
        this.templates = templates;
        this.announcements = announcements;
        this.users = users;
        this.preferences = preferences;
        this.deliveryLogs = deliveryLogs;
        this.devices = devices;
        this.dispatcher = dispatcher;
    }

    // ---------- Phát thông báo in-app ----------

    public Notification notifyUser(String recipientId, String type, String title, String body,
                                   String refType, String refId) {
        Notification notification = null;
        if (channelEnabled(recipientId, "IN_APP")) {
            notification = notifications.save(Notification.builder()
                    .id(Ids.gen("noti")).recipientId(recipientId).type(type)
                    .title(title).body(body).read(false)
                    .refType(refType).refId(refId).createdAt(Instant.now()).build());
            deliveryLogs.save(NotificationDeliveryLog.builder().id(Ids.gen("ndl"))
                    .notificationId(notification.getId()).recipientId(recipientId).channel("IN_APP")
                    .status("DELIVERED").attempts(1).createdAt(Instant.now()).build());
        }
        dispatcher.dispatch(recipientId, notification == null ? null : notification.getId(), title, body);
        return notification;
    }

    public void notifyUsers(List<String> recipientIds, String type, String title, String body,
                            String refType, String refId) {
        for (String id : recipientIds) notifyUser(id, type, title, body, refType, refId);
    }

    /** 2.5/2.6: bắn cho tất cả phụ huynh của một học sinh. */
    public void notifyParentsOfStudent(String studentId, String type, String title, String body,
                                       String refType, String refId) {
        notifyUsers(users.parentIdsOf(studentId), type, title, body, refType, refId);
    }

    // ---------- Hộp thư in-app ----------

    public List<Notification> inbox(String recipientId, boolean unreadOnly) {
        return unreadOnly
                ? notifications.findByRecipientIdAndReadIsFalseOrderByCreatedAtDesc(recipientId)
                : notifications.findByRecipientIdOrderByCreatedAtDesc(recipientId);
    }

    public long unreadCount(String recipientId) {
        return notifications.countByRecipientIdAndReadIsFalse(recipientId);
    }

    public Notification markRead(String id, String recipientId) {
        Notification n = notifications.findById(id).orElseThrow(() -> ApiException.notFound("Thông báo"));
        if (!recipientId.equals(n.getRecipientId())) throw ApiException.forbidden("Không phải thông báo của bạn");
        n.setRead(true);
        return notifications.save(n);
    }

    public void markAllRead(String recipientId) {
        var list = notifications.findByRecipientIdAndReadIsFalseOrderByCreatedAtDesc(recipientId);
        list.forEach(n -> n.setRead(true));
        notifications.saveAll(list);
    }

    public List<NotificationPreference> preferences(String userId) {
        for (String channel : List.of("IN_APP", "PUSH", "EMAIL")) {
            if (preferences.findByUserIdAndChannel(userId, channel).isEmpty()) {
                preferences.save(NotificationPreference.builder().id(Ids.gen("np"))
                        .userId(userId).channel(channel).enabled("IN_APP".equals(channel))
                        .updatedAt(Instant.now()).build());
            }
        }
        return preferences.findByUserIdOrderByChannel(userId);
    }

    public NotificationPreference updatePreference(String userId, UpdatePreferenceRequest request) {
        String channel = request.channel().trim().toUpperCase();
        if (!List.of("IN_APP", "PUSH", "EMAIL").contains(channel)) {
            throw ApiException.badRequest("Kênh thông báo không hợp lệ");
        }
        NotificationPreference preference = preferences.findByUserIdAndChannel(userId, channel)
                .orElseGet(() -> NotificationPreference.builder().id(Ids.gen("np"))
                        .userId(userId).channel(channel).build());
        preference.setEnabled(!Boolean.FALSE.equals(request.enabled()));
        preference.setUpdatedAt(Instant.now());
        return preferences.save(preference);
    }

    public List<NotificationDeliveryLog> deliveryLogs() {
        return deliveryLogs.findTop200ByOrderByCreatedAtDesc();
    }

    public List<UserDevice> devices(String userId) {
        return devices.findByUserIdAndActiveTrue(userId);
    }

    public UserDevice registerDevice(String userId, RegisterDeviceRequest request) {
        UserDevice device = devices.findByDeviceToken(request.deviceToken().trim())
                .orElseGet(() -> UserDevice.builder().id(Ids.gen("dev"))
                        .deviceToken(request.deviceToken().trim()).createdAt(Instant.now()).build());
        device.setUserId(userId);
        device.setPlatform(request.platform().toUpperCase());
        device.setActive(true);
        device.setUpdatedAt(Instant.now());
        return devices.save(device);
    }

    public void deactivateDevice(String userId, String deviceId) {
        UserDevice device = devices.findById(deviceId).orElseThrow(() -> ApiException.notFound("Thiết bị"));
        if (!userId.equals(device.getUserId())) throw ApiException.forbidden("Thiết bị không thuộc tài khoản của bạn");
        device.setActive(false);
        device.setUpdatedAt(Instant.now());
        devices.save(device);
    }

    private boolean channelEnabled(String userId, String channel) {
        return preferences.findByUserIdAndChannel(userId, channel)
                .map(NotificationPreference::isEnabled).orElse(true);
    }

    // ---------- Announcements ----------

    public List<Announcement> announcementsFor(String role) {
        return announcements.findAllByOrderByCreatedAtDesc().stream()
                .filter(a -> a.getAudience() == null
                        || "ALL".equalsIgnoreCase(a.getAudience())
                        || role.equalsIgnoreCase(a.getAudience()))
                .toList();
    }

    public Announcement createAnnouncement(CreateAnnouncementRequest r, String createdBy) {
        String audience = r.audience() == null ? "ALL" : r.audience().toUpperCase();
        Announcement a = announcements.save(Announcement.builder()
                .id(r.id() == null || r.id().isBlank() ? Ids.gen("an") : r.id())
                .title(r.title()).body(r.body()).audience(audience)
                .createdBy(createdBy).createdAt(Instant.now()).build());

        // Fan-out in-app cho đối tượng nhận (đồng bộ — bản RabbitMQ sẽ async hoá).
        List<String> recipients = resolveAudience(audience);
        notifyUsers(recipients, "ANNOUNCEMENT", a.getTitle(), a.getBody(), "ANNOUNCEMENT", a.getId());
        return a;
    }

    private List<String> resolveAudience(String audience) {
        if (audience.startsWith("CLASS:")) {
            String classId = audience.substring("CLASS:".length());
            return users.list("STUDENT", null, classId).stream().map(UserDto::id).toList();
        }
        return switch (audience) {
            case "PARENT", "STUDENT", "TEACHER", "ADMIN" -> users.userIdsByRole(audience);
            default -> users.allUserIds();
        };
    }

    /** Seed raw (không fan-out) — dùng bởi DataSeeder. */
    public void seed(List<Announcement> anns, List<NotificationTemplate> tpls) {
        announcements.saveAll(anns);
        templates.saveAll(tpls);
    }

    // ---------- Templates (E2/S12) ----------

    public List<NotificationTemplate> listTemplates() { return templates.findAll(); }

    public NotificationTemplate createTemplate(CreateTemplateRequest r) {
        return templates.save(NotificationTemplate.builder()
                .id(r.id() == null || r.id().isBlank() ? Ids.gen("tpl") : r.id())
                .code(r.code()).name(r.name())
                .channel(r.channel() == null ? "IN_APP" : r.channel())
                .titleTemplate(r.titleTemplate()).bodyTemplate(r.bodyTemplate())
                .active(r.active() == null || r.active()).build());
    }
}
