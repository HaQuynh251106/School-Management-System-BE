# School Management System — Backend

Backend hiện tại là một **modular monolith** chạy bằng Java 17, Spring Boot 3 và Maven. Các phân hệ danh tính, cơ cấu đào tạo, thời khóa biểu, điểm danh, điểm số, bài tập, tài chính, thông báo, chat, ngoại khóa và báo cáo nằm trong cùng ứng dụng `services/app`, mặc định phục vụ tại `http://localhost:4000`.

## Chạy cục bộ

Yêu cầu: JDK 17+ và Maven 3.9+.

```powershell
mvn -pl services/app -am spring-boot:run -Dspring-boot.run.profiles=local
```

Profile `local` sử dụng H2 lưu trên đĩa, không xóa dữ liệu khi dừng Backend:

- Cơ sở dữ liệu: `services/app/data/sse.mv.db`
- Tệp tải lên: `services/app/data/uploads`
- Có thể đổi vị trí bằng `SSE_LOCAL_DB_PATH` và `SSE_LOCAL_STORAGE_PATH`.

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
| `SSE_MAIL_*` | SMTP cho email reset mật khẩu/thông báo |
| `SSE_PAYMENT_MODE` | Mặc định `disabled`; `sandbox` chỉ dùng local |
| `SSE_PAYMENT_CALLBACK_SECRET` | Khóa HMAC callback thanh toán, tối thiểu 32 ký tự |

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
