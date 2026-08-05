package com.sse.app.academic.timetable;

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
import java.time.LocalDate;

/** Tiết giảm, tiết quy đổi hoặc dạy vượt do Giáo vụ phê duyệt. */
@Entity
@Table(name = "teacher_workload_adjustments")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TeacherWorkloadAdjustment {
    @Id
    private String id;
    @Column(nullable = false)
    private String teacherId;
    @Column(nullable = false)
    private String academicYearId;
    @Column(nullable = false)
    private String category; // REDUCTION | CONVERSION | OVERTIME
    @Column(nullable = false)
    private String dutyType;
    @Column(nullable = false)
    private String title;
    @Column(nullable = false)
    private int weeklyPeriods;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    @Column(length = 1000)
    private String reason;
    @Column(nullable = false)
    private String status; // APPROVED | REJECTED | REVOKED
    private String approvedBy;
    private Instant approvedAt;
    private String revokedBy;
    private Instant revokedAt;
    @Column(length = 1000)
    private String revokeReason;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;
}
