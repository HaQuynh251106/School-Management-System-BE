# api-gateway

**Owner:** P1 (Tech Lead)
**Cổng:** 8080
**DB:** không có
**Vai trò:** Reverse proxy + xác thực JWT trung tâm + route tới các service nội bộ.

## Trách nhiệm

- Validate JWT (RS256, public key từ identity-service hoặc shared secret).
- Định tuyến request → service tương ứng (định nghĩa trong `route/`).
- Rate limiting cơ bản (Spring Cloud Gateway redis-rate-limiter).
- Global CORS, request ID injection, log truy cập.
- Trả 401/403 ngay tại gateway nếu token sai/hết hạn.

## Cấu trúc package

```
com.sse.gateway
├── GatewayApplication.java
├── config/         # GatewayConfig, CorsConfig, RateLimitConfig
├── route/          # RouteLocator beans (1 route → 1 service)
├── filter/         # JwtAuthFilter, RequestLoggingFilter, RequestIdFilter
├── security/       # JWT decoder, key loader
└── exception/      # GlobalErrorHandler
```

## Routing dự kiến

| Prefix | Service đích |
|---|---|
| `/auth/**`, `/users/**`, `/me/**` | identity-service |
| `/academic-years/**`, `/classes/**`, `/timetable/**`, `/attendance/**`, `/grades/**`, `/assignments/**`, `/extracurricular/**` | academic-service |
| `/fee-periods/**`, `/invoices/**`, `/payments/**` | finance-service |
| `/notifications/**`, `/announcements/**` | notification-service |
| `/files/**` | file-service |

## Phụ thuộc

- Cần JWT public key từ identity-service (lấy qua `/auth/.well-known/jwks.json` hoặc shared volume).
