# Báo cáo khắc phục toàn hệ thống — 02/08/2026

## Phạm vi nghiệm thu

- Web FE, Backend và PostgreSQL thật với 4.076 tài khoản, 2.000 học sinh.
- Sáu vai trò: Quản trị, Giáo vụ, Kế toán, Giáo viên, Học sinh, Phụ huynh.
- Năm học, phân công/thời khóa biểu, khảo thí, học bạ/tổng kết, người dùng/import, báo cáo và phân quyền.

## Kết quả trước và sau

| Hạng mục | Trước | Sau |
|---|---:|---:|
| Lớp trong bộ lọc TKB | Trộn và trùng giữa hai năm | 36 lớp duy nhất/năm |
| Học kỳ trong bộ lọc TKB | Trùng HK1/HK2 | 2 học kỳ duy nhất/năm |
| API người dùng cũ | 1,22 MB; khoảng 685 ms | Bị giới hạn 200 bản ghi, projection an toàn; khoảng 75 ms |
| API người dùng phân trang 20 dòng | Chưa được dùng nhất quán | 14,9 KB; khoảng 20 ms |
| Phân bố điểm | Tải dữ liệu lớn; khoảng 1.546 ms | SQL aggregation; khoảng 55 ms |
| Học bạ lớp | Có thể treo trên danh sách toàn trường | Tải theo lớp/trang; khoảng 38 ms (20 dòng) |
| Tên học sinh | 97 tên/2.000 tài khoản | 2.000 tên phân biệt |
| Tên phụ huynh | 97 tên/2.000 tài khoản | 2.000 tên phân biệt |
| Tên giáo viên | 38 tên/73 tài khoản | 73 tên phân biệt |
| Xung đột giám thị/phòng/thí sinh | Có dữ liệu giám thị trùng | 0 xung đột |
| Học bạ năm đóng | 300 hồ sơ chưa phát hành | 1.500/1.500 đã phát hành |
| Bundle màn chức năng | Một chunk khoảng 580 KB | Lazy chunks; lớn nhất nhóm chức năng dưới 100 KB |

## Các thay đổi chính

1. Đồng bộ Năm học → Khối → Lớp → Học kỳ trên FE/BE và URL; loại năm `CLOSED` khỏi màn vận hành.
2. Từ chối tổ hợp lớp, học kỳ, kỳ thi và báo cáo khác năm học.
3. Chuyển báo cáo điểm/chuyên cần sang SQL aggregation và bổ sung index cho dữ liệu lớn.
4. Học bạ tải theo cây Niên khóa → Năm → Lớp → Học sinh và phân trang server.
5. Chặn đóng/chuyển năm khi chưa đủ học bạ khóa hoặc phát hành; tổng kết năm cũ lấy đủ 12 môn của cả hai học kỳ.
6. Chặn trùng email, mã học sinh, mã giáo viên ở service, import Excel và database.
7. Import kiểm tra trùng trong file và với database trước khi commit.
8. Ràng buộc khảo thí chặn trùng giám thị trong cùng phòng, giữa các phòng và các ca thi giao nhau; dọn dữ liệu xung đột.
9. Dữ liệu tên được sửa đồng bộ tại hóa đơn, học bạ, khảo thí, bài tập, thời khóa biểu, tin nhắn và lịch sử.
10. Tách container PostgreSQL RBAC cũ khỏi network ứng dụng; alias `postgres` chỉ còn một IP, Backend khởi động ổn định.
11. Tách lazy chunk theo nhóm chức năng và sửa toàn bộ cảnh báo/error React hooks.

## Kết quả kiểm thử

- Backend Maven/integration: **68/68 đạt**.
- Web ESLint: **0 lỗi, 0 cảnh báo**.
- Web unit test: **47/47 đạt**.
- Web TypeScript + Vite production build: **đạt**, không còn cảnh báo chunk quá lớn.
- Playwright E2E PostgreSQL thật: **23/23 đạt**.
- Sáu tài khoản UAT đăng nhập đúng vai trò; reload giữ phiên; URL sai vai trò bị chặn.
- Không có HTTP 5xx/console error trong toàn bộ điều hướng UAT.
- Backend/PostgreSQL sau khi tách network: healthy, restart count 0, không có error mới.

## Trạng thái dữ liệu nghiệm thu

- `ay-2025`: `CLOSED`, 1.500 học sinh, đủ tổng kết hai học kỳ, 1.500 học bạ `PUBLISHED`.
- `ay-2026`: `ACTIVE`, 1.500 học sinh đang học; dữ liệu học tập đang phát sinh.
- Flyway hiện tại: **V62**.
- UTF-8: không phát hiện chuỗi mojibake trong tên lớp hoặc tên người dùng.
- Trùng định danh email/mã học sinh/mã giáo viên: **0**.
- Tên snapshot lệch với tài khoản nguồn trong các bảng trọng yếu: **0**.
