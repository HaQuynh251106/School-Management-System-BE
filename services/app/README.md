# SSE Backend — Modular Monolith (`services/app`)

Backend chạy được ngay cho **Smart School Ecosystem**, gói toàn bộ nghiệp vụ GĐ1 + phần lớn GĐ2 vào **một Spring Boot app duy nhất** (modular monolith). Các domain được tách thành package độc lập, có thể tách thành microservices sau:

```
com.sse.app
 ├─ security/        JWT (HS), filter xác thực, CurrentUser
 ├─ common/          ApiException, GlobalExceptionHandler, Ids, Health
 ├─ identity/        E1+A1: auth, users, parent-student, reset password
 ├─ academic
 │   ├─ structure/   A2: năm học, học kỳ, lớp, môn, phòng
 │   ├─ timetable/   A3/B2/C2: TKB + conflict resolution
 │   ├─ attendance/  B3/C3/D2: điểm danh + cảnh báo vắng cho PH
 │   ├─ grade/       B4/C2/A4: điểm + log sửa + loại điểm/hệ số
 │   └─ assignment/  B5/C4: bài tập + nộp + chấm
 ├─ finance/         A7/D4: đợt thu, hóa đơn, VietQR và đối soát
 ├─ notification/    E2/C5: thông báo in-app, announcement, template
 └─ seed/            Seed dữ liệu mẫu khớp mock-server
```

> Phục vụ **cùng cổng 4000** và đặt tên route trùng mock-server (`/auth/login`, `/me`, `/me/children`, `/users`, `/academicYears`, `/semesters`, `/classes`, `/subjects`, `/timetableSlots`, `/grades`, `/attendance`, `/announcements`) ⇒ là **drop-in thay thế** `mock-server` của Mobile/Web FE. Đổi `API_BASE_URL` của FE sang backend này là chạy.

## Yêu cầu
- JDK 17, Maven 3.9+
- PostgreSQL đang chạy + database `sse_db` (`createdb sse_db`)

## Chạy
```bash
# từ thư mục gốc repo
export JAVA_HOME=/opt/homebrew/opt/openjdk@17        # hoặc JDK 17 của bạn

# Cách 1 — dev (hot, không cần đóng gói)
mvn -f services/app/pom.xml spring-boot:run

# Cách 2 — fat jar
mvn -f services/app/pom.xml -DskipTests package
java -jar services/app/target/sse-app.jar
```
App lắng nghe tại `http://localhost:4000`. Kiểm tra: `curl localhost:4000/health`.

## Cấu hình (env, có default)
| Biến | Default |
|---|---|
| `SSE_DB_URL` | `jdbc:postgresql://localhost:5432/sse_db` |
| `SSE_DB_USER` | `a1234` |
| `SSE_DB_PASSWORD` | *(rỗng — trust auth local)* |
| `SSE_JWT_SECRET` | secret dev (đổi khi deploy) |
| `sse.seed.enabled` | `true` (seed nếu DB rỗng) |

## Tài khoản demo (seed sẵn)
| Vai trò | username | password |
|---|---|---|
| Admin | `admin` | `admin@123` |
| Teacher | `gv.hoa`, `gv.minh` | `teacher@123` |
| Student | `hs.an`, `hs.binh` | `student@123` |
| Parent | `ph.pham` (cha của hs.an + hs.binh) | `parent@123` |

```bash
curl -X POST localhost:4000/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin@123"}'
# -> { user, accessToken, refreshToken, expiresIn }
# Các API khác: header  Authorization: Bearer <accessToken>
```

## Danh mục API (theo Use Case)

**Auth / Identity (E1, A1)**
`POST /auth/login` · `/auth/refresh` · `/auth/logout` · `/auth/forgot-password` · `/auth/reset-password`
`GET /me` · `GET /me/children` (PH)
`GET/POST /users` · `GET/PUT /users/{id}` · `POST /users/{id}/lock|unlock|reset-password` (ADMIN)

**Cơ cấu đào tạo (A2)**
`GET/POST /academicYears` · `/semesters` · `/classes` · `/subjects` · `/rooms`
`GET /classes/{id}` · `GET /classes/{id}/students`

**Thời khóa biểu (A3/B2/C2)**
`GET/POST /timetableSlots` (POST kiểm tra trùng GV/lớp/phòng → 409) · `DELETE /timetableSlots/{id}` · `GET /me/timetable`

**Điểm danh (B3/C3/D2)**
`GET /attendance?studentId|classId|slotId|date` · `POST /attendance/bulk` → tự bắn cảnh báo vắng cho PH

**Điểm (B4/C2/A4)**
`GET /grades?studentId|subjectId|semesterId|category|classId` · `POST /grades/bulk` (upsert + ghi `grade_change_logs`)
`GET /grades/{id}/change-logs` · `GET/POST /exam-categories`

**Bài tập (B5/C4)**
`GET/POST /assignments` · `POST /assignments/{id}/publish` · `GET /assignments/{id}/submissions`
`POST /assignments/{id}/submit` (HS) · `POST /submissions/{id}/grade` (GV) · `GET /me/assignments` · `GET /me/submissions`

**Tài chính (A7/D4)**
`GET/POST /fee-periods` · `GET/POST /fee-periods/{id}/items` · `POST /fee-periods/{id}/open`
`POST /fee-periods/{id}/generate-invoices` · `GET /invoices` · `GET /invoices/{id}` · `POST /payments`
`POST /payments/{id}/submitted` · `GET /payments/vietqr/pending` · `POST /payments/{id}/confirm-vietqr|reject-vietqr`

**Thông báo (E2/C5)**
`GET /notifications?unread` · `GET /notifications/unread-count` · `POST /notifications/{id}/read` · `/notifications/read-all`
`GET/POST /announcements` · `GET/POST /notification-templates`

## RBAC & ràng buộc nghiệp vụ
- JWT bắt buộc cho mọi route trừ `/auth/**`, `/health`.
- HS chỉ xem dữ liệu của chính mình; PH chỉ xem con mình (`parent_student`) — sai quyền → **403**.
- Xếp TKB chống trùng lớp/GV/phòng theo (thứ × tiết) → **409**.
- Sửa điểm ghi `grade_change_logs` (ai sửa, cũ→mới, lý do); điểm 0..10.
- Điểm danh vắng/trễ → tạo `notifications` cho phụ huynh (đồng bộ).

## Đơn giản hóa so với kiến trúc đầy đủ (để chạy nhanh GĐ1)
- **1 PostgreSQL** + `ddl-auto=update` (Hibernate sinh schema) thay cho 4 DB + Flyway. DDL/Flyway có thể bổ sung sau (đã có thiết kế trong plan).
- **Thông báo đồng bộ** (gọi trực tiếp) thay cho RabbitMQ async; chưa tích hợp FCM/SendGrid thật (mới có in-app + log).
- **Thanh toán** dùng VietQR: hệ thống sinh mã chuyển khoản có nội dung định danh, phụ huynh báo đã chuyển và quản trị viên đối soát trước khi ghi nhận.
- **File đính kèm** mới lưu *metadata tên file* (chưa nối MinIO presigned upload).
- Mongo (audit/chat) chưa dùng.

## Chạy demo không cần PostgreSQL

Profile `demo` dùng H2, chạy Flyway và seed dữ liệu mẫu. Profile `local` dùng PostgreSQL thật từ `.env.local`.

```bash
mvn -pl services/app spring-boot:run -Dspring-boot.run.profiles=demo
```

## Chạy production

Production dùng Flyway (`db/migration`), PostgreSQL và `ddl-auto=validate`; không tự seed dữ liệu và không có secret mặc định.

```bash
cp .env.example .env
# thay toàn bộ password/secret/origin trong .env
docker compose up --build -d
```

Các điểm vận hành:

- Health: `/actuator/health`; metrics Prometheus: `/actuator/prometheus` (metrics yêu cầu JWT).
- OpenAPI JSON: `/v3/api-docs`; Swagger UI: `/swagger-ui.html`.
- File: `POST /files` multipart field `file`, tối đa 10 MB; `GET /files/{id}/content`.
- Refresh token được lưu dạng hash, xoay vòng và bị thu hồi khi logout.
- `SSE_PAYMENT_MODE=disabled` là mặc định production. Dùng `vietqr` cùng `SSE_VIETQR_BANK_ID`, `SSE_VIETQR_ACCOUNT_NO`, `SSE_VIETQR_ACCOUNT_NAME` để bật mã chuyển khoản.
- Bật email reset bằng `SSE_MAIL_ENABLED=true` và cấu hình SMTP trong `.env.example`.
- Dữ liệu upload và PostgreSQL được gắn persistent Docker volume.
