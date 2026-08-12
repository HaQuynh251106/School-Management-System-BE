package com.sse.app.identity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Email;

import java.time.LocalDate;
import java.time.Instant;
import java.util.List;

/** Gom các request DTO của phân hệ identity. */
public final class IdentityDtos {
    private IdentityDtos() {}

    public record LoginRequest(@NotBlank String username, @NotBlank String password,
                               @Pattern(regexp = "^$|^[0-9]{6}$", message = "Mã xác thực phải gồm 6 chữ số")
                               String twoFactorCode) {}

    public record TwoFactorStatus(boolean enabled) {}
    public record TwoFactorSetup(String secret, String otpauthUri) {}
    public record TwoFactorCodeRequest(
            @NotBlank @Pattern(regexp = "^[0-9]{6}$", message = "Mã xác thực phải gồm 6 chữ số")
            String code) {}

    /** Refresh token may be supplied in the request body by mobile clients or by an HttpOnly cookie on web. */
    public record RefreshRequest(String refreshToken) {}
    public record LogoutRequest(String refreshToken) {}

    public record ForgotPasswordRequest(String email, String username) {}

    public record ResetPasswordRequest(@NotBlank String token, @NotBlank @Size(min = 10, max = 128) String newPassword) {}

    public record ActivateAccountRequest(@NotBlank String token,
                                         @NotBlank @Size(min = 10, max = 128) String newPassword) {}

    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = 10, max = 128) String newPassword) {}

    public record UpdateMyProfileRequest(
            @Email String email,
            @Size(max = 30) @Pattern(regexp = "^$|^[0-9+ .()\\-]{7,30}$", message = "Số điện thoại không hợp lệ") String phone,
            @Size(max = 1000) String avatarUrl,
            @Size(max = 1000) String address,
            @Size(max = 255) String guardianName,
            @Size(max = 30) String guardianPhone) {}

    public record CreateUserRequest(
            String id,
            String username,
            @NotBlank String fullName,
            @NotBlank @Pattern(regexp = "ADMIN|ACADEMIC_STAFF|ACCOUNTANT|TEACHER|STUDENT|PARENT") String role,
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

    public record BulkAccountActionRequest(
            @Size(min = 1, max = 500) List<String> userIds,
            @NotBlank @Pattern(regexp = "RESEND_ACTIVATION|SEND_PASSWORD_RESET|UNLOCK|LOCK|REQUIRE_PASSWORD_CHANGE") String action) {}

    public record BulkAccountActionError(String userId, String error) {}
    public record BulkAccountActionResult(int requested, int succeeded, int failed,
                                          List<BulkAccountActionError> errors) {}

    public record AccountLifecycleSummary(long total, long active, long locked,
                                          long pendingActivation, long requiresPasswordChange,
                                          long missingEmail) {}

    public record SessionView(String id, String device, String ipAddress,
                              Instant createdAt, Instant expiresAt, boolean current) {}

    public record LinkChildRequest(
            @NotBlank String studentId,
            Boolean primaryContact,
            Boolean confirmException,
            @Size(min = 10, max = 500) String reason) {}

    public record UpdateTeacherSpecializationRequest(
            @NotBlank @Size(max = 255) String mainSubject) {}

    public record ImportRowError(int row, String username, String error) {}
    public record ImportResult(int totalRows, int importedRows, int failedRows, List<ImportRowError> errors) {}

    public record ImportPreviewRow(
            int row,
            String username,
            String fullName,
            String role,
            String classCode,
            String mainSubject,
            String linkedUsername,
            boolean valid,
            String error
    ) {}

    public record ImportPreview(
            String token,
            String checksum,
            long expiresAt,
            int totalRows,
            int validRows,
            int invalidRows,
            List<ImportPreviewRow> rows
    ) {}
}
