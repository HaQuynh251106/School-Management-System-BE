package com.sse.app.academic.grade;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** S7/B4b: Lưu vết mọi lần sửa điểm. */
@Entity
@Table(name = "grade_change_logs", indexes = @Index(name = "idx_gcl_grade", columnList = "gradeId"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class GradeChangeLog {
    @Id
    private String id;
    private String gradeId;
    /** CREATE | UPDATE */
    private String action;
    private Double oldScore;
    private Double newScore;
    private String oldNote;
    private String newNote;
    private String changedBy;
    private String reason;
    private Instant changedAt;
}
