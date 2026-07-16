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
        return notifications.inbox(CurrentUserHolder.require().id(), unread);
    }

    @GetMapping("/notifications/unread-count")
    public Map<String, Object> unreadCount() {
        return Map.of("count", notifications.unreadCount(CurrentUserHolder.require().id()));
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
        if (me.isTeacher()) assertTeacherAudience(me.id(), r.audience());
        return notifications.createAnnouncement(r, me.id());
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

    private void assertTeacherAudience(String teacherId, String audience) {
        if (audience == null || !audience.toUpperCase().startsWith("CLASS:")) {
            throw ApiException.forbidden("Giáo viên chỉ được gửi thông báo tới lớp mình phụ trách");
        }
        String classId = audience.substring("CLASS:".length());
        boolean teaches = teachingAssignments.isAssigned(teacherId, classId);
        boolean homeroom = teacherId.equals(structure.getClass(classId).getHomeroomTeacherId());
        if (!teaches && !homeroom) throw ApiException.forbidden("Giáo viên không phụ trách lớp nhận thông báo");
    }
}
