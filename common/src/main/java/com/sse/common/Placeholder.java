package com.sse.common;

/**
 * Placeholder để module common có ít nhất 1 file Java, đảm bảo Maven build pass.
 *
 * Khi P1 bắt đầu viết shared library, xóa file này và thêm các class thật:
 *   - core/BaseEntity.java         (id, createdAt, updatedAt cha của mọi entity)
 *   - core/ApiResponse.java        (wrapper response { success, data, message })
 *   - security/JwtTokenValidator.java
 *   - messaging/EventEnvelope.java
 *   - web/GlobalExceptionHandler.java
 */
public final class Placeholder {
    private Placeholder() {
        // utility class, không cho new
    }
}
