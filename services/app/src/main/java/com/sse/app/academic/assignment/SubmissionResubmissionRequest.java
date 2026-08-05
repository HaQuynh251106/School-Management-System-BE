package com.sse.app.academic.assignment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "submission_resubmission_requests",
        indexes = @Index(name = "idx_resubmission_submission", columnList = "submissionId"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SubmissionResubmissionRequest {
    @Id
    private String id;
    private String submissionId;
    private String assignmentId;
    private String studentId;
    @Column(length = 1000)
    private String reason;
    /** OPEN | USED | CANCELLED | EXPIRED */
    private String status;
    private Instant allowedUntil;
    private String requestedBy;
    private Instant requestedAt;
    private Instant usedAt;
}
