package com.sse.app.academic.structure;

import com.sse.app.academic.structure.StructureDtos.ClassCountPlanRequest;
import com.sse.app.academic.structure.StructureDtos.ClassCountPlanResponse;
import com.sse.app.academic.structure.StructureDtos.ClassCountPlanRow;
import com.sse.app.academic.structure.StructureDtos.CreateClassRequest;
import com.sse.app.academic.planning.EducationPlanningCatalogService;
import com.sse.app.academic.teaching.TeachingAssignmentService;
import com.sse.app.academic.timetable.AutomaticTimetableService;
import com.sse.app.academic.timetable.TimetableService;
import com.sse.app.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
public class ClassCountManagementService {
    private static final List<String> GRADES = List.of("K10", "K11", "K12");

    private final StructureService structure;
    private final TeachingAssignmentService teachingAssignments;
    private final TimetableService timetable;
    private final AutomaticTimetableService automaticTimetable;
    private final EducationPlanningCatalogService planningCatalog;

    public ClassCountManagementService(
            StructureService structure,
            TeachingAssignmentService teachingAssignments,
            TimetableService timetable,
            AutomaticTimetableService automaticTimetable,
            EducationPlanningCatalogService planningCatalog) {
        this.structure = structure;
        this.teachingAssignments = teachingAssignments;
        this.timetable = timetable;
        this.automaticTimetable = automaticTimetable;
        this.planningCatalog = planningCatalog;
    }

    public ClassCountPlanResponse preview(ClassCountPlanRequest request) {
        AcademicYear year = structure.getYear(request.academicYearId());
        if ("CLOSED".equalsIgnoreCase(year.getStatus())
                || "LOCKED".equalsIgnoreCase(year.getStatus())) {
            throw ApiException.badRequest("Năm học đã đóng hoặc đã khóa, không thể đổi quy mô lớp");
        }
        Map<String, Integer> targets = Map.of(
                "K10", target(request.grade10Count(), "Khối 10"),
                "K11", target(request.grade11Count(), "Khối 11"),
                "K12", target(request.grade12Count(), "Khối 12"));
        List<ClassCountPlanRow> rows = GRADES.stream()
                .map(grade -> previewGrade(year.getId(), grade, targets.get(grade)))
                .toList();
        boolean applicable = rows.stream().allMatch(row -> row.blockingReasons().isEmpty());
        return new ClassCountPlanResponse(year.getId(), applicable, rows);
    }

    @Transactional
    public ClassCountPlanResponse apply(ClassCountPlanRequest request) {
        ClassCountPlanResponse plan = preview(request);
        if (!plan.applicable()) {
            throw ApiException.badRequest("Chưa thể thay đổi quy mô lớp. Hãy xử lý các lớp đang có dữ liệu trước");
        }
        for (ClassCountPlanRow row : plan.grades()) {
            for (String code : row.classesToReactivate()) {
                SchoolClass schoolClass = structure.findClass(plan.academicYearId(), code);
                structure.setClassStatus(schoolClass.getId(), "ACTIVE");
            }
            for (String code : row.classesToCreate()) {
                structure.createClass(new CreateClassRequest(
                        null, code, "Lớp " + code, row.gradeLevel(),
                        plan.academicYearId(), null, null, 45));
            }
            for (String code : row.classesToDeactivate()) {
                SchoolClass schoolClass = structure.findClass(plan.academicYearId(), code);
                structure.setClassStatus(schoolClass.getId(), "INACTIVE");
            }
        }
        return preview(request);
    }

    private ClassCountPlanRow previewGrade(String academicYearId, String grade, int target) {
        List<SchoolClass> all = structure.listClasses(academicYearId, grade);
        List<SchoolClass> active = all.stream().filter(this::isActive)
                .sorted(Comparator.comparingInt(item -> classNumber(item.getCode())))
                .toList();
        List<SchoolClass> inactive = all.stream().filter(item -> !isActive(item)).toList();
        List<String> create = new ArrayList<>();
        List<String> reactivate = new ArrayList<>();
        String gradeNumber = grade.substring(1);
        for (int number = 1; number <= target; number++) {
            String code = gradeNumber + "A" + number;
            if (active.stream().noneMatch(item -> code.equalsIgnoreCase(item.getCode()))) {
                if (inactive.stream().anyMatch(item -> code.equalsIgnoreCase(item.getCode()))) {
                    reactivate.add(code);
                } else {
                    create.add(code);
                }
            }
        }
        List<SchoolClass> deactivateRows = active.stream()
                .filter(item -> classNumber(item.getCode()) > target)
                .sorted(Comparator.comparingInt((SchoolClass item) -> classNumber(item.getCode())).reversed())
                .toList();
        List<String> blocking = new ArrayList<>();
        for (SchoolClass schoolClass : deactivateRows) {
            List<String> reasons = deactivationReasons(schoolClass);
            if (!reasons.isEmpty()) {
                blocking.add(schoolClass.getCode() + ": " + String.join(", ", reasons));
            }
        }
        return new ClassCountPlanRow(grade, active.size(), target, create, reactivate,
                deactivateRows.stream().map(SchoolClass::getCode).toList(), blocking);
    }

    private List<String> deactivationReasons(SchoolClass schoolClass) {
        List<String> reasons = new ArrayList<>();
        if (schoolClass.getStudentCount() > 0) reasons.add("còn " + schoolClass.getStudentCount() + " học sinh");
        if (schoolClass.getHomeroomTeacherId() != null && !schoolClass.getHomeroomTeacherId().isBlank()) {
            reasons.add("đã có GVCN");
        }
        if (teachingAssignments.hasActiveAssignmentsForClass(schoolClass.getId())) {
            reasons.add("còn phân công giảng dạy");
        }
        if (timetable.hasPublishedSlotsForClass(schoolClass.getId())) {
            reasons.add("đã có thời khóa biểu phát hành");
        }
        if (automaticTimetable.hasDraftSlotsForClass(schoolClass.getId())) {
            reasons.add("đang có trong bản thời khóa biểu nháp");
        }
        if (planningCatalog.hasClassCombination(schoolClass.getId())) {
            reasons.add("đã được gán tổ hợp môn");
        }
        return reasons;
    }

    private boolean isActive(SchoolClass schoolClass) {
        return schoolClass.getStatus() == null || schoolClass.getStatus().isBlank()
                || "ACTIVE".equalsIgnoreCase(schoolClass.getStatus());
    }

    private int target(Integer value, String label) {
        if (value == null || value < 1 || value > 20) {
            throw ApiException.badRequest(label + " phải có từ 1 đến 20 lớp");
        }
        return value;
    }

    private int classNumber(String code) {
        try {
            return Integer.parseInt(code.substring(code.toUpperCase().lastIndexOf('A') + 1));
        } catch (RuntimeException ignored) {
            return Integer.MAX_VALUE;
        }
    }
}
