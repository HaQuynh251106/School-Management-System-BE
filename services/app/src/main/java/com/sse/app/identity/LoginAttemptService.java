package com.sse.app.identity;

import com.sse.app.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/** Giới hạn đăng nhập sai theo tài khoản + địa chỉ mạng cho một instance Backend. */
@Service
public class LoginAttemptService {
    private static final int MAX_FAILURES = 5;
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private final ConcurrentHashMap<String, Attempt> attempts = new ConcurrentHashMap<>();

    public void assertAllowed(String username, String ipAddress) {
        String key = key(username, ipAddress);
        Attempt attempt = attempts.get(key);
        if (attempt == null) return;
        if (attempt.expiresAt().isBefore(Instant.now())) {
            attempts.remove(key, attempt);
            return;
        }
        if (attempt.failures() >= MAX_FAILURES) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "Đăng nhập sai quá nhiều lần. Vui lòng thử lại sau 15 phút.");
        }
    }

    public void failed(String username, String ipAddress) {
        String key = key(username, ipAddress);
        Instant now = Instant.now();
        attempts.compute(key, (ignored, existing) -> existing == null || existing.expiresAt().isBefore(now)
                ? new Attempt(1, now.plus(WINDOW))
                : new Attempt(existing.failures() + 1, existing.expiresAt()));
    }

    public void succeeded(String username, String ipAddress) {
        attempts.remove(key(username, ipAddress));
    }

    /**
     * Xóa giới hạn của một tài khoản trên mọi địa chỉ mạng. Admin dùng thao tác
     * này sau khi tạo/cấp lại mật khẩu; nếu không, người dùng vẫn có thể bị giữ
     * ở trạng thái khóa 15 phút dù thông tin đăng nhập mới đã hợp lệ.
     */
    public void clearForUsername(String username) {
        String normalized = normalizeUsername(username);
        if (normalized.isEmpty()) return;
        String prefix = normalized + "|";
        attempts.keySet().removeIf(value -> value.startsWith(prefix));
    }

    private String key(String username, String ipAddress) {
        return normalizeUsername(username)
                + "|" + (ipAddress == null ? "unknown" : ipAddress);
    }

    private String normalizeUsername(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    private record Attempt(int failures, Instant expiresAt) {}
}
