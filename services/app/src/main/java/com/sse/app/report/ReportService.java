package com.sse.app.report;

import com.sse.app.academic.attendance.AttendanceRecord;
import com.sse.app.academic.attendance.AttendanceService;
import com.sse.app.academic.grade.Grade;
import com.sse.app.academic.grade.GradeCalculationService;
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
import com.sse.app.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;

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
    private final GradeCalculationService gradeCalculations;
    private final JdbcTemplate jdbc;

    public ReportService(GradeService grades, AttendanceService attendance, FinanceService finance,
                         UserService users, StructureService structure, YearEndService yearEnd,
                         AssignmentService assignments, TeachingAssignmentService teachingAssignments,
                         GradeCalculationService gradeCalculations, JdbcTemplate jdbc) {
        this.grades = grades;
        this.attendance = attendance;
        this.finance = finance;
        this.users = users;
        this.structure = structure;
        this.yearEnd = yearEnd;
        this.assignments = assignments;
        this.teachingAssignments = teachingAssignments;
        this.gradeCalculations = gradeCalculations;
        this.jdbc = jdbc;
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

    public List<Map<String, Object>> gradeDistribution(String semesterId, String classId, String subjectId) {
        return gradeDistribution(null, semesterId, classId, subjectId);
    }

    public List<Map<String, Object>> gradeDistribution(String academicYearId, String semesterId,
                                                        String classId, String subjectId) {
        ReportScope scope = validateScope(academicYearId, semesterId, classId);
        int[] bands = new int[4]; // <5, 5-6.4, 6.5-7.9, 8-10
        StringBuilder sql = new StringBuilder("""
                select case when g.score < 5 then 0 when g.score < 6.5 then 1
                            when g.score < 8 then 2 else 3 end score_band,
                       count(*) total
                from grades g where g.score is not null
                """);
        List<Object> args = new ArrayList<>();
        if (!scope.semesterIds().isEmpty()) {
            sql.append(" and g.semester_id in (")
                    .append(String.join(",", Collections.nCopies(scope.semesterIds().size(), "?"))).append(')');
            args.addAll(scope.semesterIds());
        }
        if (subjectId != null && !subjectId.isBlank()) {
            sql.append(" and g.subject_id=?");
            args.add(subjectId);
        }
        if (classId != null && !classId.isBlank()) {
            sql.append(" and exists (select 1 from class_enrollments ce where ce.student_id=g.student_id")
                    .append(" and ce.class_id=? and ce.status<>'ROLLED_BACK')");
            args.add(classId);
        }
        sql.append(" group by score_band");
        jdbc.query(sql.toString(), rs -> {
            while (rs.next()) bands[rs.getInt("score_band")] = rs.getInt("total");
        }, args.toArray());
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

    public Map<String, Object> attendanceSummary(String classId, java.time.LocalDate startDate, java.time.LocalDate endDate) {
        return attendanceSummary(null, classId, startDate, endDate);
    }

    public Map<String, Object> attendanceSummary(String academicYearId, String classId,
                                                  java.time.LocalDate startDate, java.time.LocalDate endDate) {
        ReportScope scope = validateScope(academicYearId, null, classId);
        java.time.LocalDate effectiveStart = startDate != null ? startDate : scope.startDate();
        java.time.LocalDate effectiveEnd = endDate != null ? endDate : scope.endDate();
        StringBuilder sql = new StringBuilder("select status,count(*) total from attendance_records where 1=1");
        List<Object> args = new ArrayList<>();
        if (classId != null && !classId.isBlank()) { sql.append(" and class_id=?"); args.add(classId); }
        if (effectiveStart != null) { sql.append(" and date>=?"); args.add(effectiveStart); }
        if (effectiveEnd != null) { sql.append(" and date<=?"); args.add(effectiveEnd); }
        sql.append(" group by status");
        Map<String, Long> counts = new HashMap<>();
        jdbc.query(sql.toString(), rs -> {
            while (rs.next()) counts.put(rs.getString("status"), rs.getLong("total"));
        }, args.toArray());
        long present = counts.getOrDefault("PRESENT", 0L);
        long late = counts.getOrDefault("LATE", 0L);
        long excused = counts.getOrDefault("ABSENT_EXCUSED", 0L);
        long unexcused = counts.getOrDefault("ABSENT_UNEXCUSED", 0L);
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

    public Map<String, Object> revenue(String periodId, String classId) {
        return finance.revenueReport(periodId, classId);
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

    public String exportCsv(String type, String semesterId, String classId, String subjectId,
                            java.time.LocalDate startDate, java.time.LocalDate endDate, String periodId) {
        return exportCsv(type, null, semesterId, classId, subjectId, startDate, endDate, periodId);
    }

    public String exportCsv(String type, String academicYearId, String semesterId, String classId, String subjectId,
                            java.time.LocalDate startDate, java.time.LocalDate endDate, String periodId) {
        StringBuilder csv = new StringBuilder("\uFEFF");
        switch (type == null ? "overview" : type.toLowerCase()) {
            case "grades" -> {
                csv.append("Khoảng điểm,Số kết quả\n");
                gradeDistribution(academicYearId, semesterId, classId, subjectId).forEach(row -> csv.append(cell(row.get("band"))).append(',')
                        .append(cell(row.get("count"))).append('\n'));
            }
            case "attendance" -> {
                csv.append("Trạng thái,Số lượt\n");
                Map<String, Object> data = attendanceSummary(academicYearId, classId, startDate, endDate);
                csv.append("Có mặt,").append(data.get("present")).append('\n')
                        .append("Đi muộn,").append(data.get("late")).append('\n')
                        .append("Vắng có phép,").append(data.get("absentExcused")).append('\n')
                        .append("Vắng không phép,").append(data.get("absentUnexcused")).append('\n');
            }
            case "revenue" -> {
                csv.append("Hạng mục,Giá trị\n");
                revenue(periodId, classId).forEach((key, value) -> csv.append(cell(key)).append(',').append(cell(value)).append('\n'));
            }
            case "overview" -> {
                csv.append("Nhóm dữ liệu,Số lượng\n");
                overview().forEach((key, value) -> csv.append(cell(key)).append(',').append(cell(value)).append('\n'));
            }
            default -> throw com.sse.app.common.ApiException.badRequest("Loại báo cáo không hợp lệ");
        }
        return csv.toString();
    }

    private ReportScope validateScope(String academicYearId, String semesterId, String classId) {
        String yearId = clean(academicYearId);
        String semId = clean(semesterId);
        String targetClassId = clean(classId);
        java.time.LocalDate start = null;
        java.time.LocalDate end = null;
        Set<String> semesterIds = new LinkedHashSet<>();
        if (yearId != null) {
            var year = structure.getYear(yearId);
            start = year.getStartDate();
            end = year.getEndDate();
            structure.listSemesters(yearId).forEach(item -> semesterIds.add(item.getId()));
        }
        if (semId != null) {
            var semester = structure.getSemester(semId);
            if (yearId != null && !yearId.equals(semester.getAcademicYearId())) {
                throw ApiException.badRequest("Học kỳ không thuộc năm học đã chọn");
            }
            semesterIds.clear();
            semesterIds.add(semId);
        }
        if (targetClassId != null) {
            var schoolClass = structure.getClass(targetClassId);
            if (yearId != null && !yearId.equals(schoolClass.getAcademicYearId())) {
                throw ApiException.badRequest("Lớp không thuộc năm học đã chọn");
            }
        }
        return new ReportScope(semesterIds, start, end);
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record ReportScope(Set<String> semesterIds, java.time.LocalDate startDate,
                               java.time.LocalDate endDate) {}

    public Map<String, Object> personalOverview(CurrentUser actor, String childId) {
        Set<String> studentIds = scopedStudentIds(actor, childId);
        List<Grade> gradeRows = grades.allGrades().stream().filter(item -> studentIds.contains(item.getStudentId())).toList();
        List<AttendanceRecord> attendanceRows = attendance.allRecords().stream().filter(item -> studentIds.contains(item.getStudentId())).toList();
        long present = attendanceRows.stream().filter(item -> "PRESENT".equals(item.getStatus())).count();
        long late = attendanceRows.stream().filter(item -> "LATE".equals(item.getStatus())).count();
        long excused = attendanceRows.stream().filter(item -> "ABSENT_EXCUSED".equals(item.getStatus())).count();
        long unexcused = attendanceRows.stream().filter(item -> "ABSENT_UNEXCUSED".equals(item.getStatus())).count();
        double average = Optional.ofNullable(gradeCalculations.overallAverage(studentIds)).orElse(0d);
        long submissions = studentIds.stream().mapToLong(id -> assignments.submissionsByStudent(id).size()).sum();
        long gradedSubmissions = studentIds.stream().flatMap(id -> assignments.submissionsByStudent(id).stream())
                .filter(item -> "GRADED".equals(item.getStatus())).count();
        Map<String, Double> subjectAverages = gradeCalculations.subjectAverages(studentIds);

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
