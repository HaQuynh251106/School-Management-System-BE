package com.sse.app.academic.conduct;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface ConductRuleSetRepository extends JpaRepository<ConductRuleSet, String> {
    List<ConductRuleSet> findByAcademicYearIdAndSemesterIdOrderByVersionNoDesc(String academicYearId, String semesterId);
    List<ConductRuleSet> findByAcademicYearIdAndSemesterIdIsNullOrderByVersionNoDesc(String academicYearId);
}

interface ConductEvidenceRepository extends JpaRepository<ConductEvidence, String> {
    List<ConductEvidence> findByAcademicYearIdAndStudentIdOrderByOccurredOnDescCreatedAtDesc(String academicYearId, String studentId);
    Optional<ConductEvidence> findByAcademicYearIdAndSourceTypeAndSourceRef(String academicYearId, String sourceType, String sourceRef);
}

interface ConductEvaluationRepository extends JpaRepository<ConductEvaluation, String> {
    Optional<ConductEvaluation> findByAcademicYearIdAndSemesterIdAndStudentId(String academicYearId, String semesterId, String studentId);
    Optional<ConductEvaluation> findByAcademicYearIdAndSemesterIdIsNullAndStudentId(String academicYearId, String studentId);
}

interface ConductEvaluationAuditRepository extends JpaRepository<ConductEvaluationAudit, String> {
    List<ConductEvaluationAudit> findByEvaluationIdOrderByCreatedAtDesc(String evaluationId);
}
