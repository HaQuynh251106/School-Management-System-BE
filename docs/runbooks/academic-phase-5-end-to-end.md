# Luồng nghiệm thu Giai đoạn 5 - Lịch thi và coi thi

GĐ5 không tự khai báo lại môn, khối, loại bài kiểm tra hoặc thời lượng. Các dữ liệu này phải lấy từ phiên bản kế hoạch giáo dục GĐ3 đã công bố hoặc khóa. GĐ5 chỉ xếp ngày giờ thực tế, phòng, số báo danh, học sinh và giám thị.

## 1. Khởi động

Backend:

```powershell
cd C:\SchoolManagementSystem\BE
docker compose -f docker-compose.dev.yml up -d rabbitmq minio

$env:SSE_DB_URL = "jdbc:postgresql://localhost:5432/sse_db"
$env:SSE_DB_USER = "postgres"
$env:SSE_DB_PASSWORD = "postgres"
$env:SSE_EVENTS_RABBITMQ_ENABLED = "true"
$env:SSE_EVENTS_LOCAL_LISTENER_ENABLED = "false"
$env:SSE_NOTIFICATION_WORKER_ENABLED = "true"

& "C:\Users\thaid\.cache\sse-tools\apache-maven-3.9.16\bin\mvn.cmd" -pl services/app -am package -DskipTests
& "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot\bin\java.exe" -jar .\services\app\target\sse-app.jar
```

Frontend:

```powershell
cd C:\SchoolManagementSystem\Web-FE
npm.cmd run dev
```

Mở `http://127.0.0.1:5173`, đăng nhập `admin / admin@123`.

## 2. Kiểm tra nguồn GĐ3

1. Vào **Cơ cấu đào tạo > Kế hoạch đào tạo**.
2. Chọn năm học, khối và phiên bản đã công bố.
3. Kiểm tra mỗi môn thi có loại kiểm tra, tuần dự kiến và thời lượng.
4. Quay lại **Khảo thí & lịch thi**.

Kỳ vọng:

- Hiển thị chương trình đang áp dụng.
- Hiển thị đúng phiên bản kế hoạch nguồn của từng khối.
- Danh sách nguồn ghi rõ môn, khối, loại kiểm tra, tuần, khoảng ngày quy đổi và thời lượng.
- Chào cờ và Sinh hoạt lớp không xuất hiện trong nguồn thi.
- Kế hoạch nháp hoặc yêu cầu chỉnh sửa không được dùng làm nguồn.

## 3. Tạo đợt thi

1. Bấm **Tạo đợt thi**.
2. Chọn học kỳ, loại kỳ thi và khối.
3. Quan sát khung **Thời gian gợi ý từ kế hoạch GĐ3**.
4. Bấm **Dùng thời gian gợi ý** hoặc nhập khoảng khác có giao với các tuần nguồn.
5. Nhập mã và tên duy nhất rồi tạo.

Kỳ vọng trước khi lưu:

- Thiếu mã, tên, khối hoặc học kỳ: hiện lỗi ngay trong form.
- Ngày ngoài học kỳ, ngày kết thúc trước ngày bắt đầu hoặc chỉ gồm ngày nghỉ: bị chặn.
- Khoảng ngày không bao phủ tuần GĐ3 hoặc không đủ số ca: bị chặn trước khi tạo.
- Thiếu phòng hoặc thiếu giáo viên: bị chặn và nêu rõ tài nguyên thiếu.

Kỳ vọng sau khi lưu: đợt thi ở trạng thái **Bản nháp**, tự có phiên bản v1 và mỗi phiên bản ghi người tạo, thời gian tạo, lý do.

## 4. Khai báo giáo viên bận hoặc nghỉ

1. Mở tab **GV bận/nghỉ**.
2. Chọn giáo viên và loại: nghỉ phép, công tác, bận chuyên môn, nghỉ ốm hoặc không tham gia coi thi.
3. Chọn từ ngày đến ngày; có thể nghỉ cả ngày hoặc nhập khoảng giờ.
4. Bấm **Ghi nhận**.
5. Dùng nút bút chì để sửa và nút thùng rác để xóa.

Kỳ vọng:

- Không cho nhập ngoài khoảng đợt thi hoặc giờ kết thúc trước giờ bắt đầu.
- Bảng hiển thị người tạo, thời gian tạo và số ca đang bị ảnh hưởng.
- Nếu thêm lịch bận sau khi đã xếp lịch, phiên bản mất trạng thái kiểm tra hợp lệ và yêu cầu kiểm tra/tạo lại.
- Giáo viên chỉ bị loại khỏi đúng khoảng bận, không bị loại khỏi toàn bộ đợt thi.

## 5. Tạo lịch tự động

1. Mở tab **Lịch thi**.
2. Đọc từng thẻ nguồn GĐ3 và trạng thái sẵn sàng.
3. Bấm **Tạo lịch tự động**.
4. Nếu đã có lịch trong bản nháp, xác nhận **Xếp lại toàn bộ**.
5. Mở từng ca thi để xem phòng, học sinh, số báo danh và giám thị.

Kỳ vọng:

- Mỗi cặp môn/khối/tuần nguồn sinh đúng một ca.
- Ca chỉ được xếp trong tuần đã quy đổi từ GĐ3; ngày nghỉ trường được loại bỏ hoặc chuyển sang cửa sổ hợp lệ kế tiếp.
- Thời lượng đúng snapshot GĐ3 và không có ô sửa tùy ý ở GĐ5.
- Số báo danh liên tục, không nhảy số trong cùng danh sách.
- Không trùng phòng, học sinh hoặc giám thị ở cùng thời gian, kể cả với đợt thi khác đã phát hành.
- Giám thị chính và dự phòng khác nhau; giáo viên bận không được xếp vào ca bị ảnh hưởng.
- Các phòng và giáo viên được phân bổ luân phiên thay vì luôn dùng một nhóm đầu danh sách.
- Không tìm được nghiệm hợp lệ thì không lưu lịch lỗi; thông báo nêu môn/tuần và hướng xử lý.

## 6. Điều chỉnh thủ công

1. Bấm **Thêm ca thi thủ công** để chọn một nguồn GĐ3 chưa được xếp.
2. Dùng nút bút chì để đổi ngày hoặc giờ của ca.
3. Mở chi tiết ca, sửa phòng và giám thị khi cần.
4. Nếu xếp ngoài tuần GĐ3, nhập lý do sai lệch bắt buộc.

Kỳ vọng:

- Không cho nhập lại môn, khối hoặc thời lượng ngoài nguồn GĐ3.
- Thay đổi ngoài tuần không có lý do bị chặn.
- Mọi chỉnh sửa làm kết quả kiểm tra trước đó hết hiệu lực.
- Ca hiển thị snapshot nguồn và trạng thái **Nguồn hiện hành**, **Nguồn đã thay đổi** hoặc **Dữ liệu cũ**.

## 7. Kiểm tra và phát hành

1. Bấm **Kiểm tra**.
2. Mở từng nhóm lỗi/cảnh báo; dùng **Đi tới ca thi** để cuộn đúng vị trí.
3. Sửa hết lỗi bắt buộc rồi bấm **Kiểm tra** lại.
4. Bấm **Phát hành** và xác nhận.

Kỳ vọng:

- Không phát hành khi còn lỗi bắt buộc.
- Không phát hành nếu lịch đã thay đổi sau lần kiểm tra cuối.
- Cảnh báo có thể không chặn nhưng phải hiển thị rõ.
- Nếu GĐ3 đổi phiên bản hoặc thay nội dung nguồn, lịch cũ bị đánh dấu lệch nguồn và phải xử lý trước khi phát hành.
- Phiên bản phát hành là bất biến; chỉnh sửa tiếp phải tạo bản nháp mới.

## 8. Kiểm tra bốn vai trò

1. Student mở lịch thi và chỉ thấy môn, ngày giờ, phòng, số báo danh của mình.
2. Parent chọn từng con và chỉ thấy lịch của người con đó.
3. Teacher chỉ thấy ca được phân công coi chính hoặc dự phòng.
4. Admin xem toàn bộ và kiểm tra sự kiện thông báo.

Kỳ vọng: RabbitMQ gửi thông báo lịch mới cho đúng người liên quan; người dùng không thể truy cập lịch của người khác.

## 9. Phiên bản, thu hồi và đóng đợt

1. Tạo **Bản điều chỉnh** từ lịch đã phát hành và nhập lý do.
2. Bản giống hệt phiên bản trước không được phát hành.
3. Thay đổi ít nhất một ca/phòng/giám thị, kiểm tra bảng so sánh rồi kiểm tra và phát hành.
4. Có thể **Thu hồi về nháp** với lý do; lịch tạm ẩn khỏi người dùng cuối cho tới khi phát hành lại.
5. Khi kết thúc vận hành, dùng **Đóng đợt thi**.
6. Nếu đợt không còn hiệu lực, dùng **Hủy đợt thi** và nhập lý do.

Kỳ vọng:

- Phiên bản mới được phát hành, phiên bản cũ chuyển lưu trữ nhưng không bị ghi đè.
- Chỉ đợt thi chưa từng phát hành mới được xóa vĩnh viễn.
- Đợt từng phát hành phải đóng/hủy; toàn bộ phiên bản, snapshot nguồn và audit vẫn được giữ.

## 10. Test tự động

Luồng GĐ5 đầy đủ:

```powershell
cd C:\SchoolManagementSystem\BE
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\smoke-academic-g5.ps1 `
  -BaseUrl http://127.0.0.1:4000
```

Xung đột tài nguyên và lịch cả ba khối:

```powershell
cd C:\SchoolManagementSystem\BE
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\smoke-exam-global-conflicts.ps1 `
  -BaseUrl http://127.0.0.1:4000
```

Kết quả bắt buộc:

- GĐ3 nguồn sẵn sàng và khoảng quá ngắn bị từ chối.
- Tạo đủ các ca, phòng, học sinh và số báo danh.
- Lịch bận/nghỉ, kiểm tra lại, phát hành, điều chỉnh, thu hồi và audit đều đạt.
- Lịch ba khối có 36 ca và không trùng phòng/giám thị trong bất kỳ slot nào.
- Dữ liệu test dạng bản nháp được dọn sau khi script kết thúc; lịch đã phát hành không bị xóa trái quy tắc bất biến.
