package com.sse.app.academic.structure;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** A student intake group that remains stable while classes change each academic year. */
@Entity
@Table(name = "cohorts")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Cohort {
    @Id private String id;
    private String code;
    private String name;
    private int entryYear;
    private int graduationYear;
    private int durationYears;
    private String status;
    private String entryAcademicYearId;
    private Instant createdAt;
    private String createdBy;
    private Instant completedAt;
}
