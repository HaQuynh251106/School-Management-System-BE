package com.sse.app.academic.planning;

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
import java.time.LocalTime;

@Entity
@Table(name = "academic_exam_schedules")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AcademicExamSchedule {
    @Id
    private String id;
    private String planId;
    private String semesterId;
    private String subjectId;
    private String gradeLevel;
    private String name;
    private LocalDate examDate;
    private LocalTime startTime;
    private int durationMinutes;
    private String roomId;
    private String proctorTeacherId;
    private String status;
    private String notes;
    private Instant createdAt;
    private Instant updatedAt;
}
