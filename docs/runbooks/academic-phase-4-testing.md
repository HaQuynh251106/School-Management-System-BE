# Kiểm thử Giai đoạn 4 - Thời khóa biểu, tiến độ và dạy bù

## 1. Điều kiện nghiệp vụ

- Năm học đang mở có đúng hai học kỳ.
- K10, K11 và K12 đều có kế hoạch GĐ3 ở trạng thái `PUBLISHED` hoặc `LOCKED`.
- Mỗi kế hoạch có môn và `weeklyPeriods` cho học kỳ cần xếp.
- Phân công giáo viên chỉ xác định giáo viên, lớp, môn và học kỳ. Số tiết không lấy từ phân công.
- Mỗi lớp có GVCN và phòng học cố định đang hoạt động.
- Tải mỗi giáo viên không quá 25 tiết/tuần, gồm cả chào cờ và sinh hoạt lớp nếu là GVCN.

## 2. Chạy dự án

```powershell
cd C:\SchoolManagementSystem\BE
docker compose -f docker-compose.dev.yml up -d rabbitmq minio

$env:SSE_DB_URL = "jdbc:postgresql://localhost:5432/sse_db"
$env:SSE_DB_USER = "postgres"
$env:SSE_DB_PASSWORD = "postgres"
$env:SSE_EVENTS_RABBITMQ_ENABLED = "true"
$env:SSE_EVENTS_LOCAL_LISTENER_ENABLED = "false"
$env:SSE_NOTIFICATION_WORKER_ENABLED = "true"

& "C:\Users\thaid\.cache\sse-tools\apache-maven-3.9.16\bin\mvn.cmd" -pl services/app -am spring-boot:run
```

PowerShell khác:

```powershell
cd C:\SchoolManagementSystem\Web-FE
& npm.cmd run dev
```

## 3. Kiểm tra nguồn GĐ3

1. Đăng nhập `admin / admin@123`.
2. Vào **Xếp thời khóa biểu > Xếp lịch tự động**.
3. Chọn học kỳ và từng phạm vi K10, K11, K12.
4. Khung kiểm tra phải hiện phiên bản nguồn, số lớp và tổng số tiết.
5. Chọn **Toàn trường**. Nút tạo lịch chỉ bật khi cả ba khối đủ điều kiện.
6. Nếu thiếu kế hoạch, phân công, GVCN, phòng hoặc giáo viên quá tải, lỗi phải hiện trước khi chạy bộ giải.

Khi tạo thành công, bản lịch phải lưu `sourcePlanSnapshot` và `sourcePlanSummary`, ví dụ `K10 v4 · K11 v1 · K12 v2`. Sửa hoặc công bố kế hoạch GĐ3 sau đó không được âm thầm đổi nguồn của bản lịch cũ.

## 4. Tạo và phát hành lịch ba khối

1. Chọn **Toàn trường**, đặt tên bản lịch và thời gian giải tối thiểu 60 giây/khối.
2. Bấm **Tạo lịch tự động**.
3. Kiểm tra lần lượt lớp của K10, K11 và K12 trong cùng bản nháp.
4. Tổng số tiết phải bằng tổng `weeklyPeriods` của kế hoạch nguồn cộng hai tiết cố định mỗi lớp.
5. Không được trùng lớp, giáo viên hoặc phòng; phòng chuyên dụng phải đúng loại.
6. Kéo một tiết sang ô trống hoặc đổi phòng. Hệ thống phải kiểm tra lại ngay.
7. Chỉ bản có `0 lỗi bắt buộc` mới được **Khóa & phát hành**.
8. Sau phát hành, giáo viên, học sinh và phụ huynh xem được lịch mới; RabbitMQ gửi thông báo thay đổi lịch.

## 5. Tiến độ giảng dạy

1. Đăng nhập giáo viên và vào **TKB cá nhân > Cập nhật tiến độ bài học**.
2. Chọn lớp, môn và bài học thuộc đúng phiên bản kế hoạch nguồn của lịch đang áp dụng.
3. Lưu số tiết đã hoàn thành, trạng thái và ghi chú.
4. Admin mở **Tiến độ cùng khối** và kiểm tra độ lệch theo ngày học, số tiết và bài học.
5. Màn hình phải ghi phiên bản kế hoạch nguồn; tiến độ của phiên bản cũ không được trộn sai vào phiên bản mới.

## 6. Dạy bù

1. Tạo ngày nghỉ trùng một ngày có tiết trong lịch đã phát hành.
2. Ở bản lịch đã phát hành, chọn khoảng ngày và bấm **Rà soát ngày nghỉ**.
3. Hệ thống đề xuất ngày/tiết gần nhất trong 21 ngày, từ thứ Hai đến thứ Bảy.
4. Ca đề xuất không được trùng lớp, giáo viên, phòng, ngày nghỉ hoặc ca dạy bù đã đề xuất/duyệt.
5. Duyệt đề xuất. Hệ thống kiểm tra xung đột lần cuối và phát sự kiện `academic.timetable.makeup_approved`.

## 7. Smoke và test tự động

```powershell
cd C:\SchoolManagementSystem\BE

& "C:\Users\thaid\.cache\sse-tools\apache-maven-3.9.16\bin\mvn.cmd" `
  -pl services/app -am test

powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\smoke-academic-g4.ps1 `
  -BaseUrl http://127.0.0.1:4000

powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\smoke-timetable-whole-school.ps1 `
  -BaseUrl http://127.0.0.1:4000 `
  -SolveSeconds 120
```

## 8. Tiêu chí đạt

- Số tiết lịch lấy duy nhất từ snapshot kế hoạch GĐ3.
- Phân công không còn quyết định số tiết hoặc loại phòng.
- Một bản lịch toàn trường chứa đủ K10, K11 và K12 cùng phiên bản nguồn rõ ràng.
- Dữ liệu không khả thi bị chặn trước khi chạy bộ giải.
- Tiến độ gắn đúng kế hoạch nguồn và đo đủ ngày, tiết, bài.
- Dạy bù tìm ca gần nhất hợp lệ và kiểm tra lại khi duyệt.
