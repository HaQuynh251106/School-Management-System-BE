package com.sse.app.notification;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface NotificationRepository extends JpaRepository<Notification, String> {
    List<Notification> findByRecipientIdOrderByCreatedAtDesc(String recipientId);
    List<Notification> findByRecipientIdAndReadIsFalseOrderByCreatedAtDesc(String recipientId);
    List<Notification> findByRecipientIdAndReadIsFalseAndTypeInOrderByCreatedAtDesc(
            String recipientId, List<String> types);
    long countByRecipientIdAndReadIsFalse(String recipientId);
    long countByRecipientIdAndReadIsFalseAndTypeIn(String recipientId, List<String> types);
    List<Notification> findByRecipientIdAndReadIsFalseAndGroupKeyOrderByCreatedAtDesc(
            String recipientId, String groupKey);
    List<Notification> findByStatusOrderByCreatedAtDesc(String status);
    long countByStatus(String status);
    long countByChannel(String channel);
}

interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, String> {
}

interface NotificationDeliveryLogRepository extends JpaRepository<NotificationDeliveryLog, String> {
    List<NotificationDeliveryLog> findByNotificationIdOrderByAttemptNoAsc(String notificationId);
    List<NotificationDeliveryLog> findTop200ByOrderByAttemptedAtDesc();
    long countByStatus(String status);
}

interface AnnouncementRepository extends JpaRepository<Announcement, String> {
    List<Announcement> findAllByOrderByCreatedAtDesc();
}

interface UserNotificationPreferenceRepository
        extends JpaRepository<UserNotificationPreference, String> {
    List<UserNotificationPreference> findByUserIdOrderByNotificationTypeAscChannelAsc(String userId);
    Optional<UserNotificationPreference> findByUserIdAndNotificationTypeAndChannel(
            String userId, String notificationType, String channel);
}
