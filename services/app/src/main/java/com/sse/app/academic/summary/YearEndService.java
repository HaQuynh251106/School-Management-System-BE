package com.sse.app.academic.summary;

import com.sse.app.academic.grade.ExamCategory;
import com.sse.app.academic.grade.Grade;
import com.sse.app.academic.grade.GradeCalculationService;
import com.sse.app.academic.grade.GradeService;
import com.sse.app.academic.structure.AcademicYear;
import com.sse.app.academic.structure.SchoolClass;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.academic.timetable.TeachingAssignmentService;
import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.identity.UserDto;
import com.sse.app.identity.UserService;
import com.sse.app.notification.NotificationService;
import com.sse.app.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/** E5: tổng kết điểm, nhập hạnh kiểm, xét lên lớp và khóa năm học. */
@Service
public class YearEndService {
    private final StudentYearlySummaryRepository summaries;
    private final StructureService structure;
    private final TeachingAssignmentService teachingAssignments;
    private final GradeService grades;
    private final UserService users;
    private final NotificationService notifications;
    private final GradeCalculationService gradeCalculations;

    public YearEndService(StudentYearlySummaryRepository summaries, StructureService structure,
                          TeachingAssignmentService teachingAssignments, GradeService grades, UserService users,
                          NotificationService notifications, GradeCalculationService gradeCalculations) {
        this.summaries = summaries;
        this.structure = structure;
        this.teachingAssignments = teachingAssignments;
        this.grades = grades;
        this.users = users;
        this.notifications = notifications;
        this.gradeCalculations = gradeCalculations;
    }

    @Transactional
    public List<StudentYearlySummary> preview(String academicYearId) {
        AcademicYear year = structure.getYear(academicYearId);
        List<StudentYearlySummary> existing = summaries.findByAcademicYearIdOrderByStudentName(academicYearId);
        if ("CLOSED".equals(year.getStatus()) && existing.stream().anyMatch(s -> s.getFinalizedAt() != null)) {
            return existing;
        }
        List<String> semesterIds = structure.semesterIdsOfYear(academicYearId);
        if (semesterIds.isEmpty()) throw ApiException.badRequest("Năm học chưa có học kỳ");

        LinkedHashMap<String, UserDto> students = new LinkedHashMap<>();
        for (SchoolClass schoolClass : structure.listClasses(academicYearId, null)) {
            for (UserDto student : users.list("STUDENT", null, schoolClass.getId())) students.put(student.id(), student);
        }
        for (UserDto student : students.values()) evaluateAndSave(academicYearId, semesterIds, student);
        return summaries.findByAcademicYearIdOrderByStudentName(academicYearId);
    }

    @Transactional
    public StudentYearlySummary setConduct(String academicYearId, String studentId, String conduct, CurrentUser actor) {
        if (conduct == null || !Set.of("GOOD", "FAIR", "AVERAGE", "WEAK").contains(conduct)) {
            throw ApiException.badRequest("Hạnh kiểm phải là Tốt, Khá, Trung bình hoặc Yếu");
        }
        preview(academicYearId);
        StudentYearlySummary summary = summaries.findByAcademicYearIdAndStudentId(academicYearId, studentId)
                .orElseThrow(() -> ApiException.notFound("Kết quả tổng kết học sinh"));
        SchoolClass schoolClass = structure.getClass(summary.getClassId());
        if (!actor.isAdmin() && (!actor.isTeacher()
                || !actor.id().equals(schoolClass.getHomeroomTeacherId()))) {
            throw ApiException.forbidden("Chỉ quản trị viên hoặc giáo viên chủ nhiệm của lớp được nhập hạnh kiểm");
        }
        if (summary.getFinalizedAt() != null) throw ApiException.conflict("Năm học đã được chốt");
        summary.setConductGrade(conduct);
        summary.setUpdatedAt(Instant.now());
        return summaries.save(summary);
    }

    @Transactional
    public List<StudentYearlySummary> finalizeYear(String academicYearId, String actorId) {
        AcademicYear year = structure.getYear(academicYearId);
        if ("CLOSED".equals(year.getStatus())) {
            List<StudentYearlySummary> existing = summaries.findByAcademicYearIdOrderByStudentName(academicYearId);
            if (!existing.isEmpty() && existing.stream().allMatch(item -> item.getFinalizedAt() != null)) {
                return existing;
            }
            throw ApiException.conflict("Năm học đã đóng nhưng dữ liệu tổng kết chưa hoàn chỉnh");
        }
        List<StudentYearlySummary> list = preview(academicYearId);
        long incomplete = list.stream().filter(s -> s.getAverageScore() == null
                || s.getMissingRequirements() != null || s.getConductGrade() == null).count();
        if (incomplete > 0) {
            throw ApiException.badRequest("Còn " + incomplete + " học sinh thiếu điểm hoặc hạnh kiểm; chưa thể chốt năm học");
        }
        Instant now = Instant.now();
        for (StudentYearlySummary summary : list) {
            SchoolClass currentClass = structure.getClass(summary.getClassId());
            int gradeLevel = parseGrade(currentClass.getGradeLevel());
            boolean pass = summary.getAverageScore() >= 5.0 && !"WEAK".equals(summary.getConductGrade());
            if (pass && gradeLevel >= 12) {
                summary.setPromotionStatus("GRADUATED");
            } else if (pass) {
                Optional<SchoolClass> nextClass = structure.findNextClass(academicYearId, currentClass)
                        .filter(target -> users.studentCountOfClass(target.getId()) < target.getCapacity());
                if (nextClass.isPresent()) {
                    summary.setPromotionStatus("PROMOTED");
                    summary.setNextClassId(nextClass.get().getId());
                    users.moveStudentToClass(summary.getStudentId(), nextClass.get().getId(), nextClass.get().getCode());
                } else {
                    summary.setPromotionStatus("PROMOTED_PENDING_CLASS");
                }
            } else {
                summary.setPromotionStatus("RETAINED");
                summary.setNextClassId(currentClass.getId());
            }
            summary.setFinalizedAt(now);
            summary.setFinalizedBy(actorId);
            summary.setUpdatedAt(now);
            summaries.save(summary);
            String body = "Kết quả năm học: " + statusLabel(summary.getPromotionStatus())
                    + " · Điểm trung bình " + String.format(Locale.US, "%.2f", summary.getAverageScore());
            notifications.notifyUser(summary.getStudentId(), "YEAR_END", "Kết quả tổng kết năm học", body,
                    "YEARLY_SUMMARY", summary.getId());
            notifications.notifyParentsOfStudent(summary.getStudentId(), "YEAR_END", "Kết quả tổng kết năm học", body,
                    "YEARLY_SUMMARY", summary.getId());
        }
        structure.closeYear(academicYearId);
        return summaries.findByAcademicYearIdOrderByStudentName(academicYearId);
    }

    public List<StudentYearlySummary> summaries(String academicYearId) {
        return summaries.findByAcademicYearIdOrderByStudentName(academicYearId);
    }

    private void evaluateAndSave(String yearId, List<String> semesterIds, UserDto student) {
        StudentYearlySummary summary = summaries.findByAcademicYearIdAndStudentId(yearId, student.id())
                .orElseGet(() -> StudentYearlySummary.builder().id(Ids.gen("sys")).academicYearId(yearId)
                        .studentId(student.id()).build());
        summary.setStudentName(student.fullName());
        summary.setClassId(student.classId());
        summary.setUpdatedAt(Instant.now());

        Evaluation evaluation = evaluateGrades(student.id(), student.classId(), semesterIds);
        summary.setAverageScore(evaluation.average());
        summary.setMissingRequirements(evaluation.missing());
        summary.setPromotionStatus(evaluation.missing() == null ? "READY" : "INCOMPLETE");
        summaries.save(summary);
    }

    private Evaluation evaluateGrades(String studentId, String classId, List<String> semesterIds) {
        List<ExamCategory> categories = grades.listCategories();
        List<Grade> actual = grades.list(studentId, null, null, null, null).stream()
                .filter(g -> semesterIds.contains(g.getSemesterId())).toList();
        Map<String, List<Grade>> gradeMap = actual.stream().collect(Collectors.groupingBy(
                g -> g.getSubjectId() + "|" + g.getSemesterId()));
        LinkedHashSet<String> expected = new LinkedHashSet<>();
        for (String semesterId : semesterIds) {
            teachingAssignments.assignmentsOfClass(classId, semesterId)
                    .forEach(item -> expected.add(item.getSubjectId() + "|" + semesterId));
        }
        if (expected.isEmpty()) return new Evaluation(null, "Chưa có thời khóa biểu cho năm học");

        List<String> missing = new ArrayList<>();
        Map<String, List<Double>> subjectSemesterAverages = new LinkedHashMap<>();
        for (String key : expected) {
            List<Grade> entries = gradeMap.getOrDefault(key, List.of());
            String subjectId = key.substring(0, key.indexOf('|'));
            List<String> subjectMissing = new ArrayList<>();
            for (ExamCategory category : categories) {
                Set<Integer> indexes = entries.stream().filter(g -> category.getCode().equals(g.getCategory()))
                        .filter(g -> g.getScore() != null)
                        .map(g -> g.getAssessmentIndex() == null ? 1 : g.getAssessmentIndex())
                        .collect(Collectors.toSet());
                boolean complete = java.util.stream.IntStream.rangeClosed(1, Math.max(1, category.getRequiredCount()))
                        .allMatch(indexes::contains);
                if (!complete) {
                    String subjectName = entries.isEmpty() ? structure.requireSubjectName(subjectId) : entries.get(0).getSubjectName();
                    subjectMissing.add(subjectName + " thiếu " + category.getName());
                }
            }
            missing.addAll(subjectMissing);
            if (subjectMissing.isEmpty()) {
                Double average = gradeCalculations.subjectAverage(entries, categories);
                if (average != null) subjectSemesterAverages.computeIfAbsent(subjectId, ignored -> new ArrayList<>()).add(average);
            }
        }
        if (!missing.isEmpty()) return new Evaluation(null, String.join("; ", missing.stream().distinct().limit(12).toList()));

        double total = 0;
        double coefficientTotal = 0;
        for (var entry : subjectSemesterAverages.entrySet()) {
            double annual = entry.getValue().stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double coefficient = structure.subjectCoefficient(entry.getKey());
            total += annual * coefficient;
            coefficientTotal += coefficient;
        }
        if (coefficientTotal == 0) return new Evaluation(null, "Chưa có đủ điểm để tổng kết");
        return new Evaluation(Math.round(total / coefficientTotal * 100.0) / 100.0, null);
    }

    private int parseGrade(String value) {
        try { return Integer.parseInt(value == null ? "0" : value.replaceAll("\\D", "")); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private String statusLabel(String value) {
        return switch (value) {
            case "PROMOTED" -> "Được lên lớp";
            case "PROMOTED_PENDING_CLASS" -> "Đủ điều kiện lên lớp, chờ xếp lớp";
            case "GRADUATED" -> "Tốt nghiệp";
            default -> "Lưu ban";
        };
    }

    private record Evaluation(Double average, String missing) {}
}
