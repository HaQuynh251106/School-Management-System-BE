package com.sse.app.identity;

import java.time.LocalDate;
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
        LocalDate dateOfBirth,
        String gender,
        String placeOfBirth,
        String ethnicity,
        String nationality,
        String address,
        LocalDate enrollmentDate,
        String guardianName,
        String guardianPhone,
        String teacherCode,
        String mainSubject,
        List<String> childrenIds
) {}
