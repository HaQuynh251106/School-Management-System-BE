package com.sse.app.audit;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** A6: Lưu vết hệ thống (audit log). Bản giản lược dùng Postgres thay cho MongoDB. */
@Entity
@Table(name = "audit_logs", indexes = {
        @Index(name = "idx_audit_module", columnList = "module"),
        @Index(name = "idx_audit_actor", columnList = "actorId")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AuditLog {
    @Id
    private String id;
    private String actorId;
    private String actorName;
    private String role;
    private String action;       // LOGIN | CREATE | UPDATE | DELETE | PAYMENT | EXPORT | LOGIN_FAILED
    private String module;       // identity | academic | finance | notification | reports
    private String entityType;
    private String entityId;
    @Column(length = 1000)
    private String detail;
    private String ipAddress;

    @Column(length = 1000)
    private String userAgent;

    private String requestId;
    private Instant createdAt;
}
