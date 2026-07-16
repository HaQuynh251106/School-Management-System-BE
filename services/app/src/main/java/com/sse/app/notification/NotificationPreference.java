package com.sse.app.notification;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "notification_preferences", uniqueConstraints =
        @UniqueConstraint(columnNames = {"userId", "channel"}), indexes =
        @Index(name = "idx_notification_pref_user", columnList = "userId"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class NotificationPreference {
    @Id private String id;
    private String userId;
    private String channel;
    private boolean enabled;
    private Instant updatedAt;
}
