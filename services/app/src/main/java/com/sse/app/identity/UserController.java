package com.sse.app.identity;

import com.sse.app.identity.IdentityDtos.*;
import com.sse.app.audit.AuditService;
import com.sse.app.common.ApiException;
import com.sse.app.common.PageResponse;
import com.sse.app.security.CurrentUserHolder;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Value;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A1: Quản trị người dùng & phân quyền. Drop-in route /users (json-server). */
@RestController
@RequestMapping("/users")
public class UserController {

    private final UserService users;
    private final UserImportService imports;
    private final LoginHistoryService loginHistory;
    private final PasswordResetMailer resetMailer;
    private final AuditService audit;
    private final boolean exposeResetToken;

    public UserController(UserService users, UserImportService imports, LoginHistoryService loginHistory,
                          PasswordResetMailer resetMailer, AuditService audit,
                          @Value("${sse.password-reset.expose-token:false}") boolean exposeResetToken) {
        this.users = users;
        this.imports = imports;
        this.loginHistory = loginHistory;
        this.resetMailer = resetMailer;
        this.audit = audit;
        this.exposeResetToken = exposeResetToken;
    }

    @GetMapping
    public List<UserDto> list(@RequestParam(required = false) String role,
                              @RequestParam(required = false) String q,
                              @RequestParam(required = false) String classId) {
        CurrentUserHolder.requireRole("ADMIN", "ACADEMIC_STAFF", "TEACHER");
        var current = CurrentUserHolder.require();
        return current.canManageAcademics()
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
        CurrentUserHolder.requireRole("ADMIN", "ACADEMIC_STAFF", "TEACHER");
        var current = CurrentUserHolder.require();
        return current.canManageAcademics()
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
        var actor = CurrentUserHolder.require();
        UserService.AdminResetResult result = users.adminResetAuthentication(id);
        Map<String, Object> body = new HashMap<>();
        body.put("ok", true);
        body.put("authType", result.authType());
        body.put("action", result.action());
        body.put("mustChangePassword", result.mustChangePassword());
        body.put("message", result.message());
        if (result.issue() != null) {
            boolean delivered = resetMailer.send(result.issue().email(), result.issue().token());
            body.put("deliveryChannel", delivered ? "EMAIL" : "UNAVAILABLE");
            if (!delivered) {
                body.put("message", "Yêu cầu reset đã được tạo nhưng kênh email chưa sẵn sàng; "
                        + "hãy kiểm tra cấu hình SMTP hoặc dùng token DEV trong môi trường local.");
            }
            if (exposeResetToken) body.put("devResetToken", result.issue().token());
        }
        audit.record(actor.id(), actor.username(), actor.role(), "RESET_AUTHENTICATION",
                "identity", "user", id, "Reset authentication: " + result.action());
        return body;
    }
}
