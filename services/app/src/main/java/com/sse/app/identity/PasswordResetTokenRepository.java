package com.sse.app.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, String> {
    Optional<PasswordResetToken> findByTokenHash(String tokenHash);
    Optional<PasswordResetToken> findByTokenHashAndPurpose(String tokenHash, String purpose);
    List<PasswordResetToken> findByUserIdAndPurposeAndUsedAtIsNull(String userId, String purpose);
}
