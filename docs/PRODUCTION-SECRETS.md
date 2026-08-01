# Cấu hình bí mật production

Không commit `.env.production.local`, mật khẩu SMTP, khóa SSH, JWT hay mật khẩu PostgreSQL. Giá trị thật phải được lưu trong GitHub Environment `production` hoặc secret manager của máy chủ.

## Repository variables

- `DEPLOY_ENABLED=true` chỉ bật sau khi staging và UAT đạt.
- Web: `VITE_API_BASE=https://<backend-public-domain>`.

## GitHub Environment secrets

- `DEPLOY_HOST`, `DEPLOY_USER`, `DEPLOY_SSH_KEY`, `DEPLOY_KNOWN_HOSTS`, `DEPLOY_PATH`.
- Máy chủ giữ `.env.production.local` với `SSE_DB_PASSWORD`, `SSE_JWT_SECRET`, `SSE_GRAFANA_ADMIN_PASSWORD`, CORS, URL reset mật khẩu, SMTP (nếu bật), Firebase (nếu bật) và VietQR.
- VietQR bắt buộc: `SSE_PAYMENT_MODE=vietqr`, `SSE_VIETQR_BANK_ID`, `SSE_VIETQR_ACCOUNT_NO`, `SSE_VIETQR_ACCOUNT_NAME`.

## Cổng kiểm soát phát hành

1. Chạy `scripts/validate-production-env.ps1 -EnvFile .env.production.local`.
2. Chạy staging bằng compose production + override staging.
3. Chạy smoke test sức khỏe Backend/Web và bộ E2E sáu vai trò.
4. Chỉ người duyệt GitHub Environment `production` mới được cho phép deploy.
5. `SSE_SEED_ENABLED=false` ở production; dữ liệu nghiệm thu không được nạp vào production.
6. Cấu hình receiver cảnh báo email/webhook từ secret manager, kiểm tra một cảnh báo thử và diễn tập restore trước khi bật deploy.
