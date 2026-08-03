package com.sse.app.academic.timetable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "curriculum_requirement_history")
@Getter @NoArgsConstructor @AllArgsConstructor @Builder
public class CurriculumRequirementHistory {
    @Id private String id;
    @Column(nullable = false) private String semesterId;
    @Column(nullable = false) private String gradeLevel;
    @Column(nullable = false) private String subjectId;
    @Column(nullable = false) private String subjectName;
    @Column(nullable = false) private String action;
    private Integer previousWeeklyPeriods;
    private Integer newWeeklyPeriods;
    private String actorId;
    @Column(nullable = false) private Instant createdAt;
}
