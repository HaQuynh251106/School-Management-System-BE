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
public class MeController {
    private final UserService users;
    private final AuditService audit;

    public MeController(UserService users, AuditService audit) {
        this.users = users;
        this.audit = audit;
    }

    @GetMapping("/me")
    public UserDto me() {
        return users.dtoById(CurrentUserHolder.require().id());
    }

    @PutMapping("/me/password")
    public Map<String, Object> changePassword(
            @Valid @RequestBody ChangePasswordRequest request) {
        CurrentUser actor = CurrentUserHolder.require();
        users.changeOwnPassword(
                actor.id(), request.currentPassword(), request.newPassword());
        audit(actor, "PASSWORD_CHANGE", "Doi mat khau va thu hoi toan bo phien");
        return Map.of("ok", true, "sessionsRevoked", true);
    }

    @GetMapping("/me/children")
    public List<UserDto> children() {
        CurrentUser me = CurrentUserHolder.require();
        if (!me.isParent()) throw ApiException.forbidden("Chi phu huynh");
        return users.childrenOf(me.id());
    }

    @PostMapping("/me/devices")
    public UserDevice registerDevice(@Valid @RequestBody RegisterDeviceRequest request) {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requirePermission("IDENTITY_DEVICE_MANAGE_SELF");
        UserDevice device = users.registerDevice(actor.id(), request);
        audit(actor, "DEVICE_REGISTER", "Device=" + device.getId());
        return device;
    }

    @GetMapping("/me/devices")
    public List<UserDevice> devices() {
        CurrentUserHolder.requirePermission("IDENTITY_DEVICE_MANAGE_SELF");
        return users.devices(CurrentUserHolder.require().id(), true);
    }

    @DeleteMapping("/me/devices/{deviceId}")
    public UserDevice deactivateDevice(@PathVariable String deviceId) {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requirePermission("IDENTITY_DEVICE_MANAGE_SELF");
        UserDevice device = users.deactivateDevice(
                actor.id(), deviceId, actor.id(), "USER_DEACTIVATED");
        audit(actor, "DEVICE_DEACTIVATE", "Device=" + deviceId);
        return device;
    }

    @GetMapping("/me/sessions")
    public List<SessionResponse> sessions() {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requirePermission("IDENTITY_SESSION_MANAGE_SELF");
        return users.activeSessions(actor.id(), actor.sessionId());
    }

    @DeleteMapping("/me/sessions/{sessionId}")
    public Map<String, Object> revokeSession(@PathVariable String sessionId) {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requirePermission("IDENTITY_SESSION_MANAGE_SELF");
        users.revokeSession(
                actor.id(), sessionId, actor.id(), "USER_REVOKED");
        audit(actor, "SESSION_REVOKE", "Session=" + sessionId);
        return Map.of("ok", true);
    }

    @DeleteMapping("/me/sessions")
    public Map<String, Object> revokeAllSessions() {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requirePermission("IDENTITY_SESSION_MANAGE_SELF");
        int count = users.revokeAllRefreshTokens(
                actor.id(), actor.id(), "USER_REVOKED_ALL");
        audit(actor, "SESSION_REVOKE_ALL", "Revoked=" + count);
        return Map.of("ok", true, "revokedSessions", count);
    }

    private void audit(CurrentUser actor, String action, String detail) {
        audit.record(actor.id(), actor.username(), actor.role(), action,
                "identity", "user", actor.id(), detail);
    }
}
