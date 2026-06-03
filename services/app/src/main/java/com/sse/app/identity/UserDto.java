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
        List<String> childrenIds
) {}
