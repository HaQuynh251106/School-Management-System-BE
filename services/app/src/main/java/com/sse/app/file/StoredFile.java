package com.sse.app.file;

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
@Table(name = "stored_files", indexes = {
        @Index(name = "idx_stored_file_key", columnList = "fileKey", unique = true),
        @Index(name = "idx_stored_file_uploader", columnList = "uploadedBy")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StoredFile {
    @Id
    private String id;

    @Column(nullable = false, unique = true, length = 700)
    private String fileKey;
    @Column(nullable = false, length = 24)
    private String scope;
    @Column(nullable = false, length = 255)
    private String originalName;
    @Column(nullable = false, length = 160)
    private String contentType;
    @Column(nullable = false)
    private long sizeBytes;
    @Column(nullable = false)
    private String uploadedBy;
    /** PENDING_UPLOAD | READY */
    @Column(nullable = false, length = 24)
    private String status;
    private Instant createdAt;
    private Instant completedAt;
}
