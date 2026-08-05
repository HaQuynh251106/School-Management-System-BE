package com.sse.app.identity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "permissions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Permission {
    @Id
    private String id;
    private String code;
    private String module;
    private String name;
    private String description;
    private boolean active;
    private Instant createdAt;
}
