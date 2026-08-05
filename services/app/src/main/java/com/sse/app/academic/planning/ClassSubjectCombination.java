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
@Table(name = "class_subject_combinations")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ClassSubjectCombination {
    @Id private String classId;
    private String combinationId;
    private Instant assignedAt;
    private String assignedBy;
}
