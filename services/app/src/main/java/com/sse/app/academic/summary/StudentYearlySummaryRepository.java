package com.sse.app.academic.summary;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface StudentYearlySummaryRepository extends JpaRepository<StudentYearlySummary, String> {
    List<StudentYearlySummary> findByAcademicYearIdOrderByStudentName(String academicYearId);
    Optional<StudentYearlySummary> findByAcademicYearIdAndStudentId(String academicYearId, String studentId);
}
