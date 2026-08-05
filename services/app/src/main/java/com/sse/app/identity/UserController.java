package com.sse.app.identity;

import com.sse.app.audit.AuditService;
import com.sse.app.common.ApiException;
import com.sse.app.identity.IdentityDtos.*;
import com.sse.app.security.CurrentUser;
import com.sse.app.security.CurrentUserHolder;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/users")
public class UserController {
    private final UserService users;
    private final AuditService audit;

    public UserController(UserService users, AuditService audit) {
        this.users = users;
        this.audit = audit;
    }

    @GetMapping
    public List<UserDto> list(
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String classId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "false") boolean includeDeleted) {
        CurrentUserHolder.requirePermission("IDENTITY_USER_READ");
        return users.list(role, q, classId, status, includeDeleted);
    }

    @GetMapping("/{id}")
    public UserDto get(@PathVariable String id) {
        CurrentUser actor = CurrentUserHolder.require();
        if (!users.canReadUser(actor.id(), id,
                actor.hasPermission("IDENTITY_USER_READ"))) {
            throw ApiException.forbidden("Khong co quyen xem tai khoan nay");
        }
        return users.dtoById(id);
    }

    @PostMapping
    public UserDto create(@Valid @RequestBody CreateUserRequest request) {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requirePermission("IDENTITY_USER_CREATE");
        UserDto created = users.create(request, actor.id());
        audit(actor, "USER_CREATE", created.id(),
                "Tao tai khoan " + created.username() + "; status=" + created.status());
        return created;
    }

    @PutMapping("/{id}")
    public UserDto update(@PathVariable String id,
                          @RequestBody UpdateUserRequest request) {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requirePermission("IDENTITY_USER_UPDATE");
        UserDto updated = users.update(id, request);
        audit(actor, "USER_UPDATE", id, "Cap nhat ho so " + updated.username());
        return updated;
    }

    @PostMapping("/{id}/lock")
    public UserDto lock(@PathVariable String id) {
        return changeStatus(id, "LOCKED");
    }

    @PostMapping("/{id}/unlock")
    public UserDto unlock(@PathVariable String id) {
        return changeStatus(id, "ACTIVE");
    }

    @PostMapping("/{id}/pending")
    public UserDto pending(@PathVariable String id) {
        return changeStatus(id, "PENDING");
    }

    @PostMapping("/{id}/reset-password")
    public PasswordResetResult resetPassword(
            @PathVariable String id,
            @Valid @RequestBody AdminResetPasswordRequest request) {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requirePermission("IDENTITY_USER_RESET_PASSWORD");
        if (actor.id().equals(id)) {
            throw ApiException.badRequest(
                    "Hay dung chuc nang doi mat khau cua tai khoan dang dang nhap");
        }
        PasswordResetResult result = users.adminResetPassword(
                id, request.newPassword(), actor.id());
        audit(actor, "PASSWORD_RESET_BY_ADMIN", id,
                "Reset mat khau; revokedSessions=" + result.revokedSessions()
                        + "; reason=" + request.reason());
        return result;
    }

    @DeleteMapping("/{id}")
    public UserDto delete(@PathVariable String id,
                          @Valid @RequestBody DeleteUserRequest request) {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requirePermission("IDENTITY_USER_DELETE");
        UserDto deleted = users.softDelete(id, actor.id(), request.reason());
        audit(actor, "USER_SOFT_DELETE", id, "Ly do=" + request.reason());
        return deleted;
    }

    @PostMapping("/{id}/restore")
    public UserDto restore(@PathVariable String id,
                           @Valid @RequestBody RestoreUserRequest request) {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requirePermission("IDENTITY_USER_RESTORE");
        UserDto restored = users.restore(
                id, actor.id(), request.status(), request.reason());
        audit(actor, "USER_RESTORE", id,
                "Status=" + restored.status() + "; reason=" + request.reason());
        return restored;
    }

    @GetMapping("/{id}/login-history")
    public List<LoginHistory> loginHistory(@PathVariable String id) {
        CurrentUserHolder.requirePermission("IDENTITY_LOGIN_HISTORY_READ");
        return users.loginHistory(id);
    }

    @GetMapping("/{id}/sessions")
    public List<SessionResponse> sessions(@PathVariable String id) {
        CurrentUserHolder.requirePermission("IDENTITY_SESSION_MANAGE_ANY");
        return users.activeSessions(id, null);
    }

    @DeleteMapping("/{id}/sessions/{sessionId}")
    public Map<String, Object> revokeSession(
            @PathVariable String id, @PathVariable String sessionId) {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requirePermission("IDENTITY_SESSION_MANAGE_ANY");
        users.revokeSession(id, sessionId, actor.id(), "ADMIN_REVOKED");
        audit(actor, "SESSION_REVOKE", id, "Session=" + sessionId);
        return Map.of("ok", true);
    }

    @DeleteMapping("/{id}/sessions")
    public Map<String, Object> revokeSessions(@PathVariable String id) {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requirePermission("IDENTITY_SESSION_MANAGE_ANY");
        int count = users.revokeAllRefreshTokens(id, actor.id(), "ADMIN_REVOKED_ALL");
        audit(actor, "SESSION_REVOKE_ALL", id, "Revoked=" + count);
        return Map.of("ok", true, "revokedSessions", count);
    }

    @GetMapping("/{id}/devices")
    public List<UserDevice> devices(@PathVariable String id) {
        CurrentUserHolder.requirePermission("IDENTITY_DEVICE_MANAGE_ANY");
        return users.devices(id, true);
    }

    @DeleteMapping("/{id}/devices/{deviceId}")
    public UserDevice deactivateDevice(
            @PathVariable String id, @PathVariable String deviceId) {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requirePermission("IDENTITY_DEVICE_MANAGE_ANY");
        UserDevice device = users.deactivateDevice(
                id, deviceId, actor.id(), "ADMIN_DEACTIVATED");
        audit(actor, "DEVICE_DEACTIVATE", id, "Device=" + deviceId);
        return device;
    }

    private UserDto changeStatus(String id, String status) {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requirePermission("IDENTITY_USER_LOCK");
        if (actor.id().equals(id) && !"ACTIVE".equals(status)) {
            throw ApiException.badRequest(
                    "Admin khong the tu khoa tai khoan dang dang nhap");
        }
        UserDto changed = users.changeStatus(id, status, actor.id());
        audit(actor, "USER_STATUS_CHANGE", id, "Status=" + status);
        return changed;
    }

    private void audit(CurrentUser actor, String action, String entityId, String detail) {
        audit.record(actor.id(), actor.username(), actor.role(), action,
                "identity", "user", entityId, detail);
    }
}
