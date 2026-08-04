package com.sse.app.academic.conduct;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "conduct_evaluation_audits")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ConductEvaluationAudit {
    @Id private String id;
    @Column(nullable = false) private String evaluationId;
    @Column(nullable = false, length = 60) private String action;
    private String previousGrade;
    private String newGrade;
    @Column(length = 2000) private String note;
    @Column(nullable = false) private String actorId;
    @Column(nullable = false) private Instant createdAt;
}
