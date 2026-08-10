package com.sse.app.academic.planning;

import com.sse.app.academic.planning.TeacherStaffingDtos.StaffingPolicyDto;
import com.sse.app.academic.planning.TeacherStaffingDtos.StaffingPolicyRequest;
import com.sse.app.academic.planning.TeacherStaffingDtos.TeacherStaffingAnalysis;
import com.sse.app.security.CurrentUserHolder;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TeacherStaffingController {
    private final TeacherStaffingService staffing;

    public TeacherStaffingController(TeacherStaffingService staffing) {
        this.staffing = staffing;
    }

    @GetMapping("/academic/teacher-staffing")
    public TeacherStaffingAnalysis analyze(
            @RequestParam String academicYearId,
            @RequestParam String semesterId,
            @RequestParam(required = false) String scopeGradeLevel) {
        CurrentUserHolder.requireRole("ADMIN");
        return staffing.analyze(academicYearId, semesterId, scopeGradeLevel);
    }

    @GetMapping("/academic/teacher-staffing/policy/{academicYearId}")
    public StaffingPolicyDto policy(@PathVariable String academicYearId) {
        CurrentUserHolder.requireRole("ADMIN");
        return staffing.policy(academicYearId);
    }

    @PutMapping("/academic/teacher-staffing/policy/{academicYearId}")
    public StaffingPolicyDto savePolicy(
            @PathVariable String academicYearId,
            @Valid @RequestBody StaffingPolicyRequest request) {
        CurrentUserHolder.requireRole("ADMIN");
        return staffing.savePolicy(academicYearId, request);
    }
}

