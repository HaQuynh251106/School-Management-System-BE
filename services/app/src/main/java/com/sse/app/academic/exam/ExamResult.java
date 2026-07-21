package com.sse.app.academic.exam;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity @Table(name = "exam_results", uniqueConstraints = @UniqueConstraint(
        name = "uk_exam_result_student_subject", columnNames = {"examPeriodId", "studentId", "subjectId"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ExamResult {
    @Id private String id;
    @Column(nullable = false) private String examPeriodId;
    @Column(nullable = false) private String scheduleId;
    @Column(nullable = false) private String studentId;
    @Column(nullable = false) private String subjectId;
    private Double score;
    @Column(nullable = false) private String status;
    @Column(length = 1000) private String note;
    private Instant recordedAt;
    private String recordedBy;
    private Instant updatedAt;
    private String updatedBy;
    @Version @Builder.Default private Long version = 0L;
}
