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
@Table(name = "academic_curriculum_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AcademicCurriculumItem {
    @Id
    private String id;
    private String planSubjectId;
    private String parentId;
    private String itemType;
    private String code;
    private String title;
    private int sequence;
    private int plannedPeriods;
    private String description;
    private Instant createdAt;
    private Instant updatedAt;
}
