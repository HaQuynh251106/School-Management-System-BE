# Cấu hình email đặt lại mật khẩu trên production

Backend hỗ trợ hai provider email: `sendgrid` và `smtp`. Mật khẩu/API key chỉ được lưu dưới dạng secret của môi trường chạy; tuyệt đối không ghi vào Git, seed SQL, migration hoặc log.

## Gmail SMTP

Gmail không chấp nhận mật khẩu đăng nhập thông thường cho SMTP. Tài khoản gửi phải bật xác minh hai bước và tạo **App Password 16 ký tự**. Không dùng mật khẩu Gmail mà người dùng đăng nhập trên trình duyệt.

Các biến môi trường:

```text
SSE_NOTIFICATION_PROVIDER_MODE=real
SSE_NOTIFICATION_EMAIL_PROVIDER=smtp
SSE_SMTP_HOST=smtp.gmail.com
SSE_SMTP_PORT=587
SSE_SMTP_USERNAME=<email gửi đã xác minh>
SSE_SMTP_PASSWORD=<Gmail App Password>
SSE_SMTP_FROM_EMAIL=<email gửi đã xác minh>
SSE_SMTP_AUTH=true
SSE_SMTP_STARTTLS=true
SSE_NOTIFICATION_WORKER_ENABLED=true
SSE_EVENTS_LOCAL_LISTENER_ENABLED=true
SSE_WEB_BASE_URL=https://<web-production>
```

Trên Azure Container Apps, tạo secret trước rồi tham chiếu secret vào container. Không đưa giá trị secret vào workflow hoặc terminal output:

```powershell
$smtpPassword = Read-Host "Gmail App Password" -AsSecureString
# Chuyển SecureString sang plaintext chỉ trong tiến trình PowerShell hiện tại,
# truyền cho Azure CLI và xóa biến ngay sau lệnh. Không ghi ra màn hình.
```

## Tiêu chí kiểm tra

1. `POST /auth/forgot-password` luôn trả thông báo trung tính, không lộ email tồn tại hay không.
2. Bảng `password_reset_tokens` có một token mới, chỉ lưu hash token.
3. `notifications` có bản ghi kênh `EMAIL`; `notification_delivery_logs` có provider `SMTP` và trạng thái `SENT`.
4. Email thật nhận được link trỏ về Web production.
5. Token dùng một lần; token đã dùng hoặc hết 30 phút bị từ chối.
6. Sau đặt lại mật khẩu, refresh token cũ bị thu hồi.

Nếu chưa có App Password hoặc sender đã xác minh, giữ `SSE_NOTIFICATION_PROVIDER_MODE=mock`/worker tắt. Không chuyển sang `real` với credential trống vì request sẽ ghi trạng thái `FAILED` sau số lần retry cấu hình.
