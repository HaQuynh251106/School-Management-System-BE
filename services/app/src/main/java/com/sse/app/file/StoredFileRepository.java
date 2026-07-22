package com.sse.app.file;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StoredFileRepository extends JpaRepository<StoredFile, String> {
    @Query("select coalesce(sum(f.sizeBytes), 0) from StoredFile f where f.uploadedBy = :userId")
    long totalSizeByUploader(@Param("userId") String userId);
}
