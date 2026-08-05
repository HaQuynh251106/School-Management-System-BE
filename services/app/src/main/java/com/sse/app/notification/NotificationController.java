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
