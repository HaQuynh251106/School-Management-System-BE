package com.sse.app.identity;

import com.sse.app.audit.AuditService;
import com.sse.app.common.ApiException;
import com.sse.app.identity.IdentityDtos.*;
import com.sse.app.security.JwtService;
import io.jsonwebtoken.Claims;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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

    public AuthController(UserService users, JwtService jwt, AuditService audit) {
        this.users = users;
        this.jwt = jwt;
        this.audit = audit;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest req) {
        User u = users.authenticate(req.username(), req.password());
        audit.record(u.getId(), u.getFullName(), u.getRole(), "LOGIN", "identity",
                "user", u.getId(), "Đăng nhập thành công");
        return tokenResponse(u, true);
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh(@Valid @RequestBody RefreshRequest req) {
        Claims c;
        try {
            c = jwt.parse(req.refreshToken());
        } catch (Exception e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
        }
        if (!"refresh".equals(c.get("type", String.class))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
        }
        User u = users.getById(c.getSubject());
        if (!"ACTIVE".equals(u.getStatus())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Tài khoản bị khóa");
        }
        return tokenResponse(u, false);
    }

    @PostMapping("/logout")
    public Map<String, Object> logout() {
        return Map.of("ok", true);
    }

    @PostMapping("/forgot-password")
    public Map<String, Object> forgotPassword(@RequestBody ForgotPasswordRequest req) {
        String devToken = users.requestPasswordReset(req.email(), req.username());
        Map<String, Object> body = new HashMap<>();
        body.put("ok", true);
        body.put("message", "Nếu email tồn tại, link reset đã được gửi.");
        // DEV: không có email server nên trả token trực tiếp để test luồng reset.
        if (devToken != null) body.put("devResetToken", devToken);
        return body;
    }

    @PostMapping("/reset-password")
    public Map<String, Object> resetPassword(@Valid @RequestBody ResetPasswordRequest req) {
        users.confirmPasswordReset(req.token(), req.newPassword());
        return Map.of("ok", true);
    }

    private Map<String, Object> tokenResponse(User u, boolean includeUser) {
        Map<String, Object> body = new HashMap<>();
        if (includeUser) body.put("user", users.toDto(u));
        body.put("accessToken", jwt.createAccessToken(u.getId(), u.getUsername(), u.getRole()));
        body.put("refreshToken", jwt.createRefreshToken(u.getId()));
        body.put("expiresIn", jwt.accessTtlSeconds());
        return body;
    }
}
