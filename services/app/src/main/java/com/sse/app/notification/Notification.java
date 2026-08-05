package com.sse.app.notification;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** E2/C5/D2: Thông báo in-app gửi tới 1 người dùng. */
@Entity
@Table(name = "notifications", indexes = @Index(name = "idx_noti_recipient", columnList = "recipientId"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Notification {
    @Id
    private String id;
    private String recipientId;
    private String type;        // ATTENDANCE_ALERT | GRADE | INVOICE | ANNOUNCEMENT | ASSIGNMENT
    private String priority;    // NORMAL | IMPORTANT | URGENT
    private String title;
    @Column(length = 2000)
    private String body;
    private boolean read;
    private String refType;
    private String refId;
    @Column(length = 1000)
    private String actionUrl;
    private Instant sentAt;
    private Instant deliveredAt;
    private Instant readAt;
    private Instant createdAt;
}
