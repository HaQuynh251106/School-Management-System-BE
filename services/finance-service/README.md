# finance-service

Thư mục này là bản phác thảo tách dịch vụ cũ và không phải ứng dụng đang chạy.
Nghiệp vụ tài chính chính thức hiện nằm trong modular monolith tại
`services/app/src/main/java/com/sse/app/finance`.

Luồng thanh toán được hỗ trợ là VietQR:

1. Admin tạo và phát hành đợt thu, hệ thống sinh hóa đơn.
2. Phụ huynh mở hóa đơn và nhận mã VietQR có nội dung chuyển khoản định danh.
3. Phụ huynh đánh dấu đã chuyển khoản.
4. Admin đối soát giao dịch ngân hàng rồi xác nhận hoặc từ chối.
5. Khi xác nhận, hệ thống cập nhật công nợ, lưu lịch sử và gửi biên nhận qua email nếu SMTP đã được cấu hình.

Không sử dụng VNPay, MoMo, SMS hay Zalo OA trong phạm vi sản phẩm hiện tại.
