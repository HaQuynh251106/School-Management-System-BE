package com.sse.app.identity;

import jakarta.validation.constraints.NotBlank;

/** Gom các request DTO của phân hệ identity. */
public final class IdentityDtos {
    private IdentityDtos() {}

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    public record RefreshRequest(@NotBlank String refreshToken) {}

    public record ForgotPasswordRequest(String email, String username) {}

    public record ResetPasswordRequest(@NotBlank String token, @NotBlank String newPassword) {}

    public record CreateUserRequest(
            String id,
            @NotBlank String username,
            @NotBlank String password,
            @NotBlank String fullName,
            @NotBlank String role,
            String email,
            String phone,
            String avatarUrl,
            String teacherCode,
            String mainSubject,
            String studentCode,
            String classId,
            String className
    ) {}

    public record UpdateUserRequest(
            String fullName,
            String email,
            String phone,
            String avatarUrl,
            String teacherCode,
            String mainSubject,
            String studentCode,
            String classId,
            String className
    ) {}

    public record AdminResetPasswordRequest(String newPassword) {}
}
