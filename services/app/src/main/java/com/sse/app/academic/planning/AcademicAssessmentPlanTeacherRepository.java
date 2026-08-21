package com.sse.app.academic.planning;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AcademicAssessmentPlanTeacherRepository
        extends JpaRepository<AcademicAssessmentPlanTeacher, String> {
    List<AcademicAssessmentPlanTeacher> findByAssessmentPlanIdOrderByPrimaryTeacherDescTeacherIdAsc(
            String assessmentPlanId);
    void deleteByAssessmentPlanId(String assessmentPlanId);
}
