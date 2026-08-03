package com.sse.app.identity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** Token đặt lại mật khẩu (S2) — phục vụ luồng Quên mật khẩu (A1). */
@Entity
@Table(name = "password_reset_tokens")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PasswordResetToken {

    @Id
    private String id;

    @Column(nullable = false)
    private String userId;

    /** SHA-256 hash của token gửi cho user (không lưu token thô). */
    @Column(nullable = false)
    private String tokenHash;

    @Column(nullable = false)
    private Instant expiresAt;

    private Instant usedAt;

    /** RESET_LINK | ACTIVATION_LINK. */
    @Builder.Default
    @Column(nullable = false)
    private String purpose = "RESET_LINK";

    @Builder.Default
    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}
