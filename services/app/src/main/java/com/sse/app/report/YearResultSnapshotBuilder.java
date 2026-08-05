package com.sse.app.report;

import com.sse.app.academic.attendance.AttendanceRecord;
import com.sse.app.academic.attendance.AttendanceService;
import com.sse.app.academic.grade.ExamCategory;
import com.sse.app.academic.grade.Grade;
import com.sse.app.academic.grade.GradeService;
import com.sse.app.academic.structure.Semester;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.report.YearReviewDtos.AnnualSubjectResult;
import com.sse.app.report.YearReviewDtos.SemesterResult;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class YearResultSnapshotBuilder {
    private final StructureService structure;
    private final GradeService grades;
    private final AttendanceService attendance;

    public YearResultSnapshotBuilder(StructureService structure,
                                     GradeService grades,
                                     AttendanceService attendance) {
        this.structure = structure;
        this.grades = grades;
        this.attendance = attendance;
    }

    public Snapshot build(StudentYearlySummary summary, double subjectMinimumScore) {
        List<Semester> semesters = structure.listSemesters(summary.getAcademicYearId()).stream()
                .sorted(Comparator.comparingInt(Semester::getSequence)).toList();
        Map<String, ExamCategory> categories = grades.listCategories().stream()
                .collect(Collectors.toMap(ExamCategory::getCode, Function.identity(),
                        (left, right) -> left, LinkedHashMap::new));
        List<AttendanceRecord> attendanceRows =
                attendance.list(summary.getStudentId(), null, null, null);
        Map<Integer, Map<String, SubjectAverage>> bySemester = new LinkedHashMap<>();
        List<SemesterResult> semesterResults = new ArrayList<>();

        for (Semester semester : semesters) {
            List<Grade> semesterGrades = grades.list(
                    summary.getStudentId(), null, semester.getId(), null, null);
            Map<String, List<Grade>> bySubject = semesterGrades.stream()
                    .filter(grade -> grade.getSubjectId() != null)
                    .collect(Collectors.groupingBy(Grade::getSubjectId,
                            LinkedHashMap::new, Collectors.toList()));
            Map<String, SubjectAverage> subjectAverages = new LinkedHashMap<>();
            for (Map.Entry<String, List<Grade>> entry : bySubject.entrySet()) {
                Grade first = entry.getValue().get(0);
                subjectAverages.put(entry.getKey(), new SubjectAverage(
                        entry.getKey(), first.getSubjectName(),
                        weightedAverage(entry.getValue(), categories)));
            }
            bySemester.put(semester.getSequence(), subjectAverages);
            List<AttendanceRecord> semesterAttendance = attendanceRows.stream()
                    .filter(row -> inSemester(row.getDate(), semester)).toList();
            semesterResults.add(new SemesterResult(
                    semester.getId(),
                    display(semester.getName(), semester.getCode()),
                    "CLOSED",
                    average(subjectAverages.values().stream()
                            .map(SubjectAverage::average).toList()),
                    attendanceRate(semesterAttendance),
                    !subjectAverages.isEmpty() && !semesterAttendance.isEmpty(),
                    List.of()));
        }

        Set<String> subjectIds = new LinkedHashSet<>();
        bySemester.values().forEach(rows -> subjectIds.addAll(rows.keySet()));
        List<AnnualSubjectResult> annualSubjects = subjectIds.stream().map(subjectId -> {
            SubjectAverage first = bySemester.getOrDefault(1, Map.of()).get(subjectId);
            SubjectAverage second = bySemester.getOrDefault(2, Map.of()).get(subjectId);
            Double yearly = weightedYearAverage(
                    first == null ? null : first.average(),
                    second == null ? null : second.average());
            String name = second != null ? second.name()
                    : first == null ? subjectId : first.name();
            return new AnnualSubjectResult(
                    subjectId, name,
                    first == null ? null : first.average(),
                    second == null ? null : second.average(),
                    yearly, yearly != null && yearly < subjectMinimumScore);
        }).sorted(Comparator.comparing(AnnualSubjectResult::subjectName,
                String.CASE_INSENSITIVE_ORDER)).toList();
        return new Snapshot(semesterResults, annualSubjects);
    }

    private Double weightedAverage(List<Grade> rows,
                                   Map<String, ExamCategory> categories) {
        Map<String, Grade> latest = new LinkedHashMap<>();
        for (Grade grade : rows) {
            if (grade.getCategory() == null || grade.getScore() == null) continue;
            latest.merge(grade.getCategory(), grade, (left, right) -> {
                if (left.getRecordedAt() == null) return right;
                if (right.getRecordedAt() == null) return left;
                return right.getRecordedAt().isAfter(left.getRecordedAt()) ? right : left;
            });
        }
        double total = 0;
        double weights = 0;
        for (Grade grade : latest.values()) {
            ExamCategory category = categories.get(grade.getCategory());
            double weight = category == null ? 1 : category.getWeight();
            total += grade.getScore() * weight;
            weights += weight;
        }
        return weights == 0 ? null : round(total / weights);
    }

    private Double weightedYearAverage(Double first, Double second) {
        if (first == null || second == null) return null;
        return round((first + second * 2) / 3);
    }

    private Double average(List<Double> values) {
        List<Double> present = values.stream().filter(value -> value != null).toList();
        return present.isEmpty() ? null
                : round(present.stream().mapToDouble(Double::doubleValue).average().orElse(0));
    }

    private Double attendanceRate(List<AttendanceRecord> rows) {
        if (rows.isEmpty()) return null;
        long attended = rows.stream().filter(row ->
                "PRESENT".equals(row.getStatus()) || "LATE".equals(row.getStatus())).count();
        return round(attended * 100.0 / rows.size());
    }

    private boolean inSemester(LocalDate date, Semester semester) {
        if (date == null) return false;
        return (semester.getStartDate() == null || !date.isBefore(semester.getStartDate()))
                && (semester.getEndDate() == null || !date.isAfter(semester.getEndDate()));
    }

    private double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private String display(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private record SubjectAverage(String id, String name, Double average) {}

    public record Snapshot(
            List<SemesterResult> semesters,
            List<AnnualSubjectResult> subjects) {}
}
