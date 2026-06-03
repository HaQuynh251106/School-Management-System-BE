package com.sse.app.notification;

import com.sse.app.notification.NotificationDtos.*;
import com.sse.app.security.CurrentUser;
import com.sse.app.security.CurrentUserHolder;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** C5/D2/E2: Hộp thư in-app, announcement broadcast, cấu hình template. */
@RestController
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
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
}
