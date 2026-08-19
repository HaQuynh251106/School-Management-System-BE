package com.sse.app.academic.timetable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sse.app.academic.planning.AcademicPlanningService;
import com.sse.app.academic.planning.AcademicTrainingPlan;
import com.sse.app.academic.planning.AcademicTrainingPlanSubject;
import com.sse.app.academic.planning.EducationPlanningCatalogService;
import com.sse.app.academic.structure.SchoolClass;
import com.sse.app.common.ApiException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class TimetablePlanSourceService {
    private final AcademicPlanningService planning;
    private final EducationPlanningCatalogService catalogs;
    private final ObjectMapper objectMapper;

    public TimetablePlanSourceService(
            AcademicPlanningService planning,
            EducationPlanningCatalogService catalogs,
            ObjectMapper objectMapper) {
        this.planning = planning;
        this.catalogs = catalogs;
        this.objectMapper = objectMapper;
    }

    public List<PlanSnapshot> resolve(
            String academicYearId, String semesterId, Set<String> grades) {
        return grades.stream().sorted().map(grade -> {
            AcademicTrainingPlan plan = planning.publishedPlan(academicYearId, grade);
            List<SubjectSnapshot> subjects = planning
                    .publishedPlanSubjects(plan.getId(), semesterId).stream()
                    .map(this::subjectSnapshot).toList();
            if (subjects.isEmpty()) {
                throw ApiException.conflict("Kế hoạch " + grade
                        + " chưa cấu hình môn học cho học kỳ đã chọn");
            }
            return new PlanSnapshot(plan.getId(), plan.getProgramId(), plan.getVersionNumber(),
                    plan.getGradeLevel(), plan.getStatus(), plan.getPublishedAt(),
                    semesterId, subjects);
        }).toList();
    }

    public String serialize(List<PlanSnapshot> sources) {
        try {
            return objectMapper.writeValueAsString(sources);
        } catch (JsonProcessingException exception) {
            throw ApiException.badRequest("Không thể lưu phiên bản kế hoạch nguồn");
        }
    }

    public List<PlanSnapshot> parse(TimetableSchedule schedule) {
        if (schedule.getSourcePlanSnapshot() == null
                || schedule.getSourcePlanSnapshot().isBlank()) return List.of();
        try {
            return objectMapper.readValue(schedule.getSourcePlanSnapshot(),
                    new TypeReference<List<PlanSnapshot>>() {});
        } catch (JsonProcessingException exception) {
            throw ApiException.conflict(
                    "Snapshot kế hoạch nguồn của thời khóa biểu không hợp lệ");
        }
    }

    public PlanSnapshot sourceForGrade(
            List<PlanSnapshot> sources, String gradeLevel) {
        return sources.stream().filter(item -> gradeLevel.equals(item.gradeLevel()))
                .findFirst().orElseThrow(() -> ApiException.conflict(
                        "Thời khóa biểu chưa lưu kế hoạch nguồn của " + gradeLevel));
    }

    public int weeklyPeriods(List<PlanSnapshot> sources,
                             String gradeLevel, String subjectId) {
        return sourceForGrade(sources, gradeLevel).subjects().stream()
                .filter(item -> subjectId.equals(item.subjectId()))
                .map(SubjectSnapshot::weeklyPeriods).findFirst()
                .orElseThrow(() -> ApiException.conflict("Môn " + subjectId
                        + " không thuộc kế hoạch nguồn của " + gradeLevel));
    }

    public List<SubjectSnapshot> applicableSubjects(
            List<PlanSnapshot> sources, SchoolClass schoolClass) {
        PlanSnapshot source = sourceForGrade(sources, schoolClass.getGradeLevel());
        return source.subjects().stream()
                .filter(item -> item.weeklyPeriods() > 0)
                .filter(item -> !isFixedHomeroomActivity(item.subjectId()))
                .filter(item -> catalogs.subjectAppliesToClass(
                        source.programId(), schoolClass.getGradeLevel(),
                        schoolClass.getId(), item.subjectId()))
                .toList();
    }

    public String summary(List<PlanSnapshot> sources) {
        return sources.stream().sorted(Comparator.comparing(PlanSnapshot::gradeLevel))
                .map(item -> item.gradeLevel() + " v" + item.versionNumber())
                .collect(Collectors.joining(" · "));
    }

    private SubjectSnapshot subjectSnapshot(AcademicTrainingPlanSubject row) {
        return new SubjectSnapshot(row.getId(), row.getSubjectId(),
                row.getWeeklyPeriods(), row.getTotalPeriods());
    }

    public record PlanSnapshot(
            String planId, String programId, int versionNumber,
            String gradeLevel, String status,
            Instant publishedAt, String semesterId,
            List<SubjectSnapshot> subjects) {}

    public record SubjectSnapshot(
            String planSubjectId, String subjectId,
            int weeklyPeriods, int totalPeriods) {}

    private boolean isFixedHomeroomActivity(String subjectId) {
        if (subjectId == null) return false;
        String normalized = subjectId.trim().toLowerCase();
        return normalized.equals("sj-flag") || normalized.equals("sj-homeroom");
    }
}
