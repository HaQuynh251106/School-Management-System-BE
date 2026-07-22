package com.sse.app.academic.assignment;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** Immutable history row for every assignment submission attempt. */
@Entity
@Table(name = "assignment_submission_attempts", uniqueConstraints = {
        @UniqueConstraint(name = "uk_submission_attempt", columnNames = {"submissionId", "attemptNumber"})
}, indexes = {
        @Index(name = "idx_attempt_submission", columnList = "submissionId,attemptNumber"),
        @Index(name = "idx_attempt_attachment", columnList = "attachmentFileId")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AssignmentSubmissionAttempt {
    @Id
    private String id;
    @Column(nullable = false)
    private String submissionId;
    @Column(nullable = false)
    private String assignmentId;
    @Column(nullable = false)
    private String studentId;
    @Column(nullable = false)
    private int attemptNumber;
    @Column(nullable = false)
    private String status;
    @Column(length = 4000)
    private String content;
    private String attachmentFileId;
    private String attachmentName;
    @Column(nullable = false)
    private Instant submittedAt;
    private Double score;
    @Column(length = 2000)
    private String feedback;
    private String gradedBy;
    private Instant gradedAt;
    @Column(nullable = false)
    private Instant updatedAt;
}
