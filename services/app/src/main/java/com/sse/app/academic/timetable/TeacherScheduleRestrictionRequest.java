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

@Entity
@Table(name = "teacher_schedule_restriction_requests")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TeacherScheduleRestrictionRequest {
    @Id
    private String id;
    @Column(nullable = false)
    private String teacherId;
    @Column(nullable = false)
    private String teacherName;
    @Column(nullable = false)
    private String semesterId;
    @Column(nullable = false, length = 2000)
    private String restrictedSlots;
    @Column(nullable = false)
    private LocalDate effectiveFrom;
    @Column(nullable = false)
    private LocalDate effectiveTo;
    @Column(nullable = false, length = 1000)
    private String reason;
    @Column(length = 1000)
    private String evidenceUrl;
    @Column(nullable = false)
    private String status;
    @Column(length = 1000)
    private String decisionNote;
    private String reviewedBy;
    private Instant reviewedAt;
    private String revokedBy;
    private Instant revokedAt;
    @Column(length = 1000)
    private String revokeReason;
    @Column(nullable = false)
    private Instant submittedAt;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;
}
