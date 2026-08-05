package com.sse.app.notification;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "user_notification_preferences",
        uniqueConstraints = @UniqueConstraint(name = "uk_notification_preference",
                columnNames = {"userId", "notificationType", "channel"}),
        indexes = @Index(name = "idx_notification_preference_user", columnList = "userId"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UserNotificationPreference {
    @Id
    private String id;
    private String userId;
    private String notificationType;
    private String channel;
    private boolean enabled;
    private Instant updatedAt;
}
