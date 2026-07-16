package com.sse.app.identity;

import com.sse.app.audit.AuditService;
import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.identity.IdentityDtos.*;
import com.sse.app.security.JwtService;
import io.jsonwebtoken.Claims;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/** E1 + A1: đăng nhập, refresh token, quên/đặt lại mật khẩu. */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final UserService users;
    private final JwtService jwt;
    private final AuditService audit;
    private final RefreshTokenService refreshTokens;
    private final boolean exposeResetToken;
    private final PasswordResetMailer resetMailer;
    private final LoginHistoryService loginHistory;
    private final LoginAttemptService loginAttempts;

    public AuthController(UserService users, JwtService jwt, AuditService audit,
                          RefreshTokenService refreshTokens,
                          PasswordResetMailer resetMailer,
                          LoginHistoryService loginHistory,
                          LoginAttemptService loginAttempts,
                          @Value("${sse.password-reset.expose-token:false}") boolean exposeResetToken) {
        this.users = users;
        this.jwt = jwt;
        this.audit = audit;
        this.refreshTokens = refreshTokens;
        this.exposeResetToken = exposeResetToken;
        this.resetMailer = resetMailer;
        this.loginHistory = loginHistory;
        this.loginAttempts = loginAttempts;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest req, HttpServletRequest request) {
        String ipAddress = clientIp(request);
        loginAttempts.assertAllowed(req.username(), ipAddress);
        try {
            User u = users.authenticate(req.username(), req.password());
            loginAttempts.succeeded(req.username(), ipAddress);
            loginHistory.record(u.getId(), u.getUsername(), true, null, ipAddress, request.getHeader("User-Agent"));
            audit.record(u.getId(), u.getFullName(), u.getRole(), "LOGIN", "identity",
                    "user", u.getId(), "Đăng nhập thành công");
            return tokenResponse(u, true, request);
        } catch (ApiException e) {
            if (e.getStatus() == HttpStatus.UNAUTHORIZED) loginAttempts.failed(req.username(), ipAddress);
            String userId = users.findByUsername(req.username()).map(User::getId).orElse(null);
            loginHistory.record(userId, req.username(), false, e.getMessage(), ipAddress, request.getHeader("User-Agent"));
            throw e;
        }
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh(@Valid @RequestBody RefreshRequest req, HttpServletRequest request) {
        Claims c;
        try {
            c = jwt.parse(req.refreshToken());
        } catch (Exception e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
        }
        if (!"refresh".equals(c.get("type", String.class))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
        }
        refreshTokens.consume(c.getId(), req.refreshToken(), clientIp(request), request.getHeader("User-Agent"));
        User u = users.getById(c.getSubject());
        if (!"ACTIVE".equals(u.getStatus())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Tài khoản bị khóa");
        }
        return tokenResponse(u, false, request);
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(@RequestBody(required = false) LogoutRequest req) {
        if (req != null && req.refreshToken() != null && !req.refreshToken().isBlank()) {
            refreshTokens.revoke(req.refreshToken());
        }
        return Map.of("ok", true);
    }

    @PostMapping("/forgot-password")
    public Map<String, Object> forgotPassword(@RequestBody ForgotPasswordRequest req) {
        UserService.PasswordResetIssue issue = users.requestPasswordReset(req.email(), req.username());
        Map<String, Object> body = new HashMap<>();
        body.put("ok", true);
        body.put("message", "Nếu email tồn tại, link reset đã được gửi.");
        // DEV: không có email server nên trả token trực tiếp để test luồng reset.
        if (issue != null) resetMailer.send(issue.email(), issue.token());
        if (exposeResetToken && issue != null) body.put("devResetToken", issue.token());
        return body;
    }

    @PostMapping("/reset-password")
    public Map<String, Object> resetPassword(@Valid @RequestBody ResetPasswordRequest req) {
        users.confirmPasswordReset(req.token(), req.newPassword());
        return Map.of("ok", true);
    }

    private Map<String, Object> tokenResponse(User u, boolean includeUser, HttpServletRequest request) {
        Map<String, Object> body = new HashMap<>();
        if (includeUser) body.put("user", users.toDto(u));
        body.put("accessToken", jwt.createAccessToken(u.getId(), u.getUsername(), u.getRole(), u.getTokenVersion()));
        String tokenId = Ids.gen("rt");
        String refreshToken = jwt.createRefreshToken(u.getId(), tokenId);
        refreshTokens.store(tokenId, u.getId(), refreshToken, jwt.refreshTtlSeconds(),
                clientIp(request), request.getHeader("User-Agent"));
        body.put("refreshToken", refreshToken);
        body.put("expiresIn", jwt.accessTtlSeconds());
        return body;
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded == null || forwarded.isBlank() ? request.getRemoteAddr() : forwarded.split(",")[0].trim();
    }
}
