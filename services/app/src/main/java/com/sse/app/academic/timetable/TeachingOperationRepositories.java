package com.sse.app.academic.timetable;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

interface LessonDiaryRepository extends JpaRepository<LessonDiary, String> {
    Optional<LessonDiary> findBySlotIdAndSessionDate(String slotId, LocalDate sessionDate);
    List<LessonDiary> findByActualTeacherIdAndSessionDateBetweenOrderBySessionDateDesc(
            String actualTeacherId, LocalDate from, LocalDate to);
}

interface TimetableChangeRequestRepository extends JpaRepository<TimetableChangeRequest, String> {
    List<TimetableChangeRequest> findByRequestedByOrOriginalTeacherIdOrSubstituteTeacherIdOrderByCreatedAtDesc(
            String requestedBy, String originalTeacherId, String substituteTeacherId);
    List<TimetableChangeRequest> findByStatusOrderByCreatedAtAsc(String status);
    Optional<TimetableChangeRequest> findFirstBySlotIdAndOccurrenceDateAndStatus(
            String slotId, LocalDate occurrenceDate, String status);
    List<TimetableChangeRequest> findBySubstituteTeacherIdAndStatusAndOccurrenceDateBetween(
            String substituteTeacherId, String status, LocalDate from, LocalDate to);
    List<TimetableChangeRequest> findBySubstituteTeacherIdAndStatus(String substituteTeacherId, String status);
    List<TimetableChangeRequest> findByStatusAndProposedDateBetween(
            String status, LocalDate from, LocalDate to);
}
