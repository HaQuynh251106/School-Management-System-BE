package com.sse.app.academic.teaching;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TeachingAssignmentRepository extends JpaRepository<TeacherClassSubject, String> {
    List<TeacherClassSubject> findByTeacherIdAndStatus(String teacherId, String status);
    List<TeacherClassSubject> findByClassIdAndStatus(String classId, String status);
    Optional<TeacherClassSubject> findByClassIdAndSubjectIdAndSemesterIdAndStatus(
            String classId, String subjectId, String semesterId, String status);
    boolean existsByTeacherIdAndClassIdAndSubjectIdAndSemesterIdAndStatus(
            String teacherId, String classId, String subjectId, String semesterId, String status);
}
