package com.sse.app.notification;

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
    private Instant createdAt;
}
