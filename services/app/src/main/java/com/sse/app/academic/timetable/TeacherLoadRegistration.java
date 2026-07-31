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
    @Column(length = 2000)
    private String unavailableSlots;
    @Column(length = 500)
    private String preferredGradeLevels;
    @Column(length = 1000)
    private String note;
    @Column(length = 1000)
    private String reviewNote;
    @Column(nullable = false)
    private String status;
    private Instant submittedAt;
    private Instant reviewedAt;
    private String reviewedBy;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;
}
