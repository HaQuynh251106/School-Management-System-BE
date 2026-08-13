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
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** E1 + A1: đăng nhập, refresh token, quên/đặt lại mật khẩu. */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final String REFRESH_COOKIE = "sse_refresh";

    private final UserService users;
    private final JwtService jwt;
    private final AuditService audit;
    private final RefreshTokenService refreshTokens;
    private final boolean exposeResetToken;
    private final PasswordResetMailer resetMailer;
    private final LoginHistoryService loginHistory;
    private final LoginAttemptService loginAttempts;
    private final boolean secureRefreshCookie;
    private final ConcurrentHashMap<String, ForgotWindow> forgotWindows = new ConcurrentHashMap<>();

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
        assertForgotAllowed(clientIp(request));
        UserService.PasswordResetIssue issue = users.requestPasswordReset(req.email(), req.username());
        Map<String, Object> body = new HashMap<>();
        body.put("ok", true);
        body.put("message", "Nếu tài khoản hợp lệ, yêu cầu đặt lại mật khẩu đã được tiếp nhận.");
        if (issue != null) resetMailer.send(issue.email(), issue.token());
        // Không tiết lộ tài khoản có tồn tại hay không: trạng thái này chỉ phụ
        // thuộc cấu hình môi trường và giống nhau với mọi địa chỉ đầu vào.
        body.put("deliveryChannel", resetMailer.isEnabled() ? "EMAIL" : "UNAVAILABLE");
        if (exposeResetToken && issue != null) body.put("devResetToken", issue.token());
        return body;
    }

    private void assertForgotAllowed(String ipAddress) {
        Instant now = Instant.now();
        ForgotWindow window = forgotWindows.compute(ipAddress, (key, current) -> {
            if (current == null || current.startedAt().plus(Duration.ofMinutes(15)).isBefore(now)) {
                return new ForgotWindow(now, 1);
            }
            return new ForgotWindow(current.startedAt(), current.count() + 1);
        });
        if (window.count() > 5) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "Bạn đã gửi quá nhiều yêu cầu. Vui lòng thử lại sau.");
        }
    }

    private record ForgotWindow(Instant startedAt, int count) {}

    @PostMapping("/reset-password")
    public Map<String, Object> resetPassword(@Valid @RequestBody ResetPasswordRequest req) {
        users.confirmPasswordReset(req.token(), req.newPassword());
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
