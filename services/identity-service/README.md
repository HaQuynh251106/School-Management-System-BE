# identity-service

**Owner:** P1 (Tech Lead)
**Cổng:** 8081
**DB:** `identity_db` (PostgreSQL — DDL §5.1 của plan)
**Phân hệ phụ trách:** A1, E1 + bổ sung S1, S2, S3, S13

## Trách nhiệm

- Quản lý `users`, `roles`, `permissions`, `role_permissions`.
- Profile mở rộng: `student_profiles`, `teacher_profiles`, `parent_profiles`.
- `parent_student_relations` (cầu nối cho Switch Profile của PH).
- Auth: Bcrypt password, JWT RS256 (15m access + 7d refresh), rotate refresh token.
- Forgot/Reset password (token 30m, gửi qua event tới Notification Service).
- Quản lý `user_devices` (FCM token) cho Push Notification.
- `login_history` (audit truy cập).
- Cung cấp API nội bộ: `/internal/parents-of/:studentId`, `/internal/devices/:userId` (cho Notification Service gọi).

## Cấu trúc package

```
com.sse.identity
├── IdentityServiceApplication.java
├── config/         # SecurityConfig, JwtConfig, RabbitConfig
├── security/       # JwtTokenProvider, BcryptPasswordEncoder, UserDetailsService
├── controller/     # AuthController, UserController, RoleController, MeController
├── service/        # AuthService, UserService, TokenService, PasswordResetService
├── repository/     # JPA: UserRepository, RoleRepository, RefreshTokenRepository, ...
├── entity/         # User, Role, Permission, RefreshToken, ...
├── dto/{request,response}/
├── mapper/         # MapStruct: User <-> UserDto
├── event/
│   ├── publisher/  # IdentityEventPublisher (login, password.reset_requested)
│   └── listener/   # (có thể null GĐ1)
└── exception/
```

## Endpoint chính (GĐ1)

| Method | Path | Mô tả |
|---|---|---|
| POST | /auth/login | Login → access + refresh |
| POST | /auth/refresh | Rotate refresh |
| POST | /auth/logout | Revoke refresh token |
| POST | /auth/forgot-password | Phát event reset |
| POST | /auth/reset-password | Đổi mật khẩu bằng token |
| GET | /me | Profile hiện tại |
| GET | /me/children | (cho PH) danh sách con |
| POST | /users/import | Import HS từ Excel (A1) |
| POST | /users/{id}/lock, /unlock, /reset-password | Admin actions |
| GET | /internal/parents-of/{studentId} | Service-to-service |

## Event publish

- `identity.user.login` (success/failed)
- `identity.password.reset_requested`
- `identity.user.created`
- `identity.user.locked` / `unlocked`

## Migration Flyway

Versioning: `V1__init.sql` ... — P1 sở hữu toàn bộ.
