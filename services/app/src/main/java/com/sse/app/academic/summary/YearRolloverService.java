package com.sse.app.academic.summary;

import com.sse.app.academic.structure.AcademicYear;
import com.sse.app.academic.structure.SchoolClass;
import com.sse.app.academic.structure.Semester;
import com.sse.app.academic.structure.StructureDtos.CreateAcademicYearRequest;
import com.sse.app.academic.structure.StructureDtos.CreateClassRequest;
import com.sse.app.academic.structure.StructureDtos.CreateSemesterRequest;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.common.ApiException;
import com.sse.app.academic.summary.YearEndDtos.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Quy trình nguyên tử: chuẩn bị cơ cấu mới -> tổng kết -> xếp lớp -> kích hoạt năm mới. */
@Service
public class YearRolloverService {
    private final YearEndService yearEnd;
    private final StructureService structure;

    public YearRolloverService(YearEndService yearEnd, StructureService structure) {
        this.yearEnd = yearEnd;
        this.structure = structure;
    }

    @Transactional
    public RolloverPreview preview(String academicYearId) {
        AcademicYear year = structure.getYear(academicYearId);
        List<Semester> semesters = structure.listSemesters(academicYearId);
        List<SchoolClass> classes = structure.listClasses(academicYearId, null);
        List<StudentYearlySummary> summaries = yearEnd.preview(academicYearId);

        int ready = 0;
        int incomplete = 0;
        int promoted = 0;
        int retained = 0;
        int graduated = 0;
        for (StudentYearlySummary summary : summaries) {
            boolean complete = summary.getSemesterOneAverage() != null && summary.getSemesterTwoAverage() != null
                    && summary.getAverageScore() != null && summary.getMissingRequirements() == null
                    && summary.getConductGrade() != null;
            if (!complete) {
                incomplete++;
                continue;
            }
            ready++;
            SchoolClass currentClass = structure.getClass(summary.getClassId());
            int grade = parseGrade(currentClass.getGradeLevel());
            boolean pass = summary.getAverageScore() >= 5.0 && !"WEAK".equals(summary.getConductGrade());
            if (!pass) retained++;
            else if (grade >= 12) graduated++;
            else promoted++;
        }

        List<String> blockers = new ArrayList<>();
        if (!"ACTIVE".equals(year.getStatus())) blockers.add("Chỉ năm học đang hoạt động mới có thể chuyển năm");
        if (findSemester(semesters, 1).isEmpty()) blockers.add("Năm học chưa cấu hình học kỳ I");
        if (findSemester(semesters, 2).isEmpty()) blockers.add("Năm học chưa cấu hình học kỳ II");
        if (classes.isEmpty()) blockers.add("Năm học chưa có lớp");
        if (summaries.isEmpty()) blockers.add("Năm học chưa có học sinh để tổng kết");
        if (incomplete > 0) blockers.add("Còn " + incomplete + " học sinh thiếu điểm hoặc hạnh kiểm");
        int finalizedReportCards = yearEnd.lockedOrPublishedReportCardCount(academicYearId);
        if (!summaries.isEmpty() && finalizedReportCards < summaries.size()) {
            blockers.add("Cần khóa hoặc phát hành đủ " + summaries.size()
                    + " học bạ; hiện đã hoàn thành " + finalizedReportCards);
        }

        return new RolloverPreview(year.getId(), year.getCode(), year.getStatus(), semesters.size(), classes.size(),
                summaries.size(), ready, incomplete, promoted, retained, graduated,
                buildClassPlan(classes, true), List.copyOf(blockers));
    }

    @Transactional
    public RolloverResult rollover(String academicYearId, RolloverRequest request, String actorId) {
        RolloverPreview readiness = preview(academicYearId);
        if (!readiness.blockers().isEmpty()) {
            throw ApiException.badRequest(String.join("; ", readiness.blockers()));
        }
        AcademicYear currentYear = structure.getYear(academicYearId);
        if (!request.startDate().isAfter(currentYear.getEndDate())) {
            throw ApiException.badRequest("Năm học mới phải bắt đầu sau ngày kết thúc năm học hiện tại");
        }

        AcademicYear nextYear = findPreparedNextYear(request).orElseGet(() -> structure.createYear(
                new CreateAcademicYearRequest(null, request.nextYearCode(), request.nextYearName(),
                        request.startDate(), request.endDate(), "PLANNED"), false));
        List<Semester> clonedSemesters = ensureSemesters(currentYear, nextYear);
        boolean createIntake = request.createIntakeClasses() == null || request.createIntakeClasses();
        if (!createIntake && readiness.expectedRetained() > 0) {
            throw ApiException.badRequest("Có học sinh dự kiến lưu ban; cần tạo lớp đầu cấp/cùng khối để hệ thống xếp lớp an toàn");
        }
        List<RolloverClassPlan> classPlan = buildClassPlan(structure.listClasses(academicYearId, null), createIntake);
        Set<String> existingClassCodes = new HashSet<>();
        structure.listClasses(nextYear.getId(), null).forEach(item ->
                existingClassCodes.add(item.getCode().trim().toUpperCase(Locale.ROOT)));
        int createdClassCount = 0;
        for (RolloverClassPlan plan : classPlan) {
            if (existingClassCodes.add(plan.targetClassCode().trim().toUpperCase(Locale.ROOT))) {
                structure.createClass(new CreateClassRequest(null, plan.targetClassCode(),
                        "Lớp " + plan.targetClassCode(), plan.targetGradeLevel(), nextYear.getId(), null,
                        plan.studyShift(), plan.capacity(), null));
                createdClassCount++;
            }
        }

        List<StudentYearlySummary> finalized = yearEnd.finalizeYear(academicYearId, actorId);
        boolean activate = request.activateNextYear() == null || request.activateNextYear();
        if (activate) {
            structure.changeYearStatus(nextYear.getId(), "ACTIVE");
            clonedSemesters.stream().min(Comparator.comparingInt(Semester::getSequence))
                    .ifPresent(semester -> structure.changeSemesterStatus(semester.getId(), "ACTIVE"));
        }

        int promoted = (int) finalized.stream().filter(item -> "PROMOTED".equals(item.getPromotionStatus())).count();
        int retained = (int) finalized.stream().filter(item -> "RETAINED".equals(item.getPromotionStatus())).count();
        int graduated = (int) finalized.stream().filter(item -> "GRADUATED".equals(item.getPromotionStatus())).count();
        return new RolloverResult(currentYear.getId(), nextYear.getId(), nextYear.getCode(),
                clonedSemesters.size(), createdClassCount, promoted, retained, graduated, activate, Instant.now());
    }

    private Optional<AcademicYear> findPreparedNextYear(RolloverRequest request) {
        Optional<AcademicYear> existing = structure.listYears().stream()
                .filter(item -> item.getCode().equalsIgnoreCase(request.nextYearCode().trim()))
                .findFirst();
        if (existing.isEmpty()) return Optional.empty();
        AcademicYear year = existing.get();
        if (!"PLANNED".equals(year.getStatus())) {
            throw ApiException.conflict("Năm học " + year.getCode() + " đã tồn tại nhưng không còn ở trạng thái dự kiến");
        }
        if (!Objects.equals(year.getStartDate(), request.startDate())
                || !Objects.equals(year.getEndDate(), request.endDate())) {
            throw ApiException.badRequest("Năm học " + year.getCode()
                    + " đã được chuẩn bị với thời gian " + year.getStartDate() + " - " + year.getEndDate()
                    + "; hãy dùng đúng thời gian đã cấu hình");
        }
        return existing;
    }

    private List<Semester> ensureSemesters(AcademicYear currentYear, AcademicYear nextYear) {
        List<Semester> existing = structure.listSemesters(nextYear.getId());
        if (!existing.isEmpty()) {
            if (findSemester(existing, 1).isEmpty() || findSemester(existing, 2).isEmpty()) {
                throw ApiException.badRequest("Năm học mới đã có cấu hình học kỳ chưa đầy đủ; cần đủ học kỳ I và II");
            }
            return existing;
        }
        return cloneSemesters(currentYear, nextYear);
    }

    private List<Semester> cloneSemesters(AcademicYear currentYear, AcademicYear nextYear) {
        List<Semester> result = new ArrayList<>();
        for (Semester current : structure.listSemesters(currentYear.getId())) {
            long startOffset = ChronoUnit.DAYS.between(currentYear.getStartDate(), current.getStartDate());
            long endOffset = ChronoUnit.DAYS.between(currentYear.getStartDate(), current.getEndDate());
            LocalDate start = min(nextYear.getStartDate().plusDays(Math.max(0, startOffset)), nextYear.getEndDate());
            LocalDate end = min(nextYear.getStartDate().plusDays(Math.max(startOffset, endOffset)), nextYear.getEndDate());
            result.add(structure.createSemester(new CreateSemesterRequest(null, nextYear.getId(), current.getCode(),
                    current.getName(), current.getSequence(), start, end, "PLANNED")));
        }
        return result;
    }

    private List<RolloverClassPlan> buildClassPlan(List<SchoolClass> classes, boolean createIntake) {
        int entryGrade = classes.stream().mapToInt(item -> parseGrade(item.getGradeLevel()))
                .filter(value -> value > 0).min().orElse(10);
        LinkedHashMap<String, RolloverClassPlan> plan = new LinkedHashMap<>();
        for (SchoolClass source : classes) {
            int grade = parseGrade(source.getGradeLevel());
            if (grade > 0 && grade < 12) {
                addClassPlan(plan, source, grade + 1, "PROMOTION");
            }
            if (createIntake && grade == entryGrade) {
                addClassPlan(plan, source, grade, "NEW_INTAKE");
            }
        }
        return List.copyOf(plan.values());
    }

    private void addClassPlan(Map<String, RolloverClassPlan> plan, SchoolClass source, int targetGrade, String type) {
        String targetCode = replaceGradePrefix(source.getCode(), targetGrade);
        String key = targetGrade + "|" + targetCode.toUpperCase(Locale.ROOT);
        plan.putIfAbsent(key, new RolloverClassPlan(source.getId(), source.getCode(), targetCode,
                "K" + targetGrade, type, source.getCapacity(), source.getStudyShift()));
    }

    private String replaceGradePrefix(String code, int grade) {
        String clean = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        return clean.matches("^\\d+.*") ? clean.replaceFirst("^\\d+", String.valueOf(grade))
                : grade + "-" + clean;
    }

    private int parseGrade(String value) {
        try { return Integer.parseInt(value == null ? "0" : value.replaceAll("\\D", "")); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private Optional<Semester> findSemester(List<Semester> semesters, int sequence) {
        String expectedCode = "HK" + sequence;
        return semesters.stream()
                .filter(item -> item.getSequence() == sequence || expectedCode.equalsIgnoreCase(item.getCode()))
                .findFirst();
    }

    private LocalDate min(LocalDate left, LocalDate right) {
        return left.isAfter(right) ? right : left;
    }
}
