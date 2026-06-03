package com.sse.app.identity;

import com.sse.app.identity.IdentityDtos.*;
import com.sse.app.security.CurrentUserHolder;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** A1: Quản trị người dùng & phân quyền. Drop-in route /users (json-server). */
@RestController
@RequestMapping("/users")
public class UserController {

    private final UserService users;

    public UserController(UserService users) {
        this.users = users;
    }

    @GetMapping
    public List<UserDto> list(@RequestParam(required = false) String role,
                              @RequestParam(required = false) String q,
                              @RequestParam(required = false) String classId) {
        CurrentUserHolder.requireRole("ADMIN", "TEACHER");
        return users.list(role, q, classId);
    }

    @GetMapping("/{id}")
    public UserDto get(@PathVariable String id) {
        CurrentUserHolder.require();
        return users.dtoById(id);
    }

    @PostMapping
    public UserDto create(@Valid @RequestBody CreateUserRequest req) {
        CurrentUserHolder.requireRole("ADMIN");
        return users.create(req);
    }

    @PutMapping("/{id}")
    public UserDto update(@PathVariable String id, @RequestBody UpdateUserRequest req) {
        CurrentUserHolder.requireRole("ADMIN");
        return users.update(id, req);
    }

    @PostMapping("/{id}/lock")
    public UserDto lock(@PathVariable String id) {
        CurrentUserHolder.requireRole("ADMIN");
        return users.setStatus(id, "LOCKED");
    }

    @PostMapping("/{id}/unlock")
    public UserDto unlock(@PathVariable String id) {
        CurrentUserHolder.requireRole("ADMIN");
        return users.setStatus(id, "ACTIVE");
    }

    @PostMapping("/{id}/reset-password")
    public Map<String, Object> resetPassword(@PathVariable String id,
                                             @RequestBody(required = false) AdminResetPasswordRequest req) {
        CurrentUserHolder.requireRole("ADMIN");
        String pwd = users.adminResetPassword(id, req == null ? null : req.newPassword());
        return Map.of("ok", true, "password", pwd);
    }
}
