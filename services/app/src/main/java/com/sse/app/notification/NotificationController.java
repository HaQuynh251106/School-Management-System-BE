package com.sse.app.notification;

import com.sse.app.notification.NotificationDtos.*;
import com.sse.app.security.CurrentUser;
import com.sse.app.security.CurrentUserHolder;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.academic.timetable.TeachingAssignmentService;
import com.sse.app.common.ApiException;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** C5/D2/E2: Hộp thư in-app, announcement broadcast, cấu hình template. */
@RestController
public class NotificationController {

    private final NotificationService notifications;
    private final TeachingAssignmentService teachingAssignments;
    private final StructureService structure;

    public NotificationController(NotificationService notifications, TeachingAssignmentService teachingAssignments,
                                  StructureService structure) {
        this.notifications = notifications;
        this.teachingAssignments = teachingAssignments;
        this.structure = structure;
    }

    @GetMapping("/notifications")
    public List<Notification> inbox(@RequestParam(required = false, defaultValue = "false") boolean unread) {
        CurrentUser user = CurrentUserHolder.require();
        notifications.syncAnnouncementsForUser(user.id(), user.role());
        return notifications.inbox(user.id(), unread);
    }

    @GetMapping("/notifications/unread-count")
    public Map<String, Object> unreadCount() {
        CurrentUser user = CurrentUserHolder.require();
        notifications.syncAnnouncementsForUser(user.id(), user.role());
        return Map.of("count", notifications.unreadCount(user.id()));
    }

    @PostMapping("/notifications/{id}/read")
    public Notification markRead(@PathVariable String id) {
        return notifications.markRead(id, CurrentUserHolder.require().id());
    }

    @PostMapping("/notifications/{id}/unread")
    public Notification markUnread(@PathVariable String id) {
        return notifications.markUnread(id, CurrentUserHolder.require().id());
    }

    @PostMapping("/notifications/read-all")
    public Map<String, Object> markAllRead() {
        notifications.markAllRead(CurrentUserHolder.require().id());
        return Map.of("ok", true);
    }

    @GetMapping("/notification-preferences")
    public List<NotificationPreference> preferences() {
        return notifications.preferences(CurrentUserHolder.require().id());
    }

    @PutMapping("/notification-preferences")
    public NotificationPreference updatePreference(@Valid @RequestBody UpdatePreferenceRequest request) {
        return notifications.updatePreference(CurrentUserHolder.require().id(), request);
    }

    @GetMapping("/devices")
    public List<UserDevice> devices() {
        return notifications.devices(CurrentUserHolder.require().id());
    }

    @PostMapping("/devices")
    public UserDevice registerDevice(@Valid @RequestBody RegisterDeviceRequest request) {
        return notifications.registerDevice(CurrentUserHolder.require().id(), request);
    }

    @DeleteMapping("/devices/{id}")
    public Map<String, Object> deactivateDevice(@PathVariable String id) {
        notifications.deactivateDevice(CurrentUserHolder.require().id(), id);
        return Map.of("ok", true);
    }

    @GetMapping("/notification-delivery-logs")
    public List<NotificationDeliveryLog> deliveryLogs() {
        CurrentUserHolder.requireRole("ADMIN");
        return notifications.deliveryLogs();
    }

    // ----- Announcements (route khớp json-server) -----
    @GetMapping("/announcements")
    public List<Announcement> announcements() {
        CurrentUser me = CurrentUserHolder.require();
        return notifications.announcementsFor(me.role());
    }

    @PostMapping("/announcements")
    public Announcement createAnnouncement(@Valid @RequestBody CreateAnnouncementRequest r) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("ADMIN", "TEACHER");
        if (me.isTeacher()) {
            assertTeacherAnnouncement(me.id(), r);
        } else {
            assertAdminAnnouncement(r);
        }
        return notifications.createAnnouncement(r, me.id());
    }

    @GetMapping("/admin/announcements")
    public List<Announcement> adminAnnouncements() {
        CurrentUserHolder.requireRole("ADMIN");
        return notifications.adminAnnouncements();
    }

    @GetMapping("/admin/announcements/audience-counts")
    public Map<String, Long> announcementAudienceCounts() {
        CurrentUserHolder.requireRole("ADMIN");
        return notifications.audienceCounts();
    }

    @GetMapping("/teacher/announcements")
    public List<Announcement> teacherAnnouncements() {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("TEACHER");
        return notifications.teacherAnnouncements(me.id());
    }

    @GetMapping("/teacher/announcements/scopes")
    public List<TeacherAnnouncementScope> teacherAnnouncementScopes() {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("TEACHER");
        return notifications.teacherAnnouncementScopes(me.id());
    }

    // ----- Templates -----
    @GetMapping("/notification-templates")
    public List<NotificationTemplate> templates() {
        CurrentUserHolder.requireRole("ADMIN");
        return notifications.listTemplates();
    }

    @PostMapping("/notification-templates")
    public NotificationTemplate createTemplate(@Valid @RequestBody CreateTemplateRequest r) {
        CurrentUserHolder.requireRole("ADMIN");
        return notifications.createTemplate(r);
    }

    private void assertAdminAnnouncement(CreateAnnouncementRequest request) {
        String category = request.category() == null ? "GENERAL" : request.category().toUpperCase();
        if (!Set.of("GENERAL", "HOLIDAY", "EVENT", "PARENT_MEETING").contains(category)) {
            throw ApiException.forbidden("Quản trị viên chỉ gửi thông báo chung, nghỉ lễ, sự kiện và họp phụ huynh");
        }
        String audience = normalizeAudience(request.audience());
        if (audience.startsWith("CLASS")) {
            throw ApiException.forbidden("Thông báo theo lớp thuộc trách nhiệm của giáo viên phụ trách");
        }
    }

    private void assertTeacherAnnouncement(String teacherId, CreateAnnouncementRequest request) {
        String category = request.category() == null ? "GENERAL" : request.category().toUpperCase();
        if (!"STUDENT_STATUS".equals(category)) {
            throw ApiException.forbidden("Điểm số và điểm danh được hệ thống thông báo tự động; giáo viên chỉ cần gửi thủ công tình hình lớp học");
        }
        String audience = normalizeAudience(request.audience());
        if (!(audience.startsWith("CLASS:") || audience.startsWith("CLASS_STUDENTS:")
                || audience.startsWith("CLASS_PARENTS:") || audience.startsWith("CLASS_ALL:"))) {
            throw ApiException.forbidden("Giáo viên chỉ được gửi thông báo tới học sinh và phụ huynh của lớp mình phụ trách");
        }
        String classId = audience.substring(audience.indexOf(':') + 1);
        boolean teaches = teachingAssignments.isAssigned(teacherId, classId);
        boolean homeroom = teacherId.equals(structure.getClass(classId).getHomeroomTeacherId());
        if (!teaches && !homeroom) throw ApiException.forbidden("Giáo viên không phụ trách lớp nhận thông báo");
    }

    private String normalizeAudience(String rawAudience) {
        if (rawAudience == null || rawAudience.isBlank()) return "ALL";
        int separator = rawAudience.indexOf(':');
        if (separator < 0) return rawAudience.toUpperCase();
        return rawAudience.substring(0, separator).toUpperCase() + rawAudience.substring(separator);
    }
}
