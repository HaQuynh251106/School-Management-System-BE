package com.sse.app.academic.timetable;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

interface TeachingProgressRepository extends JpaRepository<TeachingProgress, String> {
    Optional<TeachingProgress> findByTimetableSlotIdAndLessonDate(String timetableSlotId, LocalDate lessonDate);
    List<TeachingProgress> findBySemesterIdOrderByLessonDateDesc(String semesterId);
}
