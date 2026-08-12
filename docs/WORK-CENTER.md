# Trung tâm công việc

## Phạm vi

Trung tâm công việc là luồng điều phối chung cho Quản trị viên, Giáo vụ, Kế toán và Giáo viên. Dữ liệu được lưu trong PostgreSQL; Mobile chưa có màn hình riêng nhưng có thể dùng các API `/work-center` sau này.

## Vòng đời

`NEW → ACCEPTED → IN_PROGRESS → WAITING_CONFIRMATION → COMPLETED`.

- Người nhận có thể từ chối và phải nhập lý do.
- Người giao hoặc Admin xác nhận công việc thủ công. Công việc tự động có thể được quản lý bộ phận xác nhận.
- Công việc quá hạn chuyển sang `OVERDUE`, lưu lịch sử và được escalation.
- Checklist tự tính tiến độ. Trao đổi, tệp, trì hoãn và mọi chuyển trạng thái đều có lịch sử/audit.

## Phân quyền

| Vai trò | Phạm vi xem | Giao việc |
| --- | --- | --- |
| Admin | Toàn trường | Admin, Giáo vụ, Kế toán, Giáo viên |
| Giáo vụ | Cá nhân và bộ phận Giáo vụ | Giáo vụ, Giáo viên; không giao tài chính |
| Kế toán | Cá nhân và bộ phận Kế toán | Chỉ Kế toán và module Tài chính |
| Giáo viên | Chỉ công việc gán trực tiếp/cá nhân | Không giao cho người khác |

Tệp đính kèm dùng kho `/files`; chỉ người nhìn thấy công việc mới tải được tệp.

## Tự động hóa

Scheduler chạy khi Backend khởi động và mỗi 15 phút. `source_key` là khóa duy nhất nên chạy lại không sinh trùng. Các quy tắc hiện có:

- Học sinh mới chờ phân lớp, lớp thiếu GVCN.
- Thời khóa biểu chưa đủ, kỳ thi còn chuẩn bị.
- Hóa đơn quá hạn, đợt thu còn bản nháp.
- Bài nộp chưa chấm theo từng giáo viên.

Khi dữ liệu nguồn đã hoàn tất, nhiệm vụ liên quan được tự đóng và ghi lịch sử.

## Nhắc việc và escalation

- Nhắc trước hạn 3 ngày, 1 ngày, đúng hạn và quá hạn.
- Mỗi loại chỉ phát một lần/ngày; hỗ trợ `snooze`.
- Gửi tối đa ba lần khi lỗi. Quá hạn gửi thêm cho người giao và Admin.
- Notification có deep-link đúng vai trò, đồng bộ SSE, và dùng delivery log hiện có để theo dõi gửi/nhận/đọc.

## API chính

- `GET/POST /work-center/tasks`
- `GET/PUT /work-center/tasks/{id}`
- `POST /work-center/tasks/{id}/transitions`
- comments, checklist, attachments, snooze dưới `/work-center/tasks/{id}`
- `GET /work-center/stats`, `/assignees`, `/export`

Tìm kiếm, bộ lọc, phân trang và sắp xếp chạy ở Backend. Web đồng bộ các tham số vào URL.

## Nghiệm thu

1. Đăng nhập từng vai trò và xác nhận chỉ thấy đúng phạm vi.
2. Admin giao việc cho Giáo vụ; Giáo vụ tiếp nhận, thực hiện, gửi xác nhận; Admin hoàn thành.
3. Kế toán không xem/sửa việc học vụ; Giáo vụ không tạo việc tài chính.
4. Tải tệp lên, mở bằng người tham gia và từ chối bằng vai trò ngoài phạm vi.
5. Chạy automation hai lần và xác nhận mỗi `source_key` chỉ có một bản ghi.
6. Đặt hạn T-3/T-1/hôm nay/quá hạn và xác nhận reminder không trùng, có escalation.
7. Kiểm tra dashboard, CSV, dark mode, 390/768/1366/1920 px và deep-link từ chuông thông báo.

## Triển khai và rollback

Migration `V78__operation_work_center.sql` chỉ mở rộng bảng cũ và tạo bảng/index mới. Trước production cần backup PostgreSQL và chạy migration trên staging.

Rollback ứng dụng: triển khai lại image Backend/Web trước V78; các cột/bảng mới không ảnh hưởng mã cũ. Chỉ xóa cấu trúc sau khi đã xuất dữ liệu và chắc chắn không quay lại phiên bản mới:

```sql
DROP TABLE IF EXISTS operation_task_reminders;
DROP TABLE IF EXISTS operation_task_attachments;
DROP TABLE IF EXISTS operation_task_history;
DROP TABLE IF EXISTS operation_task_checklist_items;
-- Không xóa operation_tasks vì đây là bảng đã tồn tại trước V78.
```

Không sửa trực tiếp `flyway_schema_history`. Nếu cần rollback cấu trúc đầy đủ, phục hồi snapshot/backup trước migration.
