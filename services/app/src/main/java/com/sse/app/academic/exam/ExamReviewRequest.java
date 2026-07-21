package com.sse.app.academic.exam;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity @Table(name = "exam_review_requests")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ExamReviewRequest {
    @Id private String id;
    @Column(nullable = false) private String examPeriodId;
    @Column(nullable = false) private String resultId;
    @Column(nullable = false) private String studentId;
    @Column(nullable = false) private String studentName;
    @Column(nullable = false) private String subjectId;
    @Column(nullable = false) private String subjectName;
    private Double originalScore;
    @Column(nullable = false, length = 2000) private String reason;
    @Column(nullable = false) private String status;
    @Column(length = 2000) private String resolution;
    private Double resolvedScore;
    @Column(nullable = false) private Instant requestedAt;
    @Column(nullable = false) private String requestedBy;
    private Instant resolvedAt;
    private String resolvedBy;
}
