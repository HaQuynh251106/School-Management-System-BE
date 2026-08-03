package com.sse.app.identity;

import com.sse.app.common.ApiException;
import com.sse.app.security.CurrentUser;
import com.sse.app.security.CurrentUserHolder;
import com.sse.app.audit.AuditService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Hồ sơ của chính người đang đăng nhập + danh sách con (D1). */
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

    @GetMapping("/me/children")
    public List<UserDto> children() {
        CurrentUser me = CurrentUserHolder.require();
        if (!me.isParent()) throw ApiException.forbidden("Chỉ phụ huynh");
        return users.childrenOf(me.id());
    }

    @PutMapping("/me/password")
    public Map<String, Object> changePassword(
            @Valid @RequestBody IdentityDtos.ChangePasswordRequest request) {
        CurrentUser current = CurrentUserHolder.require();
        users.changePassword(current.id(), request.currentPassword(), request.newPassword());
        audit.record(current.id(), current.username(), current.role(), "PASSWORD_CHANGED", "identity",
                "user", current.id(), "Người dùng chủ động đổi mật khẩu; các phiên cũ đã bị thu hồi");
        return Map.of("ok", true, "reauthenticationRequired", true);
    }

    @PutMapping("/me/profile")
    public UserDto updateProfile(@Valid @RequestBody IdentityDtos.UpdateMyProfileRequest request) {
        return users.updateMyProfile(CurrentUserHolder.require().id(), request);
    }
}
