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
@Table(name = "academic_assessment_plan_teachers")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AcademicAssessmentPlanTeacher {
    @Id private String id;
    private String assessmentPlanId;
    private String teacherId;
    private boolean primaryTeacher;
    private Instant createdAt;
}
