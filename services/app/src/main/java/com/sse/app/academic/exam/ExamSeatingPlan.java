package com.sse.app.academic.exam;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "exam_seating_plans")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ExamSeatingPlan {
    @Id private String id;
    @Column(nullable = false) private String examPeriodId;
    @Column(nullable = false) private String scheduleId;
    @Column(nullable = false) private String status;
    @Column(nullable = false) private int candidateCount;
    @Column(nullable = false) private int totalCapacity;
    @Column(nullable = false) private int assignedCount;
    @Column(nullable = false) private int unassignedCount;
    @Column(nullable = false, length = 128) private String sourceFingerprint;
    @Column(nullable = false, length = 4000) private String selectedRoomIds;
    @Column(length = 2000) private String warningSummary;
    @Column(nullable = false) private String createdBy;
    @Column(nullable = false) private Instant createdAt;
    private String appliedBy;
    private Instant appliedAt;
    private String undoneBy;
    private Instant undoneAt;
}
