package com.sse.app.notification;

import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.event.DomainEvent;
import com.sse.app.identity.UserDto;
import com.sse.app.identity.UserService;
import com.sse.app.notification.NotificationDtos.*;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** E2: điều phối thông báo in-app; worker consume DomainEvent từ RabbitMQ. */
@Service
public class NotificationService {

    private final NotificationRepository notifications;
    private final NotificationTemplateRepository templates;
    private final NotificationDeliveryLogRepository deliveryLogs;
    private final AnnouncementRepository announcements;
    private final UserService users;

    public NotificationService(NotificationRepository notifications,
                               NotificationTemplateRepository templates,
                               NotificationDeliveryLogRepository deliveryLogs,
                               AnnouncementRepository announcements,
                               UserService users) {
        this.notifications = notifications;
        this.templates = templates;
        this.deliveryLogs = deliveryLogs;
        this.announcements = announcements;
        this.users = users;
    }

    // ---------- Phát thông báo in-app ----------

    public Notification notifyUser(String recipientId, String type, String title, String body,
                                   String refType, String refId) {
        Notification n = notifications.save(Notification.builder()
                .id(Ids.gen("noti")).recipientId(recipientId).type(type)
                .channel("IN_APP")
                .title(title).body(body).read(false)
                .refType(refType).refId(refId)
                .status("SENT").attemptCount(1).sentAt(Instant.now())
                .createdAt(Instant.now()).build());
        deliveryLogs.save(NotificationDeliveryLog.builder()
                .id(Ids.gen("ndl"))
                .notificationId(n.getId())
                .attemptNo(1)
                .status("SENT")
                .providerResponse("IN_APP persisted")
                .attemptedAt(Instant.now())
                .build());
        return n;
    }

    public void notifyUsers(List<String> recipientIds, String type, String title, String body,
                            String refType, String refId) {
        for (String id : recipientIds) notifyUser(id, type, title, body, refType, refId);
    }

    /** D2/C5: gửi cho tất cả phụ huynh đang liên kết với một học sinh. */
    public void notifyParentsOfStudent(String studentId, String type, String title, String body,
                                       String refType, String refId) {
        notifyUsers(users.parentIdsOf(studentId), type, title, body, refType, refId);
    }

    public void handleDomainEvent(DomainEvent event) {
        Map<String, Object> p = event.payload();
        switch (event.name()) {
            case "identity.user.login" -> notifyUser(
                    event.entityId(), "SYSTEM", "Đăng nhập mới",
                    "Tài khoản của bạn vừa đăng nhập thành công.",
                    "USER", event.entityId());
            case "identity.password.reset_requested" -> notifyUser(
                    event.entityId(), "SYSTEM", "Yêu cầu đặt lại mật khẩu",
                    "Hệ thống đã nhận yêu cầu đặt lại mật khẩu cho tài khoản của bạn.",
                    "USER", event.entityId());
            case "identity.password.reset_completed" -> notifyUser(
                    event.entityId(), "SYSTEM", "Mật khẩu đã được cập nhật",
                    "Mật khẩu của bạn đã được đặt lại, mọi refresh token cũ đã bị thu hồi.",
                    "USER", event.entityId());
            case "academic.timetable.changed" -> notifyClassStudentsAndParents(
                    asString(p.get("classId")), "TIMETABLE", "Thời khóa biểu được cập nhật",
                    "Thời khóa biểu lớp đã có thay đổi.", "TIMETABLE", event.entityId());
            case "academic.attendance.absent" -> notifyParentsOfStudent(
                    asString(p.get("studentId")),
                    "ATTENDANCE_ALERT",
                    "Cảnh báo chuyên cần",
                    asString(p.get("message")),
                    "ATTENDANCE",
                    event.entityId());
            case "academic.grade.published", "academic.grade.changed" -> {
                String studentId = asString(p.get("studentId"));
                String title = "academic.grade.changed".equals(event.name())
                        ? "Điểm được cập nhật" : "Có điểm mới";
                String body = asString(p.get("message"));
                notifyUser(studentId, "GRADE", title, body, "GRADE", event.entityId());
                notifyParentsOfStudent(studentId, "GRADE", title, body, "GRADE", event.entityId());
            }
            case "academic.assignment.published" -> {
                String classId = asString(p.get("classId"));
                String title = "Bài tập mới: " + asString(p.get("title"));
                String body = asString(p.get("message"));
                notifyClassStudentsAndParents(classId, "ASSIGNMENT", title, body, "ASSIGNMENT", event.entityId());
            }
            case "academic.submission.graded" -> {
                String studentId = asString(p.get("studentId"));
                String body = asString(p.get("message"));
                notifyUser(studentId, "ASSIGNMENT", "Bài tập đã được chấm", body, "SUBMISSION", event.entityId());
                notifyParentsOfStudent(studentId, "ASSIGNMENT", "Bài tập đã được chấm", body, "SUBMISSION", event.entityId());
            }
            case "finance.invoice.issued" -> notifyParentsOfStudent(
                    asString(p.get("studentId")), "INVOICE", "Có hóa đơn mới",
                    asString(p.get("message")), "INVOICE", event.entityId());
            case "finance.invoice.paid" -> notifyUser(
                    asString(p.get("parentId")), "PAYMENT", "Thanh toán thành công",
                    asString(p.get("message")), "INVOICE", event.entityId());
            default -> {
                // Keep unknown events observable later without failing core flows.
            }
        }
    }

    private void notifyClassStudentsAndParents(String classId, String type, String title, String body,
                                               String refType, String refId) {
        if (classId == null || classId.isBlank()) return;
        List<UserDto> students = users.list("STUDENT", null, classId);
        for (UserDto student : students) {
            notifyUser(student.id(), type, title, body, refType, refId);
            notifyParentsOfStudent(student.id(), type, title, body, refType, refId);
        }
    }

    private String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
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

    /** Seed raw, không fan-out. */
    public void seed(List<Announcement> anns, List<NotificationTemplate> tpls) {
        announcements.saveAll(anns);
        templates.saveAll(tpls);
    }

    // ---------- Templates (E2/S12) ----------

    public List<NotificationTemplate> listTemplates() {
        return templates.findAll();
    }

    public NotificationTemplate createTemplate(CreateTemplateRequest r) {
        return templates.save(NotificationTemplate.builder()
                .id(r.id() == null || r.id().isBlank() ? Ids.gen("tpl") : r.id())
                .code(r.code()).name(r.name())
                .channel(r.channel() == null ? "IN_APP" : r.channel())
                .titleTemplate(r.titleTemplate()).bodyTemplate(r.bodyTemplate())
                .active(r.active() == null || r.active()).build());
    }
}
