# Báo cáo F17 và S01 trên Mobile Android

Ngày kiểm tra: 12/08/2026  
Nhánh: `mobile-chiduc`

## 1. Môi trường kiểm tra

- Backend: `http://127.0.0.1:4000`
- Mobile Android: emulator `emulator-5554`
- API từ emulator: `http://10.0.2.2:4000`
- Tài khoản Kế toán: `ketoan / Ketoan123@@`
- Tài khoản Phụ huynh: `ph.nguyenvanhung / nguyenvanhung123@`

## 2. F17 - Tiền mặt, đối soát và hoàn tiền

### VietQR và đối soát

- Phụ huynh tạo VietQR thật, xem được QR, ngân hàng, số tài khoản và nội dung chuyển khoản.
- Phụ huynh xác nhận đã chuyển khoản.
- Kế toán nhìn thấy giao dịch trong tab `Đối soát`.
- Kế toán xác nhận mã ngân hàng `F17FIX20260812`.
- Giao dịch biến mất ngay khỏi danh sách chờ, không cần khởi động lại ứng dụng.
- Hóa đơn `inv-b17311b56c` chuyển từ `UNPAID` sang `PAID`, `paidAmount = 250000`.

### Thu tiền mặt

- Hóa đơn `inv-66330a746b` đã được thu nhiều lần trực tiếp trên Android.
- Tổng đã thu hiện tại: `400000` trên tổng `1000000`.
- Trạng thái giữ lại: `PARTIAL`, còn phải thu `600000`.
- Form kiểm tra số tiền không cho nhập số âm, bằng 0 hoặc lớn hơn công nợ.

### Hoàn tiền

- Hóa đơn `inv-d5d3897160` đã thanh toán `275000`.
- Đã hoàn một phần trực tiếp trên Android.
- Trạng thái giữ lại: `PARTIALLY_REFUNDED`.
- Tổng đã hoàn hiện tại: `35000`.
- Lý do test cuối: `F17_REFUND_RETEST`.

### Lỗi đã sửa

- Sửa callback `setState` vô tình trả về `Future`, từng làm màn Đối soát ném exception sau khi xác nhận.
- Sửa các màn khác có cùng mẫu lỗi để tránh tái diễn.
- Các tab Kế toán tự tải lại dữ liệu khi chuyển tab, không còn hiển thị snapshot cũ.
- Tách đóng bottom sheet và mở dialog thu/hoàn tiền thành hai bước.
- Chờ dialog đóng hết animation trước khi dispose controller và tải lại dữ liệu.

## 3. S01 - Trạng thái hóa đơn

Mobile đã hiển thị và lọc được các trạng thái:

| State | Nhãn tiếng Việt | Dữ liệu kiểm tra |
| --- | --- | --- |
| `UNPAID` | Chưa thu | `inv-de73d64aaa`, 123.000đ |
| `PARTIAL` | Thu một phần | `inv-66330a746b`, còn 600.000đ |
| `OVERDUE` | Quá hạn | `inv-08f6d56fa0` |
| `PAID` | Đã thu | `inv-b17311b56c` |
| `PARTIALLY_REFUNDED` | Hoàn một phần | `inv-d5d3897160`, đã hoàn 35.000đ |
| `REFUNDED` | Đã hoàn | `inv-c8543c18cf` |
| `CANCELLED` | Đã hủy | `inv-d247592301` |

Đợt thu giữ lại cho trạng thái `UNPAID`:

- ID: `fp-621d5f3f71`
- Mã: `S01-UNPAID-20260812`
- Hạn nộp: `15/09/2026`
- Phạm vi: lớp `10A1`

## 4. Kết quả kiểm thử

- `flutter analyze`: không có lỗi.
- `flutter test`: 30 test đạt, 4 integration test bỏ qua theo cấu hình hiện tại.
- APK debug build thành công.
- F17 VietQR, tiền mặt và hoàn tiền chạy thành công trên Android native.
- S01 có đủ dữ liệu thật trong PostgreSQL để kiểm tra lại trên Mobile.

## 5. Cách kiểm tra nhanh

1. Đăng nhập Kế toán.
2. Mở `Công nợ` để xem và lọc đủ trạng thái S01.
3. Chọn hóa đơn `INV-CLUB-cr-3749ef5f98` để thử thu thêm tiền mặt.
4. Chọn hóa đơn `INV-CLUB-cr-563848e6a3` để thử hoàn thêm một phần.
5. Đăng nhập Phụ huynh để xem các trạng thái tương ứng trong chi tiết hóa đơn.

