# Kiểm thử thanh toán MoMo Sandbox

Hệ thống sử dụng luồng thanh toán một lần `captureWallet` qua endpoint:

`https://test-payment.momo.vn/v2/gateway/api/create`

## 1. Lấy thông tin Testing

Đăng ký MoMo for Business và lấy riêng cho môi trường Testing:

- Partner Code
- Access Key
- Secret Key

Không ghi các khóa này vào mã nguồn, ảnh chụp hoặc Git.

## 2. Tạo URL công khai cho backend

MoMo gửi kết quả thanh toán theo cơ chế server-to-server, vì vậy backend cục bộ
phải được đưa ra một HTTPS URL bằng tunnel hoặc môi trường staging.

Ví dụ:

- Backend công khai: `https://your-tunnel.example`
- IPN: `https://your-tunnel.example/payments/momo/ipn`
- Redirect: `http://127.0.0.1:5173/?payment=momo#/D4`

IPN phải trỏ tới backend, không trỏ tới frontend.

## 3. Cấu hình `.env.local`

```dotenv
SSE_PAYMENT_MODE=momo-sandbox
SSE_MOMO_ENDPOINT=https://test-payment.momo.vn/v2/gateway/api/create
SSE_MOMO_PARTNER_CODE=YOUR_TEST_PARTNER_CODE
SSE_MOMO_ACCESS_KEY=YOUR_TEST_ACCESS_KEY
SSE_MOMO_SECRET_KEY=YOUR_TEST_SECRET_KEY
SSE_MOMO_REDIRECT_URL=http://127.0.0.1:5173/?payment=momo#/D4
SSE_MOMO_IPN_URL=https://your-tunnel.example/payments/momo/ipn
```

Khởi động lại backend sau khi thay đổi biến môi trường.

## 4. Luồng kiểm thử

1. Đăng nhập bằng tài khoản phụ huynh.
2. Mở **Học phí**.
3. Chọn hóa đơn chưa hoàn tất và bấm **Thanh toán MoMo**.
4. Hệ thống chuyển sang trang MoMo Testing.
5. Hoàn tất hoặc hủy giao dịch.
6. MoMo chuyển trình duyệt về trang học phí và đồng thời gửi IPN về backend.
7. Chỉ IPN có chữ ký hợp lệ và đúng số tiền mới cập nhật hóa đơn.

## 5. Kết quả cần kiểm tra

- `payments.status` chuyển từ `PENDING` sang `SUCCESS` hoặc `FAILED`.
- `payment_gateway_transactions.signature_valid=true`.
- Hóa đơn chỉ được cộng tiền một lần khi MoMo gửi lại IPN.
- Phụ huynh nhận thông báo và xem trạng thái hóa đơn mới.
- Secret Key không xuất hiện trong log, response hoặc bảng giao dịch.

Tài liệu API chính thức:
https://developers.momo.vn/v3/docs/payment/api/wallet/onetime/
