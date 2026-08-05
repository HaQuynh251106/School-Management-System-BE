# Kiểm thử Giai đoạn 5 - Lịch thi và coi thi

## Trạng thái giai đoạn

- Giai đoạn 3 và Giai đoạn 4 vẫn chưa nghiệm thu hoàn tất.
- Giai đoạn 3 là nguồn duy nhất của môn, khối, loại kiểm tra và thời lượng. Giai đoạn 5 chỉ hiện thực hóa nguồn đã công bố thành ngày giờ, phòng, học sinh và giám thị.
- Dữ liệu nghiệm thu sẵn: đợt thi `G5-0801145450`, phiên bản 2 đang được phát hành.

## Chạy tự động

Khởi động PostgreSQL, RabbitMQ và backend, sau đó chạy:

```powershell
cd C:\SchoolManagementSystem\BE
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\smoke-academic-g5.ps1 `
  -BaseUrl http://127.0.0.1:4000
```

Script kiểm tra: tạo đợt thi, xếp tự động 2 môn cho 3 khối, phân phòng/học sinh/giám thị, phát hành, tạo phiên bản 2, sửa thủ công, lưu phiên bản cũ, phân quyền xem, RabbitMQ và audit.

## Kiểm thử trên web

### Admin

1. Đăng nhập `admin / admin@123`.
2. Mở **Khảo thí & lịch thi** > **Lịch thi & coi thi**.
3. Chọn năm học `2027-2028`, đợt thi `G5-0801145450`.
4. Kiểm tra các tab **Lịch thi**, **GV bận/nghỉ**, **Lịch sử phiên bản**.
5. Mở từng ca thi và phòng thi để xem sức chứa, giám thị chính/dự phòng và danh sách học sinh.
6. Tạo bản điều chỉnh, đổi ngày/giờ hoặc phòng/giám thị, bấm **Kiểm tra**, rồi phát hành. Thời lượng chỉ sửa tại kế hoạch kiểm tra GĐ3.
7. Mở **Lịch sử hệ thống**, lọc module `academic` để xem log tạo, sửa, xếp tự động và phát hành.

### Học sinh

1. Đăng nhập `hs.huy / student@123`.
2. Mở **Theo dõi học thuật** > **Lịch thi**.
3. Kiểm tra ngày, môn, thời gian, phòng, số báo danh và giám thị.

### Phụ huynh 

1. Đăng nhập `ph.hoang / parent@123`.
2. Chọn một người con, mở **Giám sát học tập** > **Lịch thi**.
3. Đổi người con và xác nhận lịch thay đổi đúng theo học sinh đã chọn.

### Giáo viên

1. Đăng nhập một giáo viên được phân công coi thi với mật khẩu `teacher@123`.
2. Mở **Lịch coi thi**.
3. Kiểm tra đúng ca, phòng và vai trò **Giám thị chính** hoặc **Giám thị dự phòng**.

## Tiêu chí đạt

- Không trùng phòng, học sinh hoặc giám thị trong cùng ca.
- Giáo viên nghỉ/bận không được tự động phân công.
- Nếu tắt quyền coi môn mình dạy, giáo viên bộ môn không được coi môn đó.
- Không thể phát hành khi còn lỗi bắt buộc; cảnh báo vẫn được hiển thị đầy đủ cho Admin.
- Phiên bản mới không làm mất bản đã phát hành trước đó.
- Học sinh/phụ huynh/giáo viên chỉ thấy lịch đã phát hành thuộc phạm vi của mình.
- Mọi thay đổi quan trọng có audit; người liên quan nhận thông báo loại `EXAM` qua RabbitMQ.
