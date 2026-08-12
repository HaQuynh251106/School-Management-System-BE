# Notification P0 - kiểm thử end-to-end

## Email đặt lại mật khẩu

Khởi động backend với các biến môi trường:

```powershell
$env:SSE_NOTIFICATION_PROVIDER_MODE = "real"
$env:SSE_SENDGRID_API_KEY = "SG.xxxxx"
$env:SSE_SENDGRID_FROM_EMAIL = "sender-da-xac-minh@example.com"
$env:SSE_WEB_BASE_URL = "http://127.0.0.1:5173"
```

Tài khoản thử nghiệm phải có email thật. Môi trường local hiện dùng
`thaidinh740@gmail.com` cho tài khoản Admin. Gọi `POST /auth/forgot-password`, kiểm tra Gmail,
mở liên kết và đặt mật khẩu mới. Link hết hạn sau 30 phút và không dùng lại được.

Kiểm tra tự động pipeline và xác định backend đang chạy MOCK hay REAL:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\smoke-notification-p0.ps1 `
  -Email "thaidinh740@gmail.com"
```

## Firebase Web Push

Điền các biến `VITE_FIREBASE_*` theo `.env.example`. Backend cần thêm:

```powershell
$env:SSE_NOTIFICATION_PROVIDER_MODE = "real"
$env:SSE_FCM_PROJECT_ID = "firebase-project-id"
$env:SSE_FCM_SERVICE_ACCOUNT_FILE = "C:\\secure\\firebase-service-account.json"
```

Sau đó bật `Thông báo đẩy` trong Trung tâm thông báo. FE sẽ xin quyền
trình duyệt, lấy FCM token thật và đăng ký token vào `/me/devices`.
Backend tự sinh và làm mới OAuth access token từ service-account; không cần chép token ngắn hạn.

Admin kiểm tra cấu hình mà không lộ secret qua `GET /admin/notification-providers/status`.

## RabbitMQ 10K

```powershell
node .\scripts\load\notification-10k.mjs
```

Đạt khi `published = routed = delivered = 10000`, `dropped = 0`, `dlqMessages = 0`
và `queueDrained = true`.
