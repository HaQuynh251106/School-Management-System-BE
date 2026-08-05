package com.sse.app.academic.planning;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Column;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "academic_training_plans")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AcademicTrainingPlan {
    @Id
    private String id;
    private String academicYearId;
    private String gradeLevel;
    private String name;
    private String programId;
    private String description;
    private String status;
    private int versionNumber;
    private String basedOnPlanId;
    private int maxProgressGapDays;
    private String createdBy;
    private Instant submittedAt;
    private String submittedBy;
    private Instant reviewedAt;
    private String reviewedBy;
    private Instant approvedAt;
    private String approvedBy;
    private String workflowComment;
    private Instant publishedAt;
    private String publishedBy;
    private Instant lockedAt;
    private String lockedBy;
    @Column(columnDefinition = "text")
    private String validationSnapshot;
    private Instant validatedAt;
    private Instant createdAt;
    private Instant updatedAt;
}
