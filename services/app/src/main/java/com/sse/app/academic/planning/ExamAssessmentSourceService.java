package com.sse.app.academic.planning;

import com.sse.app.academic.structure.StructureService;
import com.sse.app.academic.structure.Subject;
import com.sse.app.common.ApiException;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ExamAssessmentSourceService {
    private static final Set<String> PUBLISHED_STATUSES = Set.of("PUBLISHED", "LOCKED");
    private static final Set<String> SUPPORTED_TYPES = Set.of("MIDTERM", "FINAL", "MAKEUP");

    private final AcademicTrainingPlanRepository plans;
    private final AcademicAssessmentPlanRepository assessments;
    private final StructureService structure;

    public ExamAssessmentSourceService(
            AcademicTrainingPlanRepository plans,
            AcademicAssessmentPlanRepository assessments,
            StructureService structure) {
        this.plans = plans;
        this.assessments = assessments;
        this.structure = structure;
    }

    public List<ExamAssessmentSource> available(
            String academicYearId, String semesterId,
            String examType, List<String> gradeLevels) {
        String type = normalizeType(examType);
        Set<String> grades = gradeLevels.stream().map(this::normalizeGrade)
                .collect(Collectors.toSet());
        Map<String, Subject> subjects = structure.listSubjects().stream()
                .collect(Collectors.toMap(Subject::getId, Function.identity()));
        List<ExamAssessmentSource> result = new ArrayList<>();
        for (String grade : grades) {
            AcademicTrainingPlan plan = plans
                    .findByAcademicYearIdAndGradeLevelOrderByVersionNumberDesc(
                            academicYearId, grade)
                    .stream()
                    .filter(item -> PUBLISHED_STATUSES.contains(item.getStatus()))
                    .findFirst()
                    .orElse(null);
            if (plan == null) continue;
            for (AcademicAssessmentPlan row : assessments
                    .findByPlanIdOrderBySemesterIdAscWeekNumberAscSubjectIdAsc(plan.getId())) {
                if (!semesterId.equals(row.getSemesterId())
                        || !type.equals(row.getAssessmentType())
                        || row.getClassId() != null) continue;
                Subject subject = subjects.get(row.getSubjectId());
                if (subject == null || !subject.isActive()) continue;
                PlannedWeek plannedWeek = plannedWeek(semesterId, row.getWeekNumber());
                Instant sourceUpdatedAt = latestInstant(plan.getUpdatedAt(), row.getUpdatedAt());
                result.add(new ExamAssessmentSource(
                        row.getId(), plan.getId(), plan.getVersionNumber(),
                        plan.getName(), plan.getStatus(), sourceUpdatedAt,
                        academicYearId, semesterId, grade, row.getSubjectId(),
                        subject.getCode(), subject.getName(), row.getAssessmentType(),
                        row.getName(), row.getWeekNumber(), row.getDurationMinutes(),
                        row.getAssessmentForm(), row.getNotes(),
                        plannedWeek.startDate(), plannedWeek.endDate()));
            }
        }
        return result.stream()
                .sorted(Comparator.comparing(ExamAssessmentSource::gradeLevel)
                        .thenComparing(ExamAssessmentSource::subjectName))
                .toList();
    }

    public SourceReadiness readiness(
            String academicYearId, String semesterId,
            String examType, List<String> gradeLevels) {
        List<String> normalizedGrades = gradeLevels.stream()
                .map(this::normalizeGrade).distinct().sorted().toList();
        List<ExamAssessmentSource> sources = available(
                academicYearId, semesterId, examType, normalizedGrades);
        List<String> issues = new ArrayList<>();
        for (String grade : normalizedGrades) {
            List<ExamAssessmentSource> rows = sources.stream()
                    .filter(source -> grade.equals(source.gradeLevel())).toList();
            if (rows.isEmpty()) {
                issues.add("Khối " + grade.substring(1)
                        + " chưa có kế hoạch " + typeLabel(examType)
                        + " trong kế hoạch giáo dục đã công bố");
            }
            Map<String, Long> duplicates = rows.stream().collect(Collectors.groupingBy(
                    ExamAssessmentSource::subjectId, LinkedHashMap::new, Collectors.counting()));
            duplicates.forEach((subjectId, count) -> {
                if (count > 1) {
                    String subjectName = rows.stream()
                            .filter(row -> subjectId.equals(row.subjectId()))
                            .map(ExamAssessmentSource::subjectName).findFirst().orElse(subjectId);
                    issues.add("Khối " + grade.substring(1) + " có " + count
                            + " kế hoạch trùng cho môn " + subjectName);
                }
            });
        }
        ScheduleSuggestion suggestion = suggestSchedule(semesterId, sources);
        return new SourceReadiness(
                issues.isEmpty(), sources.size(), suggestion.subjectCount(),
                suggestion.requiredDays(), suggestion.startDate(), suggestion.endDate(),
                suggestion.examDates(), sources, issues);
    }

    private ScheduleSuggestion suggestSchedule(
            String semesterId, List<ExamAssessmentSource> sources) {
        if (sources.isEmpty()) {
            return new ScheduleSuggestion(0, 0, null, null, List.of());
        }
        var semester = structure.getSemester(semesterId);
        int subjectCount = (int) sources.stream()
                .map(source -> source.subjectId() + "|" + source.weekNumber()).distinct().count();
        int requiredDays = Math.max(1, (int) Math.ceil(subjectCount / 2.0));
        LocalDate plannedStart = sources.stream().map(ExamAssessmentSource::plannedStartDate)
                .min(LocalDate::compareTo).orElse(semester.getStartDate());
        LocalDate plannedEnd = sources.stream().map(ExamAssessmentSource::plannedEndDate)
                .max(LocalDate::compareTo).orElse(semester.getEndDate());
        List<LocalDate> dates = plannedStart.datesUntil(plannedEnd.plusDays(1))
                .filter(date -> isUsableExamDate(semester.getAcademicYearId(), date))
                .toList();
        return new ScheduleSuggestion(subjectCount, requiredDays,
                dates.isEmpty() ? null : dates.get(0),
                dates.isEmpty() ? null : dates.get(dates.size() - 1),
                List.copyOf(dates));
    }

    public PlannedWeek plannedWeek(String semesterId, int weekNumber) {
        var semester = structure.getSemester(semesterId);
        int normalizedWeek = Math.max(1, weekNumber);
        LocalDate start = semester.getStartDate().plusWeeks(normalizedWeek - 1L);
        if (start.isAfter(semester.getEndDate())) start = semester.getEndDate();
        LocalDate end = start.plusDays(6);
        if (end.isAfter(semester.getEndDate())) end = semester.getEndDate();
        boolean hasUsableDay = start.datesUntil(end.plusDays(1))
                .anyMatch(date -> isUsableExamDate(semester.getAcademicYearId(), date));
        if (!hasUsableDay) {
            LocalDate cursor = end.plusDays(1);
            while (!cursor.isAfter(semester.getEndDate())
                    && !isUsableExamDate(semester.getAcademicYearId(), cursor)) {
                cursor = cursor.plusDays(1);
            }
            if (!cursor.isAfter(semester.getEndDate())) {
                start = cursor;
                end = cursor.plusDays(6);
                if (end.isAfter(semester.getEndDate())) end = semester.getEndDate();
            }
        }
        return new PlannedWeek(start, end);
    }

    private boolean isUsableExamDate(String academicYearId, LocalDate date) {
        if (date.getDayOfWeek() == DayOfWeek.SUNDAY) return false;
        return structure.listHolidays(academicYearId).stream().noneMatch(holiday -> {
            LocalDate end = holiday.getEndDate() == null ? holiday.getDate() : holiday.getEndDate();
            return !date.isBefore(holiday.getDate()) && !date.isAfter(end);
        });
    }

    public ExamAssessmentSource requireSource(
            String assessmentId, String academicYearId, String semesterId,
            String examType, List<String> gradeLevels) {
        return available(academicYearId, semesterId, examType, gradeLevels).stream()
                .filter(row -> row.assessmentPlanId().equals(assessmentId))
                .findFirst()
                .orElseThrow(() -> ApiException.badRequest(
                        "Kế hoạch kiểm tra nguồn không tồn tại, chưa được công bố hoặc không thuộc phạm vi đợt thi"));
    }

    public ExamAssessmentSource getSource(String assessmentId) {
        AcademicAssessmentPlan row = assessments.findById(assessmentId)
                .orElseThrow(() -> ApiException.notFound("Kế hoạch kiểm tra nguồn"));
        AcademicTrainingPlan plan = plans.findById(row.getPlanId())
                .orElseThrow(() -> ApiException.notFound("Kế hoạch giáo dục nguồn"));
        Subject subject = structure.getSubject(row.getSubjectId());
        return new ExamAssessmentSource(
                row.getId(), plan.getId(), plan.getVersionNumber(),
                plan.getName(), plan.getStatus(), latestInstant(plan.getUpdatedAt(), row.getUpdatedAt()),
                plan.getAcademicYearId(), row.getSemesterId(), plan.getGradeLevel(),
                row.getSubjectId(), subject.getCode(), subject.getName(),
                row.getAssessmentType(), row.getName(), row.getWeekNumber(),
                row.getDurationMinutes(), row.getAssessmentForm(), row.getNotes(),
                plannedWeek(row.getSemesterId(), row.getWeekNumber()).startDate(),
                plannedWeek(row.getSemesterId(), row.getWeekNumber()).endDate());
    }

    private Instant latestInstant(Instant left, Instant right) {
        if (left == null) return right;
        if (right == null) return left;
        return left.isAfter(right) ? left : right;
    }

    private String normalizeType(String value) {
        String type = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_TYPES.contains(type)) {
            throw ApiException.badRequest(
                    "GĐ5 chỉ lập lịch từ kế hoạch giữa kỳ, cuối kỳ hoặc thi lại đã khai báo ở GĐ3");
        }
        return type;
    }

    private String normalizeGrade(String value) {
        String grade = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (grade.matches("10|11|12")) grade = "K" + grade;
        if (!Set.of("K10", "K11", "K12").contains(grade)) {
            throw ApiException.badRequest("Khối không hợp lệ");
        }
        return grade;
    }

    private String typeLabel(String value) {
        return switch (normalizeType(value)) {
            case "MIDTERM" -> "kiểm tra giữa kỳ";
            case "FINAL" -> "kiểm tra cuối kỳ";
            default -> "thi lại";
        };
    }

    public record ExamAssessmentSource(
            String assessmentPlanId, String trainingPlanId, int planVersion,
            String planName, String planStatus, Instant sourceUpdatedAt,
            String academicYearId, String semesterId, String gradeLevel,
            String subjectId, String subjectCode, String subjectName,
            String assessmentType, String assessmentName, int weekNumber,
            int durationMinutes, String assessmentForm, String notes,
            LocalDate plannedStartDate, LocalDate plannedEndDate) {}

    public record SourceReadiness(
            boolean ready, int sourceCount, int subjectCount, int requiredDays,
            LocalDate suggestedStartDate, LocalDate suggestedEndDate,
            List<LocalDate> suggestedExamDates,
            List<ExamAssessmentSource> sources, List<String> issues) {}

    private record ScheduleSuggestion(
            int subjectCount, int requiredDays,
            LocalDate startDate, LocalDate endDate,
            List<LocalDate> examDates) {}

    public record PlannedWeek(LocalDate startDate, LocalDate endDate) {}
}
