package com.sse.app.academic.assignment;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** C4: Bài nộp của HS cho một assignment. */
@Entity
@Table(name = "assignment_submissions", indexes = {
        @Index(name = "idx_sub_asg", columnList = "assignmentId"),
        @Index(name = "idx_sub_student", columnList = "studentId")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AssignmentSubmission {
    @Id
    private String id;
    private String assignmentId;
    private String studentId;
    private String studentName;
    /** SUBMITTED | LATE | GRADED | RESUBMISSION_ALLOWED */
    private String status;
    @Builder.Default
    @Column(nullable = false)
    private boolean resubmissionAllowed = false;
    @Builder.Default
    @Column(nullable = false)
    private int attemptNumber = 1;
    @Column(length = 4000)
    private String content;
    private String attachmentFileId;
    private String attachmentName;
    private Instant submittedAt;
    private Double score;
    @Column(length = 2000)
    private String feedback;
    private String gradedBy;
    private Instant gradedAt;
}
