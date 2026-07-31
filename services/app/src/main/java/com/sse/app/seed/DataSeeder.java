package com.sse.app.seed;

import com.sse.app.academic.attendance.AttendanceRecord;
import com.sse.app.academic.attendance.AttendanceService;
import com.sse.app.academic.grade.ExamCategory;
import com.sse.app.academic.grade.Grade;
import com.sse.app.academic.grade.GradeService;
import com.sse.app.academic.structure.*;
import com.sse.app.academic.timetable.TimetableService;
import com.sse.app.academic.timetable.TimetableSlot;
import com.sse.app.identity.ParentStudent;
import com.sse.app.identity.ParentStudentRepository;
import com.sse.app.identity.User;
import com.sse.app.identity.UserRepository;
import com.sse.app.audit.AuditLog;
import com.sse.app.audit.AuditService;
import com.sse.app.chat.ChatMessage;
import com.sse.app.chat.ChatService;
import com.sse.app.finance.FinanceService;
import com.sse.app.notification.Announcement;
import com.sse.app.notification.NotificationService;
import com.sse.app.notification.NotificationTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Seed dữ liệu mẫu khớp mock-server (cùng id/username/mật khẩu) để cả 2 FE chạy được ngay.
 * Idempotent: chỉ chạy khi bảng users rỗng. Tắt bằng sse.seed.enabled=false.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "sse.seed.enabled", havingValue = "true")
public class DataSeeder {

    @Bean
    ApplicationRunner seedRunner(UserRepository users, ParentStudentRepository relations,
                                 PasswordEncoder enc, StructureService structure,
                                 TimetableService timetable, GradeService grades,
                                 AttendanceService attendance, NotificationService notifications,
                                 AuditService audit, ChatService chat, FinanceService finance) {
        return args -> {
            if (users.count() > 0) {
                log.info("[seed] DB đã có dữ liệu — bỏ qua seed.");
                return;
            }
            log.info("[seed] DB rỗng — seed dữ liệu mẫu...");

            seedUsers(users, relations, enc);
            seedStructure(structure);
            users.findByRole("STUDENT").forEach(student -> {
                if (student.getClassId() != null) {
                    student.setCohortId(structure.cohortIdForClass(student.getClassId()));
                    student.setStudentStatus("ENROLLED");
                    users.save(student);
                    structure.recordEnrollment(student.getId(), student.getClassId());
                }
            });
            seedTimetable(timetable);
            seedGrades(grades);
            seedAttendance(attendance);
            seedNotifications(notifications);
            seedAudit(audit);
            seedChat(chat);
            finance.seedDefaultPeriodAndInvoices();

            log.info("[seed] xong: {} users.", users.count());
        };
    }

    // ---------- Identity ----------
    private void seedUsers(UserRepository users, ParentStudentRepository relations, PasswordEncoder enc) {
        users.save(base("u-admin-1", "admin", enc.encode("Admin123@@"), "Nguyễn Văn Quản",
                "admin@sse.edu.vn", "0900000001", "ADMIN"));
        users.save(base("u-academic-staff-1", "giaovu", enc.encode("Giaovu123@@"), "Nguyễn Thu Hà",
                "giaovu@sse.edu.vn", "0900000004", "ACADEMIC_STAFF"));
        users.save(base("u-accountant-1", "ketoan", enc.encode("Ketoan123@@"), "Trần Minh Anh",
                "ketoan@sse.edu.vn", "0900000005", "ACCOUNTANT"));

        users.save(teacher("u-teacher-1", "gv.nguyenminh", enc.encode("nguyenminh123@"), "Nguyễn Đức Minh",
                "hoa.tran@sse.edu.vn", "0900000002", "GV001", "Toán"));
        users.save(teacher("u-teacher-2", "gv.minh", enc.encode("teacher@123"), "Lê Văn Minh",
                "minh.le@sse.edu.vn", "0900000003", "GV002", "Vật lý"));

        User an = student("u-student-1", "hs.nguyenminhan", enc.encode("nguyenminhanh123@@"), "Nguyễn Minh An",
                "an.pham@sse.edu.vn", "0900000010", "HS2025001", "c-10a1", "10A1");
        an.setDateOfBirth(LocalDate.parse("2010-03-18"));
        an.setGender("FEMALE");
        an.setPlaceOfBirth("Hà Nội");
        an.setEthnicity("Kinh");
        an.setNationality("Việt Nam");
        an.setAddress("12 Nguyễn Trãi, Thanh Xuân, Hà Nội");
        an.setEnrollmentDate(LocalDate.parse("2025-09-05"));
        an.setGuardianName("Phạm Văn Quân");
        an.setGuardianPhone("0900000020");
        users.save(an);

        User binh = student("u-student-2", "hs.binh", enc.encode("student@123"), "Phạm Hoài Bình",
                "binh.pham@sse.edu.vn", "0900000011", "HS2025002", "c-8a1", "8A1");
        binh.setDateOfBirth(LocalDate.parse("2012-08-09"));
        binh.setGender("MALE");
        binh.setPlaceOfBirth("Hà Nội");
        binh.setEthnicity("Kinh");
        binh.setNationality("Việt Nam");
        binh.setAddress("12 Nguyễn Trãi, Thanh Xuân, Hà Nội");
        binh.setEnrollmentDate(LocalDate.parse("2025-09-05"));
        binh.setGuardianName("Phạm Văn Quân");
        binh.setGuardianPhone("0900000020");
        users.save(binh);

        users.save(base("u-parent-1", "ph.nguyenvanhung", enc.encode("nguyenvanhung123@"), "Nguyễn Văn Hùng",
                "quan.pham@gmail.com", "0900000020", "PARENT"));

        relations.save(rel("ps-1", "u-parent-1", "u-student-1", true));
        relations.save(rel("ps-2", "u-parent-1", "u-student-2", false));
    }

    // ---------- Academic structure ----------
    private void seedStructure(StructureService structure) {
        var year = AcademicYear.builder().id("ay-2026").code("2026-2027").name("Năm học 2026-2027")
                .startDate(LocalDate.parse("2026-08-17")).endDate(LocalDate.parse("2027-05-31"))
                .status("ACTIVE").build();

        var sems = List.of(
                Semester.builder().id("sm-2026-1").academicYearId("ay-2026").code("HK1").name("Học kỳ 1")
                        .sequence(1).startDate(LocalDate.parse("2026-08-17")).endDate(LocalDate.parse("2027-01-15"))
                        .status("ACTIVE").build(),
                Semester.builder().id("sm-2026-2").academicYearId("ay-2026").code("HK2").name("Học kỳ 2")
                        .sequence(2).startDate(LocalDate.parse("2027-01-18")).endDate(LocalDate.parse("2027-05-31"))
                        .status("PLANNED").build());

        var classes = List.of(
                cls("c-10a1", "10A1", "Lớp 10A1", "K10", "u-teacher-1", 38),
                cls("c-10a2", "10A2", "Lớp 10A2", "K10", "u-teacher-2", 40),
                cls("c-8a1", "8A1", "Lớp 8A1", "K8", null, 35));

        var subjects = List.of(
                subj("sj-math", "MATH", "Toán"), subj("sj-phys", "PHYS", "Vật lý"),
                subj("sj-lit", "LIT", "Ngữ văn"), subj("sj-eng", "ENG", "Tiếng Anh"),
                subj("sj-bio", "BIO", "Sinh học"));

        var rooms = List.of(
                Room.builder().id("rm-201").code("P201").name("Phòng 201").capacity(45).build(),
                Room.builder().id("rm-105").code("P105").name("Phòng 105").capacity(45).build(),
                Room.builder().id("rm-lab1").code("LAB1").name("Phòng thí nghiệm 1").capacity(30).build());

        structure.seedAll(List.of(year), sems, classes, subjects, rooms);
    }

    // ---------- Timetable ----------
    private void seedTimetable(TimetableService timetable) {
        timetable.seedSlots(List.of(
                slot("tt-1", "c-10a1", "sj-math", "Toán", "u-teacher-1", "Trần Thị Hoa", "P201", "MON", 1, "07:00", "07:45"),
                slot("tt-2", "c-10a1", "sj-phys", "Vật lý", "u-teacher-2", "Lê Văn Minh", "P201", "MON", 2, "07:50", "08:35"),
                slot("tt-3", "c-10a1", "sj-lit", "Ngữ văn", "u-teacher-1", "Trần Thị Hoa", "P201", "MON", 3, "08:45", "09:30"),
                slot("tt-4", "c-10a1", "sj-eng", "Tiếng Anh", "u-teacher-2", "Lê Văn Minh", "P201", "TUE", 1, "07:00", "07:45"),
                slot("tt-5", "c-10a1", "sj-bio", "Sinh học", "u-teacher-1", "Trần Thị Hoa", "P201", "TUE", 2, "07:50", "08:35"),
                slot("tt-6", "c-10a1", "sj-math", "Toán", "u-teacher-1", "Trần Thị Hoa", "P201", "WED", 1, "07:00", "07:45"),
                slot("tt-7", "c-8a1", "sj-math", "Toán", "u-teacher-1", "Trần Thị Hoa", "P105", "MON", 4, "09:35", "10:20"),
                slot("tt-8", "c-8a1", "sj-eng", "Tiếng Anh", "u-teacher-2", "Lê Văn Minh", "P105", "TUE", 4, "09:35", "10:20")));
    }

    // ---------- Grades + exam categories ----------
    private void seedGrades(GradeService grades) {
        var cats = List.of(
                cat("ec-oral", "ORAL", "Miệng", 1, 1), cat("ec-15m", "15M", "15 phút", 1, 1),
                cat("ec-mid", "MID", "Giữa kỳ", 2, 1), cat("ec-final", "FINAL", "Cuối kỳ", 3, 1));

        var list = List.of(
                grade("g-1", "u-student-1", "sj-math", "Toán", "ORAL", "Miệng", 9.0, "2025-09-15T08:00:00Z"),
                grade("g-2", "u-student-1", "sj-math", "Toán", "15M", "15 phút", 8.5, "2025-09-20T08:00:00Z"),
                grade("g-3", "u-student-1", "sj-math", "Toán", "MID", "Giữa kỳ", 7.5, "2025-10-25T08:00:00Z"),
                grade("g-13", "u-student-1", "sj-math", "Toán", "FINAL", "Cuối kỳ", 8.0, "2025-12-20T08:00:00Z"),
                grade("g-4", "u-student-1", "sj-phys", "Vật lý", "ORAL", "Miệng", 8.0, "2025-09-16T08:00:00Z"),
                grade("g-5", "u-student-1", "sj-phys", "Vật lý", "MID", "Giữa kỳ", 8.8, "2025-10-26T08:00:00Z"),
                grade("g-6", "u-student-1", "sj-eng", "Tiếng Anh", "ORAL", "Miệng", 7.0, "2025-09-17T08:00:00Z"),
                grade("g-7", "u-student-1", "sj-lit", "Ngữ văn", "MID", "Giữa kỳ", 6.5, "2025-10-27T08:00:00Z"),
                grade("g-8", "u-student-2", "sj-math", "Toán", "ORAL", "Miệng", 9.5, "2025-09-15T08:00:00Z"),
                grade("g-9", "u-student-2", "sj-eng", "Tiếng Anh", "MID", "Giữa kỳ", 8.0, "2025-10-26T08:00:00Z"));

        grades.seed(cats, list);
    }

    // ---------- Attendance ----------
    private void seedAttendance(AttendanceService attendance) {
        attendance.seed(List.of(
                att("att-1", "u-student-1", "c-10a1", "tt-1", "2026-05-18", "PRESENT", null, "Toán", 1),
                att("att-2", "u-student-1", "c-10a1", "tt-2", "2026-05-18", "PRESENT", null, "Vật lý", 2),
                att("att-3", "u-student-1", "c-10a1", "tt-4", "2026-05-19", "ABSENT_UNEXCUSED", "Không liên lạc được phụ huynh", "Tiếng Anh", 1),
                att("att-4", "u-student-1", "c-10a1", "tt-5", "2026-05-19", "LATE", "Muộn 10 phút", "Sinh học", 2),
                att("att-5", "u-student-1", "c-10a1", "tt-6", "2026-05-20", "ABSENT_EXCUSED", "Có đơn xin nghỉ ốm", "Toán", 1),
                att("att-6", "u-student-2", "c-8a1", "tt-7", "2026-05-19", "PRESENT", null, "Toán", 4)));
    }

    // ---------- Announcements + templates ----------
    private void seedNotifications(NotificationService notifications) {
        var anns = List.of(
                Announcement.builder().id("an-1").title("Lịch nghỉ lễ 30/04 - 01/05")
                        .body("Học sinh nghỉ từ thứ 4 tới chủ nhật. Quay lại trường thứ 2.")
                        .createdAt(Instant.parse("2026-04-25T10:00:00Z")).audience("ALL")
                        .category("HOLIDAY").priority("IMPORTANT").status("SENT").build(),
                Announcement.builder().id("an-2").title("Hội phụ huynh học kỳ 2")
                        .body("Sáng thứ 7 tuần này, tại hội trường lớn.")
                        .createdAt(Instant.parse("2026-05-15T08:00:00Z")).audience("PARENT")
                        .category("PARENT_MEETING").priority("IMPORTANT").status("SENT").build());

        var tpls = List.of(
                tpl("tpl-att", "ATTENDANCE_ABSENT", "Cảnh báo vắng", "IN_APP",
                        "Cảnh báo chuyên cần", "{{studentName}} {{status}} môn {{subject}} ngày {{date}}"),
                tpl("tpl-grade", "GRADE_PUBLISHED", "Công bố điểm", "IN_APP",
                        "Có điểm mới", "Môn {{subject}} - {{category}}: {{score}}"),
                tpl("tpl-inv", "INVOICE_ISSUED", "Hóa đơn mới", "EMAIL",
                        "Thông báo học phí", "Quý phụ huynh có hóa đơn {{invoiceCode}} số tiền {{amount}}"));

        notifications.seed(anns, tpls);
    }

    // ---------- Audit (A6) ----------
    private void seedAudit(AuditService audit) {
        Instant now = Instant.now();
        audit.seed(List.of(
                AuditLog.builder().id("evt-1").actorId("u-admin-1").actorName("Nguyễn Văn Quản").role("ADMIN")
                        .action("CREATE").module("identity").entityType("user").entityId("u-teacher-2")
                        .detail("Tạo tài khoản GV Lê Văn Minh").createdAt(now.minusSeconds(86400)).build(),
                AuditLog.builder().id("evt-2").actorId("u-teacher-1").actorName("Trần Thị Hoa").role("TEACHER")
                        .action("UPDATE").module("academic").entityType("grade").entityId("g-3")
                        .detail("Sửa điểm GK Toán 7.5 → 8.0").createdAt(now.minusSeconds(7200)).build(),
                AuditLog.builder().id("evt-3").actorId("system").actorName("Hệ thống").role("SYSTEM")
                        .action("PAYMENT").module("finance").entityType("invoice").entityId("INV-HK1-2025")
                        .detail("VietQR đã đối soát 1.800.000₫").createdAt(now.minusSeconds(3600)).build(),
                AuditLog.builder().id("evt-4").actorId("u-admin-1").actorName("Nguyễn Văn Quản").role("ADMIN")
                        .action("EXPORT").module("reports").entityType("report").entityId("grade-dist")
                        .detail("Xuất phổ điểm HK1").createdAt(now.minusSeconds(1800)).build()));
    }

    // ---------- Chat (B6/D3) ----------
    private void seedChat(ChatService chat) {
        Instant now = Instant.now();
        chat.seed(List.of(
                ChatMessage.builder().id("msg-1").senderId("u-parent-1").senderName("Phạm Văn Quân")
                        .recipientId("u-teacher-1").recipientName("Trần Thị Hoa")
                        .body("Chào cô, em là phụ huynh bé An ạ.").readFlag(true)
                        .createdAt(now.minusSeconds(7200)).build(),
                ChatMessage.builder().id("msg-2").senderId("u-teacher-1").senderName("Trần Thị Hoa")
                        .recipientId("u-parent-1").recipientName("Phạm Văn Quân")
                        .body("Chào anh, bé An học tốt môn Toán ạ. Anh yên tâm nhé.").readFlag(false)
                        .createdAt(now.minusSeconds(3600)).build(),
                ChatMessage.builder().id("msg-3").senderId("u-student-1").senderName("Phạm Hoài An")
                        .recipientId("u-teacher-1").recipientName("Trần Thị Hoa")
                        .body("Cô ơi em chưa hiểu bài 3 ạ.").readFlag(false)
                        .createdAt(now.minusSeconds(1800)).build()));
    }

    // ---------- Builders ----------
    private static User base(String id, String u, String h, String name, String email, String phone, String role) {
        return User.builder().id(id).username(u).passwordHash(h).fullName(name)
                .email(email).phone(phone).role(role).status("ACTIVE").createdAt(Instant.now()).build();
    }

    private static User teacher(String id, String u, String h, String name, String email,
                                String phone, String code, String subject) {
        User x = base(id, u, h, name, email, phone, "TEACHER");
        x.setTeacherCode(code);
        x.setMainSubject(subject);
        return x;
    }

    private static User student(String id, String u, String h, String name, String email,
                                String phone, String code, String classId, String className) {
        User x = base(id, u, h, name, email, phone, "STUDENT");
        x.setStudentCode(code);
        x.setClassId(classId);
        x.setClassName(className);
        return x;
    }

    private static ParentStudent rel(String id, String parentId, String studentId, boolean primary) {
        return ParentStudent.builder().id(id).parentId(parentId).studentId(studentId)
                .primaryContact(primary).build();
    }

    private static SchoolClass cls(String id, String code, String name, String grade, String hr, int count) {
        SchoolClass.SchoolClassBuilder builder = SchoolClass.builder()
                .id(id).code(code).name(name).gradeLevel(grade)
                .academicYearId("ay-2026").homeroomTeacherId(hr)
                .studentCount(count);
        if (hr != null) {
            builder.homeroomTeacherName("u-teacher-1".equals(hr) ? "Trần Thị Hoa" : "Lê Văn Minh")
                    .homeroomAssignedAt(Instant.now()).homeroomAssignedBy("u-admin-1");
        }
        return builder.build();
    }

    private static Subject subj(String id, String code, String name) {
        return Subject.builder().id(id).code(code).name(name).build();
    }

    private static TimetableSlot slot(String id, String classId, String subjectId, String subjectName,
                                      String teacherId, String teacherName, String room, String day,
                                      int period, String start, String end) {
        return TimetableSlot.builder().id(id).classId(classId).subjectId(subjectId).subjectName(subjectName)
                .teacherId(teacherId).teacherName(teacherName).roomCode(room).dayOfWeek(day)
                .periodNo(period).startTime(start).endTime(end).semesterId("sm-2026-1").build();
    }

    private static ExamCategory cat(String id, String code, String name, double weight, int requiredCount) {
        return ExamCategory.builder().id(id).code(code).name(name).weight(weight)
                .requiredCount(requiredCount).build();
    }

    private static Grade grade(String id, String studentId, String subjectId, String subjectName,
                               String cat, String catName, double score, String recordedAt) {
        return grade(id, studentId, subjectId, subjectName, cat, catName, 1, score, recordedAt);
    }

    private static Grade grade(String id, String studentId, String subjectId, String subjectName,
                               String cat, String catName, int assessmentIndex, double score, String recordedAt) {
        return Grade.builder().id(id).studentId(studentId).subjectId(subjectId).subjectName(subjectName)
                .semesterId("sm-2026-1").category(cat).categoryName(catName)
                .assessmentIndex(assessmentIndex).score(score)
                .recordedAt(Instant.parse(recordedAt)).build();
    }

    private static AttendanceRecord att(String id, String studentId, String classId, String slotId,
                                        String date, String status, String note, String subject, int period) {
        return AttendanceRecord.builder().id(id).studentId(studentId).classId(classId).slotId(slotId)
                .date(LocalDate.parse(date)).status(status).note(note).subjectName(subject).periodNo(period).build();
    }

    private static NotificationTemplate tpl(String id, String code, String name, String channel,
                                            String title, String body) {
        return NotificationTemplate.builder().id(id).code(code).name(name).channel(channel)
                .titleTemplate(title).bodyTemplate(body).active(true).build();
    }
}
