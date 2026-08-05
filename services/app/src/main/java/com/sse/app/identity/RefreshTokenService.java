package com.sse.app.identity;

import com.sse.app.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Comparator;
import java.util.List;
import com.sse.app.identity.IdentityDtos.SessionView;

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

    public List<SessionView> activeSessions(String userId, String currentRawToken) {
        String currentHash = currentRawToken == null || currentRawToken.isBlank() ? null : hash(currentRawToken);
        Instant now = Instant.now();
        return repository.findByUserId(userId).stream()
                .filter(token -> token.getRevokedAt() == null && token.getExpiresAt().isAfter(now))
                .sorted(Comparator.comparing(RefreshToken::getCreatedAt).reversed())
                .map(token -> new SessionView(token.getId(), deviceLabel(token.getUserAgent()),
                        token.getIpAddress(), token.getCreatedAt(), token.getExpiresAt(),
                        currentHash != null && MessageDigest.isEqual(bytes(token.getTokenHash()), bytes(currentHash))))
                .toList();
    }

    @Transactional
    public boolean revokeSession(String userId, String sessionId) {
        RefreshToken token = repository.findById(sessionId)
                .orElseThrow(() -> ApiException.notFound("Phiên đăng nhập"));
        if (!userId.equals(token.getUserId())) throw ApiException.forbidden("Không có quyền thu hồi phiên này");
        if (token.getRevokedAt() != null) return false;
        token.setRevokedAt(Instant.now());
        repository.save(token);
        return true;
    }

    @Transactional
    public int revokeOtherSessions(String userId, String currentRawToken) {
        String currentHash = currentRawToken == null || currentRawToken.isBlank() ? null : hash(currentRawToken);
        int revoked = 0;
        for (RefreshToken token : repository.findByUserId(userId)) {
            boolean current = currentHash != null
                    && MessageDigest.isEqual(bytes(token.getTokenHash()), bytes(currentHash));
            if (!current && token.getRevokedAt() == null && token.getExpiresAt().isAfter(Instant.now())) {
                token.setRevokedAt(Instant.now());
                repository.save(token);
                revoked++;
            }
        }
        return revoked;
    }

    private static String deviceLabel(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) return "Thiết bị không xác định";
        String browser = userAgent.contains("Edg/") ? "Microsoft Edge"
                : userAgent.contains("Chrome/") ? "Google Chrome"
                : userAgent.contains("Firefox/") ? "Firefox"
                : userAgent.contains("Safari/") ? "Safari" : "Trình duyệt/ứng dụng";
        String platform = userAgent.contains("Windows") ? "Windows"
                : userAgent.contains("Android") ? "Android"
                : userAgent.contains("iPhone") || userAgent.contains("iPad") ? "iOS/iPadOS"
                : userAgent.contains("Mac OS") ? "macOS" : "thiết bị khác";
        return browser + " · " + platform;
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
