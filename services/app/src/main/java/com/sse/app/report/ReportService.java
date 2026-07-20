package com.sse.app.report;

import com.sse.app.academic.attendance.AttendanceRecord;
import com.sse.app.academic.attendance.AttendanceService;
import com.sse.app.academic.grade.Grade;
import com.sse.app.academic.grade.GradeService;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.finance.FinanceService;
import com.sse.app.identity.UserService;
import com.sse.app.academic.summary.YearEndService;
import com.sse.app.academic.summary.StudentYearlySummary;
import com.sse.app.academic.assignment.AssignmentService;
import com.sse.app.academic.timetable.TeachingAssignmentService;
import com.sse.app.identity.UserDto;
import com.sse.app.security.CurrentUser;
import org.springframework.stereotype.Service;

import java.util.*;

/** A8: Báo cáo & thống kê (tính trong bộ nhớ — phù hợp quy mô GĐ1). */
@Service
public class ReportService {

    private final GradeService grades;
    private final AttendanceService attendance;
    private final FinanceService finance;
    private final UserService users;
    private final StructureService structure;
    private final YearEndService yearEnd;
    private final AssignmentService assignments;
    private final TeachingAssignmentService teachingAssignments;

    public ReportService(GradeService grades, AttendanceService attendance, FinanceService finance,
                         UserService users, StructureService structure, YearEndService yearEnd,
                         AssignmentService assignments, TeachingAssignmentService teachingAssignments) {
        this.grades = grades;
        this.attendance = attendance;
        this.finance = finance;
        this.users = users;
        this.structure = structure;
        this.yearEnd = yearEnd;
        this.assignments = assignments;
        this.teachingAssignments = teachingAssignments;
    }

    public Map<String, Object> overview() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("students", users.userIdsByRole("STUDENT").size());
        m.put("teachers", users.userIdsByRole("TEACHER").size());
        m.put("parents", users.userIdsByRole("PARENT").size());
        m.put("admins", users.userIdsByRole("ADMIN").size());
        m.put("classes", structure.listClasses(null, null).size());
        m.put("subjects", structure.listSubjects().size());
        return m;
    }

    public List<Map<String, Object>> gradeDistribution(String semesterId) {
        int[] bands = new int[4]; // <5, 5-6.4, 6.5-7.9, 8-10
        for (Grade g : grades.allGrades()) {
            if (semesterId != null && !semesterId.equals(g.getSemesterId())) continue;
            Double s = g.getScore();
            if (s == null) continue;
            if (s < 5) bands[0]++;
            else if (s < 6.5) bands[1]++;
            else if (s < 8) bands[2]++;
            else bands[3]++;
        }
        String[] labels = {"0–4.9", "5–6.4", "6.5–7.9", "8–10"};
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("band", labels[i]);
            b.put("count", bands[i]);
            out.add(b);
        }
        return out;
    }

    public Map<String, Object> attendanceSummary() {
        long present = 0, late = 0, excused = 0, unexcused = 0;
        for (AttendanceRecord r : attendance.allRecords()) {
            switch (r.getStatus() == null ? "" : r.getStatus()) {
                case "PRESENT" -> present++;
                case "LATE" -> late++;
                case "ABSENT_EXCUSED" -> excused++;
                case "ABSENT_UNEXCUSED" -> unexcused++;
                default -> { }
            }
        }
        long total = present + late + excused + unexcused;
        double rate = total == 0 ? 0 : Math.round((present + late * 0.5) / total * 1000) / 10.0;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("present", present);
        m.put("late", late);
        m.put("absentExcused", excused);
        m.put("absentUnexcused", unexcused);
        m.put("total", total);
        m.put("attendanceRate", rate);
        return m;
    }

    public Map<String, Object> revenue() {
        return finance.revenueReport();
    }

    public Map<String, Object> promotion(String academicYearId) {
        List<StudentYearlySummary> rows = yearEnd.summaries(academicYearId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", rows.size());
        result.put("promoted", rows.stream().filter(row -> "PROMOTED".equals(row.getPromotionStatus())).count());
        result.put("pendingClass", rows.stream().filter(row -> "PROMOTED_PENDING_CLASS".equals(row.getPromotionStatus())).count());
        result.put("graduated", rows.stream().filter(row -> "GRADUATED".equals(row.getPromotionStatus())).count());
        result.put("retained", rows.stream().filter(row -> "RETAINED".equals(row.getPromotionStatus())).count());
        result.put("incomplete", rows.stream().filter(row -> "INCOMPLETE".equals(row.getPromotionStatus())).count());
        return result;
    }

    public String exportCsv(String type, String semesterId) {
        StringBuilder csv = new StringBuilder("\uFEFF");
        switch (type == null ? "overview" : type.toLowerCase()) {
            case "grades" -> {
                csv.append("Khoảng điểm,Số kết quả\n");
                gradeDistribution(semesterId).forEach(row -> csv.append(cell(row.get("band"))).append(',')
                        .append(cell(row.get("count"))).append('\n'));
            }
            case "attendance" -> {
                csv.append("Trạng thái,Số lượt\n");
                Map<String, Object> data = attendanceSummary();
                csv.append("Có mặt,").append(data.get("present")).append('\n')
                        .append("Đi muộn,").append(data.get("late")).append('\n')
                        .append("Vắng có phép,").append(data.get("absentExcused")).append('\n')
                        .append("Vắng không phép,").append(data.get("absentUnexcused")).append('\n');
            }
            case "revenue" -> {
                csv.append("Hạng mục,Giá trị\n");
                revenue().forEach((key, value) -> csv.append(cell(key)).append(',').append(cell(value)).append('\n'));
            }
            case "overview" -> {
                csv.append("Nhóm dữ liệu,Số lượng\n");
                overview().forEach((key, value) -> csv.append(cell(key)).append(',').append(cell(value)).append('\n'));
            }
            default -> throw com.sse.app.common.ApiException.badRequest("Loại báo cáo không hợp lệ");
        }
        return csv.toString();
    }

    public Map<String, Object> personalOverview(CurrentUser actor, String childId) {
        Set<String> studentIds = scopedStudentIds(actor, childId);
        List<Grade> gradeRows = grades.allGrades().stream().filter(item -> studentIds.contains(item.getStudentId())).toList();
        List<AttendanceRecord> attendanceRows = attendance.allRecords().stream().filter(item -> studentIds.contains(item.getStudentId())).toList();
        long present = attendanceRows.stream().filter(item -> "PRESENT".equals(item.getStatus())).count();
        long late = attendanceRows.stream().filter(item -> "LATE".equals(item.getStatus())).count();
        long excused = attendanceRows.stream().filter(item -> "ABSENT_EXCUSED".equals(item.getStatus())).count();
        long unexcused = attendanceRows.stream().filter(item -> "ABSENT_UNEXCUSED".equals(item.getStatus())).count();
        double average = gradeRows.stream().filter(item -> item.getScore() != null).mapToDouble(Grade::getScore).average().orElse(0);
        long submissions = studentIds.stream().mapToLong(id -> assignments.submissionsByStudent(id).size()).sum();
        long gradedSubmissions = studentIds.stream().flatMap(id -> assignments.submissionsByStudent(id).stream())
                .filter(item -> "GRADED".equals(item.getStatus())).count();
        Map<String, Double> subjectAverages = new LinkedHashMap<>();
        gradeRows.stream().filter(item -> item.getScore() != null)
                .collect(java.util.stream.Collectors.groupingBy(Grade::getSubjectName, LinkedHashMap::new,
                java.util.stream.Collectors.averagingDouble(Grade::getScore))).forEach((key, value) ->
                subjectAverages.put(key, Math.round(value * 10) / 10.0));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", actor.role());
        result.put("studentCount", studentIds.size());
        result.put("classCount", actor.isTeacher() ? teachingAssignments.assignmentsOfTeacher(actor.id()).stream()
                .map(item -> item.getClassId()).distinct().count() : 1);
        result.put("gradeCount", gradeRows.size());
        result.put("averageScore", Math.round(average * 10) / 10.0);
        result.put("subjectAverages", subjectAverages);
        result.put("attendanceTotal", attendanceRows.size());
        result.put("present", present);
        result.put("late", late);
        result.put("absentExcused", excused);
        result.put("absentUnexcused", unexcused);
        result.put("attendanceRate", attendanceRows.isEmpty() ? 0 : Math.round((present + late * 0.5) / attendanceRows.size() * 1000) / 10.0);
        result.put("submissionCount", submissions);
        result.put("gradedSubmissionCount", gradedSubmissions);
        if (actor.isParent()) result.put("finance", finance.parentFinanceSummary(actor.id()));
        return result;
    }

    public String exportPersonalCsv(CurrentUser actor, String childId) {
        Set<String> studentIds = scopedStudentIds(actor, childId);
        StringBuilder csv = new StringBuilder("\uFEFF");
        csv.append("Nhóm dữ liệu,Học sinh,Môn học,Ngày,Trạng thái,Giá trị,Ghi chú\n");
        grades.allGrades().stream().filter(item -> studentIds.contains(item.getStudentId())).forEach(item -> csv
                .append(cell("Điểm")).append(',').append(cell(users.fullNameOf(item.getStudentId()))).append(',')
                .append(cell(item.getSubjectName())).append(',').append(cell(item.getRecordedAt())).append(',')
                .append(cell(item.getCategoryName())).append(',').append(cell(item.getScore())).append(',').append(cell(item.getNote())).append('\n'));
        attendance.allRecords().stream().filter(item -> studentIds.contains(item.getStudentId())).forEach(item -> csv
                .append(cell("Chuyên cần")).append(',').append(cell(users.fullNameOf(item.getStudentId()))).append(',')
                .append(cell(item.getSubjectName())).append(',').append(cell(item.getDate())).append(',')
                .append(cell(item.getStatus())).append(',').append(cell(item.getPeriodNo())).append(',').append(cell(item.getNote())).append('\n'));
        return csv.toString();
    }

    private Set<String> scopedStudentIds(CurrentUser actor, String childId) {
        if (actor.isStudent()) return Set.of(actor.id());
        if (actor.isParent()) {
            List<UserDto> children = users.childrenOf(actor.id());
            if (childId != null && !childId.isBlank()) {
                users.assertParentOf(actor.id(), childId);
                return Set.of(childId);
            }
            return children.stream().map(UserDto::id).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        }
        if (actor.isTeacher()) {
            Set<String> classIds = teachingAssignments.assignmentsOfTeacher(actor.id()).stream()
                    .map(item -> item.getClassId()).collect(java.util.stream.Collectors.toSet());
            structure.classesOfHomeroom(actor.id()).forEach(item -> classIds.add(item.getId()));
            return classIds.stream().flatMap(classId -> users.list("STUDENT", null, classId).stream())
                    .map(UserDto::id).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        }
        throw com.sse.app.common.ApiException.forbidden("Báo cáo cá nhân chỉ dành cho giáo viên, học sinh và phụ huynh");
    }

    private String cell(Object value) {
        String text = String.valueOf(value == null ? "" : value).replace("\"", "\"\"");
        if (!text.isEmpty() && "=+-@".indexOf(text.charAt(0)) >= 0) text = "'" + text;
        return "\"" + text + "\"";
    }
}
