package com.sse.app.identity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** E1/A1: audit-friendly login attempt history. */
@Entity
@Table(name = "login_history", indexes = {
        @Index(name = "idx_lh_user_time", columnList = "userId,createdAt"),
        @Index(name = "idx_lh_username_time", columnList = "username,createdAt")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LoginHistory {
    @Id
    private String id;

    private String userId;
    private String username;
    private String ipAddress;

    @Column(length = 1000)
    private String userAgent;

    @Column(nullable = false)
    private boolean success;

    private String failureReason;

    @Column(nullable = false)
    private Instant createdAt;
}
