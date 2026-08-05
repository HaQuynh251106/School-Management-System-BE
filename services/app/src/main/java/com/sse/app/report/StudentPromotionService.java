package com.sse.app.report;

import com.sse.app.academic.structure.AcademicYear;
import com.sse.app.academic.structure.SchoolClass;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.audit.AuditService;
import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.identity.User;
import com.sse.app.identity.UserRepository;
import com.sse.app.identity.UserService;
import com.sse.app.report.StudentPromotionDtos.ExecutePromotionRequest;
import com.sse.app.report.StudentPromotionDtos.PlacementRequest;
import com.sse.app.report.StudentPromotionDtos.PromotionExecutionResponse;
import com.sse.app.report.StudentPromotionDtos.PromotionMetrics;
import com.sse.app.report.StudentPromotionDtos.PromotionPlanRequest;
import com.sse.app.report.StudentPromotionDtos.PromotionPreviewResponse;
import com.sse.app.report.StudentPromotionDtos.PromotionStudent;
import com.sse.app.report.StudentPromotionDtos.PromotionTargetClass;
import com.sse.app.report.StudentPromotionDtos.PromotionUndoResponse;
import com.sse.app.report.StudentPromotionDtos.UndoPromotionRequest;
import com.sse.app.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class StudentPromotionService {
    private static final Set<String> ENROLLMENT_RESULTS = Set.of("PROMOTED", "RETAINED");
    private static final Set<String> COMPLETION_RESULTS = Set.of(
            "ELIGIBLE_FOR_GRADUATION", "GRADUATED");

    private final StructureService structure;
    private final StudentYearlySummaryRepository summaries;
    private final StudentClassEnrollmentRepository enrollments;
    private final YearResultPublicationRepository publications;
    private final UserRepository users;
    private final UserService userService;
    private final AuditService audit;

    public StudentPromotionService(StructureService structure,
                                   StudentYearlySummaryRepository summaries,
                                   StudentClassEnrollmentRepository enrollments,
                                   YearResultPublicationRepository publications,
                                   UserRepository users,
                                   UserService userService,
                                   AuditService audit) {
        this.structure = structure;
        this.summaries = summaries;
        this.enrollments = enrollments;
        this.publications = publications;
        this.users = users;
        this.userService = userService;
        this.audit = audit;
    }

    public PromotionPreviewResponse preview(PromotionPlanRequest request) {
        AcademicYear sourceYear = requireYear(request.sourceAcademicYearId());
        AcademicYear targetYear = requireYear(request.targetAcademicYearId());
        if (sourceYear.getId().equals(targetYear.getId())) {
            throw ApiException.badRequest("Năm học đích phải khác năm học nguồn");
        }
        SchoolClass sourceClass = structure.getClass(require(request.sourceClassId(), "sourceClassId"));
        if (!sourceYear.getId().equals(sourceClass.getAcademicYearId())) {
            throw ApiException.badRequest("Lớp nguồn không thuộc năm học nguồn");
        }

        List<SchoolClass> targetClasses = structure.listClasses(targetYear.getId(), null);
        Map<String, SchoolClass> targetById = targetClasses.stream()
                .collect(Collectors.toMap(SchoolClass::getId, Function.identity()));
        Map<String, String> requestedTargets = placements(request.placements());
        List<StudentYearlySummary> sourceSummaries =
                summaries.findByAcademicYearIdAndClassId(sourceYear.getId(), sourceClass.getId());

        List<String> blockers = new ArrayList<>();
        if (!"CLOSED".equalsIgnoreCase(sourceYear.getStatus())) {
            blockers.add("Năm học nguồn chưa được đóng");
        }
        if (!"ACTIVE".equalsIgnoreCase(targetYear.getStatus())) {
            blockers.add("Năm học đích phải ở trạng thái đang hoạt động");
        }
        if (sourceSummaries.isEmpty()) {
            blockers.add("Lớp nguồn chưa có kết quả tổng kết");
        }

        List<PromotionStudent> rows = sourceSummaries.stream()
                .sorted(Comparator.comparing(StudentYearlySummary::getStudentCode,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .map(summary -> planStudent(
                        summary, sourceClass, targetYear, targetClasses, targetById,
                        requestedTargets.get(summary.getStudentId())))
                .toList();
        rows = applyCapacityRules(rows, targetById);
        long unfinished = rows.stream().filter(row -> "BLOCKED".equals(row.status())).count();
        long needsPlacement = rows.stream()
                .filter(row -> "NEEDS_PLACEMENT".equals(row.status())).count();
        if (unfinished > 0) blockers.add(unfinished + " học sinh đang bị chặn");
        if (needsPlacement > 0) {
            blockers.add(needsPlacement + " học sinh chưa được chọn lớp đích");
        }

        PromotionMetrics metrics = new PromotionMetrics(
                rows.size(),
                count(rows, "READY"),
                count(rows, "NEEDS_PLACEMENT"),
                count(rows, "ALREADY_PROCESSED"),
                (int) rows.stream().filter(row -> "COMPLETE_SCHOOL".equals(row.action())).count(),
                count(rows, "BLOCKED"));
        return new PromotionPreviewResponse(
                sourceYear.getId(), display(sourceYear.getName(), sourceYear.getCode()),
                targetYear.getId(), display(targetYear.getName(), targetYear.getCode()),
                sourceClass.getId(), sourceClass.getCode(),
                blockers.isEmpty(), blockers, metrics,
                targetClasses.stream().map(this::targetClassDto).toList(),
                rows, Instant.now());
    }

    @Transactional
    public PromotionExecutionResponse execute(ExecutePromotionRequest request, CurrentUser actor) {
        if (!actor.isAdmin()) {
            throw ApiException.forbidden("Chỉ Admin được thực hiện chuyển lớp");
        }
        if (!request.confirmed()) {
            throw ApiException.badRequest("Cần xác nhận thao tác chuyển lớp");
        }
        PromotionPlanRequest planRequest = new PromotionPlanRequest(
                request.sourceAcademicYearId(), request.targetAcademicYearId(),
                request.sourceClassId(), request.placements());
        PromotionPreviewResponse plan = preview(planRequest);
        if (!plan.canExecute()) {
            throw ApiException.conflict(String.join("; ", plan.blockers()));
        }
        SchoolClass sourceClass = structure.getClass(request.sourceClassId());

        Map<String, StudentYearlySummary> summaryById =
                summaries.findByAcademicYearIdAndClassId(
                                request.sourceAcademicYearId(), request.sourceClassId()).stream()
                        .collect(Collectors.toMap(StudentYearlySummary::getId, Function.identity()));
        Map<String, SchoolClass> classById = structure
                .listClasses(request.targetAcademicYearId(), null).stream()
                .collect(Collectors.toMap(SchoolClass::getId, Function.identity()));
        Set<String> affectedClassIds = new LinkedHashSet<>();
        int enrolled = 0;
        int completed = 0;
        int skipped = 0;
        Instant now = Instant.now();

        for (PromotionStudent row : plan.students()) {
            if ("ALREADY_PROCESSED".equals(row.status())) {
                skipped++;
                continue;
            }
            StudentYearlySummary summary = summaryById.get(row.summaryId());
            User student = users.findById(row.studentId())
                    .orElseThrow(() -> ApiException.conflict(
                            "Không tìm thấy tài khoản học sinh " + row.studentCode()));
            if ("COMPLETE_SCHOOL".equals(row.action())) {
                student.setClassId(null);
                student.setClassName(null);
                users.save(student);
                summary.setProgressionStatus("COMPLETED_SCHOOL");
                summary.setNextClassId(null);
                summary.setProgressedBy(actor.id());
                summary.setProgressedAt(now);
                summary.setUpdatedAt(now);
                summaries.save(summary);
                completed++;
                continue;
            }

            SchoolClass targetClass = classById.get(row.targetClassId());
            if (targetClass == null) {
                throw ApiException.conflict("Lớp đích không còn tồn tại");
            }
            StudentClassEnrollment existing = enrollments
                    .findByAcademicYearIdAndStudentId(
                            request.targetAcademicYearId(), row.studentId())
                    .orElse(null);
            if (existing != null) {
                if ("ACTIVE".equals(existing.getStatus())) {
                    if (!targetClass.getId().equals(existing.getClassId())) {
                        throw ApiException.conflict(
                                row.studentName() + " đã được ghi danh vào lớp khác");
                    }
                    skipped++;
                    continue;
                }
                if (!"REVERTED".equals(existing.getStatus())) {
                    throw ApiException.conflict(
                            row.studentName() + " có trạng thái ghi danh không hợp lệ");
                }
                if (!sourceClass.getId().equals(student.getClassId())) {
                    throw ApiException.conflict(
                            row.studentName() + " không còn ở lớp nguồn "
                                    + sourceClass.getCode());
                }
                affectedClassIds.add(existing.getClassId());
                existing.setClassId(targetClass.getId());
                existing.setSourceAcademicYearId(request.sourceAcademicYearId());
                existing.setSourceClassId(request.sourceClassId());
                existing.setSourceSummaryId(summary.getId());
                existing.setEnrollmentType(row.action());
                existing.setStatus("ACTIVE");
                existing.setEnrolledBy(actor.id());
                existing.setEnrolledAt(now);
                existing.setRevertedBy(null);
                existing.setRevertedAt(null);
                existing.setRevertReason(null);
                enrollments.save(existing);
            } else {
                enrollments.save(StudentClassEnrollment.builder()
                        .id(Ids.gen("sce"))
                        .academicYearId(request.targetAcademicYearId())
                        .classId(targetClass.getId())
                        .studentId(student.getId())
                        .studentCode(student.getStudentCode())
                        .studentName(student.getFullName())
                        .sourceAcademicYearId(request.sourceAcademicYearId())
                        .sourceClassId(request.sourceClassId())
                        .sourceSummaryId(summary.getId())
                        .enrollmentType(row.action())
                        .status("ACTIVE")
                        .enrolledBy(actor.id())
                        .enrolledAt(now)
                        .build());
            }
            student.setClassId(targetClass.getId());
            student.setClassName(targetClass.getCode());
            users.save(student);
            summary.setProgressionStatus("ENROLLED");
            summary.setNextClassId(targetClass.getId());
            summary.setProgressedBy(actor.id());
            summary.setProgressedAt(now);
            summary.setUpdatedAt(now);
            summaries.save(summary);
            affectedClassIds.add(targetClass.getId());
            enrolled++;
        }

        structure.updateClassStudentCount(request.sourceClassId(),
                (int) users.countByRoleAndClassId("STUDENT", request.sourceClassId()));
        for (String classId : affectedClassIds) {
            structure.updateClassStudentCount(classId,
                    (int) users.countByRoleAndClassId("STUDENT", classId));
        }
        audit.record(actor.id(), userService.fullNameOf(actor.id()), actor.role(),
                "EXECUTE_YEAR_PROMOTION", "academic", "student_class_enrollment",
                request.sourceAcademicYearId() + ":" + request.sourceClassId(),
                "Năm đích=" + request.targetAcademicYearId()
                        + "; ghi danh=" + enrolled
                        + "; hoàn tất THPT=" + completed
                        + "; bỏ qua=" + skipped);
        PromotionPreviewResponse updated = preview(planRequest);
        return new PromotionExecutionResponse(enrolled, completed, skipped, updated);
    }

    @Transactional
    public PromotionUndoResponse undo(UndoPromotionRequest request, CurrentUser actor) {
        if (!actor.isAdmin()) {
            throw ApiException.forbidden("Chỉ Admin được hoàn tác chuyển lớp");
        }
        if (!request.confirmed()) {
            throw ApiException.badRequest("Cần xác nhận thao tác hoàn tác chuyển lớp");
        }
        String reason = requireReason(request.reason());
        AcademicYear sourceYear = requireYear(request.sourceAcademicYearId());
        requireYear(request.targetAcademicYearId());
        SchoolClass sourceClass = structure.getClass(request.sourceClassId());
        if (!sourceYear.getId().equals(sourceClass.getAcademicYearId())) {
            throw ApiException.badRequest("Lớp nguồn không thuộc năm học nguồn");
        }
        publications.findByAcademicYearIdAndClassId(
                        request.sourceAcademicYearId(), request.sourceClassId())
                .filter(publication -> "PUBLISHED".equals(publication.getStatus()))
                .ifPresent(publication -> {
                    throw ApiException.conflict(
                            "Phải thu hồi kết quả cuối năm của lớp trước khi hoàn tác lên lớp");
                });

        List<StudentYearlySummary> sourceRows =
                summaries.findByAcademicYearIdAndClassId(
                        request.sourceAcademicYearId(), request.sourceClassId());
        List<StudentYearlySummary> processedRows = sourceRows.stream()
                .filter(row -> Set.of("ENROLLED", "COMPLETED_SCHOOL")
                        .contains(row.getProgressionStatus()))
                .toList();
        PromotionPlanRequest planRequest = new PromotionPlanRequest(
                request.sourceAcademicYearId(), request.targetAcademicYearId(),
                request.sourceClassId(), List.of());
        if (processedRows.isEmpty()) {
            return new PromotionUndoResponse(0, 0, 0, preview(planRequest));
        }

        Map<String, StudentClassEnrollment> enrollmentByStudent = enrollments
                .findBySourceAcademicYearIdAndSourceClassId(
                        request.sourceAcademicYearId(), request.sourceClassId()).stream()
                .filter(enrollment -> request.targetAcademicYearId()
                        .equals(enrollment.getAcademicYearId()))
                .collect(Collectors.toMap(StudentClassEnrollment::getStudentId,
                        Function.identity(), (left, right) -> left));
        Map<String, User> studentById = new LinkedHashMap<>();
        List<String> blockers = new ArrayList<>();
        for (StudentYearlySummary summary : processedRows) {
            User student = users.findById(summary.getStudentId()).orElse(null);
            if (student == null) {
                blockers.add(summary.getStudentName() + ": không tìm thấy tài khoản học sinh");
                continue;
            }
            studentById.put(student.getId(), student);
            if (summaries.findByAcademicYearIdAndStudentId(
                    request.targetAcademicYearId(), summary.getStudentId()).isPresent()) {
                blockers.add(summary.getStudentName()
                        + ": đã có kết quả học tập ở năm học đích");
                continue;
            }
            if ("COMPLETED_SCHOOL".equals(summary.getProgressionStatus())) {
                if (student.getClassId() != null && !student.getClassId().isBlank()) {
                    blockers.add(summary.getStudentName()
                            + ": tài khoản đã được xếp vào lớp khác");
                }
                continue;
            }
            if (!"ENROLLED".equals(summary.getProgressionStatus())) {
                blockers.add(summary.getStudentName()
                        + ": trạng thái chuyển lớp không hỗ trợ hoàn tác");
                continue;
            }
            StudentClassEnrollment enrollment = enrollmentByStudent.get(summary.getStudentId());
            if (enrollment == null
                    || !"ACTIVE".equals(enrollment.getStatus())
                    || !summary.getId().equals(enrollment.getSourceSummaryId())) {
                blockers.add(summary.getStudentName()
                        + ": không tìm thấy bản ghi ghi danh đang hoạt động");
                continue;
            }
            if (!enrollment.getClassId().equals(student.getClassId())) {
                blockers.add(summary.getStudentName()
                        + ": học sinh không còn ở đúng lớp đích đã ghi danh");
            }
        }
        if (!blockers.isEmpty()) {
            throw ApiException.conflict(String.join("; ", blockers));
        }

        Instant now = Instant.now();
        Set<String> affectedTargetClassIds = new LinkedHashSet<>();
        int revertedEnrollments = 0;
        int restoredCompletedStudents = 0;
        for (StudentYearlySummary summary : processedRows) {
            User student = studentById.get(summary.getStudentId());
            if ("COMPLETED_SCHOOL".equals(summary.getProgressionStatus())) {
                restoredCompletedStudents++;
            } else {
                StudentClassEnrollment enrollment =
                        enrollmentByStudent.get(summary.getStudentId());
                affectedTargetClassIds.add(enrollment.getClassId());
                enrollment.setStatus("REVERTED");
                enrollment.setRevertedBy(actor.id());
                enrollment.setRevertedAt(now);
                enrollment.setRevertReason(reason);
                enrollments.save(enrollment);
                revertedEnrollments++;
            }
            student.setClassId(sourceClass.getId());
            student.setClassName(sourceClass.getCode());
            users.save(student);
            summary.setProgressionStatus(null);
            summary.setNextClassId(null);
            summary.setProgressedBy(null);
            summary.setProgressedAt(null);
            summary.setUpdatedAt(now);
            summaries.save(summary);
        }

        structure.updateClassStudentCount(sourceClass.getId(),
                (int) users.countByRoleAndClassId("STUDENT", sourceClass.getId()));
        for (String classId : affectedTargetClassIds) {
            structure.updateClassStudentCount(classId,
                    (int) users.countByRoleAndClassId("STUDENT", classId));
        }
        audit.record(actor.id(), userService.fullNameOf(actor.id()), actor.role(),
                "UNDO_YEAR_PROMOTION", "academic", "student_class_enrollment",
                request.sourceAcademicYearId() + ":" + request.sourceClassId(),
                "Năm đích=" + request.targetAcademicYearId()
                        + "; hoàn tác ghi danh=" + revertedEnrollments
                        + "; khôi phục học sinh cuối cấp=" + restoredCompletedStudents
                        + "; lý do=" + reason);
        return new PromotionUndoResponse(
                revertedEnrollments, restoredCompletedStudents, 0, preview(planRequest));
    }

    public List<StudentClassEnrollment> listEnrollments(String academicYearId, String classId) {
        requireYear(academicYearId);
        if (classId == null || classId.isBlank()) {
            throw ApiException.badRequest("Thiếu classId");
        }
        return enrollments.findByAcademicYearIdAndClassId(academicYearId, classId).stream()
                .sorted(Comparator.comparing(StudentClassEnrollment::getStudentCode,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
    }

    @Transactional
    public StudentPromotionDtos.ProgressionStatusResponse updateProgressionStatus(
            StudentPromotionDtos.UpdateProgressionStatusRequest request,
            CurrentUser actor) {
        if (!actor.isAdmin()) {
            throw ApiException.forbidden("Chỉ Admin được cập nhật trạng thái học sinh");
        }
        if (!request.confirmed()) {
            throw ApiException.badRequest("Cần xác nhận thao tác");
        }
        String status = request.status().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("TRANSFERRED", "RESERVED", "WITHDRAWN", "ACTIVE")
                .contains(status)) {
            throw ApiException.badRequest(
                    "Trạng thái chỉ nhận TRANSFERRED, RESERVED, WITHDRAWN hoặc ACTIVE");
        }
        StudentYearlySummary summary = summaries
                .findByAcademicYearIdAndStudentId(
                        request.sourceAcademicYearId(), request.studentId())
                .orElseThrow(() -> ApiException.notFound("Kết quả học sinh"));
        if (!request.sourceClassId().equals(summary.getClassId())) {
            throw ApiException.badRequest("Học sinh không thuộc lớp nguồn");
        }
        if ("ACTIVE".equals(status)) {
            if (!Set.of("TRANSFERRED", "RESERVED", "WITHDRAWN")
                    .contains(summary.getProgressionStatus())) {
                throw ApiException.conflict(
                        "Học sinh không có trạng thái đặc biệt để hoàn tác");
            }
            User student = users.findById(request.studentId())
                    .orElseThrow(() -> ApiException.notFound("Học sinh"));
            Instant now = Instant.now();
            String oldStatus = summary.getProgressionStatus();
            summary.setProgressionStatus(null);
            summary.setReason(appendReason(
                    summary.getReason(), "Hoàn tác " + oldStatus
                            + ": " + request.reason()));
            summary.setProgressedBy(null);
            summary.setProgressedAt(null);
            summary.setUpdatedAt(now);
            summaries.save(summary);
            student.setClassId(request.sourceClassId());
            student.setClassName(structure.getClass(
                    request.sourceClassId()).getCode());
            users.save(student);
            structure.updateClassStudentCount(request.sourceClassId(),
                    (int) users.countByRoleAndClassId(
                            "STUDENT", request.sourceClassId()));
            audit.record(actor.id(), userService.fullNameOf(actor.id()), actor.role(),
                    "REVERT_STUDENT_PROGRESSION_STATUS", "academic",
                    "student_yearly_summary", summary.getId(),
                    "trạng thái cũ=" + oldStatus
                            + "; lý do=" + request.reason().trim());
            return new StudentPromotionDtos.ProgressionStatusResponse(
                    student.getId(), student.getStudentCode(),
                    student.getFullName(), "ACTIVE",
                    request.reason().trim(), now);
        }
        if (summary.getProgressionStatus() != null) {
            throw ApiException.conflict("Học sinh đã được xử lý chuyển lớp");
        }
        User student = users.findById(request.studentId())
                .orElseThrow(() -> ApiException.notFound("Học sinh"));
        Instant now = Instant.now();
        summary.setProgressionStatus(status);
        summary.setReason(appendReason(summary.getReason(), request.reason()));
        summary.setProgressedBy(actor.id());
        summary.setProgressedAt(now);
        summary.setUpdatedAt(now);
        summaries.save(summary);
        if (!"RESERVED".equals(status)) {
            student.setClassId(null);
            student.setClassName(null);
            users.save(student);
            structure.updateClassStudentCount(request.sourceClassId(),
                    (int) users.countByRoleAndClassId(
                            "STUDENT", request.sourceClassId()));
        }
        audit.record(actor.id(), userService.fullNameOf(actor.id()), actor.role(),
                "UPDATE_STUDENT_PROGRESSION_STATUS", "academic",
                "student_yearly_summary", summary.getId(),
                "trạng thái=" + status + "; lý do=" + request.reason().trim());
        return new StudentPromotionDtos.ProgressionStatusResponse(
                student.getId(), student.getStudentCode(), student.getFullName(),
                status, request.reason().trim(), now);
    }

    private PromotionStudent planStudent(StudentYearlySummary summary,
                                         SchoolClass sourceClass,
                                         AcademicYear targetYear,
                                         List<SchoolClass> targetClasses,
                                         Map<String, SchoolClass> targetById,
                                         String requestedTargetId) {
        if (!"FINALIZED".equals(summary.getStatus())) {
            return row(summary, "BLOCKED", null, null, null,
                    "BLOCKED", "Kết quả học sinh chưa được chốt");
        }
        if (summary.getProgressionStatus() != null) {
            SchoolClass nextClass = summary.getNextClassId() == null
                    ? null : targetById.get(summary.getNextClassId());
            return row(summary, action(summary.getResult()), requiredGrade(
                            sourceClass.getGradeLevel(), summary.getResult()),
                    nextClass == null ? null : nextClass.getId(),
                    nextClass == null ? null : nextClass.getCode(),
                    "ALREADY_PROCESSED", progressionMessage(summary, nextClass));
        }
        StudentClassEnrollment existing = enrollments
                .findByAcademicYearIdAndStudentId(targetYear.getId(), summary.getStudentId())
                .orElse(null);
        if (existing != null && "ACTIVE".equals(existing.getStatus())) {
            SchoolClass existingClass = targetById.get(existing.getClassId());
            return row(summary, action(summary.getResult()), existingClass == null
                            ? null : existingClass.getGradeLevel(),
                    existing.getClassId(), existingClass == null ? null : existingClass.getCode(),
                    "ALREADY_PROCESSED", "Đã ghi danh trước đó");
        }
        if (users.findById(summary.getStudentId()).isEmpty()) {
            return row(summary, "BLOCKED", null, null, null,
                    "BLOCKED", "Không tìm thấy tài khoản học sinh");
        }
        if (COMPLETION_RESULTS.contains(summary.getResult())) {
            return row(summary, "COMPLETE_SCHOOL", null, null, null,
                    "READY", "Không ghi danh lớp mới; hoàn tất chương trình THPT");
        }
        if (!ENROLLMENT_RESULTS.contains(summary.getResult())) {
            return row(summary, "BLOCKED", null, null, null,
                    "BLOCKED", "Kết quả cuối năm chưa cho phép chuyển lớp");
        }

        String action = action(summary.getResult());
        String requiredGrade = requiredGrade(sourceClass.getGradeLevel(), summary.getResult());
        if (requiredGrade == null) {
            return row(summary, "BLOCKED", null, null, null,
                    "BLOCKED", "Không xác định được khối lớp đích");
        }
        SchoolClass selected = null;
        if (requestedTargetId != null && !requestedTargetId.isBlank()) {
            selected = targetById.get(requestedTargetId);
            if (selected == null || !targetYear.getId().equals(selected.getAcademicYearId())) {
                return row(summary, action, requiredGrade, requestedTargetId, null,
                        "BLOCKED", "Lớp đích không thuộc năm học đích");
            }
            if (!requiredGrade.equalsIgnoreCase(selected.getGradeLevel())) {
                return row(summary, action, requiredGrade, selected.getId(), selected.getCode(),
                        "BLOCKED", "Lớp đích không đúng khối " + gradeNumber(requiredGrade));
            }
        } else {
            String suggestedCode = suggestedClassCode(
                    sourceClass.getCode(), requiredGrade);
            selected = targetClasses.stream()
                    .filter(target -> requiredGrade.equalsIgnoreCase(target.getGradeLevel()))
                    .filter(target -> suggestedCode.equalsIgnoreCase(target.getCode()))
                    .findFirst().orElse(null);
        }
        if (selected == null) {
            return row(summary, action, requiredGrade, null, null,
                    "NEEDS_PLACEMENT", "Chọn lớp " + gradeNumber(requiredGrade) + " cho học sinh");
        }
        return row(summary, action, requiredGrade, selected.getId(), selected.getCode(),
                "READY", "Sẵn sàng " + ("PROMOTE".equals(action) ? "lên lớp" : "học lại"));
    }

    private PromotionStudent row(StudentYearlySummary summary, String action,
                                 String requiredGrade, String targetClassId,
                                 String targetClassCode, String status, String message) {
        return new PromotionStudent(
                summary.getId(), summary.getStudentId(), summary.getStudentCode(),
                summary.getStudentName(), normalizeResult(summary.getResult()), action,
                requiredGrade, targetClassId, targetClassCode, status, message);
    }

    private String action(String result) {
        if ("PROMOTED".equals(result)) return "PROMOTE";
        if ("RETAINED".equals(result)) return "RETAIN";
        if (COMPLETION_RESULTS.contains(result)) return "COMPLETE_SCHOOL";
        return "BLOCKED";
    }

    private String requiredGrade(String sourceGrade, String result) {
        if ("RETAINED".equals(result)) return sourceGrade;
        if (!"PROMOTED".equals(result)) return null;
        if ("K10".equalsIgnoreCase(sourceGrade)) return "K11";
        if ("K11".equalsIgnoreCase(sourceGrade)) return "K12";
        return null;
    }

    private String suggestedClassCode(String sourceCode, String targetGrade) {
        if (sourceCode == null) return "";
        String suffix = sourceCode.trim().toUpperCase(Locale.ROOT)
                .replaceFirst("^\\d+", "");
        return gradeNumber(targetGrade) + suffix;
    }

    private String progressionMessage(StudentYearlySummary summary, SchoolClass nextClass) {
        if ("COMPLETED_SCHOOL".equals(summary.getProgressionStatus())) {
            return "Đã hoàn tất chương trình THPT";
        }
        return nextClass == null ? "Đã xử lý" : "Đã ghi danh lớp " + nextClass.getCode();
    }

    private Map<String, String> placements(List<PlacementRequest> rows) {
        Map<String, String> result = new LinkedHashMap<>();
        if (rows == null) return result;
        for (PlacementRequest row : rows) {
            if (row == null || row.studentId() == null || row.studentId().isBlank()) continue;
            result.put(row.studentId().trim(), blankToNull(row.targetClassId()));
        }
        return result;
    }

    private PromotionTargetClass targetClassDto(SchoolClass schoolClass) {
        int maxStudents = capacity(schoolClass);
        return new PromotionTargetClass(schoolClass.getId(), schoolClass.getCode(),
                display(schoolClass.getName(), schoolClass.getCode()),
                schoolClass.getGradeLevel(),
                schoolClass.getStudentCount(), maxStudents,
                Math.max(0, maxStudents - schoolClass.getStudentCount()));
    }

    private List<PromotionStudent> applyCapacityRules(
            List<PromotionStudent> rows, Map<String, SchoolClass> targetById) {
        Map<String, Long> requested = rows.stream()
                .filter(row -> "READY".equals(row.status())
                        && row.targetClassId() != null)
                .collect(Collectors.groupingBy(
                        PromotionStudent::targetClassId, Collectors.counting()));
        return rows.stream().map(row -> {
            if (!"READY".equals(row.status()) || row.targetClassId() == null) {
                return row;
            }
            SchoolClass target = targetById.get(row.targetClassId());
            if (target == null) return row;
            long projected = target.getStudentCount()
                    + requested.getOrDefault(target.getId(), 0L);
            if (projected <= capacity(target)) return row;
            return new PromotionStudent(
                    row.summaryId(), row.studentId(), row.studentCode(),
                    row.studentName(), row.result(), row.action(),
                    row.requiredTargetGradeLevel(), row.targetClassId(),
                    row.targetClassCode(), "BLOCKED",
                    "Lớp đích vượt sức chứa "
                            + target.getStudentCount() + "/" + capacity(target));
        }).toList();
    }

    private int capacity(SchoolClass schoolClass) {
        return schoolClass.getMaxStudents() == null
                ? 45 : schoolClass.getMaxStudents();
    }

    private String appendReason(String current, String added) {
        return current == null || current.isBlank()
                ? added.trim() : current + "; " + added.trim();
    }

    private AcademicYear requireYear(String academicYearId) {
        String id = require(academicYearId, "academicYearId");
        return structure.listYears().stream().filter(year -> id.equals(year.getId())).findFirst()
                .orElseThrow(() -> ApiException.notFound("Năm học"));
    }

    private int count(List<PromotionStudent> rows, String status) {
        return (int) rows.stream().filter(row -> status.equals(row.status())).count();
    }

    private String normalizeResult(String result) {
        return "GRADUATED".equals(result) ? "ELIGIBLE_FOR_GRADUATION" : result;
    }

    private String gradeNumber(String gradeLevel) {
        return gradeLevel == null ? "" : gradeLevel.replaceFirst("(?i)^K", "");
    }

    private String require(String value, String field) {
        if (value == null || value.isBlank()) throw ApiException.badRequest("Thiếu " + field);
        return value.trim();
    }

    private String display(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String requireReason(String reason) {
        String value = reason == null ? "" : reason.trim();
        if (value.length() < 10) {
            throw ApiException.badRequest("Lý do hoàn tác phải có ít nhất 10 ký tự");
        }
        return value;
    }
}
