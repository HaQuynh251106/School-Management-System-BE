package com.sse.app.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

interface NotificationRepository extends JpaRepository<Notification, String> {
    List<Notification> findByRecipientIdAndChannelOrderByCreatedAtDesc(
            String recipientId, String channel);
    Page<Notification> findByRecipientIdAndChannelOrderByCreatedAtDesc(
            String recipientId, String channel, Pageable pageable);
    List<Notification> findByRecipientIdAndChannelAndReadIsFalseOrderByCreatedAtDesc(
            String recipientId, String channel);
    Page<Notification> findByRecipientIdAndChannelAndReadIsFalseOrderByCreatedAtDesc(
            String recipientId, String channel, Pageable pageable);
    List<Notification> findByRecipientIdAndChannelAndReadIsFalseAndTypeInOrderByCreatedAtDesc(
            String recipientId, String channel, List<String> types);
    long countByRecipientIdAndChannelAndReadIsFalse(String recipientId, String channel);
    long countByRecipientIdAndChannelAndReadIsFalseAndTypeIn(
            String recipientId, String channel, List<String> types);
    List<Notification> findByRecipientIdAndChannelAndReadIsFalseAndGroupKeyOrderByCreatedAtDesc(
            String recipientId, String channel, String groupKey);
    List<Notification> findByStatusOrderByCreatedAtDesc(String status);
    long countByStatus(String status);
    long countByChannel(String channel);
}

interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, String> {
}

interface NotificationDeliveryLogRepository extends JpaRepository<NotificationDeliveryLog, String> {
    List<NotificationDeliveryLog> findByNotificationIdOrderByAttemptNoAsc(String notificationId);
    List<NotificationDeliveryLog> findTop200ByOrderByAttemptedAtDesc();
    Page<NotificationDeliveryLog> findAllByOrderByAttemptedAtDesc(Pageable pageable);
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
