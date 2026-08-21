package com.sse.app.academic.planning;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "academic_assessment_plans")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AcademicAssessmentPlan {
    @Id private String id;
    private String planId;
    private String semesterId;
    private String classId;
    private String subjectId;
    private String assessmentType;
    private String name;
    private String assessmentForm;
    private String curriculumItemIds;
    private String resultMethod;
    private int weekNumber;
    private int durationMinutes;
    private String teacherId;
    @Transient private List<String> teacherIds;
    private String notes;
    private Instant createdAt;
    private Instant updatedAt;
}
