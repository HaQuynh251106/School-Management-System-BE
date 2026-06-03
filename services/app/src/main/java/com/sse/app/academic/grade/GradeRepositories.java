package com.sse.app.academic.grade;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface GradeRepository extends JpaRepository<Grade, String> {
    List<Grade> findByStudentId(String studentId);
    List<Grade> findByStudentIdAndSemesterId(String studentId, String semesterId);
    List<Grade> findBySubjectIdAndSemesterId(String subjectId, String semesterId);
    Optional<Grade> findByStudentIdAndSubjectIdAndSemesterIdAndCategory(
            String studentId, String subjectId, String semesterId, String category);
}

interface GradeChangeLogRepository extends JpaRepository<GradeChangeLog, String> {
    List<GradeChangeLog> findByGradeIdOrderByChangedAtDesc(String gradeId);
}

interface ExamCategoryRepository extends JpaRepository<ExamCategory, String> {
    Optional<ExamCategory> findByCode(String code);
}
