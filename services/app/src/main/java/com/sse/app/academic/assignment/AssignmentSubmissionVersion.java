package com.sse.app.academic.assignment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "assignment_submission_versions",
        uniqueConstraints = @UniqueConstraint(name = "uk_submission_version",
                columnNames = {"submissionId", "versionNo"}),
        indexes = @Index(name = "idx_submission_version_submission",
                columnList = "submissionId"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AssignmentSubmissionVersion {
    @Id
    private String id;
    private String submissionId;
    private Integer versionNo;
    @Column(length = 4000)
    private String content;
    private String attachmentName;
    private String attachmentFileId;
    private String attachmentContentType;
    private Long attachmentSizeBytes;
    private String submittedBy;
    private Instant submittedAt;
}
