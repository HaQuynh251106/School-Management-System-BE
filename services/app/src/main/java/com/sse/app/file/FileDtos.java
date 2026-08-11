package com.sse.app.file;

import java.time.Instant;

public final class FileDtos {
    private FileDtos() {}

    public record FileMetadata(
            String id,
            String originalName,
            String contentType,
            long sizeBytes,
            Instant createdAt) {
        public static FileMetadata from(StoredFile file) {
            return new FileMetadata(file.getId(), file.getOriginalName(), file.getContentType(),
                    file.getSizeBytes(), file.getCreatedAt());
        }
    }
}
