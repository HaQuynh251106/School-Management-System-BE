package com.sse.app.identity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

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

    /** Tài khoản tạm/import/reset phải đổi mật khẩu trước khi tiếp tục sử dụng. */
    @Builder.Default
    private boolean passwordChangeRequired = false;

    @Builder.Default
    private int tokenVersion = 0;

    // Teacher
    private String teacherCode;
    private String mainSubject;

    // Student
    private String studentCode;
    private String classId;
    private String className;
    private String cohortId;
    /** PENDING_PLACEMENT | ENROLLED | GRADUATED | TRANSFERRED | WITHDRAWN; separate from account status. */
    private String studentStatus;
    private Instant graduatedAt;
    private String graduationAcademicYearId;
    private String graduationClassId;
    private LocalDate dateOfBirth;
    private String gender;
    private String placeOfBirth;
    private String ethnicity;
    private String nationality;
    @Column(length = 500)
    private String address;
    private LocalDate enrollmentDate;
    private String guardianName;
    private String guardianPhone;

    private Instant createdAt;
}
