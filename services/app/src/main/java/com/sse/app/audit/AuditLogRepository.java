package com.sse.app.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

interface AuditLogRepository extends JpaRepository<AuditLog, String> {
    List<AuditLog> findTop200ByOrderByCreatedAtDesc();
    List<AuditLog> findByModuleOrderByCreatedAtDesc(String module);
    List<AuditLog> findByActionOrderByCreatedAtDesc(String action);
    Page<AuditLog> findAllByOrderByCreatedAtDesc(Pageable pageable);
    Page<AuditLog> findByModuleOrderByCreatedAtDesc(String module, Pageable pageable);
    Page<AuditLog> findByActionOrderByCreatedAtDesc(String action, Pageable pageable);
    List<AuditLog> findTop1000ByOrderByCreatedAtDesc();
}
