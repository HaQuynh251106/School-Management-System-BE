package com.sse.app.notification;

import com.sse.app.academic.structure.SchoolClass;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.academic.timetable.TeachingAssignment;
import com.sse.app.academic.timetable.TeachingAssignmentService;
import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.common.PageResponse;
import com.sse.app.common.Paging;
import com.sse.app.notification.NotificationDtos.*;
import com.sse.app.identity.UserDto;
import com.sse.app.identity.UserService;
import com.sse.app.realtime.RealtimeEventHub;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

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
    private final TeachingAssignmentService teachingAssignments;
    private final StructureService structure;
    private final NotificationPreferenceRepository preferences;
    private final NotificationDeliveryLogRepository deliveryLogs;
    private final UserDeviceRepository devices;
    private final NotificationChannelDispatcher dispatcher;
    private final RealtimeEventHub realtime;

    public NotificationService(NotificationRepository notifications,
                               NotificationTemplateRepository templates,
                               AnnouncementRepository announcements,
                               UserService users,
                               TeachingAssignmentService teachingAssignments,
                               StructureService structure,
                               NotificationPreferenceRepository preferences,
                               NotificationDeliveryLogRepository deliveryLogs,
                               UserDeviceRepository devices,
                               NotificationChannelDispatcher dispatcher,
                               RealtimeEventHub realtime) {
        this.notifications = notifications;
        this.templates = templates;
        this.announcements = announcements;
        this.users = users;
        this.teachingAssignments = teachingAssignments;
        this.structure = structure;
        this.preferences = preferences;
        this.deliveryLogs = deliveryLogs;
        this.devices = devices;
        this.dispatcher = dispatcher;
        this.realtime = realtime;
    }

    // ---------- Phát thông báo in-app ----------

    public Notification notifyUser(String recipientId, String type, String title, String body,
                                   String refType, String refId) {
        return notifyUser(recipientId, type, "NORMAL", title, body, refType, refId);
    }

    public Notification notifyUser(String recipientId, String type, String priority, String title, String body,
                                   String refType, String refId) {
        Notification notification = null;
        // Thông báo điều hành phải luôn được lưu trong hộp thư ứng dụng. Người dùng
        // vẫn có thể tắt email/push, nhưng không được bỏ lỡ thông báo từ nhà trường.
        if ("ANNOUNCEMENT".equalsIgnoreCase(refType) || "EXAM_PERIOD".equalsIgnoreCase(refType)
                || channelEnabled(recipientId, "IN_APP")) {
            notification = notifications.save(Notification.builder()
                    .id(Ids.gen("noti")).recipientId(recipientId).type(type)
                    .priority(priority)
                    .title(title).body(body).read(false)
                    .refType(refType).refId(refId).createdAt(Instant.now()).build());
            deliveryLogs.save(NotificationDeliveryLog.builder().id(Ids.gen("ndl"))
                    .notificationId(notification.getId()).recipientId(recipientId).channel("IN_APP")
                    .status("DELIVERED").attempts(1).createdAt(Instant.now()).build());
            publishInboxChanged(recipientId, notification);
        }
        dispatcher.dispatch(recipientId, notification == null ? null : notification.getId(), title, body);
        return notification;
    }

    /**
     * Lưu thông báo trong ứng dụng và luôn thử gửi email giao dịch.
     * Trạng thái gửi/không gửi được vẫn được ghi trong notification_delivery_logs.
     */
    public Notification notifyUserWithTransactionalEmail(String recipientId, String type, String title, String body,
                                                         String refType, String refId) {
        Notification notification = notifications.save(Notification.builder()
                .id(Ids.gen("noti")).recipientId(recipientId).type(type)
                .priority("IMPORTANT").title(title).body(body).read(false)
                .refType(refType).refId(refId).createdAt(Instant.now()).build());
        deliveryLogs.save(NotificationDeliveryLog.builder().id(Ids.gen("ndl"))
                .notificationId(notification.getId()).recipientId(recipientId).channel("IN_APP")
                .status("DELIVERED").attempts(1).createdAt(Instant.now()).build());
        publishInboxChanged(recipientId, notification);
        dispatcher.dispatchTransactionalEmail(recipientId, notification.getId(), title, body);
        return notification;
    }

    public void notifyUsers(List<String> recipientIds, String type, String title, String body,
                            String refType, String refId) {
        for (String id : recipientIds) notifyUser(id, type, title, body, refType, refId);
    }

    public void notifyUsers(List<String> recipientIds, String type, String priority, String title, String body,
                            String refType, String refId) {
        for (String id : recipientIds) notifyUser(id, type, priority, title, body, refType, refId);
    }

    public boolean hasNotification(String recipientId, String refType, String refId) {
        return recipientId != null && notifications.existsByRecipientIdAndRefTypeAndRefId(
                recipientId, refType, refId);
    }

    /**
     * Xóa thông báo nghiệp vụ đã trở nên không còn đúng sau khi dữ liệu nguồn được hiệu chỉnh.
     * Ví dụ: một lượt vắng được đối soát thành nghỉ có phép thì cảnh báo vắng cũ không được tiếp
     * tục hiển thị cho học sinh và phụ huynh.
     */
    @Transactional
    public void removeByReference(String refType, String refId) {
        List<Notification> matched = notifications.findByRefTypeAndRefId(refType, refId);
        if (matched.isEmpty()) return;
        List<String> notificationIds = matched.stream().map(Notification::getId).toList();
        Set<String> recipients = matched.stream().map(Notification::getRecipientId)
                .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        deliveryLogs.deleteByNotificationIdIn(notificationIds);
        notifications.deleteAllInBatch(matched);
        recipients.forEach(recipientId -> realtime.publish(recipientId, "NOTIFICATION", Map.of(
                "action", "REMOVED",
                "refType", refType,
                "refId", refId)));
    }

    /** 2.5/2.6: bắn cho tất cả phụ huynh của một học sinh. */
    public void notifyParentsOfStudent(String studentId, String type, String title, String body,
                                       String refType, String refId) {
        notifyUsers(users.parentIdsOf(studentId), type, title, body, refType, refId);
    }

    public void notifyParentsOfStudent(String studentId, String type, String priority, String title, String body,
                                       String refType, String refId) {
        notifyUsers(users.parentIdsOf(studentId), type, priority, title, body, refType, refId);
    }

    // ---------- Hộp thư in-app ----------

    public List<Notification> inbox(String recipientId, boolean unreadOnly) {
        return unreadOnly
                ? notifications.findByRecipientIdAndReadIsFalseOrderByCreatedAtDesc(recipientId)
                : notifications.findByRecipientIdOrderByCreatedAtDesc(recipientId);
    }

    public PageResponse<Notification> inboxPage(String recipientId, String readFilter, String type,
                                                 String priority, String query, int page, int size) {
        Specification<Notification> owner = (root, ignored, builder) ->
                builder.equal(root.get("recipientId"), recipientId);
        Specification<Notification> filtered = owner;
        if ("READ".equalsIgnoreCase(readFilter)) {
            filtered = filtered.and((root, ignored, builder) -> builder.isTrue(root.get("read")));
        } else if ("UNREAD".equalsIgnoreCase(readFilter)) {
            filtered = filtered.and((root, ignored, builder) -> builder.isFalse(root.get("read")));
        }
        if (type != null && !type.isBlank() && !"ALL".equalsIgnoreCase(type)) {
            filtered = filtered.and((root, ignored, builder) ->
                    builder.equal(builder.upper(root.get("type")), type.trim().toUpperCase(Locale.ROOT)));
        }
        if (priority != null && !priority.isBlank() && !"ALL".equalsIgnoreCase(priority)) {
            filtered = filtered.and((root, ignored, builder) ->
                    builder.equal(builder.upper(root.get("priority")), priority.trim().toUpperCase(Locale.ROOT)));
        }
        if (query != null && !query.isBlank()) {
            String pattern = "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
            filtered = filtered.and((root, ignored, builder) -> builder.or(
                    builder.like(builder.lower(root.get("title")), pattern),
                    builder.like(builder.lower(root.get("body")), pattern)
            ));
        }

        Instant today = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant();
        Map<String, Long> summary = new LinkedHashMap<>();
        summary.put("total", notifications.count(owner));
        summary.put("unread", notifications.count(owner.and(
                (root, ignored, builder) -> builder.isFalse(root.get("read")))));
        summary.put("important", notifications.count(owner.and(
                (root, ignored, builder) -> root.get("priority").in("IMPORTANT", "URGENT"))));
        summary.put("today", notifications.count(owner.and(
                (root, ignored, builder) -> builder.greaterThanOrEqualTo(root.get("createdAt"), today))));

        return PageResponse.from(notifications.findAll(filtered,
                Paging.request(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))), summary);
    }

    public long unreadCount(String recipientId) {
        return notifications.countByRecipientIdAndReadIsFalse(recipientId);
    }

    public Notification markRead(String id, String recipientId) {
        Notification n = notifications.findById(id).orElseThrow(() -> ApiException.notFound("Thông báo"));
        if (!recipientId.equals(n.getRecipientId())) throw ApiException.forbidden("Không phải thông báo của bạn");
        n.setRead(true);
        Notification saved = notifications.save(n);
        publishInboxChanged(recipientId, saved);
        return saved;
    }

    public Notification markUnread(String id, String recipientId) {
        Notification n = notifications.findById(id).orElseThrow(() -> ApiException.notFound("Thông báo"));
        if (!recipientId.equals(n.getRecipientId())) throw ApiException.forbidden("Không phải thông báo của bạn");
        n.setRead(false);
        Notification saved = notifications.save(n);
        publishInboxChanged(recipientId, saved);
        return saved;
    }

    public void markAllRead(String recipientId) {
        var list = notifications.findByRecipientIdAndReadIsFalseOrderByCreatedAtDesc(recipientId);
        list.forEach(n -> n.setRead(true));
        notifications.saveAll(list);
        realtime.publish(recipientId, "NOTIFICATION", Map.of("action", "READ_ALL"));
    }

    private void publishInboxChanged(String recipientId, Notification notification) {
        realtime.publish(recipientId, "NOTIFICATION", Map.of(
                "id", notification.getId(),
                "type", notification.getType(),
                "read", notification.isRead()));
    }

    /**
     * Đồng bộ các thông báo toàn trường/theo vai trò vào hộp thư của tài khoản.
     * Việc này giúp giáo viên mới được tạo vẫn nhận được thông báo Admin còn lưu
     * trong hệ thống, đồng thời không tạo bản ghi trùng khi tải lại Dashboard.
     */
    public synchronized void syncAnnouncementsForUser(String recipientId, String role) {
        if (!Set.of("TEACHER", "STUDENT", "PARENT").contains(role)) return;
        for (Announcement announcement : announcementsFor(role)) {
            if (notifications.existsByRecipientIdAndRefTypeAndRefId(
                    recipientId, "ANNOUNCEMENT", announcement.getId())) {
                continue;
            }
            Notification notification = notifications.save(Notification.builder()
                    .id(Ids.gen("noti"))
                    .recipientId(recipientId)
                    .type(announcement.getCategory() == null ? "GENERAL" : announcement.getCategory())
                    .priority(announcement.getPriority() == null ? "NORMAL" : announcement.getPriority())
                    .title(announcement.getTitle())
                    .body(announcement.getBody())
                    .read(false)
                    .refType("ANNOUNCEMENT")
                    .refId(announcement.getId())
                    .createdAt(announcement.getCreatedAt() == null ? Instant.now() : announcement.getCreatedAt())
                    .build());
            deliveryLogs.save(NotificationDeliveryLog.builder()
                    .id(Ids.gen("ndl"))
                    .notificationId(notification.getId())
                    .recipientId(recipientId)
                    .channel("IN_APP")
                    .status("DELIVERED")
                    .attempts(1)
                    .createdAt(Instant.now())
                    .build());
        }
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
        if ("IN_APP".equals(channel) && Boolean.FALSE.equals(request.enabled())) {
            throw ApiException.badRequest("Thông báo trong ứng dụng là kênh bắt buộc và không thể tắt");
        }
        if (Boolean.TRUE.equals(request.enabled()) && !dispatcher.capabilities().getOrDefault(channel, false)) {
            throw ApiException.badRequest("Kênh " + channel + " chưa được nhà trường cấu hình");
        }
        NotificationPreference preference = preferences.findByUserIdAndChannel(userId, channel)
                .orElseGet(() -> NotificationPreference.builder().id(Ids.gen("np"))
                        .userId(userId).channel(channel).build());
        preference.setEnabled(!Boolean.FALSE.equals(request.enabled()));
        preference.setUpdatedAt(Instant.now());
        return preferences.save(preference);
    }

    public Map<String, Boolean> channelCapabilities() {
        return dispatcher.capabilities();
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
        String audience = normalizeAudience(r.audience());
        String category = r.category() == null ? "GENERAL" : r.category().toUpperCase();
        String priority = r.priority() == null ? "NORMAL" : r.priority().toUpperCase();
        LocalDate holidayStartDate = null;
        LocalDate holidayEndDate = null;
        if ("HOLIDAY".equals(category)) {
            if (!"ALL".equals(audience)) {
                throw ApiException.badRequest("Thông báo nghỉ lễ phải gửi tới toàn trường");
            }
            if (r.holidayStartDate() == null || r.holidayEndDate() == null) {
                throw ApiException.badRequest("Cần chọn đầy đủ ngày bắt đầu và ngày kết thúc kỳ nghỉ");
            }
            if (r.holidayEndDate().isBefore(r.holidayStartDate())) {
                throw ApiException.badRequest("Ngày kết thúc kỳ nghỉ không được trước ngày bắt đầu");
            }
            holidayStartDate = r.holidayStartDate();
            holidayEndDate = r.holidayEndDate();
        }
        List<String> recipients = resolveAudience(audience).stream().distinct().toList();
        Announcement a = announcements.save(Announcement.builder()
                .id(r.id() == null || r.id().isBlank() ? Ids.gen("an") : r.id())
                .title(r.title().trim()).body(r.body().trim()).audience(audience)
                .category(category).priority(priority).status("SENT")
                .recipientCount(recipients.size())
                .holidayStartDate(holidayStartDate).holidayEndDate(holidayEndDate)
                .createdBy(createdBy).createdAt(Instant.now()).build());

        // Fan-out in-app cho đối tượng nhận (đồng bộ — bản RabbitMQ sẽ async hoá).
        notifyUsers(recipients, category, priority, a.getTitle(), a.getBody(), "ANNOUNCEMENT", a.getId());
        return a;
    }

    public Optional<Announcement> schoolHolidayOn(LocalDate date) {
        if (date == null) return Optional.empty();
        return announcements
                .findFirstByCategoryAndAudienceAndHolidayStartDateLessThanEqualAndHolidayEndDateGreaterThanEqualOrderByCreatedAtDesc(
                        "HOLIDAY", "ALL", date, date);
    }

    public List<Announcement> adminAnnouncements() {
        Set<String> adminCategories = Set.of("GENERAL", "HOLIDAY", "EVENT", "PARENT_MEETING");
        return announcements.findAllByOrderByCreatedAtDesc().stream()
                .filter(item -> adminCategories.contains(item.getCategory() == null ? "GENERAL" : item.getCategory()))
                .toList();
    }

    public List<Announcement> teacherAnnouncements(String teacherId) {
        return announcements.findAllByOrderByCreatedAtDesc().stream()
                .filter(item -> teacherId.equals(item.getCreatedBy()))
                .toList();
    }

    public List<TeacherAnnouncementScope> teacherAnnouncementScopes(String teacherId) {
        Map<String, String> classCodes = new LinkedHashMap<>();
        Map<String, Set<String>> subjects = new LinkedHashMap<>();
        Set<String> homeroomClassIds = new LinkedHashSet<>();

        for (TeachingAssignment assignment : teachingAssignments.assignmentsOfTeacher(teacherId)) {
            classCodes.putIfAbsent(assignment.getClassId(), assignment.getClassCode());
            subjects.computeIfAbsent(assignment.getClassId(), ignored -> new LinkedHashSet<>())
                    .add(assignment.getSubjectName());
        }
        for (SchoolClass schoolClass : structure.classesOfHomeroom(teacherId)) {
            classCodes.putIfAbsent(schoolClass.getId(), schoolClass.getCode());
            subjects.computeIfAbsent(schoolClass.getId(), ignored -> new LinkedHashSet<>());
            homeroomClassIds.add(schoolClass.getId());
        }

        Set<String> activeParentIds = Set.copyOf(users.activeUserIdsByRole("PARENT"));
        return classCodes.entrySet().stream()
                .map(entry -> {
                    List<UserDto> students = activeStudentsOfClass(entry.getKey());
                    int parentCount = (int) students.stream()
                            .flatMap(student -> users.parentIdsOf(student.id()).stream())
                            .filter(activeParentIds::contains)
                            .distinct()
                            .count();
                    return new TeacherAnnouncementScope(
                            entry.getKey(), entry.getValue(), students.size(), parentCount,
                            List.copyOf(subjects.get(entry.getKey())), homeroomClassIds.contains(entry.getKey()));
                })
                .sorted((left, right) -> left.classCode().compareToIgnoreCase(right.classCode()))
                .toList();
    }

    public Map<String, Long> audienceCounts() {
        long teachers = users.activeUserIdsByRole("TEACHER").size();
        long students = users.activeUserIdsByRole("STUDENT").size();
        long parents = users.activeUserIdsByRole("PARENT").size();
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("ALL", teachers + students + parents);
        counts.put("TEACHER", teachers);
        counts.put("STUDENT", students);
        counts.put("PARENT", parents);
        return counts;
    }

    private List<String> resolveAudience(String audience) {
        if (audience.startsWith("CLASS:")) {
            return activeStudentsOfClass(audience.substring("CLASS:".length())).stream().map(UserDto::id).toList();
        }
        if (audience.startsWith("CLASS_STUDENTS:")) {
            return activeStudentsOfClass(audience.substring("CLASS_STUDENTS:".length())).stream()
                    .map(UserDto::id).toList();
        }
        if (audience.startsWith("CLASS_PARENTS:")) {
            return activeParentIdsOfClass(audience.substring("CLASS_PARENTS:".length()));
        }
        if (audience.startsWith("CLASS_ALL:")) {
            String classId = audience.substring("CLASS_ALL:".length());
            return Stream.concat(
                    activeStudentsOfClass(classId).stream().map(UserDto::id),
                    activeParentIdsOfClass(classId).stream()).distinct().toList();
        }
        return switch (audience) {
            case "PARENT", "STUDENT", "TEACHER" -> users.activeUserIdsByRole(audience);
            case "ALL" -> Stream.of("TEACHER", "STUDENT", "PARENT")
                    .flatMap(role -> users.activeUserIdsByRole(role).stream())
                    .distinct().toList();
            default -> List.of();
        };
    }

    private String normalizeAudience(String rawAudience) {
        if (rawAudience == null || rawAudience.isBlank()) return "ALL";
        int separator = rawAudience.indexOf(':');
        if (separator < 0) return rawAudience.toUpperCase();
        return rawAudience.substring(0, separator).toUpperCase() + rawAudience.substring(separator);
    }

    private List<UserDto> activeStudentsOfClass(String classId) {
        return users.list("STUDENT", null, classId).stream()
                .filter(student -> "ACTIVE".equals(student.status()))
                .toList();
    }

    private List<String> activeParentIdsOfClass(String classId) {
        Set<String> activeParentIds = Set.copyOf(users.activeUserIdsByRole("PARENT"));
        return activeStudentsOfClass(classId).stream()
                .flatMap(student -> users.parentIdsOf(student.id()).stream())
                .filter(activeParentIds::contains)
                .distinct()
                .toList();
    }

    /** Seed raw (không fan-out) — dùng bởi DataSeeder. */
    public void seed(List<Announcement> anns, List<NotificationTemplate> tpls) {
        announcements.saveAll(anns);
        templates.saveAll(tpls);
    }

    // ---------- Templates (E2/S12) ----------

    public List<NotificationTemplate> listTemplates() { return templates.findAll(); }

    public NotificationTemplate createTemplate(CreateTemplateRequest r) {
        String code = r.code().trim().toUpperCase();
        NotificationTemplate template = r.id() == null || r.id().isBlank()
                ? templates.findByCodeIgnoreCase(code).orElseGet(NotificationTemplate::new)
                : templates.findById(r.id()).orElseThrow(() -> ApiException.notFound("Mẫu thông báo"));
        if (template.getId() == null) template.setId(Ids.gen("tpl"));
        template.setCode(code);
        template.setName(r.name());
        template.setChannel(r.channel() == null ? "IN_APP" : r.channel().trim().toUpperCase());
        template.setTitleTemplate(r.titleTemplate());
        template.setBodyTemplate(r.bodyTemplate());
        template.setActive(r.active() == null || r.active());
        return templates.save(template);
    }
}
