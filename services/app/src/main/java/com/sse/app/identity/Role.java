package com.sse.app.identity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "roles")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Role {
    @Id
    private String id;
    private String code;
    private String name;
    private String description;
    private boolean systemRole;
    private boolean active;
    private Instant createdAt;
}
