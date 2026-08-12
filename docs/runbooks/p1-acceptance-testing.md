# Hướng dẫn nghiệm thu P1

## 1. Phạm vi nghiệm thu

- Chat phụ huynh - giáo viên theo thời gian thực bằng SSE có xác thực.
- Hiển thị trạng thái trực tuyến, ngoại tuyến và trạng thái đã đọc tin nhắn.
- Kiểm thử thời khóa biểu toàn trường gồm 30 lớp, ba khối và hai ca học.
- Kiểm thử giáo viên nghỉ, phòng chuyên dụng, lịch dạy bù và phát hành lịch.
- Kiểm thử lịch thi lấy dữ liệu nguồn từ kế hoạch GĐ3.
- API có phiên bản `/api/v1` và cấu trúc phân trang thống nhất.
- Mongo audit có index, thời hạn lưu trữ, đồng bộ lại và PostgreSQL dự phòng.

## 2. Khởi động hạ tầng

Mở PowerShell và chạy:

```powershell
cd C:\SchoolManagementSystem\BE
docker compose -f docker-compose.dev.yml up -d rabbitmq minio minio-init
```

Kiểm tra container:

```powershell
docker ps
```

Kết quả mong đợi: `sse-rabbit` và `sse-minio` ở trạng thái `Up`.

## 3. Chạy toàn bộ test tự động Backend

```powershell
cd C:\SchoolManagementSystem\BE
& "C:\Users\thaid\.cache\sse-tools\apache-maven-3.9.16\bin\mvn.cmd" `
  -pl services/app -am test
```

Kết quả mong đợi:

- Hiển thị `BUILD SUCCESS`.
- `Tests run: 211`.
- `Failures: 0`, `Errors: 0`, `Skipped: 0`.

## 4. Kiểm tra Frontend

```powershell
cd C:\SchoolManagementSystem\Web-FE
npm.cmd run lint
npm.cmd test -- --run
npm.cmd run build
```

Kết quả mong đợi:

- Lint không có lỗi.
- Toàn bộ test FE thành công.
- Build kết thúc thành công và tạo thư mục `dist`.

## 5. Nghiệm thu Chat thời gian thực

1. Mở Chrome bình thường và đăng nhập tài khoản giáo viên.
2. Mở cửa sổ ẩn danh và đăng nhập tài khoản phụ huynh có liên kết với lớp của giáo viên đó.
3. Hai bên mở **Trao đổi** và chọn đúng người cần nhắn tin.
4. Kiểm tra tiêu đề hội thoại hiển thị **Đang trực tuyến** và **Thời gian thực**.
5. Phụ huynh gửi một tin nhắn.
6. Kiểm tra phía giáo viên nhận được tin ngay, không cần tải lại trang.
7. Giữ cuộc hội thoại mở ở phía giáo viên.
8. Kiểm tra phía phụ huynh thấy trạng thái tin nhắn chuyển sang đã đọc.
9. Đóng tab giáo viên.
10. Kiểm tra phía phụ huynh chuyển trạng thái giáo viên thành **Ngoại tuyến**.

Điều kiện đạt:

- Phụ huynh chỉ thấy và nhắn được với GVCN hợp lệ.
- Tin nhắn xuất hiện tức thời ở cả hai phía.
- Trạng thái đã đọc được cập nhật tức thời.
- Mất kết nối SSE không làm treo trang; hệ thống tự chuyển sang kiểm tra định kỳ.

## 6. Nghiệm thu thời khóa biểu GĐ4

Chạy smoke test chức năng:

```powershell
cd C:\SchoolManagementSystem\BE
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\smoke-academic-g4.ps1 `
  -BaseUrl http://127.0.0.1:4000 `
  -SolveSeconds 30
```

Chạy regression test toàn trường:

```powershell
cd C:\SchoolManagementSystem\BE
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\smoke-timetable-whole-school.ps1 `
  -BaseUrl http://127.0.0.1:4000 `
  -SemesterCode HK1 `
  -SolveSeconds 60
```

Kết quả mong đợi:

- Có đúng 30 lớp thuộc khối 10, 11 và 12.
- Xếp đủ `790/790` tiết từ kế hoạch GĐ3.
- Không trùng lớp, giáo viên hoặc phòng trong cùng thời điểm.
- Giáo viên không vượt quá 5 tiết mỗi ngày và có ngày nghỉ theo cấu hình.
- Môn cần phòng chuyên dụng được xếp đúng loại phòng.
- Lớp học đúng ca sáng hoặc chiều đã cấu hình.
- Tiết chào cờ và sinh hoạt lớp được xếp đúng vị trí bắt buộc.
- Lịch hợp lệ có điểm ràng buộc bắt buộc là `0hard`.
- Bản nháp do smoke test tạo ra được dọn sau khi kiểm tra.

## 7. Nghiệm thu lịch thi GĐ5

```powershell
cd C:\SchoolManagementSystem\BE
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\smoke-academic-g5.ps1 `
  -BaseUrl http://127.0.0.1:4000
```

Kết quả mong đợi:

- Chỉ tạo đợt thi khi có kế hoạch kiểm tra hợp lệ từ GĐ3.
- Khoảng ngày quá ngắn bị từ chối trước khi tạo lịch.
- Lịch tự động tạo đủ 12 ca thi từ nguồn thử nghiệm.
- Phòng thi và sức chứa đủ cho danh sách học sinh.
- Giáo viên bận hoặc nghỉ không bị xếp coi thi trong khoảng đó.
- Không xếp một giáo viên hoặc một phòng cho hai ca trùng thời gian.
- Phát hành phiên bản 1 thành công.
- Phiên bản không thay đổi không được phát hành lại.
- Phiên bản điều chỉnh lưu được khác biệt và lưu trữ phiên bản cũ.
- Học sinh, phụ huynh và giáo viên chỉ xem lịch đã phát hành thuộc phạm vi của mình.
- Thu hồi, công bố lại, tạo thủ công và xóa bản nháp hoạt động đúng.

## 8. Nghiệm thu API dành cho Mobile

Đăng nhập bằng endpoint:

```text
POST http://127.0.0.1:4000/api/v1/auth/login
```

Sau khi lấy access token, gọi lần lượt:

```text
GET /api/v1/notifications/page?page=0&size=20
GET /api/v1/admin/notification-deliveries/page?page=0&size=50
GET /api/v1/audit-logs/page?page=0&size=50
```

Kết quả mong đợi:

- Response có `items`, `page`, `size`, `totalElements`, `totalPages`, `first`, `last`.
- Header response có `X-API-Version: v1`.
- `page` không được nhỏ hơn 0.
- `size` chỉ được nằm trong khoảng 1 đến 200.
- Endpoint cũ không có `/api/v1` vẫn hoạt động để tương thích với FE hiện tại.

## 9. Nghiệm thu Postman toàn hệ thống

Khi Backend đang chạy, tạo lại collection:

```powershell
cd C:\SchoolManagementSystem\BE
node .\scripts\export-postman-from-openapi.mjs
```

1. Import file `docs/postman/SSE-FULL.postman_collection.json` vào Postman.
2. Chạy request đăng nhập trong nhóm Auth.
3. Lưu access token trả về vào biến collection `accessToken`.
4. Chạy các nhóm API cần nghiệm thu.

Kết quả mong đợi: collection có khoảng 374 request thuộc 30 nhóm chức năng.

## 10. Nghiệm thu Mongo audit

Khởi động Mongo khi Docker tải được image:

```powershell
cd C:\SchoolManagementSystem\BE
docker compose -f docker-compose.dev.yml up -d mongo
$env:SSE_AUDIT_MONGO_ENABLED = "true"
$env:SSE_AUDIT_MONGO_RETENTION_DAYS = "365"
```

Khởi động lại Backend rồi gọi bằng tài khoản Admin:

```text
GET  /api/v1/audit-logs/mongo/status
POST /api/v1/audit-logs/mongo/sync
```

Kết quả mong đợi:

- Status trả `enabled=true` và `connected=true`.
- Lệnh sync trả về số bản ghi đã đồng bộ.
- Gửi sync nhiều lần không tạo bản ghi audit trùng.
- Khi Mongo tắt, nghiệp vụ chính vẫn hoạt động và audit PostgreSQL vẫn được lưu.

Kiểm tra index Mongo:

```powershell
docker exec sse-mongo mongosh --quiet `
  "mongodb://sse:sse_dev@localhost:27017/sse_audit?authSource=admin" `
  --eval "db.audit_logs.getIndexes()"
```

Các index bắt buộc:

- `module_created_at`.
- `action_created_at`.
- `actor_created_at`.
- `ttl_created_at`, tự xóa dữ liệu quá thời hạn lưu trữ.

## 11. Tiêu chí kết luận P1

P1 được nghiệm thu đầy đủ khi:

- Backend và Frontend vượt qua toàn bộ test tự động.
- Chat nhận tin, cập nhật đã đọc và trạng thái trực tuyến tức thời.
- GĐ4 xếp đủ lịch toàn trường mà không có xung đột bắt buộc.
- GĐ5 tạo và phát hành lịch thi đúng nguồn GĐ3.
- API `/api/v1` và pagination trả đúng cấu trúc.
- Postman chạy được các nhóm API bằng access token.
- Mongo audit kết nối thật, có đủ index, TTL và fallback PostgreSQL.
