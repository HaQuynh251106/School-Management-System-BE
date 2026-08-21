# Full Demo 2027–2028 — dữ liệu đủ điều kiện chạy xuyên luồng

Tài liệu này mô tả bộ dữ liệu do `scripts/reset-and-seed-full-demo.ps1` tạo. Mục tiêu là mỗi màn hình có dữ liệu liên kết thật và các chức năng lõi vượt qua validator/service của backend; không sửa hoặc tắt luật nghiệp vụ để seed thành công.

## 1. Tài khoản đại diện

| Vai trò | Tài khoản | Mật khẩu | Dữ liệu chính |
|---|---|---|---|
| Admin | `demo.admin.01` | `Admin@123` | Cơ cấu, chương trình, kế hoạch, TKB, tài chính, tổng kết |
| Admin | `demo.admin.02` | `Admin@123` | Tài chính và đối soát trong cùng role Admin |
| Giáo viên Toán | `demo.gv.001` | `Teacher@123` | GVCN 10A1; dạy Toán; điểm danh, điểm, bài tập |
| Giáo viên Ngữ văn | `demo.gv.002` | `Teacher@123` | GVCN 10A2; dạy Ngữ văn |
| Giáo viên Tiếng Anh | `demo.gv.003` | `Teacher@123` | GVCN 11A1; dạy Tiếng Anh |
| Học sinh khối 10 | `demo.hs.001` hoặc `HS270001` | `Student@123` | 10A1; có TKB, điểm, chuyên cần, bài tập, lịch thi |
| Học sinh khối 11 | `demo.hs.021` hoặc `HS270021` | `Student@123` | 11A1 |
| Học sinh khối 12 | `demo.hs.041` hoặc `HS270041` | `Student@123` | 12A1 |
| Phụ huynh hai con | `demo.ph.001` | `Parent@123` | Liên kết HS270001 và HS270002 |
| Phụ huynh khối 11 | `demo.ph.013` | `Parent@123` | Liên kết HS270025 |
| Phụ huynh khối 12 | `demo.ph.033` | `Parent@123` | Liên kết HS270045 |

Mật khẩu trên chỉ được in bởi script. PostgreSQL chỉ lưu BCrypt hash.

## 2. Chuỗi vận hành và dữ liệu bảo đảm

### Bước 1 — Chuẩn bị cơ cấu năm học

| Điều kiện backend | Dữ liệu Full Demo | Kết quả mong đợi |
|---|---|---|
| Chỉ một năm đang hoạt động | `fd-ay-2027`, mã `2027-2028`, 01/09/2027–31/05/2028 | Chọn được năm hiện hành |
| Đúng hai học kỳ, không chồng ngày | HK1: 01/09/2027–31/01/2028; HK2: 01/02/2028–31/05/2028 | Khởi tạo kế hoạch được |
| Khối THPT hợp lệ | K10, K11, K12 | Lọc lớp/kế hoạch/báo cáo được |
| Lớp hoạt động có GVCN và phòng riêng | 10A1, 10A2, 11A1, 11A2, 12A1, 12A2 | Sáu lớp lõi sẵn sàng xếp lịch |
| Sức chứa phòng không nhỏ hơn sĩ số tối đa của lớp | Phòng lõi 40 chỗ; lớp lõi tối đa 40 | Không lỗi sức chứa |
| Danh mục môn đầy đủ | Toán, Ngữ văn, Anh, Lý, Hóa, Sinh, Sử, Địa, GDCD, Thể dục; Chào cờ và SHL là hoạt động giáo dục | Chương trình và TKB có đủ môn |

Bộ dữ liệu có tổng cộng 30 lớp và 30 phòng cố định:

- K10: 10A1–10A10; K11: 11A1–11A10; K12: 12A1–12A10.
- Sáu lớp A1/A2 là `ACTIVE` và có học sinh thật.
- Hai mươi bốn lớp A3–A10 là `INACTIVE`, đã có phòng và GVCN dự kiến nhưng chưa được đưa vào bộ giải.
- Không kích hoạt đồng loạt 30 lớp khi chỉ có 36 giáo viên. Định mức hiện hành 2,25 giáo viên/lớp sẽ cần tối thiểu 68 giáo viên. Khi mở thêm lớp, Admin phải bổ sung nhân sự rồi kiểm tra lại mục **Phân tích định biên**.

### Bước 2 — Tạo chương trình và kế hoạch nháp

| Điều kiện backend | Dữ liệu Full Demo |
|---|---|
| Chương trình ở trạng thái đang áp dụng | `fd-program-2027`, `ACTIVE` |
| Cấu hình đủ cả K10, K11, K12 trước khi kích hoạt | Mỗi khối có đủ 12 môn/hoạt động |
| Tổng cả năm bằng HK1 + HK2 | 36 cấu hình môn đều thỏa điều kiện |
| Kế hoạch có lịch sử phiên bản | Mỗi khối có v1 lưu lịch sử, v2 đã công bố, v3 bản nháp có thể sửa |
| Kế hoạch nháp có nguồn chương trình | v3 trỏ đúng `fd-program-2027` |
| Số tiết kế hoạch khớp chương trình | Tất cả môn ở HK1/HK2 khớp tuyệt đối |

### Bước 3 — Tổ hợp, phân công, công bố và xếp TKB

| Điều kiện backend | Dữ liệu Full Demo | Bằng chứng tự động |
|---|---|---|
| Mỗi lớp hoạt động có tổ hợp môn | Đã gán tổ hợp KHTN/KHXH phù hợp | Validation kế hoạch không có lỗi |
| Giáo viên đúng chuyên môn | 36 giáo viên ACTIVE, capability theo môn | Không có phân công sai chuyên môn |
| Có phân công theo lớp–môn–học kỳ | 846 bản ghi gồm lớp lõi, lớp dự kiến và hoạt động GVCN | Readiness đủ assignment cho lớp ACTIVE |
| Không vượt 25 tiết/tuần/người trong phạm vi xếp | Phân công lớp lõi được chia theo khối/môn | Không có lỗi tải giáo viên |
| Kế hoạch có giai đoạn, bài học và phân phối tuần | Tổng stage, lesson, distribution đều bằng tổng tiết | Validation `errorCount=0` |
| Môn đánh giá có giữa kỳ và cuối kỳ | Mỗi môn/HK có đủ MIDTERM và FINAL | Có kế hoạch kiểm tra |
| Một mốc kiểm tra có nhiều người phụ trách | Mỗi assessment có một giáo viên chính và một giáo viên cùng chuyên môn | Lưu ở bảng `academic_assessment_plan_teachers` |
| Phòng đúng loại | Lý/Hóa/Sinh dùng LAB; Thể dục dùng GYM; môn khác dùng GENERAL | Bộ giải không báo sai phòng |
| Không trùng lớp, giáo viên, phòng | 132 tiết đã phát hành cho sáu lớp lõi | Validator TKB: 0 hard error |

API readiness cho K10, K11 và K12 đều trả `ready=true`. Script chạy thật bộ giải riêng cho cả ba khối; mỗi kết quả bắt buộc `valid=true`, `errorCount=0`, `hardViolationCount=0`, sau đó bản nháp smoke được xóa để không tạo dữ liệu rác.

### Bước 4 — Giáo viên vận hành hằng ngày

| Chức năng | Dữ liệu có sẵn |
|---|---|
| Điểm danh | PRESENT, LATE, ABSENT_EXCUSED, ABSENT_UNEXCUSED; có đơn xin phép đã duyệt |
| Điểm | 732 điểm thành phần/tổng hợp; nhiều assessment index; có optimistic version |
| Sửa điểm | 8.4 → 9.2, có lý do và `grade_change_logs` |
| Bài tập | Một bản nháp, một bài đã phát hành, hạn nộp thật |
| Bài nộp | Đúng hạn, nộp muộn, đã chấm; điểm 8.4/9.2 và feedback |
| File | Chỉ upload file thật khi MinIO sẵn sàng; MinIO tắt không làm seed thất bại |
| Tiến độ giảng dạy | Có hai lớp K10 để so sánh tiến độ và đề xuất bù |

### Bước 5 — Học sinh và phụ huynh nhận dữ liệu

- Student xem đúng TKB đã phát hành, kế hoạch giáo dục đã công bố, điểm, chuyên cần, bài tập và lịch thi.
- Parent xem từng con riêng; `demo.ph.001` có hai con để kiểm tra đổi hồ sơ.
- Parent không thể đọc điểm, TKB, hóa đơn hoặc bài tập của học sinh không liên kết; smoke test yêu cầu HTTP 403.
- Có notification đã đọc/chưa đọc và chat mẫu giữa GVCN–phụ huynh.
- File Excel import thử ba khối được cung cấp riêng; import lại không sinh tài khoản hoặc liên kết trùng.

### Bước 6 — Thu học phí, đối soát và biên nhận

| Dữ liệu | Phạm vi |
|---|---|
| Đợt thu theo lớp | Học phí + bảo hiểm |
| Khoản thu riêng | Một học sinh cụ thể |
| Trạng thái hóa đơn | PENDING, PARTIAL, PAID, OVERDUE, CANCELLED, VOID |
| Trạng thái thanh toán | PENDING, SUCCESS, FAILED |
| Đối soát | Một phiên tiền mặt `BALANCED`, không chênh lệch |
| Biên nhận | Hai biên nhận đã phát hành; PDF thật chỉ có khi MinIO chạy |
| Phụ huynh hai con | Hóa đơn được lọc theo từng studentId, không trộn dữ liệu |

Seed không giả callback cổng thanh toán. Giao dịch thành công mẫu là tiền mặt/đã đối soát theo state machine hiện có.

### Bước 7 — Tổng kết, công bố kết quả và chuyển lớp

- Năm nguồn `2026-2027` đã đóng và có đúng hai học kỳ CLOSED.
- Lớp nguồn `11A1-2627` có một học sinh `HS270060` chưa được chuyển vào năm đích.
- Học sinh này có đủ điểm của cả hai học kỳ, chuyên cần, hạnh kiểm và bản tổng kết DRAFT.
- API preview trả trạng thái có thể chốt; Admin có thể đi qua chuỗi **xem trước → chốt → công bố → chọn lớp đích → chuyển lớp**.
- Chính sách xét lên lớp tồn tại cho cả năm nguồn và năm hiện hành.

## 3. Lệnh chạy

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\reset-and-seed-full-demo.ps1
```

Chạy không tương tác trong môi trường demo:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\reset-and-seed-full-demo.ps1 `
  -Confirm "RESET sse_db"
```

Script giữ nguyên schema, lịch sử Flyway, role/quyền hệ thống và Admin chính không phải demo. Script chạy Flyway, reset dữ liệu nghiệp vụ/demo, seed, kiểm tra SQL, chạy API smoke và in tài khoản.

Nếu database từng được Hibernate tự tạo toàn bộ bảng nhưng lịch sử Flyway vẫn ở trước V6, script sẽ dừng trước khi xóa dữ liệu. Đây là bảo vệ chủ động: không `flyway repair`, không drop schema và không giả đánh dấu migration đã chạy. Hãy tạo một database sạch do Flyway quản lý rồi chạy lại; môi trường kiểm chứng local hiện dùng `sse_full_flow_probe` cho mục đích này.

## 4. Tiêu chí PASS bắt buộc

1. SQL verify in `FULL_DEMO_VALIDATION_PASSED`.
2. Validation của ba kế hoạch v2 và bản nháp K10 v3: `valid=true`, `errorCount=0`.
3. Generation readiness K10/K11/K12: `ready=true`.
4. Chạy bộ giải K10, K11 và K12: `hardViolationCount=0` cho từng khối.
5. Login bốn role thành công bằng username/email/mã học sinh/số điện thoại.
6. Student/Parent chỉ nhìn thấy dữ liệu đúng phạm vi.
7. Import Excel lần đầu tạo ba học sinh/hai phụ huynh; lần hai cập nhật, không tạo trùng.
8. Có dữ liệu thật cho điểm danh, điểm, bài tập, thi, tài chính, notification, chat và tổng kết.
