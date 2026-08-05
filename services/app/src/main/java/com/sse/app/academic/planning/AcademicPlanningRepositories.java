package com.sse.app.academic.planning;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

interface EducationProgramRepository extends JpaRepository<EducationProgram, String> {
    List<EducationProgram> findAllByOrderByStartYearDescCodeAsc();
    List<EducationProgram> findByStatus(String status);
    Optional<EducationProgram> findByCodeIgnoreCase(String code);
}

interface EducationProgramSubjectRepository
        extends JpaRepository<EducationProgramSubject, String> {
    List<EducationProgramSubject> findByProgramIdAndGradeLevelOrderBySubjectIdAsc(
            String programId, String gradeLevel);
    Optional<EducationProgramSubject> findByProgramIdAndGradeLevelAndSubjectId(
            String programId, String gradeLevel, String subjectId);
}

interface SubjectCombinationRepository extends JpaRepository<SubjectCombination, String> {
    List<SubjectCombination> findByAcademicYearIdAndGradeLevelOrderByCodeAsc(
            String academicYearId, String gradeLevel);
    Optional<SubjectCombination> findByAcademicYearIdAndGradeLevelAndCodeIgnoreCase(
            String academicYearId, String gradeLevel, String code);
}

interface SubjectCombinationSubjectRepository
        extends JpaRepository<SubjectCombinationSubject, String> {
    List<SubjectCombinationSubject> findByCombinationIdOrderBySubjectIdAsc(String combinationId);
    void deleteByCombinationId(String combinationId);
}

interface ClassSubjectCombinationRepository
        extends JpaRepository<ClassSubjectCombination, String> {
    List<ClassSubjectCombination> findByCombinationId(String combinationId);
}

interface TeacherSubjectCapabilityRepository
        extends JpaRepository<TeacherSubjectCapability, String> {
    List<TeacherSubjectCapability> findByTeacherIdAndActiveTrueOrderBySubjectIdAsc(String teacherId);
    List<TeacherSubjectCapability> findBySubjectIdAndActiveTrueOrderByTeacherIdAsc(String subjectId);
    Optional<TeacherSubjectCapability> findByTeacherIdAndSubjectId(String teacherId, String subjectId);
}

interface AcademicTrainingPlanRepository
        extends JpaRepository<AcademicTrainingPlan, String> {
    List<AcademicTrainingPlan> findByAcademicYearIdOrderByGradeLevel(
            String academicYearId);
    List<AcademicTrainingPlan> findByAcademicYearIdAndGradeLevelOrderByVersionNumberDesc(
            String academicYearId, String gradeLevel);
    Optional<AcademicTrainingPlan> findByAcademicYearIdAndGradeLevelAndVersionNumber(
            String academicYearId, String gradeLevel, int versionNumber);
}

interface AcademicTrainingPlanSubjectRepository
        extends JpaRepository<AcademicTrainingPlanSubject, String> {
    List<AcademicTrainingPlanSubject> findByPlanIdOrderByDisplayOrderAscSubjectIdAsc(
            String planId);
    Optional<AcademicTrainingPlanSubject> findByPlanIdAndSemesterIdAndSubjectId(
            String planId, String semesterId, String subjectId);
    long countByPlanIdAndSemesterId(String planId, String semesterId);
}

interface AcademicExamScheduleRepository
        extends JpaRepository<AcademicExamSchedule, String> {
    List<AcademicExamSchedule> findByPlanIdOrderByExamDateAscStartTimeAsc(String planId);
    List<AcademicExamSchedule> findByExamDate(LocalDate examDate);
}

interface AcademicTrainingPlanStageRepository
        extends JpaRepository<AcademicTrainingPlanStage, String> {
    List<AcademicTrainingPlanStage> findByPlanSubjectIdOrderBySequenceAsc(
            String planSubjectId);
    Optional<AcademicTrainingPlanStage> findByPlanSubjectIdAndCodeIgnoreCase(
            String planSubjectId, String code);
    long countByPlanSubjectId(String planSubjectId);
}

interface AcademicCurriculumItemRepository
        extends JpaRepository<AcademicCurriculumItem, String> {
    List<AcademicCurriculumItem> findByPlanSubjectIdOrderBySequenceAsc(
            String planSubjectId);
    Optional<AcademicCurriculumItem> findByPlanSubjectIdAndCodeIgnoreCase(
            String planSubjectId, String code);
    long countByPlanSubjectId(String planSubjectId);
    long countByParentId(String parentId);
}

interface AcademicTrainingPlanSpecialWeekRepository
        extends JpaRepository<AcademicTrainingPlanSpecialWeek, String> {
    List<AcademicTrainingPlanSpecialWeek>
            findByPlanSubjectIdOrderByWeekNumberAscWeekTypeAsc(
                    String planSubjectId);
    boolean existsByPlanSubjectIdAndWeekType(
            String planSubjectId, String weekType);
    Optional<AcademicTrainingPlanSpecialWeek> findByPlanSubjectIdAndWeekNumber(
            String planSubjectId, int weekNumber);
    long countByPlanSubjectId(String planSubjectId);
}

interface AcademicCurriculumDistributionRepository
        extends JpaRepository<AcademicCurriculumDistribution, String> {
    List<AcademicCurriculumDistribution> findByPlanSubjectIdOrderByWeekNumberAscIdAsc(
            String planSubjectId);
    long countByPlanSubjectId(String planSubjectId);
}

interface AcademicAssessmentPlanRepository
        extends JpaRepository<AcademicAssessmentPlan, String> {
    List<AcademicAssessmentPlan> findByPlanIdOrderBySemesterIdAscWeekNumberAscSubjectIdAsc(
            String planId);
    long countByPlanIdAndSemesterIdAndSubjectIdAndWeekNumber(
            String planId, String semesterId, String subjectId, int weekNumber);
}

interface AcademicPlanApprovalHistoryRepository
        extends JpaRepository<AcademicPlanApprovalHistory, String> {
    List<AcademicPlanApprovalHistory> findByPlanIdOrderByCreatedAtAsc(String planId);
}
