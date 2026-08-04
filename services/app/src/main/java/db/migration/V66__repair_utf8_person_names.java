package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Repairs names from the operational acceptance dataset that was once imported
 * through a non-UTF-8 client. Stable seeded identifiers let us reconstruct the
 * authoritative names without changing relationships or historical records.
 */
public class V66__repair_utf8_person_names extends BaseJavaMigration {
    private static final String[] STUDENT_SURNAMES = {
            "Nguyễn", "Trần", "Lê", "Phạm", "Hoàng", "Vũ", "Đặng", "Đỗ", "Bùi", "Ngô", "Dương", "Đinh"
    };
    private static final String[] STUDENT_MIDDLES = {
            "Minh", "Gia", "Khánh", "Bảo", "Thanh", "Hoài", "Đức", "Ngọc"
    };
    private static final String[] STUDENT_GIVEN_NAMES = {
            "Ân", "Anh", "Bình", "Châu", "Dũng", "Giang", "Hà", "Hải", "Hân", "Hùng", "Hương", "Khang",
            "Lan", "Linh", "Long", "Mai", "Nam", "Ngân", "Phúc", "Phương", "Quân", "Thảo", "Trang", "Trung"
    };
    private static final String[] PARENT_MIDDLES = {
            "Văn", "Thị", "Đức", "Quốc", "Minh", "Ngọc", "Thanh", "Hoài"
    };
    private static final String[] PARENT_GIVEN_NAMES = {
            "An", "Anh", "Bình", "Châu", "Dũng", "Giang", "Hà", "Hải", "Hân", "Hiếu", "Hương", "Khang",
            "Lan", "Linh", "Long", "Mai", "Nam", "Ngân", "Phúc", "Phương", "Quân", "Thảo", "Trang", "Trung"
    };
    private static final String[] TEACHER_MIDDLES = {
            "Văn", "Thị", "Đức", "Quốc", "Minh", "Ngọc"
    };
    private static final String[] TEACHER_GIVEN_NAMES = {
            "Anh", "Bình", "Châu", "Dũng", "Giang", "Hà", "Hải", "Hạnh", "Hùng",
            "Hương", "Khánh", "Lan", "Linh", "Long", "Mai", "Nam", "Phương", "Quân"
    };

    @Override
    public void migrate(Context context) throws Exception {
        Map<String, String> repairedNames = collectRepairableNames(context);
        if (!repairedNames.isEmpty()) {
            updateUsers(context, repairedNames);
            refreshNameSnapshots(context);
        }

        try (Statement statement = context.getConnection().createStatement()) {
            statement.execute("ALTER TABLE users ADD CONSTRAINT chk_users_full_name_encoding "
                    + "CHECK (full_name NOT LIKE '%?%')");
        }
    }

    private Map<String, String> collectRepairableNames(Context context) throws Exception {
        Map<String, String> result = new HashMap<>();
        try (PreparedStatement statement = context.getConnection().prepareStatement(
                "SELECT id, role FROM users WHERE role IN ('STUDENT','PARENT','TEACHER')");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                String id = rows.getString("id");
                String role = rows.getString("role");
                String repaired = switch (role) {
                    case "STUDENT" -> studentName(id);
                    case "PARENT" -> parentName(id);
                    case "TEACHER" -> teacherName(id);
                    default -> null;
                };
                if (repaired != null) result.put(id, repaired);
            }
        }
        return result;
    }

    private void updateUsers(Context context, Map<String, String> repairedNames) throws Exception {
        try (PreparedStatement statement = context.getConnection().prepareStatement(
                "UPDATE users SET full_name=? WHERE id=?")) {
            for (Map.Entry<String, String> entry : repairedNames.entrySet()) {
                statement.setString(1, entry.getValue());
                statement.setString(2, entry.getKey());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void refreshNameSnapshots(Context context) throws Exception {
        List<String[]> directSnapshots = List.of(
                row("academic_documents", "student_name", "student_id"),
                row("assignment_submissions", "student_name", "student_id"),
                row("club_registrations", "student_name", "student_id"),
                row("exam_candidates", "student_name", "student_id"),
                row("exam_organization_plan_candidates", "student_name", "student_id"),
                row("exam_review_requests", "student_name", "student_id"),
                row("exam_seating_plan_items", "student_name", "student_id"),
                row("invoices", "student_name", "student_id"),
                row("leave_requests", "student_name", "student_id"),
                row("student_yearly_summaries", "student_name", "student_id"),
                row("assignments", "teacher_name", "teacher_id"),
                row("classes", "homeroom_teacher_name", "homeroom_teacher_id"),
                row("exam_grading_assignments", "teacher_name", "teacher_id"),
                row("exam_rooms", "proctor_one_name", "proctor_one_id"),
                row("exam_rooms", "proctor_two_name", "proctor_two_id"),
                row("exam_organization_plan_rooms", "proctor_one_name", "proctor_one_id"),
                row("exam_organization_plan_rooms", "proctor_two_name", "proctor_two_id"),
                row("exam_proctor_plan_items", "previous_proctor_one_name", "previous_proctor_one_id"),
                row("exam_proctor_plan_items", "previous_proctor_two_name", "previous_proctor_two_id"),
                row("exam_proctor_plan_items", "proposed_proctor_one_name", "proposed_proctor_one_id"),
                row("exam_proctor_plan_items", "proposed_proctor_two_name", "proposed_proctor_two_id"),
                row("leave_requests", "homeroom_teacher_name", "homeroom_teacher_id"),
                row("teacher_load_registrations", "teacher_name", "teacher_id"),
                row("teaching_assignments", "teacher_name", "teacher_id"),
                row("timetable_draft_slots", "teacher_name", "teacher_id"),
                row("timetable_plan_slots", "teacher_name", "teacher_id"),
                row("timetable_publication_slots", "teacher_name", "teacher_id"),
                row("timetable_slots", "teacher_name", "teacher_id"),
                row("leave_requests", "parent_name", "parent_id"),
                row("chat_messages", "sender_name", "sender_id"),
                row("chat_messages", "recipient_name", "recipient_id"),
                row("audit_logs", "actor_name", "actor_id"),
                row("operation_task_comments", "author_name", "author_id"),
                row("operation_tasks", "creator_name", "created_by"),
                row("operation_tasks", "assigned_to_name", "assigned_to")
        );

        try (Statement statement = context.getConnection().createStatement()) {
            for (String[] snapshot : directSnapshots) {
                String table = snapshot[0];
                String nameColumn = snapshot[1];
                String idColumn = snapshot[2];
                statement.execute("UPDATE " + table + " SET " + nameColumn
                        + "=(SELECT full_name FROM users WHERE users.id=" + table + "." + idColumn + ")"
                        + " WHERE " + idColumn + " IS NOT NULL AND EXISTS"
                        + " (SELECT 1 FROM users WHERE users.id=" + table + "." + idColumn + ")");
            }
            statement.execute("UPDATE users SET guardian_name=(SELECT parent_user.full_name FROM parent_student relation "
                    + "JOIN users parent_user ON parent_user.id=relation.parent_id WHERE relation.student_id=users.id) "
                    + "WHERE role='STUDENT' AND EXISTS (SELECT 1 FROM parent_student relation WHERE relation.student_id=users.id)");
            statement.execute("UPDATE payments SET payer_name=(SELECT parent_user.full_name FROM invoices invoice "
                    + "JOIN users parent_user ON parent_user.id=invoice.parent_id WHERE invoice.id=payments.invoice_id) "
                    + "WHERE EXISTS (SELECT 1 FROM invoices invoice WHERE invoice.id=payments.invoice_id)");
        }
    }

    private String studentName(String id) {
        if (!id.matches("student-\\d{4}-\\d{3}")) return null;
        if (id.equals("student-2026-001")) return "Nguyễn Minh An";
        int cohortYear = Integer.parseInt(id.substring(8, 12));
        int studentNo = Integer.parseInt(id.substring(13));
        int personNo = (cohortYear - 2023) * 500 + studentNo;
        if (personNo < 1) return null;
        return STUDENT_SURNAMES[(personNo - 1) % STUDENT_SURNAMES.length] + " "
                + STUDENT_MIDDLES[((personNo - 1) / 12) % STUDENT_MIDDLES.length] + " "
                + STUDENT_GIVEN_NAMES[((personNo - 1) / 96) % STUDENT_GIVEN_NAMES.length];
    }

    private String parentName(String id) {
        if (!id.matches("parent-\\d{4}")) return null;
        if (id.equals("parent-0001")) return "Nguyễn Văn Hùng";
        int personNo = Integer.parseInt(id.substring(7));
        return STUDENT_SURNAMES[(personNo + 116) % STUDENT_SURNAMES.length] + " "
                + PARENT_MIDDLES[((personNo + 116) / 12) % PARENT_MIDDLES.length] + " "
                + PARENT_GIVEN_NAMES[((personNo + 116) / 96) % PARENT_GIVEN_NAMES.length];
    }

    private String teacherName(String id) {
        if (!id.matches("teacher-\\d{3}")) return null;
        int teacherNo = Integer.parseInt(id.substring(8));
        if (teacherNo < 1 || teacherNo > 72) return null;
        if (teacherNo == 1) return "Nguyễn Đức Minh";
        return STUDENT_SURNAMES[(teacherNo - 1) % STUDENT_SURNAMES.length] + " "
                + TEACHER_MIDDLES[((teacherNo - 1) / 12) % TEACHER_MIDDLES.length] + " "
                + TEACHER_GIVEN_NAMES[((teacherNo - 1) / 6) % TEACHER_GIVEN_NAMES.length];
    }

    private String[] row(String table, String nameColumn, String idColumn) {
        return new String[]{table, nameColumn, idColumn};
    }
}
