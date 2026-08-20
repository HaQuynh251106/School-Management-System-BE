package com.sse.app.academic.planning;

import com.sse.app.academic.planning.EducationPlanningCatalogDtos.AssignCombinationRequest;
import com.sse.app.academic.planning.EducationPlanningCatalogDtos.CombinationDetail;
import com.sse.app.academic.planning.EducationPlanningCatalogDtos.CombinationRequest;
import com.sse.app.academic.planning.EducationPlanningCatalogDtos.ProgramRequest;
import com.sse.app.academic.planning.EducationPlanningCatalogDtos.ProgramSubjectRequest;
import com.sse.app.academic.planning.EducationPlanningCatalogDtos.TeacherCapabilityRequest;
import com.sse.app.audit.AuditService;
import com.sse.app.identity.UserService;
import com.sse.app.security.CurrentUser;
import com.sse.app.security.CurrentUserHolder;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/academic/education-planning")
public class EducationPlanningCatalogController {
    private final EducationPlanningCatalogService catalog;
    private final AuditService audit;
    private final UserService users;

    public EducationPlanningCatalogController(
            EducationPlanningCatalogService catalog,
            AuditService audit,
            UserService users) {
        this.catalog = catalog;
        this.audit = audit;
        this.users = users;
    }

    @GetMapping("/programs")
    public List<EducationProgram> programs() {
        CurrentUserHolder.requirePermission("ACADEMIC_PLAN_READ");
        return catalog.listPrograms();
    }

    @PostMapping("/programs")
    public EducationProgram createProgram(@Valid @RequestBody ProgramRequest request) {
        CurrentUser actor = manager();
        EducationProgram result = catalog.saveProgram(null, request);
        record(actor, "CREATE", "education_program", result.getId(), result.getName());
        return result;
    }

    @PutMapping("/programs/{id}")
    public EducationProgram updateProgram(
            @PathVariable String id, @Valid @RequestBody ProgramRequest request) {
        CurrentUser actor = manager();
        EducationProgram result = catalog.saveProgram(id, request);
        record(actor, "UPDATE", "education_program", id, result.getName());
        return result;
    }

    @GetMapping("/programs/{programId}/subjects")
    public List<EducationProgramSubject> programSubjects(
            @PathVariable String programId, @RequestParam String gradeLevel) {
        CurrentUserHolder.requirePermission("ACADEMIC_PLAN_READ");
        return catalog.listProgramSubjects(programId, gradeLevel);
    }

    @PostMapping("/programs/{programId}/subjects")
    public EducationProgramSubject addProgramSubject(
            @PathVariable String programId,
            @Valid @RequestBody ProgramSubjectRequest request) {
        CurrentUser actor = manager();
        EducationProgramSubject result = catalog.saveProgramSubject(programId, null, request);
        record(actor, "CREATE", "education_program_subject", result.getId(),
                result.getGradeLevel() + " · " + result.getSubjectId());
        return result;
    }

    @PutMapping("/programs/{programId}/subjects/{id}")
    public EducationProgramSubject updateProgramSubject(
            @PathVariable String programId, @PathVariable String id,
            @Valid @RequestBody ProgramSubjectRequest request) {
        CurrentUser actor = manager();
        EducationProgramSubject result = catalog.saveProgramSubject(programId, id, request);
        record(actor, "UPDATE", "education_program_subject", id,
                result.getGradeLevel() + " · " + result.getSubjectId());
        return result;
    }

    @DeleteMapping("/programs/{programId}/subjects/{id}")
    public void deleteProgramSubject(@PathVariable String programId, @PathVariable String id) {
        CurrentUser actor = manager();
        catalog.deleteProgramSubject(programId, id);
        record(actor, "DELETE", "education_program_subject", id, "Xóa môn khỏi chương trình nháp");
    }

    @PostMapping("/programs/{programId}/subjects/auto-configure")
    public EducationPlanningCatalogDtos.AutoConfigureProgramResult autoConfigureProgram(
            @PathVariable String programId,
            @RequestBody(required = false) EducationPlanningCatalogDtos.AutoConfigureProgramRequest request) {
        CurrentUser actor = manager();
        var result = catalog.autoConfigureProgram(programId,
                request == null ? null : request.gradeLevel());
        record(actor, "AUTO_CONFIGURE", "education_program", programId,
                "Tạo " + result.created() + " cấu hình môn");
        return result;
    }

    @GetMapping("/combinations")
    public List<CombinationDetail> combinations(
            @RequestParam String academicYearId, @RequestParam String gradeLevel) {
        CurrentUserHolder.requirePermission("ACADEMIC_PLAN_READ");
        return catalog.listCombinations(academicYearId, gradeLevel);
    }

    @PostMapping("/combinations")
    public CombinationDetail createCombination(@Valid @RequestBody CombinationRequest request) {
        CurrentUser actor = manager();
        CombinationDetail result = catalog.saveCombination(null, request);
        record(actor, "CREATE", "subject_combination", result.combination().getId(),
                result.combination().getName());
        return result;
    }

    @PutMapping("/combinations/{id}")
    public CombinationDetail updateCombination(
            @PathVariable String id, @Valid @RequestBody CombinationRequest request) {
        CurrentUser actor = manager();
        CombinationDetail result = catalog.saveCombination(id, request);
        record(actor, "UPDATE", "subject_combination", id, result.combination().getName());
        return result;
    }

    @PostMapping("/combinations/assign")
    public List<ClassSubjectCombination> assign(
            @Valid @RequestBody AssignCombinationRequest request) {
        CurrentUser actor = manager();
        List<ClassSubjectCombination> result = catalog.assignCombination(request, actor.id());
        record(actor, "ASSIGN", "subject_combination", request.combinationId(),
                "Gán cho " + result.size() + " lớp");
        return result;
    }

    @GetMapping("/teachers/{teacherId}/subjects")
    public List<TeacherSubjectCapability> teacherSubjects(@PathVariable String teacherId) {
        CurrentUserHolder.requirePermission("ACADEMIC_PLAN_READ");
        return catalog.teacherCapabilities(teacherId);
    }

    @PutMapping("/teachers/{teacherId}/subjects")
    public List<TeacherSubjectCapability> saveTeacherSubjects(
            @PathVariable String teacherId,
            @Valid @RequestBody TeacherCapabilityRequest request) {
        CurrentUser actor = manager();
        if (!teacherId.equals(request.teacherId())) {
            throw com.sse.app.common.ApiException.badRequest("Giáo viên trong đường dẫn và dữ liệu không khớp");
        }
        List<TeacherSubjectCapability> result = catalog.saveTeacherCapabilities(request, actor.id());
        record(actor, "UPDATE", "teacher_subject_capability", teacherId,
                "Cập nhật " + result.size() + " môn có thể giảng dạy");
        return result;
    }

    @PostMapping("/teachers/auto-configure")
    public EducationPlanningCatalogDtos.AutoConfigureTeachersResult autoConfigureTeachers() {
        CurrentUser actor = manager();
        var result = catalog.autoConfigureTeachers();
        record(actor, "AUTO_CONFIGURE", "teacher_subject_capability", "ACTIVE_TEACHERS",
                "Cấu hình " + result.capabilitiesConfigured() + " giáo viên; điều chỉnh "
                        + result.homeroomAssignmentsAdjusted() + " GVCN; cân bằng "
                        + result.assignmentsRebalanced() + " phân công");
        return result;
    }

    private CurrentUser manager() {
        CurrentUser user = CurrentUserHolder.require();
        if (!"ADMIN".equals(user.role())) {
            CurrentUserHolder.requirePermission("ACADEMIC_PROGRAM_MANAGE");
        }
        return user;
    }

    private void record(CurrentUser actor, String action, String type, String id, String detail) {
        audit.record(actor.id(), users.fullNameOf(actor.id()), actor.role(),
                action, "academic", type, id, detail);
    }
}
