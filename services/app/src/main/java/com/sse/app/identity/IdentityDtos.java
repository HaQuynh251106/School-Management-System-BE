package com.sse.app.identity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Gom các request DTO của phân hệ identity. */
public final class IdentityDtos {
    private IdentityDtos() {}

    public record LoginRequest(
            @NotBlank String username,
            @NotBlank String password,
            String deviceToken,
            String platform,
            String deviceName) {}

    public record RefreshRequest(@NotBlank String refreshToken) {}

    public record LogoutRequest(String refreshToken) {}

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
            String className,
            String status,
            java.time.LocalDate dateOfBirth,
            String gender,
            String placeOfBirth,
            String ethnicity,
            String nationality,
            String address,
            java.time.LocalDate enrollmentDate,
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
            java.time.LocalDate dateOfBirth,
            String gender,
            String placeOfBirth,
            String ethnicity,
            String nationality,
            String address,
            java.time.LocalDate enrollmentDate,
            String guardianName,
            String guardianPhone
    ) {}

    public record AdminResetPasswordRequest(
            String newPassword,
            @NotBlank @Size(min = 5, max = 500) String reason) {}

    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank String newPassword) {}

    public record DeleteUserRequest(
            @NotBlank @Size(min = 5, max = 500) String reason) {}

    public record RestoreUserRequest(
            String status,
            @NotBlank @Size(min = 5, max = 500) String reason) {}

    public record LinkChildRequest(
            @NotBlank String studentId,
            Boolean primaryContact) {}

    public record ReplaceChildrenRequest(
            @NotEmpty List<@NotBlank String> studentIds,
            Boolean primaryContact) {}

    public record PasswordResetResult(
            boolean ok,
            String temporaryPassword,
            boolean passwordChangeRequired,
            int revokedSessions) {}

    public record RegisterDeviceRequest(@NotBlank String deviceToken, @NotBlank String platform, String deviceName) {}

    public record SessionResponse(
            String id,
            String ipAddress,
            String userAgent,
            String deviceId,
            String deviceName,
            String platform,
            java.time.Instant createdAt,
            java.time.Instant lastSeenAt,
            java.time.Instant expiresAt,
            boolean active,
            boolean current) {}

    public record SessionRotation(User user, String deviceId) {}
}
