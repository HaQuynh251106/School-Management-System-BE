package com.sse.identity.dto.response;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * DTO trả về cho client.
 *
 * Tách khỏi entity để:
 *  - Không lộ field nhạy cảm (ví dụ password_hash của User).
 *  - Đổi shape API mà không phải đổi entity.
 */
@Data
public class RoleResponse {
    private Long id;
    private String code;
    private String name;
    private String description;
    private LocalDateTime createdAt;
}
