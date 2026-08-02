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
        assertAdminAccountFields(req.role(), req.classId(), req.className(), req.mainSubject());
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
