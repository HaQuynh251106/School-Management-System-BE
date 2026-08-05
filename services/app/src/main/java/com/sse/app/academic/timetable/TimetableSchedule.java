package com.sse.app.academic.timetable;

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
@Table(name = "timetable_schedules")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TimetableSchedule {
    @Id
    private String id;
    private String academicYearId;
    private String semesterId;
    private String scopeGradeLevel;
    private String name;
    private String status;
    private String teachingDays;
    private int firstPeriod;
    private int lastPeriod;
    private int maxPeriodsPerDay;
    private int maxProgressGapDays;
    private int maxProgressGapPeriods;
    private int maxCurriculumGapLessons;
    private int solveSeconds;
    private String solverScore;
    private int hardViolationCount;
    private int warningCount;
    private String generationSummary;
    private String sourcePlanSummary;
    @Column(columnDefinition = "text")
    private String sourcePlanSnapshot;
    private Instant generatedAt;
    private String generatedBy;
    private Instant publishedAt;
    private String publishedBy;
    private Instant lockedAt;
    private Instant createdAt;
    private Instant updatedAt;
}
