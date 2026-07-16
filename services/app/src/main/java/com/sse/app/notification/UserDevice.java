package com.sse.app.notification;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "user_devices", indexes = @Index(name = "idx_user_device_user", columnList = "userId,active"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UserDevice {
    @Id private String id;
    @Column(nullable = false) private String userId;
    @Column(nullable = false, unique = true, length = 1000) private String deviceToken;
    @Column(nullable = false, length = 32) private String platform;
    private boolean active;
    @Column(nullable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;
}
