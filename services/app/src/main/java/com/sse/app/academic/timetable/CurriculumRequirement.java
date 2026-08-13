package com.sse.app.academic.timetable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "curriculum_requirements", uniqueConstraints =
        @UniqueConstraint(name = "uk_curriculum_requirement",
                columnNames = {"semesterId", "gradeLevel", "subjectId"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CurriculumRequirement {
    @Id
    private String id;
    @Column(nullable = false)
    private String semesterId;
    @Column(nullable = false)
    private String gradeLevel;
    @Column(nullable = false)
    private String subjectId;
    @Column(nullable = false)
    private String subjectName;
    @Column(nullable = false)
    private int weeklyPeriods;
    @Column(nullable = false)
    private int totalPeriods;
    private LocalDate startDate;
    private LocalDate endDate;
    private LocalDate examWindowStart;
    private LocalDate examWindowEnd;
    @Column(length = 1000)
    private String milestone;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;
}
