package com.sse.app.academic.timetable;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface EducationPlanRepository extends JpaRepository<EducationPlan, String> {
    List<EducationPlan> findByAcademicYearIdOrderByGradeLevelAscVersionNoDesc(String academicYearId);
    List<EducationPlan> findByAcademicYearIdAndGradeLevelOrderByVersionNoDesc(
            String academicYearId, String gradeLevel);
    Optional<EducationPlan> findFirstByAcademicYearIdAndGradeLevelAndStatusOrderByVersionNoDesc(
            String academicYearId, String gradeLevel, String status);
}

