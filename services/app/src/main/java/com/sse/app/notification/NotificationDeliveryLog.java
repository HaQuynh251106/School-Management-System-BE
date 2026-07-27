package com.sse.app.notification;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "notification_delivery_logs", indexes = {
        @Index(name = "idx_delivery_recipient", columnList = "recipientId,createdAt"),
        @Index(name = "idx_delivery_notification", columnList = "notificationId")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class NotificationDeliveryLog {
    @Id private String id;
    private String notificationId;
    private String recipientId;
    private String channel;
    private String status;
    private int attempts;
    @Column(length = 1000)
    private String detail;
    @JsonIgnore
    @Column(length = 255)
    private String title;
    @JsonIgnore
    @Column(length = 4000)
    private String payload;
    private Instant nextAttemptAt;
    private Instant createdAt;
    private Instant updatedAt;
}
