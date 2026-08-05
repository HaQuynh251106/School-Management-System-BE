package com.sse.app.identity;

import com.sse.app.audit.AuditService;
import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.identity.IdentityDtos.*;
import com.sse.app.security.JwtService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {
    private final UserService users;
    private final JwtService jwt;
    private final AuditService audit;

    public AuthController(UserService users, JwtService jwt, AuditService audit) {
        this.users = users;
        this.jwt = jwt;
        this.audit = audit;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest request,
                                     HttpServletRequest http) {
        String ip = clientIp(http);
        String userAgent = http.getHeader("User-Agent");
        try {
            User user = users.authenticate(request.username(), request.password(), ip, userAgent);
            UserDevice device = users.registerDevice(
                    user.getId(), request.deviceToken(), request.platform(),
                    request.deviceName(), ip, userAgent);
            audit.record(user.getId(), user.getFullName(), user.getRole(),
                    "LOGIN", "identity", "user", user.getId(),
                    "Dang nhap thanh cong; ip=" + ip);
            return tokenResponse(user, true, ip, userAgent,
                    device == null ? null : device.getId());
        } catch (ApiException exception) {
            audit.record(null, request.username(), null,
                    "LOGIN_FAILED", "identity", "user", null,
                    "Dang nhap that bai; ip=" + ip
                            + "; status=" + exception.getStatus().value());
            throw exception;
        }
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh(@Valid @RequestBody RefreshRequest request,
                                       HttpServletRequest http) {
        Claims claims;
        try {
            claims = jwt.parse(request.refreshToken());
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
        }
        if (!"refresh".equals(claims.get("type", String.class))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
        }
        Integer sessionVersion = claims.get("sv", Integer.class);
        if (sessionVersion == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
        }
        SessionRotation rotation = users.verifyAndRotateRefreshToken(
                request.refreshToken(), sessionVersion);
        if (!rotation.user().getId().equals(claims.getSubject())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
        }
        return tokenResponse(rotation.user(), false, clientIp(http),
                http.getHeader("User-Agent"), rotation.deviceId());
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(
            @RequestBody(required = false) LogoutRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String refreshToken = request == null ? null : request.refreshToken();
        if (refreshToken == null && authorization != null
                && authorization.startsWith("Bearer ")) {
            refreshToken = authorization.substring("Bearer ".length());
        }
        users.revokeRefreshToken(refreshToken, null, "LOGOUT");
        return Map.of("ok", true);
    }

    @PostMapping("/forgot-password")
    public Map<String, Object> forgotPassword(@RequestBody ForgotPasswordRequest request) {
        users.requestPasswordReset(request.email(), request.username());
        return Map.of(
                "ok", true,
                "message", "Neu tai khoan ton tai, huong dan dat lai mat khau da duoc gui.");
    }

    @PostMapping("/reset-password")
    public Map<String, Object> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request) {
        users.confirmPasswordReset(request.token(), request.newPassword());
        audit.record(null, null, null, "PASSWORD_RESET_COMPLETED", "identity",
                "user", null, "Dat lai mat khau bang reset token");
        return Map.of("ok", true);
    }

    private Map<String, Object> tokenResponse(
            User user, boolean includeUser, String ipAddress,
            String userAgent, String deviceId) {
        String sessionId = Ids.gen("rt");
        String accessToken = jwt.createAccessToken(
                user.getId(), user.getUsername(), user.getRole(),
                user.getSessionVersion(), sessionId);
        String refreshToken = jwt.createRefreshToken(
                user.getId(), user.getSessionVersion(), sessionId);
        users.storeRefreshToken(sessionId, user.getId(), refreshToken,
                jwt.refreshTtlSeconds(), ipAddress, userAgent,
                deviceId, user.getSessionVersion());

        Map<String, Object> body = new HashMap<>();
        if (includeUser) body.put("user", users.toDto(user));
        body.put("accessToken", accessToken);
        body.put("refreshToken", refreshToken);
        body.put("sessionId", sessionId);
        body.put("expiresIn", jwt.accessTtlSeconds());
        return body;
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
