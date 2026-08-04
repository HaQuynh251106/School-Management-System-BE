package com.sse.app.academic.conduct;

import jakarta.validation.constraints.*;

import java.time.LocalDate;
import java.util.List;

public final class ConductDtos {
    private ConductDtos() {}

    public record RuleSetView(
            String id, String academicYearId, String semesterId, int versionNo, String status,
            double attendanceWeight, double disciplineWeight, double responsibilityWeight,
            double participationWeight, double goodMin, double fairMin, double averageMin,
            int minAttendanceRecords, int minParticipationEvidence, String createdBy,
            String createdAt, String activatedAt) {}

    public record SaveRuleSetRequest(
            String semesterId,
            @DecimalMin("0") double attendanceWeight,
            @DecimalMin("0") double disciplineWeight,
            @DecimalMin("0") double responsibilityWeight,
            @DecimalMin("0") double participationWeight,
            @DecimalMin("0") @DecimalMax("100") double goodMin,
            @DecimalMin("0") @DecimalMax("100") double fairMin,
            @DecimalMin("0") @DecimalMax("100") double averageMin,
            @Min(0) int minAttendanceRecords,
            @Min(0) int minParticipationEvidence) {}

    public record SaveEvidenceRequest(
            @NotBlank String academicYearId, String semesterId,
            @NotBlank String classId, @NotBlank String studentId,
            @Pattern(regexp = "DISCIPLINE|RESPONSIBILITY|PARTICIPATION",
                    message = "Nhóm minh chứng không hợp lệ") String category,
            @DecimalMin("-30") @DecimalMax("30") double impactPoints,
            @NotBlank @Size(max = 300) String title,
            @Size(max = 3000) String description,
            @NotNull LocalDate occurredOn,
            @Size(max = 120) String externalKey) {}

    public record DecisionRequest(
            String semesterId,
            @Pattern(regexp = "GOOD|FAIR|AVERAGE|WEAK", message = "Mức rèn luyện không hợp lệ")
            String finalGrade,
            @Size(max = 2000) String overrideReason) {}

    public record EvidenceView(
            String id, String category, double impactPoints, String title, String description,
            LocalDate occurredOn, String sourceType, String sourceRef,
            String teacherId, String teacherName, String createdAt) {}

    public record CriterionBreakdown(
            String code, String label, double weight, Double rawScore, Double weightedScore,
            boolean sufficient, String summary, List<EvidenceView> evidence) {}

    public record AuditView(
            String id, String action, String previousGrade, String newGrade,
            String note, String actorId, String actorName, String createdAt) {}

    public record EvaluationView(
            String id, String academicYearId, String semesterId, String studentId,
            String studentName, String classId, String classCode, RuleSetView ruleSet,
            String readiness, List<String> missingData, Double suggestedScore,
            String suggestedGrade, String finalGrade, String overrideReason,
            String workflowStatus, String decidedBy, String decidedByName,
            String decidedAt, String calculatedAt, List<CriterionBreakdown> criteria,
            List<AuditView> audits, boolean editableByHomeroom) {}
}
