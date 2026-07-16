# Production readiness

## Đã tự động hóa trong mã nguồn

- PostgreSQL schema được quản lý bằng Flyway; Hibernate chỉ `validate` ở production.
- JWT secret, DB password và CORS origin bắt buộc truyền từ môi trường; seed mặc định tắt.
- Refresh token được hash, rotate và revoke khi logout.
- Kiểm tra quyền truy cập dữ liệu tài chính giữa giáo viên, học sinh và phụ huynh.
- Upload/download file có JWT, ACL theo bài tập/bài nộp, whitelist MIME, giới hạn 10 MB và persistent volume.
- Email reset password hỗ trợ SMTP; token không lộ trong response production.
- Thanh toán production mặc định `disabled`; sandbox tạo giao dịch `PENDING` và chỉ hoàn tất qua callback HMAC hợp lệ.
- Hóa đơn được chống sinh trùng theo đợt thu + học sinh; giao dịch treo được đối soát và hết hạn tự động.
- Mật khẩu tạm bắt buộc đổi, access token sống ngắn và phiên cũ bị vô hiệu khi đổi/reset mật khẩu.
- OpenAPI/Swagger, health probe và Prometheus metrics.
- Docker image chạy non-root; Compose gồm Backend, PostgreSQL và persistent volumes.
- CI build/test toàn bộ modules và Dependabot theo dõi Maven/GitHub Actions.

## Secret và tài khoản bên ngoài chủ sản phẩm phải cung cấp

- SMTP host/user/password và địa chỉ gửi email.
- Merchant account, secret/HMAC và callback HTTPS của VNPAY hoặc nhà cung cấp thanh toán được chọn.
- Firebase project/service account/APNs key nếu cần push notification FCM.
- Domain, TLS certificate, DNS và hạ tầng staging/production.
- Android upload keystore; Apple Developer team, distribution certificate và provisioning profile.
- Privacy policy, terms, thông tin pháp nhân và nội dung App Store/Google Play.

Không commit các giá trị trên. Đặt chúng trong secret manager của nền tảng triển khai và CI.
