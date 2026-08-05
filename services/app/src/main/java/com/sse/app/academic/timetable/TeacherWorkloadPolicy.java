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

/** Chính sách định mức của một năm học. Giáo viên không được tự thay đổi các giá trị này. */
@Entity
@Table(name = "teacher_workload_policies", uniqueConstraints =
        @UniqueConstraint(name = "uk_teacher_workload_policy_year", columnNames = {"academicYearId"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TeacherWorkloadPolicy {
    @Id
    private String id;
    @Column(nullable = false)
    private String academicYearId;
    @Column(nullable = false)
    private String schoolLevel;
    @Column(nullable = false)
    private int baseWeeklyPeriods;
    @Column(nullable = false)
    private int teachingWeeks;
    @Column(nullable = false)
    private int maxOvertimePercent;
    @Column(nullable = false)
    private int homeroomReductionPeriods;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    @Column(length = 500)
    private String sourceDocument;
    @Column(nullable = false)
    private boolean active;
    private String configuredBy;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;
}
