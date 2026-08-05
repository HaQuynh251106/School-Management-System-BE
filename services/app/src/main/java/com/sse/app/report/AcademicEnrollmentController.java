package com.sse.app.report;

import com.sse.app.audit.AuditService;
import com.sse.app.identity.UserService;
import com.sse.app.report.AcademicEnrollmentDtos.BulkEnrollmentRequest;
import com.sse.app.report.AcademicEnrollmentDtos.BulkEnrollmentResult;
import com.sse.app.report.AcademicEnrollmentDtos.EnrollmentView;
import com.sse.app.report.AcademicEnrollmentDtos.RemoveEnrollmentRequest;
import com.sse.app.report.AcademicEnrollmentDtos.StudentCandidate;
import com.sse.app.security.CurrentUser;
import com.sse.app.security.CurrentUserHolder;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/academic/enrollments")
public class AcademicEnrollmentController {
    private final AcademicEnrollmentService enrollment;
    private final AuditService audit;
    private final UserService users;

    public AcademicEnrollmentController(
            AcademicEnrollmentService enrollment,
            AuditService audit,
            UserService users) {
        this.enrollment = enrollment;
        this.audit = audit;
        this.users = users;
    }

    @GetMapping
    public List<EnrollmentView> list(
            @RequestParam String academicYearId,
            @RequestParam String classId) {
        CurrentUserHolder.requirePermission("ACADEMIC_STRUCTURE_READ");
        return enrollment.list(academicYearId, classId);
    }

    @GetMapping("/unassigned")
    public List<StudentCandidate> unassigned(
            @RequestParam String academicYearId,
            @RequestParam(required = false) String keyword) {
        CurrentUserHolder.requirePermission("ACADEMIC_ENROLLMENT_MANAGE");
        return enrollment.unassigned(academicYearId, keyword);
    }

    @PostMapping("/bulk")
    public BulkEnrollmentResult assign(
            @Valid @RequestBody BulkEnrollmentRequest request) {
        CurrentUserHolder.requirePermission("ACADEMIC_ENROLLMENT_MANAGE");
        CurrentUser actor = CurrentUserHolder.require();
        BulkEnrollmentResult result = enrollment.assign(request, actor.id());
        audit.record(actor.id(), users.fullNameOf(actor.id()), actor.role(),
                "ENROLL_STUDENTS", "academic", "class", request.classId(),
                "Phân lớp " + request.studentIds().size()
                        + " học sinh · " + request.reason().trim());
        return result;
    }

    @DeleteMapping("/{id}")
    public void remove(
            @PathVariable String id,
            @Valid @RequestBody RemoveEnrollmentRequest request) {
        CurrentUserHolder.requirePermission("ACADEMIC_ENROLLMENT_MANAGE");
        CurrentUser actor = CurrentUserHolder.require();
        enrollment.remove(id, request.reason(), actor.id());
        audit.record(actor.id(), users.fullNameOf(actor.id()), actor.role(),
                "REMOVE_ENROLLMENT", "academic", "student_class_enrollment",
                id, request.reason().trim());
    }
}
