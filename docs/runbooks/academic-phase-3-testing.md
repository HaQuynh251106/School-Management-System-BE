# Kiểm thử Giai đoạn 3 - Kế hoạch đào tạo năm học

> **Trạng thái: CHƯA HOÀN THÀNH - TẠM DỪNG CHỜ RÀ SOÁT LẠI.**
>
> Các chức năng bên dưới là phần kỹ thuật đã triển khai, chưa phải kết quả
> nghiệm thu cuối. Sau khi hoàn thành các giai đoạn khác, dự án phải quay lại
> Giai đoạn 3 để rà soát tài liệu gốc, bổ sung phần còn thiếu, kiểm thử toàn
> luồng và chỉ đánh dấu hoàn thành khi người dùng xác nhận.

## 1. Phạm vi đã triển khai kỹ thuật

Giai đoạn 3 quản lý kế hoạch đào tạo theo **năm học, khối và phiên bản**.
Số môn không được hardcode trong mã nguồn mà lấy từ danh sách môn của từng
phiên bản kế hoạch.

Mỗi môn trong kế hoạch có:

1. Học kỳ áp dụng.
2. Ngày bắt đầu và kết thúc.
3. Số tiết mỗi tuần và tổng số tiết.
4. Danh sách Chương, Chủ đề và Bài học.
5. Các giai đoạn cùng số tiết phải hoàn thành.
6. Tuần kiểm tra và tuần dự phòng.

Vòng đời phiên bản:

`DRAFT → PUBLISHED → LOCKED`

- `DRAFT`: được chỉnh sửa toàn bộ nội dung.
- `PUBLISHED`: đang được áp dụng, không được sửa trực tiếp.
- `LOCKED`: đã khóa vĩnh viễn; muốn điều chỉnh phải tạo phiên bản mới.
- Khi công bố phiên bản mới, phiên bản đang `PUBLISHED` trước đó tự chuyển
  thành `LOCKED`.

## 2. Chuẩn bị

Khởi động backend và frontend, sau đó đăng nhập:

- Admin: `admin / admin@123`
- Teacher: `gv.toan / teacher@123`

Mở:

**Cơ cấu đào tạo → Kế hoạch đào tạo**

## 3. Luồng tạo kế hoạch lần đầu

1. Chọn năm học.
2. Chọn khối `K10`, `K11` hoặc `K12`.
3. Nếu khối chưa có kế hoạch, nhập tên và bấm **Tạo kế hoạch**.
4. Kiểm tra kế hoạch được tạo là `Phiên bản 1 · Bản nháp`.
5. Thêm môn cho cả HK1 và HK2.
6. Với mỗi môn, nhập tiết/tuần, tổng tiết, ngày bắt đầu và kết thúc.

Kỳ vọng:

- Một môn chỉ xuất hiện một lần trong cùng học kỳ và phiên bản.
- Thời gian môn phải nằm trong học kỳ.
- Một năm học và khối không thể có hai bản nháp cùng lúc.

## 4. Luồng nội dung chi tiết môn học

Chọn một môn trong **Nội dung chi tiết từng môn**.

### Giai đoạn

1. Mở tab **Giai đoạn**.
2. Tạo các giai đoạn theo thứ tự.
3. Nhập ngày bắt đầu, kết thúc và số tiết mục tiêu.
4. Sửa thử một giai đoạn rồi lưu.

Kỳ vọng:

- Ngày giai đoạn phải nằm trong thời gian môn học.
- Tổng tiết các giai đoạn không được vượt tổng số tiết môn.
- Khi công bố, tổng tiết các giai đoạn phải bằng tổng số tiết môn.

### Chương trình môn học

1. Tạo một `Chương`.
2. Tạo `Chủ đề` và chọn Chương làm mục cha.
3. Tạo `Bài học` và chọn Chủ đề làm mục cha.
4. Nhập số tiết cho từng Bài học.

Kỳ vọng:

- Cấu trúc luôn là `Chương → Chủ đề → Bài học`.
- Chương không có mục cha.
- Chủ đề bắt buộc thuộc Chương.
- Bài học bắt buộc thuộc Chủ đề.
- Tổng tiết bài học không được vượt tổng số tiết môn.
- Khi công bố, tổng tiết bài học phải bằng tổng số tiết môn.

### Tuần đặc biệt

1. Mở tab **Tuần đặc biệt**.
2. Thêm ít nhất một tuần `Kiểm tra`.
3. Thêm ít nhất một tuần `Dự phòng`.
4. Sửa số tuần hoặc tên rồi lưu lại.

Kỳ vọng:

- Số tuần từ 1 đến 30 và không vượt thời gian môn học.
- Mỗi môn phải có cả tuần kiểm tra và tuần dự phòng trước khi công bố.

## 5. Công bố, khóa và tạo phiên bản

1. Hoàn thiện dữ liệu tất cả môn và kế hoạch kiểm tra giữa kỳ/cuối kỳ bắt buộc.
2. Kiểm tra thanh trạng thái hiển thị **Đủ điều kiện công bố**.
3. Bấm **Công bố**.
4. Kiểm tra toàn bộ nút thêm, sửa và xóa nội dung biến mất.
5. Bấm **Tạo phiên bản mới**.
6. Kiểm tra phiên bản mới có toàn bộ môn, giai đoạn, chương trình,
   tuần đặc biệt và kế hoạch kiểm tra được sao chép; ngày giờ/phòng/giám thị GĐ5 không được sao chép vào GĐ3.
7. Chỉnh sửa bản nháp mới rồi công bố.

Kỳ vọng:

- Phiên bản mới tăng tuần tự: `v1`, `v2`, `v3`...
- Phiên bản mới luôn bắt đầu ở `DRAFT`.
- Công bố phiên bản mới sẽ tự khóa phiên bản cũ.
- Phiên bản `LOCKED` không thể thu hồi hoặc sửa.
- Mọi thao tác tạo phiên bản, công bố và khóa đều có Admin audit.

## 6. Kiểm thử tự động

```powershell
cd C:\SchoolManagementSystem\BE

& "C:\Users\thaid\.cache\sse-tools\apache-maven-3.9.16\bin\mvn.cmd" `
  -pl services/app -am `
  -Dtest=AcademicPlanningServiceTest test

powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\smoke-academic-g3.ps1 `
  -BaseUrl http://127.0.0.1:4000

powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\audit-database.ps1 `
  -Database sse_db `
  -HostName localhost `
  -Port 5432 `
  -Username postgres `
  -Password postgres
```

Smoke GĐ3 tạo dữ liệu tạm trong một bản nháp, kiểm tra:

- Phiên bản kế hoạch có số phiên bản hợp lệ.
- Tạo đủ giai đoạn, Chương, Chủ đề, Bài học và tuần đặc biệt.
- Bài học không có Chủ đề bị từ chối.
- Xóa môn trong bản nháp xóa dây chuyền toàn bộ dữ liệu chi tiết.
- Dữ liệu tạm được dọn sạch sau khi kiểm thử.

## 7. Tiêu chí nghiệm thu

1. Không có danh sách môn hardcode theo khối trong backend hoặc frontend.
2. Mỗi khối/năm học có lịch sử nhiều phiên bản nhưng tối đa một bản nháp
   và một bản đang áp dụng.
3. Không sửa được phiên bản `PUBLISHED` hoặc `LOCKED`.
4. Không công bố nếu thiếu học kỳ, môn, giai đoạn, cấu trúc chương trình,
   tuần kiểm tra, tuần dự phòng hoặc kế hoạch kiểm tra bắt buộc.
5. Tổng tiết giai đoạn và tổng tiết bài học phải khớp tổng tiết môn.
6. Migration V11 bảo toàn kế hoạch cũ và chuyển `CLOSED` thành `LOCKED`.
7. Database quality audit không phát hiện lỗi phạm vi, phiên bản hoặc
   cây chương trình.
