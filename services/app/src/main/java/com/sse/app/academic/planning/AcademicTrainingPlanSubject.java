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

@Entity
@Table(name = "academic_training_plan_subjects")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AcademicTrainingPlanSubject {
    @Id
    private String id;
    private String planId;
    private String semesterId;
    private String subjectId;
    private int weeklyPeriods;
    private int totalPeriods;
    private LocalDate startDate;
    private LocalDate endDate;
    private boolean examRequired;
    private int displayOrder;
    private Instant createdAt;
    private Instant updatedAt;
}
