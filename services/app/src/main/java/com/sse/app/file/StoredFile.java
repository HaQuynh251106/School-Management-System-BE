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

import java.time.Instant;

@Entity
@Table(name = "stored_files", indexes = @Index(name = "idx_file_uploader", columnList = "uploadedBy"))
@Getter @Builder @NoArgsConstructor @AllArgsConstructor
public class StoredFile {
    @Id private String id;
    @Column(nullable = false) private String originalName;
    @Column(nullable = false, unique = true) private String storageName;
    private String contentType;
    private long sizeBytes;
    @Column(nullable = false) private String uploadedBy;
    @Column(nullable = false) private Instant createdAt;
}
