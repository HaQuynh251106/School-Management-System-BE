package com.sse.app.report;

import com.sse.app.academic.attendance.AttendanceRecord;
import com.sse.app.academic.attendance.AttendanceService;
import com.sse.app.academic.grade.ExamCategory;
import com.sse.app.academic.grade.Grade;
import com.sse.app.academic.grade.GradeService;
import com.sse.app.academic.structure.AcademicYear;
import com.sse.app.academic.structure.SchoolClass;
import com.sse.app.academic.structure.Semester;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.academic.teaching.TeachingAssignmentService;
import com.sse.app.academic.teaching.TeachingDtos.TeachingAssignmentDto;
import com.sse.app.common.ApiException;
import com.sse.app.identity.UserDto;
import com.sse.app.identity.UserService;
import com.sse.app.report.YearSummaryPreviewDtos.AttendanceSummary;
import com.sse.app.report.YearSummaryPreviewDtos.ExpectedSubject;
import com.sse.app.report.YearSummaryPreviewDtos.PreviewMetrics;
import com.sse.app.report.YearSummaryPreviewDtos.StudentSummaryRow;
import com.sse.app.report.YearSummaryPreviewDtos.SubjectSummary;
import com.sse.app.report.YearSummaryPreviewDtos.YearSummaryPreviewResponse;
import com.sse.app.security.CurrentUser;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class YearSummaryPreviewService {

    private final StructureService structure;
    private final TeachingAssignmentService teachingAssignments;
    private final GradeService grades;
    private final AttendanceService attendance;
    private final UserService users;

    public YearSummaryPreviewService(StructureService structure,
                                     TeachingAssignmentService teachingAssignments,
                                     GradeService grades,
                                     AttendanceService attendance,
                                     UserService users) {
        this.structure = structure;
        this.teachingAssignments = teachingAssignments;
        this.grades = grades;
        this.attendance = attendance;
        this.users = users;
    }

    public YearSummaryPreviewResponse preview(String academicYearId, String semesterId,
                                              String classId, CurrentUser actor) {
        AcademicYear year = requireYear(academicYearId);
        Semester semester = requireSemester(year.getId(), semesterId);
        SchoolClass schoolClass = structure.getClass(require(classId, "classId"));
        if (!year.getId().equals(schoolClass.getAcademicYearId())) {
            throw ApiException.badRequest("Lớp không thuộc năm học đã chọn");
        }
        assertCanView(actor, schoolClass);

        List<UserDto> students = users.list("STUDENT", null, schoolClass.getId()).stream()
                .filter(student -> "ACTIVE".equals(student.status()))
                .sorted(Comparator.comparing(UserDto::studentCode,
                                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                        .thenComparing(UserDto::fullName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
        List<ExamCategory> categories = grades.listCategories().stream()
                .filter(category -> category.getCode() != null && !category.getCode().isBlank())
                .sorted(Comparator.comparing(ExamCategory::getCode))
                .toList();
        List<TeachingAssignmentDto> assignments = teachingAssignments.list(
                null, schoolClass.getId(), null, semester.getId(), "ACTIVE");

        List<String> studentIds = students.stream().map(UserDto::id).toList();
        List<Grade> semesterGrades = grades.list(null, null, semester.getId(), null, studentIds);
        Map<String, List<Grade>> gradesByStudent = semesterGrades
                .stream().collect(Collectors.groupingBy(Grade::getStudentId));
        List<ExpectedSubject> expectedSubjects = expectedSubjects(
                assignments, categories.size(), students.size(), semesterGrades);
        Map<String, List<AttendanceRecord>> attendanceByStudent = attendance
                .list(null, schoolClass.getId(), null, null)
                .stream()
                .filter(record -> inSemester(record.getDate(), semester))
                .collect(Collectors.groupingBy(AttendanceRecord::getStudentId));

        List<StudentSummaryRow> rows = students.stream()
                .map(student -> summarizeStudent(student, expectedSubjects, categories,
                        gradesByStudent.getOrDefault(student.id(), List.of()),
                        attendanceByStudent.getOrDefault(student.id(), List.of())))
                .toList();

        int readyStudents = (int) rows.stream().filter(StudentSummaryRow::ready).count();
        int missingGradeStudents = (int) rows.stream().filter(row -> row.missingGradeCount() > 0).count();
        int noAttendanceStudents = (int) rows.stream().filter(row -> row.attendance().total() == 0).count();
        Double classAverage = average(rows.stream().map(StudentSummaryRow::overallAverage).toList());
        AttendanceSummary classAttendance = attendanceSummary(attendanceByStudent.values().stream()
                .flatMap(Collection::stream).toList());

        String periodState = periodState(semester);
        List<String> warnings = new ArrayList<>();
        if (expectedSubjects.isEmpty()) {
            warnings.add("Lớp chưa có phân công môn học trong học kỳ đã chọn");
        }
        if (categories.isEmpty()) {
            warnings.add("Chưa cấu hình loại điểm bắt buộc");
        }
        if ("UPCOMING".equals(periodState)) {
            warnings.add("Học kỳ chưa bắt đầu; dữ liệu thiếu hiện chỉ dùng để chuẩn bị");
        } else if ("IN_PROGRESS".equals(periodState) && missingGradeStudents > 0) {
            warnings.add("Đang cập nhật điểm: " + missingGradeStudents + " học sinh chưa đủ đầu điểm");
        } else if (missingGradeStudents > 0) {
            warnings.add(missingGradeStudents + " học sinh còn thiếu đầu điểm");
        }
        if (!"UPCOMING".equals(periodState) && noAttendanceStudents > 0) {
            warnings.add(noAttendanceStudents + " học sinh chưa có dữ liệu chuyên cần");
        }

        return new YearSummaryPreviewResponse(
                year.getId(), displayName(year.getName(), year.getCode()),
                semester.getId(), displayName(semester.getName(), semester.getCode()),
                schoolClass.getId(), schoolClass.getCode(),
                displayName(schoolClass.getName(), schoolClass.getCode()),
                periodState, periodMessage(periodState, semester),
                Instant.now(),
                new PreviewMetrics(rows.size(), readyStudents, missingGradeStudents,
                        noAttendanceStudents, classAverage, classAttendance.attendanceRate()),
                expectedSubjects, rows, warnings);
    }

    private StudentSummaryRow summarizeStudent(UserDto student,
                                               List<ExpectedSubject> expectedSubjects,
                                               List<ExamCategory> categories,
                                               List<Grade> studentGrades,
                                               List<AttendanceRecord> studentAttendance) {
        Map<String, List<Grade>> bySubject = studentGrades.stream()
                .collect(Collectors.groupingBy(Grade::getSubjectId));
        Map<String, ExamCategory> categoryByCode = categories.stream()
                .collect(Collectors.toMap(ExamCategory::getCode, Function.identity(), (left, right) -> left,
                        LinkedHashMap::new));

        List<SubjectSummary> subjectRows = new ArrayList<>();
        int missingGradeCount = 0;
        for (ExpectedSubject subject : expectedSubjects) {
            List<Grade> subjectGrades = bySubject.getOrDefault(subject.subjectId(), List.of());
            Map<String, Grade> latestByCategory = latestByCategory(subjectGrades);
            List<String> missingCategories = categories.stream()
                    .filter(category -> !latestByCategory.containsKey(category.getCode()))
                    .map(category -> displayName(category.getName(), category.getCode()))
                    .toList();
            missingGradeCount += missingCategories.size();
            subjectRows.add(new SubjectSummary(
                    subject.subjectId(), subject.subjectName(),
                    weightedAverage(latestByCategory.values(), categoryByCode),
                    latestByCategory.size(), categories.size(), missingCategories));
        }

        AttendanceSummary attendanceResult = attendanceSummary(studentAttendance);
        Double overallAverage = average(subjectRows.stream().map(SubjectSummary::average).toList());
        List<String> warnings = new ArrayList<>();
        if (missingGradeCount > 0) warnings.add("Thiếu " + missingGradeCount + " đầu điểm");
        if (attendanceResult.total() == 0) warnings.add("Chưa có dữ liệu chuyên cần");
        boolean ready = !expectedSubjects.isEmpty() && !categories.isEmpty()
                && missingGradeCount == 0 && attendanceResult.total() > 0;
        return new StudentSummaryRow(student.id(), student.studentCode(), student.fullName(),
                overallAverage, attendanceResult, subjectRows, missingGradeCount, ready, warnings);
    }

    private List<ExpectedSubject> expectedSubjects(List<TeachingAssignmentDto> assignments,
                                                   int requiredGradeCount,
                                                   int studentCount,
                                                   List<Grade> semesterGrades) {
        Map<String, ExpectedSubject> unique = new LinkedHashMap<>();
        for (TeachingAssignmentDto assignment : assignments) {
            unique.putIfAbsent(assignment.subjectId(),
                    new ExpectedSubject(assignment.subjectId(),
                            displayName(assignment.subjectName(), assignment.subjectId()),
                            requiredGradeCount, 0, 0, 0));
        }
        Map<String, Integer> enteredBySubject = semesterGrades.stream()
                .filter(grade -> grade.getSubjectId() != null && grade.getCategory() != null
                        && grade.getScore() != null)
                .collect(Collectors.groupingBy(Grade::getSubjectId,
                        Collectors.collectingAndThen(Collectors.toList(), rows ->
                                latestGradeKeys(rows).size())));
        return unique.values().stream()
                .map(subject -> {
                    int expected = studentCount * requiredGradeCount;
                    int entered = Math.min(expected, enteredBySubject.getOrDefault(subject.subjectId(), 0));
                    double completion = expected == 0 ? 0 : round(entered * 100.0 / expected);
                    return new ExpectedSubject(subject.subjectId(), subject.subjectName(),
                            requiredGradeCount, expected, entered, completion);
                })
                .sorted(Comparator.comparing(ExpectedSubject::subjectName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private java.util.Set<String> latestGradeKeys(List<Grade> gradeRows) {
        return gradeRows.stream()
                .map(grade -> grade.getStudentId() + "|" + grade.getCategory())
                .collect(Collectors.toSet());
    }

    private Map<String, Grade> latestByCategory(List<Grade> subjectGrades) {
        Map<String, Grade> latest = new LinkedHashMap<>();
        for (Grade grade : subjectGrades) {
            if (grade.getCategory() == null || grade.getScore() == null) continue;
            latest.merge(grade.getCategory(), grade, (left, right) -> {
                if (left.getRecordedAt() == null) return right;
                if (right.getRecordedAt() == null) return left;
                return right.getRecordedAt().isAfter(left.getRecordedAt()) ? right : left;
            });
        }
        return latest;
    }

    private Double weightedAverage(Collection<Grade> gradeValues,
                                   Map<String, ExamCategory> categoryByCode) {
        double weightedTotal = 0;
        double totalWeight = 0;
        for (Grade grade : gradeValues) {
            if (grade.getScore() == null) continue;
            ExamCategory category = categoryByCode.get(grade.getCategory());
            double weight = category == null || category.getWeight() <= 0 ? 1 : category.getWeight();
            weightedTotal += grade.getScore() * weight;
            totalWeight += weight;
        }
        return totalWeight == 0 ? null : round(weightedTotal / totalWeight);
    }

    private AttendanceSummary attendanceSummary(List<AttendanceRecord> records) {
        int present = 0;
        int late = 0;
        int absentExcused = 0;
        int absentUnexcused = 0;
        for (AttendanceRecord record : records) {
            switch (record.getStatus() == null ? "" : record.getStatus().toUpperCase(Locale.ROOT)) {
                case "PRESENT" -> present++;
                case "LATE" -> late++;
                case "ABSENT_EXCUSED" -> absentExcused++;
                case "ABSENT_UNEXCUSED" -> absentUnexcused++;
                default -> { }
            }
        }
        int total = present + late + absentExcused + absentUnexcused;
        Double rate = total == 0 ? null : round((present + late * 0.5) / total * 100);
        return new AttendanceSummary(present, late, absentExcused, absentUnexcused, total, rate);
    }

    private Double average(List<Double> values) {
        List<Double> available = values.stream().filter(java.util.Objects::nonNull).toList();
        return available.isEmpty() ? null : round(available.stream()
                .mapToDouble(Double::doubleValue).average().orElse(0));
    }

    private boolean inSemester(LocalDate date, Semester semester) {
        if (date == null) return false;
        return (semester.getStartDate() == null || !date.isBefore(semester.getStartDate()))
                && (semester.getEndDate() == null || !date.isAfter(semester.getEndDate()));
    }

    private String periodState(Semester semester) {
        LocalDate today = LocalDate.now();
        if (semester.getStartDate() != null && today.isBefore(semester.getStartDate())) return "UPCOMING";
        if ("CLOSED".equalsIgnoreCase(semester.getStatus())
                || (semester.getEndDate() != null && today.isAfter(semester.getEndDate()))) return "CLOSED";
        return "IN_PROGRESS";
    }

    private String periodMessage(String state, Semester semester) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        return switch (state) {
            case "UPCOMING" -> "Học kỳ bắt đầu ngày "
                    + (semester.getStartDate() == null ? "chưa xác định" : semester.getStartDate().format(formatter));
            case "CLOSED" -> "Học kỳ đã kết thúc; cần hoàn tất dữ liệu trước khi chốt";
            default -> "Học kỳ đang diễn ra; điểm và chuyên cần đang được cập nhật";
        };
    }

    private AcademicYear requireYear(String academicYearId) {
        String id = require(academicYearId, "academicYearId");
        return structure.listYears().stream().filter(year -> id.equals(year.getId())).findFirst()
                .orElseThrow(() -> ApiException.notFound("Năm học"));
    }

    private Semester requireSemester(String academicYearId, String semesterId) {
        String id = require(semesterId, "semesterId");
        return structure.listSemesters(academicYearId).stream()
                .filter(semester -> id.equals(semester.getId())).findFirst()
                .orElseThrow(() -> ApiException.badRequest("Học kỳ không thuộc năm học đã chọn"));
    }

    private void assertCanView(CurrentUser actor, SchoolClass schoolClass) {
        if (actor == null) throw ApiException.forbidden("Chưa xác định người xem");
        if (actor.isAdmin()) return;
        if (actor.isTeacher() && actor.id().equals(schoolClass.getHomeroomTeacherId())) return;
        throw ApiException.forbidden("Giáo viên chỉ được xem tổng kết lớp mình chủ nhiệm");
    }

    private String require(String value, String field) {
        if (value == null || value.isBlank()) throw ApiException.badRequest("Thiếu " + field);
        return value.trim();
    }

    private String displayName(String name, String fallback) {
        return name == null || name.isBlank() ? fallback : name;
    }

    private double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
