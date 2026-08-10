package com.sse.app.academic.grade;

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

/** Required grade components for one subject in one semester. */
@Entity
@Table(name = "grade_configurations",
        uniqueConstraints = @UniqueConstraint(name = "uk_grade_config_scope",
                columnNames = {"subjectId", "semesterId", "categoryCode"}),
        indexes = @Index(name = "idx_grade_config_scope", columnList = "subjectId,semesterId"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class GradeConfiguration {
    @Id
    private String id;
    private String subjectId;
    private String semesterId;
    private String categoryCode;
    private String categoryName;
    private int requiredCount;
    private double weight;
    private boolean active;
    private String updatedBy;
    private Instant updatedAt;
}
