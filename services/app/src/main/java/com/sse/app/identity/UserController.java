package com.sse.app.identity;

import com.sse.app.identity.IdentityDtos.*;
import com.sse.app.common.ApiException;
import com.sse.app.common.PageResponse;
import com.sse.app.security.CurrentUserHolder;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

/** A1: Quản trị người dùng & phân quyền. Drop-in route /users (json-server). */
@RestController
@RequestMapping("/users")
public class UserController {

    private final UserService users;
    private final UserImportService imports;
    private final LoginHistoryService loginHistory;

    public UserController(UserService users, UserImportService imports, LoginHistoryService loginHistory) {
        this.users = users;
        this.imports = imports;
        this.loginHistory = loginHistory;
    }

    @GetMapping
    public List<UserDto> list(@RequestParam(required = false) String role,
                              @RequestParam(required = false) String q,
                              @RequestParam(required = false) String classId) {
        CurrentUserHolder.requireRole("ADMIN", "TEACHER");
        var current = CurrentUserHolder.require();
        return current.isAdmin()
                ? users.list(role, q, classId)
                : users.listSummaries(role, q, classId);
    }

    @GetMapping("/page")
    public PageResponse<UserDto> page(@RequestParam(required = false) String role,
                                      @RequestParam(required = false) String q,
                                      @RequestParam(required = false) String classId,
                                      @RequestParam(required = false) String gradeLevel,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "10") int size,
                                      @RequestParam(defaultValue = "fullName") String sort) {
        CurrentUserHolder.requireRole("ADMIN", "TEACHER");
        var current = CurrentUserHolder.require();
        return current.isAdmin()
                ? users.page(role, q, classId, gradeLevel, status, page, size, sort)
                : users.summaryPage(role, q, classId, gradeLevel, status, page, size, sort);
    }

    @GetMapping("/{id}")
    public UserDto get(@PathVariable String id) {
        var current = CurrentUserHolder.require();
        if (!current.isAdmin() && !current.id().equals(id)) {
            if (current.isParent()) {
                users.assertParentOf(current.id(), id);
            } else {
                throw ApiException.forbidden("Không có quyền xem hồ sơ người dùng này");
            }
        }
        return users.dtoById(id);
    }

    @PostMapping
    public UserDto create(@Valid @RequestBody CreateUserRequest req) {
        CurrentUserHolder.requireRole("ADMIN");
        return users.create(req);
    }

    @PostMapping(value = "/import", consumes = "multipart/form-data")
    public ImportResult importUsers(@RequestParam("file") MultipartFile file) {
        CurrentUserHolder.requireRole("ADMIN");
        return imports.importExcel(file);
    }

    @PostMapping(value = "/import/preview", consumes = "multipart/form-data")
    public ImportPreview previewImport(@RequestParam("file") MultipartFile file) {
        CurrentUserHolder.requireRole("ADMIN");
        return imports.preview(file);
    }

    @PostMapping(value = "/import/commit", consumes = "multipart/form-data")
    public ImportResult commitImport(@RequestParam("file") MultipartFile file,
                                     @RequestParam("token") String token,
                                     @RequestParam(defaultValue = "ALL_OR_NOTHING") String strategy) {
        CurrentUserHolder.requireRole("ADMIN");
        return imports.commit(file, token, strategy);
    }

    @GetMapping("/import-template")
    public ResponseEntity<byte[]> importTemplate() {
        CurrentUserHolder.requireRole("ADMIN");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=mau-nhap-nguoi-dung.xlsx")
                .body(imports.template());
    }

    @GetMapping("/{id}/children")
    public List<UserDto> children(@PathVariable String id) {
        var current = CurrentUserHolder.require();
        if (!current.isAdmin() && !current.id().equals(id)) throw ApiException.forbidden("Không có quyền xem liên kết này");
        return users.childrenOf(id);
    }

    @PostMapping("/{id}/children")
    public UserDto linkChild(@PathVariable String id, @Valid @RequestBody LinkChildRequest req) {
        CurrentUserHolder.requireRole("ADMIN");
        return users.linkChild(id, req.studentId(), Boolean.TRUE.equals(req.primaryContact()));
    }

    @DeleteMapping("/{id}/children/{studentId}")
    public void unlinkChild(@PathVariable String id, @PathVariable String studentId) {
        CurrentUserHolder.requireRole("ADMIN");
        users.unlinkChild(id, studentId);
    }

    @GetMapping("/{id}/login-history")
    public List<LoginHistory> loginHistory(@PathVariable String id) {
        var current = CurrentUserHolder.require();
        if (!current.isAdmin() && !current.id().equals(id)) throw ApiException.forbidden("Không có quyền xem lịch sử đăng nhập");
        return loginHistory.list(id);
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
