package com.sse.app.academic.timetable;

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
@Table(name = "class_lesson_progress")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ClassLessonProgress {
    @Id
    private String id;
    private String academicYearId;
    private String semesterId;
    private String classId;
    private String subjectId;
    private String curriculumItemId;
    private String sourcePlanId;
    private Integer sourcePlanVersion;
    private LocalDate lessonDate;
    private int plannedPeriods;
    private int completedPeriods;
    private String status;
    private String teacherId;
    private String notes;
    private Instant createdAt;
    private Instant updatedAt;
}
