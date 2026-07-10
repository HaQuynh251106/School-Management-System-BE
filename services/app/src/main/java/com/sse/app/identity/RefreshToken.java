package com.sse.app.identity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** E1: server-side refresh token session with rotation/revocation support. */
@Entity
@Table(name = "refresh_tokens", indexes = {
        @Index(name = "idx_rt_user", columnList = "userId"),
        @Index(name = "idx_rt_hash", columnList = "tokenHash", unique = true)
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class RefreshToken {
    @Id
    private String id;

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false, unique = true, length = 128)
    private String tokenHash;

    @Column(length = 1000)
    private String userAgent;

    private String ipAddress;

    @Column(nullable = false)
    private Instant expiresAt;

    private Instant revokedAt;

    @Column(nullable = false)
    private Instant createdAt;
}
