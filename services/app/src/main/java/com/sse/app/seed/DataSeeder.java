package com.sse.app.seed;

import com.sse.app.academic.assignment.AssignmentService;
import com.sse.app.academic.assignment.AssignmentDtos.CreateAssignmentRequest;
import com.sse.app.academic.assignment.AssignmentDtos.GradeSubmissionRequest;
import com.sse.app.academic.assignment.AssignmentDtos.SubmitRequest;
import com.sse.app.academic.attendance.AttendanceRecord;
import com.sse.app.academic.attendance.AttendanceService;
import com.sse.app.academic.grade.ExamCategory;
import com.sse.app.academic.grade.Grade;
import com.sse.app.academic.grade.GradeService;
import com.sse.app.academic.structure.*;
import com.sse.app.academic.teaching.TeacherClassSubject;
import com.sse.app.academic.teaching.TeachingAssignmentRepository;
import com.sse.app.academic.timetable.TimetableService;
import com.sse.app.academic.timetable.TimetableSlot;
import com.sse.app.audit.AuditLog;
import com.sse.app.audit.AuditService;
import com.sse.app.chat.ChatMessage;
import com.sse.app.chat.ChatService;
import com.sse.app.finance.FinanceService;
import com.sse.app.identity.ParentStudent;
import com.sse.app.identity.ParentStudentRepository;
import com.sse.app.identity.User;
import com.sse.app.identity.UserRepository;
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
import java.util.ArrayList;
import java.util.List;

/**
 * Fresh local demonstration data.
 *
 * All identities below are fictional. Grade 10 is deliberately empty so Excel import
 * can be tested repeatedly without deleting existing student or parent accounts.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "sse.seed.enabled", havingValue = "true", matchIfMissing = true)
public class DataSeeder {
    private static final String YEAR_ID = "ay-2026";
    private static final String SEMESTER_1 = "sm-2026-1";

    private static final List<TeacherSeed> TEACHERS = List.of(
            new TeacherSeed("u-t-math", "gv.toan", "Nguyen Thi Mai An", "GV001", "MATH", "Toan"),
            new TeacherSeed("u-t-lit", "gv.van", "Tran Van Huy", "GV002", "LIT", "Ngu van"),
            new TeacherSeed("u-t-eng", "gv.anh", "Le Thu Trang", "GV003", "ENG", "Tieng Anh"),
            new TeacherSeed("u-t-phys", "gv.ly", "Pham Quang Minh", "GV004", "PHYS", "Vat ly"),
            new TeacherSeed("u-t-chem", "gv.hoa", "Do Khanh Linh", "GV005", "CHEM", "Hoa hoc"),
            new TeacherSeed("u-t-bio", "gv.sinh", "Vu Hoai Nam", "GV006", "BIO", "Sinh hoc"),
            new TeacherSeed("u-t-hist", "gv.su", "Bui Thanh Ha", "GV007", "HIST", "Lich su"),
            new TeacherSeed("u-t-geo", "gv.dia", "Hoang Gia Bao", "GV008", "GEO", "Dia ly"),
            new TeacherSeed("u-t-it", "gv.tin", "Nguyen Duc Long", "GV009", "IT", "Tin hoc"),
            new TeacherSeed("u-t-pe", "gv.theduc", "Dinh Ngoc Son", "GV010", "PE", "Giao duc the chat"),
            new TeacherSeed("u-t-civic", "gv.gdcd", "Mai Phuong Thao", "GV011", "CIVIC", "GDKT va PL"),
            new TeacherSeed("u-t-defense", "gv.gdqp", "Luong Thanh Binh", "GV012", "DEF", "GDQP-AN")
    );

    private static final List<StudentSeed> STUDENTS = List.of(
            new StudentSeed("u-s-minh", "hs.minh", "Nguyen Gia Minh", "HS2601101", "c-11a1", "11A1"),
            new StudentSeed("u-s-linh", "hs.linh", "Tran Khanh Linh", "HS2601102", "c-11a1", "11A1"),
            new StudentSeed("u-s-quan", "hs.quan", "Le Minh Quan", "HS2601103", "c-11a1", "11A1"),
            new StudentSeed("u-s-ngoc", "hs.ngoc", "Pham Bao Ngoc", "HS2601104", "c-11a1", "11A1"),
            new StudentSeed("u-s-huy", "hs.huy", "Do Anh Huy", "HS2601105", "c-11a2", "11A2"),
            new StudentSeed("u-s-thao", "hs.thao", "Vu Phuong Thao", "HS2601106", "c-11a2", "11A2"),
            new StudentSeed("u-s-khoi", "hs.khoi", "Bui Dang Khoi", "HS2601107", "c-11a2", "11A2"),
            new StudentSeed("u-s-yen", "hs.yen", "Hoang Nhu Yen", "HS2601108", "c-11a2", "11A2"),
            new StudentSeed("u-s-mai", "hs.mai", "Nguyen Thanh Mai", "HS2601201", "c-12a1", "12A1"),
            new StudentSeed("u-s-nam", "hs.nam", "Tran Quoc Nam", "HS2601202", "c-12a1", "12A1"),
            new StudentSeed("u-s-phuong", "hs.phuong", "Le Bao Phuong", "HS2601203", "c-12a1", "12A1"),
            new StudentSeed("u-s-viet", "hs.viet", "Pham Trung Viet", "HS2601204", "c-12a1", "12A1"),
            new StudentSeed("u-s-lam", "hs.lam", "Do Minh Lam", "HS2601205", "c-12a2", "12A2"),
            new StudentSeed("u-s-han", "hs.han", "Vu Thu Han", "HS2601206", "c-12a2", "12A2"),
            new StudentSeed("u-s-tuan", "hs.tuan", "Bui Anh Tuan", "HS2601207", "c-12a2", "12A2"),
            new StudentSeed("u-s-chi", "hs.chi", "Hoang Lan Chi", "HS2601208", "c-12a2", "12A2")
    );

    @Bean
    ApplicationRunner seedRunner(UserRepository users, ParentStudentRepository relations,
                                 PasswordEncoder encoder, StructureService structure,
                                 TeachingAssignmentRepository teachingAssignments,
                                 TimetableService timetable, GradeService grades,
                                 AttendanceService attendance, AssignmentService assignments,
                                 NotificationService notifications, AuditService audit,
                                 ChatService chat, FinanceService finance) {
        return args -> {
            if (users.count() > 0) {
                log.info("[seed] Existing database detected; skipping seed.");
                return;
            }
            log.info("[seed] Creating fresh high-school demonstration data...");
            seedUsers(users, relations, encoder);
            seedStructure(structure);
            seedTeachingAssignments(teachingAssignments);
            seedTimetable(timetable);
            seedGrades(grades);
            seedAttendance(attendance);
            seedAssignments(assignments);
            seedNotifications(notifications);
            seedAudit(audit);
            seedChat(chat);
            finance.seedDefaultPeriodAndInvoices();
            log.info("[seed] Complete: {} users, grades 10-12 only; grade 10 has no students.", users.count());
        };
    }

    private void seedUsers(UserRepository users, ParentStudentRepository relations, PasswordEncoder encoder) {
        users.save(base("u-admin-1", "admin", encoder.encode("admin@123"), "School Administrator",
                "admin@demo.sse.local", "0900000001", "ADMIN"));
        int teacherNumber = 1;
        for (TeacherSeed teacher : TEACHERS) {
            users.save(teacher(teacher.id(), teacher.username(), encoder.encode("teacher@123"), teacher.name(),
                    "teacher" + teacherNumber + "@demo.sse.local", "0911000" + pad(teacherNumber, 3),
                    teacher.code(), teacher.subjectName()));
            teacherNumber++;
        }
        int studentNumber = 1;
        for (StudentSeed student : STUDENTS) {
            users.save(student(student.id(), student.username(), encoder.encode("student@123"), student.name(),
                    "student" + studentNumber + "@demo.sse.local", "0922000" + pad(studentNumber, 3),
                    student.code(), student.classId(), student.classCode()));
            studentNumber++;
        }

        List<ParentSeed> parents = List.of(
                new ParentSeed("u-p-nguyen", "ph.nguyen", "Nguyen Van Duc", List.of("u-s-minh", "u-s-mai")),
                new ParentSeed("u-p-tran", "ph.tran", "Tran Thi Lan", List.of("u-s-linh", "u-s-nam")),
                new ParentSeed("u-p-le", "ph.le", "Le Quang Hieu", List.of("u-s-quan", "u-s-phuong")),
                new ParentSeed("u-p-pham", "ph.pham", "Pham Thu Huong", List.of("u-s-ngoc", "u-s-viet")),
                new ParentSeed("u-p-do", "ph.do", "Do Minh Tuan", List.of("u-s-huy", "u-s-lam")),
                new ParentSeed("u-p-vu", "ph.vu", "Vu Thanh Van", List.of("u-s-thao", "u-s-han")),
                new ParentSeed("u-p-bui", "ph.bui", "Bui Anh Khoa", List.of("u-s-khoi", "u-s-tuan")),
                new ParentSeed("u-p-hoang", "ph.hoang", "Hoang Mai Anh", List.of("u-s-yen", "u-s-chi"))
        );
        int parentNumber = 1;
        for (ParentSeed parent : parents) {
            users.save(base(parent.id(), parent.username(), encoder.encode("parent@123"), parent.name(),
                    "parent" + parentNumber + "@demo.sse.local", "0933000" + pad(parentNumber, 3), "PARENT"));
            int relationNumber = 1;
            for (String childId : parent.childIds()) {
                relations.save(ParentStudent.builder().id("ps-" + parentNumber + "-" + relationNumber)
                        .parentId(parent.id()).studentId(childId).primaryContact(relationNumber == 1).build());
                relationNumber++;
            }
            parentNumber++;
        }
    }

    private void seedStructure(StructureService structure) {
        LocalDate now = LocalDate.now();
        AcademicYear year = AcademicYear.builder().id(YEAR_ID).code("2026-2027").name("Nam hoc 2026-2027")
                .startDate(LocalDate.of(2026, 9, 5)).endDate(LocalDate.of(2027, 5, 31)).status("ACTIVE").build();
        List<Semester> semesters = List.of(
                Semester.builder().id(SEMESTER_1).academicYearId(YEAR_ID).code("HK1").name("Hoc ky 1")
                        .sequence(1).startDate(LocalDate.of(2026, 9, 5)).endDate(LocalDate.of(2027, 1, 15)).status("ACTIVE").build(),
                Semester.builder().id("sm-2026-2").academicYearId(YEAR_ID).code("HK2").name("Hoc ky 2")
                        .sequence(2).startDate(LocalDate.of(2027, 1, 20)).endDate(LocalDate.of(2027, 5, 31)).status("PLANNED").build()
        );
        List<SchoolClass> classes = new ArrayList<>();
        int classIndex = 0;
        for (int grade = 10; grade <= 12; grade++) {
            for (int number = 1; number <= 10; number++) {
                String code = grade + "A" + number;
                classes.add(SchoolClass.builder().id("c-" + code.toLowerCase()).code(code).name("Lop " + code)
                        .gradeLevel("K" + grade).academicYearId(YEAR_ID)
                        .homeroomTeacherId(TEACHERS.get(classIndex % TEACHERS.size()).id())
                        .studentCount(studentCount(code)).build());
                classIndex++;
            }
        }
        List<Subject> subjects = TEACHERS.stream().map(t -> Subject.builder().id("sj-" + t.subjectCode().toLowerCase())
                .code(t.subjectCode()).name(t.subjectName()).build()).toList();
        List<Room> rooms = List.of(
                room("rm-101", "P101", "Phong hoc 101", 45), room("rm-102", "P102", "Phong hoc 102", 45),
                room("rm-201", "P201", "Phong hoc 201", 45), room("rm-202", "P202", "Phong hoc 202", 45),
                room("rm-lab", "LAB1", "Phong thi nghiem", 35), room("rm-it", "IT1", "Phong tin hoc", 40)
        );
        structure.seedAll(List.of(year), semesters, classes, subjects, rooms, List.of(
                SchoolHoliday.builder().id("hol-national").date(now.plusDays(30)).name("Ngay nghi mau").build()));
    }

    private void seedTeachingAssignments(TeachingAssignmentRepository repository) {
        List<TeacherClassSubject> rows = new ArrayList<>();
        for (int grade = 10; grade <= 12; grade++) {
            for (int number = 1; number <= 10; number++) {
                String classCode = grade + "A" + number;
                String classId = "c-" + classCode.toLowerCase();
                for (TeacherSeed teacher : TEACHERS) {
                    rows.add(TeacherClassSubject.builder().id("tcs-" + classCode.toLowerCase() + "-" + teacher.subjectCode().toLowerCase())
                            .teacherId(teacher.id()).teacherName(teacher.name()).classId(classId).classCode(classCode)
                            .subjectId("sj-" + teacher.subjectCode().toLowerCase()).subjectName(teacher.subjectName())
                            .semesterId(SEMESTER_1).status("ACTIVE").createdAt(Instant.now()).updatedAt(Instant.now()).build());
                }
            }
        }
        repository.saveAll(rows);
    }

    private void seedTimetable(TimetableService timetable) {
        List<TimetableSlot> rows = new ArrayList<>();
        String[] classes = {"11A1", "11A2", "12A1", "12A2"};
        String[] days = {"MON", "TUE", "WED", "THU"};
        for (int classIndex = 0; classIndex < classes.length; classIndex++) {
            for (int subjectIndex = 0; subjectIndex < 4; subjectIndex++) {
                TeacherSeed teacher = TEACHERS.get(subjectIndex);
                String code = classes[classIndex];
                rows.add(TimetableSlot.builder().id("tt-" + code.toLowerCase() + "-" + teacher.subjectCode().toLowerCase())
                        .classId("c-" + code.toLowerCase()).subjectId("sj-" + teacher.subjectCode().toLowerCase())
                        .subjectName(teacher.subjectName()).teacherId(teacher.id()).teacherName(teacher.name())
                        .roomCode("P" + (101 + classIndex)).dayOfWeek(days[subjectIndex]).periodNo(classIndex + 1)
                        .startTime("07:00").endTime("07:45").semesterId(SEMESTER_1).build());
            }
        }
        timetable.seedSlots(rows);
    }

    private void seedGrades(GradeService grades) {
        List<ExamCategory> categories = List.of(
                category("ec-oral", "ORAL", "Mieng", 1), category("ec-15m", "15M", "15 phut", 1),
                category("ec-mid", "MID", "Giua ky", 2), category("ec-final", "FINAL", "Cuoi ky", 3)
        );
        List<Grade> rows = new ArrayList<>();
        double[] marks = {7.2, 8.4, 6.8, 9.0, 7.6, 8.2, 6.4, 8.8, 7.4, 9.2};
        for (int studentIndex = 0; studentIndex < STUDENTS.size(); studentIndex++) {
            StudentSeed student = STUDENTS.get(studentIndex);
            for (int subjectIndex = 0; subjectIndex < 4; subjectIndex++) {
                TeacherSeed teacher = TEACHERS.get(subjectIndex);
                rows.add(Grade.builder().id("g-" + (studentIndex + 1) + "-" + subjectIndex)
                        .studentId(student.id()).subjectId("sj-" + teacher.subjectCode().toLowerCase())
                        .subjectName(teacher.subjectName()).semesterId(SEMESTER_1).category(subjectIndex == 2 ? "MID" : "15M")
                        .categoryName(subjectIndex == 2 ? "Giua ky" : "15 phut")
                        .score(marks[(studentIndex + subjectIndex * 2) % marks.length])
                        .recordedAt(Instant.now().minusSeconds((long) (studentIndex + 1) * 3600)).build());
            }
        }
        grades.seed(categories, rows);
    }

    private void seedAttendance(AttendanceService attendance) {
        List<AttendanceRecord> rows = new ArrayList<>();
        for (int studentIndex = 0; studentIndex < STUDENTS.size(); studentIndex++) {
            StudentSeed student = STUDENTS.get(studentIndex);
            for (int dayOffset = 0; dayOffset < 5; dayOffset++) {
                String status = "PRESENT";
                if (studentIndex == 1 && dayOffset == 1) status = "LATE";
                if (studentIndex == 4 && dayOffset == 2) status = "ABSENT_UNEXCUSED";
                if (studentIndex == 9 && dayOffset == 3) status = "ABSENT_EXCUSED";
                rows.add(AttendanceRecord.builder().id("att-" + studentIndex + "-" + dayOffset).studentId(student.id())
                        .classId(student.classId()).slotId("tt-" + student.classCode().toLowerCase() + "-math")
                        .date(LocalDate.now().minusDays(dayOffset)).status(status)
                        .note("PRESENT".equals(status) ? null : "Demo attendance record")
                        .subjectName("Toan").periodNo(1).build());
            }
        }
        attendance.seed(rows);
    }

    private void seedAssignments(AssignmentService assignments) {
        assignments.create(new CreateAssignmentRequest("asg-11a1-math", "c-11a1", "sj-math", "Luyen tap ham so",
                "Hoan thanh bai tap chuong ham so.", Instant.now().plusSeconds(5 * 86_400L), false, null, true, null), "u-t-math");
        assignments.create(new CreateAssignmentRequest("asg-11a2-eng", "c-11a2", "sj-eng", "English presentation",
                "Prepare a short presentation.", Instant.now().plusSeconds(7 * 86_400L), true, null, true, null), "u-t-eng");
        assignments.create(new CreateAssignmentRequest("asg-12a1-lit", "c-12a1", "sj-lit", "Phan tich tac pham",
                "Write a short analysis.", Instant.now().plusSeconds(4 * 86_400L), false, null, true, null), "u-t-lit");
        assignments.submit("asg-11a1-math", "u-s-minh", new SubmitRequest("Bai lam cua Nguyen Gia Minh", null, null));
        assignments.submit("asg-11a1-math", "u-s-linh", new SubmitRequest("Bai lam cua Tran Khanh Linh", null, null));
        assignments.grade(assignments.submissionsOf("asg-11a1-math").get(0).getId(),
                new GradeSubmissionRequest(8.4, "Lam bai tot.", null), "u-t-math");
    }

    private void seedNotifications(NotificationService notifications) {
        notifications.seed(List.of(
                Announcement.builder().id("an-welcome").title("Thong bao nam hoc moi")
                        .body("Du lieu demo da san sang de kiem thu cac luong nghiep vu.")
                        .createdAt(Instant.now()).audience("ALL").build()),
                List.of(
                        template("tpl-att", "ATTENDANCE_ABSENT", "Attendance alert", "IN_APP", "Attendance alert", "{{studentName}} {{status}}"),
                        template("tpl-grade", "GRADE_PUBLISHED", "Grade published", "IN_APP", "New grade", "{{subject}}: {{score}}"),
                        template("tpl-inv", "INVOICE_ISSUED", "Invoice issued", "IN_APP", "New invoice", "{{invoiceCode}}")
                ));
    }

    private void seedAudit(AuditService audit) {
        audit.seed(List.of(AuditLog.builder().id("audit-seed").actorId("u-admin-1").actorName("School Administrator")
                .role("ADMIN").action("SEED").module("system").entityType("database").entityId("sse_db")
                .detail("Fresh local demonstration data created.").createdAt(Instant.now()).build()));
    }

    private void seedChat(ChatService chat) {
        chat.seed(List.of(ChatMessage.builder().id("msg-demo").senderId("u-p-nguyen").senderName("Nguyen Van Duc")
                .recipientId("u-t-math").recipientName("Nguyen Thi Mai An").body("Xin chao co.")
                .readFlag(false).createdAt(Instant.now().minusSeconds(1800)).build()));
    }

    private static User base(String id, String username, String password, String name, String email, String phone, String role) {
        return User.builder().id(id).username(username).passwordHash(password).fullName(name)
                .email(email).phone(phone).role(role).status("ACTIVE").createdAt(Instant.now()).build();
    }

    private static User teacher(String id, String username, String password, String name, String email,
                                String phone, String code, String subject) {
        User user = base(id, username, password, name, email, phone, "TEACHER");
        user.setTeacherCode(code);
        user.setMainSubject(subject);
        return user;
    }

    private static User student(String id, String username, String password, String name, String email,
                                String phone, String code, String classId, String className) {
        User user = base(id, username, password, name, email, phone, "STUDENT");
        user.setStudentCode(code);
        user.setClassId(classId);
        user.setClassName(className);
        return user;
    }

    private static Room room(String id, String code, String name, int capacity) {
        return Room.builder().id(id).code(code).name(name).capacity(capacity).build();
    }

    private static ExamCategory category(String id, String code, String name, double weight) {
        return ExamCategory.builder().id(id).code(code).name(name).weight(weight).build();
    }

    private static NotificationTemplate template(String id, String code, String name, String channel,
                                                  String title, String body) {
        return NotificationTemplate.builder().id(id).code(code).name(name).channel(channel)
                .titleTemplate(title).bodyTemplate(body).active(true).build();
    }

    private static int studentCount(String classCode) {
        return switch (classCode) {
            case "11A1", "11A2", "12A1", "12A2" -> 4;
            default -> 0;
        };
    }

    private static String pad(int value, int length) {
        return String.format("%0" + length + "d", value);
    }

    private record TeacherSeed(String id, String username, String name, String code, String subjectCode, String subjectName) {}
    private record StudentSeed(String id, String username, String name, String code, String classId, String classCode) {}
    private record ParentSeed(String id, String username, String name, List<String> childIds) {}
}
