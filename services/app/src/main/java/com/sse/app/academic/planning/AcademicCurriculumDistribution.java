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
@Table(name = "academic_curriculum_distributions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AcademicCurriculumDistribution {
    @Id private String id;
    private String planSubjectId;
    private String curriculumItemId;
    private int weekNumber;
    private String contentType;
    private String title;
    private int periods;
    private String notes;
    private Instant createdAt;
    private Instant updatedAt;
}
