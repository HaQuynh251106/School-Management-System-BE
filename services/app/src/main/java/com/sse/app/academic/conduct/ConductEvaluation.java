package com.sse.app.academic.conduct;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "conduct_evaluations")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ConductEvaluation {
    @Id private String id;
    @Column(nullable = false) private String academicYearId;
    private String semesterId;
    @Column(nullable = false) private String scopeKey;
    @Column(nullable = false) private String studentId;
    @Column(nullable = false) private String classId;
    @Column(nullable = false) private String ruleSetId;
    @Column(nullable = false, length = 30) private String readiness;
    private Double suggestedScore;
    private String suggestedGrade;
    private String finalGrade;
    @Column(length = 2000) private String overrideReason;
    @Column(nullable = false, length = 30) private String workflowStatus;
    private String decidedBy;
    private Instant decidedAt;
    @Column(nullable = false) private Instant calculatedAt;
    @Column(nullable = false) private Instant updatedAt;
    @Version private long version;
}
