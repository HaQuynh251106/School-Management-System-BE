package com.sse.app.identity;

import com.sse.app.audit.AuditService;
import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.identity.IdentityDtos.*;
import com.sse.app.security.JwtService;
import io.jsonwebtoken.Claims;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** E1 + A1: đăng nhập, refresh token, quên/đặt lại mật khẩu. */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final String REFRESH_COOKIE = "sse_refresh";
    private static final long RECOVERY_COOLDOWN_MILLIS = Duration.ofSeconds(60).toMillis();
    private static final int MAX_RECOVERY_RATE_KEYS = 10_000;

    private final UserService users;
    private final JwtService jwt;
    private final AuditService audit;
    private final RefreshTokenService refreshTokens;
    private final boolean exposeResetToken;
    private final PasswordResetMailer resetMailer;
    private final LoginHistoryService loginHistory;
    private final LoginAttemptService loginAttempts;
    private final boolean secureRefreshCookie;
    private final Map<String, Long> recoveryRequests = new ConcurrentHashMap<>();

    public AuthController(UserService users, JwtService jwt, AuditService audit,
                          RefreshTokenService refreshTokens,
                          PasswordResetMailer resetMailer,
                          LoginHistoryService loginHistory,
                          LoginAttemptService loginAttempts,
                          @Value("${sse.password-reset.expose-token:false}") boolean exposeResetToken,
                          @Value("${sse.jwt.cookie-secure:false}") boolean secureRefreshCookie) {
        this.users = users;
        this.jwt = jwt;
        this.audit = audit;
        this.refreshTokens = refreshTokens;
        this.exposeResetToken = exposeResetToken;
        this.resetMailer = resetMailer;
        this.loginHistory = loginHistory;
        this.loginAttempts = loginAttempts;
        this.secureRefreshCookie = secureRefreshCookie;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest req, HttpServletRequest request,
                                     HttpServletResponse response) {
        String ipAddress = clientIp(request);
        loginAttempts.assertAllowed(req.username(), ipAddress);
        try {
            User u = users.authenticate(req.username(), req.password());
            loginAttempts.succeeded(req.username(), ipAddress);
            loginHistory.record(u.getId(), u.getUsername(), true, null, ipAddress, request.getHeader("User-Agent"));
            audit.record(u.getId(), u.getFullName(), u.getRole(), "LOGIN", "identity",
                    "user", u.getId(), "Đăng nhập thành công");
            return tokenResponse(u, true, request, response);
        } catch (ApiException e) {
            if (e.getStatus() == HttpStatus.UNAUTHORIZED) loginAttempts.failed(req.username(), ipAddress);
            String userId = users.findByUsername(req.username()).map(User::getId).orElse(null);
            loginHistory.record(userId, req.username(), false, e.getMessage(), ipAddress, request.getHeader("User-Agent"));
            throw e;
        }
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh(@RequestBody(required = false) RefreshRequest req,
                                       @CookieValue(name = REFRESH_COOKIE, required = false) String cookieToken,
                                       HttpServletRequest request, HttpServletResponse response) {
        String suppliedToken = req != null && req.refreshToken() != null && !req.refreshToken().isBlank()
                ? req.refreshToken() : cookieToken;
        if (suppliedToken == null || suppliedToken.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Missing refresh token");
        }
        Claims c;
        try {
            c = jwt.parse(suppliedToken);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
        }
        if (!"refresh".equals(c.get("type", String.class))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
        }
        refreshTokens.consume(c.getId(), suppliedToken, clientIp(request), request.getHeader("User-Agent"));
        User u = users.getById(c.getSubject());
        if (!"ACTIVE".equals(u.getStatus())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Tài khoản bị khóa");
        }
        return tokenResponse(u, false, request, response);
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(@RequestBody(required = false) LogoutRequest req,
                                      @CookieValue(name = REFRESH_COOKIE, required = false) String cookieToken,
                                      HttpServletResponse response) {
        String suppliedToken = req != null && req.refreshToken() != null && !req.refreshToken().isBlank()
                ? req.refreshToken() : cookieToken;
        if (suppliedToken != null && !suppliedToken.isBlank()) {
            refreshTokens.revoke(suppliedToken);
        }
        clearRefreshCookie(response);
        return Map.of("ok", true);
    }

    @PostMapping("/forgot-password")
    public Map<String, Object> forgotPassword(@RequestBody ForgotPasswordRequest req,
                                              HttpServletRequest request) {
        if (!acquireRecoverySlot(req, request)) return recoveryResponse();
        UserService.PasswordResetIssue issue = users.requestPasswordReset(req.email(), req.username());
        Map<String, Object> body = recoveryResponse();
        if (issue != null) {
            if ("ACTIVATION_LINK".equals(issue.purpose())) resetMailer.sendActivation(issue.email(), issue.token());
            else resetMailer.sendReset(issue.email(), issue.token());
            audit.record(issue.userId(), "SELF_SERVICE", "PUBLIC", "PASSWORD_RECOVERY_REQUESTED",
                    "identity", "user", issue.userId(), "Đã yêu cầu liên kết " + issue.purpose());
        }
        if (exposeResetToken && issue != null) body.put("devResetToken", issue.token());
        if (exposeResetToken && issue != null) body.put("devPurpose", issue.purpose());
        return body;
    }

    private Map<String, Object> recoveryResponse() {
        Map<String, Object> body = new HashMap<>();
        body.put("ok", true);
        body.put("message", "Nếu thông tin hợp lệ, liên kết truy cập sẽ được gửi qua email.");
        return body;
    }

    private boolean acquireRecoverySlot(ForgotPasswordRequest req, HttpServletRequest request) {
        long now = System.currentTimeMillis();
        if (recoveryRequests.size() > MAX_RECOVERY_RATE_KEYS) {
            recoveryRequests.entrySet().removeIf(entry -> now - entry.getValue() >= RECOVERY_COOLDOWN_MILLIS);
        }
        String email = req == null || req.email() == null ? "" : req.email().trim().toLowerCase(Locale.ROOT);
        String username = req == null || req.username() == null ? "" : req.username().trim().toLowerCase(Locale.ROOT);
        String key = clientIp(request) + "|" + email + "|" + username;
        Long previous = recoveryRequests.putIfAbsent(key, now);
        if (previous == null) return true;
        if (now - previous < RECOVERY_COOLDOWN_MILLIS) return false;
        return recoveryRequests.replace(key, previous, now);
    }

    @PostMapping("/reset-password")
    public Map<String, Object> resetPassword(@Valid @RequestBody ResetPasswordRequest req) {
        UserDto user = users.confirmPasswordReset(req.token(), req.newPassword());
        audit.record(user.id(), user.username(), user.role(), "PASSWORD_RESET_COMPLETED",
                "identity", "user", user.id(), "Người dùng tự đặt lại mật khẩu");
        return Map.of("ok", true);
    }

    @PostMapping("/activate")
    public Map<String, Object> activate(@Valid @RequestBody ActivateAccountRequest req) {
        UserDto user = users.confirmActivation(req.token(), req.newPassword());
        audit.record(user.id(), user.username(), user.role(), "ACCOUNT_ACTIVATED",
                "identity", "user", user.id(), "Người dùng hoàn tất kích hoạt tài khoản");
        return Map.of("ok", true);
    }

    private Map<String, Object> tokenResponse(User u, boolean includeUser, HttpServletRequest request,
                                              HttpServletResponse response) {
        Map<String, Object> body = new HashMap<>();
        if (includeUser) body.put("user", users.toDto(u));
        body.put("accessToken", jwt.createAccessToken(u.getId(), u.getUsername(), u.getRole(), u.getTokenVersion()));
        String tokenId = Ids.gen("rt");
        String refreshToken = jwt.createRefreshToken(u.getId(), tokenId);
        refreshTokens.store(tokenId, u.getId(), refreshToken, jwt.refreshTtlSeconds(),
                clientIp(request), request.getHeader("User-Agent"));
        setRefreshCookie(response, refreshToken);
        // Kept in the response for native/mobile clients. The web client uses the HttpOnly cookie.
        body.put("refreshToken", refreshToken);
        body.put("expiresIn", jwt.accessTtlSeconds());
        return body;
    }

    private void setRefreshCookie(HttpServletResponse response, String token) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE, token)
                .httpOnly(true)
                .secure(secureRefreshCookie)
                .sameSite("Lax")
                .path("/auth")
                .maxAge(Duration.ofSeconds(jwt.refreshTtlSeconds()))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearRefreshCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE, "")
                .httpOnly(true)
                .secure(secureRefreshCookie)
                .sameSite("Lax")
                .path("/auth")
                .maxAge(Duration.ZERO)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded == null || forwarded.isBlank() ? request.getRemoteAddr() : forwarded.split(",")[0].trim();
    }
}
