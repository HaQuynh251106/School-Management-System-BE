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
@Table(name = "teacher_load_registrations", uniqueConstraints =
        @UniqueConstraint(name = "uk_teacher_load_registration",
                columnNames = {"teacherId", "semesterId"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TeacherLoadRegistration {
    @Id
    private String id;
    @Column(nullable = false)
    private String teacherId;
    @Column(nullable = false)
    private String teacherName;
    @Column(nullable = false)
    private String semesterId;
    @Column(nullable = false)
    private int maxWeeklyPeriods;
    private Integer baseWeeklyPeriods;
    private Integer reductionWeeklyPeriods;
    private Integer convertedWeeklyPeriods;
    private Integer approvedOvertimeWeeklyPeriods;
    private Integer annualTargetPeriods;
    private Integer standardWeeklyPeriods;
    private Integer minWeeklyPeriods;
    private Integer maxDailyPeriods;
    private Integer maxConsecutivePeriods;
    @Column(length = 2000)
    private String unavailableSlots;
    @Column(length = 500)
    private String preferredGradeLevels;
    @Column(length = 250)
    private String preferredDaysOff;
    @Column(length = 1000)
    private String note;
    @Column(length = 1000)
    private String reviewNote;
    @Column(nullable = false)
    private String status;
    private Instant submittedAt;
    private Instant reviewedAt;
    private String reviewedBy;
    private LocalDate extendedClosesOn;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;

    public int getStandardWeeklyPeriods() {
        return standardWeeklyPeriods == null ? Math.min(maxWeeklyPeriods, 17) : standardWeeklyPeriods;
    }

    public int getMinWeeklyPeriods() {
        return minWeeklyPeriods == null ? getStandardWeeklyPeriods() : minWeeklyPeriods;
    }

    public int getBaseWeeklyPeriods() {
        return baseWeeklyPeriods == null ? 17 : baseWeeklyPeriods;
    }

    public int getReductionWeeklyPeriods() {
        return reductionWeeklyPeriods == null ? 0 : reductionWeeklyPeriods;
    }

    public int getConvertedWeeklyPeriods() {
        return convertedWeeklyPeriods == null ? 0 : convertedWeeklyPeriods;
    }

    public int getApprovedOvertimeWeeklyPeriods() {
        return approvedOvertimeWeeklyPeriods == null ? 0 : approvedOvertimeWeeklyPeriods;
    }

    public int getAnnualTargetPeriods() {
        return annualTargetPeriods == null ? getStandardWeeklyPeriods() * 35 : annualTargetPeriods;
    }

    public int getMaxDailyPeriods() {
        return maxDailyPeriods == null ? TimetableRulePolicy.PERIODS_PER_DAY : maxDailyPeriods;
    }

    public int getMaxConsecutivePeriods() {
        return maxConsecutivePeriods == null ? TimetableRulePolicy.PERIODS_PER_DAY : maxConsecutivePeriods;
    }
}
