# Nghiệm thu 8 nhánh backend SSE

Tài liệu này kiểm tra riêng backend. Smoke mặc định chỉ đọc dữ liệu và không công bố,
hoàn tác, chấm điểm, thu tiền hoặc sửa database.

## 1. Chuẩn bị

```powershell
cd C:\SchoolManagementSystem\BE
docker compose -f docker-compose.dev.yml up -d rabbitmq minio

$env:SSE_DB_URL = "jdbc:postgresql://localhost:5432/sse_db"
$env:SSE_DB_USER = "postgres"
$env:SSE_DB_PASSWORD = "postgres"

& "C:\Users\thaid\.cache\sse-tools\apache-maven-3.9.16\bin\mvn.cmd" `
  -pl services/app -am clean package

& "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot\bin\java.exe" `
  -jar .\services\app\target\sse-app.jar
```

Ở cửa sổ PowerShell khác:

```powershell
cd C:\SchoolManagementSystem\BE
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\smoke-backend-eight-branches.ps1 `
  -BaseUrl http://127.0.0.1:4000
```

Kết quả đạt là tất cả dòng chính có `[OK]`. `[SKIP]` chỉ xuất hiện khi database
không có fixture phù hợp, không phải lỗi API.

## 2. Kết quả cuối năm

API chính:

- `GET /academic-year-summaries/preview`
- `POST /academic-year-summaries/{yearId}/classes/{classId}/finalize`
- `POST /year-results/{yearId}/classes/{classId}/publish`
- `POST /year-results/{yearId}/classes/{classId}/withdraw`
- `POST /year-results/{yearId}/publish-batch`
- `POST /year-results/{yearId}/withdraw-batch`
- `GET /year-results/{yearId}/classes/{classId}/history`

Luồng UAT:

1. Chốt đủ học sinh của lớp.
2. Công bố lần đầu; học sinh/phụ huynh nhìn thấy kết quả.
3. Thu hồi với lý do; kết quả biến mất khỏi màn học sinh/phụ huynh.
4. Sửa dữ liệu, công bố lại với lý do; phiên bản tăng từ `1` lên `2`.
5. Xem history phải có `PUBLISH`, `WITHDRAW`, `REPUBLISH`.
6. Gửi lại cùng thao tác không được tạo thêm phiên bản ngoài ý muốn.

Vì sao hoạt động: trạng thái hiện tại nằm ở `year_result_publications`; mỗi hành
động còn được append vào `year_result_publication_history`, nên vừa truy vấn nhanh
được trạng thái mới nhất vừa giữ timeline không ghi đè.

## 3. Lên lớp và hoàn tác

API chính:

- `POST /student-promotions/preview`
- `POST /student-promotions/execute`
- `POST /student-promotions/undo`
- `POST /student-promotions/progression-status`
- `GET /student-promotions/enrollments`

Luồng UAT:

1. Preview lớp nguồn đã chốt và năm đích đang `ACTIVE`.
2. Chọn lớp đích; lớp vượt `maxStudents` phải bị chặn.
3. Execute; tài khoản học sinh và enrollment cùng chuyển sang lớp đích.
4. Thu hồi công bố kết quả trước, sau đó Undo với lý do.
5. Học sinh trở lại lớp nguồn, enrollment đích chuyển `REVERTED`.
6. Thử `TRANSFERRED`, `RESERVED`, `WITHDRAWN`, rồi chuyển lại `ACTIVE`.

Vì sao hoạt động: enrollment theo năm học có unique key, preview tính sức chứa
theo tổng học sinh dự kiến, execute/undo chạy transaction và ghi audit.

## 4. Điểm, hạnh kiểm và chuyên cần

API chính:

- `GET /grades/completeness`
- `POST /grades/bulk`
- `GET /grades/{id}/change-logs`
- `PUT /academic-year-summaries/.../students/{studentId}`
- `GET /students/{studentId}/attendance/summary`
- `POST /attendance/{recordId}/excuse-requests`
- `POST /attendance/excuse-requests/{id}/review`

Luồng UAT:

1. Kiểm tra completeness theo lớp, môn, học kỳ.
2. Nhập đủ đầu điểm; điểm ngoài `0..10` phải lỗi.
3. Sửa điểm đã có bắt buộc lý do và phải xuất hiện trong change log.
4. GVCN nhập hạnh kiểm; Admin chốt rồi thử sửa điểm, hệ thống phải chặn.
5. Điểm danh `LATE` kèm số phút, phụ huynh gửi đơn xin phép.
6. Giáo viên/Admin duyệt hoặc từ chối; thống kê tháng/học kỳ phải thay đổi đúng.
7. Vắng lặp lại ở các mốc cảnh báo phải tạo event thông báo.

Vì sao hoạt động: điểm và điểm danh có bảng lịch sử riêng; kết quả chốt tạo khóa
theo lớp/học kỳ; đơn xin phép có trạng thái và người duyệt độc lập.

## 5. Bài tập

API chính:

- `POST /assignments/{id}/submit`
- `POST /submissions/{id}/request-resubmission`
- `GET /submissions/{id}/versions`
- `POST /submissions/batch-grade`
- `POST /assignments/{id}/remind-due`
- `GET /assignments/{id}/submissions/export`

Luồng UAT:

1. Giáo viên tạo bài đúng lớp/môn được phân công và phát hành.
2. Học sinh nộp file; không có file phải bị chặn.
3. Giáo viên chấm, sau đó yêu cầu nộp lại với lý do.
4. Học sinh nộp phiên bản mới; history phải còn cả file cũ và file mới.
5. Chấm hàng loạt và xuất Excel danh sách bài nộp.
6. Chạy nhắc hạn; chỉ học sinh chưa nộp nhận thông báo.

Vì sao hoạt động: metadata mỗi lần nộp được append vào
`assignment_submission_versions`; yêu cầu nộp lại là một workflow riêng, còn file
thật lưu MinIO bằng presigned URL.

## 6. Tài chính

API chính:

- `POST /finance/reminders/run`
- `POST /finance/bank-statements/import`
- `POST /payments/{id}/receipt/void`
- `POST /payments/{id}/receipt/reissue`
- `POST /finance/reconciliations`
- `POST /payments/{id}/refunds`

Luồng UAT:

1. Chạy reminder; cùng hóa đơn không bị nhắc lặp trong ngày.
2. Import CSV/XLSX sao kê MB hai lần; lần hai phải báo duplicate.
3. Dòng đúng mã hóa đơn và số tiền là `MATCHED`; lệch tiền là `MISMATCH`.
4. Admin xác nhận thu, biên nhận PDF được sinh trên MinIO.
5. Thu hồi biên nhận bắt buộc lý do; cấp lại tăng revision và giữ `previousFileId`.
6. Tạo refund bằng Admin 1, phê duyệt bằng Admin 2.
7. Refund vượt số đã thu hoặc tự duyệt phải bị chặn.
8. Đối soát kiểm tra tổng thu, hoàn tiền và doanh thu ròng.

Vì sao hoạt động: payment callback có khóa và idempotency; receipt/refund giữ
snapshot; sao kê có unique `(bankCode, transactionReference)`; refund bắt buộc
người yêu cầu khác người phê duyệt.

## 7. Notification

API chính:

- `GET/PUT /me/notification-preferences`
- `POST /notifications/groups/{groupKey}/read`
- `GET /admin/notification-operations/summary`
- `GET /admin/notification-deliveries`
- `GET /admin/notifications/failed`
- `POST /admin/notifications/{id}/retry`

Luồng UAT:

1. Tắt `IN_APP` cho một loại, phát event và xác nhận không tạo thông báo loại đó.
2. Thông báo mới phải có `deepLink` và `groupKey`.
3. Đọc theo group; unread count của nhóm phải về `0`.
4. Admin xem summary, lịch sử từng lần gửi và retry bản ghi `FAILED`.
5. Tắt worker rồi phát event: core API vẫn chạy; bật worker lại để queue drain.

Vì sao hoạt động: RabbitMQ tách producer khỏi notification worker; preference
được kiểm tra trước khi ghi inbox; delivery log append theo từng attempt.

## 8. Vận hành và bảo mật

API/script chính:

- `GET /health/ready`
- `GET /admin/operations/health`
- `GET/DELETE /me/sessions`
- `GET/DELETE /me/devices`
- `scripts/backup-sse.ps1`
- `scripts/restore-sse.ps1`

Luồng UAT:

1. Health phải báo riêng PostgreSQL, RabbitMQ, MinIO.
2. Dừng RabbitMQ hoặc MinIO; readiness phải trả `503 DEGRADED`.
3. Login sai 5 lần trong 15 phút; lần tiếp theo bị rate limit.
4. Xem session, revoke một session rồi refresh token tương ứng phải thất bại.
5. Đăng ký và vô hiệu hóa device token.
6. Chạy backup; kiểm tra dump DB, dữ liệu MinIO và manifest.
7. Chỉ test restore trên database/staging riêng với xác nhận `RESTORE`.
8. Push/PR chạy GitHub Actions `clean verify` và lưu surefire report.

## 9. Test tự động

```powershell
cd C:\SchoolManagementSystem\BE
& "C:\Users\thaid\.cache\sse-tools\apache-maven-3.9.16\bin\mvn.cmd" `
  -pl services/app -am test
```

Đạt khi Maven in `BUILD SUCCESS`, `Failures: 0`, `Errors: 0`.

