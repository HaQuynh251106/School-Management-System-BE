package com.sse.app.identity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** Tài khoản người dùng cho cả 4 vai trò (ADMIN/TEACHER/STUDENT/PARENT). */
@Entity
@Table(name = "users", indexes = {
        @Index(name = "idx_users_role", columnList = "role"),
        @Index(name = "idx_users_class", columnList = "classId")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class User {

    @Id
    private String id;

    @Column(unique = true, nullable = false)
    private String username;

    @Column(nullable = false)
    private String passwordHash;

    private String fullName;
    private String email;
    private String phone;

    /** ADMIN | TEACHER | STUDENT | PARENT */
    @Column(nullable = false)
    private String role;

    private String avatarUrl;

    /** ACTIVE | LOCKED */
    @Column(nullable = false)
    private String status;

    // Teacher
    private String teacherCode;
    private String mainSubject;

    // Student
    private String studentCode;
    private String classId;
    private String className;

    private Instant createdAt;
}
