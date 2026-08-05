# Kiểm thử Giai đoạn 2 - Cơ cấu và kế hoạch đào tạo

## 1. Phạm vi đã hoàn thành

Màn **Cơ cấu đào tạo** có sáu tab dùng API và dữ liệu PostgreSQL thật:

1. Năm học.
2. Khối.
3. Lớp và phân lớp học sinh.
4. Môn học.
5. Kế hoạch đào tạo và lịch thi dự kiến.
6. Phòng học.

Học kỳ được tự sinh và hiển thị ngay dưới năm học được chọn. Ngày nghỉ được quản lý trong màn **Xếp thời khóa biểu** vì đây là đầu vào trực tiếp của lịch học.

Quyền mặc định:

- Admin quản lý toàn bộ cơ cấu, phân lớp, kế hoạch và lịch thi.
- Teacher được xem cơ cấu; được quản lý kế hoạch và lịch thi.
- Student/Parent chỉ có quyền đọc dữ liệu đào tạo được phép công khai.
- Student/Parent không được gọi API danh sách giáo viên hoặc API quản lý.

## 2. Khởi động

Mở PowerShell thứ nhất:

```powershell
cd C:\SchoolManagementSystem\BE
docker compose -f docker-compose.dev.yml up -d postgres rabbitmq minio

$env:SSE_DB_URL = "jdbc:postgresql://localhost:5432/sse_db"
$env:SSE_DB_USER = "postgres"
$env:SSE_DB_PASSWORD = "postgres"

& "C:\Users\thaid\.cache\sse-tools\apache-maven-3.9.16\bin\mvn.cmd" `
  -pl services/app -am spring-boot:run
```

Mở PowerShell thứ hai:

```powershell
cd C:\SchoolManagementSystem\Web-FE
& "C:\Program Files\nodejs\npm.cmd" run dev
```

Mở `http://127.0.0.1:5173`.

Tài khoản kiểm thử:

| Vai trò | Tài khoản | Mật khẩu |
|---|---|---|
| Admin | `admin` | `admin@123` |
| Teacher | `gv.toan` | `teacher@123` |
| Student | `hs.minh` | `student@123` |
| Parent | `ph.nguyen` | `parent@123` |

## 3. Luồng Admin trên FE

Đăng nhập Admin, mở **Cơ cấu đào tạo**.

### Tab Năm học

1. Tạo một năm học thử, ví dụ `2028-2029`.
2. Bấm mã năm học vừa tạo.
3. Kiểm tra HK1 tự sinh từ `01/09/2028` đến `31/01/2029`.
4. Kiểm tra HK2 tự sinh từ `01/02/2029` đến `30/06/2029`.
5. Kích hoạt năm học thử rồi kiểm tra năm đang mở trước đó tự đóng.
6. Bấm **Mở lại** tại năm `2027-2028`.

Kỳ vọng:

- Mã năm học không trùng.
- Mỗi năm luôn có đúng hai học kỳ, mỗi kỳ năm tháng.
- Chỉ duy nhất một năm có trạng thái đang hoạt động.
- `2027-2028` là năm đang hoạt động sau khi hoàn tất kiểm thử.

### Tab Khối

Kỳ vọng luôn chỉ có `K10`, `K11`, `K12`. Đây là danh mục cố định, không tạo thêm khối 9 hoặc khối 13.

### Tab Lớp

1. Chọn năm học và khối.
2. Tạo lớp mới, ví dụ `10A1`.
3. Gán GVCN.
4. Mở danh sách lớp, chọn học sinh chưa phân lớp rồi bấm xếp lớp.
5. Gỡ một học sinh khỏi lớp với lý do.
6. Tải lại trang.

Kỳ vọng:

- Lớp luôn sắp theo thứ tự tự nhiên `10A1` đến `10A10`.
- Một giáo viên không làm GVCN hai lớp trong cùng năm.
- Sĩ số không vượt sức chứa.
- Học sinh chỉ có một lớp trong một năm học.
- Lịch sử hệ thống có thao tác phân lớp và gỡ khỏi lớp.

### Tab Môn

1. Tạo môn với mã duy nhất.
2. Sửa hệ số.
3. Ngừng dùng rồi kích hoạt lại môn.

Kỳ vọng: môn ngừng dùng không xuất hiện trong lựa chọn tạo mới của kế hoạch.

### Tab Kế hoạch đào tạo

1. Chọn năm học và khối.
2. Tạo kế hoạch nháp.
3. Thêm môn cho cả HK1 và HK2; nhập tiết/tuần, tổng tiết, ngày bắt đầu và kết thúc.
4. Đánh dấu môn có thi.
5. Thêm lịch thi, phòng và giám thị.
6. Tạo một lịch thi khác trùng phòng hoặc trùng giám thị trong cùng thời gian.
7. Công bố kế hoạch khi chỉ báo chuyển sang **Đủ điều kiện công bố**.
8. Thu hồi về nháp để chỉnh sửa và công bố lại.
9. Khi kế hoạch ổn định, bấm **Đóng kế hoạch**.

Kỳ vọng:

- Mỗi khối chỉ có một kế hoạch trong một năm học.
- Môn phải nằm trong đúng học kỳ của năm học.
- Lịch thi trùng phòng/giám thị trả về xung đột.
- Kế hoạch thiếu học kỳ, thiếu môn hoặc thiếu kế hoạch kiểm tra bắt buộc không được công bố.
- Kế hoạch đã đóng không thể đưa về nháp hoặc chỉnh sửa.

### Tab Phòng

1. Tạo phòng với mã duy nhất và sức chứa lớn hơn 0.
2. Ngừng dùng rồi kích hoạt lại phòng.

Kỳ vọng: phòng ngừng dùng không thể chọn cho lịch thi mới.

### Ngày nghỉ trong Xếp thời khóa biểu

1. Mở **Xếp thời khóa biểu → Ngày nghỉ**.
2. Kiểm tra màn hình chỉ dùng năm học đang mở `2027-2028`.
3. Tạo ngày nghỉ một ngày.
4. Tạo kỳ nghỉ nhiều ngày.
5. Thử tạo ngày ngoài phạm vi năm học.
6. Xóa một ngày nghỉ thử.

Kỳ vọng: khoảng ngày hợp lệ được lưu; ngày ngoài năm học hoặc ngày kết thúc trước ngày bắt đầu bị chặn.

### Bộ lọc thời khóa biểu

1. Mở **Phân công bộ môn** và danh sách Học kỳ.
2. Mở **Xếp thời khóa biểu** và danh sách Học kỳ.
3. Kiểm tra cả hai nơi chỉ có `Học kỳ 1 · HK1` và `Học kỳ 2 · HK2` của năm `2027-2028`.
4. Kiểm tra danh sách lớp cũng chỉ thuộc năm `2027-2028`.
5. Chọn cùng một lớp, lần lượt mở HK1 và HK2; vị trí môn học của hai kỳ phải khác nhau.
6. Kiểm tra thời khóa biểu chỉ sử dụng các tiết từ 1 đến 6.

## 4. Luồng Teacher và kiểm tra quyền

1. Đăng nhập `gv.toan`.
2. Mở **Kế hoạch đào tạo**.
3. Kiểm tra Teacher xem được cơ cấu và quản lý kế hoạch/lịch thi.
4. Thử tạo môn, lớp hoặc phân lớp học sinh.

Kỳ vọng: thao tác quản lý cơ cấu/phân lớp bị `403` nếu Admin chưa cấp thêm quyền.

Đăng nhập Student hoặc Parent:

1. Kiểm tra không có màn quản lý GĐ2.
2. Gọi API quản lý hoặc danh sách giáo viên phải bị `403`.

## 5. Kiểm thử tự động

```powershell
cd C:\SchoolManagementSystem\BE

& "C:\Users\thaid\.cache\sse-tools\apache-maven-3.9.16\bin\mvn.cmd" `
  -pl services/app -am test

powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\smoke-academic-g2.ps1 `
  -BaseUrl http://127.0.0.1:4000

powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\smoke-app.ps1 `
  -BaseUrl http://127.0.0.1:4000

powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\audit-database.ps1 `
  -Database sse_db `
  -Username postgres `
  -Password postgres
```

Kiểm tra frontend:

```powershell
cd C:\SchoolManagementSystem\Web-FE
& "C:\Program Files\nodejs\npm.cmd" run lint
& "C:\Program Files\nodejs\npm.cmd" run build
```

## 6. Giai đoạn 3

Giai đoạn 3 đã triển khai một phần kỹ thuật gồm kế hoạch theo phiên bản, giai
đoạn tiến độ, cấu trúc Chương - Chủ đề - Bài học, tuần kiểm tra và tuần dự
phòng. Giai đoạn này **chưa hoàn thành và chưa được nghiệm thu**; sau khi hoàn
thành các giai đoạn khác phải quay lại rà soát và làm nốt. Xem trạng thái tại
`docs/runbooks/academic-phase-3-testing.md`.

Thuật toán tự động xếp thời khóa biểu, cân bằng tiến độ giữa các lớp, gợi ý giám
thị và tối ưu phòng là phạm vi tối ưu riêng sau khi hoàn thành các nghiệp vụ gốc.
