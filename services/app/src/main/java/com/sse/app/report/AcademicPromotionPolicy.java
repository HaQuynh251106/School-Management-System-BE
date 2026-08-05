package com.sse.app.report;

import jakarta.persistence.Column;
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
@Table(name = "academic_promotion_policies")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AcademicPromotionPolicy {
    @Id
    private String id;
    @Column(nullable = false, unique = true)
    private String academicYearId;
    private Double minimumYearlyAverage;
    private String minimumConductGrade;
    private Double subjectMinimumScore;
    private Integer maximumSubjectsBelowMinimum;
    private Double minimumAttendanceRate;
    private String updatedBy;
    private Instant updatedAt;
}
