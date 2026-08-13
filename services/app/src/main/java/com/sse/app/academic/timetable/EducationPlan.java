package com.sse.app.academic.timetable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "education_plans",
        uniqueConstraints = @UniqueConstraint(name = "uk_education_plan_version",
                columnNames = {"academic_year_id", "grade_level", "version_no"}),
        indexes = @Index(name = "idx_education_plan_scope",
                columnList = "academic_year_id,grade_level,status,version_no"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EducationPlan {
    @Id
    private String id;
    @Column(name = "academic_year_id", nullable = false)
    private String academicYearId;
    @Column(name = "grade_level", nullable = false)
    private String gradeLevel;
    @Column(nullable = false)
    private String name;
    @Column(name = "version_no", nullable = false)
    private int versionNo;
    @Column(nullable = false)
    private String status;
    @Column(length = 2000)
    private String description;
    @Column(name = "source_plan_id")
    private String sourcePlanId;
    @Column(name = "revision_reason", length = 1000)
    private String revisionReason;
    @Column(name = "created_by")
    private String createdBy;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "submitted_by")
    private String submittedBy;
    @Column(name = "submitted_at")
    private Instant submittedAt;
    @Column(name = "approved_by")
    private String approvedBy;
    @Column(name = "approved_at")
    private Instant approvedAt;
    @Column(name = "published_by")
    private String publishedBy;
    @Column(name = "published_at")
    private Instant publishedAt;
}

