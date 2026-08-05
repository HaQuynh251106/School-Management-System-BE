package com.sse.app.academic.planning;

import com.sse.app.academic.planning.AcademicPlanningDtos.ExamScheduleRequest;
import com.sse.app.academic.planning.AcademicPlanningDtos.CurriculumItemRequest;
import com.sse.app.academic.planning.AcademicPlanningDtos.NewVersionRequest;
import com.sse.app.academic.planning.AcademicPlanningDtos.PlanDetail;
import com.sse.app.academic.planning.AcademicPlanningDtos.PlanReadiness;
import com.sse.app.academic.planning.AcademicPlanningDtos.PlanRequest;
import com.sse.app.academic.planning.AcademicPlanningDtos.PlanStageRequest;
import com.sse.app.academic.planning.AcademicPlanningDtos.PlanSubjectRequest;
import com.sse.app.academic.planning.AcademicPlanningDtos.PlanUpdateRequest;
import com.sse.app.academic.planning.AcademicPlanningDtos.SpecialWeekRequest;
import com.sse.app.academic.planning.AcademicPlanningDtos.AssessmentPlanRequest;
import com.sse.app.academic.planning.AcademicPlanningDtos.CurriculumDistributionRequest;
import com.sse.app.academic.planning.AcademicPlanningDtos.WorkflowRequest;
import com.sse.app.audit.AuditService;
import com.sse.app.identity.UserService;
import com.sse.app.security.CurrentUser;
import com.sse.app.security.CurrentUserHolder;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;

@RestController
@RequestMapping("/academic/training-plans")
public class AcademicPlanningController {
    private final AcademicPlanningService planning;
    private final AcademicPlanCompletionService completion;
    private final AcademicPlanReportService reports;
    private final EducationPlanningCatalogService catalog;
    private final AuditService audit;
    private final UserService users;

    public AcademicPlanningController(
            AcademicPlanningService planning,
            AcademicPlanCompletionService completion,
            AcademicPlanReportService reports,
            EducationPlanningCatalogService catalog,
            AuditService audit,
            UserService users) {
        this.planning = planning;
        this.completion = completion;
        this.reports = reports;
        this.catalog = catalog;
        this.audit = audit;
        this.users = users;
    }

    @GetMapping
    public List<AcademicTrainingPlan> list(
            @RequestParam(required = false) String academicYearId,
            @RequestParam(required = false) String gradeLevel) {
        CurrentUserHolder.requirePermission("ACADEMIC_PLAN_READ");
        return planning.listPlans(academicYearId, gradeLevel);
    }

    @PostMapping
    public AcademicTrainingPlan create(@Valid @RequestBody PlanRequest request) {
        CurrentUser actor = requirePlanManager();
        AcademicTrainingPlan result = planning.createPlan(request, actor.id());
        record(actor, "CREATE", "training_plan", result.getId(),
                result.getAcademicYearId() + " · " + result.getGradeLevel());
        return result;
    }

    @PutMapping("/{id}")
    public AcademicTrainingPlan update(
            @PathVariable String id,
            @Valid @RequestBody PlanUpdateRequest request) {
        CurrentUser actor = requirePlanManager();
        AcademicTrainingPlan result = planning.updatePlan(id, request);
        record(actor, "UPDATE", "training_plan", id, result.getName());
        return result;
    }

    @PostMapping("/{id}/publish")
    public AcademicTrainingPlan publish(@PathVariable String id) {
        CurrentUser actor = requirePlanManager();
        AcademicPlanningDtos.PlanValidationReport validation = completion.validate(id);
        if (!validation.valid()) {
            throw com.sse.app.common.ApiException.conflict(
                    "Kế hoạch còn " + validation.errorCount()
                            + " lỗi bắt buộc và chưa thể công bố");
        }
        AcademicTrainingPlan result = planning.publishPlan(id, actor.id());
        completion.recordPublished(id, actor.id());
        record(actor, "PUBLISH", "training_plan", id, result.getName());
        return result;
    }

    @PostMapping("/{id}/reopen")
    public AcademicTrainingPlan reopen(@PathVariable String id) {
        CurrentUser actor = requirePlanManager();
        AcademicTrainingPlan result = planning.reopenPlan(id);
        record(actor, "REOPEN", "training_plan", id, result.getName());
        return result;
    }

    @PostMapping({"/{id}/lock", "/{id}/close"})
    public AcademicTrainingPlan lock(@PathVariable String id) {
        CurrentUser actor = requirePlanManager();
        AcademicTrainingPlan result = planning.lockPlan(id, actor.id());
        record(actor, "LOCK", "training_plan", id,
                result.getName() + " · v" + result.getVersionNumber());
        return result;
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id) {
        CurrentUser actor = requirePlanManager();
        planning.deletePlan(id);
        record(actor, "DELETE", "training_plan", id,
                "Xóa kế hoạch giáo dục năm học đang ở trạng thái nháp");
    }

    @GetMapping("/{id}/readiness")
    public PlanReadiness readiness(@PathVariable String id) {
        CurrentUserHolder.requirePermission("ACADEMIC_PLAN_READ");
        return planning.readiness(id);
    }

    @GetMapping("/{id}/details")
    public PlanDetail details(@PathVariable String id) {
        CurrentUserHolder.requirePermission("ACADEMIC_PLAN_READ");
        return planning.detail(id);
    }

    @PostMapping("/{id}/versions")
    public AcademicTrainingPlan createVersion(
            @PathVariable String id,
            @RequestBody(required = false) NewVersionRequest request) {
        CurrentUser actor = requirePlanManager();
        AcademicTrainingPlan result = planning.createVersion(
                id, request == null ? new NewVersionRequest(null) : request);
        completion.copyCompletionContent(id, result.getId());
        record(actor, "CREATE_VERSION", "training_plan", result.getId(),
                "Tạo phiên bản " + result.getVersionNumber()
                        + " từ " + id);
        return result;
    }

    @GetMapping("/{id}/subjects")
    public List<AcademicTrainingPlanSubject> subjects(@PathVariable String id) {
        CurrentUserHolder.requirePermission("ACADEMIC_PLAN_READ");
        return planning.listSubjects(id);
    }

    @GetMapping("/{id}/annual-summary")
    public List<AcademicPlanningDtos.AnnualSubjectSummary> annualSummary(
            @PathVariable String id) {
        CurrentUserHolder.requirePermission("ACADEMIC_PLAN_READ");
        return completion.annualSummary(id);
    }

    @PostMapping("/{id}/initialize-from-program")
    public AcademicPlanningDtos.PlanInitializationResult initializeFromProgram(
            @PathVariable String id) {
        CurrentUser actor = requirePlanManager();
        AcademicPlanningDtos.PlanInitializationResult result = completion.initializeFromProgram(id);
        record(actor, "INITIALIZE", "training_plan", id,
                "Khoi tao noi dung ke hoach tu chuong trinh giao duc");
        return result;
    }

    @GetMapping("/{id}/validation")
    public AcademicPlanningDtos.PlanValidationReport validation(@PathVariable String id) {
        CurrentUserHolder.requirePermission("ACADEMIC_PLAN_READ");
        return completion.validate(id);
    }

    @GetMapping("/{id}/approval-history")
    public List<AcademicPlanningDtos.ApprovalHistoryView> approvalHistory(@PathVariable String id) {
        CurrentUserHolder.requirePermission("ACADEMIC_PLAN_READ");
        return completion.history(id);
    }

    @GetMapping("/published/me")
    public ResponseEntity<AcademicPlanningDtos.PublishedPlanView> publishedForMe(
            @RequestParam(required = false) String studentId) {
        CurrentUser actor = CurrentUserHolder.require();
        String targetStudentId;
        if (actor.isStudent()) {
            targetStudentId = actor.id();
            if (studentId != null && !studentId.isBlank() && !actor.id().equals(studentId)) {
                throw com.sse.app.common.ApiException.forbidden("Khong co quyen xem ke hoach cua hoc sinh khac");
            }
        } else if (actor.isParent()) {
            if (studentId == null || studentId.isBlank()) {
                throw com.sse.app.common.ApiException.badRequest("Vui long chon hoc sinh");
            }
            users.assertParentOf(actor.id(), studentId);
            targetStudentId = studentId;
        } else {
            throw com.sse.app.common.ApiException.forbidden("Chuc nang danh cho hoc sinh va phu huynh");
        }
        com.sse.app.identity.User student = users.getById(targetStudentId);
        if (student.getClassId() == null || student.getClassId().isBlank()) {
            throw com.sse.app.common.ApiException.conflict("Hoc sinh chua duoc xep lop");
        }
        com.sse.app.academic.structure.SchoolClass schoolClass =
                planning.schoolClass(student.getClassId());
        AcademicTrainingPlan plan = planning.listPlans(
                        schoolClass.getAcademicYearId(), schoolClass.getGradeLevel()).stream()
                .filter(item -> java.util.Set.of("PUBLISHED", "LOCKED").contains(item.getStatus()))
                .findFirst()
                .orElse(null);
        if (plan == null) {
            return ResponseEntity.noContent().build();
        }
        List<AcademicPlanningDtos.AnnualSubjectSummary> visibleSubjects = completion
                .annualSummary(plan.getId()).stream()
                .filter(item -> catalog.subjectAppliesToClass(plan.getProgramId(),
                        plan.getGradeLevel(), schoolClass.getId(), item.subjectId()))
                .toList();
        java.util.Set<String> visibleSubjectIds = visibleSubjects.stream()
                .map(AcademicPlanningDtos.AnnualSubjectSummary::subjectId)
                .collect(java.util.stream.Collectors.toSet());
        List<AcademicAssessmentPlan> visibleAssessments = completion.listAssessments(plan.getId())
                .stream().filter(item -> (item.getClassId() == null
                        || schoolClass.getId().equals(item.getClassId()))
                        && visibleSubjectIds.contains(item.getSubjectId())).toList();
        return ResponseEntity.ok(new AcademicPlanningDtos.PublishedPlanView(plan, schoolClass.getId(),
                schoolClass.getCode(), visibleSubjects, visibleAssessments));
    }

    @GetMapping("/{id}/export.xlsx")
    public ResponseEntity<byte[]> exportExcel(@PathVariable String id) {
        CurrentUserHolder.requirePermission("ACADEMIC_PLAN_READ");
        CurrentUser actor = CurrentUserHolder.require();
        byte[] body = reports.excel(id);
        record(actor, "EXPORT", "training_plan", id, "Xuất kế hoạch giáo dục Excel");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=education-plan-" + id + ".xlsx")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }

    @GetMapping("/{id}/export.pdf")
    public ResponseEntity<byte[]> exportPdf(@PathVariable String id) {
        CurrentUserHolder.requirePermission("ACADEMIC_PLAN_READ");
        CurrentUser actor = CurrentUserHolder.require();
        byte[] body = reports.pdf(id);
        record(actor, "EXPORT", "training_plan", id, "Xuất kế hoạch giáo dục PDF");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=education-plan-" + id + ".pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(body);
    }

    @PostMapping("/{id}/submit")
    public AcademicTrainingPlan submit(
            @PathVariable String id, @Valid @RequestBody WorkflowRequest request) {
        CurrentUserHolder.requirePermission("ACADEMIC_PLAN_SUBMIT");
        CurrentUser actor = CurrentUserHolder.require();
        AcademicTrainingPlan result = completion.submit(id, actor.id(), request.comment());
        record(actor, "SUBMIT", "training_plan", id, request.comment());
        return result;
    }

    @PostMapping("/{id}/review")
    public AcademicTrainingPlan review(
            @PathVariable String id, @Valid @RequestBody WorkflowRequest request) {
        CurrentUserHolder.requirePermission("ACADEMIC_PLAN_REVIEW");
        CurrentUser actor = CurrentUserHolder.require();
        AcademicTrainingPlan result = completion.review(id, actor.id(), request.comment());
        record(actor, "REVIEW", "training_plan", id, request.comment());
        return result;
    }

    @PostMapping("/{id}/request-revision")
    public AcademicTrainingPlan requestRevision(
            @PathVariable String id, @Valid @RequestBody WorkflowRequest request) {
        CurrentUserHolder.requirePermission("ACADEMIC_PLAN_REVIEW");
        CurrentUser actor = CurrentUserHolder.require();
        AcademicTrainingPlan result = completion.requestRevision(id, actor.id(), request.comment());
        record(actor, "REQUEST_REVISION", "training_plan", id, request.comment());
        return result;
    }

    @PostMapping("/{id}/approve")
    public AcademicTrainingPlan approve(
            @PathVariable String id, @Valid @RequestBody WorkflowRequest request) {
        CurrentUserHolder.requirePermission("ACADEMIC_PLAN_APPROVE");
        CurrentUser actor = CurrentUserHolder.require();
        AcademicTrainingPlan result = completion.approve(id, actor.id(), request.comment());
        record(actor, "APPROVE", "training_plan", id, request.comment());
        return result;
    }

    @PostMapping("/{id}/archive")
    public AcademicTrainingPlan archive(
            @PathVariable String id, @Valid @RequestBody WorkflowRequest request) {
        CurrentUserHolder.requirePermission("ACADEMIC_PLAN_APPROVE");
        CurrentUser actor = CurrentUserHolder.require();
        AcademicTrainingPlan result = completion.archive(id, actor.id(), request.comment());
        record(actor, "ARCHIVE", "training_plan", id, request.comment());
        return result;
    }

    @GetMapping("/{planId}/subjects/{subjectId}/distributions")
    public List<AcademicCurriculumDistribution> distributions(
            @PathVariable String planId, @PathVariable String subjectId) {
        CurrentUserHolder.requirePermission("ACADEMIC_PLAN_READ");
        return completion.listDistributions(planId, subjectId);
    }

    @PostMapping("/{planId}/subjects/{subjectId}/distributions")
    public AcademicCurriculumDistribution addDistribution(
            @PathVariable String planId, @PathVariable String subjectId,
            @Valid @RequestBody CurriculumDistributionRequest request) {
        CurrentUser actor = requireContentManager(planning.getPlanSubject(subjectId).getSubjectId());
        AcademicCurriculumDistribution result = completion.saveDistribution(
                planId, subjectId, null, request);
        record(actor, "CREATE", "curriculum_distribution", result.getId(), result.getTitle());
        return result;
    }

    @PutMapping("/{planId}/distributions/{id}")
    public AcademicCurriculumDistribution updateDistribution(
            @PathVariable String planId, @PathVariable String id,
            @Valid @RequestBody CurriculumDistributionRequest request) {
        CurrentUser actor = requireContentManager(
                planning.getPlanSubject(completion.getDistribution(planId, id).getPlanSubjectId()).getSubjectId());
        AcademicCurriculumDistribution existing = completion.updateDistribution(
                planId, id, request);
        record(actor, "UPDATE", "curriculum_distribution", id, existing.getTitle());
        return existing;
    }

    @DeleteMapping("/{planId}/distributions/{id}")
    public void deleteDistribution(@PathVariable String planId, @PathVariable String id) {
        CurrentUser actor = requireContentManager(
                planning.getPlanSubject(completion.getDistribution(planId, id).getPlanSubjectId()).getSubjectId());
        completion.deleteDistribution(planId, id);
        record(actor, "DELETE", "curriculum_distribution", id, "Xóa phân phối theo tuần");
    }

    @GetMapping("/{planId}/assessments")
    public List<AcademicAssessmentPlan> assessments(@PathVariable String planId) {
        CurrentUserHolder.requirePermission("ACADEMIC_PLAN_READ");
        return completion.listAssessments(planId);
    }

    @PostMapping("/{planId}/assessments")
    public AcademicAssessmentPlan addAssessment(
            @PathVariable String planId,
            @Valid @RequestBody AssessmentPlanRequest request) {
        CurrentUser actor = requireContentManager(request.subjectId());
        AcademicAssessmentPlan result = completion.saveAssessment(planId, null, request);
        record(actor, "CREATE", "assessment_plan", result.getId(), result.getAssessmentType());
        return result;
    }

    @PutMapping("/{planId}/assessments/{id}")
    public AcademicAssessmentPlan updateAssessment(
            @PathVariable String planId, @PathVariable String id,
            @Valid @RequestBody AssessmentPlanRequest request) {
        CurrentUser actor = requireContentManager(request.subjectId());
        AcademicAssessmentPlan result = completion.saveAssessment(planId, id, request);
        record(actor, "UPDATE", "assessment_plan", id, result.getAssessmentType());
        return result;
    }

    @DeleteMapping("/{planId}/assessments/{id}")
    public void deleteAssessment(@PathVariable String planId, @PathVariable String id) {
        CurrentUser actor = requireContentManager(completion.getAssessment(planId, id).getSubjectId());
        completion.deleteAssessment(planId, id);
        record(actor, "DELETE", "assessment_plan", id, "Xóa kế hoạch kiểm tra");
    }

    @PostMapping("/{id}/subjects")
    public AcademicTrainingPlanSubject addSubject(
            @PathVariable String id,
            @Valid @RequestBody PlanSubjectRequest request) {
        CurrentUser actor = requireContentManager(request.subjectId());
        AcademicTrainingPlanSubject result =
                planning.addSubject(id, request);
        record(actor, "CREATE", "training_plan_subject", result.getId(),
                result.getSemesterId() + " · " + result.getSubjectId());
        return result;
    }

    @PutMapping("/{planId}/subjects/{id}")
    public AcademicTrainingPlanSubject updateSubject(
            @PathVariable String planId,
            @PathVariable String id,
            @Valid @RequestBody PlanSubjectRequest request) {
        CurrentUser actor = requireContentManager(request.subjectId());
        AcademicTrainingPlanSubject result =
                planning.updateSubject(planId, id, request);
        record(actor, "UPDATE", "training_plan_subject", id,
                result.getSemesterId() + " · " + result.getSubjectId());
        return result;
    }

    @DeleteMapping("/{planId}/subjects/{id}")
    public void deleteSubject(
            @PathVariable String planId, @PathVariable String id) {
        CurrentUser actor = requireContentManager(planning.getPlanSubject(id).getSubjectId());
        planning.deleteSubject(planId, id);
        record(actor, "DELETE", "training_plan_subject", id,
                "Xóa môn khỏi kế hoạch");
    }

    @GetMapping("/{planId}/subjects/{subjectId}/stages")
    public List<AcademicTrainingPlanStage> stages(
            @PathVariable String planId,
            @PathVariable String subjectId) {
        CurrentUserHolder.requirePermission("ACADEMIC_PLAN_READ");
        return planning.listStages(planId, subjectId);
    }

    @PostMapping("/{planId}/subjects/{subjectId}/stages")
    public AcademicTrainingPlanStage addStage(
            @PathVariable String planId,
            @PathVariable String subjectId,
            @Valid @RequestBody PlanStageRequest request) {
        CurrentUser actor = requireContentManager(planning.getPlanSubject(subjectId).getSubjectId());
        AcademicTrainingPlanStage result =
                planning.addStage(planId, subjectId, request);
        record(actor, "CREATE", "training_plan_stage", result.getId(),
                result.getCode() + " · " + result.getName());
        return result;
    }

    @PutMapping("/{planId}/stages/{id}")
    public AcademicTrainingPlanStage updateStage(
            @PathVariable String planId,
            @PathVariable String id,
            @Valid @RequestBody PlanStageRequest request) {
        CurrentUser actor = requireContentManager(
                planning.getPlanSubject(planning.getStage(id).getPlanSubjectId()).getSubjectId());
        AcademicTrainingPlanStage result =
                planning.updateStage(planId, id, request);
        record(actor, "UPDATE", "training_plan_stage", id,
                result.getCode() + " · " + result.getName());
        return result;
    }

    @DeleteMapping("/{planId}/stages/{id}")
    public void deleteStage(
            @PathVariable String planId, @PathVariable String id) {
        CurrentUser actor = requireContentManager(
                planning.getPlanSubject(planning.getStage(id).getPlanSubjectId()).getSubjectId());
        planning.deleteStage(planId, id);
        record(actor, "DELETE", "training_plan_stage", id,
                "Xóa giai đoạn khỏi kế hoạch");
    }

    @GetMapping("/{planId}/subjects/{subjectId}/curriculum")
    public List<AcademicCurriculumItem> curriculum(
            @PathVariable String planId,
            @PathVariable String subjectId) {
        CurrentUserHolder.requirePermission("ACADEMIC_PLAN_READ");
        return planning.listCurriculum(planId, subjectId);
    }

    @PostMapping("/{planId}/subjects/{subjectId}/curriculum")
    public AcademicCurriculumItem addCurriculumItem(
            @PathVariable String planId,
            @PathVariable String subjectId,
            @Valid @RequestBody CurriculumItemRequest request) {
        CurrentUser actor = requireContentManager(planning.getPlanSubject(subjectId).getSubjectId());
        AcademicCurriculumItem result = planning.addCurriculumItem(
                planId, subjectId, request);
        record(actor, "CREATE", "curriculum_item", result.getId(),
                result.getItemType() + " · " + result.getTitle());
        return result;
    }

    @PutMapping("/{planId}/curriculum/{id}")
    public AcademicCurriculumItem updateCurriculumItem(
            @PathVariable String planId,
            @PathVariable String id,
            @Valid @RequestBody CurriculumItemRequest request) {
        CurrentUser actor = requireContentManager(
                planning.getPlanSubject(planning.getCurriculumItem(id).getPlanSubjectId()).getSubjectId());
        AcademicCurriculumItem result =
                planning.updateCurriculumItem(planId, id, request);
        record(actor, "UPDATE", "curriculum_item", id,
                result.getItemType() + " · " + result.getTitle());
        return result;
    }

    @DeleteMapping("/{planId}/curriculum/{id}")
    public void deleteCurriculumItem(
            @PathVariable String planId, @PathVariable String id) {
        CurrentUser actor = requireContentManager(
                planning.getPlanSubject(planning.getCurriculumItem(id).getPlanSubjectId()).getSubjectId());
        planning.deleteCurriculumItem(planId, id);
        record(actor, "DELETE", "curriculum_item", id,
                "Xóa nội dung chương trình");
    }

    @GetMapping("/{planId}/subjects/{subjectId}/special-weeks")
    public List<AcademicTrainingPlanSpecialWeek> specialWeeks(
            @PathVariable String planId,
            @PathVariable String subjectId) {
        CurrentUserHolder.requirePermission("ACADEMIC_PLAN_READ");
        return planning.listSpecialWeeks(planId, subjectId);
    }

    @PostMapping("/{planId}/subjects/{subjectId}/special-weeks")
    public AcademicTrainingPlanSpecialWeek addSpecialWeek(
            @PathVariable String planId,
            @PathVariable String subjectId,
            @Valid @RequestBody SpecialWeekRequest request) {
        CurrentUser actor = requireContentManager(planning.getPlanSubject(subjectId).getSubjectId());
        AcademicTrainingPlanSpecialWeek result =
                planning.addSpecialWeek(planId, subjectId, request);
        record(actor, "CREATE", "training_plan_special_week",
                result.getId(), result.getWeekType()
                        + " · tuần " + result.getWeekNumber());
        return result;
    }

    @PutMapping("/{planId}/special-weeks/{id}")
    public AcademicTrainingPlanSpecialWeek updateSpecialWeek(
            @PathVariable String planId,
            @PathVariable String id,
            @Valid @RequestBody SpecialWeekRequest request) {
        CurrentUser actor = requireContentManager(
                planning.getPlanSubject(planning.getSpecialWeek(id).getPlanSubjectId()).getSubjectId());
        AcademicTrainingPlanSpecialWeek result =
                planning.updateSpecialWeek(planId, id, request);
        record(actor, "UPDATE", "training_plan_special_week",
                result.getId(), result.getWeekType()
                        + " · tuần " + result.getWeekNumber());
        return result;
    }

    @DeleteMapping("/{planId}/special-weeks/{id}")
    public void deleteSpecialWeek(
            @PathVariable String planId, @PathVariable String id) {
        CurrentUser actor = requireContentManager(
                planning.getPlanSubject(planning.getSpecialWeek(id).getPlanSubjectId()).getSubjectId());
        planning.deleteSpecialWeek(planId, id);
        record(actor, "DELETE", "training_plan_special_week", id,
                "Xóa tuần kiểm tra/dự phòng");
    }

    @GetMapping("/{id}/exams")
    public List<AcademicExamSchedule> exams(@PathVariable String id) {
        CurrentUserHolder.requirePermission("ACADEMIC_PLAN_READ");
        return planning.listExams(id);
    }

    @PostMapping("/{id}/exams")
    public AcademicExamSchedule addExam(
            @PathVariable String id,
            @Valid @RequestBody ExamScheduleRequest request) {
        CurrentUser actor = requireExamManager();
        AcademicExamSchedule result = planning.addExam(id, request);
        record(actor, "CREATE", "exam_schedule", result.getId(),
                result.getName());
        return result;
    }

    @PutMapping("/{planId}/exams/{id}")
    public AcademicExamSchedule updateExam(
            @PathVariable String planId,
            @PathVariable String id,
            @Valid @RequestBody ExamScheduleRequest request) {
        CurrentUser actor = requireExamManager();
        AcademicExamSchedule result =
                planning.updateExam(planId, id, request);
        record(actor, "UPDATE", "exam_schedule", id, result.getName());
        return result;
    }

    @DeleteMapping("/{planId}/exams/{id}")
    public void deleteExam(
            @PathVariable String planId, @PathVariable String id) {
        CurrentUser actor = requireExamManager();
        planning.deleteExam(planId, id);
        record(actor, "DELETE", "exam_schedule", id,
                "Xóa lịch thi dự kiến");
    }

    private CurrentUser requirePlanManager() {
        CurrentUser actor = CurrentUserHolder.require();
        if (!actor.isAdmin()) {
            throw com.sse.app.common.ApiException.forbidden(
                    "Chỉ quản trị viên được quản lý khung và phiên bản kế hoạch giáo dục");
        }
        CurrentUserHolder.requirePermission("ACADEMIC_PLAN_MANAGE");
        return actor;
    }

    private CurrentUser requireContentManager(String subjectId) {
        CurrentUser actor = CurrentUserHolder.require();
        if (actor.isAdmin()) return actor;
        if (!actor.isTeacher()) {
            CurrentUserHolder.requirePermission("ACADEMIC_PLAN_MANAGE");
            return actor;
        }
        if (!actor.hasPermission("ACADEMIC_PLAN_CONTENT_MANAGE")
                || !catalog.teacherCanTeach(actor.id(), subjectId)) {
            throw com.sse.app.common.ApiException.forbidden(
                    "Giáo viên chỉ được sửa nội dung môn thuộc chuyên môn đã khai báo");
        }
        return actor;
    }

    private CurrentUser requireExamManager() {
        CurrentUserHolder.requirePermission("ACADEMIC_EXAM_PLAN_MANAGE");
        return CurrentUserHolder.require();
    }

    private void record(
            CurrentUser actor, String action, String entityType,
            String entityId, String detail) {
        audit.record(actor.id(), users.fullNameOf(actor.id()), actor.role(),
                action, "academic", entityType, entityId, detail);
    }
}
