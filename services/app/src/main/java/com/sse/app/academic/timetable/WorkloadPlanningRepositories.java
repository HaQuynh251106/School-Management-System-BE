package com.sse.app.academic.timetable;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

interface CurriculumRequirementRepository extends JpaRepository<CurriculumRequirement, String> {
    List<CurriculumRequirement> findBySemesterId(String semesterId);
    Optional<CurriculumRequirement> findBySemesterIdAndGradeLevelAndSubjectId(
            String semesterId, String gradeLevel, String subjectId);
}

interface CurriculumRequirementHistoryRepository extends JpaRepository<CurriculumRequirementHistory, String> {
    List<CurriculumRequirementHistory> findTop100BySemesterIdOrderByCreatedAtDesc(String semesterId);
}

interface TeacherLoadRegistrationRepository extends JpaRepository<TeacherLoadRegistration, String> {
    List<TeacherLoadRegistration> findBySemesterId(String semesterId);
    boolean existsBySemesterId(String semesterId);
    Optional<TeacherLoadRegistration> findByTeacherIdAndSemesterId(String teacherId, String semesterId);
    @Query(value = "select count(*) from (select distinct ar.slot_id, ar.date " +
            "from attendance_records ar join timetable_slots ts on ts.id=ar.slot_id " +
            "where ts.teacher_id=:teacherId and ts.semester_id=:semesterId) taught", nativeQuery = true)
    long countActualTaughtPeriods(@Param("teacherId") String teacherId,
                                  @Param("semesterId") String semesterId);
    @Query(value = "select count(*) from (select distinct ar.slot_id, ar.date " +
            "from attendance_records ar join timetable_slots ts on ts.id=ar.slot_id " +
            "join semesters sem on sem.id=ts.semester_id " +
            "where ts.teacher_id=:teacherId and sem.academic_year_id=:academicYearId) taught", nativeQuery = true)
    long countActualTaughtPeriodsInYear(@Param("teacherId") String teacherId,
                                        @Param("academicYearId") String academicYearId);
    @Query(value = "select count(*) from timetable_slots where semester_id=:semesterId", nativeQuery = true)
    int countTimetableSlots(@Param("semesterId") String semesterId);
}

interface TeacherLoadRegistrationWindowRepository extends JpaRepository<TeacherLoadRegistrationWindow, String> {
    Optional<TeacherLoadRegistrationWindow> findBySemesterId(String semesterId);
}

interface TeacherLoadRegistrationHistoryRepository extends JpaRepository<TeacherLoadRegistrationHistory, String> {
    List<TeacherLoadRegistrationHistory> findTop100BySemesterIdOrderByCreatedAtDesc(String semesterId);
    List<TeacherLoadRegistrationHistory> findTop100ByRegistrationIdOrderByCreatedAtDesc(String registrationId);
}

interface TeacherScheduleRestrictionRequestRepository
        extends JpaRepository<TeacherScheduleRestrictionRequest, String> {
    List<TeacherScheduleRestrictionRequest> findByTeacherIdAndSemesterIdOrderByCreatedAtDesc(
            String teacherId, String semesterId);
    List<TeacherScheduleRestrictionRequest> findBySemesterIdOrderByCreatedAtDesc(String semesterId);
    List<TeacherScheduleRestrictionRequest> findByTeacherIdAndSemesterIdAndStatus(
            String teacherId, String semesterId, String status);
    long countByTeacherIdAndSemesterIdAndStatus(String teacherId, String semesterId, String status);
    long countBySemesterIdAndStatus(String semesterId, String status);
}

interface TeacherScheduleRestrictionHistoryRepository
        extends JpaRepository<TeacherScheduleRestrictionHistory, String> {
    List<TeacherScheduleRestrictionHistory> findTop100ByRequestIdOrderByCreatedAtDesc(String requestId);
    List<TeacherScheduleRestrictionHistory> findTop200BySemesterIdOrderByCreatedAtDesc(String semesterId);
}

interface TeacherWorkloadPolicyRepository extends JpaRepository<TeacherWorkloadPolicy, String> {
    Optional<TeacherWorkloadPolicy> findByAcademicYearId(String academicYearId);
}

interface TeacherWorkloadAdjustmentRepository extends JpaRepository<TeacherWorkloadAdjustment, String> {
    List<TeacherWorkloadAdjustment> findByAcademicYearIdOrderByCreatedAtDesc(String academicYearId);
    List<TeacherWorkloadAdjustment> findByTeacherIdAndAcademicYearIdAndStatus(
            String teacherId, String academicYearId, String status);
}
