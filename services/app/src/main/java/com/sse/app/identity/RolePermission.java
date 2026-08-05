package com.sse.app.identity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "role_permissions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class RolePermission {
    @Id
    private String id;
    private String roleId;
    private String permissionId;
    private Instant grantedAt;
    private String grantedBy;
}
