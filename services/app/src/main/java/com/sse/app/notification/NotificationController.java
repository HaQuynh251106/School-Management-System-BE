package com.sse.app.notification;

import com.sse.app.academic.structure.SchoolClass;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.academic.teaching.TeachingAssignmentService;
import com.sse.app.common.ApiException;
import com.sse.app.identity.UserDto;
import com.sse.app.identity.UserService;
import com.sse.app.notification.NotificationDtos.*;
import com.sse.app.security.CurrentUser;
import com.sse.app.security.CurrentUserHolder;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;

/** C5/D2/E2: Hộp thư in-app, announcement broadcast, cấu hình template. */
@RestController
public class NotificationController {

    private final NotificationService notifications;
    private final TeachingAssignmentService teachingAssignments;
    private final StructureService structure;
    private final UserService users;

    public NotificationController(NotificationService notifications,
                                  TeachingAssignmentService teachingAssignments,
                                  StructureService structure,
                                  UserService users) {
        this.notifications = notifications;
        this.teachingAssignments = teachingAssignments;
        this.structure = structure;
        this.users = users;
    }

    @GetMapping("/notifications")
    public List<Notification> inbox(@RequestParam(required = false, defaultValue = "false") boolean unread) {
        return notifications.inbox(CurrentUserHolder.require().id(), unread);
    }

    @GetMapping("/notifications/unread-count")
    public Map<String, Object> unreadCount() {
        return Map.of("count", notifications.unreadCount(CurrentUserHolder.require().id()));
    }

    @GetMapping("/notifications/finance/unread-count")
    public Map<String, Object> financeUnreadCount() {
        return Map.of("count", notifications.financeUnreadCount(CurrentUserHolder.require().id()));
    }

    @PostMapping("/notifications/{id}/read")
    public Notification markRead(@PathVariable String id) {
        return notifications.markRead(id, CurrentUserHolder.require().id());
    }

    @PostMapping("/notifications/read-all")
    public Map<String, Object> markAllRead() {
        notifications.markAllRead(CurrentUserHolder.require().id());
        return Map.of("ok", true);
    }

    @PostMapping("/notifications/finance/read-all")
    public Map<String, Object> markAllFinanceRead() {
        notifications.markAllFinanceRead(CurrentUserHolder.require().id());
        return Map.of("ok", true);
    }

    @PostMapping("/notifications/groups/{groupKey}/read")
    public Map<String, Object> markGroupRead(@PathVariable String groupKey) {
        int updated = notifications.markGroupRead(
                CurrentUserHolder.require().id(), groupKey);
        return Map.of("ok", true, "updated", updated);
    }

    @GetMapping("/me/notification-preferences")
    public List<UserNotificationPreference> preferences() {
        return notifications.preferences(CurrentUserHolder.require().id());
    }

    @PutMapping("/me/notification-preferences")
    public UserNotificationPreference updatePreference(
            @Valid @RequestBody UpdatePreferenceRequest request) {
        return notifications.updatePreference(
                CurrentUserHolder.require().id(), request);
    }

    @GetMapping("/admin/notification-deliveries")
    public List<NotificationDeliveryLog> deliveries() {
        CurrentUserHolder.requireRole("ADMIN");
        return notifications.latestDeliveryAttempts();
    }

    @GetMapping("/admin/notification-operations/summary")
    public NotificationOperationsSummary operationsSummary() {
        CurrentUserHolder.requireRole("ADMIN");
        return notifications.operationsSummary();
    }

    @GetMapping("/admin/notifications/{id}/deliveries")
    public List<NotificationDeliveryLog> deliveries(@PathVariable String id) {
        CurrentUserHolder.requireRole("ADMIN");
        return notifications.deliveryAttempts(id);
    }

    @GetMapping("/admin/notifications/failed")
    public List<Notification> failed() {
        CurrentUserHolder.requireRole("ADMIN");
        return notifications.failedNotifications();
    }

    @PostMapping("/admin/notifications/{id}/retry")
    public Notification retry(@PathVariable String id) {
        CurrentUserHolder.requireRole("ADMIN");
        return notifications.retry(id);
    }

    // ----- Announcements (route khớp json-server) -----
    @GetMapping("/announcements")
    public List<Announcement> announcements() {
        CurrentUser me = CurrentUserHolder.require();
        return notifications.announcementsFor(me.role());
    }

    @GetMapping("/admin/announcements")
    public List<Announcement> adminAnnouncements() {
        CurrentUserHolder.requireRole("ADMIN");
        return notifications.announcementsFor("ADMIN");
    }

    @GetMapping("/admin/announcements/audience-counts")
    public Map<String, Integer> announcementAudienceCounts() {
        CurrentUserHolder.requireRole("ADMIN");
        return notifications.announcementAudienceCounts();
    }

    @GetMapping("/teacher/announcements")
    public List<Announcement> teacherAnnouncements() {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("TEACHER");
        return notifications.announcementsCreatedBy(me.id());
    }

    @GetMapping("/teacher/announcements/scopes")
    public List<TeacherAnnouncementScope> teacherAnnouncementScopes() {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("TEACHER");
        return teacherScopes(me.id());
    }

    @PostMapping("/announcements")
    public Announcement createAnnouncement(@Valid @RequestBody CreateAnnouncementRequest r) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("ADMIN", "TEACHER");
        if (me.isTeacher()) {
            String classId = classIdFromAudience(r.audience());
            Set<String> allowed = teacherScopes(me.id()).stream()
                    .map(TeacherAnnouncementScope::classId)
                    .collect(java.util.stream.Collectors.toSet());
            if (classId == null || !allowed.contains(classId)) {
                throw ApiException.forbidden("Giáo viên chỉ được gửi thông báo tới lớp đang giảng dạy hoặc chủ nhiệm");
            }
        }
        return notifications.createAnnouncement(r, me.id());
    }

    private List<TeacherAnnouncementScope> teacherScopes(String teacherId) {
        Map<String, ScopeBuilder> scopes = new LinkedHashMap<>();
        teachingAssignments.list(teacherId, null, null, null, "ACTIVE").forEach(item -> {
            ScopeBuilder scope = scopes.computeIfAbsent(item.classId(), id -> new ScopeBuilder(id, item.classCode()));
            if (item.subjectName() != null && !item.subjectName().isBlank()) scope.subjects.add(item.subjectName());
        });
        for (SchoolClass schoolClass : structure.classesOfHomeroom(teacherId)) {
            scopes.computeIfAbsent(schoolClass.getId(), id -> new ScopeBuilder(id, schoolClass.getCode())).homeroom = true;
        }
        return scopes.values().stream().map(scope -> {
            List<UserDto> students = users.list("STUDENT", null, scope.classId);
            int parentCount = students.stream()
                    .flatMap(student -> users.parentIdsOf(student.id()).stream())
                    .collect(java.util.stream.Collectors.toSet()).size();
            return new TeacherAnnouncementScope(scope.classId, scope.classCode,
                    students.size(), parentCount, List.copyOf(scope.subjects), scope.homeroom);
        }).toList();
    }

    private String classIdFromAudience(String audience) {
        if (audience == null) return null;
        int separator = audience.indexOf(':');
        if (separator <= 0 || separator == audience.length() - 1) return null;
        String prefix = audience.substring(0, separator).toUpperCase(java.util.Locale.ROOT);
        if (!Set.of("CLASS", "CLASS_ALL", "CLASS_STUDENTS", "CLASS_PARENTS").contains(prefix)) return null;
        return audience.substring(separator + 1).trim();
    }

    private static final class ScopeBuilder {
        private final String classId;
        private final String classCode;
        private final Set<String> subjects = new LinkedHashSet<>();
        private boolean homeroom;

        private ScopeBuilder(String classId, String classCode) {
            this.classId = classId;
            this.classCode = classCode;
        }
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
}
