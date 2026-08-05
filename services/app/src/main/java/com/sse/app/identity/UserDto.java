package com.sse.app.identity;

import java.util.List;

/** Hồ sơ user trả cho client (không kèm mật khẩu) — khớp UserModel của FE. */
public record UserDto(
        String id,
        String username,
        String fullName,
        String role,
        String status,
        String email,
        String phone,
        String avatarUrl,
        String studentCode,
        String className,
        String classId,
        String teacherCode,
        String mainSubject,
        List<String> childrenIds,
        boolean passwordChangeRequired,
        java.time.Instant passwordChangedAt,
        java.time.Instant deletedAt,
        String deleteReason,
        java.time.Instant restoredAt,
        List<String> permissions
) {
    public UserDto(
            String id, String username, String fullName, String role, String status,
            String email, String phone, String avatarUrl, String studentCode,
            String className, String classId, String teacherCode, String mainSubject,
            List<String> childrenIds) {
        this(id, username, fullName, role, status, email, phone, avatarUrl,
                studentCode, className, classId, teacherCode, mainSubject, childrenIds,
                false, null, null, null, null, List.of());
    }
}
