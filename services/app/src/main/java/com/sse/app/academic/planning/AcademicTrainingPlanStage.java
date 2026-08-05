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
@Table(name = "academic_training_plan_stages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AcademicTrainingPlanStage {
    @Id
    private String id;
    private String planSubjectId;
    private String code;
    private String name;
    private int sequence;
    private LocalDate startDate;
    private LocalDate endDate;
    private int targetPeriods;
    private String description;
    private Instant createdAt;
    private Instant updatedAt;
}
