package com.sse.app.academic.timetable;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.time.LocalDate;
import java.util.Optional;

interface TimetableRepository extends JpaRepository<TimetableSlot, String> {
    List<TimetableSlot> findByClassId(String classId);
    boolean existsByClassId(String classId);
    List<TimetableSlot> findByTeacherId(String teacherId);
    List<TimetableSlot> findBySemesterId(String semesterId);
    List<TimetableSlot> findBySemesterIdAndClassIdIn(String semesterId, List<String> classIds);
    @Modifying(flushAutomatically = true)
    @Query("delete from TimetableSlot slot where slot.semesterId = :semesterId and slot.classId in :classIds")
    int deleteBySemesterIdAndClassIdIn(@Param("semesterId") String semesterId,
                                       @Param("classIds") List<String> classIds);
    List<TimetableSlot> findByDayOfWeekAndPeriodNo(String dayOfWeek, int periodNo);
    boolean existsByTeacherIdAndClassIdAndSubjectIdAndSemesterId(String teacherId, String classId, String subjectId, String semesterId);
}

interface TimetableScheduleRepository extends JpaRepository<TimetableSchedule, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select schedule from TimetableSchedule schedule where schedule.id = :id")
    Optional<TimetableSchedule> findByIdForUpdate(@Param("id") String id);
    List<TimetableSchedule> findBySemesterIdOrderByCreatedAtDesc(String semesterId);
    List<TimetableSchedule> findBySemesterIdAndStatus(String semesterId, String status);
    Optional<TimetableSchedule> findFirstBySemesterIdAndScopeGradeLevelAndStatusOrderByPublishedAtDesc(
            String semesterId, String scopeGradeLevel, String status);
    Optional<TimetableSchedule> findFirstBySemesterIdAndScopeGradeLevelIsNullAndStatusOrderByPublishedAtDesc(
            String semesterId, String status);
    Optional<TimetableSchedule> findFirstByStatusOrderByPublishedAtDesc(String status);
}

interface TimetableDraftSlotRepository extends JpaRepository<TimetableDraftSlot, String> {
    List<TimetableDraftSlot> findByScheduleIdOrderByClassIdAscDayOfWeekAscPeriodNoAsc(String scheduleId);
    List<TimetableDraftSlot> findByScheduleIdAndClassIdOrderByDayOfWeekAscPeriodNoAsc(
            String scheduleId, String classId);
    boolean existsByClassId(String classId);
    void deleteByScheduleId(String scheduleId);
}

interface ClassLessonProgressRepository extends JpaRepository<ClassLessonProgress, String> {
    List<ClassLessonProgress> findBySemesterIdAndSubjectIdOrderByLessonDateAsc(
            String semesterId, String subjectId);
    List<ClassLessonProgress> findByClassIdAndSemesterIdOrderByLessonDateDesc(
            String classId, String semesterId);
    Optional<ClassLessonProgress> findByClassIdAndSubjectIdAndSemesterIdAndCurriculumItemIdAndLessonDate(
            String classId, String subjectId, String semesterId,
            String curriculumItemId, LocalDate lessonDate);
}

interface TimetableMakeupProposalRepository extends JpaRepository<TimetableMakeupProposal, String> {
    List<TimetableMakeupProposal> findByScheduleIdOrderByMissedDateAscMissedPeriodNoAsc(String scheduleId);
    boolean existsByScheduleIdAndClassIdAndMissedDateAndMissedPeriodNo(
            String scheduleId, String classId, LocalDate missedDate, int missedPeriodNo);
}
