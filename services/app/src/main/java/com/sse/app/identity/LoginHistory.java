package com.sse.app.identity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** Lịch sử đăng nhập phục vụ bảo mật và truy vết theo flowchart 2.1. */
@Entity
@Table(name = "login_history", indexes = {
        @Index(name = "idx_login_history_user", columnList = "userId,createdAt"),
        @Index(name = "idx_login_history_username", columnList = "username,createdAt")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LoginHistory {
    @Id
    private String id;
    private String userId;
    @Column(nullable = false)
    private String username;
    private boolean success;
    @Column(length = 500)
    private String failureReason;
    private String ipAddress;
    @Column(length = 1000)
    private String userAgent;
    @Column(nullable = false)
    private Instant createdAt;
}
