package com.sse.app.academic.grade;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.sse.app.academic.grade.GradeDtos.GradeSubjectSummary;

/** Nguồn công thức điểm duy nhất cho dashboard, báo cáo, học bạ và tổng kết năm. */
@Service
@RequiredArgsConstructor
public class GradeCalculationService {
    private final GradeRepository grades;
    private final ExamCategoryRepository categories;

    public Double subjectAverage(List<Grade> values) {
        return subjectAverage(values, categories.findAll());
    }

    /** Mỗi đầu điểm mang hệ số của loại điểm và phải đủ mọi vị trí bắt buộc. */
    public Double subjectAverage(List<Grade> values, List<ExamCategory> configuredCategories) {
        if (configuredCategories.isEmpty()) return null;
        double weightedTotal = 0;
        double totalWeight = 0;
        for (ExamCategory category : configuredCategories) {
            Map<Integer, Grade> byIndex = values.stream()
                    .filter(grade -> category.getCode().equals(grade.getCategory()))
                    .filter(grade -> grade.getScore() != null && Double.isFinite(grade.getScore()))
                    .collect(Collectors.toMap(
                            grade -> grade.getAssessmentIndex() == null ? 1 : grade.getAssessmentIndex(),
                            Function.identity(), (first, ignored) -> first));
            int required = Math.max(1, category.getRequiredCount());
            for (int index = 1; index <= required; index++) {
                Grade grade = byIndex.get(index);
                if (grade == null) return null;
                double weight = category.getWeight() > 0 ? category.getWeight() : 1;
                weightedTotal += grade.getScore() * weight;
                totalWeight += weight;
            }
        }
        return totalWeight == 0 ? null : roundOneDecimal(weightedTotal / totalWeight);
    }

    public Double overallAverage(Collection<String> studentIds) {
        List<Double> averages = completeSubjectSemesterAverages(studentIds).stream()
                .map(SubjectSemesterAverage::average).toList();
        return averages.isEmpty() ? null
                : roundOneDecimal(averages.stream().mapToDouble(Double::doubleValue).average().orElse(0));
    }

    public Map<String, Double> subjectAverages(Collection<String> studentIds) {
        Map<String, List<Double>> grouped = new LinkedHashMap<>();
        for (SubjectSemesterAverage row : completeSubjectSemesterAverages(studentIds)) {
            grouped.computeIfAbsent(row.subjectName(), ignored -> new ArrayList<>()).add(row.average());
        }
        Map<String, Double> result = new LinkedHashMap<>();
        grouped.forEach((subject, values) -> result.put(subject,
                roundOneDecimal(values.stream().mapToDouble(Double::doubleValue).average().orElse(0))));
        return result;
    }

    /**
     * Canonical subject/semester result consumed by Web and Mobile. Averages
     * are intentionally null until every configured assessment slot exists.
     */
    public List<GradeSubjectSummary> subjectSummaries(String studentId, String semesterId) {
        List<ExamCategory> configured = categories.findAll();
        List<Grade> rows = semesterId == null || semesterId.isBlank()
                ? grades.findByStudentId(studentId)
                : grades.findByStudentIdAndSemesterId(studentId, semesterId);
        Map<String, List<Grade>> grouped = rows.stream()
                .filter(grade -> grade.getSubjectId() != null && grade.getSemesterId() != null)
                .collect(Collectors.groupingBy(
                        grade -> grade.getSubjectId() + "|" + grade.getSemesterId(),
                        LinkedHashMap::new,
                        Collectors.toList()));
        return grouped.values().stream().map(values -> {
            Grade first = values.get(0);
            List<String> missing = missingAssessmentKeys(values, configured);
            Double average = missing.isEmpty() ? subjectAverage(values, configured) : null;
            return new GradeSubjectSummary(
                    studentId,
                    first.getSubjectId(),
                    first.getSubjectName(),
                    first.getSemesterId(),
                    average,
                    missing.isEmpty(),
                    missing);
        }).toList();
    }

    private List<String> missingAssessmentKeys(List<Grade> values, List<ExamCategory> configured) {
        Set<String> present = values.stream()
                .filter(grade -> grade.getScore() != null && Double.isFinite(grade.getScore()))
                .map(grade -> grade.getCategory() + "#"
                        + (grade.getAssessmentIndex() == null ? 1 : grade.getAssessmentIndex()))
                .collect(Collectors.toSet());
        List<String> missing = new ArrayList<>();
        for (ExamCategory category : configured) {
            for (int index = 1; index <= Math.max(1, category.getRequiredCount()); index++) {
                String key = category.getCode() + "#" + index;
                if (!present.contains(key)) missing.add(key);
            }
        }
        return missing;
    }

    private List<SubjectSemesterAverage> completeSubjectSemesterAverages(Collection<String> studentIds) {
        if (studentIds == null || studentIds.isEmpty()) return List.of();
        List<ExamCategory> configured = categories.findAll();
        if (configured.isEmpty()) return List.of();
        List<Grade> rows = grades.findByStudentIdIn(studentIds).stream()
                .filter(grade -> grade.getSemesterId() != null && grade.getSubjectId() != null)
                .toList();
        Map<String, List<Grade>> grouped = rows.stream().collect(Collectors.groupingBy(
                grade -> grade.getStudentId() + "|" + grade.getSubjectId() + "|" + grade.getSemesterId(),
                LinkedHashMap::new, Collectors.toList()));
        List<SubjectSemesterAverage> result = new ArrayList<>();
        grouped.values().forEach(values -> {
            Double average = subjectAverage(values, configured);
            if (average != null) {
                String subjectName = values.stream().map(Grade::getSubjectName).filter(Objects::nonNull)
                        .findFirst().orElse(values.get(0).getSubjectId());
                result.add(new SubjectSemesterAverage(subjectName, average));
            }
        });
        return result;
    }

    private double roundOneDecimal(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private record SubjectSemesterAverage(String subjectName, Double average) {}
}
