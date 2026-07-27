# School Management System — Backend

Backend hiện tại là một **modular monolith** chạy bằng Java 17, Spring Boot 3 và Maven. Các phân hệ danh tính, cơ cấu đào tạo, thời khóa biểu, điểm danh, điểm số, bài tập, tài chính, thông báo, chat và báo cáo nằm trong cùng ứng dụng `services/app`, mặc định phục vụ tại `http://localhost:4000`.

## Chạy cục bộ với PostgreSQL

Yêu cầu: JDK 17+, Maven 3.9+ và PostgreSQL 17 (hoặc PostgreSQL 16+).

```powershell
Copy-Item .env.local.example .env.local
# Điền mật khẩu PostgreSQL và các khóa local trong .env.local
mvn -pl services/app -am package
.\scripts\start-postgres-local.ps1
```

Profile `local` sử dụng PostgreSQL thật tại `127.0.0.1:5432/sse_db`. Script sẽ:

- đọc thông tin kết nối từ `.env.local` không được đưa vào Git;
- khởi tạo cụm PostgreSQL riêng trong `data/postgres` nếu chưa tồn tại;
- tạo `sse_db`, chạy toàn bộ migration Flyway rồi khởi động Backend;
- chỉ seed khi database còn trống, không ghi đè dữ liệu đã nhập.

Nếu chỉ cần bản demo H2, chạy profile `demo`:

```powershell
mvn -pl services/app spring-boot:run -Dspring-boot.run.profiles=demo
```

H2 không còn là nguồn dữ liệu của profile `local`.

Kiểm tra trạng thái tại `http://localhost:4000/actuator/health`; OpenAPI tại `http://localhost:4000/swagger-ui.html`.

## Kiểm thử và đóng gói

```powershell
mvn -pl services/app -am test
mvn -pl services/app -am package
```

Flyway là nguồn chân lý của schema. Hibernate dùng `validate`; không dùng `create-drop` ở profile chạy sản phẩm. Kiểm thử tích hợp sử dụng H2 in-memory riêng nên không tác động dữ liệu local.

## Chạy với PostgreSQL bằng Docker

```powershell
Copy-Item .env.example .env
# Điền toàn bộ secret trong .env
docker compose up --build
```

Compose khởi động một PostgreSQL 16 và một Backend. Dữ liệu PostgreSQL cùng tệp upload được gắn volume bền vững.

## Biến môi trường chính

| Biến | Mục đích |
|---|---|
| `SSE_DB_URL`, `SSE_DB_USER`, `SSE_DB_PASSWORD` | Kết nối PostgreSQL |
| `SSE_JWT_SECRET` | Khóa ký JWT tối thiểu 32 ký tự |
| `SSE_CORS_ALLOWED_ORIGINS` | Danh sách origin Web/Mobile Web |
| `SSE_SEED_ENABLED` | Chỉ bật dữ liệu mẫu ở môi trường phát triển |
| `SSE_STORAGE_PATH` | Thư mục lưu tệp upload |
| `SSE_STORAGE_MAX_FILE_BYTES` | Dung lượng tối đa mỗi tệp, mặc định 10 MB |
| `SSE_STORAGE_USER_QUOTA_BYTES` | Hạn mức tệp mỗi tài khoản, mặc định 100 MB |
| `SSE_COOKIE_SECURE` | Đặt `true` khi website chạy HTTPS để bảo vệ cookie phiên |
| `SSE_MAIL_ENABLED` | Chỉ bật sau khi SMTP đã được cấu hình và kiểm thử |
| `SSE_MAIL_*` | SMTP cho email reset mật khẩu/thông báo |
| `SSE_FIREBASE_ENABLED`, `SSE_FIREBASE_PROJECT_ID` | Bật Firebase Cloud Messaging cho push notification |
| `SSE_FIREBASE_CREDENTIALS_PATH` | Đường dẫn service-account JSON khi chạy trực tiếp |
| `SSE_FIREBASE_CREDENTIALS_BASE64` | Service-account JSON dạng Base64, phù hợp Docker/secret manager |
| `SSE_PAYMENT_MODE` | `disabled`, `sandbox` (mô phỏng nội bộ) hoặc `momo-sandbox` |
| `SSE_PAYMENT_CALLBACK_SECRET` | Khóa HMAC callback sandbox, tối thiểu 32 ký tự |
| `SSE_MOMO_PARTNER_CODE` | Partner Code do MoMo for Business cấp |
| `SSE_MOMO_ACCESS_KEY` | Access Key của môi trường MoMo Testing |
| `SSE_MOMO_SECRET_KEY` | Secret Key ký HMAC-SHA256, không đưa lên Git |
| `SSE_MOMO_ENDPOINT` | Mặc định `https://test-payment.momo.vn/v2/gateway/api/create` |
| `SSE_MOMO_REDIRECT_URL` | URL đưa phụ huynh trở lại trang khoản thu |
| `SSE_MOMO_IPN_URL` | URL HTTPS công khai để MoMo gửi kết quả server-to-server |

## Quy tắc an toàn đã áp dụng

- Access token sống ngắn; refresh token được hash, xoay vòng, gắn IP/User-Agent và thu hồi khi đổi mật khẩu.
- Tài khoản tạo/import/reset phải đổi mật khẩu tạm; phiên cũ bị vô hiệu qua `tokenVersion`.
- File chỉ tải được bởi người tải lên, quản trị viên hoặc người thực sự tham gia bài tập/bài nộp.
- Hóa đơn có khóa duy nhất theo đợt thu + học sinh; chạy sinh hóa đơn nhiều lần không tạo bản trùng.
- Thanh toán bắt đầu ở `PENDING`; chỉ callback HMAC hợp lệ mới cập nhật hóa đơn. Thu tiền mặt có endpoint riêng cho quản trị viên.
- Mọi API ghi dữ liệu thành công được đưa vào audit log.

## Cấu trúc chính

```text
services/app/src/main/java/com/sse/app/
├── identity/        tài khoản, đăng nhập, phụ huynh–học sinh
├── academic/        lớp, môn, thời khóa biểu, điểm danh, điểm, bài tập, tổng kết
├── finance/         đợt thu, hóa đơn, thanh toán, đối soát
├── notification/    thông báo, sở thích kênh, thiết bị, delivery log
├── file/            upload và download có phân quyền
├── audit/           nhật ký thay đổi
└── report/          báo cáo và xuất dữ liệu
```

Các tài liệu mô tả sáu microservice trong `docs/architecture` là thiết kế lịch sử, không phải cấu trúc runtime hiện tại.
