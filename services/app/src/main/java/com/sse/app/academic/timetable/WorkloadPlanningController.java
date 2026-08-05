package com.sse.app.academic.timetable;

import com.sse.app.academic.timetable.WorkloadPlanningDtos.*;
import com.sse.app.security.CurrentUserHolder;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class WorkloadPlanningController {
    private final WorkloadPlanningService planning;
    private final TeacherScheduleRestrictionService restrictions;
    private final TeachingAssignmentVersionService assignmentVersions;

    public WorkloadPlanningController(WorkloadPlanningService planning,
                                      TeacherScheduleRestrictionService restrictions,
                                      TeachingAssignmentVersionService assignmentVersions) {
        this.planning = planning;
        this.restrictions = restrictions;
        this.assignmentVersions = assignmentVersions;
    }

    @GetMapping("/curriculum-requirements")
    public List<CurriculumRequirement> requirements(@RequestParam String semesterId) {
        CurrentUserHolder.requireRole("ADMIN", "ACADEMIC_STAFF");
        return planning.listRequirements(semesterId);
    }

    @GetMapping("/curriculum-requirements/readiness")
    public CurriculumReadiness requirementReadiness(@RequestParam String semesterId) {
        CurrentUserHolder.requireRole("ADMIN", "ACADEMIC_STAFF");
        return planning.curriculumReadiness(semesterId);
    }

    @GetMapping("/curriculum-requirements/history")
    public List<CurriculumRequirementHistoryResponse> requirementHistory(@RequestParam String semesterId) {
        CurrentUserHolder.requireRole("ADMIN", "ACADEMIC_STAFF");
        return planning.curriculumHistory(semesterId);
    }

    @PutMapping("/curriculum-requirements")
    public CurriculumRequirement saveRequirement(@Valid @RequestBody SaveCurriculumRequirementRequest request) {
        CurrentUserHolder.requireRole("ACADEMIC_STAFF");
        return planning.saveRequirement(request, CurrentUserHolder.require().id());
    }

    @PostMapping("/curriculum-requirements/copy")
    public List<CurriculumRequirement> copyRequirements(
            @Valid @RequestBody CopyCurriculumRequirementsRequest request) {
        CurrentUserHolder.requireRole("ACADEMIC_STAFF");
        return planning.copyRequirements(request, CurrentUserHolder.require().id());
    }

    @DeleteMapping("/curriculum-requirements/{id}")
    public void deleteRequirement(@PathVariable String id) {
        CurrentUserHolder.requireRole("ACADEMIC_STAFF");
        planning.deleteRequirement(id, CurrentUserHolder.require().id());
    }

    @GetMapping("/me/teacher-load-registration")
    public TeacherLoadResponse mine(@RequestParam String semesterId) {
        CurrentUserHolder.requireRole("TEACHER");
        return planning.mine(CurrentUserHolder.require().id(), semesterId);
    }

    @GetMapping("/teacher-load-registrations")
    public List<TeacherLoadResponse> registrations(@RequestParam String semesterId) {
        CurrentUserHolder.requireRole("ACADEMIC_STAFF");
        return planning.listRegistrations(semesterId);
    }

    @GetMapping("/academic-scheduling/readiness")
    public SchedulingReadinessResponse schedulingReadiness(@RequestParam String semesterId) {
        CurrentUserHolder.requireRole("ACADEMIC_STAFF");
        return planning.schedulingReadiness(semesterId);
    }

    @GetMapping("/me/schedule-restriction-requests")
    public List<ScheduleRestrictionResponse> myRestrictionRequests(@RequestParam String semesterId) {
        CurrentUserHolder.requireRole("TEACHER");
        return restrictions.mine(CurrentUserHolder.require().id(), semesterId);
    }

    @PostMapping("/me/schedule-restriction-requests")
    public ScheduleRestrictionResponse submitRestriction(
            @Valid @RequestBody SaveScheduleRestrictionRequest request) {
        CurrentUserHolder.requireRole("TEACHER");
        return restrictions.submit(CurrentUserHolder.require().id(), request);
    }

    @PutMapping("/me/schedule-restriction-requests/{id}")
    public ScheduleRestrictionResponse reviseRestriction(
            @PathVariable String id, @Valid @RequestBody SaveScheduleRestrictionRequest request) {
        CurrentUserHolder.requireRole("TEACHER");
        return restrictions.revise(CurrentUserHolder.require().id(), id, request);
    }

    @PostMapping("/me/schedule-restriction-requests/{id}/withdraw")
    public ScheduleRestrictionResponse withdrawRestriction(@PathVariable String id) {
        CurrentUserHolder.requireRole("TEACHER");
        return restrictions.withdraw(CurrentUserHolder.require().id(), id);
    }

    @GetMapping("/me/schedule-restriction-history")
    public List<ScheduleRestrictionHistoryResponse> myRestrictionHistory(@RequestParam String requestId) {
        CurrentUserHolder.requireRole("TEACHER");
        return restrictions.history(requestId, null, CurrentUserHolder.require().id());
    }

    @GetMapping("/schedule-restriction-requests")
    public List<ScheduleRestrictionResponse> restrictionRequests(
            @RequestParam String semesterId, @RequestParam(required = false) String status) {
        CurrentUserHolder.requireRole("ACADEMIC_STAFF");
        return restrictions.list(semesterId, status);
    }

    @PutMapping("/schedule-restriction-requests/{id}/review")
    public ScheduleRestrictionResponse reviewRestriction(
            @PathVariable String id, @Valid @RequestBody ReviewScheduleRestrictionRequest request) {
        CurrentUserHolder.requireRole("ACADEMIC_STAFF");
        return restrictions.review(id, request, CurrentUserHolder.require().id());
    }

    @PostMapping("/schedule-restriction-requests/{id}/revoke")
    public ScheduleRestrictionResponse revokeRestriction(
            @PathVariable String id, @Valid @RequestBody RevokeScheduleRestrictionRequest request) {
        CurrentUserHolder.requireRole("ACADEMIC_STAFF");
        return restrictions.revoke(id, request.reason(), CurrentUserHolder.require().id());
    }

    @GetMapping("/schedule-restriction-history")
    public List<ScheduleRestrictionHistoryResponse> restrictionHistory(
            @RequestParam(required = false) String requestId,
            @RequestParam(required = false) String semesterId) {
        CurrentUserHolder.requireRole("ACADEMIC_STAFF");
        if ((requestId == null || requestId.isBlank()) && (semesterId == null || semesterId.isBlank())) {
            throw com.sse.app.common.ApiException.badRequest("Cần chọn yêu cầu hoặc học kỳ để xem lịch sử");
        }
        return restrictions.history(requestId, semesterId, null);
    }

    @GetMapping("/teacher-workload-policy")
    public WorkloadPolicyResponse workloadPolicy(@RequestParam String academicYearId) {
        CurrentUserHolder.requireRole("ADMIN", "ACADEMIC_STAFF", "TEACHER");
        return planning.workloadPolicy(academicYearId);
    }

    @PutMapping("/teacher-workload-policy")
    public WorkloadPolicyResponse saveWorkloadPolicy(
            @Valid @RequestBody SaveWorkloadPolicyRequest request) {
        CurrentUserHolder.requireRole("ACADEMIC_STAFF");
        return planning.saveWorkloadPolicy(request, CurrentUserHolder.require().id());
    }

    @GetMapping("/teacher-workload-adjustments")
    public List<WorkloadAdjustmentResponse> workloadAdjustments(
            @RequestParam String academicYearId,
            @RequestParam(required = false) String teacherId) {
        CurrentUserHolder.requireRole("ACADEMIC_STAFF");
        return planning.workloadAdjustments(academicYearId, teacherId);
    }

    @PostMapping("/teacher-workload-adjustments")
    public WorkloadAdjustmentResponse saveWorkloadAdjustment(
            @Valid @RequestBody SaveWorkloadAdjustmentRequest request) {
        CurrentUserHolder.requireRole("ACADEMIC_STAFF");
        return planning.saveWorkloadAdjustment(request, CurrentUserHolder.require().id());
    }

    @PostMapping("/teacher-workload-adjustments/{id}/revoke")
    public WorkloadAdjustmentResponse revokeWorkloadAdjustment(
            @PathVariable String id,
            @Valid @RequestBody RevokeWorkloadAdjustmentRequest request) {
        CurrentUserHolder.requireRole("ACADEMIC_STAFF");
        return planning.revokeWorkloadAdjustment(id, request, CurrentUserHolder.require().id());
    }

    @PostMapping("/teaching-assignments/auto-plan")
    public AutoAssignmentPlan autoPlan(@Valid @RequestBody AutoAssignmentRequest request) {
        CurrentUserHolder.requireRole("ACADEMIC_STAFF");
        return planning.plan(request, CurrentUserHolder.require().id());
    }

    @GetMapping("/teaching-assignment-versions")
    public List<AssignmentVersionResponse> assignmentVersions(@RequestParam String semesterId) {
        CurrentUserHolder.requireRole("ACADEMIC_STAFF");
        return assignmentVersions.list(semesterId);
    }

    @GetMapping("/teaching-assignment-versions/{id}/items")
    public List<AssignmentVersionItemResponse> assignmentVersionItems(@PathVariable String id) {
        CurrentUserHolder.requireRole("ACADEMIC_STAFF");
        return assignmentVersions.items(id);
    }

    @PostMapping("/teaching-assignment-versions/{id}/restore")
    public AssignmentVersionResponse restoreAssignmentVersion(
            @PathVariable String id, @Valid @RequestBody RestoreAssignmentVersionRequest request) {
        CurrentUserHolder.requireRole("ACADEMIC_STAFF");
        return assignmentVersions.restore(id, request.name(), CurrentUserHolder.require().id());
    }

    @PostMapping("/teaching-assignment-versions/{id}/publish")
    public AssignmentVersionResponse publishAssignmentVersion(@PathVariable String id) {
        CurrentUserHolder.requireRole("ACADEMIC_STAFF");
        return assignmentVersions.publish(id, CurrentUserHolder.require().id());
    }
}
