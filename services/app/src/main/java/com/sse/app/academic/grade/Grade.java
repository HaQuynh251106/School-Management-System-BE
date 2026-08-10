package com.sse.app.academic.grade;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** B4/C2: Một đầu điểm của HS theo môn/học kỳ/loại điểm. */
@Entity
@Table(name = "grades", indexes = {
        @Index(name = "idx_grade_student", columnList = "studentId"),
        @Index(name = "idx_grade_subject_sem", columnList = "subjectId,semesterId")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Grade {
    @Id
    private String id;
    private String studentId;
    private String subjectId;
    private String subjectName;
    private String semesterId;
    /** ORAL | 15M | MID | FINAL */
    private String category;
    private String categoryName;
    /** 1-based occurrence when a category requires multiple grade entries. */
    @JsonProperty("assessmentIndex")
    private int entryIndex;
    private Double score;
    private String note;
    private Instant recordedAt;
}
