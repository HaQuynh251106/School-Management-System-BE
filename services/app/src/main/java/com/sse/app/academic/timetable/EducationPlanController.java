package com.sse.app.academic.timetable;

import com.sse.app.academic.timetable.EducationPlanDtos.CreateEducationPlanRequest;
import com.sse.app.academic.timetable.EducationPlanDtos.EducationPlanValidation;
import com.sse.app.academic.timetable.EducationPlanDtos.PlanActionRequest;
import com.sse.app.security.CurrentUserHolder;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/education-plans")
public class EducationPlanController {
    private final EducationPlanService plans;

    public EducationPlanController(EducationPlanService plans) {
        this.plans = plans;
    }

    @GetMapping
    public List<EducationPlan> list(@RequestParam String academicYearId,
                                    @RequestParam(required = false) String gradeLevel) {
        CurrentUserHolder.requireRole("ADMIN", "ACADEMIC_STAFF");
        return plans.list(academicYearId, gradeLevel);
    }

    @PostMapping
    public EducationPlan create(@Valid @RequestBody CreateEducationPlanRequest request) {
        CurrentUserHolder.requireRole("ADMIN", "ACADEMIC_STAFF");
        return plans.create(request, CurrentUserHolder.require().id());
    }

    @GetMapping("/{id}/requirements")
    public List<CurriculumRequirement> requirements(@PathVariable String id) {
        CurrentUserHolder.requireRole("ADMIN", "ACADEMIC_STAFF");
        return plans.requirements(id);
    }

    @GetMapping("/{id}/validation")
    public EducationPlanValidation validation(@PathVariable String id) {
        CurrentUserHolder.requireRole("ADMIN", "ACADEMIC_STAFF");
        return plans.validate(id);
    }

    @PostMapping("/{id}/submit")
    public EducationPlan submit(@PathVariable String id) {
        CurrentUserHolder.requireRole("ADMIN", "ACADEMIC_STAFF");
        return plans.submit(id, CurrentUserHolder.require().id());
    }

    @PostMapping("/{id}/approve")
    public EducationPlan approve(@PathVariable String id) {
        CurrentUserHolder.requireRole("ADMIN", "ACADEMIC_STAFF");
        return plans.approve(id, CurrentUserHolder.require().id());
    }

    @PostMapping("/{id}/request-revision")
    public EducationPlan requestRevision(@PathVariable String id,
                                         @RequestBody(required = false) PlanActionRequest request) {
        CurrentUserHolder.requireRole("ADMIN", "ACADEMIC_STAFF");
        return plans.requestRevision(id, request == null ? null : request.comment(),
                CurrentUserHolder.require().id());
    }

    @PostMapping("/{id}/publish")
    public EducationPlan publish(@PathVariable String id) {
        CurrentUserHolder.requireRole("ADMIN", "ACADEMIC_STAFF");
        return plans.publish(id, CurrentUserHolder.require().id());
    }

    @PostMapping("/{id}/lock")
    public EducationPlan lock(@PathVariable String id) {
        CurrentUserHolder.requireRole("ADMIN", "ACADEMIC_STAFF");
        return plans.lock(id, CurrentUserHolder.require().id());
    }
}
