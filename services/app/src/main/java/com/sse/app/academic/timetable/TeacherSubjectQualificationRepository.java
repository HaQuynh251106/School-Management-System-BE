package com.sse.app.academic.timetable;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TeacherSubjectQualificationRepository
        extends JpaRepository<TeacherSubjectQualification, String> {
    boolean existsByTeacherIdAndSubjectId(String teacherId, String subjectId);
    List<TeacherSubjectQualification> findByTeacherId(String teacherId);
}
