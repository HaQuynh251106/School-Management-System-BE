package com.sse.app.academic.timetable;

import com.sse.app.academic.timetable.WorkloadPlanningDtos.*;
import com.sse.app.security.CurrentUserHolder;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class WorkloadPlanningController {
    private final WorkloadPlanningService planning;

    public WorkloadPlanningController(WorkloadPlanningService planning) {
        this.planning = planning;
    }

    @GetMapping("/curriculum-requirements")
    public List<CurriculumRequirement> requirements(@RequestParam String semesterId) {
        CurrentUserHolder.requireRole("ADMIN", "ACADEMIC_STAFF");
        return planning.listRequirements(semesterId);
    }

    @PutMapping("/curriculum-requirements")
    public CurriculumRequirement saveRequirement(@Valid @RequestBody SaveCurriculumRequirementRequest request) {
        CurrentUserHolder.requireRole("ADMIN", "ACADEMIC_STAFF");
        return planning.saveRequirement(request, CurrentUserHolder.require().id());
    }

    @PutMapping("/education-plans/{planId}/requirements")
    public CurriculumRequirement savePlanRequirement(@PathVariable String planId,
            @Valid @RequestBody SaveCurriculumRequirementRequest request) {
        CurrentUserHolder.requireRole("ADMIN", "ACADEMIC_STAFF");
        return planning.saveRequirement(planId, request);
    }

    @DeleteMapping("/curriculum-requirements/{id}")
    public void deleteRequirement(@PathVariable String id) {
        CurrentUserHolder.requireRole("ADMIN", "ACADEMIC_STAFF");
        planning.deleteRequirement(id);
    }

    @GetMapping("/me/teacher-load-registration")
    public TeacherLoadResponse mine(@RequestParam String semesterId) {
        CurrentUserHolder.requireRole("TEACHER");
        return planning.mine(CurrentUserHolder.require().id(), semesterId);
    }

    @PutMapping("/me/teacher-load-registration")
    public TeacherLoadResponse saveMine(@Valid @RequestBody SaveTeacherLoadRequest request) {
        CurrentUserHolder.requireRole("TEACHER");
        return planning.saveMine(CurrentUserHolder.require().id(), request);
    }

    @PostMapping("/me/teacher-load-registration/submit")
    public TeacherLoadResponse submitMine(@RequestParam String semesterId) {
        CurrentUserHolder.requireRole("TEACHER");
        return planning.submitMine(CurrentUserHolder.require().id(), semesterId);
    }

    @GetMapping("/teacher-load-registrations")
    public List<TeacherLoadResponse> registrations(@RequestParam String semesterId) {
        CurrentUserHolder.requireRole("ADMIN", "ACADEMIC_STAFF");
        return planning.listRegistrations(semesterId);
    }

    @PutMapping("/teacher-load-registrations/{id}/status")
    public TeacherLoadResponse review(@PathVariable String id,
                                      @Valid @RequestBody ReviewTeacherLoadRequest request) {
        CurrentUserHolder.requireRole("ADMIN", "ACADEMIC_STAFF");
        return planning.review(id, request, CurrentUserHolder.require().id());
    }

    @PostMapping("/teaching-assignments/auto-plan")
    public AutoAssignmentPlan autoPlan(@Valid @RequestBody AutoAssignmentRequest request) {
        CurrentUserHolder.requireRole("ADMIN", "ACADEMIC_STAFF");
        return planning.plan(request, CurrentUserHolder.require().id());
    }
}
