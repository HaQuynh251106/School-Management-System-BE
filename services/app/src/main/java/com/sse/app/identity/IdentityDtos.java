package com.sse.app.identity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/** Gom các request DTO của phân hệ identity. */
public final class IdentityDtos {
    private IdentityDtos() {}

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    public record RefreshRequest(@NotBlank String refreshToken) {}
    public record LogoutRequest(String refreshToken) {}

    public record ForgotPasswordRequest(String email, String username) {}

    public record ResetPasswordRequest(@NotBlank String token, @NotBlank @Size(min = 8, max = 128) String newPassword) {}

    public record CreateUserRequest(
            String id,
            @NotBlank String username,
            @NotBlank @Size(min = 8, max = 128) String password,
            @NotBlank String fullName,
            @NotBlank @Pattern(regexp = "ADMIN|TEACHER|STUDENT|PARENT") String role,
            String email,
            String phone,
            String avatarUrl,
            String teacherCode,
            String mainSubject,
            String studentCode,
            String classId,
            String className,
            LocalDate dateOfBirth,
            String gender,
            String placeOfBirth,
            String ethnicity,
            String nationality,
            String address,
            LocalDate enrollmentDate,
            String guardianName,
            String guardianPhone
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
            String className,
            LocalDate dateOfBirth,
            String gender,
            String placeOfBirth,
            String ethnicity,
            String nationality,
            String address,
            LocalDate enrollmentDate,
            String guardianName,
            String guardianPhone
    ) {}

    public record AdminResetPasswordRequest(@Size(min = 8, max = 128) String newPassword) {}
}
