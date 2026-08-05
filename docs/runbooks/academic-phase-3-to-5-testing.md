# Nghiệm thu kết nối GĐ3 -> GĐ5

## Nguyên tắc dữ liệu

- GĐ3 lưu kế hoạch kiểm tra: học kỳ, môn, khối, loại kiểm tra, tuần dự kiến và thời lượng.
- Kế hoạch GĐ3 phải ở trạng thái `PUBLISHED` hoặc `LOCKED` mới được dùng.
- GĐ5 không cho nhập lại môn, khối hoặc thời lượng.
- GĐ5 chỉ lưu ngày thi, giờ bắt đầu, phòng, danh sách học sinh, giám thị chính/dự phòng và ghi chú vận hành.
- Mỗi ca thi lưu `source_assessment_plan_id`, `source_training_plan_id` và phiên bản kế hoạch nguồn.

## Chuẩn bị ở GĐ3

1. Đăng nhập Admin và mở **Cơ cấu đào tạo > Kế hoạch giáo dục năm học**.
2. Chọn lần lượt Khối 10, 11, 12 và đúng học kỳ.
3. Trong bước **Kế hoạch kiểm tra**, tạo kế hoạch `Giữa kỳ`, `Cuối kỳ` hoặc `Thi lại` cho các môn cần tổ chức thi.
4. Nhập tuần dự kiến và thời lượng riêng của từng môn.
5. Gửi duyệt, phê duyệt và công bố kế hoạch giáo dục.

Kỳ vọng: kế hoạch chưa công bố không được GĐ5 sử dụng.

## Tạo đợt thi ở GĐ5

1. Mở **Khảo thí & lịch thi > Lịch thi & coi thi**.
2. Bấm **Tạo đợt thi** và chọn năm học, học kỳ, loại kỳ thi, khối và khoảng ngày.
3. Quan sát khối kiểm tra ngay trong biểu mẫu.

Kỳ vọng:

- Nếu một khối chưa có kế hoạch kiểm tra GĐ3 đã công bố, biểu mẫu nêu rõ khối và loại kế hoạch còn thiếu; nút tạo bị khóa.
- Nếu đủ nguồn, biểu mẫu báo hợp lệ và cho tạo đợt thi.
- Chỉ có ba loại gắn được với GĐ3: giữa kỳ, cuối kỳ và thi lại.

## Tạo lịch tự động

1. Chọn phiên bản nháp và mở tab **Lịch thi**.
2. Kiểm tra danh sách **Đầu vào đã công bố từ GĐ3**.
3. Đối chiếu tên môn, khối, tuần, thời lượng và phiên bản nguồn.
4. Bấm **Tạo lịch tự động**.

Kỳ vọng:

- Số ca thi bằng số kế hoạch nguồn của các khối đã chọn.
- Thời lượng từng ca giống tuyệt đối với GĐ3 và không có ô sửa tại GĐ5.
- Hệ thống tự xếp ngày/giờ, phòng, học sinh và giám thị; không trùng tài nguyên trong cùng ca.
- Validation báo lỗi nếu thiếu một kế hoạch nguồn hoặc còn ca dữ liệu cũ chưa liên kết GĐ3.

## Chỉnh thủ công

1. Bấm **Thêm ca thi thủ công**.
2. Chỉ chọn trong danh sách kế hoạch GĐ3 chưa được xếp.
3. Chọn ngày, giờ và ghi chú; lưu ca thi.
4. Bấm bút chì trên ca đã tạo.

Kỳ vọng: màn sửa chỉ cho đổi ngày, giờ và ghi chú. Môn, khối, thời lượng và nguồn GĐ3 chỉ đọc.

## Phát hành và người dùng cuối

1. Bấm **Kiểm tra**, xử lý hết lỗi bắt buộc rồi **Phát hành**.
2. Học sinh xem ngày, môn, giờ, phòng và số báo danh.
3. Phụ huynh chọn từng con và xem đúng lịch của con đó.
4. Giáo viên xem đúng ca coi thi chính/dự phòng.

Kỳ vọng: lịch chỉ xuất hiện sau khi phát hành; RabbitMQ gửi thông báo và audit lưu hành động.

## Kiểm thử tự động

```powershell
cd C:\SchoolManagementSystem\BE

& "C:\Users\thaid\.cache\sse-tools\apache-maven-3.9.16\bin\mvn.cmd" `
  -pl services/app -am test

powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\smoke-academic-g5.ps1 `
  -BaseUrl http://127.0.0.1:4000
```

Smoke test tự tìm các khối đã có nguồn `FINAL` công bố ở GĐ3, không tự tạo môn hoặc thời lượng độc lập.
