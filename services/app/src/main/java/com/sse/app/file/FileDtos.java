package com.sse.app.file;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

public final class FileDtos {
    private FileDtos() {
    }

    public record PresignUploadRequest(
            @NotBlank String scope,
            @NotBlank String fileName,
            @NotBlank String contentType,
            @NotNull @Positive Long sizeBytes) {
    }

    public record PresignUploadResponse(
            String id,
            String fileKey,
            String uploadUrl,
            Instant expiresAt,
            String method) {
    }

    public record StoredFileResponse(
            String id,
            String fileKey,
            String scope,
            String originalName,
            String contentType,
            long sizeBytes,
            String status,
            Instant createdAt,
            Instant completedAt) {
    }

    public record PresignDownloadResponse(String downloadUrl, Instant expiresAt) {
    }
}
