# Danh mục 89 bảng PostgreSQL và ánh xạ chức năng

Ngày sinh tài liệu: 22/08/2026. Nguồn sự thật: `information_schema` của database Full Demo đã migrate tới phiên bản hiện tại; không suy đoán bảng từ ảnh hoặc tên màn hình.

- Tổng bảng: **89** (gồm `flyway_schema_history`).
- Tổng khóa ngoại: **220**.
- Quan hệ phụ huynh–con được lưu tại `parent_student`.
- Người phụ trách nhiều giáo viên của kế hoạch kiểm tra được lưu tại `academic_assessment_plan_teachers`.

## Tổng quan theo miền

| Miền | Số bảng | Chức năng chính |
|---|---:|---|
| Identity & Access | 11 | `audit_logs`, `login_history`, `parent_student`, `password_reset_tokens`, `permissions`, `refresh_tokens`, `role_permissions`, `roles`, `user_devices`, `user_roles`, `users` |
| Cơ cấu đào tạo | 14 | `academic_years`, `class_subject_combinations`, `classes`, `grade_levels`, `rooms`, `school_holidays`, `semesters`, `student_class_enrollments`, `subject_combination_subjects`, `subject_combinations`, `subjects`, `teacher_class_subjects`, `teacher_staffing_policies`, `teacher_subject_capabilities` |
| Chương trình & kế hoạch giáo dục | 12 | `academic_assessment_plan_teachers`, `academic_assessment_plans`, `academic_curriculum_distributions`, `academic_curriculum_items`, `academic_exam_schedules`, `academic_plan_approval_history`, `academic_training_plan_special_weeks`, `academic_training_plan_stages`, `academic_training_plan_subjects`, `academic_training_plans`, `education_program_subjects`, `education_programs` |
| Thời khóa biểu & tiến độ | 5 | `class_lesson_progress`, `timetable_draft_slots`, `timetable_makeup_proposals`, `timetable_schedules`, `timetable_slots` |
| Dạy học hằng ngày | 9 | `assignment_submission_versions`, `assignment_submissions`, `assignments`, `attendance_excuse_requests`, `attendance_records`, `grade_change_logs`, `grade_configurations`, `grades`, `submission_resubmission_requests` |
| Khảo thí | 7 | `exam_categories`, `exam_periods`, `exam_room_assignments`, `exam_room_students`, `exam_schedule_versions`, `exam_sessions`, `exam_teacher_unavailability` |
| Tài chính | 15 | `bank_statement_entries`, `fee_period_item_targets`, `fee_period_items`, `fee_period_targets`, `fee_periods`, `invoice_items`, `invoices`, `payment_gateway_transactions`, `payment_proofs`, `payment_receipts`, `payment_reconciliation_issues`, `payment_reconciliation_method_summaries`, `payment_reconciliation_runs`, `payment_refunds`, `payments` |
| Thông báo, chat, file & ngoại khóa | 9 | `announcements`, `chat_messages`, `club_registrations`, `clubs`, `notification_delivery_logs`, `notification_templates`, `notifications`, `stored_files`, `user_notification_preferences` |
| Tổng kết & chuyển năm | 6 | `academic_promotion_policies`, `academic_result_locks`, `homeroom_remarks`, `student_yearly_summaries`, `year_result_publication_history`, `year_result_publications` |
| Hạ tầng schema | 1 | `flyway_schema_history` |

## Chi tiết từng bảng

### Identity & Access

| Bảng | Chức năng lưu trữ | Quan hệ cha trực tiếp (FK) |
|---|---|---|
| `audit_logs` | Audit mutation nghiệp vụ quan trọng như sửa điểm, thanh toán, công bố và cấu hình. | Không có FK cha |
| `login_history` | Lịch sử đăng nhập phục vụ bảo mật; đã loại khỏi màn Audit nghiệp vụ. | `user_id` → `users.id` |
| `parent_student` | Bảng nối nhiều-nhiều phụ huynh–học sinh; nguồn scope cho toàn bộ dữ liệu con. | `parent_id` → `users.id`<br>`student_id` → `users.id` |
| `password_reset_tokens` | Token đặt lại mật khẩu một lần, thời hạn và thời điểm đã sử dụng. | `user_id` → `users.id` |
| `permissions` | Danh mục quyền kỹ thuật dùng RBAC. | Không có FK cha |
| `refresh_tokens` | Phiên refresh token đã hash, hạn dùng và trạng thái thu hồi. | `device_id` → `user_devices.id`<br>`replaced_by_token_id` → `refresh_tokens.id`<br>`user_id` → `users.id` |
| `role_permissions` | Bảng nối role–permission. | `permission_id` → `permissions.id`<br>`role_id` → `roles.id` |
| `roles` | Danh mục bốn role chính và metadata phân quyền. | Không có FK cha |
| `user_devices` | Thiết bị/FCM token của người dùng phục vụ push notification. | `user_id` → `users.id` |
| `user_roles` | Bảng nối tài khoản–role; source hiện vẫn đồng bộ thêm cột role trên users. | `role_id` → `roles.id`<br>`user_id` → `users.id` |
| `users` | Tài khoản, mã hệ thống, hồ sơ, email, điện thoại, hash mật khẩu và trạng thái. | `class_id` → `classes.id` |

### Cơ cấu đào tạo

| Bảng | Chức năng lưu trữ | Quan hệ cha trực tiếp (FK) |
|---|---|---|
| `academic_years` | Danh mục năm học và trạng thái hoạt động/đã đóng. | Không có FK cha |
| `class_subject_combinations` | Gán tổ hợp môn lựa chọn cho từng lớp. | `assigned_by` → `users.id`<br>`class_id` → `classes.id`<br>`combination_id` → `subject_combinations.id` |
| `classes` | Lớp học theo năm/khối, GVCN, phòng chủ nhiệm, sĩ số và sức chứa tối đa. | `academic_year_id` → `academic_years.id`<br>`grade_level` → `grade_levels.code`<br>`home_room_id` → `rooms.id`<br>`homeroom_teacher_id` → `users.id` |
| `grade_levels` | Danh mục khối K10/K11/K12 và các quy tắc cấp học. | Không có FK cha |
| `rooms` | Phòng thường/phòng bộ môn/nhà thể chất và sức chứa. | Không có FK cha |
| `school_holidays` | Ngày nghỉ/lịch đặc biệt dùng validator kế hoạch, TKB và tiến độ. | `academic_year_id` → `academic_years.id` |
| `semesters` | Hai học kỳ thuộc năm học và phạm vi ngày hợp lệ. | `academic_year_id` → `academic_years.id` |
| `student_class_enrollments` | Lịch sử học sinh thuộc lớp nào trong năm học. | `academic_year_id` → `academic_years.id`<br>`class_id` → `classes.id`<br>`enrolled_by` → `users.id`<br>`reverted_by` → `users.id`<br>`source_academic_year_id` → `academic_years.id`<br>`source_class_id` → `classes.id`<br>`source_summary_id` → `student_yearly_summaries.id`<br>`student_id` → `users.id` |
| `subject_combination_subjects` | Bảng nối tổ hợp–môn. | `combination_id` → `subject_combinations.id`<br>`subject_id` → `subjects.id` |
| `subject_combinations` | Danh mục tổ hợp môn lựa chọn theo năm và khối. | `academic_year_id` → `academic_years.id`<br>`grade_level` → `grade_levels.code` |
| `subjects` | Danh mục môn/hoạt động giáo dục và loại phòng yêu cầu. | Không có FK cha |
| `teacher_class_subjects` | Phân công giáo viên dạy lớp–môn–học kỳ. | `class_id` → `classes.id`<br>`semester_id` → `semesters.id`<br>`subject_id` → `subjects.id`<br>`teacher_id` → `users.id` |
| `teacher_staffing_policies` | Định mức giáo viên/lớp và ngưỡng tải dùng phân tích nhân sự. | `academic_year_id` → `academic_years.id` |
| `teacher_subject_capabilities` | Chuyên môn/capability giáo viên theo môn; ngăn phân công sai chuyên môn. | `subject_id` → `subjects.id`<br>`teacher_id` → `users.id` |

### Chương trình & kế hoạch giáo dục

| Bảng | Chức năng lưu trữ | Quan hệ cha trực tiếp (FK) |
|---|---|---|
| `academic_assessment_plan_teachers` | Danh sách nhiều giáo viên cùng phụ trách một mốc kiểm tra; nối kế hoạch đánh giá với tài khoản giáo viên. | `assessment_plan_id` → `academic_assessment_plans.id`<br>`teacher_id` → `users.id` |
| `academic_assessment_plans` | Mốc kiểm tra/đánh giá của kế hoạch giáo dục theo môn, học kỳ, lớp hoặc toàn khối. | `class_id` → `classes.id`<br>`plan_id` → `academic_training_plans.id`<br>`semester_id` → `semesters.id`<br>`subject_id` → `subjects.id`<br>`teacher_id` → `users.id` |
| `academic_curriculum_distributions` | Phân phối số tiết của nội dung môn học theo tuần và học kỳ. | `curriculum_item_id` → `academic_curriculum_items.id`<br>`plan_subject_id` → `academic_training_plan_subjects.id` |
| `academic_curriculum_items` | Cây chương, chủ đề và bài học của từng môn trong kế hoạch. | `parent_id` → `academic_curriculum_items.id`<br>`plan_subject_id` → `academic_training_plan_subjects.id` |
| `academic_exam_schedules` | Lịch kiểm tra dự kiến được khai báo ngay trong kế hoạch giáo dục. | `grade_level` → `grade_levels.code`<br>`plan_id` → `academic_training_plans.id`<br>`proctor_teacher_id` → `users.id`<br>`room_id` → `rooms.id`<br>`semester_id` → `semesters.id`<br>`subject_id` → `subjects.id` |
| `academic_plan_approval_history` | Lịch sử khóa, công bố, trả về hoặc thay đổi trạng thái kế hoạch. | `actor_id` → `users.id`<br>`plan_id` → `academic_training_plans.id` |
| `academic_training_plan_special_weeks` | Tuần nghỉ, tuần kiểm tra hoặc tuần đặc biệt làm thay đổi phân phối chương trình. | `plan_subject_id` → `academic_training_plan_subjects.id` |
| `academic_training_plan_stages` | Các giai đoạn thực hiện một môn trong kế hoạch giáo dục. | `plan_subject_id` → `academic_training_plan_subjects.id` |
| `academic_training_plan_subjects` | Môn, học kỳ và tổng số tiết thuộc một phiên bản kế hoạch giáo dục. | `plan_id` → `academic_training_plans.id`<br>`semester_id` → `semesters.id`<br>`subject_id` → `subjects.id` |
| `academic_training_plans` | Aggregate kế hoạch giáo dục theo năm, khối, chương trình, phiên bản và lifecycle. | `academic_year_id` → `academic_years.id`<br>`based_on_plan_id` → `academic_training_plans.id`<br>`grade_level` → `grade_levels.code`<br>`locked_by` → `users.id`<br>`program_id` → `education_programs.id`<br>`published_by` → `users.id` |
| `education_program_subjects` | Môn và số tiết HK1/HK2/cả năm của chương trình theo khối. | `grade_level` → `grade_levels.code`<br>`program_id` → `education_programs.id`<br>`subject_id` → `subjects.id` |
| `education_programs` | Chương trình giáo dục nháp/đang áp dụng/đã lưu trữ. | Không có FK cha |

### Thời khóa biểu & tiến độ

| Bảng | Chức năng lưu trữ | Quan hệ cha trực tiếp (FK) |
|---|---|---|
| `class_lesson_progress` | Tiến độ thực dạy theo lớp, môn, bài học và ngày học. | `academic_year_id` → `academic_years.id`<br>`class_id` → `classes.id`<br>`curriculum_item_id` → `academic_curriculum_items.id`<br>`semester_id` → `semesters.id`<br>`subject_id` → `subjects.id`<br>`teacher_id` → `users.id` |
| `timetable_draft_slots` | Các tiết trong bản nháp do bộ xếp lịch tạo. | `assignment_id` → `teacher_class_subjects.id`<br>`class_id` → `classes.id`<br>`room_id` → `rooms.id`<br>`schedule_id` → `timetable_schedules.id`<br>`semester_id` → `semesters.id`<br>`subject_id` → `subjects.id`<br>`teacher_id` → `users.id` |
| `timetable_makeup_proposals` | Đề xuất ngày/tiết/phòng dạy bù và trạng thái duyệt. | `class_id` → `classes.id`<br>`reviewed_by` → `users.id`<br>`schedule_id` → `timetable_schedules.id`<br>`subject_id` → `subjects.id`<br>`teacher_id` → `users.id` |
| `timetable_schedules` | Phiên bản TKB nháp/đã công bố theo năm, học kỳ và phạm vi khối. | `academic_year_id` → `academic_years.id`<br>`generated_by` → `users.id`<br>`published_by` → `users.id`<br>`scope_grade_level` → `grade_levels.code`<br>`semester_id` → `semesters.id` |
| `timetable_slots` | Tiết học chính thức đã phát hành cho lớp, môn, giáo viên và phòng. | `class_id` → `classes.id`<br>`semester_id` → `semesters.id`<br>`source_schedule_id` → `timetable_schedules.id`<br>`subject_id` → `subjects.id`<br>`teacher_id` → `users.id` |

### Dạy học hằng ngày

| Bảng | Chức năng lưu trữ | Quan hệ cha trực tiếp (FK) |
|---|---|---|
| `assignment_submission_versions` | Lưu từng lần nộp bài để không mất lịch sử khi học sinh nộp lại. | `attachment_file_id` → `stored_files.id`<br>`submission_id` → `assignment_submissions.id`<br>`submitted_by` → `users.id` |
| `assignment_submissions` | Bài nộp hiện tại, điểm, feedback và trạng thái chấm của học sinh. | `assignment_id` → `assignments.id`<br>`attachment_file_id` → `stored_files.id`<br>`graded_by` → `users.id`<br>`student_id` → `users.id` |
| `assignments` | Bài tập nháp/đã phát hành theo lớp, môn và giáo viên. | `attachment_file_id` → `stored_files.id`<br>`class_id` → `classes.id`<br>`subject_id` → `subjects.id`<br>`teacher_id` → `users.id` |
| `attendance_excuse_requests` | Đơn xin nghỉ và chuỗi xác nhận phụ huynh/GVCN. | `attendance_record_id` → `attendance_records.id`<br>`requested_by` → `users.id`<br>`reviewed_by` → `users.id`<br>`student_id` → `users.id` |
| `attendance_records` | Điểm danh theo học sinh, lớp, tiết và ngày; có optimistic version. | `class_id` → `classes.id`<br>`slot_id` → `timetable_slots.id`<br>`student_id` → `users.id` |
| `grade_change_logs` | Before/after, lý do, actor và thời gian mỗi lần sửa điểm. | `grade_id` → `grades.id` |
| `grade_configurations` | Cấu hình loại đầu điểm, số cột và trọng số theo môn/học kỳ. | `semester_id` → `semesters.id`<br>`subject_id` → `subjects.id`<br>`updated_by` → `users.id` |
| `grades` | Điểm theo học sinh, môn, học kỳ, loại và assessmentIndex. | `semester_id` → `semesters.id`<br>`student_id` → `users.id`<br>`subject_id` → `subjects.id` |
| `submission_resubmission_requests` | Yêu cầu cho phép nộp lại và quyết định giáo viên. | `assignment_id` → `assignments.id`<br>`requested_by` → `users.id`<br>`student_id` → `users.id`<br>`submission_id` → `assignment_submissions.id` |

### Khảo thí

| Bảng | Chức năng lưu trữ | Quan hệ cha trực tiếp (FK) |
|---|---|---|
| `exam_categories` | Danh mục loại/hệ số đầu điểm dùng bảng điểm và khảo thí. | Không có FK cha |
| `exam_periods` | Đợt thi theo năm, học kỳ và trạng thái công bố/khóa. | `academic_year_id` → `academic_years.id`<br>`created_by` → `users.id`<br>`published_version_id` → `exam_schedule_versions.id`<br>`semester_id` → `semesters.id` |
| `exam_room_assignments` | Phòng thi và giám thị chính/dự phòng cho từng ca thi. | `backup_proctor_id` → `users.id`<br>`primary_proctor_id` → `users.id`<br>`room_id` → `rooms.id`<br>`session_id` → `exam_sessions.id` |
| `exam_room_students` | Phân phòng, số báo danh/chỗ ngồi của học sinh trong ca thi. | `class_id` → `classes.id`<br>`room_assignment_id` → `exam_room_assignments.id`<br>`session_id` → `exam_sessions.id`<br>`student_id` → `users.id` |
| `exam_schedule_versions` | Phiên bản lịch thi nháp/đã công bố và quan hệ phiên bản nguồn. | `based_on_version_id` → `exam_schedule_versions.id`<br>`created_by` → `users.id`<br>`exam_period_id` → `exam_periods.id` |
| `exam_sessions` | Ca thi, môn thi, thời gian và nguồn kế hoạch đánh giá. | `source_assessment_plan_id` → `academic_assessment_plans.id`<br>`source_training_plan_id` → `academic_training_plans.id`<br>`subject_id` → `subjects.id`<br>`version_id` → `exam_schedule_versions.id` |
| `exam_teacher_unavailability` | Khung thời gian giáo viên không thể coi/chấm thi. | `created_by` → `users.id`<br>`exam_period_id` → `exam_periods.id`<br>`teacher_id` → `users.id` |

### Tài chính

| Bảng | Chức năng lưu trữ | Quan hệ cha trực tiếp (FK) |
|---|---|---|
| `bank_statement_entries` | Dòng sao kê ngân hàng dùng ghép hóa đơn/thanh toán khi đối soát. | `matched_invoice_id` → `invoices.id`<br>`matched_payment_id` → `payments.id` |
| `fee_period_item_targets` | Đối tượng áp dụng riêng cho từng khoản trong một đợt thu. | `fee_period_item_id` → `fee_period_items.id` |
| `fee_period_items` | Các khoản tiền cấu thành một đợt thu. | `fee_period_id` → `fee_periods.id` |
| `fee_period_targets` | Phạm vi lớp hoặc học sinh của toàn đợt thu. | `fee_period_id` → `fee_periods.id` |
| `fee_periods` | Đợt thu theo năm/học kỳ và lifecycle mở/đóng. | `academic_year_id` → `academic_years.id`<br>`semester_id` → `semesters.id` |
| `invoice_items` | Snapshot chi tiết khoản thu trong một hóa đơn. | `fee_period_item_id` → `fee_period_items.id`<br>`invoice_id` → `invoices.id` |
| `invoices` | Công nợ/hóa đơn của từng học sinh và trạng thái thanh toán. | `fee_period_id` → `fee_periods.id`<br>`parent_id` → `users.id`<br>`student_id` → `users.id` |
| `payment_gateway_transactions` | Request/response/idempotency/signature của giao dịch cổng thanh toán. | `payment_id` → `payments.id` |
| `payment_proofs` | Minh chứng chuyển khoản VietQR để Admin xác nhận hoặc từ chối. | `file_id` → `stored_files.id`<br>`invoice_id` → `invoices.id`<br>`parent_id` → `users.id`<br>`payment_id` → `payments.id`<br>`student_id` → `users.id` |
| `payment_receipts` | Biên nhận đã phát hành sau thanh toán/đối soát thành công. | `file_id` → `stored_files.id`<br>`invoice_id` → `invoices.id`<br>`parent_id` → `users.id`<br>`payment_id` → `payments.id`<br>`previous_file_id` → `stored_files.id`<br>`student_id` → `users.id` |
| `payment_reconciliation_issues` | Sai lệch phát hiện trong một lần đối soát. | `run_id` → `payment_reconciliation_runs.id` |
| `payment_reconciliation_method_summaries` | Tổng hợp số tiền theo phương thức trong một lần đối soát. | `run_id` → `payment_reconciliation_runs.id` |
| `payment_reconciliation_runs` | Phiên đối soát, tổng thực tế, chênh lệch và trạng thái chốt. | Không có FK cha |
| `payment_refunds` | Yêu cầu/ghi nhận hoàn tiền một phần hoặc toàn phần. | `invoice_id` → `invoices.id`<br>`parent_id` → `users.id`<br>`payment_id` → `payments.id`<br>`student_id` → `users.id` |
| `payments` | Khoản thanh toán cho hóa đơn, phương thức, trạng thái và số tiền. | `invoice_id` → `invoices.id` |

### Thông báo, chat, file & ngoại khóa

| Bảng | Chức năng lưu trữ | Quan hệ cha trực tiếp (FK) |
|---|---|---|
| `announcements` | Thông báo/bản tin do nhà trường hoặc giáo viên phát hành theo phạm vi người nhận. | Không có FK cha |
| `chat_messages` | Tin nhắn trực tiếp giữa các tài khoản trong phạm vi quan hệ cho phép. | `recipient_id` → `users.id`<br>`sender_id` → `users.id` |
| `club_registrations` | Đăng ký ngoại khóa/CLB, trạng thái duyệt và hóa đơn phí nếu có. | `club_id` → `clubs.id`<br>`fee_period_id` → `fee_periods.id`<br>`invoice_id` → `invoices.id`<br>`registered_by` → `users.id`<br>`student_id` → `users.id` |
| `clubs` | Danh mục ngoại khóa/CLB miễn phí hoặc có phí. | Không có FK cha |
| `notification_delivery_logs` | Log từng lần gửi email/push, phản hồi provider và lỗi retry. | `notification_id` → `notifications.id` |
| `notification_templates` | Mẫu nội dung thông báo theo loại sự kiện/kênh. | Không có FK cha |
| `notifications` | Hộp thư in-app/email/push theo người nhận, trạng thái đọc và delivery. | `recipient_id` → `users.id` |
| `stored_files` | Metadata file riêng tư, owner, MIME, object key và phạm vi truy cập. | Không có FK cha |
| `user_notification_preferences` | Tùy chọn bật/tắt kênh thông báo theo người dùng và loại. | `user_id` → `users.id` |

### Tổng kết & chuyển năm

| Bảng | Chức năng lưu trữ | Quan hệ cha trực tiếp (FK) |
|---|---|---|
| `academic_promotion_policies` | Chính sách xét lên lớp, ở lại lớp và điều kiện tổng kết theo năm học. | `academic_year_id` → `academic_years.id`<br>`updated_by` → `users.id` |
| `academic_result_locks` | Khóa kết quả của lớp/học kỳ/năm để ngăn sửa dữ liệu sau khi chốt. | `academic_year_id` → `academic_years.id`<br>`class_id` → `classes.id`<br>`locked_by` → `users.id`<br>`semester_id` → `semesters.id` |
| `homeroom_remarks` | Nhận xét GVCN theo học sinh, lớp, học kỳ và năm. | `academic_year_id` → `academic_years.id`<br>`class_id` → `classes.id`<br>`semester_id` → `semesters.id`<br>`student_id` → `users.id`<br>`teacher_id` → `users.id` |
| `student_yearly_summaries` | Kết quả tổng kết năm, học lực/hạnh kiểm và quyết định lên lớp. | `academic_year_id` → `academic_years.id`<br>`class_id` → `classes.id`<br>`finalized_by` → `users.id`<br>`next_class_id` → `classes.id`<br>`progressed_by` → `users.id`<br>`reviewed_by` → `users.id`<br>`student_id` → `users.id` |
| `year_result_publication_history` | Lịch sử công bố/hủy công bố kết quả năm. | `academic_year_id` → `academic_years.id`<br>`actor_id` → `users.id`<br>`class_id` → `classes.id`<br>`publication_id` → `year_result_publications.id` |
| `year_result_publications` | Trạng thái công bố kết quả theo năm/lớp/học sinh. | `academic_year_id` → `academic_years.id`<br>`class_id` → `classes.id`<br>`published_by` → `users.id`<br>`withdrawn_by` → `users.id` |

### Hạ tầng schema

| Bảng | Chức năng lưu trữ | Quan hệ cha trực tiếp (FK) |
|---|---|---|
| `flyway_schema_history` | Lịch sử migration Flyway; không phải dữ liệu nghiệp vụ. | Không có FK cha |

## Audit chức năng Web và nơi lưu dữ liệu

| Chức năng Web | Bảng lưu chính | Kết luận |
|---|---|---|
| Liên kết một phụ huynh với nhiều con | `parent_student` | **Có bảng**; lỗi trước đây nằm ở UI/API, không phải thiếu schema. |
| Kế hoạch kiểm tra có nhiều người phụ trách | `academic_assessment_plans`, `academic_assessment_plan_teachers` | **Có bảng sau V58**; giữ `teacher_id` làm người chính để tương thích dữ liệu cũ. |
| Chương trình/kế hoạch/phiên bản/công bố | `education_programs`, `education_program_subjects`, `academic_training_plans`, `academic_plan_approval_history` | **Có đầy đủ persistence**. |
| Auto timetable và bản nháp | `timetable_schedules`, `timetable_draft_slots`, `timetable_slots` | **Có đầy đủ persistence** cho draft/publish; preview solver không cần bảng riêng. |
| Import Excel preview/commit | Dữ liệu đích nằm ở `users`, `student_class_enrollments`, `parent_student` | **Thiếu operation table** để lưu import job, checksum, idempotency/replay và lịch sử commit; đây là gap cần hardening. |
| Realtime invalidate Web/Mobile | `notifications` chỉ lưu notification | **Thiếu transactional outbox/event log chung**; sự kiện nghiệp vụ realtime hiện không có durable outbox để phát lại sau sự cố. |
| Reset mật khẩu và email gửi link | `password_reset_tokens`, `notifications`, `notification_delivery_logs` | **Có bảng token và log delivery**; thiếu cấu hình provider production chứ không thiếu bảng. |
| SSO/OIDC | Không có provider/account-link table | **Chức năng chưa được triển khai**; chỉ cần bảng khi hỗ trợ nhiều IdP hoặc liên kết subject bên ngoài. |
| Duyệt lịch bù | `timetable_makeup_proposals` | **Có bảng đề xuất**, nhưng APPROVED chưa materialize thành occurrence/slot publish chính thức; đây là gap nghiệp vụ, không phải thiếu nơi lưu đề xuất. |
| Dashboard, báo cáo, filter, phân trang | Đọc tổng hợp từ bảng nghiệp vụ | **Không cần bảng riêng**; đây là read model/query. Chỉ thêm materialized view khi có bằng chứng hiệu năng. |
| Trạng thái giao diện, form nháp cục bộ | Browser memory/local state | **Không phải dữ liệu nghiệp vụ**; không nên tạo bảng chỉ để lưu UI tạm. |

## Kết luận khẩn cấp

Không phát hiện chức năng active nào đang nhận dữ liệu nghiệp vụ quan trọng từ người dùng rồi hoàn toàn không có bảng đích. Bốn khoảng trống cần xử lý đúng bản chất là: import operation/idempotency, durable event outbox, SSO bên ngoài và materialization lịch bù. Không tạo API hoặc bảng trùng chỉ để che lỗi UI.

## Cách tái sinh tài liệu

```bash
SSE_DB_URL='postgresql://user@localhost:5432/sse_db' node scripts/export-database-documentation.mjs
```
