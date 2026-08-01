package com.sse.app.academic.exam;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "exam_proctor_plans")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ExamProctorPlan {
    @Id private String id;
    @Column(nullable = false) private String scheduleId;
    @Column(nullable = false) private String status;
    @Column(nullable = false) private boolean includeSecondProctor;
    @Column(nullable = false) private int roomCount;
    @Column(nullable = false) private int readyRoomCount;
    @Column(nullable = false) private int missingAssignmentCount;
    @Column(nullable = false, length = 128) private String sourceFingerprint;
    @Column(length = 2000) private String warningSummary;
    @Column(nullable = false) private String createdBy;
    @Column(nullable = false) private Instant createdAt;
    private String appliedBy;
    private Instant appliedAt;
    private String undoneBy;
    private Instant undoneAt;
}
