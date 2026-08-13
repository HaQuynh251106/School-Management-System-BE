# Biên bản UAT năm học 2026–2027

## Trạng thái phát hành

- Technical UAT trên staging: **Đạt**.
- Business UAT với người dùng thực tế: **Chờ xác nhận**.
- Production: **Chưa được phép triển khai** cho tới khi phần ký xác nhận hoàn tất.

## Môi trường đã kiểm tra

- Web staging: `http://127.0.0.1:5180`.
- Backend staging: `http://127.0.0.1:4100`; Actuator tách riêng tại `4101`.
- PostgreSQL staging độc lập với database local/production.
- Bộ dữ liệu: 6 vai trò, 2 học kỳ, 6 lớp, 12 môn, 360 tiết, 2.880 điểm,
  30 tổng kết đủ điều kiện, 18 học sinh đầu cấp, khảo thí, bài tập, điểm danh,
  hóa đơn và thanh toán VietQR.

## Bằng chứng tự động

| Hạng mục | Kết quả |
|---|---:|
| Backend Maven | 54 test đạt |
| Web unit/component | 27 test đạt |
| Web Playwright đăng nhập/phân quyền sáu vai trò | 12/12 đạt trên staging |
| Web Playwright toàn bộ màn hình theo vai trò | 6/6 hành trình, 53 URL đạt; không có JavaScript error hoặc HTTP 5xx |
| Mobile analyze | Không có issue |
| Mobile integration thực | 4/4 đạt |
| Kiểm tra dữ liệu UAT SQL | Đạt |
| Restore PostgreSQL tách biệt | 79 bảng, 0 migration lỗi |
| Prometheus rules | 4/4 hợp lệ |
| Prometheus scrape Backend | `up=1` |
| Alertmanager | Đã nhận cảnh báo UAT tổng hợp qua receiver `operations` |
| Loki JSON log | Đã nhận stream `sse-backend` |
| Backup tự động staging | Đã tạo và xác minh |

## Kịch bản người dùng phải nghiệm thu

Đánh dấu `Đạt/Không đạt`, ghi bằng chứng và mã lỗi nếu có.

| # | Người thực hiện | Kịch bản | Kết quả | Ghi chú |
|---:|---|---|---|---|
| 1 | Admin | Kiểm tra dashboard, người dùng, phân quyền và báo cáo toàn trường |  |  |
| 2 | Admin | Tạo cơ cấu năm học, phân công, tạo–phát hành–khôi phục phiên bản thời khóa biểu |  |  |
| 3 | Admin | Tạo đợt thu, phát hành hóa đơn, lọc công nợ, đối soát VietQR |  |  |
| 4 | Giáo viên | Điểm danh, xử lý đơn nghỉ, nhập điểm, bài tập, khảo thí và tổng kết lớp |  |  |
| 5 | Học sinh | Xem lịch, điểm, bài tập, xin nghỉ, thông báo và phúc khảo |  |  |
| 6 | Phụ huynh | Xem dữ liệu con, duyệt đơn nghỉ, nhận nhắc nợ và mở VietQR |  |  |
| 7 | Cả bốn vai trò | Reload giữ phiên; URL ngoài quyền bị từ chối đúng |  |  |
| 8 | Admin + giáo viên | Lịch phát hành hiển thị đúng phiên bản, không lộ bản nháp |  |  |
| 9 | Giáo viên + phụ huynh | Thông báo điểm danh/điểm số/đơn nghỉ đúng đối tượng |  |  |
| 10 | Vận hành | Cảnh báo thử, tìm log theo `X-Request-ID`, restore một backup gần nhất |  |  |

## Tiêu chí go/no-go

Chỉ **GO** khi:

1. Tất cả kịch bản trên đạt, không còn lỗi blocker/critical/high.
2. Secret production đã được nạp từ secret manager; không có secret trong Git.
3. Domain HTTPS, CORS, email, Firebase và VietQR production đã được xác minh.
4. Receiver cảnh báo email/webhook production đã nhận một cảnh báo thử.
5. Backup off-site và diễn tập restore có biên bản.
6. Có phương án rollback image/database và người trực vận hành ngày phát hành.

## Xác nhận

| Vai trò | Họ tên | Kết luận | Ngày | Chữ ký/xác nhận điện tử |
|---|---|---|---|---|
| Đại diện Admin |  |  |  |  |
| Đại diện Giáo viên |  |  |  |  |
| Đại diện Phụ huynh/học sinh |  |  |  |  |
| Người phụ trách kỹ thuật |  |  |  |  |
