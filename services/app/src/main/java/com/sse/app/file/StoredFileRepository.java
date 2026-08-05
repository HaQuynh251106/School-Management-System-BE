package com.sse.app.file;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StoredFileRepository extends JpaRepository<StoredFile, String> {
    Optional<StoredFile> findByFileKey(String fileKey);
}
