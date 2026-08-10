# Hướng dẫn nghiệm thu Giai đoạn 6

## 1. Khởi động môi trường

Lệnh khuyến nghị trên Windows (tự kiểm tra cổng 4000, build sạch và chạy RabbitMQ/MinIO):

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-backend-dev.ps1
```

Nếu backend cũ vẫn giữ cổng 4000, cho phép script dừng đúng tiến trình SSE rồi chạy lại:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-backend-dev.ps1 -StopExisting
```

```powershell
cd C:\SchoolManagementSystem\BE
docker compose -f docker-compose.dev.yml up -d rabbitmq minio minio-init mongo

$env:SSE_DB_URL = "jdbc:postgresql://localhost:5432/sse_db"
$env:SSE_DB_USER = "postgres"
$env:SSE_DB_PASSWORD = "postgres"
$env:SSE_EVENTS_RABBITMQ_ENABLED = "true"
$env:SSE_EVENTS_LOCAL_LISTENER_ENABLED = "false"
$env:SSE_NOTIFICATION_WORKER_ENABLED = "true"

& "C:\Users\thaid\.cache\sse-tools\apache-maven-3.9.16\bin\mvn.cmd" -pl services/app -am package -DskipTests
& "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot\bin\java.exe" -jar .\services\app\target\sse-app.jar
```

Mở terminal thứ hai:

```powershell
cd C:\SchoolManagementSystem\Web-FE
cmd /c npm run dev -- --host 127.0.0.1
```

Web: `http://127.0.0.1:5173`. Swagger: `http://127.0.0.1:4000/swagger-ui/index.html`.

## 2. Cấu hình điểm theo môn và học kỳ

1. Đăng nhập Admin, mở `Khảo thí & lịch thi > Loại điểm`.
2. Chọn môn và học kỳ, đặt số đầu điểm bắt buộc, hệ số và trạng thái áp dụng.
3. Đăng nhập giáo viên được phân công, mở `Bảng điểm`, chọn đúng lớp, môn và học kỳ.
4. Kiểm tra bảng điểm sinh đúng số cột, nhập đủ điểm từ `0` đến `10`.
5. Thử nhập `-1`, `10.1`, sai lớp hoặc sai môn: hệ thống phải từ chối.
6. Sửa điểm đã có: bắt buộc nhập lý do và có change log.
7. Dashboard giáo viên phải hiện số học sinh còn thiếu đầu điểm thực tế.

## 3. Đơn xin phép nghỉ và duyệt nghỉ

1. Giáo viên điểm danh học sinh là `Đi muộn` hoặc `Vắng không phép`.
2. Học sinh hoặc phụ huynh mở chuyên cần, chọn bản ghi và gửi lý do xin phép.
3. Phụ huynh chỉ thấy dữ liệu của con đã chọn; truy cập học sinh khác phải trả `403`.
4. Giáo viên chỉ thấy đơn thuộc lớp/môn/tiết mình phụ trách.
5. Giáo viên duyệt: bản ghi chuyển thành `Vắng có phép`; từ chối: trạng thái điểm danh giữ nguyên.
6. Thử duyệt lại đơn đã xử lý hoặc giáo viên ngoài phạm vi: phải bị từ chối.

## 4. Bài tập nâng cao

1. Giáo viên tạo bài tập có tên và file đề, sau đó phát hành.
2. Học sinh bắt buộc nộp file; nộp lần hai tạo phiên bản mới, không ghi đè file cũ.
3. Giáo viên mở lịch sử phiên bản, tải từng file và chấm điểm `0..10`.
4. Chọn nhiều bài nộp và chấm hàng loạt; dữ liệu không hợp lệ phải làm cả batch thất bại.
5. Giáo viên yêu cầu nộp lại kèm lý do; học sinh nhận thông báo và được mở lại nút chọn file.
6. Phụ huynh chỉ xem phiên bản và yêu cầu nộp lại của con mình.

## 5. Ngoại khóa có phí

1. Admin tạo hoạt động có phí và còn chỗ.
2. Học sinh hoặc phụ huynh đăng ký cho con.
3. Hệ thống tạo đúng một đợt thu loại `ACTIVITY` và một invoice cho học sinh.
4. Đăng ký lặp không được tạo invoice thứ hai.
5. Hủy đăng ký trước khi thu tiền: invoice và đợt thu liên quan được hủy.
6. Người không phải học sinh, phụ huynh của học sinh hoặc Admin không được hủy.

## 6. Chat phụ huynh và GVCN

1. Phụ huynh mở `Giao tiếp`: danh bạ chỉ có GVCN của các con.
2. Gửi tin, đăng nhập GVCN và mở hội thoại: tin chuyển sang đã đọc.
3. GVCN được nhắn phụ huynh/học sinh lớp chủ nhiệm; giáo viên bộ môn chỉ nhắn học sinh lớp được phân công.
4. Thử gửi trực tiếp tới tài khoản ngoài phạm vi: API phải trả `403`.

## 7. SendGrid, FCM, retry và delivery log

Chế độ mặc định là `mock`, dùng để kiểm tra retry và log mà không gửi ra ngoài. Để test provider thật:

```powershell
$env:SSE_NOTIFICATION_PROVIDER_MODE = "real"
$env:SSE_SENDGRID_API_KEY = "<SENDGRID_API_KEY>"
$env:SSE_SENDGRID_FROM_EMAIL = "no-reply@your-verified-domain.com"
$env:SSE_FCM_PROJECT_ID = "<FIREBASE_PROJECT_ID>"
$env:SSE_FCM_ACCESS_TOKEN = "<OAUTH2_ACCESS_TOKEN>"
```

1. Người dùng bật/tắt riêng `Trong ứng dụng`, `Email`, `Push`.
2. Phát sinh sự kiện điểm danh, điểm, bài tập hoặc invoice.
3. Admin mở `Trung tâm thông báo`, xem tổng số, tỷ lệ lỗi và delivery log.
4. Provider lỗi phải có tối đa ba lần thử, thời gian backoff, mã lỗi và response.
5. Bấm `Thử gửi lại`; lần thử mới phải được lưu, không xóa lịch sử cũ.

## 8. Báo cáo học vụ Excel/PDF

1. Admin mở `Báo cáo & thống kê > Báo cáo học vụ`.
2. Lọc theo năm học, học kỳ, khối, lớp và môn.
3. Kiểm tra tổng học sinh, điểm trung bình, chuyên cần, bài đã nộp và đã chấm.
4. Xuất Excel: file có ba sheet tổng hợp, học sinh và môn học.
5. Xuất PDF: tiếng Việt hiển thị đúng, số liệu khớp bộ lọc.
6. Giáo viên gọi endpoint báo cáo toàn trường phải nhận `403`.
7. Mỗi lần xuất phải có bản ghi `EXPORT` trong lịch sử hệ thống.

## 9. Mongo audit, Flyway, OpenAPI, Postman và tải

Mongo audit:

```powershell
$env:SSE_AUDIT_MONGO_ENABLED = "true"
$env:SSE_AUDIT_MONGO_URI = "mongodb://sse:sse_dev@localhost:27017/?authSource=admin"
```

Khi Mongo không sẵn sàng, nghiệp vụ vẫn chạy và audit PostgreSQL vẫn được lưu. Khi Mongo hoạt động, hành động mới phải xuất hiện trong database `sse_audit`, collection `audit_logs`.

Flyway phải báo schema ở phiên bản `46`. OpenAPI có tại `/v3/api-docs`; collection Postman nằm ở `docs/postman/SSE-GD6.postman_collection.json`.

Chạy toàn bộ test:

```powershell
cd C:\SchoolManagementSystem\BE
& "C:\Users\thaid\.cache\sse-tools\apache-maven-3.9.16\bin\mvn.cmd" -pl services/app -am test
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\smoke-app.ps1 -BaseUrl http://127.0.0.1:4000
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\smoke-gd6.ps1 -BaseUrl http://127.0.0.1:4000

$env:SSE_LOAD_CONCURRENCY = "20"
$env:SSE_LOAD_ITERATIONS = "10"
node .\scripts\load\gd6-load.mjs
```

## 10. Shortcut theo vai trò

1. Admin thấy lớp thiếu GVCN, lớp thiếu phân công, xung đột TKB, học sinh thiếu điểm, bài chưa chấm, hóa đơn quá hạn và notification lỗi.
2. Giáo viên thấy tiết chưa điểm danh, bài chưa chấm và học sinh còn thiếu đầu điểm.
3. Học sinh thấy bài quá hạn chưa nộp.
4. Phụ huynh thấy hóa đơn quá hạn và cảnh báo chuyên cần.
5. Bấm shortcut phải chuyển đúng màn nghiệp vụ; bộ lọc được lưu theo shortcut để màn đích áp dụng.
