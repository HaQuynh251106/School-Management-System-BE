package com.sse.identity.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Entity Role — map 1-1 với bảng `roles` trong DB.
 *
 * Quy ước:
 *  - Tên class là số ít (Role, không phải Roles).
 *  - @Entity báo cho JPA biết class này là 1 bảng.
 *  - @Table(name=...) khai báo tên bảng nếu khác tên class.
 *  - @Id + @GeneratedValue: PK auto-increment.
 *  - @Column(name=...) khai báo tên cột nếu khác tên field.
 *
 * Lombok:
 *  - @Getter @Setter: tự sinh getter/setter cho mọi field.
 *  - @NoArgsConstructor: JPA cần constructor không tham số.
 */
@Entity
@Table(name = "roles")
@Getter
@Setter
@NoArgsConstructor
public class Role {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
