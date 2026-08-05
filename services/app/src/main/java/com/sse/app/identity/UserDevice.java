package com.sse.app.identity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** S3: FCM/Web push device registration for notification dispatch. */
@Entity
@Table(name = "user_devices", indexes = @Index(name = "idx_ud_user_active", columnList = "userId,active"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UserDevice {
    @Id
    private String id;

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false, length = 1000)
    private String deviceToken;

    @Column(nullable = false)
    private String platform;

    private String deviceName;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false)
    private Instant lastSeenAt;

    private String lastIpAddress;

    @Column(length = 1000)
    private String lastUserAgent;

    private Instant deactivatedAt;
    private String deactivatedBy;
    private String deactivationReason;

    @Column(nullable = false)
    private Instant createdAt;
}
