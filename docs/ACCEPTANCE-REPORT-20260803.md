# Báo cáo nghiệm thu phục hồi hệ thống — 03/08/2026

## Kết quả

- Backend và PostgreSQL chính đang chạy ổn định trên database sạch được tạo hoàn toàn từ Flyway V1–V63.
- Đã loại bỏ 25 bản ghi catalog `pg_index` mồ côi khỏi quá trình chuyển đổi; database mới có `0` index mồ côi, `0` index không hợp lệ và `0` constraint chưa được xác thực.
- 35 khóa ngoại đã được đối chiếu, không có bản ghi mồ côi.
- Health check mới kiểm tra catalog và 6 bảng nghiệp vụ trọng yếu, vì vậy không còn tình trạng PostgreSQL kết nối được nhưng nghiệp vụ hỏng mà vẫn báo `UP`.
- Các API thiếu tham số bắt buộc trả `400` cùng mã lỗi rõ ràng thay vì `500`.
- API điểm và điểm danh từ chối tải toàn trường khi không có bộ lọc, tránh phản hồi rất lớn trên dữ liệu vận hành.

## Đối chiếu dữ liệu chính

| Bảng | Nguồn | Database sạch | Ghi chú |
|---|---:|---:|---|
| users | 4.076 | 4.076 | Bảo toàn |
| academic_years | 2 | 2 | Bảo toàn |
| semesters | 4 | 4 | Bảo toàn |
| classes | 72 | 72 | Bảo toàn |
| subjects | 12 | 12 | Bảo toàn |
| class_enrollments | 3.000 | 3.000 | Bảo toàn |
| grades | 208.800 | 208.800 | Bảo toàn |
| attendance_records | 7.500 | 7.500 | Bảo toàn |
| report_cards | 3.000 | 3.000 | Bảo toàn |
| student_yearly_summaries | 3.000 | 3.000 | Bảo toàn |
| invoices | 3.000 | 3.000 | Bảo toàn |
| payments | 1.500 | 1.500 | Bảo toàn |
| exam_rooms | 151 | 102 | Loại 49 bản ghi trùng khóa chính |

Thông báo tăng sau nghiệm thu vì hệ thống thực hiện các tác vụ nhắc việc hợp lệ trên dữ liệu thật.

## Kết quả kiểm thử

- Backend: `72/72` test đạt; migration sạch đạt V63; Docker production image build thành công.
- Web: ESLint đạt; `47/47` unit test đạt; TypeScript và Vite production build đạt.
- Web E2E: `23/23` đạt trên PostgreSQL thật cho Admin, Giáo vụ, Kế toán, Giáo viên, Học sinh và Phụ huynh.
- Mobile V2: `flutter analyze` không có lỗi; unit test đạt; `3/3` integration test đạt trên API thật; Web build và APK debug build thành công.
- Dependency Mobile: đã thay `jni 1.0.1` bị thu hồi bằng `jni 1.0.3`, đồng thời nâng `jni_flutter` lên `1.0.2`.

## Trạng thái vận hành

- Volume đang dùng: `school-management-system-be_postgres-clean-v63-20260803-120858`.
- Volume nguồn cũ và volume phục hồi vẫn được giữ, chưa xóa.
- `/actuator/health`: `UP`.
- Không ghi nhận lỗi `OID`, catalog, HTTP 500 nghiệp vụ hoặc lỗi scheduler trong log sau chuyển đổi.

## Giới hạn còn lại

- Mobile Web JavaScript build hoạt động, nhưng Flutter vẫn cảnh báo WebAssembly do `flutter_secure_storage_web` 1.x sử dụng `dart:html`/`dart:js_util`. Đây không ảnh hưởng APK hoặc Web JavaScript hiện tại; chỉ cần xử lý khi chọn phát hành WebAssembly.
- Integration test Mobile hiện chạy bằng `flutter-tester`; trước khi phát hành store nên bổ sung một vòng chạy trên thiết bị Android thật hoặc emulator trong CI.

## Hoàn tác an toàn

1. Dừng stack bằng `docker compose --env-file .env.local down`.
2. Đổi `SSE_POSTGRES_VOLUME` trong `.env.local` sang `school-management-system-be_postgres-rollback-20260803-120643`.
3. Chạy lại `docker compose --env-file .env.local up -d` và kiểm tra `/actuator/health`.

Không dùng volume nguồn forensic `school-management-system-be_postgres-data` để vận hành trực tiếp vì nó vẫn chứa catalog lỗi ban đầu.
