package com.sse.app.academic.exam;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

interface ExamPeriodRepository extends JpaRepository<ExamPeriod, String> {
    List<ExamPeriod> findByAcademicYearIdOrderByStartDateDesc(String academicYearId);
    boolean existsByAcademicYearIdAndCodeIgnoreCase(String academicYearId, String code);
}

interface ExamScheduleVersionRepository extends JpaRepository<ExamScheduleVersion, String> {
    List<ExamScheduleVersion> findByExamPeriodIdOrderByVersionNoDesc(String examPeriodId);
    Optional<ExamScheduleVersion> findByExamPeriodIdAndStatus(String examPeriodId, String status);
}

interface ExamSessionRepository extends JpaRepository<ExamSession, String> {
    List<ExamSession> findByVersionIdOrderByExamDateAscStartTimeAscGradeLevelAsc(String versionId);
    boolean existsByVersionIdAndSourceAssessmentPlanId(String versionId, String sourceAssessmentPlanId);
    void deleteByVersionId(String versionId);
}

interface ExamRoomAssignmentRepository extends JpaRepository<ExamRoomAssignment, String> {
    List<ExamRoomAssignment> findBySessionId(String sessionId);
    List<ExamRoomAssignment> findBySessionIdIn(List<String> sessionIds);
    void deleteBySessionIdIn(List<String> sessionIds);
}

interface ExamRoomStudentRepository extends JpaRepository<ExamRoomStudent, String> {
    List<ExamRoomStudent> findByRoomAssignmentId(String roomAssignmentId);
    List<ExamRoomStudent> findByRoomAssignmentIdIn(List<String> roomAssignmentIds);
    List<ExamRoomStudent> findByStudentId(String studentId);
    void deleteBySessionIdIn(List<String> sessionIds);
}

interface ExamTeacherUnavailabilityRepository extends JpaRepository<ExamTeacherUnavailability, String> {
    List<ExamTeacherUnavailability> findByExamPeriodIdOrderByUnavailableDateAsc(String examPeriodId);
    List<ExamTeacherUnavailability> findByTeacherId(String teacherId);
}
