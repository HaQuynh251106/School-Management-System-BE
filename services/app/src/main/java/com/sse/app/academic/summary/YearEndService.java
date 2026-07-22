package com.sse.app.academic.summary;

import com.sse.app.academic.grade.ExamCategory;
import com.sse.app.academic.grade.Grade;
import com.sse.app.academic.grade.GradeCalculationService;
import com.sse.app.academic.grade.GradeService;
import com.sse.app.academic.structure.AcademicYear;
import com.sse.app.academic.structure.SchoolClass;
import com.sse.app.academic.structure.Semester;
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
        List<Semester> semesters = structure.listSemesters(academicYearId);

        LinkedHashMap<String, UserDto> students = new LinkedHashMap<>();
        for (SchoolClass schoolClass : structure.listClasses(academicYearId, null)) {
            for (UserDto student : users.list("STUDENT", null, schoolClass.getId())) students.put(student.id(), student);
        }
        for (UserDto student : students.values()) evaluateAndSave(academicYearId, semesters, student);
        return summaries.findByAcademicYearIdOrderByStudentName(academicYearId);
    }

    /**
     * B13: GVCN chỉ nhìn thấy và tổng kết học sinh thuộc lớp mình chủ nhiệm
     * trong đúng năm học được chọn.
     */
    @Transactional
    public List<StudentYearlySummary> homeroomPreview(String academicYearId, String teacherId) {
        AcademicYear year = structure.getYear(academicYearId);
        Set<String> classIds = structure.listClasses(academicYearId, null).stream()
                .filter(item -> teacherId.equals(item.getHomeroomTeacherId()))
                .map(SchoolClass::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (classIds.isEmpty()) return List.of();

        List<StudentYearlySummary> existing = summaries.findByAcademicYearIdOrderByStudentName(academicYearId);
        if (!"CLOSED".equals(year.getStatus()) || existing.stream().noneMatch(item -> item.getFinalizedAt() != null)) {
            List<Semester> semesters = structure.listSemesters(academicYearId);
            for (String classId : classIds) {
                for (UserDto student : users.list("STUDENT", null, classId)) {
                    evaluateAndSave(academicYearId, semesters, student);
                }
            }
            existing = summaries.findByAcademicYearIdOrderByStudentName(academicYearId);
        }
        return existing.stream().filter(item -> classIds.contains(item.getClassId())).toList();
    }

    /** C11/D10: trả đúng một hồ sơ tổng kết, không làm lộ dữ liệu học sinh khác. */
    @Transactional
    public StudentYearlySummary studentSummary(String academicYearId, String studentId) {
        AcademicYear year = structure.getYear(academicYearId);
        Optional<StudentYearlySummary> existing = summaries.findByAcademicYearIdAndStudentId(academicYearId, studentId);
        if ("CLOSED".equals(year.getStatus())) {
            return existing.orElseThrow(() -> ApiException.notFound("Chưa có kết quả tổng kết trong năm học này"));
        }

        UserDto student = users.dtoById(studentId);
        if (!"STUDENT".equals(student.role())) throw ApiException.badRequest("Người dùng không phải học sinh");
        if (student.classId() == null || student.classId().isBlank()) {
            return existing.orElseThrow(() -> ApiException.notFound("Học sinh chưa được xếp lớp trong năm học này"));
        }
        SchoolClass schoolClass = structure.getClass(student.classId());
        if (!academicYearId.equals(schoolClass.getAcademicYearId())) {
            return existing.orElseThrow(() -> ApiException.notFound("Học sinh không thuộc năm học được chọn"));
        }
        evaluateAndSave(academicYearId, structure.listSemesters(academicYearId), student);
        return summaries.findByAcademicYearIdAndStudentId(academicYearId, studentId)
                .orElseThrow(() -> ApiException.notFound("Kết quả tổng kết học sinh"));
    }

    @Transactional
    public StudentYearlySummary setConduct(String academicYearId, String studentId, String conduct, CurrentUser actor) {
        if (conduct == null || !Set.of("GOOD", "FAIR", "AVERAGE", "WEAK").contains(conduct)) {
            throw ApiException.badRequest("Hạnh kiểm phải là Tốt, Khá, Trung bình hoặc Yếu");
        }
        if (!actor.isTeacher()) {
            throw ApiException.forbidden("Chỉ giáo viên chủ nhiệm được đánh giá hạnh kiểm");
        }
        homeroomPreview(academicYearId, actor.id());
        StudentYearlySummary summary = summaries.findByAcademicYearIdAndStudentId(academicYearId, studentId)
                .orElseThrow(() -> ApiException.notFound("Kết quả tổng kết học sinh"));
        SchoolClass schoolClass = structure.getClass(summary.getClassId());
        if (!actor.id().equals(schoolClass.getHomeroomTeacherId())) {
            throw ApiException.forbidden("Chỉ giáo viên chủ nhiệm của lớp được đánh giá hạnh kiểm");
        }
        if (summary.getFinalizedAt() != null) throw ApiException.conflict("Năm học đã được chốt");
        String previousConduct = summary.getConductGrade();
        summary.setConductGrade(conduct);
        summary.setPromotionStatus(summary.getSemesterOneAverage() != null && summary.getSemesterTwoAverage() != null
                && summary.getAverageScore() != null && summary.getMissingRequirements() == null
                ? "READY" : "INCOMPLETE");
        summary.setUpdatedAt(Instant.now());
        StudentYearlySummary saved = summaries.save(summary);
        if (!Objects.equals(previousConduct, conduct)) {
            String body = "Hạnh kiểm năm học " + structure.getYear(academicYearId).getCode()
                    + " đã được GVCN cập nhật: " + conductLabel(conduct) + ".";
            notifications.notifyUser(studentId, "CONDUCT_UPDATED", "IMPORTANT",
                    "Cập nhật hạnh kiểm", body, "YEARLY_SUMMARY", saved.getId());
            notifications.notifyParentsOfStudent(studentId, "CONDUCT_UPDATED", "IMPORTANT",
                    "Cập nhật hạnh kiểm của học sinh", body, "YEARLY_SUMMARY", saved.getId());
        }
        return saved;
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
        long incomplete = list.stream().filter(s -> s.getSemesterOneAverage() == null
                || s.getSemesterTwoAverage() == null || s.getAverageScore() == null
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
                Optional<SchoolClass> retainedClass = structure.findRetainedClass(academicYearId, currentClass)
                        .filter(target -> users.studentCountOfClass(target.getId()) < target.getCapacity());
                if (retainedClass.isPresent()) {
                    summary.setPromotionStatus("RETAINED");
                    summary.setNextClassId(retainedClass.get().getId());
                    users.moveStudentToClass(summary.getStudentId(), retainedClass.get().getId(), retainedClass.get().getCode());
                } else {
                    summary.setPromotionStatus("RETAINED_PENDING_CLASS");
                }
            }
            summary.setFinalizedAt(now);
            summary.setFinalizedBy(actorId);
            summary.setUpdatedAt(now);
            summaries.save(summary);
            String body = "Kết quả năm học: " + statusLabel(summary.getPromotionStatus())
                    + " · HKI " + String.format(Locale.US, "%.2f", summary.getSemesterOneAverage())
                    + " · HKII " + String.format(Locale.US, "%.2f", summary.getSemesterTwoAverage())
                    + " · Cả năm " + String.format(Locale.US, "%.2f", summary.getAverageScore());
            notifications.notifyUser(summary.getStudentId(), "YEAR_END", "Kết quả tổng kết năm học", body,
                    "YEARLY_SUMMARY", summary.getId());
            notifications.notifyParentsOfStudent(summary.getStudentId(), "YEAR_END", "Kết quả tổng kết năm học", body,
                    "YEARLY_SUMMARY", summary.getId());
        }
        long pendingPlacement = list.stream().filter(summary -> "PROMOTED_PENDING_CLASS".equals(summary.getPromotionStatus())
                || "RETAINED_PENDING_CLASS".equals(summary.getPromotionStatus())).count();
        if (pendingPlacement > 0) {
            throw ApiException.badRequest("Còn " + pendingPlacement
                    + " học sinh chưa có lớp đích trong năm học mới; hệ thống chưa chốt để bảo toàn dữ liệu");
        }
        structure.closeYear(academicYearId);
        return summaries.findByAcademicYearIdOrderByStudentName(academicYearId);
    }

    public List<StudentYearlySummary> summaries(String academicYearId) {
        return summaries.findByAcademicYearIdOrderByStudentName(academicYearId);
    }

    public void assertParentOf(String parentId, String studentId) {
        users.assertParentOf(parentId, studentId);
    }

    private void evaluateAndSave(String yearId, List<Semester> semesters, UserDto student) {
        StudentYearlySummary summary = summaries.findByAcademicYearIdAndStudentId(yearId, student.id())
                .orElseGet(() -> StudentYearlySummary.builder().id(Ids.gen("sys")).academicYearId(yearId)
                        .studentId(student.id()).build());
        summary.setStudentName(student.fullName());
        summary.setClassId(student.classId());
        summary.setUpdatedAt(Instant.now());

        Evaluation evaluation = evaluateGrades(student.id(), student.classId(), semesters);
        summary.setSemesterOneAverage(evaluation.semesterOneAverage());
        summary.setSemesterTwoAverage(evaluation.semesterTwoAverage());
        summary.setAverageScore(evaluation.annualAverage());
        summary.setMissingRequirements(evaluation.missing());
        summary.setPromotionStatus(evaluation.semesterOneAverage() != null && evaluation.semesterTwoAverage() != null
                && evaluation.annualAverage() != null && evaluation.missing() == null
                && summary.getConductGrade() != null
                ? "READY" : "INCOMPLETE");
        summaries.save(summary);
    }

    private Evaluation evaluateGrades(String studentId, String classId, List<Semester> semesters) {
        List<ExamCategory> categories = grades.listCategories();
        List<String> missing = new ArrayList<>();
        Semester semesterOne = findSemester(semesters, 1);
        Semester semesterTwo = findSemester(semesters, 2);
        List<Grade> actual = grades.list(studentId, null, null, null, null);

        SemesterEvaluation first = semesterOne == null
                ? new SemesterEvaluation(null, List.of("Chưa cấu hình học kỳ I"))
                : evaluateSemester(classId, semesterOne, actual, categories, "HKI");
        SemesterEvaluation second = semesterTwo == null
                ? new SemesterEvaluation(null, List.of("Chưa cấu hình học kỳ II"))
                : evaluateSemester(classId, semesterTwo, actual, categories, "HKII");
        missing.addAll(first.missing());
        missing.addAll(second.missing());

        Double annualAverage = null;
        if (first.average() != null && second.average() != null && missing.isEmpty()) {
            annualAverage = round((first.average() + 2 * second.average()) / 3.0);
        }
        String missingText = missing.isEmpty() ? null
                : String.join("; ", missing.stream().distinct().limit(16).toList());
        return new Evaluation(first.average(), second.average(), annualAverage, missingText);
    }

    private SemesterEvaluation evaluateSemester(String classId, Semester semester, List<Grade> actual,
                                                  List<ExamCategory> categories, String label) {
        LinkedHashSet<String> subjectIds = teachingAssignments.assignmentsOfClass(classId, semester.getId()).stream()
                .map(item -> item.getSubjectId()).collect(Collectors.toCollection(LinkedHashSet::new));
        if (subjectIds.isEmpty()) {
            return new SemesterEvaluation(null, List.of(label + " chưa có phân công môn học"));
        }

        List<String> missing = new ArrayList<>();
        double total = 0;
        double coefficientTotal = 0;
        for (String subjectId : subjectIds) {
            List<Grade> entries = actual.stream()
                    .filter(item -> semester.getId().equals(item.getSemesterId()) && subjectId.equals(item.getSubjectId()))
                    .toList();
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
                    subjectMissing.add(label + " · " + subjectName + " thiếu " + category.getName());
                }
            }
            missing.addAll(subjectMissing);
            if (subjectMissing.isEmpty()) {
                Double subjectAverage = gradeCalculations.subjectAverage(entries, categories);
                if (subjectAverage != null) {
                    double coefficient = structure.subjectCoefficient(subjectId);
                    total += subjectAverage * coefficient;
                    coefficientTotal += coefficient;
                }
            }
        }
        if (!missing.isEmpty()) return new SemesterEvaluation(null, missing);
        if (coefficientTotal == 0) {
            return new SemesterEvaluation(null, List.of(label + " chưa có đủ điểm để tổng kết"));
        }
        return new SemesterEvaluation(round(total / coefficientTotal), List.of());
    }

    private Semester findSemester(List<Semester> semesters, int sequence) {
        String expectedCode = "HK" + sequence;
        return semesters.stream()
                .filter(item -> item.getSequence() == sequence || expectedCode.equalsIgnoreCase(item.getCode()))
                .min(Comparator.comparingInt(item -> item.getSequence() == sequence ? 0 : 1))
                .orElse(null);
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private String conductLabel(String conduct) {
        return switch (conduct) {
            case "GOOD" -> "Tốt";
            case "FAIR" -> "Khá";
            case "AVERAGE" -> "Trung bình";
            case "WEAK" -> "Yếu";
            default -> conduct;
        };
    }

    private int parseGrade(String value) {
        try { return Integer.parseInt(value == null ? "0" : value.replaceAll("\\D", "")); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private String statusLabel(String value) {
        return switch (value) {
            case "PROMOTED" -> "Được lên lớp";
            case "PROMOTED_PENDING_CLASS" -> "Đủ điều kiện lên lớp, chờ xếp lớp";
            case "RETAINED_PENDING_CLASS" -> "Lưu ban, chờ xếp lớp";
            case "GRADUATED" -> "Tốt nghiệp";
            default -> "Lưu ban";
        };
    }

    private record SemesterEvaluation(Double average, List<String> missing) {}
    private record Evaluation(Double semesterOneAverage, Double semesterTwoAverage,
                              Double annualAverage, String missing) {}
}
