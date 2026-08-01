package com.sse.app.academic.exam;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

interface ExamPeriodRepository extends JpaRepository<ExamPeriod, String> { Optional<ExamPeriod> findByCode(String code); }
interface ExamScheduleRepository extends JpaRepository<ExamSchedule, String> { List<ExamSchedule> findByExamPeriodId(String examPeriodId); }
interface ExamRoomRepository extends JpaRepository<ExamRoom, String> { List<ExamRoom> findByScheduleId(String scheduleId); }
interface ExamGradingAssignmentRepository extends JpaRepository<ExamGradingAssignment, String> {
    List<ExamGradingAssignment> findByExamPeriodId(String examPeriodId);
    List<ExamGradingAssignment> findByScheduleId(String scheduleId);
    List<ExamGradingAssignment> findByTeacherId(String teacherId);
    Optional<ExamGradingAssignment> findByScheduleIdAndClassId(String scheduleId, String classId);
}
interface ExamCandidateRepository extends JpaRepository<ExamCandidate, String> {
    List<ExamCandidate> findByExamPeriodId(String examPeriodId);
    List<ExamCandidate> findByExamPeriodIdAndClassId(String examPeriodId, String classId);
    List<ExamCandidate> findByScheduleId(String scheduleId);
    List<ExamCandidate> findByScheduleIdAndClassId(String scheduleId, String classId);
    Optional<ExamCandidate> findByScheduleIdAndStudentId(String scheduleId, String studentId);
    List<ExamCandidate> findByStudentId(String studentId);
    Optional<ExamCandidate> findFirstByExamPeriodIdAndStudentIdOrderByCandidateNo(String examPeriodId, String studentId);
    long countByExamRoomId(String examRoomId);
    void deleteByScheduleIdAndClassId(String scheduleId, String classId);
    void deleteByScheduleId(String scheduleId);
}
interface ExamSeatingPlanRepository extends JpaRepository<ExamSeatingPlan, String> {
    List<ExamSeatingPlan> findByScheduleIdOrderByCreatedAtDesc(String scheduleId);
}
interface ExamSeatingPlanItemRepository extends JpaRepository<ExamSeatingPlanItem, String> {
    List<ExamSeatingPlanItem> findByPlanId(String planId);
    List<ExamSeatingPlanItem> findByPlanIdAndRowType(String planId, String rowType);
    void deleteByPlanId(String planId);
}
interface ExamProctorPlanRepository extends JpaRepository<ExamProctorPlan, String> {
    List<ExamProctorPlan> findByScheduleIdOrderByCreatedAtDesc(String scheduleId);
}
interface ExamProctorPlanItemRepository extends JpaRepository<ExamProctorPlanItem, String> {
    List<ExamProctorPlanItem> findByPlanId(String planId);
}
interface ExamResultRepository extends JpaRepository<ExamResult, String> {
    List<ExamResult> findByExamPeriodId(String examPeriodId);
    List<ExamResult> findByExamPeriodIdAndStudentId(String examPeriodId, String studentId);
    Optional<ExamResult> findByExamPeriodIdAndStudentIdAndSubjectId(String examPeriodId, String studentId, String subjectId);
}
interface ExamReviewRepository extends JpaRepository<ExamReviewRequest, String> {
    List<ExamReviewRequest> findByExamPeriodId(String examPeriodId);
    List<ExamReviewRequest> findByStudentIdOrderByRequestedAtDesc(String studentId);
}
interface ExamScoreAdjustmentRepository extends JpaRepository<ExamScoreAdjustment, String> { List<ExamScoreAdjustment> findByExamPeriodIdOrderByAdjustedAtDesc(String examPeriodId); }
