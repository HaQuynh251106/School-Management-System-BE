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
@Table(name = "subject_combinations")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SubjectCombination {
    @Id private String id;
    private String code;
    private String name;
    private String academicYearId;
    private String gradeLevel;
    private int expectedClassCount;
    private int maxStudents;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
}
