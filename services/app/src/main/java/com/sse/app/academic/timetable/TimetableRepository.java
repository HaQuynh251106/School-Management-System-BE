package com.sse.app.academic.timetable;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface TimetableRepository extends JpaRepository<TimetableSlot, String> {
    List<TimetableSlot> findByClassId(String classId);
    List<TimetableSlot> findByTeacherId(String teacherId);
    List<TimetableSlot> findBySemesterId(String semesterId);
    List<TimetableSlot> findByDayOfWeekAndPeriodNo(String dayOfWeek, int periodNo);
}
