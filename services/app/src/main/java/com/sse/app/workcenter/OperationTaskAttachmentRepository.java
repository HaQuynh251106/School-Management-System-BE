package com.sse.app.workcenter;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface OperationTaskAttachmentRepository extends JpaRepository<OperationTaskAttachment, String> {
    List<OperationTaskAttachment> findByTaskIdOrderByUploadedAtDesc(String taskId);
    Optional<OperationTaskAttachment> findFirstByFileUrl(String fileUrl);
}
