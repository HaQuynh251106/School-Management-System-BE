# Bộ dữ liệu nhập sẵn theo từng tab — kịch bản UAT 2080–2081

Tài liệu này dành cho người mới. Mỗi ô bên dưới là giá trị có thể sao chép trực tiếp vào Web. Kịch bản `2080–2081` được tách khỏi bộ Full Demo `2027–2028` để người kiểm thử có thể tạo, sửa và xóa mà không phá dữ liệu mẫu đang vận hành.

> Thứ tự bắt buộc: **Năm học → học kỳ → phòng → lớp → môn → chương trình → kế hoạch → tổ hợp → chuyên môn → phân công → thời khóa biểu**. Không bỏ qua bước phía trước rồi kết luận bước sau bị lỗi.

## 1. Tab Năm học

| Trường | Giá trị nhập |
|---|---|
| Mã năm học | `2080-2081` |
| Tên năm học | `Năm học UAT 2080–2081` |
| Năm bắt đầu | `2080` |
| Năm kết thúc | `2081` |
| Ngày bắt đầu | `01/09/2080` |
| Ngày kết thúc | `31/05/2081` |
| Trạng thái lúc tạo | `Nháp` |

Chỉ kích hoạt sau khi đã tạo đủ hai học kỳ ở mục 2. Nếu hệ thống đã có một năm `ACTIVE`, đóng hoặc lưu trữ năm đó trước; tại một thời điểm chỉ có một năm hoạt động.

## 2. Tab Học kỳ

### Học kỳ 1

| Trường | Giá trị nhập |
|---|---|
| Năm học | `Năm học UAT 2080–2081` |
| Mã học kỳ | `HK1-2080` |
| Tên học kỳ | `Học kỳ 1 năm học 2080–2081` |
| Bắt đầu | `01/09/2080` |
| Kết thúc | `31/01/2081` |

### Học kỳ 2

| Trường | Giá trị nhập |
|---|---|
| Năm học | `Năm học UAT 2080–2081` |
| Mã học kỳ | `HK2-2081` |
| Tên học kỳ | `Học kỳ 2 năm học 2080–2081` |
| Bắt đầu | `01/02/2081` |
| Kết thúc | `31/05/2081` |

Hai khoảng ngày không được chồng nhau và phải nằm trọn trong năm học.

## 3. Tab Phòng học và Tab Lớp

Bộ Full Demo đã có cố định 30 lớp và 30 phòng tương ứng. Sáu lớp `A1/A2` đang hoạt động; các lớp `A3–A10` là lớp dự phòng, đã có phòng nhưng chưa đưa vào bộ xếp lịch để không vượt định biên giáo viên.

| Khối | Mã lớp | Tên lớp | Mã phòng | Tên phòng | Sức chứa | Trạng thái mẫu |
|---|---|---|---|---|---:|---|
| K10 | `10A1` | `Lớp 10A1` | `P101` | `Phòng học 10A1` | 40 | Hoạt động |
| K10 | `10A2` | `Lớp 10A2` | `P102` | `Phòng học 10A2` | 40 | Hoạt động |
| K10 | `10A3` | `Lớp 10A3` | `P103` | `Phòng học 10A3` | 36 | Dự phòng |
| K10 | `10A4` | `Lớp 10A4` | `P104` | `Phòng học 10A4` | 36 | Dự phòng |
| K10 | `10A5` | `Lớp 10A5` | `P105` | `Phòng học 10A5` | 36 | Dự phòng |
| K10 | `10A6` | `Lớp 10A6` | `P106` | `Phòng học 10A6` | 36 | Dự phòng |
| K10 | `10A7` | `Lớp 10A7` | `P107` | `Phòng học 10A7` | 36 | Dự phòng |
| K10 | `10A8` | `Lớp 10A8` | `P108` | `Phòng học 10A8` | 36 | Dự phòng |
| K10 | `10A9` | `Lớp 10A9` | `P109` | `Phòng học 10A9` | 36 | Dự phòng |
| K10 | `10A10` | `Lớp 10A10` | `P110` | `Phòng học 10A10` | 36 | Dự phòng |
| K11 | `11A1` | `Lớp 11A1` | `P201` | `Phòng học 11A1` | 40 | Hoạt động |
| K11 | `11A2` | `Lớp 11A2` | `P202` | `Phòng học 11A2` | 40 | Hoạt động |
| K11 | `11A3` | `Lớp 11A3` | `P203` | `Phòng học 11A3` | 40 | Dự phòng |
| K11 | `11A4` | `Lớp 11A4` | `P204` | `Phòng học 11A4` | 40 | Dự phòng |
| K11 | `11A5` | `Lớp 11A5` | `P205` | `Phòng học 11A5` | 40 | Dự phòng |
| K11 | `11A6` | `Lớp 11A6` | `P206` | `Phòng học 11A6` | 40 | Dự phòng |
| K11 | `11A7` | `Lớp 11A7` | `P207` | `Phòng học 11A7` | 40 | Dự phòng |
| K11 | `11A8` | `Lớp 11A8` | `P208` | `Phòng học 11A8` | 40 | Dự phòng |
| K11 | `11A9` | `Lớp 11A9` | `P209` | `Phòng học 11A9` | 40 | Dự phòng |
| K11 | `11A10` | `Lớp 11A10` | `P210` | `Phòng học 11A10` | 40 | Dự phòng |
| K12 | `12A1` | `Lớp 12A1` | `P301` | `Phòng học 12A1` | 40 | Hoạt động |
| K12 | `12A2` | `Lớp 12A2` | `P302` | `Phòng học 12A2` | 40 | Hoạt động |
| K12 | `12A3` | `Lớp 12A3` | `P303` | `Phòng học 12A3` | 42 | Dự phòng |
| K12 | `12A4` | `Lớp 12A4` | `P304` | `Phòng học 12A4` | 42 | Dự phòng |
| K12 | `12A5` | `Lớp 12A5` | `P305` | `Phòng học 12A5` | 42 | Dự phòng |
| K12 | `12A6` | `Lớp 12A6` | `P306` | `Phòng học 12A6` | 42 | Dự phòng |
| K12 | `12A7` | `Lớp 12A7` | `P307` | `Phòng học 12A7` | 42 | Dự phòng |
| K12 | `12A8` | `Lớp 12A8` | `P308` | `Phòng học 12A8` | 42 | Dự phòng |
| K12 | `12A9` | `Lớp 12A9` | `P309` | `Phòng học 12A9` | 42 | Dự phòng |
| K12 | `12A10` | `Lớp 12A10` | `P310` | `Phòng học 12A10` | 42 | Dự phòng |

Quy tắc thao tác:

- Tạo phòng trước, sau đó chọn phòng ở form lớp.
- `Sĩ số tối đa của lớp ≤ sức chứa phòng`.
- Không gán cùng một phòng chủ nhiệm cho hai lớp đang hoạt động.
- Muốn bật lớp dự phòng, bổ sung đủ giáo viên đúng chuyên môn rồi chạy **Phân tích định biên** và **Kiểm tra sẵn sàng** trước.
- Muốn xóa dữ liệu thử: bỏ GVCN, bỏ phân công, bỏ học sinh khỏi lớp, chuyển lớp về `INACTIVE`, sau đó mới xóa. Dữ liệu đã được nghiệp vụ khác tham chiếu phải được lưu trữ thay vì xóa cứng.

## 4. Tab Môn học

| Mã | Tên | Loại | Phòng yêu cầu | Có kiểm tra/đánh giá |
|---|---|---|---|---|
| `MATH` | `Toán` | Bắt buộc | Phòng thường | Có |
| `LIT` | `Ngữ văn` | Bắt buộc | Phòng thường | Có |
| `ENG` | `Tiếng Anh` | Bắt buộc | Phòng thường | Có |
| `PHYS` | `Vật lý` | Bắt buộc | Phòng thí nghiệm | Có |
| `CHEM` | `Hóa học` | Bắt buộc | Phòng thí nghiệm | Có |
| `BIO` | `Sinh học` | Bắt buộc | Phòng thí nghiệm | Có |
| `HIST` | `Lịch sử` | Bắt buộc | Phòng thường | Có |
| `GEO` | `Địa lý` | Bắt buộc | Phòng thường | Có |
| `CIVIC` | `Giáo dục công dân` | Bắt buộc | Phòng thường | Có |
| `PE` | `Giáo dục thể chất` | Bắt buộc | Nhà thể chất | Có |
| `CHAOCO` | `Chào cờ` | Hoạt động giáo dục | Phòng thường | Không |
| `SHL` | `Sinh hoạt lớp` | Hoạt động giáo dục | Phòng thường | Không |

Không tạo kế hoạch kiểm tra cho `CHAOCO` hoặc `SHL`.

## 5. Tab Chương trình giáo dục

| Trường | Giá trị nhập |
|---|---|
| Mã chương trình | `CT-UAT-2080` |
| Tên chương trình | `Chương trình giáo dục UAT 2080` |
| Năm bắt đầu | `2080` |
| Mô tả | `Chương trình UAT dùng kiểm tra tạo kế hoạch, công bố và xếp thời khóa biểu.` |
| Trạng thái lúc tạo | `Bản nháp` |

Sau khi tạo, chọn **Tự động cấu hình cả 3 khối**. Kiểm tra mỗi môn có `Cả năm = HK1 + HK2`; sau đó mới chọn **Áp dụng chương trình**. Khi một chương trình mới được áp dụng, chương trình cũ được lưu trữ chứ không bị mất lịch sử.

## 6. Tab Kế hoạch giáo dục năm học

Tạo ba bản nháp, mỗi khối một bản:

| Khối | Tên kế hoạch | Chương trình | Chênh tiến độ tối đa | Mô tả |
|---|---|---|---:|---|
| K10 | `Kế hoạch giáo dục K10 · 2080–2081` | `CT-UAT-2080` | 2 ngày | `Kế hoạch chính thức cho khối 10.` |
| K11 | `Kế hoạch giáo dục K11 · 2080–2081` | `CT-UAT-2080` | 2 ngày | `Kế hoạch chính thức cho khối 11.` |
| K12 | `Kế hoạch giáo dục K12 · 2080–2081` | `CT-UAT-2080` | 2 ngày | `Kế hoạch chính thức cho khối 12.` |

Trình tự trong từng kế hoạch:

1. Chọn đúng chương trình `CT-UAT-2080`, không để hệ thống tự quay về chương trình 2018.
2. Chọn **Đồng bộ từ chương trình** để lấy môn và số tiết.
3. Khai báo giai đoạn, nội dung/bài học và phân phối theo tuần; tổng tiết phân phối phải bằng tổng tiết môn.
4. Tạo giữa kỳ/cuối kỳ. Ô **Người phụ trách** cho phép tìm kiếm và chọn nhiều giáo viên cùng chuyên môn.
5. Chạy **Kiểm tra điều kiện**; chỉ công bố khi `0 lỗi bắt buộc`.
6. Admin là người quyền cao nhất nên được công bố trực tiếp; lịch sử vẫn ghi actor, thời gian và phiên bản.

Ví dụ một mốc đánh giá:

| Trường | Giá trị nhập |
|---|---|
| Học kỳ | `Học kỳ 1 năm học 2080–2081` |
| Môn | `Toán` |
| Loại | `Giữa kỳ` |
| Tên | `Kiểm tra giữa kỳ I môn Toán` |
| Hình thức | `Tự luận` |
| Tuần | `8` |
| Thời lượng | `90` phút |
| Phạm vi | `Toàn khối` |
| Người phụ trách | Chọn hai giáo viên Toán |
| Ghi chú | `Đề chung toàn khối; chấm theo đáp án thống nhất.` |

## 7. Tab Tổ hợp môn và chuyên môn giáo viên

| Khối | Mã tổ hợp | Tên tổ hợp | Các môn lựa chọn |
|---|---|---|---|
| K10 | `K10-KHTN-2080` | `Khoa học tự nhiên K10` | Lý, Hóa, Sinh |
| K11 | `K11-KHXH-2080` | `Khoa học xã hội K11` | Sử, Địa, GDCD |
| K12 | `K12-CB-2080` | `Cơ bản K12` | Anh, Lý, Địa |

Chuyên môn giáo viên chỉ chọn từ danh mục môn đã có. Không có lựa chọn “Tự nhận diện và cân bằng”; cân bằng tải là kết quả của phân tích định biên và bộ xếp lịch, không phải một chuyên môn.

## 8. Tab Phân công giáo viên

Chỉ phân công khi thỏa cả bốn điều kiện: lớp hoạt động, kế hoạch đã công bố, giáo viên có chuyên môn phù hợp, tải không vượt chính sách.

| Lớp | Môn | Giáo viên mẫu | Học kỳ | Số tiết/tuần |
|---|---|---|---|---:|
| 10A1 | Toán | `demo.gv.001` | HK1 | 4 |
| 10A2 | Ngữ văn | `demo.gv.002` | HK1 | 4 |
| 11A1 | Tiếng Anh | `demo.gv.003` | HK1 | 3 |

Nhấn **Xem trước** trước khi **Áp dụng**. Nếu cảnh báo sai chuyên môn, thiếu tiết hoặc quá tải, sửa capability/phân công; không bỏ qua validator.

## 9. Tab Thời khóa biểu tự động

| Trường | Giá trị nhập |
|---|---|
| Năm học | `Năm học UAT 2080–2081` |
| Học kỳ | `Học kỳ 1 năm học 2080–2081` |
| Phạm vi | Chạy lần lượt `K10`, `K11`, `K12` |
| Tên bản nháp | `TKB HK1 UAT 2080–2081 · K10` (đổi K10 theo khối) |
| Ngày dạy | Thứ Hai đến Thứ Sáu |

Luồng: **Kiểm tra sẵn sàng → Tạo bản nháp → xem xung đột → điều chỉnh → kiểm tra lại → phát hành**. Trước khi phát hành phải có `0 xung đột cứng`; bản nháp không được hiển thị cho Giáo viên/Học sinh/Phụ huynh.

## 10. Tab Import Excel

File đi kèm chứa ba học sinh:

- `HSUAT280101` vào `10A1`.
- `HSUAT280201` vào `11A1`.
- `HSUAT280301` vào `12A1`.
- Hai học sinh đầu dùng chung phụ huynh `uat.ph.multi` để kiểm tra một phụ huynh có nhiều con.

Luồng bắt buộc: **Tải mẫu → chọn file → Xem trước → kiểm tra lớp/phụ huynh → Commit**. Xem trước không được ghi database; chạy lại cùng file phải cập nhật/tái sử dụng quan hệ, không sinh bản ghi trùng.

## 11. Tab Tạo tài khoản phụ huynh

| Trường | Giá trị nhập |
|---|---|
| Họ tên | `Phụ huynh UAT nhiều con` |
| Email | `uat.ph.multi@example.test` |
| Số điện thoại | `0988002080` |
| Vai trò | `Phụ huynh` |
| Con liên kết | Tìm `HSUAT280101`, chọn; tìm `HSUAT280201`, chọn; lưu một lần |

Danh sách liên kết có ô tìm kiếm, phân trang và checkbox nhiều học sinh. Nút **Lưu liên kết** gọi `PUT /users/{parentId}/children`; quan hệ được lưu tại `parent_student`. Reload trang phải vẫn thấy cả hai con.

## 12. Tab Khảo thí

| Trường | Giá trị nhập |
|---|---|
| Tên đợt thi | `Kiểm tra học kỳ I · 2080–2081 · K10` |
| Năm học | `2080–2081` |
| Học kỳ | `HK1-2080` |
| Ngày bắt đầu | `15/12/2080` |
| Ngày kết thúc | `22/12/2080` |

Lấy môn thi từ kế hoạch đã công bố. Chạy auto-plan ở chế độ xem trước, xác nhận không trùng phòng/giám thị/ca, sau đó apply và publish. Không chọn `CHAOCO` hoặc `SHL` làm môn thi.

## 13. Tab Vận hành giáo viên

### Điểm danh

- `PRESENT`: có mặt.
- `LATE`: đi muộn, ghi `Đến muộn 10 phút`.
- `ABSENT_EXCUSED`: nghỉ có phép, phải khớp đơn đã duyệt.
- `ABSENT_UNEXCUSED`: nghỉ không phép, ghi lý do/cảnh báo.

### Điểm

| Loại | Lần | Điểm mẫu | Ghi chú |
|---|---:|---:|---|
| Miệng | 1 | 8.4 | `Trả lời đúng, trình bày chưa rõ.` |
| 15 phút | 1 | 9.2 | `Nắm chắc kiến thức.` |
| Giữa kỳ | 1 | 8.8 | `Đúng cấu trúc đề chung.` |

Khi sửa 8.4 thành 9.2, lý do: `Nhập nhầm điểm từ bài chấm; đã đối chiếu bài gốc.` Hệ thống lưu before/after/version vào `grade_change_logs`.

### Bài tập

| Trường | Giá trị nhập |
|---|---|
| Tiêu đề | `Ôn tập giữa kỳ I môn Toán` |
| Mô tả | `Hoàn thành bài 1–10, trình bày đầy đủ bước giải.` |
| Hạn nộp | Một ngày tương lai trong HK1 |
| Trạng thái đầu | Nháp |

Phát hành xong mới xuất hiện cho học sinh. Học sinh nộp, giáo viên chấm `8.4` hoặc `9.2` và ghi feedback.

## 14. Tab Tài chính và đối soát

### Đợt thu theo lớp

| Trường | Giá trị nhập |
|---|---|
| Tên | `Học phí HK1 · 2080–2081` |
| Mã | `HP-HK1-2080` |
| Phạm vi | Lớp `10A1` |
| Khoản thu | `Học phí học kỳ I` |
| Số tiền | `2500000` |

### Khoản riêng học sinh

| Trường | Giá trị nhập |
|---|---|
| Tên | `Phí tài liệu bổ sung` |
| Phạm vi | Học sinh `HSUAT280101` |
| Số tiền | `150000` |

Luồng: **Xem trước đối tượng → Mở đợt → Sinh hóa đơn → Parent tạo VietQR/gửi xác nhận → Admin đối soát → Phát hành biên nhận**. Không giả callback thành công từ client.

## 15. Tab Thông báo, chat và ngoại khóa

- Thông báo bài tập: `Bài tập Toán mới đã được phát hành. Hạn nộp: ...`
- Thông báo chuyên cần: `Học sinh đi muộn tiết 1 ngày ...`
- Thông báo học phí: `Hóa đơn học phí HK1 đã được phát hành.`
- Thông báo lịch: `Thời khóa biểu HK1 đã có phiên bản mới.`
- Chat phụ huynh → GVCN: `Thầy/cô cho tôi hỏi về tiến độ học tập tuần này của cháu.`
- Chat GVCN → phụ huynh: `Cháu học đều; gia đình lưu ý giúp phần bài tập Toán.`
- Ngoại khóa miễn phí: `Ngày hội STEM 2080`.
- Ngoại khóa có phí: `Câu lạc bộ Robotics 2080`, phí `300000`.

## 16. Tab Tổng kết và chuyển lớp

Thao tác trên dữ liệu Full Demo nguồn `2026–2027` hoặc tạo dữ liệu UAT tương đương. Luồng: **Xem blocker → Xem trước kết quả → Chốt → Công bố → Chọn lớp đích → Chuyển lớp**. Không chuyển lớp khi còn thiếu điểm, chuyên cần, nhận xét hoặc kết quả chưa công bố.

## 17. Kết quả xóa/sửa cần kiểm tra

- Danh mục nháp chưa được tham chiếu: cho phép sửa/xóa.
- Chương trình/kế hoạch đã công bố: tạo version mới, không xóa lịch sử.
- Lớp/phòng đang được TKB dùng: không xóa cứng; chuyển trạng thái hoặc gỡ quan hệ theo đúng thứ tự.
- Điểm đã tồn tại: sửa với `expectedVersion` và lý do; xung đột version trả 409 và UI tải lại.
- Quan hệ phụ huynh–con: lưu nhiều lựa chọn trong một request; bỏ một con không ảnh hưởng con còn lại.
