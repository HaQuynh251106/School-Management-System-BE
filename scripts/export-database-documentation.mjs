#!/usr/bin/env node

import { execFileSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptDir = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(scriptDir, '..');
const outputDir = resolve(repoRoot, 'docs', 'erd', 'generated');
const databaseUrl = process.env.SSE_DB_URL
  ?? process.env.DATABASE_URL
  ?? 'postgresql://a1234@localhost:5432/sse_full_flow_probe';

const definitions = {
  academic_assessment_plan_teachers: ['education-plan', 'Danh sách nhiều giáo viên cùng phụ trách một mốc kiểm tra; nối kế hoạch đánh giá với tài khoản giáo viên.'],
  academic_assessment_plans: ['education-plan', 'Mốc kiểm tra/đánh giá của kế hoạch giáo dục theo môn, học kỳ, lớp hoặc toàn khối.'],
  academic_curriculum_distributions: ['education-plan', 'Phân phối số tiết của nội dung môn học theo tuần và học kỳ.'],
  academic_curriculum_items: ['education-plan', 'Cây chương, chủ đề và bài học của từng môn trong kế hoạch.'],
  academic_exam_schedules: ['education-plan', 'Lịch kiểm tra dự kiến được khai báo ngay trong kế hoạch giáo dục.'],
  academic_plan_approval_history: ['education-plan', 'Lịch sử khóa, công bố, trả về hoặc thay đổi trạng thái kế hoạch.'],
  academic_promotion_policies: ['year-end', 'Chính sách xét lên lớp, ở lại lớp và điều kiện tổng kết theo năm học.'],
  academic_result_locks: ['year-end', 'Khóa kết quả của lớp/học kỳ/năm để ngăn sửa dữ liệu sau khi chốt.'],
  academic_training_plan_special_weeks: ['education-plan', 'Tuần nghỉ, tuần kiểm tra hoặc tuần đặc biệt làm thay đổi phân phối chương trình.'],
  academic_training_plan_stages: ['education-plan', 'Các giai đoạn thực hiện một môn trong kế hoạch giáo dục.'],
  academic_training_plan_subjects: ['education-plan', 'Môn, học kỳ và tổng số tiết thuộc một phiên bản kế hoạch giáo dục.'],
  academic_training_plans: ['education-plan', 'Aggregate kế hoạch giáo dục theo năm, khối, chương trình, phiên bản và lifecycle.'],
  academic_years: ['structure', 'Danh mục năm học và trạng thái hoạt động/đã đóng.'],
  announcements: ['communication', 'Thông báo/bản tin do nhà trường hoặc giáo viên phát hành theo phạm vi người nhận.'],
  assignment_submission_versions: ['learning', 'Lưu từng lần nộp bài để không mất lịch sử khi học sinh nộp lại.'],
  assignment_submissions: ['learning', 'Bài nộp hiện tại, điểm, feedback và trạng thái chấm của học sinh.'],
  assignments: ['learning', 'Bài tập nháp/đã phát hành theo lớp, môn và giáo viên.'],
  attendance_excuse_requests: ['learning', 'Đơn xin nghỉ và chuỗi xác nhận phụ huynh/GVCN.'],
  attendance_records: ['learning', 'Điểm danh theo học sinh, lớp, tiết và ngày; có optimistic version.'],
  audit_logs: ['identity', 'Audit mutation nghiệp vụ quan trọng như sửa điểm, thanh toán, công bố và cấu hình.'],
  bank_statement_entries: ['finance', 'Dòng sao kê ngân hàng dùng ghép hóa đơn/thanh toán khi đối soát.'],
  chat_messages: ['communication', 'Tin nhắn trực tiếp giữa các tài khoản trong phạm vi quan hệ cho phép.'],
  class_lesson_progress: ['timetable', 'Tiến độ thực dạy theo lớp, môn, bài học và ngày học.'],
  class_subject_combinations: ['structure', 'Gán tổ hợp môn lựa chọn cho từng lớp.'],
  classes: ['structure', 'Lớp học theo năm/khối, GVCN, phòng chủ nhiệm, sĩ số và sức chứa tối đa.'],
  club_registrations: ['communication', 'Đăng ký ngoại khóa/CLB, trạng thái duyệt và hóa đơn phí nếu có.'],
  clubs: ['communication', 'Danh mục ngoại khóa/CLB miễn phí hoặc có phí.'],
  education_program_subjects: ['education-plan', 'Môn và số tiết HK1/HK2/cả năm của chương trình theo khối.'],
  education_programs: ['education-plan', 'Chương trình giáo dục nháp/đang áp dụng/đã lưu trữ.'],
  exam_categories: ['exam', 'Danh mục loại/hệ số đầu điểm dùng bảng điểm và khảo thí.'],
  exam_periods: ['exam', 'Đợt thi theo năm, học kỳ và trạng thái công bố/khóa.'],
  exam_room_assignments: ['exam', 'Phòng thi và giám thị chính/dự phòng cho từng ca thi.'],
  exam_room_students: ['exam', 'Phân phòng, số báo danh/chỗ ngồi của học sinh trong ca thi.'],
  exam_schedule_versions: ['exam', 'Phiên bản lịch thi nháp/đã công bố và quan hệ phiên bản nguồn.'],
  exam_sessions: ['exam', 'Ca thi, môn thi, thời gian và nguồn kế hoạch đánh giá.'],
  exam_teacher_unavailability: ['exam', 'Khung thời gian giáo viên không thể coi/chấm thi.'],
  fee_period_item_targets: ['finance', 'Đối tượng áp dụng riêng cho từng khoản trong một đợt thu.'],
  fee_period_items: ['finance', 'Các khoản tiền cấu thành một đợt thu.'],
  fee_period_targets: ['finance', 'Phạm vi lớp hoặc học sinh của toàn đợt thu.'],
  fee_periods: ['finance', 'Đợt thu theo năm/học kỳ và lifecycle mở/đóng.'],
  flyway_schema_history: ['system', 'Lịch sử migration Flyway; không phải dữ liệu nghiệp vụ.'],
  grade_change_logs: ['learning', 'Before/after, lý do, actor và thời gian mỗi lần sửa điểm.'],
  grade_configurations: ['learning', 'Cấu hình loại đầu điểm, số cột và trọng số theo môn/học kỳ.'],
  grade_levels: ['structure', 'Danh mục khối K10/K11/K12 và các quy tắc cấp học.'],
  grades: ['learning', 'Điểm theo học sinh, môn, học kỳ, loại và assessmentIndex.'],
  homeroom_remarks: ['year-end', 'Nhận xét GVCN theo học sinh, lớp, học kỳ và năm.'],
  invoice_items: ['finance', 'Snapshot chi tiết khoản thu trong một hóa đơn.'],
  invoices: ['finance', 'Công nợ/hóa đơn của từng học sinh và trạng thái thanh toán.'],
  login_history: ['identity', 'Lịch sử đăng nhập phục vụ bảo mật; đã loại khỏi màn Audit nghiệp vụ.'],
  notification_delivery_logs: ['communication', 'Log từng lần gửi email/push, phản hồi provider và lỗi retry.'],
  notification_templates: ['communication', 'Mẫu nội dung thông báo theo loại sự kiện/kênh.'],
  notifications: ['communication', 'Hộp thư in-app/email/push theo người nhận, trạng thái đọc và delivery.'],
  parent_student: ['identity', 'Bảng nối nhiều-nhiều phụ huynh–học sinh; nguồn scope cho toàn bộ dữ liệu con.'],
  password_reset_tokens: ['identity', 'Token đặt lại mật khẩu một lần, thời hạn và thời điểm đã sử dụng.'],
  payment_gateway_transactions: ['finance', 'Request/response/idempotency/signature của giao dịch cổng thanh toán.'],
  payment_proofs: ['finance', 'Minh chứng chuyển khoản VietQR để Admin xác nhận hoặc từ chối.'],
  payment_receipts: ['finance', 'Biên nhận đã phát hành sau thanh toán/đối soát thành công.'],
  payment_reconciliation_issues: ['finance', 'Sai lệch phát hiện trong một lần đối soát.'],
  payment_reconciliation_method_summaries: ['finance', 'Tổng hợp số tiền theo phương thức trong một lần đối soát.'],
  payment_reconciliation_runs: ['finance', 'Phiên đối soát, tổng thực tế, chênh lệch và trạng thái chốt.'],
  payment_refunds: ['finance', 'Yêu cầu/ghi nhận hoàn tiền một phần hoặc toàn phần.'],
  payments: ['finance', 'Khoản thanh toán cho hóa đơn, phương thức, trạng thái và số tiền.'],
  permissions: ['identity', 'Danh mục quyền kỹ thuật dùng RBAC.'],
  refresh_tokens: ['identity', 'Phiên refresh token đã hash, hạn dùng và trạng thái thu hồi.'],
  role_permissions: ['identity', 'Bảng nối role–permission.'],
  roles: ['identity', 'Danh mục bốn role chính và metadata phân quyền.'],
  rooms: ['structure', 'Phòng thường/phòng bộ môn/nhà thể chất và sức chứa.'],
  school_holidays: ['structure', 'Ngày nghỉ/lịch đặc biệt dùng validator kế hoạch, TKB và tiến độ.'],
  semesters: ['structure', 'Hai học kỳ thuộc năm học và phạm vi ngày hợp lệ.'],
  stored_files: ['communication', 'Metadata file riêng tư, owner, MIME, object key và phạm vi truy cập.'],
  student_class_enrollments: ['structure', 'Lịch sử học sinh thuộc lớp nào trong năm học.'],
  student_yearly_summaries: ['year-end', 'Kết quả tổng kết năm, học lực/hạnh kiểm và quyết định lên lớp.'],
  subject_combination_subjects: ['structure', 'Bảng nối tổ hợp–môn.'],
  subject_combinations: ['structure', 'Danh mục tổ hợp môn lựa chọn theo năm và khối.'],
  subjects: ['structure', 'Danh mục môn/hoạt động giáo dục và loại phòng yêu cầu.'],
  submission_resubmission_requests: ['learning', 'Yêu cầu cho phép nộp lại và quyết định giáo viên.'],
  teacher_class_subjects: ['structure', 'Phân công giáo viên dạy lớp–môn–học kỳ.'],
  teacher_staffing_policies: ['structure', 'Định mức giáo viên/lớp và ngưỡng tải dùng phân tích nhân sự.'],
  teacher_subject_capabilities: ['structure', 'Chuyên môn/capability giáo viên theo môn; ngăn phân công sai chuyên môn.'],
  timetable_draft_slots: ['timetable', 'Các tiết trong bản nháp do bộ xếp lịch tạo.'],
  timetable_makeup_proposals: ['timetable', 'Đề xuất ngày/tiết/phòng dạy bù và trạng thái duyệt.'],
  timetable_schedules: ['timetable', 'Phiên bản TKB nháp/đã công bố theo năm, học kỳ và phạm vi khối.'],
  timetable_slots: ['timetable', 'Tiết học chính thức đã phát hành cho lớp, môn, giáo viên và phòng.'],
  user_devices: ['identity', 'Thiết bị/FCM token của người dùng phục vụ push notification.'],
  user_notification_preferences: ['communication', 'Tùy chọn bật/tắt kênh thông báo theo người dùng và loại.'],
  user_roles: ['identity', 'Bảng nối tài khoản–role; source hiện vẫn đồng bộ thêm cột role trên users.'],
  users: ['identity', 'Tài khoản, mã hệ thống, hồ sơ, email, điện thoại, hash mật khẩu và trạng thái.'],
  year_result_publication_history: ['year-end', 'Lịch sử công bố/hủy công bố kết quả năm.'],
  year_result_publications: ['year-end', 'Trạng thái công bố kết quả theo năm/lớp/học sinh.'],
};

const moduleLabels = {
  identity: 'Identity & Access',
  structure: 'Cơ cấu đào tạo',
  'education-plan': 'Chương trình & kế hoạch giáo dục',
  timetable: 'Thời khóa biểu & tiến độ',
  learning: 'Dạy học hằng ngày',
  exam: 'Khảo thí',
  finance: 'Tài chính',
  communication: 'Thông báo, chat, file & ngoại khóa',
  'year-end': 'Tổng kết & chuyển năm',
  system: 'Hạ tầng schema',
};

const schemaSql = `
SELECT json_build_object(
  'tables', (
    SELECT json_agg(json_build_object(
      'name', t.table_name,
      'columns', (
        SELECT json_agg(json_build_object(
          'name', c.column_name,
          'type', c.data_type,
          'nullable', c.is_nullable = 'YES'
        ) ORDER BY c.ordinal_position)
        FROM information_schema.columns c
        WHERE c.table_schema = 'public' AND c.table_name = t.table_name
      )
    ) ORDER BY t.table_name)
    FROM information_schema.tables t
    WHERE t.table_schema = 'public' AND t.table_type = 'BASE TABLE'
  ),
  'foreignKeys', (
    SELECT json_agg(json_build_object(
      'fromTable', kcu.table_name,
      'fromColumn', kcu.column_name,
      'toTable', ccu.table_name,
      'toColumn', ccu.column_name
    ) ORDER BY kcu.table_name, kcu.column_name)
    FROM information_schema.table_constraints tc
    JOIN information_schema.key_column_usage kcu
      ON tc.constraint_name = kcu.constraint_name
     AND tc.table_schema = kcu.table_schema
    JOIN information_schema.constraint_column_usage ccu
      ON ccu.constraint_name = tc.constraint_name
     AND ccu.table_schema = tc.table_schema
    WHERE tc.constraint_type = 'FOREIGN KEY' AND tc.table_schema = 'public'
  )
);`;

const raw = execFileSync('psql', [databaseUrl, '-X', '-At', '-v', 'ON_ERROR_STOP=1', '-c', schemaSql], {
  encoding: 'utf8', maxBuffer: 20 * 1024 * 1024,
});
const schema = JSON.parse(raw.trim());
const tableNames = schema.tables.map((table) => table.name).sort();
const missing = tableNames.filter((name) => !definitions[name]);
const stale = Object.keys(definitions).filter((name) => !tableNames.includes(name));
if (missing.length || stale.length) {
  throw new Error(`Catalog mismatch. Missing descriptions: ${missing.join(', ') || 'none'}; stale: ${stale.join(', ') || 'none'}`);
}

const fksByTable = new Map();
for (const fk of schema.foreignKeys ?? []) {
  const rows = fksByTable.get(fk.fromTable) ?? [];
  rows.push(fk);
  fksByTable.set(fk.fromTable, rows);
}

const compact = (text) => String(text).replaceAll('|', '\\|').replaceAll('\n', ' ');
const grouped = Object.keys(moduleLabels).map((module) => ({
  module,
  tables: tableNames.filter((name) => definitions[name][0] === module),
})).filter((group) => group.tables.length);

const lines = [];
lines.push('# Danh mục 89 bảng PostgreSQL và ánh xạ chức năng');
lines.push('');
lines.push('Ngày sinh tài liệu: 22/08/2026. Nguồn sự thật: `information_schema` của database Full Demo đã migrate tới phiên bản hiện tại; không suy đoán bảng từ ảnh hoặc tên màn hình.');
lines.push('');
lines.push(`- Tổng bảng: **${schema.tables.length}** (gồm \`flyway_schema_history\`).`);
lines.push(`- Tổng khóa ngoại: **${schema.foreignKeys.length}**.`);
lines.push('- Quan hệ phụ huynh–con được lưu tại `parent_student`.');
lines.push('- Người phụ trách nhiều giáo viên của kế hoạch kiểm tra được lưu tại `academic_assessment_plan_teachers`.');
lines.push('');
lines.push('## Tổng quan theo miền');
lines.push('');
lines.push('| Miền | Số bảng | Chức năng chính |');
lines.push('|---|---:|---|');
for (const group of grouped) {
  lines.push(`| ${moduleLabels[group.module]} | ${group.tables.length} | ${group.tables.map((name) => `\`${name}\``).join(', ')} |`);
}
lines.push('');
lines.push('## Chi tiết từng bảng');
lines.push('');

for (const group of grouped) {
  lines.push(`### ${moduleLabels[group.module]}`);
  lines.push('');
  lines.push('| Bảng | Chức năng lưu trữ | Quan hệ cha trực tiếp (FK) |');
  lines.push('|---|---|---|');
  for (const name of group.tables) {
    const parents = (fksByTable.get(name) ?? [])
      .map((fk) => `\`${fk.fromColumn}\` → \`${fk.toTable}.${fk.toColumn}\``)
      .join('<br>') || 'Không có FK cha';
    lines.push(`| \`${name}\` | ${compact(definitions[name][1])} | ${parents} |`);
  }
  lines.push('');
}

lines.push('## Audit chức năng Web và nơi lưu dữ liệu');
lines.push('');
lines.push('| Chức năng Web | Bảng lưu chính | Kết luận |');
lines.push('|---|---|---|');
lines.push('| Liên kết một phụ huynh với nhiều con | `parent_student` | **Có bảng**; lỗi trước đây nằm ở UI/API, không phải thiếu schema. |');
lines.push('| Kế hoạch kiểm tra có nhiều người phụ trách | `academic_assessment_plans`, `academic_assessment_plan_teachers` | **Có bảng sau V58**; giữ `teacher_id` làm người chính để tương thích dữ liệu cũ. |');
lines.push('| Chương trình/kế hoạch/phiên bản/công bố | `education_programs`, `education_program_subjects`, `academic_training_plans`, `academic_plan_approval_history` | **Có đầy đủ persistence**. |');
lines.push('| Auto timetable và bản nháp | `timetable_schedules`, `timetable_draft_slots`, `timetable_slots` | **Có đầy đủ persistence** cho draft/publish; preview solver không cần bảng riêng. |');
lines.push('| Import Excel preview/commit | Dữ liệu đích nằm ở `users`, `student_class_enrollments`, `parent_student` | **Thiếu operation table** để lưu import job, checksum, idempotency/replay và lịch sử commit; đây là gap cần hardening. |');
lines.push('| Realtime invalidate Web/Mobile | `notifications` chỉ lưu notification | **Thiếu transactional outbox/event log chung**; sự kiện nghiệp vụ realtime hiện không có durable outbox để phát lại sau sự cố. |');
lines.push('| Reset mật khẩu và email gửi link | `password_reset_tokens`, `notifications`, `notification_delivery_logs` | **Có bảng token và log delivery**; thiếu cấu hình provider production chứ không thiếu bảng. |');
lines.push('| SSO/OIDC | Không có provider/account-link table | **Chức năng chưa được triển khai**; chỉ cần bảng khi hỗ trợ nhiều IdP hoặc liên kết subject bên ngoài. |');
lines.push('| Duyệt lịch bù | `timetable_makeup_proposals` | **Có bảng đề xuất**, nhưng APPROVED chưa materialize thành occurrence/slot publish chính thức; đây là gap nghiệp vụ, không phải thiếu nơi lưu đề xuất. |');
lines.push('| Dashboard, báo cáo, filter, phân trang | Đọc tổng hợp từ bảng nghiệp vụ | **Không cần bảng riêng**; đây là read model/query. Chỉ thêm materialized view khi có bằng chứng hiệu năng. |');
lines.push('| Trạng thái giao diện, form nháp cục bộ | Browser memory/local state | **Không phải dữ liệu nghiệp vụ**; không nên tạo bảng chỉ để lưu UI tạm. |');
lines.push('');
lines.push('## Kết luận khẩn cấp');
lines.push('');
lines.push('Không phát hiện chức năng active nào đang nhận dữ liệu nghiệp vụ quan trọng từ người dùng rồi hoàn toàn không có bảng đích. Bốn khoảng trống cần xử lý đúng bản chất là: import operation/idempotency, durable event outbox, SSO bên ngoài và materialization lịch bù. Không tạo API hoặc bảng trùng chỉ để che lỗi UI.');
lines.push('');
lines.push('## Cách tái sinh tài liệu');
lines.push('');
lines.push('```bash');
lines.push("SSE_DB_URL='postgresql://user@localhost:5432/sse_db' node scripts/export-database-documentation.mjs");
lines.push('```');

mkdirSync(outputDir, { recursive: true });
const catalogPath = resolve(repoRoot, 'docs', 'erd', 'SSE-DATABASE-SCHEMA-CATALOG-2026-08-22.md');
writeFileSync(catalogPath, `${lines.join('\n')}\n`, 'utf8');

const mermaidType = (type) => {
  if (type.includes('int') || type === 'numeric' || type === 'double precision') return 'number';
  if (type.includes('timestamp')) return 'datetime';
  if (type === 'date') return 'date';
  if (type === 'boolean') return 'bool';
  if (type.includes('json')) return 'json';
  return 'string';
};
const safeId = (name) => name.replaceAll('_', '');

for (const group of grouped.filter((item) => item.module !== 'system')) {
  const selected = new Set(group.tables);
  const body = ['erDiagram', '    direction LR'];
  const internalFks = schema.foreignKeys.filter((fk) => selected.has(fk.fromTable) && selected.has(fk.toTable));
  for (const fk of internalFks) {
    body.push(`    ${safeId(fk.toTable)} ||--o{ ${safeId(fk.fromTable)} : references`);
  }
  for (const name of group.tables) {
    const table = schema.tables.find((item) => item.name === name);
    const fkColumns = new Set((fksByTable.get(name) ?? []).map((fk) => fk.fromColumn));
    const important = table.columns.filter((column) => column.name === 'id' || fkColumns.has(column.name))
      .concat(table.columns.filter((column) => !['id', ...fkColumns].includes(column.name)).slice(0, 3))
      .slice(0, 8);
    body.push(`    ${safeId(name)}["${name}"] {`);
    for (const column of important) {
      const keys = column.name === 'id' ? ' PK' : fkColumns.has(column.name) ? ' FK' : '';
      body.push(`        ${mermaidType(column.type)} ${column.name}${keys}`);
    }
    body.push('    }');
  }
  writeFileSync(resolve(outputDir, `${group.module}.mmd`), `${body.join('\n')}\n`, 'utf8');
}

const highLevel = `erDiagram
    direction LR
    users ||--o{ parentstudent : links
    users ||--o{ teacherclasssubjects : teaches
    users ||--o{ grades : receives
    academicyears ||--|{ semesters : contains
    academicyears ||--o{ classes : contains
    rooms ||--o{ classes : hosts
    classes ||--o{ studentclassenrollments : enrolls
    classes ||--o{ teacherclasssubjects : assigned
    subjects ||--o{ teacherclasssubjects : assigned
    educationprograms ||--o{ educationprogramsubjects : configures
    educationprograms ||--o{ academictrainingplans : sources
    academictrainingplans ||--o{ academictrainingplansubjects : contains
    academictrainingplans ||--o{ academicassessmentplans : defines
    academicassessmentplans ||--o{ academicassessmentplanteachers : owners
    timetableschedules ||--o{ timetabledraftslots : previews
    timetableschedules ||--o{ timetableslots : publishes
    assignments ||--o{ assignmentsubmissions : receives
    feeperiods ||--o{ invoices : generates
    invoices ||--o{ invoiceitems : contains
    invoices ||--o{ payments : paid_by
    users ||--o{ notifications : receives
    users ||--o{ chatmessages : sends

    users["users"] { string id PK string role string user_code }
    parentstudent["parent_student"] { string id PK string parent_id FK string student_id FK }
    academicyears["academic_years"] { string id PK string code string status }
    semesters["semesters"] { string id PK string academic_year_id FK string code }
    classes["classes"] { string id PK string academic_year_id FK string home_room_id FK }
    rooms["rooms"] { string id PK string code number capacity }
    subjects["subjects"] { string id PK string code string required_room_type }
    studentclassenrollments["student_class_enrollments"] { string id PK string student_id FK string class_id FK }
    teacherclasssubjects["teacher_class_subjects"] { string id PK string teacher_id FK string class_id FK }
    educationprograms["education_programs"] { string id PK string code string status }
    educationprogramsubjects["education_program_subjects"] { string id PK string program_id FK string subject_id FK }
    academictrainingplans["academic_training_plans"] { string id PK string program_id FK string status }
    academictrainingplansubjects["academic_training_plan_subjects"] { string id PK string plan_id FK string subject_id FK }
    academicassessmentplans["academic_assessment_plans"] { string id PK string plan_id FK string subject_id FK }
    academicassessmentplanteachers["academic_assessment_plan_teachers"] { string id PK string assessment_plan_id FK string teacher_id FK }
    timetableschedules["timetable_schedules"] { string id PK string semester_id FK string status }
    timetabledraftslots["timetable_draft_slots"] { string id PK string schedule_id FK string assignment_id FK }
    timetableslots["timetable_slots"] { string id PK string class_id FK string teacher_id FK }
    grades["grades"] { string id PK string student_id FK string subject_id FK }
    assignments["assignments"] { string id PK string class_id FK string teacher_id FK }
    assignmentsubmissions["assignment_submissions"] { string id PK string assignment_id FK string student_id FK }
    feeperiods["fee_periods"] { string id PK string academic_year_id FK string status }
    invoices["invoices"] { string id PK string fee_period_id FK string student_id }
    invoiceitems["invoice_items"] { string id PK string invoice_id FK number amount }
    payments["payments"] { string id PK string invoice_id FK number amount }
    notifications["notifications"] { string id PK string recipient_id string status }
    chatmessages["chat_messages"] { string id PK string sender_id FK string recipient_id FK }
`;
writeFileSync(resolve(outputDir, 'high-level.mmd'), highLevel, 'utf8');

console.log(JSON.stringify({
  catalogPath,
  diagramDirectory: outputDir,
  tableCount: schema.tables.length,
  foreignKeyCount: schema.foreignKeys.length,
  modules: grouped.map((group) => ({ module: group.module, count: group.tables.length })),
}, null, 2));
