# common — Thư viện dùng chung

**Owner:** P1

1 module duy nhất, được tất cả service import.

## Cấu trúc

```
common/src/main/java/com/sse/common/
├── core/         # BaseEntity (id, createdAt, updatedAt), BusinessException, ApiResponse
├── security/     # JwtTokenValidator, @CurrentUser annotation
├── messaging/    # EventEnvelope<T>, RabbitMQ helper
└── web/          # GlobalExceptionHandler, RequestIdFilter, PageResponse
```

## Quy ước

- Chỉ P1 sửa code trong này. Người khác cần thêm utility → mở PR + P1 review.
- Mọi service thêm dependency `com.sse:common` trong pom riêng của service.
