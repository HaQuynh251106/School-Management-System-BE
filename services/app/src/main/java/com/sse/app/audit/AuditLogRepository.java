package com.sse.app.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface AuditLogRepository extends JpaRepository<AuditLog, String> {
    List<AuditLog> findTop200ByOrderByCreatedAtDesc();
    List<AuditLog> findByModuleOrderByCreatedAtDesc(String module);
    List<AuditLog> findByActionOrderByCreatedAtDesc(String action);
}
