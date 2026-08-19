package com.sse.app.notification;

import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.event.DomainEvent;
import com.sse.app.identity.UserDto;
import com.sse.app.identity.UserService;
import com.sse.app.notification.NotificationDtos.*;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.PageRequest;
import com.sse.app.common.PageResponse;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.List;
import java.util.Map;

/** E2: điều phối thông báo in-app; worker consume DomainEvent từ RabbitMQ. */
@Service
public class NotificationService {

    private static final List<String> FINANCE_NOTIFICATION_TYPES = List.of("INVOICE", "PAYMENT");

    private final NotificationRepository notifications;
    private final NotificationTemplateRepository templates;
    private final NotificationDeliveryLogRepository deliveryLogs;
    private final AnnouncementRepository announcements;
    private final UserNotificationPreferenceRepository preferences;
    private final UserService users;
    private final NotificationChannelDispatcher channelDispatcher;

    public NotificationService(NotificationRepository notifications,
                               NotificationTemplateRepository templates,
                               NotificationDeliveryLogRepository deliveryLogs,
                               AnnouncementRepository announcements,
                               UserNotificationPreferenceRepository preferences,
                               UserService users,
                               NotificationChannelDispatcher channelDispatcher) {
        this.notifications = notifications;
        this.templates = templates;
        this.deliveryLogs = deliveryLogs;
        this.announcements = announcements;
        this.preferences = preferences;
        this.users = users;
        this.channelDispatcher = channelDispatcher;
    }

    // ---------- Phát thông báo in-app ----------

    public Notification notifyUser(String recipientId, String type, String title, String body,
                                   String refType, String refId) {
        Notification notification = notifyInAppOnly(recipientId, type, title, body, refType, refId);
        dispatchExternalIfEnabled(recipientId, type, title, body, refType, refId);
        return notification;
    }

    public Notification notifyUserWithRequiredEmail(
            String recipientId, String type, String title, String body,
            String refType, String refId) {
        Notification notification = notifyInAppOnly(
                recipientId, type, title, body, refType, refId);
        channelDispatcher.dispatch(recipientId, normalize(type), "EMAIL",
                title, body, refType, refId, deepLink(refType, refId), groupKey(type));
        if (isEnabled(recipientId, type, "PUSH")) {
            boolean pushEnabled = preferences.findByUserIdAndNotificationTypeAndChannel(
                            recipientId, normalize(type), "PUSH")
                    .or(() -> preferences.findByUserIdAndNotificationTypeAndChannel(
                            recipientId, "ALL", "PUSH"))
                    .map(UserNotificationPreference::isEnabled).orElse(false);
            if (pushEnabled) {
                channelDispatcher.dispatch(recipientId, normalize(type), "PUSH",
                        title, body, refType, refId, deepLink(refType, refId), groupKey(type));
            }
        }
        return notification;
    }

    private Notification notifyInAppOnly(String recipientId, String type, String title, String body,
                                         String refType, String refId) {
        if (!isEnabled(recipientId, type, "IN_APP")) return null;
        String groupKey = groupKey(type);
        Notification n = notifications.save(Notification.builder()
                .id(Ids.gen("noti")).recipientId(recipientId).type(type)
                .channel("IN_APP")
                .title(title).body(body).read(false)
                .refType(refType).refId(refId)
                .deepLink(deepLink(refType, refId))
                .groupKey(groupKey)
                .status("SENT").attemptCount(1).sentAt(Instant.now())
                .createdAt(Instant.now()).build());
        deliveryLogs.save(NotificationDeliveryLog.builder()
                .id(Ids.gen("ndl"))
                .notificationId(n.getId())
                .channel("IN_APP")
                .provider("DATABASE")
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
            case "identity.password.reset_requested" -> {
                String resetUrl = asString(p.get("resetUrl"));
                String title = "Đặt lại mật khẩu Trường học số";
                notifyInAppOnly(event.entityId(), "SYSTEM", title,
                        "Yêu cầu đặt lại mật khẩu đã được gửi tới email của bạn. Liên kết có hiệu lực trong 30 phút.",
                        "USER", event.entityId());
                String emailBody = "Xin chào " + asString(p.get("username")) + ",\n\n"
                        + "Mở liên kết dưới đây để đặt lại mật khẩu:\n" + resetUrl + "\n\n"
                        + "Liên kết chỉ sử dụng một lần và hết hạn sau 30 phút. "
                        + "Nếu bạn không yêu cầu thao tác này, hãy bỏ qua email.";
                channelDispatcher.dispatch(event.entityId(), "PASSWORD_RESET", "EMAIL",
                        title, emailBody, "PASSWORD_RESET", event.entityId(),
                        resetUrl, "SECURITY");
            }
            case "identity.password.reset_completed" -> notifyUser(
                    event.entityId(), "SYSTEM", "Mật khẩu đã được cập nhật",
                    "Mật khẩu của bạn đã được đặt lại, mọi refresh token cũ đã bị thu hồi.",
                    "USER", event.entityId());
            case "academic.timetable.changed" -> notifyClassStudentsAndParents(
                    asString(p.get("classId")), "TIMETABLE", "Thời khóa biểu được cập nhật",
                    "Thời khóa biểu lớp đã có thay đổi.", "TIMETABLE", event.entityId());
            case "academic.timetable.published" -> {
                String classId = asString(p.get("classId"));
                String body = asString(p.get("message"));
                notifyClassStudentsAndParents(classId, "TIMETABLE",
                        "Thời khóa biểu mới", body,
                        "TIMETABLE", event.entityId());
                asStringList(p.get("teacherIds")).forEach(teacherId ->
                        notifyUser(teacherId, "TIMETABLE",
                                "Lịch giảng dạy mới", body,
                                "TIMETABLE", event.entityId()));
            }
            case "academic.timetable.makeup_approved" -> {
                String classId = asString(p.get("classId"));
                String body = asString(p.get("message"));
                notifyClassStudentsAndParents(classId, "TIMETABLE",
                        "Lịch học bù", body,
                        "TIMETABLE", event.entityId());
                asStringList(p.get("teacherIds")).forEach(teacherId ->
                        notifyUser(teacherId, "TIMETABLE",
                                "Lịch dạy bù", body,
                                "TIMETABLE", event.entityId()));
            }
            case "academic.exam_schedule.published" -> {
                String periodName = asString(p.get("periodName"));
                boolean updated = Boolean.TRUE.equals(p.get("updated"));
                String studentTitle = updated ? "Lịch thi đã được cập nhật" : "Đã có lịch thi mới";
                String body = "Lịch thi " + periodName
                        + (updated ? " đã được cập nhật và phát hành lại." : " đã được phát hành.")
                        + " Vui lòng mở mục Lịch thi để xem ngày, giờ và phòng thi.";
                LinkedHashSet<String> parentIds = new LinkedHashSet<>();
                asStringList(p.get("studentIds")).forEach(studentId -> {
                    notifyUserWithRequiredEmail(studentId, "EXAM", studentTitle, body,
                            "EXAM_PERIOD", event.entityId());
                    parentIds.addAll(users.parentIdsOf(studentId));
                });
                parentIds.forEach(parentId ->
                        notifyUserWithRequiredEmail(parentId, "EXAM", studentTitle, body,
                                "EXAM_PERIOD", event.entityId()));
                asStringList(p.get("teacherIds")).forEach(teacherId ->
                        notifyUser(teacherId, "EXAM",
                                updated ? "Lịch coi thi đã được cập nhật" : "Lịch coi thi đã được phân công", body,
                                "EXAM_PERIOD", event.entityId()));
            }
            case "academic.education_plan.published" -> {
                String body = asString(p.get("message"));
                asStringList(p.get("classIds")).forEach(classId ->
                        notifyClassStudentsAndParents(classId, "ACADEMIC_PLAN",
                                "Kế hoạch giáo dục mới", body,
                                "EDUCATION_PLAN", event.entityId()));
            }
            case "academic.education_plan.submitted" -> users.list("ADMIN", null, null)
                    .forEach(admin -> notifyUser(admin.id(), "ACADEMIC_PLAN",
                            "Kế hoạch giáo dục chờ rà soát", asString(p.get("message")),
                            "EDUCATION_PLAN", event.entityId()));
            case "academic.education_plan.revision_required" -> notifyUser(
                    asString(p.get("targetUserId")), "ACADEMIC_PLAN",
                    "Kế hoạch giáo dục cần chỉnh sửa", asString(p.get("message")),
                    "EDUCATION_PLAN", event.entityId());
            case "academic.education_plan.approved" -> notifyUser(
                    asString(p.get("targetUserId")), "ACADEMIC_PLAN",
                    "Kế hoạch giáo dục đã được phê duyệt", asString(p.get("message")),
                    "EDUCATION_PLAN", event.entityId());
            case "academic.teacher_specialty.changed" -> notifyUser(
                    asString(p.get("teacherId")),
                    "ACADEMIC_PLAN",
                    "Chuyên môn giảng dạy đã được cập nhật",
                    asString(p.get("message")),
                    "TEACHER_SPECIALTY",
                    event.entityId());
            case "academic.attendance.absent" -> notifyParentsOfStudent(
                    asString(p.get("studentId")),
                    "ATTENDANCE_ALERT",
                    "Cảnh báo chuyên cần",
                    asString(p.get("message")),
                    "ATTENDANCE",
                    event.entityId());
            case "academic.attendance.repeated_violation" -> {
                String studentId = asString(p.get("studentId"));
                notifyUser(studentId, "ATTENDANCE_ALERT",
                        "Cảnh báo chuyên cần lặp lại", asString(p.get("message")),
                        "ATTENDANCE", event.entityId());
                notifyParentsOfStudent(studentId, "ATTENDANCE_ALERT",
                        "Cảnh báo chuyên cần lặp lại", asString(p.get("message")),
                        "ATTENDANCE", event.entityId());
            }
            case "academic.attendance.excuse_reviewed" -> {
                String studentId = asString(p.get("studentId"));
                notifyUser(studentId, "ATTENDANCE_ALERT",
                        "Kết quả đơn xin phép", asString(p.get("message")),
                        "ATTENDANCE", event.entityId());
                notifyParentsOfStudent(studentId, "ATTENDANCE_ALERT",
                        "Kết quả đơn xin phép", asString(p.get("message")),
                        "ATTENDANCE", event.entityId());
            }
            case "academic.grade.published", "academic.grade.changed" -> {
                String studentId = asString(p.get("studentId"));
                String title = "academic.grade.changed".equals(event.name())
                        ? "Điểm được cập nhật" : "Có điểm mới";
                String body = asString(p.get("message"));
                notifyUser(studentId, "GRADE", title, body, "GRADE", event.entityId());
                notifyParentsOfStudent(studentId, "GRADE", title, body, "GRADE", event.entityId());
            }
            case "academic.homeroom_remark.published" -> {
                String studentId = asString(p.get("studentId"));
                String body = asString(p.get("message"));
                notifyUser(studentId, "HOMEROOM_REMARK", "Nhận xét mới từ giáo viên chủ nhiệm",
                        body, "HOMEROOM_REMARK", event.entityId());
                notifyParentsOfStudent(studentId, "HOMEROOM_REMARK",
                        "GVCN đã nhận xét về học sinh", body,
                        "HOMEROOM_REMARK", event.entityId());
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
            case "academic.submission.resubmission_requested" -> {
                String studentId = asString(p.get("studentId"));
                String body = asString(p.get("message"));
                notifyUser(studentId, "ASSIGNMENT", "Yêu cầu nộp lại bài",
                        body, "SUBMISSION", event.entityId());
                notifyParentsOfStudent(studentId, "ASSIGNMENT",
                        "Giáo viên yêu cầu nộp lại bài", body,
                        "SUBMISSION", event.entityId());
            }
            case "academic.assignment.deadline_reminder" -> {
                String studentId = asString(p.get("studentId"));
                String body = asString(p.get("message"));
                notifyUser(studentId, "ASSIGNMENT", "Sắp đến hạn nộp bài",
                        body, "ASSIGNMENT", event.entityId());
                notifyParentsOfStudent(studentId, "ASSIGNMENT",
                        "Con chưa nộp bài tập", body,
                        "ASSIGNMENT", event.entityId());
            }
            case "academic.year_result.published" -> {
                String studentId = asString(p.get("studentId"));
                String body = asString(p.get("message"));
                notifyUser(studentId, "YEAR_RESULT", "Đã có kết quả cuối năm",
                        body, "YEAR_RESULT", event.entityId());
                notifyParentsOfStudent(studentId, "YEAR_RESULT", "Đã có kết quả cuối năm",
                        body, "YEAR_RESULT", event.entityId());
            }
            case "academic.year_result.withdrawn" -> {
                String studentId = asString(p.get("studentId"));
                String body = asString(p.get("message"));
                notifyUser(studentId, "YEAR_RESULT", "Kết quả cuối năm đang được rà soát",
                        body, "YEAR_RESULT", event.entityId());
                notifyParentsOfStudent(studentId, "YEAR_RESULT",
                        "Kết quả cuối năm đang được rà soát",
                        body, "YEAR_RESULT", event.entityId());
            }
            case "finance.invoice.issued" -> {
                String studentId = asString(p.get("studentId"));
                String body = "Vui lòng vào Học phí để xem chi tiết và thanh toán.";
                notifyUser(studentId, "INVOICE", "Có khoản thu mới", body, "INVOICE", event.entityId());
                notifyParentsOfStudent(studentId, "INVOICE", "Có khoản thu mới", body, "INVOICE", event.entityId());
            }
            case "finance.invoice.reminder" -> {
                String studentId = asString(p.get("studentId"));
                String body = asString(p.get("message"));
                notifyUser(studentId, "INVOICE", "Nhắc thanh toán học phí", body, "INVOICE", event.entityId());
                notifyParentsOfStudent(studentId, "INVOICE", "Nhắc thanh toán học phí", body, "INVOICE", event.entityId());
            }
            case "finance.invoice.recalled" -> {
                String studentId = asString(p.get("studentId"));
                String body = asString(p.get("message"));
                notifyUser(studentId, "INVOICE", "Hóa đơn được thu hồi", body, "INVOICE", event.entityId());
                notifyParentsOfStudent(studentId, "INVOICE", "Hóa đơn được thu hồi", body, "INVOICE", event.entityId());
            }
            case "finance.invoice.paid" -> notifyUser(
                    asString(p.get("parentId")), "PAYMENT", "Thanh toán thành công",
                    asString(p.get("message")), "INVOICE", event.entityId());
            case "finance.payment.refunded" -> {
                String studentId = asString(p.get("studentId"));
                String body = asString(p.get("message"));
                notifyUser(studentId, "PAYMENT", "Đã hoàn tiền", body,
                        "PAYMENT_REFUND", event.entityId());
                notifyParentsOfStudent(studentId, "PAYMENT", "Đã hoàn tiền", body,
                        "PAYMENT_REFUND", event.entityId());
            }
            case "finance.payment.proof_submitted" -> notifyUser(
                    event.actorUserId(), "PAYMENT", "Có biên lai chuyển khoản mới",
                    asString(p.get("studentName")) + " - " + asString(p.get("invoiceCode"))
                            + ": " + asString(p.get("message")),
                    "PAYMENT_PROOF", event.entityId());
            case "finance.payment.proof_reviewed" -> {
                String title = "APPROVED".equals(asString(p.get("status")))
                        ? "Biên lai đã được xác nhận" : "Yêu cầu thanh toán lại";
                notifyUser(event.actorUserId(), "PAYMENT", title,
                        asString(p.get("invoiceCode")) + ": " + asString(p.get("message")),
                        "PAYMENT_PROOF", event.entityId());
            }
            case "finance.payment.failed" -> {
                String studentId = asString(p.get("studentId"));
                String body = asString(p.get("message"));
                notifyUser(studentId, "PAYMENT", "Thanh toán không thành công", body, "INVOICE", event.entityId());
                notifyParentsOfStudent(studentId, "PAYMENT", "Thanh toán không thành công", body, "INVOICE", event.entityId());
            }
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
                ? notifications.findByRecipientIdAndChannelAndReadIsFalseOrderByCreatedAtDesc(
                        recipientId, "IN_APP")
                : notifications.findByRecipientIdAndChannelOrderByCreatedAtDesc(
                        recipientId, "IN_APP");
    }

    public PageResponse<Notification> inboxPage(
            String recipientId, boolean unreadOnly, int page, int size) {
        PageRequest pageable = PageRequest.of(validPage(page), validSize(size));
        return PageResponse.from(unreadOnly
                ? notifications.findByRecipientIdAndChannelAndReadIsFalseOrderByCreatedAtDesc(
                        recipientId, "IN_APP", pageable)
                : notifications.findByRecipientIdAndChannelOrderByCreatedAtDesc(
                        recipientId, "IN_APP", pageable));
    }

    public PageResponse<NotificationDeliveryLog> deliveryPage(int page, int size) {
        return PageResponse.from(deliveryLogs.findAllByOrderByAttemptedAtDesc(
                PageRequest.of(validPage(page), validSize(size))));
    }

    private int validPage(int page) {
        if (page < 0) throw ApiException.badRequest("Số trang không được âm");
        return page;
    }

    private int validSize(int size) {
        if (size < 1 || size > 200) {
            throw ApiException.badRequest("Kích thước trang phải từ 1 đến 200");
        }
        return size;
    }

    public long unreadCount(String recipientId) {
        return notifications.countByRecipientIdAndChannelAndReadIsFalse(recipientId, "IN_APP");
    }

    public long financeUnreadCount(String recipientId) {
        return notifications.countByRecipientIdAndChannelAndReadIsFalseAndTypeIn(
                recipientId, "IN_APP", FINANCE_NOTIFICATION_TYPES);
    }

    public Notification markRead(String id, String recipientId) {
        Notification n = notifications.findById(id).orElseThrow(() -> ApiException.notFound("Thông báo"));
        if (!recipientId.equals(n.getRecipientId())) throw ApiException.forbidden("Không phải thông báo của bạn");
        if (!"IN_APP".equals(n.getChannel())) throw ApiException.forbidden("Không phải thông báo trong ứng dụng");
        n.setRead(true);
        n.setReadAt(Instant.now());
        return notifications.save(n);
    }

    public void markAllRead(String recipientId) {
        var list = notifications.findByRecipientIdAndChannelAndReadIsFalseOrderByCreatedAtDesc(
                recipientId, "IN_APP");
        list.forEach(n -> {
            n.setRead(true);
            n.setReadAt(Instant.now());
        });
        notifications.saveAll(list);
    }

    public void markAllFinanceRead(String recipientId) {
        var list = notifications.findByRecipientIdAndChannelAndReadIsFalseAndTypeInOrderByCreatedAtDesc(
                recipientId, "IN_APP", FINANCE_NOTIFICATION_TYPES);
        list.forEach(n -> {
            n.setRead(true);
            n.setReadAt(Instant.now());
        });
        notifications.saveAll(list);
    }

    public int markGroupRead(String recipientId, String requestedGroupKey) {
        String key = normalize(requestedGroupKey);
        List<Notification> rows =
                notifications.findByRecipientIdAndChannelAndReadIsFalseAndGroupKeyOrderByCreatedAtDesc(
                        recipientId, "IN_APP", key);
        Instant now = Instant.now();
        rows.forEach(row -> {
            row.setRead(true);
            row.setReadAt(now);
        });
        notifications.saveAll(rows);
        return rows.size();
    }

    public List<UserNotificationPreference> preferences(String userId) {
        return preferences.findByUserIdOrderByNotificationTypeAscChannelAsc(userId);
    }

    public UserNotificationPreference updatePreference(
            String userId, UpdatePreferenceRequest request) {
        String type = normalize(request.notificationType());
        String channel = normalize(request.channel());
        if (!List.of("IN_APP", "EMAIL", "PUSH").contains(channel)) {
            throw ApiException.badRequest("Kênh thông báo chỉ nhận IN_APP, EMAIL hoặc PUSH");
        }
        UserNotificationPreference row = preferences
                .findByUserIdAndNotificationTypeAndChannel(userId, type, channel)
                .orElseGet(() -> UserNotificationPreference.builder()
                        .id(Ids.gen("unp"))
                        .userId(userId)
                        .notificationType(type)
                        .channel(channel)
                        .build());
        row.setEnabled(request.enabled());
        row.setUpdatedAt(Instant.now());
        return preferences.save(row);
    }

    public List<NotificationDeliveryLog> deliveryAttempts(String notificationId) {
        notifications.findById(notificationId)
                .orElseThrow(() -> ApiException.notFound("Thông báo"));
        return deliveryLogs.findByNotificationIdOrderByAttemptNoAsc(notificationId);
    }

    public List<NotificationDeliveryLog> latestDeliveryAttempts() {
        return deliveryLogs.findTop200ByOrderByAttemptedAtDesc();
    }

    public List<Notification> failedNotifications() {
        return notifications.findByStatusOrderByCreatedAtDesc("FAILED");
    }

    public NotificationDtos.NotificationOperationsSummary operationsSummary() {
        long total = notifications.count();
        long queued = notifications.countByStatus("QUEUED");
        long sent = notifications.countByStatus("SENT");
        long failed = notifications.countByStatus("FAILED");
        long retrying = notifications.countByStatus("RETRYING");
        long attempts = deliveryLogs.count();
        long successfulAttempts = deliveryLogs.countByStatus("SENT");
        long failedAttempts = deliveryLogs.countByStatus("FAILED");
        double failureRate = attempts == 0
                ? 0.0 : Math.round(failedAttempts * 10000.0 / attempts) / 100.0;
        return new NotificationDtos.NotificationOperationsSummary(
                total, queued, sent, failed, retrying,
                attempts, successfulAttempts, failedAttempts, failureRate,
                java.util.Map.of(
                        "IN_APP", notifications.countByChannel("IN_APP"),
                        "EMAIL", notifications.countByChannel("EMAIL"),
                        "PUSH", notifications.countByChannel("PUSH")),
                Instant.now());
    }

    public Notification retry(String notificationId) {
        Notification row = notifications.findById(notificationId)
                .orElseThrow(() -> ApiException.notFound("Thông báo"));
        if (!"FAILED".equals(row.getStatus())) {
            throw ApiException.conflict("Chỉ thông báo FAILED mới được gửi lại");
        }
        if (!"IN_APP".equals(row.getChannel())) {
            return channelDispatcher.retry(row);
        }
        int attempt = row.getAttemptCount() == null ? 1 : row.getAttemptCount() + 1;
        row.setStatus("SENT");
        row.setAttemptCount(attempt);
        row.setSentAt(Instant.now());
        row.setErrorMessage(null);
        notifications.save(row);
        deliveryLogs.save(NotificationDeliveryLog.builder()
                .id(Ids.gen("ndl"))
                .notificationId(row.getId())
                .channel("IN_APP")
                .provider("DATABASE")
                .attemptNo(attempt)
                .status("SENT")
                .providerResponse("Manual IN_APP retry")
                .attemptedAt(Instant.now())
                .build());
        return row;
    }

    private boolean isEnabled(String recipientId, String type, String channel) {
        return java.util.Optional.ofNullable(
                        preferences.findByUserIdAndNotificationTypeAndChannel(
                                recipientId, normalize(type), normalize(channel)))
                .orElse(java.util.Optional.empty())
                .or(() -> preferences.findByUserIdAndNotificationTypeAndChannel(
                        recipientId, "ALL", normalize(channel)))
                .map(UserNotificationPreference::isEnabled)
                .orElse(true);
    }

    private void dispatchExternalIfEnabled(String recipientId, String type, String title,
                                           String body, String refType, String refId) {
        for (String channel : List.of("EMAIL", "PUSH")) {
            boolean enabled = preferences.findByUserIdAndNotificationTypeAndChannel(
                            recipientId, normalize(type), channel)
                    .or(() -> preferences.findByUserIdAndNotificationTypeAndChannel(
                            recipientId, "ALL", channel))
                    .map(UserNotificationPreference::isEnabled).orElse(false);
            if (enabled) {
                channelDispatcher.dispatch(recipientId, normalize(type), channel,
                        title, body, refType, refId, deepLink(refType, refId), groupKey(type));
            }
        }
    }

    private String groupKey(String type) {
        String normalized = normalize(type);
        if (FINANCE_NOTIFICATION_TYPES.contains(normalized)
                || normalized.startsWith("REFUND")) return "FINANCE";
        if (normalized.startsWith("ATTENDANCE")) return "ATTENDANCE";
        if (normalized.startsWith("GRADE") || normalized.startsWith("YEAR_RESULT")) {
            return "ACADEMIC";
        }
        return normalized;
    }

    private String deepLink(String refType, String refId) {
        String id = refId == null ? "" : refId;
        return switch (normalize(refType)) {
            case "INVOICE", "PAYMENT", "PAYMENT_REFUND" -> "/finance?ref=" + id;
            case "ASSIGNMENT", "SUBMISSION" -> "/assignments?ref=" + id;
            case "ATTENDANCE" -> "/attendance?ref=" + id;
            case "GRADE" -> "/grades?ref=" + id;
            case "YEAR_RESULT" -> "/year-results?ref=" + id;
            case "HOMEROOM_REMARK" -> "/academic-monitoring?ref=" + id;
            case "TIMETABLE" -> "/timetable";
            case "EXAM_PERIOD" -> "/exam-schedule?ref=" + id;
            default -> "/notifications?ref=" + id;
        };
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private List<String> asStringList(Object value) {
        if (value instanceof java.util.Collection<?> values) {
            return values.stream().map(this::asString)
                    .filter(item -> !item.isBlank()).distinct().toList();
        }
        String single = asString(value);
        return single.isBlank() ? List.of() : List.of(single);
    }

    // ---------- Announcements ----------

    public List<Announcement> announcementsFor(String role) {
        return announcements.findAllByOrderByCreatedAtDesc().stream()
                .filter(a -> a.getAudience() == null
                        || "ALL".equalsIgnoreCase(a.getAudience())
                        || role.equalsIgnoreCase(a.getAudience()))
                .toList();
    }

    public List<Announcement> announcementsCreatedBy(String userId) {
        return announcements.findAllByOrderByCreatedAtDesc().stream()
                .filter(item -> userId.equals(item.getCreatedBy()))
                .toList();
    }

    public Announcement createAnnouncement(CreateAnnouncementRequest r, String createdBy) {
        String audience = normalizeAudience(r.audience());
        Announcement a = announcements.save(Announcement.builder()
                .id(r.id() == null || r.id().isBlank() ? Ids.gen("an") : r.id())
                .title(r.title()).body(r.body()).audience(audience)
                .createdBy(createdBy).createdAt(Instant.now()).build());

        List<String> recipients = resolveAudience(audience);
        notifyUsers(recipients, "ANNOUNCEMENT", a.getTitle(), a.getBody(), "ANNOUNCEMENT", a.getId());
        return a;
    }

    public Map<String, Integer> announcementAudienceCounts() {
        Map<String, Integer> result = new LinkedHashMap<>();
        result.put("ALL", users.allUserIds().size());
        result.put("TEACHER", users.userIdsByRole("TEACHER").size());
        result.put("STUDENT", users.userIdsByRole("STUDENT").size());
        result.put("PARENT", users.userIdsByRole("PARENT").size());
        result.put("ADMIN", users.userIdsByRole("ADMIN").size());
        return result;
    }

    private List<String> resolveAudience(String audience) {
        int separator = audience.indexOf(':');
        if (separator > 0 && separator < audience.length() - 1) {
            String target = audience.substring(0, separator).toUpperCase(Locale.ROOT);
            String classId = audience.substring(separator + 1);
            List<String> studentIds = users.list("STUDENT", null, classId).stream().map(UserDto::id).toList();
            if ("CLASS".equals(target) || "CLASS_STUDENTS".equals(target)) return studentIds;
            List<String> parentIds = studentIds.stream()
                    .flatMap(studentId -> users.parentIdsOf(studentId).stream())
                    .distinct().toList();
            if ("CLASS_PARENTS".equals(target)) return parentIds;
            if ("CLASS_ALL".equals(target)) {
                return java.util.stream.Stream.concat(studentIds.stream(), parentIds.stream()).distinct().toList();
            }
            return List.of();
        }
        return switch (audience) {
            case "PARENT", "STUDENT", "TEACHER", "ADMIN" -> users.userIdsByRole(audience);
            default -> users.allUserIds();
        };
    }

    private String normalizeAudience(String value) {
        if (value == null || value.isBlank()) return "ALL";
        String audience = value.trim();
        int separator = audience.indexOf(':');
        if (separator < 0) return audience.toUpperCase(Locale.ROOT);
        return audience.substring(0, separator).toUpperCase(Locale.ROOT)
                + ":" + audience.substring(separator + 1).trim();
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
