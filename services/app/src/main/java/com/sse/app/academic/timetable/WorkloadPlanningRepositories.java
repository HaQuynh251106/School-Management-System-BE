package com.sse.app.academic.timetable;

import org.springframework.data.jpa.repository.JpaRepository;

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
    Optional<TeacherLoadRegistration> findByTeacherIdAndSemesterId(String teacherId, String semesterId);
}
