package com.sse.app.academic.planning;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "subject_combination_subjects")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SubjectCombinationSubject {
    @Id private String id;
    private String combinationId;
    private String subjectId;
}
