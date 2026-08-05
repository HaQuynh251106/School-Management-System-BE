package com.sse.app.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "two_factor_credentials")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class TwoFactorCredential {
    @Id
    private String userId;
    @Column(nullable = false, columnDefinition = "TEXT")
    private String secretCiphertext;
    @Builder.Default
    private boolean enabled = false;
    private Instant createdAt;
    private Instant enabledAt;
    private Long lastUsedCounter;
}
