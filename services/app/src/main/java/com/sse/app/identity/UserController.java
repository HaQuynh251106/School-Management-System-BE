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

import java.util.List;
import java.util.ArrayList;
import java.util.Map;

/** A1: Quản trị người dùng & phân quyền. Drop-in route /users (json-server). */
@RestController
@RequestMapping("/users")
public class UserController {

    private final UserService users;
    private final UserImportService imports;
    private final LoginHistoryService loginHistory;
    private final AuditService audit;

    public UserController(UserService users, UserImportService imports, LoginHistoryService loginHistory,
                          AuditService audit) {
        this.users = users;
        this.imports = imports;
        this.loginHistory = loginHistory;
        this.audit = audit;
    }

    @GetMapping
    public List<UserDto> list(@RequestParam(required = false) String role,
                              @RequestParam(required = false) String q,
                              @RequestParam(required = false) String classId) {
        CurrentUserHolder.requireRole("ADMIN", "ACADEMIC_STAFF", "TEACHER");
        // This legacy endpoint is only used by compact selectors. Return the
        // privacy-safe, bounded projection; full management data is available
        // through the paginated /users/page endpoint.
        // The parent-by-class exception remains a small, explicitly scoped
        // administrative relationship view and therefore includes child IDs.
        var current = CurrentUserHolder.require();
        if (current.canManageAcademics() && "PARENT".equalsIgnoreCase(role)
                && classId != null && !classId.isBlank()) {
            return users.list(role, q, classId);
        }
        return users.listSummaries(role, q, classId);
    }

    @GetMapping("/page")
    public PageResponse<UserDto> page(@RequestParam(required = false) String role,
                                      @RequestParam(required = false) String q,
                                      @RequestParam(required = false) String classId,
                                      @RequestParam(required = false) String gradeLevel,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(required = false) String accessState,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "10") int size,
                                      @RequestParam(defaultValue = "fullName") String sort) {
        CurrentUserHolder.requireRole("ADMIN", "ACADEMIC_STAFF", "TEACHER");
        var current = CurrentUserHolder.require();
        return current.canManageAcademics()
                ? users.page(role, q, classId, gradeLevel, status, accessState, page, size, sort)
                : users.summaryPage(role, q, classId, gradeLevel, status, accessState, page, size, sort);
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
        assertAdminAccountFields(req.role(), req.classId(), req.className(), req.mainSubject());
        UserDto created = users.create(req);
        var actor = CurrentUserHolder.require();
        audit.record(actor.id(), actor.username(), actor.role(), "ACCOUNT_PROVISIONED", "identity",
                "user", created.id(), "Tạo tài khoản " + created.username() + " · " + created.activationStatus());
        return created;
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
        ImportResult result = imports.commit(file, token, strategy);
        var actor = CurrentUserHolder.require();
        audit.record(actor.id(), actor.username(), actor.role(), "ACCOUNT_IMPORT_COMPLETED", "identity",
                "user_import", null, "Đã nhập " + result.importedRows() + "/" + result.totalRows() + " tài khoản");
        return result;
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
        requireConfirmedException(req.confirmException(), req.reason());
        var actor = CurrentUserHolder.require();
        UserDto result = users.linkChild(id, req.studentId(), Boolean.TRUE.equals(req.primaryContact()));
        audit.record(actor.id(), actor.username(), actor.role(), "LINK_EXCEPTION", "identity",
                "parent_student", id + ":" + req.studentId(), req.reason().trim());
        return result;
    }

    @DeleteMapping("/{id}/children/{studentId}")
    public void unlinkChild(@PathVariable String id, @PathVariable String studentId,
                            @RequestParam(defaultValue = "false") boolean confirmException,
                            @RequestParam String reason) {
        CurrentUserHolder.requireRole("ADMIN");
        requireConfirmedException(confirmException, reason);
        users.unlinkChild(id, studentId);
        var actor = CurrentUserHolder.require();
        audit.record(actor.id(), actor.username(), actor.role(), "UNLINK_EXCEPTION", "identity",
                "parent_student", id + ":" + studentId, reason.trim());
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
        UserDto current = users.dtoById(id);
        assertAdminAccountFields(current.role(), req.classId(), req.className(), req.mainSubject());
        return users.update(id, req);
    }

    @PutMapping("/{id}/specialization")
    public UserDto updateTeacherSpecialization(@PathVariable String id,
                                                @Valid @RequestBody UpdateTeacherSpecializationRequest req) {
        CurrentUserHolder.requireRole("ACADEMIC_STAFF");
        return users.updateTeacherSpecialization(id, req.mainSubject());
    }

    @PostMapping("/{id}/lock")
    public UserDto lock(@PathVariable String id) {
        CurrentUserHolder.requireRole("ADMIN");
        UserDto result = users.setStatus(id, "LOCKED");
        auditAccessAction("ACCOUNT_LOCKED", id, "Khóa tài khoản");
        return result;
    }

    @PostMapping("/{id}/unlock")
    public UserDto unlock(@PathVariable String id) {
        CurrentUserHolder.requireRole("ADMIN");
        UserDto result = users.setStatus(id, "ACTIVE");
        auditAccessAction("ACCOUNT_UNLOCKED", id, "Mở khóa tài khoản");
        return result;
    }

    @GetMapping("/lifecycle/summary")
    public AccountLifecycleSummary lifecycleSummary(@RequestParam(required = false) String role) {
        CurrentUserHolder.requireRole("ADMIN");
        return users.lifecycleSummary(role);
    }

    @PostMapping("/{id}/send-activation")
    public Map<String, Object> sendActivation(@PathVariable String id) {
        CurrentUserHolder.requireRole("ADMIN");
        boolean delivered = users.resendActivation(id);
        auditAccessAction("ACTIVATION_LINK_SENT", id, delivered ? "Đã gửi email kích hoạt" : "Đã tạo liên kết; email đang tắt hoặc gửi thất bại");
        return Map.of("ok", true, "delivered", delivered);
    }

    @PostMapping("/{id}/send-password-reset")
    public Map<String, Object> sendPasswordReset(@PathVariable String id) {
        CurrentUserHolder.requireRole("ADMIN");
        boolean delivered = users.sendPasswordReset(id);
        auditAccessAction("PASSWORD_RESET_LINK_SENT", id, delivered ? "Đã gửi email đặt lại mật khẩu" : "Đã tạo liên kết; email đang tắt hoặc gửi thất bại");
        return Map.of("ok", true, "delivered", delivered);
    }

    @PostMapping("/bulk/access-action")
    public BulkAccountActionResult bulkAccessAction(@Valid @RequestBody BulkAccountActionRequest request) {
        CurrentUserHolder.requireRole("ADMIN");
        if (request.userIds() == null || request.userIds().isEmpty()) {
            throw ApiException.badRequest("Cần chọn ít nhất một tài khoản");
        }
        var actor = CurrentUserHolder.require();
        int succeeded = 0;
        List<BulkAccountActionError> errors = new ArrayList<>();
        for (String userId : request.userIds().stream().filter(java.util.Objects::nonNull).distinct().toList()) {
            try {
                if ("LOCK".equals(request.action()) && actor.id().equals(userId)) {
                    throw ApiException.badRequest("Không thể tự khóa tài khoản đang đăng nhập");
                }
                switch (request.action()) {
                    case "RESEND_ACTIVATION" -> users.resendActivation(userId);
                    case "SEND_PASSWORD_RESET" -> users.sendPasswordReset(userId);
                    case "UNLOCK" -> users.setStatus(userId, "ACTIVE");
                    case "LOCK" -> users.setStatus(userId, "LOCKED");
                    case "REQUIRE_PASSWORD_CHANGE" -> users.requirePasswordChange(userId);
                    default -> throw ApiException.badRequest("Thao tác tài khoản không hợp lệ");
                }
                succeeded++;
            } catch (Exception exception) {
                errors.add(new BulkAccountActionError(userId,
                        exception.getMessage() == null ? "Không thể xử lý" : exception.getMessage()));
            }
        }
        audit.record(actor.id(), actor.username(), actor.role(), "BULK_ACCOUNT_ACTION", "identity",
                "users", null, request.action() + " · thành công " + succeeded + "/" + request.userIds().size());
        return new BulkAccountActionResult(request.userIds().size(), succeeded, errors.size(), errors);
    }

    private void auditAccessAction(String action, String userId, String detail) {
        var actor = CurrentUserHolder.require();
        audit.record(actor.id(), actor.username(), actor.role(), action, "identity", "user", userId, detail);
    }

    private void assertAdminAccountFields(String role, String classId, String className, String mainSubject) {
        if ("STUDENT".equals(role) && (hasValue(classId) || hasValue(className))) {
            throw ApiException.forbidden("Admin chỉ tạo tài khoản học sinh ở trạng thái Chờ phân lớp; Giáo vụ chịu trách nhiệm phân lớp");
        }
        if ("TEACHER".equals(role) && hasValue(mainSubject)) {
            throw ApiException.forbidden("Chuyên môn giáo viên do Giáo vụ chuẩn hóa");
        }
    }

    private void requireConfirmedException(Boolean confirmed, String reason) {
        if (!Boolean.TRUE.equals(confirmed)) {
            throw ApiException.forbidden("Liên kết thủ công chỉ dành cho ngoại lệ đã được xác nhận");
        }
        if (reason == null || reason.trim().length() < 10) {
            throw ApiException.badRequest("Cần ghi lý do xử lý ngoại lệ tối thiểu 10 ký tự");
        }
    }

    private boolean hasValue(String value) {
        return value != null && !value.isBlank();
    }
}
