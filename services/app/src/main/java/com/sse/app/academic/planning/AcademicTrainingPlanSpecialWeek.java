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

@Entity
@Table(name = "academic_training_plan_special_weeks")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AcademicTrainingPlanSpecialWeek {
    @Id
    private String id;
    private String planSubjectId;
    private String weekType;
    private int weekNumber;
    private String name;
    private String description;
    private Instant createdAt;
    private Instant updatedAt;
}
