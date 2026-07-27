package com.sse.app.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;
import java.time.LocalDate;
import java.time.Instant;

interface NotificationRepository extends JpaRepository<Notification, String>, JpaSpecificationExecutor<Notification> {
    List<Notification> findByRecipientIdOrderByCreatedAtDesc(String recipientId);
    List<Notification> findByRecipientIdAndReadIsFalseOrderByCreatedAtDesc(String recipientId);
    long countByRecipientIdAndReadIsFalse(String recipientId);
    boolean existsByRecipientIdAndRefTypeAndRefId(String recipientId, String refType, String refId);
}

interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, String> {
}

interface AnnouncementRepository extends JpaRepository<Announcement, String> {
    List<Announcement> findAllByOrderByCreatedAtDesc();
    Optional<Announcement> findFirstByCategoryAndAudienceAndHolidayStartDateLessThanEqualAndHolidayEndDateGreaterThanEqualOrderByCreatedAtDesc(
            String category, String audience, LocalDate dateAtOrAfterStart, LocalDate dateAtOrBeforeEnd);
}

interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, String> {
    List<NotificationPreference> findByUserIdOrderByChannel(String userId);
    Optional<NotificationPreference> findByUserIdAndChannel(String userId, String channel);
}

interface NotificationDeliveryLogRepository extends JpaRepository<NotificationDeliveryLog, String> {
    List<NotificationDeliveryLog> findTop200ByOrderByCreatedAtDesc();
    List<NotificationDeliveryLog> findTop100ByStatusInAndAttemptsLessThanAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            List<String> statuses, int maxAttempts, Instant now);
}

interface UserDeviceRepository extends JpaRepository<UserDevice, String> {
    List<UserDevice> findByUserIdAndActiveTrue(String userId);
    Optional<UserDevice> findByDeviceToken(String deviceToken);
}
