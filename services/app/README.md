# SSE Backend — Modular Monolith (`services/app`)

Backend chạy được ngay cho **Smart School Ecosystem**, gói toàn bộ nghiệp vụ GĐ1 + phần lớn GĐ2 vào **một Spring Boot app duy nhất** (modular monolith). Các domain được tách thành package độc lập, có thể tách thành microservices sau:

```
com.sse.app
 ├─ security/        JWT (HS), filter xác thực, CurrentUser
 ├─ common/          ApiException, GlobalExceptionHandler, Ids, Health
 ├─ identity/        E1+A1: auth, users, parent-student, reset password
 ├─ academic
 │   ├─ structure/   A2: năm học, học kỳ, lớp, môn, phòng, ngày nghỉ
 │   ├─ timetable/   A3/B2/C2: TKB + conflict resolution
 │   ├─ attendance/  B3/C3/D2: điểm danh + cảnh báo vắng cho PH
 │   ├─ grade/       B4/C2/A4: điểm + log sửa + loại điểm/hệ số
 │   └─ assignment/  B5/C4: bài tập + nộp + chấm
 ├─ finance/         A7/D4: đợt thu, hóa đơn, thanh toán (sandbox)
 ├─ notification/    E2/C5: thông báo in-app, announcement, template
 ├─ extracurricular/ A5/C6/D5: CLB ngoại khóa + đăng ký
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
| Admin (gồm cơ cấu, tài chính và đối soát) | `admin` | `admin@123` |
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
`GET/POST /academicYears` · `/semesters` · `/classes` · `/subjects` · `/rooms` · `/school-holidays`
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
`POST /fee-periods/{id}/generate-invoices` · `GET /invoices` · `GET /invoices/{id}` · `POST /payments` · `GET /payments?invoiceId`

**Báo cáo tài chính (P5)**
`GET /reports/finance` (lọc theo ngày, đợt thu, khối, lớp, học sinh, phương thức)
`GET /reports/finance/export?format=XLSX|PDF` (xuất file và ghi audit)

Kiểm thử P5 tự động sau khi backend chạy ở cổng `4000`:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\smoke-finance-p5.ps1 `
  -BaseUrl http://127.0.0.1:4000
```

**Thông báo (E2/C5)**
`GET /notifications?unread` · `GET /notifications/unread-count` · `POST /notifications/{id}/read` · `/notifications/read-all`
`GET/POST /announcements` · `GET/POST /notification-templates`

**Ngoại khóa (A5/C6/D5)**
`GET/POST /clubs` · `GET /clubs/{id}/registrations` · `POST /clubs/{id}/register` · `GET /me/club-registrations` · `POST /club-registrations/{id}/cancel`

## RBAC & ràng buộc nghiệp vụ
- JWT bắt buộc cho mọi route trừ `/auth/**`, `/health`.
- HS chỉ xem dữ liệu của chính mình; PH chỉ xem con mình (`parent_student`) — sai quyền → **403**.
- Xếp TKB chống trùng lớp/GV/phòng theo (thứ × tiết) → **409**.
- Sửa điểm ghi `grade_change_logs` (ai sửa, cũ→mới, lý do); điểm 0..10.
- Điểm danh vắng/trễ → tạo `notifications` cho phụ huynh (đồng bộ).

## Đơn giản hóa so với kiến trúc đầy đủ (để chạy nhanh GĐ1)
- **1 PostgreSQL** + `ddl-auto=update` (Hibernate sinh schema) thay cho 4 DB + Flyway. DDL/Flyway có thể bổ sung sau (đã có thiết kế trong plan).
- **Thông báo đồng bộ** (gọi trực tiếp) thay cho RabbitMQ async; chưa tích hợp FCM/SendGrid thật (mới có in-app + log).
- **Thanh toán sandbox tự `SUCCESS`** thay cho gọi cổng VNPAY/MoMo + verify HMAC.
- **File đính kèm** mới lưu *metadata tên file* (chưa nối MinIO presigned upload).
- Mongo (audit/chat) chưa dùng.
