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
    private String channel;     // IN_APP | EMAIL | PUSH
    private String title;
    @Column(length = 2000)
    private String body;
    private boolean read;
    private String refType;
    private String refId;
    private String status;      // QUEUED | SENT | FAILED | RETRYING
    private Integer attemptCount;
    private Instant sentAt;
    @Column(length = 1000)
    private String errorMessage;
    private Instant createdAt;
}
