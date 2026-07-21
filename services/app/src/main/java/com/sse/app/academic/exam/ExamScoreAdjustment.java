package com.sse.app.academic.exam;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity @Table(name = "exam_score_adjustments")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ExamScoreAdjustment {
    @Id private String id;
    @Column(nullable = false) private String examPeriodId;
    @Column(nullable = false) private String resultId;
    private String reviewRequestId;
    private Double oldScore;
    private Double newScore;
    @Column(nullable = false, length = 2000) private String reason;
    @Column(nullable = false) private Instant adjustedAt;
    @Column(nullable = false) private String adjustedBy;
}
