package com.sse.app.notification;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** S12: per-attempt delivery log; currently records IN_APP dispatch attempts. */
@Entity
@Table(name = "notification_delivery_logs", indexes = @Index(name = "idx_ndl_notification", columnList = "notificationId"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class NotificationDeliveryLog {
    @Id
    private String id;

    @Column(nullable = false)
    private String notificationId;

    private String channel;
    private String provider;

    @Column(nullable = false)
    private int attemptNo;

    @Column(nullable = false)
    private String status;

    @Column(length = 2000)
    private String providerResponse;

    @Column(length = 1000)
    private String errorMessage;

    @Column(nullable = false)
    private Instant attemptedAt;
}
