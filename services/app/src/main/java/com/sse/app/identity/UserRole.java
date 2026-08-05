package com.sse.app.identity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "user_roles")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UserRole {
    @Id
    private String id;
    private String userId;
    private String roleId;
    private Instant assignedAt;
    private String assignedBy;
}
