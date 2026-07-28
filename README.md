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
| `SSE_PAYMENT_MODE` | `disabled` hoặc `vietqr` |
| `SSE_VIETQR_BANK_ID` | Mã ngân hàng Napas/VietQR |
| `SSE_VIETQR_ACCOUNT_NO` | Số tài khoản nhận khoản thu |
| `SSE_VIETQR_ACCOUNT_NAME` | Tên chủ tài khoản không dấu |
| `SSE_VIETQR_TEMPLATE` | Mẫu ảnh QR, mặc định `compact2` |

## Quy tắc an toàn đã áp dụng

- Access token sống ngắn; refresh token được hash, xoay vòng, gắn IP/User-Agent và thu hồi khi đổi mật khẩu.
- Tài khoản tạo/import/reset phải đổi mật khẩu tạm; phiên cũ bị vô hiệu qua `tokenVersion`.
- File chỉ tải được bởi người tải lên, quản trị viên hoặc người thực sự tham gia bài tập/bài nộp.
- Hóa đơn có khóa duy nhất theo đợt thu + học sinh; chạy sinh hóa đơn nhiều lần không tạo bản trùng.
- Thanh toán VietQR bắt đầu ở `PENDING`; phụ huynh xác nhận đã chuyển khoản và quản trị viên đối soát trước khi hóa đơn được cập nhật. Thu tiền mặt có endpoint riêng cho quản trị viên.
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

## CI/CD và triển khai production

- `Backend CI` chạy Maven `clean verify` trên mọi push, pull request hoặc khi chạy thủ công.
- `Backend Release` chạy trên `main`, tag `v*` hoặc thủ công; image được phát hành tại `ghcr.io/<owner>/<repository>`.
- `docker-compose.prod.yml` chạy Web, Backend và PostgreSQL bằng image đã phát hành, volume bền vững, healthcheck và `SSE_COOKIE_SECURE=true`.

Trên máy chủ:

```bash
cp .env.production.example .env.production
# Thay toàn bộ mật khẩu, secret, domain và cấu hình tích hợp.
docker compose --env-file .env.production -f docker-compose.prod.yml up -d
```

Nên đặt Web và Backend sau reverse proxy HTTPS. Repository Web phải có variable `VITE_API_BASE` trỏ tới URL HTTPS công khai của Backend trước khi phát hành image.

Để GitHub Actions tự cập nhật VPS, đặt repository variable `DEPLOY_ENABLED=true`, tạo Environment `production` và thêm các secret `DEPLOY_HOST`, `DEPLOY_USER`, `DEPLOY_SSH_KEY`, `DEPLOY_KNOWN_HOSTS`, `DEPLOY_PATH`. Thư mục `DEPLOY_PATH` trên máy chủ phải chứa `docker-compose.prod.yml` và file `.env` production mà Docker Compose tự đọc.
