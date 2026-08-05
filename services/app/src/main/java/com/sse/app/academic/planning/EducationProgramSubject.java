package com.sse.app.academic.planning;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Column;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "education_program_subjects")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EducationProgramSubject {
    @Id private String id;
    private String programId;
    private String gradeLevel;
    private String subjectId;
    private String subjectType;
    private int annualPeriods;
    @Column(name = "semester1_periods")
    private int semester1Periods;
    @Column(name = "semester2_periods")
    private int semester2Periods;
    private int weeklyPeriods;
    private boolean required;
    private String notes;
}
