package com.sse.app.report;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface StudentYearlySummaryRepository extends JpaRepository<StudentYearlySummary, String> {
    List<StudentYearlySummary> findByAcademicYearIdAndClassId(String academicYearId, String classId);
    List<StudentYearlySummary> findByStudentId(String studentId);
    Optional<StudentYearlySummary> findByAcademicYearIdAndStudentId(String academicYearId, String studentId);
    boolean existsByAcademicYearIdAndStatus(String academicYearId, String status);
}

interface AcademicResultLockRepository extends JpaRepository<AcademicResultLock, String> {
    boolean existsByClassIdAndSemesterId(String classId, String semesterId);
    List<AcademicResultLock> findByAcademicYearIdAndClassId(String academicYearId, String classId);
    void deleteByAcademicYearIdAndClassId(String academicYearId, String classId);
}

interface AcademicPromotionPolicyRepository extends JpaRepository<AcademicPromotionPolicy, String> {
    Optional<AcademicPromotionPolicy> findByAcademicYearId(String academicYearId);
}

interface StudentClassEnrollmentRepository extends JpaRepository<StudentClassEnrollment, String> {
    Optional<StudentClassEnrollment> findByAcademicYearIdAndStudentId(
            String academicYearId, String studentId);
    List<StudentClassEnrollment> findByAcademicYearIdAndClassId(
            String academicYearId, String classId);
    List<StudentClassEnrollment> findBySourceAcademicYearIdAndSourceClassId(
            String sourceAcademicYearId, String sourceClassId);
}

interface YearResultPublicationRepository extends JpaRepository<YearResultPublication, String> {
    Optional<YearResultPublication> findByAcademicYearIdAndClassId(
            String academicYearId, String classId);
}

interface YearResultPublicationHistoryRepository
        extends JpaRepository<YearResultPublicationHistory, String> {
    List<YearResultPublicationHistory>
    findByAcademicYearIdAndClassIdOrderByOccurredAtDesc(
            String academicYearId, String classId);
}
