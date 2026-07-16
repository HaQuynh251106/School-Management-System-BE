package com.sse.app.notification;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface NotificationRepository extends JpaRepository<Notification, String> {
    List<Notification> findByRecipientIdOrderByCreatedAtDesc(String recipientId);
    List<Notification> findByRecipientIdAndReadIsFalseOrderByCreatedAtDesc(String recipientId);
    long countByRecipientIdAndReadIsFalse(String recipientId);
}

interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, String> {
}

interface AnnouncementRepository extends JpaRepository<Announcement, String> {
    List<Announcement> findAllByOrderByCreatedAtDesc();
}

interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, String> {
    List<NotificationPreference> findByUserIdOrderByChannel(String userId);
    Optional<NotificationPreference> findByUserIdAndChannel(String userId, String channel);
}

interface NotificationDeliveryLogRepository extends JpaRepository<NotificationDeliveryLog, String> {
    List<NotificationDeliveryLog> findTop200ByOrderByCreatedAtDesc();
}

interface UserDeviceRepository extends JpaRepository<UserDevice, String> {
    List<UserDevice> findByUserIdAndActiveTrue(String userId);
    Optional<UserDevice> findByDeviceToken(String deviceToken);
}
