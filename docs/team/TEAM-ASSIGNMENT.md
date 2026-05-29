# SSE Backend — Phân công 5 thành viên

> Đọc xong file này, mỗi người biết: mình sở hữu service nào, viết file nào, không được đụng cái gì, lệ thuộc vào ai. Có thể mở IDE code ngay.

**Nguyên tắc chống conflict:**
1. Mỗi service do **1 người sở hữu**. Riêng `academic-service` chia **theo file** giữa P2 và P3.
2. Cross-service giao tiếp qua **RabbitMQ event** (không gọi DB của nhau).
3. **Flyway version range** chia theo người để không trùng số.
4. **API contract first** — commit OpenAPI trước khi code.

---

## 1. Bảng phân công

| # | Vai trò | Service sở hữu | Phân hệ UC |
|---|---|---|---|
| **P1** | Tech Lead / Infra & Identity | `infrastructure/`, `common/`, `api-gateway`, `identity-service` | A1, E1 |
| **P2** | Academic Core | `academic-service` (file của P2 — xem §3.2) | A2, A3, B1, B2, B3, C2(TKB), C3 |
| **P3** | Academic Advanced | `academic-service` (file của P3 — xem §3.3) | A4, A5, B4, B5, C2(điểm), C4, C6, D5 |
| **P4** | Finance & File | `finance-service`, `file-service` | A7, A8(tài chính), D4 |
| **P5** | Notification & Audit & Reports | `notification-service` (+ Mongo audit/chat) | E2, A6, A8(học thuật), B6, C5, D2(noti), D3 |

> 1 sprint = 2 tuần. GĐ1 = S1→S6 (~3 tháng), GĐ2 = S7→S12 (~3 tháng).

---

## 2. Sơ đồ phụ thuộc (ai chặn ai)

```
S1-S2  : P1 → infra + common + identity
              │
              ▼  có JWT, mọi người mới authen được
S3     : P2 → academic structure (years, classes, enrollments)
              │
              ▼  có class_id/subject_id, P3 mới nhập điểm được
S4     : P2 → timetable + attendance
S5     : P3 → grading
S7     : P4 → file-service ──┐
              P3 → assignment─┘ (P3 gọi file-service upload)
S8     : P5 → notification (consume mọi event)
S9-S10 : P4 → finance + VNPAY
S11    : P3 → extracurricular ──► P4 sinh invoice tự động
S11    : P3 → year finalize
S12    : P5 → chat + bulk noti stress test
```

**Critical path:** **P1 (S1+S2) → P2 (S3) → mọi người mới có data test**.

---

## 3. Chi tiết từng người

### 3.1 P1 — Tech Lead, Infrastructure & Identity Service

**Service sở hữu:** `api-gateway`, `identity-service`, `common`, `infrastructure/*`, `pom.xml` parent, CI.

**Layout package (identity-service):**
```
com.sse.identity
├── IdentityServiceApplication.java
├── config/         # SecurityConfig, JwtConfig, RabbitConfig
├── security/       # JwtTokenProvider, UserDetailsServiceImpl
├── controller/     # AuthController, UserController, MeController
├── service/        # AuthService, UserService, TokenService, ...
├── repository/     # UserRepository, RoleRepository, RefreshTokenRepository, ...
├── entity/         # User, Role, Permission, RefreshToken, StudentProfile, ...
├── dto/{request,response}/
├── mapper/         # UserMapper (MapStruct)
├── event/publisher/  # IdentityEventPublisher
├── exception/
└── util/
```

**Bảng DB sở hữu (`identity_db` — DDL §5.1 plan):**
`roles`, `permissions`, `role_permissions`, `users`, `user_roles`, `student_profiles`, `teacher_profiles`, `parent_profiles`, `parent_student_relations`, `refresh_tokens`, `password_reset_tokens`, `login_history`, `user_devices`.

**Flyway:** `V1__init.sql` ... toàn bộ.

**Roadmap:**

| Sprint | Việc |
|---|---|
| **S1** | Parent pom, `docker-compose.dev.yml`, module `common`, CI GitHub Actions, skeleton 6 service (chỉ Application + application.yml). |
| **S2** | Identity đầy đủ: login/refresh/logout, JWT RS256, Bcrypt, forgot/reset password (publish event), `/me`, `/me/children`, admin endpoints. API Gateway route + JWT validate. |
| **S3** | Import HS từ Excel (`POST /users/import`). Internal endpoint `/internal/parents-of/{studentId}` cho P5. |
| **S6** | Security hardening, OWASP top 10. |

**Endpoint chính:**
```
POST /auth/login | /refresh | /logout | /forgot-password | /reset-password
GET  /me | /me/children
POST /me/devices                       (FCM)
POST /users | /users/import            (admin)
PATCH /users/{id}/lock | /unlock
GET  /internal/parents-of/{studentId}
GET  /internal/devices/{userId}
```

**Event publish:** `identity.user.login`, `identity.user.created`, `identity.user.locked`, `identity.password.reset_requested`.

**Ai phụ thuộc P1:** TẤT CẢ (cần JWT).

---

### 3.2 P2 — Academic Core (Cấu trúc + TKB + Điểm danh)

**Service sở hữu:** `academic-service` (một phần — theo file).

**File được sửa (whitelist theo tên):**

| Loại | File P2 sở hữu |
|---|---|
| Entity | `AcademicYear`, `Semester`, `GradeLevel`, `Subject`, `Room`, `SchoolHoliday`, `Class`, `ClassEnrollment`, `TeacherClassSubject`, `TimetableSlot`, `AttendanceRecord` |
| Repository | `*Repository` cho 11 entity trên |
| Service | `*Service` cho 11 entity trên |
| Controller | `*Controller` tương ứng |
| Mapper | `*Mapper` tương ứng |
| DTO | `*Request` / `*Response` tương ứng |
| Event publisher | `TimetableEventPublisher`, `AttendanceEventPublisher` |

**Skeleton tạo ở S3 (P2 tạo, P3 KHÔNG sửa):**
- `AcademicServiceApplication.java`
- `config/JpaConfig.java`, `config/RabbitConfig.java`, `config/SecurityConfig.java`
- `exception/GlobalExceptionHandler.java`

**Flyway:** `V100__` → `V199__` trong `services/academic-service/src/main/resources/db/migration/`.

Ví dụ:
- `V100__create_academic_years.sql`
- `V101__create_semesters.sql`
- `V110__create_classes.sql`
- `V120__create_timetable_slots.sql`
- `V130__create_attendance_records.sql`

**Roadmap:**

| Sprint | Việc |
|---|---|
| **S3** | Tạo skeleton service. Migration V100–V120 (cấu trúc đến `class_enrollments`). CRUD năm học/HK/khối/môn/phòng/lớp. Gán GVCN. Bulk import HS vào lớp. |
| **S4** | Migration V130–V150. `POST /timetable/slots` + conflict check (UNIQUE + holiday check). `POST /attendance/bulk`. Publish event vắng mặt. View GV xem "lớp tôi dạy". View HS xem TKB + chuyên cần. |
| **S6** | Tối ưu index, test ≥ 70%. |

**Endpoint chính:**
```
POST   /academic-years | /semesters | /grade-levels | /subjects | /rooms | /classes
POST   /classes/{id}/enrollments/bulk     (import HS)
POST   /teacher-class-subjects             (phân công GV dạy môn)
GET    /teachers/{id}/my-classes           (B1)
GET    /teachers/{id}/timetable            (B2)
POST   /timetable/slots                    (A3, conflict check)
GET    /classes/{id}/timetable
POST   /attendance/bulk                    (B3)
GET    /attendance/today?classId&slotId
GET    /students/{id}/attendance           (C3)
POST   /school-holidays
```

**Event publish:** `academic.timetable.changed`, `academic.attendance.absent`, `academic.attendance.recorded`.

**Acceptance:**
- Conflict check trả 409 cho (lớp+slot trùng), (GV bận), (phòng đang dùng).
- Holiday rơi vào slot → cảnh báo, admin có thể override.
- `/attendance/bulk` với 45 HS < 300ms.

---

### 3.3 P3 — Academic Advanced (Điểm + Bài tập + Ngoại khóa + Lên lớp)

**Service sở hữu:** `academic-service` (một phần — theo file).

**File được sửa (whitelist theo tên):**

| Loại | File P3 sở hữu |
|---|---|
| Entity | `ExamCategory`, `SubjectScoreConfig`, `Grade`, `GradeChangeLog`, `Assignment`, `AssignmentAttachment`, `AssignmentSubmission`, `SubmissionAttachment`, `ExtracurricularCourse`, `ExtracurricularEnrollment`, `StudentYearlySummary` |
| Repository / Service / Controller / Mapper / DTO | `*` tương ứng |
| Event publisher | `GradeEventPublisher`, `AssignmentEventPublisher`, `ExtracurricularEventPublisher`, `YearlySummaryEventPublisher` |

**Flyway:** `V200__` → `V299__`.

Ví dụ:
- `V200__create_exam_categories.sql`
- `V210__create_grades.sql`
- `V220__create_assignments.sql`
- `V230__create_extracurricular_courses.sql`
- `V240__create_student_yearly_summaries.sql`

**Roadmap:**

| Sprint | Việc |
|---|---|
| **S5** | Migration V200–V220. CRUD exam_categories, subject_score_configs. `POST /grades/bulk`, `PATCH /grades/{id}` (auto-log change). Phổ điểm. View HS xem điểm. |
| **S7** | Migration V220–V240. Assignment CRUD (DRAFT/PUBLISHED + deadline + attachment metadata). Submissions (HS upload qua file-service). GV chấm bài. |
| **S11** | Migration V250–V280. Extracurricular CRUD + enroll (publish event để P4 sinh invoice). Year finalize + promotion. |

**Endpoint chính:**
```
POST   /exam-categories | /subject-score-configs
POST   /grades/bulk
PATCH  /grades/{id}                        (auto-log change)
GET    /classes/{id}/grade-distribution
GET    /students/{id}/grades
POST   /assignments
PATCH  /assignments/{id}/publish
POST   /assignments/{id}/submissions       (HS nộp)
PATCH  /submissions/{id}/grade             (GV chấm)
POST   /extracurricular/courses | /courses/{id}/enroll
POST   /academic-years/{id}/finalize
GET    /students/{id}/yearly-summaries
```

**Event publish:** `academic.grade.published`, `.changed`, `academic.assignment.published`, `academic.submission.graded`, `academic.extracurricular.enrolled`, `academic.year.finalized`.

**Acceptance:**
- Sửa điểm → 1 row mới trong `grade_change_logs` (old/new/by/reason).
- Validate `0 ≤ score ≤ 10`.
- Finalize 1000 HS < 60s.
- Submission sau deadline → reject (trừ khi `allow_late=true`).

---

### 3.4 P4 — Finance & File Service

**Service sở hữu:** `finance-service` (toàn bộ) + `file-service` (toàn bộ).

**Layout package (finance-service):**
```
com.sse.finance
├── FinanceServiceApplication.java
├── config/
├── controller/     # FeePeriodController, InvoiceController, PaymentController, RefundController
├── service/        # InvoiceService, PaymentService, ...
├── repository/
├── entity/         # FeeCategory, FeePeriod, FeePeriodItem, Invoice, InvoiceItem, Payment, ...
├── dto/{request,response}/
├── mapper/
├── event/{publisher,listener}/
├── gateway/        # vnpay/ + momo/ (chỉ là helper, không phải sub-domain)
│   ├── vnpay/
│   └── momo/
├── exception/
└── util/
```

**Layout package (file-service):**
```
com.sse.file
├── FileServiceApplication.java
├── config/         # MinioConfig
├── controller/     # FileController
├── service/        # FileService
├── repository/     # FileRecordRepository (nếu cần)
├── entity/         # FileRecord
├── dto/{request,response}/
├── minio/          # MinioClientWrapper, BucketInitializer
└── exception/
```

**Bảng DB sở hữu (`finance_db` — DDL §5.3):**
`fee_categories`, `fee_periods`, `fee_period_items`, `invoices`, `invoice_items`, `payments`, `payment_gateway_transactions`, `refunds`.

**Flyway:** `V1__` → `V99__` (finance riêng, file riêng).

**Roadmap:**

| Sprint | Việc |
|---|---|
| **S7** | File Service: MinIO bucket setup, presigned URL upload/download. Coordinate với P3 về `file_key`. |
| **S9** | Finance migration toàn bộ. CRUD fee_categories, fee_periods, items. `POST /fee-periods/{id}/generate-invoices` sinh bulk. Publish `finance.invoice.issued`. |
| **S10** | VNPAY + MoMo sandbox: build request HMAC, callback verify, idempotent. Refund flow. Publish `finance.invoice.paid`, `finance.payment.failed`. |
| **S11** | Consume `academic.extracurricular.enrolled` → tự sinh invoice. Report doanh thu (A8). |

**Endpoint chính (finance):**
```
POST   /fee-categories | /fee-periods | /fee-periods/{id}/items | /fee-periods/{id}/generate-invoices
GET    /invoices?studentId&status | /invoices/{id}
POST   /payments
GET    /payments/vnpay/callback             (verify HMAC)
GET    /payments/momo/callback
POST   /refunds
GET    /reports/revenue?from&to             (A8)
```

**Endpoint chính (file-service):**
```
POST   /files/presigned-upload
POST   /files/{fileKey}/confirm
GET    /files/{fileKey}/presigned-download
DELETE /files/{fileKey}
```

**Event publish:** `finance.invoice.issued`, `finance.invoice.paid`, `finance.payment.failed`, `finance.refund.completed`.

**Event consume:** `academic.extracurricular.enrolled` (S11).

**Acceptance:**
- VNPAY sandbox: thanh toán đúng → `invoices.status=PAID`, có log `payment_gateway_transactions`.
- Chữ ký sai → `signature_valid=false`, không đổi trạng thái.
- Idempotent: replay callback 3 lần chỉ update 1 lần.
- Upload 50MB qua presigned, không qua backend.

---

### 3.5 P5 — Notification, Audit & Reports

**Service sở hữu:** `notification-service` (toàn bộ) + MongoDB collections (`audit_logs`, `chat_threads`, `chat_messages`, `in_app_notifications`).

**Layout package (notification-service):**
```
com.sse.notification
├── NotificationServiceApplication.java
├── config/         # RabbitConfig, MongoConfig, FcmConfig, EmailConfig, WebSocketConfig
├── controller/     # NotificationController, PreferenceController, ChatController, AuditController
├── service/
├── repository/     # JPA + MongoRepository
├── entity/         # NotificationTemplate, Notification, NotificationDeliveryLog, UserNotificationPreference + Mongo documents
├── dto/{request,response}/
├── mapper/
├── template/       # HandlebarsRenderer, TemplateLoader
├── channel/        # push/, email/, inapp/ — chỉ là helper (mỗi cái 1 class hoặc 1 thư mục nhỏ)
│   ├── push/
│   ├── email/
│   └── inapp/
├── consumer/       # @RabbitListener cho từng event
├── audit/          # AuditLogConsumer + Mongo writer
├── exception/
└── util/
```

**Bảng DB sở hữu (`notification_db` — DDL §5.4):**
`notification_templates`, `notifications`, `notification_delivery_logs`, `user_notification_preferences`.

**Mongo collections:** `audit_logs`, `chat_threads`, `chat_messages`, `in_app_notifications`.

**Flyway:** `V1__` → `V99__`.

**Roadmap:**

| Sprint | Việc |
|---|---|
| **S2** | Thiết kế RabbitMQ topology (`infrastructure/docker/rabbitmq/definitions.json`). |
| **S6** | Skeleton + migration §5.4 + seed 4 template. Consumer lắng nghe event đầu tiên, log console. |
| **S8** | 3 channel đầy đủ (FCM/Email/InApp + WebSocket). Render template Handlebars. Preferences. Retry exponential. Delivery log. |
| **S11** | Audit Mongo consumer (subscribe `#`). Endpoint `/audit-logs` viewer. Reports academic A8 (Apache POI/iText). |
| **S12** | Chat 1-1 + class broadcast (Mongo + WebSocket STOMP). Stress test 10K broadcast. |

**Endpoint chính:**
```
GET    /notifications/me?unread=true
PATCH  /notifications/{id}/read
GET/PUT /me/notification-preferences
POST   /announcements/broadcast            (Admin)
GET    /chats/threads | /chats/threads/{id}/messages
POST   /chats/threads/{id}/messages
WS     /ws/chat                            (STOMP)
GET    /audit-logs?actor&action&date       (Admin)
GET    /reports/grade-distribution | /attendance-rate
```

**Event consume:** TẤT CẢ (`academic.*`, `finance.*`, `identity.*`) → noti theo template + ghi audit.

**Acceptance:**
- E2E vắng mặt: GV mark → 30s sau PH có push + email.
- Stress: 10K event → drain ≤ 60s với 10 worker.
- Retry: tắt SMTP → 3 lần fail → log status=FAILED.

---

## 4. Quy tắc chống conflict

### 4.1 Whitelist file (TUYỆT ĐỐI)

| Đường dẫn | Ai được sửa |
|---|---|
| `pom.xml` parent, `docker-compose.dev.yml`, `infrastructure/**`, `common/**`, `.github/**`, `scripts/**` | P1 |
| `services/api-gateway/**`, `services/identity-service/**` | P1 |
| `services/academic-service/**` file P2 sở hữu (xem §3.2) + migration `V1xx__*.sql` | P2 |
| `services/academic-service/**` file P3 sở hữu (xem §3.3) + migration `V2xx__*.sql` | P3 |
| `services/academic-service/{AcademicServiceApplication.java, config/, exception/, pom.xml}` | P2 tạo, P3 không sửa; thay đổi cần cả 2 approve |
| `services/finance-service/**`, `services/file-service/**` | P4 |
| `services/notification-service/**` | P5 |
| `docs/architecture/event-catalog.md` | Ai thêm event MỚI phải cập nhật; P5 review |

### 4.2 Git workflow

```
main (protected)
└── develop (protected)
    └── feature/p<N>/<short-desc>     ví dụ: feature/p2/timetable-conflict
```

- PR target = `develop`.
- Trước push: `git fetch && git rebase origin/develop && mvn -pl services/<your> verify`.
- Squash merge + Conventional Commits (`feat(identity): ...`, `fix(academic): ...`).
- Reviewer ≥ 1; PR đụng `common/` → P1 bắt buộc review.

Chi tiết: [docs/architecture/git-workflow.md](../architecture/git-workflow.md).

### 4.3 Flyway version range

- `identity-service`: V1–V99 (P1)
- `academic-service`: V100–V199 (P2), V200–V299 (P3), V900+ (P1 seed)
- `finance-service`: V1–V99 (P4)
- `notification-service`: V1–V99 (P5)
- `file-service`: V1–V99 (P4)

### 4.4 API + Event contract first

- Endpoint mới → commit OpenAPI vào `docs/openapi/<service>.yaml` trước khi code.
- Event mới → cập nhật `docs/architecture/event-catalog.md` trước khi publish.

### 4.5 Idempotency

Mọi consumer event PHẢI idempotent: check `event.id` trong Mongo `processed_events` để dedup.

### 4.6 Cấm tuyệt đối

- ❌ Query DB của service khác.
- ❌ FK cross-DB.
- ❌ Commit secret.
- ❌ `git push -f` lên `develop`/`main`.
- ❌ Sửa file ngoài whitelist.

---

## 5. Tuần đầu — mọi người làm gì

### Ngày 1

| Người | Việc |
|---|---|
| P1 | Clone repo, `docker compose up -d`, verify hạ tầng. Skeleton `common`. |
| P2-P5 | Đọc plan + flowchart + README service của mình. Cài JDK 17, Maven 3.9+, IDE. |

### Ngày 2-3

| Người | Việc |
|---|---|
| P1 | Hoàn thiện `common`. Skeleton 6 service. CI workflow build. |
| P2 | OpenAPI cho structure. ERD `academic_db` phần 1. |
| P3 | OpenAPI cho grading + assignment. ERD phần 2. |
| P4 | OpenAPI finance + file. Nghiên cứu VNPAY spec + MinIO SDK. |
| P5 | RabbitMQ topology + `definitions.json`. Schema MongoDB. |

### Ngày 4-5

| Người | Việc |
|---|---|
| P1 | Identity Service: roles, users, bcrypt, login. |
| Mọi người | Review OpenAPI của nhau (đảm bảo naming consistent). |

**Cuối tuần 1:** demo nội bộ — `docker compose up` xanh, P1 demo `POST /auth/login` trả JWT, các service khác có skeleton chạy được.

---

## 6. Định nghĩa "Done" của PR

- [ ] Code compile, test pass local
- [ ] CI xanh (build + test + lint)
- [ ] OpenAPI commit kèm (nếu thêm endpoint)
- [ ] Migration Flyway đúng range version
- [ ] Không sửa file ngoài whitelist
- [ ] ≥ 1 reviewer approve
- [ ] Squash merge

---

## 7. Mở IDE và bắt đầu

```bash
git clone <repo-url> sse-backend
cd sse-backend
git checkout develop && git pull
git checkout -b feature/p<N>/<task-name>

docker compose -f docker-compose.dev.yml up -d

# IntelliJ → File → Open → chọn pom.xml ở root → "Open as Project"
# Right-click *Application.java → Run

git commit -m "feat(identity): implement refresh token rotation"
git push -u origin feature/p1/refresh-token
# PR target: develop
```

---

## 8. Ma trận sở hữu — Bảng Database

> Tham chiếu đầy đủ DDL ở plan §5. Tổng cộng **40 bảng SQL + 4 collection Mongo**.

### 8.1 `identity_db` — P1 (13 bảng)

| # | Bảng | Owner | Mục đích |
|---|---|---|---|
| 1 | `roles` | P1 | 4 vai trò gốc (ADMIN/TEACHER/STUDENT/PARENT) |
| 2 | `permissions` | P1 | Danh sách quyền (USER_CREATE, GRADE_EDIT, …) |
| 3 | `role_permissions` | P1 | Map role ↔ permission |
| 4 | `users` | P1 | Tài khoản gốc — username, email, password_hash, status |
| 5 | `user_roles` | P1 | Map user ↔ role (n-n) |
| 6 | `student_profiles` | P1 | Hồ sơ HS (student_code, enrollment_date) |
| 7 | `teacher_profiles` | P1 | Hồ sơ GV (teacher_code, degree, main_subject) |
| 8 | `parent_profiles` | P1 | Hồ sơ PH (occupation, workplace) |
| 9 | `parent_student_relations` ★ | P1 | Cầu nối PH ↔ HS (cốt lõi cho D1 Switch Profile) |
| 10 | `refresh_tokens` | P1 | JWT refresh token (rotate, revoke) |
| 11 | `password_reset_tokens` | P1 | Token reset password (TTL 30m) |
| 12 | `login_history` | P1 | Audit truy cập (success/failed + IP/UA) |
| 13 | `user_devices` | P1 | FCM token cho Push Notification |

### 8.2 `academic_db` — P2 + P3 (21 bảng)

#### P2 sở hữu (11 bảng)

| # | Bảng | Owner | Mục đích |
|---|---|---|---|
| 14 | `academic_years` | P2 | Năm học (2025-2026) |
| 15 | `semesters` | P2 | Học kỳ HK1/HK2 |
| 16 | `school_holidays` | P2 | Ngày nghỉ — chống xếp TKB |
| 17 | `grade_levels` | P2 | Khối lớp (K10, K11, K12) |
| 18 | `subjects` | P2 | Môn học |
| 19 | `rooms` | P2 | Phòng học |
| 20 | `classes` | P2 | Lớp 10A1, 10A2, … |
| 21 | `class_enrollments` | P2 | HS thuộc lớp nào |
| 22 | `teacher_class_subjects` | P2 | Phân công GV dạy môn ở lớp |
| 23 | `timetable_slots` | P2 | Thời khóa biểu (UNIQUE chống conflict) |
| 24 | `attendance_records` | P2 | Sổ điểm danh per period |

#### P3 sở hữu (10 bảng)

| # | Bảng | Owner | Mục đích |
|---|---|---|---|
| 25 | `exam_categories` | P3 | Loại điểm (miệng/15p/GK/CK) + trọng số |
| 26 | `subject_score_configs` | P3 | Hệ số môn học |
| 27 | `grades` | P3 | Điểm số |
| 28 | `grade_change_logs` | P3 | Log sửa điểm (old/new/by/reason) |
| 29 | `assignments` | P3 | Bài tập |
| 30 | `assignment_attachments` | P3 | File đính kèm bài tập (metadata) |
| 31 | `assignment_submissions` | P3 | HS nộp bài |
| 32 | `submission_attachments` | P3 | File nộp bài (metadata) |
| 33 | `extracurricular_courses` | P3 | Khóa ngoại khóa |
| 34 | `extracurricular_enrollments` | P3 | Đăng ký ngoại khóa |
| 35 | `student_yearly_summaries` | P3 | Tổng kết năm + lên lớp/lưu ban |

### 8.3 `finance_db` — P4 (8 bảng)

| # | Bảng | Owner | Mục đích |
|---|---|---|---|
| 36 | `fee_categories` | P4 | Loại phí (học phí/bảo hiểm/ăn trưa) |
| 37 | `fee_periods` | P4 | Đợt thu |
| 38 | `fee_period_items` | P4 | Định mức phí theo khối/HS |
| 39 | `invoices` | P4 | Hóa đơn của HS |
| 40 | `invoice_items` | P4 | Dòng chi tiết hóa đơn (S9) |
| 41 | `payments` | P4 | Thanh toán |
| 42 | `payment_gateway_transactions` | P4 | Log raw callback VNPAY/MoMo (S8) |
| 43 | `refunds` | P4 | Hoàn tiền |

### 8.4 `notification_db` — P5 (4 bảng)

| # | Bảng | Owner | Mục đích |
|---|---|---|---|
| 44 | `notification_templates` | P5 | Mẫu thông báo Handlebars |
| 45 | `notifications` | P5 | Bản ghi gốc mỗi noti gửi đi |
| 46 | `notification_delivery_logs` | P5 | Log retry + provider response |
| 47 | `user_notification_preferences` | P5 | Cấu hình kênh per user per category |

### 8.5 MongoDB collections — P5 (4 collection)

| # | Collection | Owner | Mục đích |
|---|---|---|---|
| M1 | `audit_logs` | P5 | A6 — log mọi action CRUD nhạy cảm |
| M2 | `chat_threads` | P5 | Hội thoại 1-1 / broadcast lớp |
| M3 | `chat_messages` | P5 | Tin nhắn chat |
| M4 | `in_app_notifications` | P5 | Noti hiển thị trong app |

---

## 9. Ma trận sở hữu — Endpoints

> Tổng kết toàn bộ endpoint REST + WebSocket. Path đã có prefix `/api/v1` ngầm định, lược cho gọn.

### 9.1 P1 — `identity-service` (24 endpoint) + `api-gateway`

| Method | Path | UC | Mô tả |
|---|---|---|---|
| POST | /auth/login | E1 | Login → access JWT 15m + refresh 7d |
| POST | /auth/refresh | E1 | Rotate refresh token |
| POST | /auth/logout | E1 | Revoke refresh token |
| POST | /auth/forgot-password | A1 | Phát event reset (Notification consume) |
| POST | /auth/reset-password | A1 | Đổi mật khẩu bằng token |
| GET | /me | E1 | Profile hiện tại |
| PATCH | /me | C1 | Cập nhật profile (avatar, sđt, …) |
| GET | /me/children | D1 | Danh sách con (PH only) |
| POST | /me/devices | C5,D2 | Đăng ký FCM token |
| DELETE | /me/devices/{id} | C5,D2 | Bỏ đăng ký device |
| POST | /users | A1 | Tạo user (admin) |
| GET | /users?role&status | A1 | List user (admin) |
| GET | /users/{id} | A1 | Chi tiết user |
| PATCH | /users/{id} | A1 | Sửa user (admin) |
| POST | /users/import | A1 | Import HS từ Excel |
| PATCH | /users/{id}/lock | A1,S13 | Khóa tài khoản |
| PATCH | /users/{id}/unlock | A1 | Mở khóa |
| POST | /users/{id}/reset-password | A1 | Admin force reset |
| GET | /roles | A1 | List role |
| POST | /roles | A1 | Tạo role (admin) |
| GET | /permissions | A1 | List permission |
| PUT | /roles/{id}/permissions | A1 | Gán quyền cho role |
| GET | /internal/parents-of/{studentId} | — | Service-to-service (P5 gọi) |
| GET | /internal/devices/{userId} | — | Service-to-service (P5 gọi) |

**api-gateway:** không có business endpoint, chỉ route + validate JWT cho mọi request.

### 9.2 P2 — `academic-service` (Cấu trúc + TKB + Điểm danh) — 25 endpoint

| Method | Path | UC | Mô tả |
|---|---|---|---|
| POST | /academic-years | A2 | Tạo năm học |
| GET | /academic-years | A2 | List năm học |
| PATCH | /academic-years/{id}/close | A2 | Khóa năm học (precondition cho finalize của P3) |
| POST | /semesters | A2 | Tạo học kỳ |
| GET | /semesters?academicYearId | A2 | List HK |
| POST | /grade-levels | A2 | Tạo khối (K10/11/12) |
| POST | /subjects | A2 | Tạo môn học |
| POST | /rooms | A2 | Tạo phòng |
| POST | /school-holidays | A3,S10 | Tạo ngày nghỉ |
| GET | /school-holidays?academicYearId | A3 | List ngày nghỉ |
| POST | /classes | A2 | Tạo lớp |
| GET | /classes?academicYearId&gradeLevelId | A2 | List lớp |
| GET | /classes/{id} | A2 | Chi tiết lớp |
| PATCH | /classes/{id}/homeroom-teacher | A2 | Gán/đổi GVCN |
| POST | /classes/{id}/enrollments | A2 | Thêm 1 HS vào lớp |
| POST | /classes/{id}/enrollments/bulk | A2 | Bulk import HS |
| GET | /classes/{id}/students | A2 | DS HS trong lớp |
| DELETE | /class-enrollments/{id} | A2 | Chuyển/rời lớp |
| POST | /teacher-class-subjects | A2,S5 | Phân công GV dạy môn |
| GET | /teachers/{id}/my-classes | B1 | Lớp GV được phân công |
| GET | /teachers/{id}/timetable | B2 | TKB cá nhân GV |
| POST | /timetable/slots | A3 | Xếp tiết + conflict check (409 nếu trùng) |
| GET | /classes/{id}/timetable | A3,C2 | TKB của lớp / HS |
| DELETE | /timetable/slots/{id} | A3 | Xóa tiết |
| POST | /attendance/bulk | B3 | Điểm danh hàng loạt → publish `attendance.absent` |
| GET | /attendance/today?classId&slotId | B3 | DS HS để GV điểm danh |
| GET | /classes/{id}/attendance?from&to | B3 | Sổ điểm danh lớp |
| GET | /students/{id}/attendance | C3,D2 | Chuyên cần HS (PH xem được nếu là con) |

### 9.3 P3 — `academic-service` (Điểm + Bài tập + Ngoại khóa + Lên lớp) — 22 endpoint

| Method | Path | UC | Mô tả |
|---|---|---|---|
| POST | /exam-categories | A4,S6 | Loại điểm + hệ số |
| GET | /exam-categories?academicYearId | A4 | List |
| POST | /subject-score-configs | A4 | Hệ số môn |
| POST | /grades/bulk | B4 | Nhập điểm hàng loạt → publish `grade.published` |
| GET | /grades?classId&subjectId&semesterId | B4 | Bảng điểm |
| PATCH | /grades/{id} | B4,S7 | Sửa điểm (auto-log) → publish `grade.changed` |
| GET | /grades/{id}/change-logs | B4 | Lịch sử sửa |
| GET | /classes/{id}/grade-distribution | B4,A8 | Phổ điểm |
| GET | /students/{id}/grades | C2,D2 | Điểm HS |
| POST | /assignments | B5 | Tạo bài tập (DRAFT) |
| GET | /assignments?classId | B5,C4 | List bài tập |
| PATCH | /assignments/{id}/publish | B5 | Publish → noti lớp |
| PATCH | /assignments/{id}/close | B5 | Đóng nhận bài |
| DELETE | /assignments/{id} | B5 | Xóa (chỉ DRAFT) |
| POST | /assignments/{id}/submissions | C4 | HS nộp bài (file qua file-service) |
| GET | /assignments/{id}/submissions | B5 | GV xem DS nộp |
| PATCH | /submissions/{id}/grade | B5 | GV chấm + feedback |
| GET | /students/{id}/assignments | C4 | DS bài tập của HS |
| POST | /extracurricular/courses | A5 | Tạo khóa ngoại khóa |
| GET | /extracurricular/courses | A5,C6 | List khóa |
| POST | /extracurricular/courses/{id}/enroll | C6,D5 | Đăng ký → publish `extracurricular.enrolled` (P4 sinh invoice) |
| GET | /students/{id}/extracurricular | C6,D5 | Khóa HS đã đăng ký |
| POST | /academic-years/{id}/finalize | S11 | Chốt lên lớp/lưu ban/tốt nghiệp |
| GET | /students/{id}/yearly-summaries | S11 | Tổng kết các năm |

### 9.4 P4 — `finance-service` (20 endpoint) + `file-service` (5 endpoint)

#### finance-service

| Method | Path | UC | Mô tả |
|---|---|---|---|
| POST | /fee-categories | A7 | Tạo loại phí |
| GET | /fee-categories | A7 | List |
| POST | /fee-periods | A7 | Tạo đợt thu (DRAFT) |
| GET | /fee-periods | A7 | List đợt thu |
| GET | /fee-periods/{id} | A7 | Chi tiết đợt |
| POST | /fee-periods/{id}/items | A7 | Định mức theo khối/HS |
| PATCH | /fee-periods/{id}/open | A7 | Đổi sang OPEN |
| PATCH | /fee-periods/{id}/close | A7 | Đóng đợt |
| POST | /fee-periods/{id}/generate-invoices | A7 | Bulk sinh invoice → publish `invoice.issued` |
| GET | /invoices?studentId&status | A7,D4 | List hóa đơn |
| GET | /invoices/{id} | A7,D4 | Chi tiết hóa đơn |
| PATCH | /invoices/{id}/cancel | A7 | Hủy hóa đơn |
| POST | /payments | D4 | PH tạo payment → trả redirect URL |
| GET | /payments/{id} | D4 | Trạng thái payment |
| GET | /payments/vnpay/callback | D4,S8 | IPN VNPAY — verify HMAC, idempotent |
| GET | /payments/momo/callback | D4,S8 | IPN MoMo |
| POST | /refunds | A7 | Yêu cầu hoàn tiền |
| PATCH | /refunds/{id}/approve | A7 | Duyệt hoàn |
| GET | /reports/revenue?from&to | A8 | Báo cáo doanh thu |
| GET | /reports/debt | A8 | Báo cáo công nợ |

#### file-service

| Method | Path | UC | Mô tả |
|---|---|---|---|
| POST | /files/presigned-upload | S4 | Trả URL PUT MinIO + fileKey |
| POST | /files/{fileKey}/confirm | S4 | Xác nhận upload xong |
| GET | /files/{fileKey}/presigned-download | S4 | URL GET hết hạn 15m |
| GET | /files/{fileKey}/metadata | S4 | Metadata file |
| DELETE | /files/{fileKey} | S4 | Xóa (owner/admin only) |

### 9.5 P5 — `notification-service` (19 endpoint + 2 WebSocket)

| Method | Path | UC | Mô tả |
|---|---|---|---|
| GET | /notifications/me?unread=true | C5,D2 | In-app noti của user |
| PATCH | /notifications/{id}/read | C5 | Đánh dấu đã đọc |
| POST | /notifications/read-all | C5 | Đọc tất cả |
| GET | /me/notification-preferences | C5 | Cấu hình kênh |
| PUT | /me/notification-preferences | C5 | Cập nhật preference |
| GET | /notification-templates | A1 | List template (admin) |
| POST | /notification-templates | A1 | Tạo template |
| PATCH | /notification-templates/{id} | A1 | Sửa template |
| POST | /announcements/broadcast | A6,E2 | Admin broadcast 10K (stress test) |
| GET | /chats/threads | B6,D3 | DS hội thoại |
| POST | /chats/threads | B6,D3 | Tạo DM mới |
| GET | /chats/threads/{id}/messages | B6 | Lịch sử chat (Mongo) |
| POST | /chats/threads/{id}/messages | B6 | Gửi tin nhắn |
| PATCH | /chats/messages/{id}/read | B6 | Đánh dấu đọc |
| GET | /audit-logs?actor&action&entity&date | A6 | Audit log viewer (Admin) |
| GET | /audit-logs/{id} | A6 | Chi tiết audit |
| GET | /reports/grade-distribution | A8 | Phổ điểm toàn trường |
| GET | /reports/attendance-rate | A8 | Tỷ lệ chuyên cần |
| POST | /reports/grade-distribution/export | A8 | Export Excel (Apache POI) |
| POST | /reports/attendance-rate/export | A8 | Export Excel |
| WS | /ws/chat | B6 | Chat realtime (STOMP) |
| WS | /ws/notifications | C5,D2 | Push noti realtime cho web |

---

## 10. Tổng kết phân chia khối lượng

| Người | Service | # bảng DB | # endpoint REST | # event publish |
|---|---|---|---|---|
| **P1** | api-gateway + identity-service + common + infra | 13 | 24 | 4 |
| **P2** | academic-service (core) | 11 | 25 | 3 |
| **P3** | academic-service (advanced) | 11 | 22 | 6 |
| **P4** | finance-service + file-service | 8 | 25 (20+5) | 4 |
| **P5** | notification-service + Mongo | 4 + 4 collection | 19 + 2 WS | 0 (chỉ consume) |
| | **Tổng** | **47 + 4 Mongo** | **115 + 2 WS** | **17** |

> Cân bằng khối lượng: P2/P3/P4 tương đương ~22-25 endpoint. P1 ít endpoint nhưng phải làm infra + auth (chặn cả team). P5 ít endpoint nhưng phải consume mọi event + 4 collection Mongo + WebSocket + reports.

---

**Lưu ý:** Nếu cần đổi phân công, họp 5 người và cập nhật file này trong PR riêng.
