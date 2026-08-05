package com.sse.app.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sse.app.academic.structure.AcademicYear;
import com.sse.app.academic.structure.SchoolClass;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.audit.AuditService;
import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.event.DomainEventPublisher;
import com.sse.app.identity.UserService;
import com.sse.app.report.YearResultDtos.PublishYearResultResponse;
import com.sse.app.report.YearResultDtos.StudentYearResult;
import com.sse.app.report.YearResultDtos.YearResultFile;
import com.sse.app.report.YearResultDtos.YearResultPublicationStatus;
import com.sse.app.report.YearResultDtos.WithdrawYearResultResponse;
import com.sse.app.report.YearReviewDtos.AnnualSubjectResult;
import com.sse.app.report.YearReviewDtos.SemesterResult;
import com.sse.app.report.YearReviewDtos.YearReviewResponse;
import com.sse.app.report.YearReviewDtos.YearReviewStudent;
import com.sse.app.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class YearResultService {
    private static final TypeReference<List<SemesterResult>> SEMESTERS =
            new TypeReference<>() {};
    private static final TypeReference<List<AnnualSubjectResult>> SUBJECTS =
            new TypeReference<>() {};

    private final StudentYearlySummaryRepository summaries;
    private final YearResultPublicationRepository publications;
    private final YearReviewService reviews;
    private final StructureService structure;
    private final UserService users;
    private final DomainEventPublisher events;
    private final AuditService audit;
    private final ObjectMapper objectMapper;
    private final YearResultPdfRenderer pdf;
    private final YearResultExcelExporter excel;
    private final YearResultSnapshotBuilder snapshotBuilder;
    private final YearResultPublicationHistoryRepository history;

    public YearResultService(StudentYearlySummaryRepository summaries,
                             YearResultPublicationRepository publications,
                             YearReviewService reviews,
                             StructureService structure,
                             UserService users,
                             DomainEventPublisher events,
                             AuditService audit,
                             ObjectMapper objectMapper,
                             YearResultPdfRenderer pdf,
                             YearResultExcelExporter excel,
                             YearResultSnapshotBuilder snapshotBuilder) {
        this(summaries, publications, reviews, structure, users, events,
                audit, objectMapper, pdf, excel, snapshotBuilder, null);
    }

    @Autowired
    public YearResultService(StudentYearlySummaryRepository summaries,
                             YearResultPublicationRepository publications,
                             YearReviewService reviews,
                             StructureService structure,
                             UserService users,
                             DomainEventPublisher events,
                             AuditService audit,
                             ObjectMapper objectMapper,
                             YearResultPdfRenderer pdf,
                             YearResultExcelExporter excel,
                             YearResultSnapshotBuilder snapshotBuilder,
                             YearResultPublicationHistoryRepository history) {
        this.summaries = summaries;
        this.publications = publications;
        this.reviews = reviews;
        this.structure = structure;
        this.users = users;
        this.events = events;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.pdf = pdf;
        this.excel = excel;
        this.snapshotBuilder = snapshotBuilder;
        this.history = history;
    }

    public YearResultPublicationStatus status(String academicYearId, String classId) {
        AcademicYear year = requireYear(academicYearId);
        SchoolClass schoolClass = requireClass(year, classId);
        List<StudentYearlySummary> rows =
                summaries.findByAcademicYearIdAndClassId(year.getId(), schoolClass.getId());
        int finalized = (int) rows.stream()
                .filter(row -> "FINALIZED".equals(row.getStatus())).count();
        YearResultPublication publication = publications
                .findByAcademicYearIdAndClassId(year.getId(), schoolClass.getId())
                .orElse(null);
        boolean publicationCurrent = publication != null
                && "PUBLISHED".equals(publication.getStatus())
                && !rows.isEmpty()
                && finalized == rows.size()
                && rows.stream().allMatch(row -> publishedVersion(publication, row));
        String publicationState = publication == null
                ? "NOT_PUBLISHED"
                : "WITHDRAWN".equals(publication.getStatus())
                    ? "WITHDRAWN"
                    : publicationCurrent ? "PUBLISHED" : "OUTDATED";
        return new YearResultPublicationStatus(
                year.getId(), display(year.getName(), year.getCode()),
                schoolClass.getId(), schoolClass.getCode(), rows.size(), finalized,
                !rows.isEmpty() && finalized == rows.size(),
                publicationCurrent,
                publicationState,
                publication == null || publication.getPublicationVersion() == null
                        ? 0 : publication.getPublicationVersion(),
                publication == null || publication.getPublishedBy() == null
                        ? null : users.fullNameOf(publication.getPublishedBy()),
                publication == null ? null : publication.getPublishedAt(),
                publication == null || publication.getWithdrawnBy() == null
                        ? null : users.fullNameOf(publication.getWithdrawnBy()),
                publication == null ? null : publication.getWithdrawnAt(),
                publication == null ? null : publication.getWithdrawalReason());
    }

    @Transactional
    public synchronized PublishYearResultResponse publish(String academicYearId, String classId,
                                                          boolean confirmed, String reason,
                                                          CurrentUser actor) {
        if (!actor.isAdmin()) {
            throw ApiException.forbidden("Chỉ Admin được công bố kết quả năm học");
        }
        if (!confirmed) {
            throw ApiException.badRequest("Cần xác nhận thao tác công bố kết quả");
        }
        YearResultPublication existing = publications
                .findByAcademicYearIdAndClassId(academicYearId, classId).orElse(null);
        List<StudentYearlySummary> currentRows =
                summaries.findByAcademicYearIdAndClassId(academicYearId, classId);
        if (existing != null && "PUBLISHED".equals(existing.getStatus())
                && !currentRows.isEmpty()
                && currentRows.stream().allMatch(row -> "FINALIZED".equals(row.getStatus())
                        && publishedVersion(existing, row))) {
            return new PublishYearResultResponse(status(academicYearId, classId), 0, false);
        }
        boolean republishing = existing != null;
        String publishReason = republishing
                ? requireReason(reason, "Lý do công bố lại")
                : blankToDefault(reason, "Công bố kết quả lần đầu");

        YearReviewResponse review = reviews.review(academicYearId, classId, actor);
        if (!review.finalized()) {
            throw ApiException.conflict("Lớp chưa được chốt đầy đủ nên chưa thể công bố");
        }
        Map<String, YearReviewStudent> reviewByStudent = review.students().stream()
                .collect(Collectors.toMap(YearReviewStudent::studentId, Function.identity()));
        List<StudentYearlySummary> finalizedRows =
                summaries.findByAcademicYearIdAndClassId(academicYearId, classId);
        if (finalizedRows.isEmpty() || finalizedRows.stream()
                .anyMatch(row -> !"FINALIZED".equals(row.getStatus()))) {
            throw ApiException.conflict("Kết quả học sinh chưa được chốt đầy đủ");
        }

        for (StudentYearlySummary summary : finalizedRows) {
            YearReviewStudent row = reviewByStudent.get(summary.getStudentId());
            List<SemesterResult> semesterSnapshot =
                    row == null ? List.of() : row.semesters();
            List<AnnualSubjectResult> subjectSnapshot =
                    row == null ? List.of() : row.annualSubjects();
            if (semesterSnapshot.isEmpty() || subjectSnapshot.isEmpty()) {
                double subjectMinimum = review.policy() == null
                        ? 5.0 : review.policy().subjectMinimumScore();
                YearResultSnapshotBuilder.Snapshot rebuilt =
                        snapshotBuilder.build(summary, subjectMinimum);
                if (semesterSnapshot.isEmpty()) semesterSnapshot = rebuilt.semesters();
                if (subjectSnapshot.isEmpty()) subjectSnapshot = rebuilt.subjects();
            }
            summary.setSemesterResultsJson(writeJson(semesterSnapshot));
            summary.setSubjectResultsJson(writeJson(subjectSnapshot));
            summary.setUpdatedAt(Instant.now());
            summaries.save(summary);
        }

        Instant now = Instant.now();
        YearResultPublication publication = existing == null
                ? YearResultPublication.builder()
                    .id(Ids.gen("yrp"))
                    .academicYearId(academicYearId)
                    .classId(classId)
                    .build()
                : existing;
        publication.setStatus("PUBLISHED");
        publication.setStudentCount(finalizedRows.size());
        publication.setPublicationVersion(
                (publication.getPublicationVersion() == null
                        ? 0 : publication.getPublicationVersion()) + 1);
        publication.setLastPublishReason(publishReason);
        publication.setPublishedBy(actor.id());
        publication.setPublishedAt(now);
        publication.setWithdrawnBy(null);
        publication.setWithdrawnAt(null);
        publication.setWithdrawalReason(null);
        publication.setUpdatedAt(now);
        publications.save(publication);
        saveHistory(publication, republishing ? "REPUBLISH" : "PUBLISH", publishReason,
                actor.id(), finalizedRows.size(), now);

        for (StudentYearlySummary summary : finalizedRows) {
            events.publish("academic.year_result.published", actor.id(),
                    "student_yearly_summary", summary.getId(),
                    Map.of(
                            "studentId", summary.getStudentId(),
                            "studentName", value(summary.getStudentName()),
                            "studentCode", value(summary.getStudentCode()),
                            "academicYearId", academicYearId,
                            "academicYearName", review.academicYearName(),
                            "classId", classId,
                            "classCode", review.classCode(),
                            "result", value(summary.getResult()),
                            "message", "Kết quả năm học " + review.academicYearName()
                                    + " đã được nhà trường công bố."));
        }
        audit.record(actor.id(), users.fullNameOf(actor.id()), actor.role(),
                republishing ? "REPUBLISH_YEAR_RESULTS" : "PUBLISH_YEAR_RESULTS",
                "academic", "year_result_publication",
                publication.getId(), "Năm học=" + academicYearId + "; lớp=" + classId
                        + "; phiên bản=" + publication.getPublicationVersion()
                        + "; học sinh=" + finalizedRows.size()
                        + "; lý do=" + publishReason);
        return new PublishYearResultResponse(status(academicYearId, classId),
                finalizedRows.size(), true);
    }

    @Transactional
    public synchronized WithdrawYearResultResponse withdraw(
            String academicYearId, String classId, boolean confirmed,
            String reason, CurrentUser actor) {
        if (!actor.isAdmin()) {
            throw ApiException.forbidden("Chỉ Admin được thu hồi kết quả năm học");
        }
        if (!confirmed) {
            throw ApiException.badRequest("Cần xác nhận thao tác thu hồi kết quả");
        }
        AcademicYear year = requireYear(academicYearId);
        SchoolClass schoolClass = requireClass(year, classId);
        YearResultPublication publication = publications
                .findByAcademicYearIdAndClassId(academicYearId, classId)
                .orElseThrow(() -> ApiException.notFound("Kết quả đã công bố"));
        if ("WITHDRAWN".equals(publication.getStatus())) {
            return new WithdrawYearResultResponse(status(academicYearId, classId), 0, false);
        }
        if (!"PUBLISHED".equals(publication.getStatus())) {
            throw ApiException.conflict("Kết quả lớp này chưa ở trạng thái đã công bố");
        }
        String withdrawalReason = requireReason(reason, "Lý do thu hồi");
        List<StudentYearlySummary> rows =
                summaries.findByAcademicYearIdAndClassId(academicYearId, classId);
        Instant now = Instant.now();
        publication.setStatus("WITHDRAWN");
        publication.setWithdrawnBy(actor.id());
        publication.setWithdrawnAt(now);
        publication.setWithdrawalReason(withdrawalReason);
        publication.setUpdatedAt(now);
        publications.save(publication);
        saveHistory(publication, "WITHDRAW", withdrawalReason,
                actor.id(), rows.size(), now);

        String yearName = display(year.getName(), year.getCode());
        for (StudentYearlySummary summary : rows) {
            events.publish("academic.year_result.withdrawn", actor.id(),
                    "student_yearly_summary", summary.getId(),
                    Map.of(
                            "studentId", summary.getStudentId(),
                            "academicYearId", academicYearId,
                            "academicYearName", yearName,
                            "classId", classId,
                            "classCode", schoolClass.getCode(),
                            "message", "Kết quả năm học " + yearName
                                    + " đã được tạm thu hồi để nhà trường rà soát."));
        }
        audit.record(actor.id(), users.fullNameOf(actor.id()), actor.role(),
                "WITHDRAW_YEAR_RESULTS", "academic", "year_result_publication",
                publication.getId(), "Năm học=" + academicYearId + "; lớp=" + classId
                        + "; phiên bản=" + publication.getPublicationVersion()
                        + "; học sinh=" + rows.size()
                        + "; lý do=" + withdrawalReason);
        return new WithdrawYearResultResponse(status(academicYearId, classId),
                rows.size(), true);
    }

    @Transactional
    public synchronized YearResultDtos.BatchYearResultResponse publishBatch(
            String academicYearId, YearResultDtos.BatchYearResultRequest request,
            CurrentUser actor) {
        if (request == null || request.classIds() == null
                || request.classIds().isEmpty()) {
            throw ApiException.badRequest("Danh sách lớp không được trống");
        }
        List<YearResultPublicationStatus> statuses = new java.util.ArrayList<>();
        int changed = 0;
        int affected = 0;
        for (String classId : request.classIds().stream()
                .filter(Objects::nonNull).map(String::trim)
                .filter(value -> !value.isBlank()).distinct().toList()) {
            PublishYearResultResponse response = publish(
                    academicYearId, classId, request.confirmed(),
                    request.reason(), actor);
            statuses.add(response.publication());
            if (response.newlyPublished()) changed++;
            affected += response.notificationsQueued();
        }
        return new YearResultDtos.BatchYearResultResponse(
                statuses.size(), changed, affected, statuses);
    }

    @Transactional
    public synchronized YearResultDtos.BatchYearResultResponse withdrawBatch(
            String academicYearId, YearResultDtos.BatchYearResultRequest request,
            CurrentUser actor) {
        if (request == null || request.classIds() == null
                || request.classIds().isEmpty()) {
            throw ApiException.badRequest("Danh sách lớp không được trống");
        }
        List<YearResultPublicationStatus> statuses = new java.util.ArrayList<>();
        int changed = 0;
        int affected = 0;
        for (String classId : request.classIds().stream()
                .filter(Objects::nonNull).map(String::trim)
                .filter(value -> !value.isBlank()).distinct().toList()) {
            WithdrawYearResultResponse response = withdraw(
                    academicYearId, classId, request.confirmed(),
                    request.reason(), actor);
            statuses.add(response.publication());
            if (response.newlyWithdrawn()) changed++;
            affected += response.notificationsQueued();
        }
        return new YearResultDtos.BatchYearResultResponse(
                statuses.size(), changed, affected, statuses);
    }

    public List<YearResultPublicationHistory> history(
            String academicYearId, String classId) {
        requireYear(academicYearId);
        if (history == null) return List.of();
        return history.findByAcademicYearIdAndClassIdOrderByOccurredAtDesc(
                academicYearId, classId);
    }

    private void saveHistory(
            YearResultPublication publication, String action, String reason,
            String actorId, int studentCount, Instant occurredAt) {
        if (history == null) return;
        history.save(YearResultPublicationHistory.builder()
                .id(Ids.gen("yrh"))
                .publicationId(publication.getId())
                .academicYearId(publication.getAcademicYearId())
                .classId(publication.getClassId())
                .publicationVersion(publication.getPublicationVersion())
                .action(action)
                .studentCount(studentCount)
                .actorId(actorId)
                .reason(reason)
                .occurredAt(occurredAt)
                .build());
    }

    public List<StudentYearResult> ownResults(CurrentUser actor) {
        if (!"STUDENT".equals(actor.role())) {
            throw ApiException.forbidden("Chỉ học sinh được xem kết quả của chính mình");
        }
        return resultsForStudent(actor.id(), actor);
    }

    public List<StudentYearResult> resultsForStudent(String studentId, CurrentUser actor) {
        assertCanView(studentId, actor);
        Map<String, AcademicYear> years = structure.listYears().stream()
                .collect(Collectors.toMap(AcademicYear::getId, Function.identity()));
        Map<String, SchoolClass> classes = new LinkedHashMap<>();
        return summaries.findByStudentId(studentId).stream()
                .filter(summary -> "FINALIZED".equals(summary.getStatus()))
                .map(summary -> {
                    YearResultPublication publication = publications
                            .findByAcademicYearIdAndClassId(
                                    summary.getAcademicYearId(), summary.getClassId())
                            .filter(row -> "PUBLISHED".equals(row.getStatus()))
                            .orElse(null);
                    if (publication == null || !publishedVersion(publication, summary)) return null;
                    SchoolClass schoolClass = classes.computeIfAbsent(
                            summary.getClassId(), structure::getClass);
                    AcademicYear year = years.get(summary.getAcademicYearId());
                    return toResult(summary, publication, year, schoolClass);
                })
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(StudentYearResult::publishedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    public YearResultFile export(String studentId, String academicYearId,
                                 String requestedFormat, CurrentUser actor) {
        StudentYearResult result = resultsForStudent(studentId, actor).stream()
                .filter(row -> academicYearId.equals(row.academicYearId()))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("Kết quả năm học đã công bố"));
        String format = requestedFormat == null ? "PDF" : requestedFormat.trim().toUpperCase();
        YearResultFile file = switch (format) {
            case "PDF" -> new YearResultFile(fileName(result, "pdf"),
                    "application/pdf", pdf.render(result));
            case "XLSX", "EXCEL" -> new YearResultFile(fileName(result, "xlsx"),
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    excel.export(result));
            default -> throw ApiException.badRequest("Định dạng chỉ nhận PDF hoặc XLSX");
        };
        audit.record(actor.id(), users.fullNameOf(actor.id()), actor.role(),
                "EXPORT_YEAR_RESULT", "academic", "student_yearly_summary",
                result.summaryId(), "Định dạng=" + format + "; học sinh=" + studentId);
        return file;
    }

    private StudentYearResult toResult(StudentYearlySummary summary,
                                       YearResultPublication publication,
                                       AcademicYear year,
                                       SchoolClass schoolClass) {
        String nextClassCode = null;
        if (summary.getNextClassId() != null && !summary.getNextClassId().isBlank()) {
            try {
                nextClassCode = structure.getClass(summary.getNextClassId()).getCode();
            } catch (ApiException ignored) {
                // Historical result remains readable even if a later class was removed.
            }
        }
        return new StudentYearResult(
                summary.getId(), summary.getAcademicYearId(),
                year == null ? summary.getAcademicYearId() : display(year.getName(), year.getCode()),
                summary.getClassId(), schoolClass.getCode(),
                display(schoolClass.getName(), schoolClass.getCode()),
                summary.getStudentId(), summary.getStudentCode(), summary.getStudentName(),
                summary.getYearlyAverage(), summary.getAttendanceRate(),
                summary.getConductGrade(), summary.getResult(), summary.getReason(),
                summary.getProgressionStatus(), summary.getNextClassId(), nextClassCode,
                readJson(summary.getSemesterResultsJson(), SEMESTERS),
                readJson(summary.getSubjectResultsJson(), SUBJECTS),
                summary.getFinalizedAt(), publication.getPublishedAt());
    }

    private void assertCanView(String studentId, CurrentUser actor) {
        if (actor.isAdmin()) return;
        if ("STUDENT".equals(actor.role()) && actor.id().equals(studentId)) return;
        if ("PARENT".equals(actor.role())) {
            users.assertParentOf(actor.id(), studentId);
            return;
        }
        throw ApiException.forbidden("Không có quyền xem kết quả học sinh này");
    }

    private AcademicYear requireYear(String id) {
        return structure.listYears().stream()
                .filter(year -> id.equals(year.getId()))
                .findFirst().orElseThrow(() -> ApiException.notFound("Năm học"));
    }

    private SchoolClass requireClass(AcademicYear year, String classId) {
        SchoolClass schoolClass = structure.getClass(classId);
        if (!year.getId().equals(schoolClass.getAcademicYearId())) {
            throw ApiException.badRequest("Lớp không thuộc năm học đã chọn");
        }
        return schoolClass;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Không thể lưu snapshot kết quả năm học", exception);
        }
    }

    private <T> List<T> readJson(String json, TypeReference<List<T>> type) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    private String fileName(StudentYearResult result, String extension) {
        String student = value(result.studentCode()).replaceAll("[^A-Za-z0-9_-]", "_");
        String year = value(result.academicYearName()).replaceAll("[^A-Za-z0-9_-]", "_");
        return "phieu-tong-ket-" + student + "-" + year + "." + extension;
    }

    private String display(String name, String fallback) {
        return name == null || name.isBlank() ? fallback : name;
    }

    private String value(String text) {
        return text == null ? "" : text;
    }

    private String requireReason(String reason, String label) {
        String value = reason == null ? "" : reason.trim();
        if (value.length() < 10) {
            throw ApiException.badRequest(label + " phải có ít nhất 10 ký tự");
        }
        return value;
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private boolean publishedVersion(YearResultPublication publication,
                                     StudentYearlySummary summary) {
        if (publication.getPublishedAt() == null) return false;
        Instant changedAt = summary.getUpdatedAt() == null
                ? summary.getFinalizedAt() : summary.getUpdatedAt();
        return changedAt == null || !changedAt.isAfter(publication.getPublishedAt());
    }
}
