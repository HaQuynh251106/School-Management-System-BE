package com.sse.app.academic.planning;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "academic_plan_approval_history")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AcademicPlanApprovalHistory {
    @Id private String id;
    private String planId;
    private String action;
    private String fromStatus;
    private String toStatus;
    private String actorId;
    private String comment;
    private Instant createdAt;
}
