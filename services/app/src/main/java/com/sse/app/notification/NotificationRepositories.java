package com.sse.app.notification;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface NotificationRepository extends JpaRepository<Notification, String> {
    List<Notification> findByRecipientIdOrderByCreatedAtDesc(String recipientId);
    List<Notification> findByRecipientIdAndReadIsFalseOrderByCreatedAtDesc(String recipientId);
    long countByRecipientIdAndReadIsFalse(String recipientId);
}

interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, String> {
}

interface NotificationDeliveryLogRepository extends JpaRepository<NotificationDeliveryLog, String> {
}

interface AnnouncementRepository extends JpaRepository<Announcement, String> {
    List<Announcement> findAllByOrderByCreatedAtDesc();
}
