# Hướng dẫn kiểm thử tài chính P0-P5

Tài liệu này mô tả trạng thái thực tế của code tại ngày 22/07/2026, cách kiểm thử trên giao diện và bằng script, cùng cơ chế kỹ thuật giúp từng luồng hoạt động.

## 1. Trạng thái hiện tại

| Giai đoạn | Trạng thái | Phạm vi đã có |
| --- | --- | --- |
| P0 | Hoàn thành | Ràng buộc dữ liệu, chống invoice trùng, khóa khi cập nhật tiền, trạng thái invoice, phân quyền |
| P1 | Hoàn thành | Đợt thu theo khối/lớp/học sinh, khoản chung/riêng, xem trước, phát hành batch, thu hồi/đóng/hủy |
| P2 | Hoàn thành | Payment Service, payment PENDING, gateway ledger, callback/IPN, chống callback trùng, Return URL chỉ đọc |
| P3 | Một phần cần UAT ngoài | Adapter VNPAY/MoMo đã có và có unit test; chuyển khoản MB + VietQR dùng được; VNPAY/MoMo thật cần merchant sandbox |
| P4 | Hoàn thành trên local | Lịch sử, ảnh biên lai, PDF biên nhận, đối soát, hoàn tiền, quy trình hai Admin, audit |
| P5 | Hoàn thành | Doanh thu, công nợ, quá hạn, bộ lọc, Excel/PDF, audit khi xuất |

## 2. Chuẩn bị môi trường local

### 2.1. Khởi động RabbitMQ và MinIO

```powershell
cd C:\SchoolManagementSystem\BE
docker compose -f docker-compose.dev.yml up -d rabbitmq minio minio-init
```

- RabbitMQ Management: `http://127.0.0.1:15672`, tài khoản `sse / sse_dev`.
- MinIO Console: `http://127.0.0.1:9001`, tài khoản `sse / sse_dev_minio`.
- MinIO API dùng bởi backend: `http://127.0.0.1:9000`.

### 2.2. Chạy backend với `sse_db` và tài khoản MB

```powershell
cd C:\SchoolManagementSystem\BE

$env:SSE_DB_URL = "jdbc:postgresql://localhost:5432/sse_db"
$env:SSE_DB_USER = "postgres"
$env:SSE_DB_PASSWORD = "postgres"
$env:SSE_MB_BANK_TRANSFER_ENABLED = "true"
$env:SSE_MB_BANK_ID = "MB"
$env:SSE_MB_BANK_NAME = "MB Bank"
$env:SSE_MB_ACCOUNT_NUMBER = "0334611565"
$env:SSE_MB_ACCOUNT_NAME = "DINH QUANG THAI"
$env:SSE_MB_TRANSFER_PREFIX = "SSE"

& "C:\Users\thaid\.cache\sse-tools\apache-maven-3.9.16\bin\mvn.cmd" `
  -pl services/app spring-boot:run
```

Không chạy hai backend cùng lúc trên cổng `4000`. Nếu đang chạy một phiên cũ, dừng bằng `Ctrl+C` trước.

### 2.3. Chạy frontend

```powershell
cd C:\SchoolManagementSystem\Web-FE
npm.cmd run dev
```

Mở `http://127.0.0.1:5173`.

Tài khoản chính:

| Vai trò | Tên đăng nhập | Mật khẩu |
| --- | --- | --- |
| Admin tạo yêu cầu | `admin` | `admin@123` |
| Admin duyệt hoàn tiền | `admin.finance` | `admin2@123` |
| Phụ huynh hai con | `ph.nguyen` | `parent@123` |

## 3. Bộ test tự động tổng quát

### 3.1. Toàn bộ unit test backend

```powershell
cd C:\SchoolManagementSystem\BE
& "C:\Users\thaid\.cache\sse-tools\apache-maven-3.9.16\bin\mvn.cmd" `
  -pl services/app -am test
```

Kết quả hiện tại: `94 tests`, `0 failures`, `0 errors`.

### 3.2. Build frontend

```powershell
cd C:\SchoolManagementSystem\Web-FE
npm.cmd run build
```

Kỳ vọng: TypeScript không lỗi và Vite báo `built`.

### 3.3. Smoke tổng

```powershell
cd C:\SchoolManagementSystem\BE
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\smoke-app.ps1 `
  -BaseUrl http://127.0.0.1:4000
```

Smoke tổng kiểm tra đăng nhập, RBAC, finance P0, payment P2, notification RabbitMQ và các luồng học vụ liên quan. Nếu fixture P1 cũ không còn là DRAFT, script sẽ `SKIP` fixture đó để không sửa dữ liệu người dùng; các ràng buộc P1 vẫn được kiểm tra trong `FinanceServiceTest` và bằng luồng giao diện bên dưới.

## 4. P0 - Chuẩn hóa finance core

### 4.1. Những gì cần kiểm tra

1. Mã đợt thu không được trùng, kể cả khác chữ hoa/chữ thường.
2. Một học sinh chỉ có một invoice trong một đợt thu.
3. Phát hành lại không tạo invoice trùng.
4. Invoice có đủ `PENDING`, `PARTIAL`, `OVERDUE`, `PAID`, `CANCELLED`, `VOID`.
5. Parent/Student chỉ xem invoice và payment của chính mình hoặc con mình.
6. Khi xác nhận payment, invoice bị khóa ghi để hai callback không cộng tiền đồng thời.

### 4.2. Test trên giao diện

1. Đăng nhập `admin`, vào **Tài chính nội bộ > Đợt thu**.
2. Tạo mã mới, ví dụ `P0-TEST-01`.
3. Tạo lại mã `p0-test-01`.
4. Kỳ vọng lần hai báo mã đã tồn tại.
5. Với một đợt OPEN đã có khoản thu, xem trước và phát hành invoice.
6. Gọi phát hành lần nữa hoặc tải lại trang.
7. Kỳ vọng không xuất hiện invoice thứ hai cho cùng học sinh.
8. Đăng nhập phụ huynh khác và thử mở URL/API invoice không thuộc con mình.
9. Kỳ vọng HTTP `403`.

### 4.3. Test tự động trọng tâm

```powershell
cd C:\SchoolManagementSystem\BE
& "C:\Users\thaid\.cache\sse-tools\apache-maven-3.9.16\bin\mvn.cmd" `
  -pl services/app -am -Dtest=FinanceServiceTest test
```

### 4.4. Vì sao P0 hoạt động

- SQL startup patch tạo unique index `lower(fee_periods.code)` và `(fee_period_id, student_id)`.
- Service vẫn kiểm tra trước để trả thông báo dễ hiểu; database là lớp bảo vệ cuối khi có hai request đồng thời.
- Repository dùng `PESSIMISTIC_WRITE` cho đợt thu, invoice và payment trong các thao tác đổi số tiền/trạng thái.
- Controller kiểm tra vai trò và quan hệ Parent-Student trước khi trả invoice/payment.
- Script SQL chạy lặp lại an toàn mỗi lần khởi động bằng `spring.sql.init`.

## 5. P1 - Đợt thu và invoice

### 5.1. Test tạo phạm vi và khoản thu

1. Đăng nhập `admin`, vào **Tài chính nội bộ > Đợt thu**.
2. Nhập mã, tên, hạn thanh toán.
3. Chọn một trong bốn phạm vi: toàn trường, khối, lớp hoặc danh sách học sinh.
4. Với danh sách học sinh, bắt buộc chọn theo thứ tự **khối > lớp > học sinh**.
5. Tạo đợt thu và mở **Chi tiết**.
6. Thêm khoản chung cho toàn bộ phạm vi.
7. Thêm khoản riêng và chọn đúng một học sinh trong phạm vi.
8. Thử chọn học sinh ngoài phạm vi hoặc nhập số tiền `0`.
9. Kỳ vọng backend từ chối và giao diện hiển thị nội dung cần bổ sung.

### 5.2. Test xem trước và phát hành

1. Khi đợt còn DRAFT, thử bấm mở mà chưa có khoản thu.
2. Kỳ vọng cảnh báo chưa có khoản thu.
3. Thêm khoản thu, bấm **Mở đợt thu** rồi **Xem trước**.
4. Đối chiếu số học sinh, số invoice mới và tổng tiền.
5. Học sinh có khoản riêng phải có tổng tiền bằng khoản chung cộng khoản riêng.
6. Bấm **Phát hành**.
7. Kỳ vọng trạng thái thành PUBLISHED, mất nút phát hành/xem trước, invoice xuất hiện ở tab **Hóa đơn & thu tiền**.
8. Đăng nhập `ph.nguyen`, kiểm tra thông báo **Có khoản thu mới** và badge ở tab **Học phí**.
9. Bấm thông báo phải chuyển đến **Học phí**.

### 5.3. Test vòng đời đợt thu

- DRAFT: được thêm/xóa khoản thu.
- PUBLISHED chưa có hoạt động thanh toán: được **Lưu về nháp**, invoice và QR tự tạo bị thu hồi.
- PUBLISHED đã có giao dịch: không được thu hồi.
- PUBLISHED: được **Đóng**; invoice và lịch sử vẫn giữ nguyên.
- DRAFT/OPEN/PUBLISHED chưa thu tiền: được **Hủy**.
- Đã thu tiền: không được hủy trước khi hoàn tiền.
- Invoice quá hạn: Admin bấm **Nhắc nhở**, Student và Parent nhận notification.

### 5.4. Vì sao P1 hoạt động

- Phạm vi đợt thu được chuẩn hóa thành `ALL`, `GRADE`, `CLASS` hoặc `STUDENT` và lưu trong bảng target riêng.
- Khoản thu chỉ cho phép toàn bộ phạm vi hoặc đúng một học sinh, đúng với UI đã chốt.
- Preview lấy học sinh ACTIVE thuộc năm học/lớp hiện tại, áp dụng khoản chung và khoản riêng rồi tính tổng nhưng chưa ghi invoice.
- Phát hành khóa bản ghi đợt thu, chia batch 100 học sinh và lưu snapshot tên/số tiền từng khoản vào `invoice_items`.
- Snapshot giúp invoice cũ không đổi nội dung nếu cấu hình khoản thu sau này thay đổi.
- Mỗi invoice phát event `finance.invoice.issued`; RabbitMQ worker tạo thông báo cho học sinh và mọi phụ huynh liên kết.

## 6. P2 - Payment Service

### 6.1. Test tự động đầy đủ

Smoke tổng đã kiểm tra P2. Có thể chạy riêng với một invoice chưa thanh toán thuộc đúng phụ huynh:

```powershell
cd C:\SchoolManagementSystem\BE
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\smoke-payment-p2.ps1 `
  -InvoiceId "<ID_INVOICE_CHUA_THANH_TOAN>" `
  -ParentUsername "ph.nguyen" `
  -ParentPassword "parent@123" `
  -ReplayCount 10
```

Kỳ vọng:

1. Payment mới là `PENDING`; invoice chưa tăng `paidAmount`.
2. Browser Return trước IPN vẫn là `PENDING`.
3. Callback sai chữ ký không thay đổi payment/invoice.
4. Callback đúng chữ ký cập nhật đúng một lần.
5. Gửi lại cùng callback 10 lần không cộng tiền thêm.
6. `callbackCount` tăng để phục vụ truy vết.

### 6.2. Vì sao P2 hoạt động

- `PaymentGateway` tách logic cổng khỏi `PaymentService`.
- `payments` lưu ý định thanh toán; `payment_gateway_transactions` lưu request, response, chữ ký, lỗi và số lần callback.
- Payment luôn bắt đầu ở `PENDING`; tạo URL không đồng nghĩa đã nhận tiền.
- Callback kiểm tra chữ ký, provider, merchant, mã payment, số tiền và provider transaction ID.
- Unique provider transaction ID và khóa payment/invoice ngăn xử lý trùng.
- Return URL trên trình duyệt chỉ đọc trạng thái trong database, tuyệt đối không gọi hàm settle.

## 7. P3 - VNPAY, MoMo và chuyển khoản MB

### 7.1. Trạng thái nghiệm thu

- VNPAY adapter: đã tạo URL HMAC-SHA512, kiểm tra merchant/signature/amount/transaction, có unit test và smoke script.
- MoMo adapter: đã gọi create API, ký HMAC-SHA256, kiểm tra response/IPN, có unit test và smoke script.
- Chưa thể xác nhận giao dịch thật với VNPAY/MoMo khi chưa có merchant sandbox và callback public HTTPS.
- Chuyển khoản tài khoản MB cá nhân hiện dùng được qua VietQR và Admin duyệt ảnh biên lai.

### 7.2. Test chuyển khoản MB trên FE

1. Admin phát hành một đợt thu cho con của `ph.nguyen`.
2. Đăng nhập `ph.nguyen`, badge **Học phí** phải tăng.
3. Vào **Học phí**; đọc cảnh báo mã học sinh/họ tên và tích xác nhận.
4. Badge finance phải về `0` sau khi tiếp tục.
5. Chọn invoice chưa trả, bấm **Thanh toán > Chuyển khoản MB**.
6. Kiểm tra QR hiển thị:
   - Tài khoản `0334611565`.
   - Chủ tài khoản `DINH QUANG THAI`.
   - Số tiền bằng công nợ còn lại.
   - Nội dung có `SSE + mã học sinh + tên học sinh`.
7. Chọn ảnh JPG/PNG dưới 5MB và bấm **Gửi biên lai cho Admin**.
8. Kỳ vọng invoice vẫn chưa PAID, trạng thái biên lai là chờ Admin duyệt.
9. Admin vào **Tài chính nội bộ > Biên lai**, mở ảnh và đối chiếu app MB.
10. Không tích xác nhận đối chiếu thì nút duyệt không được thực hiện.
11. Nếu ảnh sai, nhập lý do và bấm **Yêu cầu thanh toán lại**.
12. Nếu đúng, tích đã đối chiếu và duyệt; payment thành SUCCESS, invoice tăng paidAmount.

### 7.3. Vì sao QR tự điền số tiền và nội dung

1. Khi phát hành invoice, backend tự tạo một payment MB `PENDING` nếu MB được bật.
2. Backend đọc mã học sinh và họ tên từ hồ sơ thật.
3. Nội dung được chuẩn hóa thành ASCII, tối đa 50 ký tự: `SSE <MÃ_HS> <TÊN_HS>`, thêm mã invoice nếu còn chỗ.
4. Backend dựng URL VietQR dạng:

```text
https://img.vietqr.io/image/MB-0334611565-compact2.png
  ?amount=<SO_TIEN_CON_NO>
  &addInfo=<NOI_DUNG_BAT_BUOC>
  &accountName=DINH_QUANG_THAI
```

5. FE chỉ hiển thị URL này thành ảnh. Khi quét, ứng dụng ngân hàng đọc sẵn tài khoản, số tiền và nội dung từ QR.
6. Đây là chuyển khoản bán tự động: hệ thống không được quyền đọc tài khoản MB cá nhân, vì vậy ảnh biên lai và bước Admin đối chiếu là lớp xác nhận tiền thật.

### 7.4. Test VNPAY thật khi đã có merchant sandbox

Khởi động backend với `SSE_VNPAY_ENABLED=true`, `SSE_VNPAY_TMN_CODE`, `SSE_VNPAY_HASH_SECRET`, Return URL và IPN URL public, sau đó:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\smoke-payment-p3.ps1 `
  -InvoiceId "<ID_INVOICE>" `
  -TmnCode "<VNPAY_TMN_CODE>" `
  -HashSecret "<VNPAY_HASH_SECRET>" `
  -ParentUsername "<PHU_HUYNH_CUA_HOC_SINH>" `
  -ReplayCount 10
```

### 7.5. Test MoMo thật khi đã có merchant sandbox

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\smoke-payment-momo-p3.ps1 `
  -InvoiceId "<ID_INVOICE>" `
  -PartnerCode "<MOMO_PARTNER_CODE>" `
  -AccessKey "<MOMO_ACCESS_KEY>" `
  -SecretKey "<MOMO_SECRET_KEY>" `
  -ParentUsername "<PHU_HUYNH_CUA_HOC_SINH>" `
  -ReplayCount 10
```

## 8. P4 - Vận hành tài chính

### 8.1. P4.1 Lịch sử và biên nhận PDF

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\smoke-finance-p4.ps1 `
  -BaseUrl http://127.0.0.1:4000
```

Test FE:

1. Admin vào **Lịch sử giao dịch**.
2. Lọc SUCCESS/PENDING/FAILED/REVERSED và theo phương thức.
3. Payment SUCCESS có nút tạo/tải biên nhận PDF.
4. Parent vào **Học phí > Lịch sử giao dịch** và tải PDF của payment thuộc con mình.
5. Payment PENDING không được tạo biên nhận.

Vì sao PDF biên nhận hoạt động:

- Khi settle, hệ thống sinh số duy nhất `SSE-REC-<NGAY>-<PAYMENT_TOKEN>`.
- Renderer vẽ thông tin invoice, học sinh, số tiền, phương thức, mã giao dịch và thời gian lên trang ảnh có bố cục cố định.
- PDFBox đưa ảnh đó vào một trang PDF, đảm bảo font tiếng Việt hiển thị nhất quán.
- Backend upload byte PDF vào MinIO và lưu `fileId` trong `payment_receipts`.
- Khi tải, backend kiểm tra quyền rồi trả presigned URL có thời hạn; bucket không cần mở public.
- Nếu MinIO lỗi, payment vẫn SUCCESS; receipt ghi FAILED và Admin có thể tạo lại.

### 8.2. P4.2 Hoàn tiền cơ bản

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\smoke-finance-p4-2.ps1 `
  -BaseUrl http://127.0.0.1:4000
```

Kỳ vọng: không hoàn vượt số tiền đã thu, giữ chỗ yêu cầu đang chờ, Parent thấy kết quả, RabbitMQ gửi notification, audit lưu người tạo/người duyệt.

### 8.3. P4.3 Đối soát

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\smoke-finance-p4-3.ps1 `
  -BaseUrl http://127.0.0.1:4000
```

Test FE:

1. Admin vào **Đối soát & hoàn tiền**.
2. Chọn từ ngày, đến ngày, phương thức và khoảng tiền.
3. Chạy đối soát.
4. `BALANCED` nghĩa payment, refund, invoice, receipt và bằng chứng cổng/MB khớp nhau.
5. `DISCREPANCY` hiển thị từng lỗi như sai paidAmount, sai status, thiếu receipt, thiếu IPN hợp lệ hoặc biên lai MB chưa duyệt.
6. Chạy lại cùng bộ lọc cập nhật cùng snapshot và tăng `runCount`, không sinh bản ghi trùng.

### 8.4. P4.4/P4.5 Hoàn tiền đủ và hai Admin

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\smoke-finance-p4-4.ps1 `
  -BaseUrl http://127.0.0.1:4000
```

Test FE:

1. Đăng nhập `admin`, chọn payment SUCCESS và tạo yêu cầu hoàn một phần/toàn phần.
2. Bắt buộc nhập lý do.
3. Thử tự duyệt bằng chính `admin`; kỳ vọng bị chặn.
4. Đăng xuất, đăng nhập `admin.finance`.
5. Chọn phương thức hoàn, nhập mã tham chiếu nếu không phải CASH, tích xác nhận đã hoàn tiền thật.
6. Duyệt yêu cầu.
7. Kỳ vọng invoice giảm paidAmount; hoàn hết payment thì payment thành REVERSED.
8. Thử hoàn vượt số tiền hoặc dùng lại mã tham chiếu; kỳ vọng bị chặn.
9. Kiểm tra **Lịch sử hệ thống** có cả người yêu cầu và người phê duyệt.

Vì sao không thể hoàn âm hoặc tự duyệt:

- Request giữ chỗ số tiền đang chờ cùng số đã hoàn.
- Khi duyệt, payment và invoice được khóa; backend tính lại tổng completed trước khi trừ.
- `invoice.paidAmount - refund.amount` chỉ chạy sau mọi kiểm tra số dư.
- Người yêu cầu và người duyệt phải có user ID khác nhau.
- Mã tham chiếu hoàn tiền có unique index không phân biệt hoa/thường.

## 9. P5 - Báo cáo và thống kê

### 9.1. Test tự động

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\smoke-finance-p5.ps1 `
  -BaseUrl http://127.0.0.1:4000
```

Script tạo một fixture riêng có invoice quá hạn, payment và hoàn một phần rồi xác nhận chính xác:

- Tổng phải thu.
- Thực thu.
- Hoàn tiền.
- Doanh thu ròng = thực thu - hoàn tiền.
- Công nợ và công nợ quá hạn.
- Bộ lọc phương thức.
- Admin-only.
- Chữ ký file XLSX/PDF.
- Audit cho từng lần export.

File test được lưu tại `services/app/target/p5-smoke`.

### 9.2. Test trên giao diện

1. Đăng nhập Admin, vào **Báo cáo & thống kê**.
2. Chọn khoảng ngày, đợt thu, khối, lớp, học sinh và phương thức.
3. Bấm **Áp dụng**.
4. Đối chiếu sáu KPI: phải thu, thực thu, hoàn tiền, ròng, còn phải thu, quá hạn.
5. Kiểm tra bảng theo ngày và theo phương thức.
6. Chuyển tab công nợ theo đợt thu, khối và lớp.
7. Bấm **Excel**, mở file và kiểm tra 7 sheet.
8. Bấm **PDF**, kiểm tra trang tổng quan và các trang chi tiết công nợ.
9. Vào **Lịch sử hệ thống**, tìm action `EXPORT` và entity `finance_report`.

### 9.3. Vì sao xuất Excel được

- API `/reports/finance` tính một mô hình báo cáo duy nhất từ invoice, payment SUCCESS/REVERSED hợp lệ và refund COMPLETED.
- Apache POI `XSSFWorkbook` tạo workbook `.xlsx` trực tiếp trong bộ nhớ.
- Hệ thống tạo 7 sheet: Tổng quan, Theo ngày, Theo phương thức, Công nợ đợt thu, Công nợ khối, Công nợ lớp, Chi tiết công nợ.
- Số tiền được ghi dạng số và gán format VND, không phải chuỗi chụp từ giao diện.
- Controller trả đúng MIME type và `Content-Disposition`, FE nhận byte rồi kích hoạt tải file.

### 9.4. Vì sao xuất PDF được

- Cùng mô hình báo cáo với Excel được đưa vào `FinanceReportPdfRenderer`.
- Renderer vẽ trang A4 ngang ở độ phân giải cố định: KPI, dòng tiền, phương thức và bảng công nợ.
- Chi tiết công nợ chia 20 dòng mỗi trang để không tràn nội dung.
- PDFBox đóng các trang đã vẽ thành byte PDF chuẩn `%PDF-`.
- Controller trả file cho trình duyệt và ghi Audit ngay sau khi tạo thành công.

### 9.5. Công thức báo cáo

- Tổng phải thu: tổng `invoice.totalAmount` của invoice còn hiệu lực.
- Đã ghi nhận hiện tại: tổng `invoice.paidAmount` sau hoàn tiền.
- Còn phải thu: `max(totalAmount - paidAmount, 0)`.
- Thực thu trong kỳ: tổng payment đã quyết toán theo `paidAt`.
- Hoàn tiền trong kỳ: tổng refund `COMPLETED` theo `completedAt`.
- Doanh thu ròng: thực thu trừ hoàn tiền.
- Quá hạn: invoice còn nợ và có `dueDate` trước ngày hiện tại.
- Invoice `CANCELLED` và `VOID` không được tính vào phải thu/công nợ.

## 10. Notification và badge học phí

1. Backend publish event khi phát hành, nhắc nợ, thanh toán, duyệt biên lai hoặc hoàn tiền.
2. RabbitMQ giữ event trong queue; notification worker consume và tạo inbox cho đúng Student/Parent/Admin.
3. Một Parent có nhiều con nhận notification cho từng invoice của từng con thuộc phạm vi.
4. FE hỏi `/notifications/finance/unread-count` mỗi 10 giây và khi cửa sổ được focus.
5. Số này được hiển thị cạnh tab **Học phí**.
6. Khi Parent tích đã đọc cảnh báo và tiếp tục, FE gọi `/notifications/finance/read-all`; badge finance trở về 0.
7. Bấm notification loại INVOICE/PAYMENT sẽ điều hướng thẳng tới trang **Học phí**.

## 11. Điểm cần phân biệt khi nghiệm thu

- **Ảnh biên lai**: ảnh JPG/PNG phụ huynh gửi để Admin đối chiếu chuyển khoản MB.
- **Biên nhận PDF**: chứng từ do nhà trường sinh sau khi payment đã SUCCESS.
- **QR VietQR**: điền sẵn dữ liệu chuyển khoản nhưng không tự chứng minh tiền đã vào tài khoản.
- **Return URL**: chỉ hiển thị trạng thái; IPN/callback hợp lệ mới xác nhận tiền VNPAY/MoMo.
- **Báo cáo Excel/PDF**: được sinh từ dữ liệu backend tại thời điểm tải, không phải ảnh chụp dashboard.

## 12. Hạn chế còn lại trước production

1. VNPAY và MoMo cần merchant sandbox thật, public HTTPS callback và một vòng UAT với nhà cung cấp.
2. Tài khoản MB cá nhân chưa có API sao kê tự động; Admin vẫn phải đối chiếu ảnh biên lai với ứng dụng/ngân hàng.
3. Dev đang dùng Hibernate `ddl-auto=update` kết hợp SQL patch; production nên chuyển toàn bộ schema sang Flyway có version.
4. Các smoke P4/P5 tạo dữ liệu test có tiền tố `P42`, `P43`, `P44`, `P5`; không chạy trên database production.
5. QR MB được tạo sẵn dưới dạng payment `PENDING`. Nếu invoice được thanh toán bằng phương thức khác, payment QR chưa dùng vẫn có thể còn trong lịch sử ở trạng thái `PENDING`; nó không được tính vào doanh thu nhưng nên được đóng tự động trong vòng hoàn thiện tiếp theo để lịch sử gọn hơn.

## 13. Checklist nghiệm thu cuối

- [ ] Tạo trùng mã đợt thu bị chặn.
- [ ] Phát hành hai lần không tạo invoice trùng.
- [ ] Parent không xem được invoice/payment của học sinh khác.
- [ ] QR MB có đúng tài khoản, số tiền, mã và tên học sinh.
- [ ] Chỉ ảnh JPG/PNG dưới 5MB được gửi làm biên lai.
- [ ] Admin yêu cầu thanh toán lại bắt buộc có lý do.
- [ ] Payment chỉ SUCCESS sau Admin duyệt MB hoặc IPN hợp lệ.
- [ ] Callback gửi lại 10 lần không cộng tiền thêm.
- [ ] Biên nhận PDF tải được từ MinIO.
- [ ] Đối soát phát hiện được dữ liệu sai lệch.
- [ ] Không hoàn vượt số tiền đã thu.
- [ ] Admin tạo yêu cầu không tự duyệt được.
- [ ] Doanh thu ròng bằng thực thu trừ hoàn tiền.
- [ ] Excel có đủ 7 sheet và PDF không tràn bảng.
- [ ] Mỗi lần export có Audit Log.
