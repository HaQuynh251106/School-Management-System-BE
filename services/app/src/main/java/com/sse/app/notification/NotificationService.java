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

    public NotificationService(NotificationRepository notifications,
                               NotificationTemplateRepository templates,
                               AnnouncementRepository announcements,
                               UserService users) {
        this.notifications = notifications;
        this.templates = templates;
        this.announcements = announcements;
        this.users = users;
    }

    // ---------- Phát thông báo in-app ----------

    public Notification notifyUser(String recipientId, String type, String title, String body,
                                   String refType, String refId) {
        return notifications.save(Notification.builder()
                .id(Ids.gen("noti")).recipientId(recipientId).type(type)
                .title(title).body(body).read(false)
                .refType(refType).refId(refId).createdAt(Instant.now()).build());
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
