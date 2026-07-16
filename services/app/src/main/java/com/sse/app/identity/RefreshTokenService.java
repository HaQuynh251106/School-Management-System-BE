package com.sse.app.identity;

import com.sse.app.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

@Service
public class RefreshTokenService {
    private final RefreshTokenRepository repository;

    public RefreshTokenService(RefreshTokenRepository repository) {
        this.repository = repository;
    }

    public void store(String id, String userId, String rawToken, long ttlSeconds,
                      String ipAddress, String userAgent) {
        repository.save(RefreshToken.builder()
                .id(id)
                .userId(userId)
                .tokenHash(hash(rawToken))
                .createdAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(ttlSeconds))
                .ipAddress(limit(ipAddress, 255))
                .userAgent(limit(userAgent, 1000))
                .build());
    }

    @Transactional
    public void consume(String id, String rawToken, String ipAddress, String userAgent) {
        RefreshToken token = repository.findById(id)
                .orElseThrow(RefreshTokenService::invalid);
        if (!MessageDigest.isEqual(token.getTokenHash().getBytes(StandardCharsets.UTF_8),
                hash(rawToken).getBytes(StandardCharsets.UTF_8))
                || token.getRevokedAt() != null
                || token.getExpiresAt().isBefore(Instant.now())
                || !sameContext(token, ipAddress, userAgent)) {
            token.setRevokedAt(Instant.now());
            repository.save(token);
            throw invalid();
        }
        token.setRevokedAt(Instant.now());
        repository.save(token);
    }

    @Transactional
    public void revoke(String rawToken) {
        repository.findByTokenHash(hash(rawToken)).ifPresent(token -> {
            if (token.getRevokedAt() == null) {
                token.setRevokedAt(Instant.now());
                repository.save(token);
            }
        });
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String limit(String value, int max) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private static boolean sameContext(RefreshToken token, String ipAddress, String userAgent) {
        return MessageDigest.isEqual(bytes(token.getIpAddress()), bytes(limit(ipAddress, 255)))
                && MessageDigest.isEqual(bytes(token.getUserAgent()), bytes(limit(userAgent, 1000)));
    }

    private static byte[] bytes(String value) {
        return (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
    }
}
