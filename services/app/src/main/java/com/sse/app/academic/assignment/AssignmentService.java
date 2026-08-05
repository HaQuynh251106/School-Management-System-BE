package com.sse.app.academic.assignment;

import com.sse.app.academic.assignment.AssignmentDtos.*;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.academic.teaching.TeachingAssignmentService;
import com.sse.app.audit.AuditService;
import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.event.DomainEventPublisher;
import com.sse.app.file.FileDtos.PresignDownloadResponse;
import com.sse.app.file.FileStorageService;
import com.sse.app.file.StoredFile;
import com.sse.app.identity.User;
import com.sse.app.identity.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

/** B5 + C4: vòng đời bài tập (flowchart 2.7). */
@Service
public class AssignmentService {

    private final AssignmentRepository assignments;
    private final AssignmentSubmissionRepository submissions;
    private final StructureService structure;
    private final UserService users;
    private final DomainEventPublisher events;
    private final FileStorageService files;
    private final TeachingAssignmentService teachingAssignments;
    private final AuditService audit;
    private final AssignmentSubmissionVersionRepository versions;
    private final SubmissionResubmissionRequestRepository resubmissionRequests;
    private final AssignmentSubmissionExcelExporter excel;

    public AssignmentService(AssignmentRepository assignments, AssignmentSubmissionRepository submissions,
                             StructureService structure, UserService users, DomainEventPublisher events,
                             FileStorageService files, TeachingAssignmentService teachingAssignments,
                             AuditService audit) {
        this(assignments, submissions, structure, users, events, files,
                teachingAssignments, audit, null, null, null);
    }

    @Autowired
    public AssignmentService(AssignmentRepository assignments,
                             AssignmentSubmissionRepository submissions,
                             StructureService structure, UserService users,
                             DomainEventPublisher events, FileStorageService files,
                             TeachingAssignmentService teachingAssignments,
                             AuditService audit,
                             AssignmentSubmissionVersionRepository versions,
                             SubmissionResubmissionRequestRepository resubmissionRequests,
                             AssignmentSubmissionExcelExporter excel) {
        this.assignments = assignments;
        this.submissions = submissions;
        this.structure = structure;
        this.users = users;
        this.events = events;
        this.files = files;
        this.teachingAssignments = teachingAssignments;
        this.audit = audit;
        this.versions = versions;
        this.resubmissionRequests = resubmissionRequests;
        this.excel = excel;
    }

    public List<Assignment> list(String classId, String teacherId, String status, boolean onlyPublished) {
        List<Assignment> base;
        if (classId != null)        base = assignments.findByClassId(classId);
        else if (teacherId != null) base = assignments.findByTeacherId(teacherId);
        else base = assignments.findAll();
        return base.stream()
                .filter(a -> status == null || status.equals(a.getStatus()))
                .filter(a -> !onlyPublished || "PUBLISHED".equals(a.getStatus()))
                .toList();
    }

    public Assignment get(String id) {
        return assignments.findById(id).orElseThrow(() -> ApiException.notFound("Bài tập"));
    }

    @Transactional
    public Assignment create(CreateAssignmentRequest r, String teacherId) {
        String title = r.title() == null ? null : r.title().trim();
        if (title == null || title.isBlank()) {
            throw ApiException.badRequest("Bắt buộc nhập tên đề bài");
        }
        if (r.attachmentFileId() == null || r.attachmentFileId().isBlank()) {
            throw ApiException.badRequest("Bắt buộc đính kèm file đề bài");
        }
        if (!teachingAssignments.teacherAssignedToClassSubject(teacherId, r.classId(), r.subjectId())) {
            throw ApiException.forbidden("Ban chi co the giao bai cho lop va mon dang duoc phan cong");
        }
        boolean publish = Boolean.TRUE.equals(r.publishNow());
        StoredFile attachment = files.requireReadyOwnedFile(r.attachmentFileId(), "ASSIGNMENT", teacherId);
        Assignment a = assignments.save(Assignment.builder()
                .id(r.id() == null || r.id().isBlank() ? Ids.gen("asg") : r.id())
                .classId(r.classId()).subjectId(r.subjectId())
                .subjectName(structure.subjectName(r.subjectId()))
                .teacherId(teacherId).teacherName(users.fullNameOf(teacherId))
                .title(title).description(r.description())
                .status(publish ? "PUBLISHED" : "DRAFT")
                .deadline(r.deadline()).allowLate(Boolean.TRUE.equals(r.allowLate()))
                .attachmentName(attachment == null ? r.attachmentName() : attachment.getOriginalName())
                .attachmentFileId(attachment == null ? null : attachment.getId())
                .attachmentFileKey(attachment == null ? null : attachment.getFileKey())
                .attachmentContentType(attachment == null ? null : attachment.getContentType())
                .attachmentSizeBytes(attachment == null ? null : attachment.getSizeBytes())
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .reminderCount(0).build());
        if (publish) publishAssignmentEvent(a);
        return a;
    }

    @Transactional
    public Assignment publish(String id, String teacherId, boolean isAdmin) {
        Assignment a = get(id);
        requireTeacherOwnsAssignment(id, teacherId, isAdmin);
        if ("PUBLISHED".equals(a.getStatus())) return a;
        if (a.getAttachmentFileId() == null || a.getAttachmentFileId().isBlank()) {
            throw ApiException.badRequest("Bắt buộc đính kèm file đề bài trước khi phát hành");
        }
        if (!teachingAssignments.teacherAssignedToClassSubject(
                a.getTeacherId(), a.getClassId(), a.getSubjectId())) {
            throw ApiException.forbidden("Không thể phát hành vì giáo viên không còn được phân công lớp và môn này");
        }
        a.setStatus("PUBLISHED");
        a.setUpdatedAt(Instant.now());
        assignments.save(a);
        publishAssignmentEvent(a);
        return a;
    }

    private void publishAssignmentEvent(Assignment a) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("classId", a.getClassId());
        payload.put("subjectName", a.getSubjectName());
        payload.put("title", a.getTitle());
        payload.put("deadline", a.getDeadline() == null ? "" : String.valueOf(a.getDeadline()));
        payload.put("message", "Môn " + a.getSubjectName()
                + (a.getDeadline() != null ? " - hạn " + a.getDeadline() : ""));
        events.publish("academic.assignment.published", a.getTeacherId(), "assignment", a.getId(), payload);
    }

    @Transactional
    public AssignmentSubmission submit(String assignmentId, String studentId, SubmitRequest r) {
        Assignment a = get(assignmentId);
        if (!"PUBLISHED".equals(a.getStatus())) throw ApiException.badRequest("Bài tập chưa mở nộp");
        User student = users.getById(studentId);
        if (!a.getClassId().equals(student.getClassId())) {
            throw ApiException.forbidden("Bạn chỉ có thể nộp bài tập của lớp mình");
        }
        String content = r.content() == null ? null : r.content().trim();
        String status = "SUBMITTED";
        if (a.getDeadline() != null && Instant.now().isAfter(a.getDeadline())) {
            if (!a.isAllowLate()) throw ApiException.badRequest("Đã quá hạn nộp bài");
            status = "LATE";
        }
        final String st = status;
        AssignmentSubmission s = submissions.findByAssignmentIdAndStudentId(assignmentId, studentId)
                .orElseGet(() -> AssignmentSubmission.builder().id(Ids.gen("sub")).build());
        SubmissionResubmissionRequest resubmission = null;
        if ("GRADED".equals(s.getStatus())) {
            requireAdvancedRepositories();
            resubmission = resubmissionRequests
                    .findFirstBySubmissionIdAndStatusOrderByRequestedAtDesc(
                            s.getId(), "OPEN")
                    .orElseThrow(() -> ApiException.conflict(
                            "Bài đã được chấm; giáo viên phải cho phép nộp lại"));
            if (resubmission.getAllowedUntil() != null
                    && Instant.now().isAfter(resubmission.getAllowedUntil())) {
                resubmission.setStatus("EXPIRED");
                resubmissionRequests.save(resubmission);
                throw ApiException.conflict("Thời hạn nộp lại đã hết");
            }
        }
        boolean hasNewAttachment = r.attachmentFileId() != null && !r.attachmentFileId().isBlank();
        boolean hasExistingAttachment = s.getAttachmentFileId() != null && !s.getAttachmentFileId().isBlank();
        if (!hasNewAttachment && !hasExistingAttachment) {
            throw ApiException.badRequest("Bắt buộc đính kèm file bài làm trước khi nộp");
        }
        s.setAssignmentId(assignmentId);
        s.setStudentId(studentId);
        s.setStudentName(users.fullNameOf(studentId));
        s.setStatus(st);
        s.setContent(content);
        if (hasNewAttachment) {
            StoredFile file = files.requireReadyOwnedFile(r.attachmentFileId(), "SUBMISSION", studentId);
            s.setAttachmentFileId(file.getId());
            s.setAttachmentFileKey(file.getFileKey());
            s.setAttachmentName(file.getOriginalName());
            s.setAttachmentContentType(file.getContentType());
            s.setAttachmentSizeBytes(file.getSizeBytes());
        } else if (!hasExistingAttachment) {
            s.setAttachmentFileId(null);
            s.setAttachmentFileKey(null);
            s.setAttachmentName(null);
            s.setAttachmentContentType(null);
            s.setAttachmentSizeBytes(null);
        }
        s.setSubmittedAt(Instant.now());
        int versionNo = s.getCurrentVersion() == null
                ? 1 : s.getCurrentVersion() + 1;
        s.setCurrentVersion(versionNo);
        s.setScore(null);
        s.setFeedback(null);
        s.setGradedBy(null);
        s.setGradedAt(null);
        AssignmentSubmission saved = submissions.save(s);
        if (versions != null) {
            versions.save(AssignmentSubmissionVersion.builder()
                    .id(Ids.gen("asv"))
                    .submissionId(saved.getId())
                    .versionNo(versionNo)
                    .content(saved.getContent())
                    .attachmentName(saved.getAttachmentName())
                    .attachmentFileId(saved.getAttachmentFileId())
                    .attachmentContentType(saved.getAttachmentContentType())
                    .attachmentSizeBytes(saved.getAttachmentSizeBytes())
                    .submittedBy(studentId)
                    .submittedAt(saved.getSubmittedAt())
                    .build());
        }
        if (resubmission != null) {
            resubmission.setStatus("USED");
            resubmission.setUsedAt(Instant.now());
            resubmissionRequests.save(resubmission);
        }
        return saved;
    }

    @Transactional
    public AssignmentSubmission grade(String submissionId, GradeSubmissionRequest r, String gradedBy) {
        return grade(submissionId, r, gradedBy, true);
    }

    @Transactional
    public AssignmentSubmission grade(String submissionId, GradeSubmissionRequest r, String gradedBy, boolean isAdmin) {
        AssignmentSubmission s = submissions.findById(submissionId)
                .orElseThrow(() -> ApiException.notFound("Bài nộp"));
        requireTeacherOwnsAssignment(s.getAssignmentId(), gradedBy, isAdmin);
        if (r.score() == null || r.score() < 0 || r.score() > 10)
            throw ApiException.badRequest("Diem phai trong khoang 0 den 10");

        boolean isCorrection = "GRADED".equals(s.getStatus());
        String reason = r.reason() == null ? null : r.reason().trim();
        if (isCorrection && (reason == null || reason.isBlank())) {
            throw ApiException.badRequest("Phải nhập lý do khi sửa kết quả đã chấm");
        }
        Double oldScore = s.getScore();
        String oldFeedback = s.getFeedback();

        s.setScore(r.score());
        s.setFeedback(r.feedback() == null || r.feedback().isBlank() ? null : r.feedback().trim());
        s.setStatus("GRADED");
        s.setGradedBy(gradedBy);
        s.setGradedAt(Instant.now());
        submissions.save(s);

        if (isCorrection) {
            User actor = users.getById(gradedBy);
            audit.record(actor.getId(), actor.getFullName(), actor.getRole(), "UPDATE", "academic",
                    "assignment_submission", s.getId(), gradeCorrectionDetail(s, oldScore, oldFeedback, reason));
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("studentId", s.getStudentId());
        payload.put("assignmentId", s.getAssignmentId());
        payload.put("score", r.score() == null ? "" : r.score());
        payload.put("message", "Điểm: " + (r.score() == null ? "-" : r.score()));
        events.publish("academic.submission.graded", gradedBy, "submission", s.getId(), payload);
        return s;
    }

    @Transactional
    public List<AssignmentSubmission> batchGrade(
            BatchGradeRequest request, String gradedBy, boolean isAdmin) {
        if (request == null || request.entries() == null
                || request.entries().isEmpty()) {
            throw ApiException.badRequest("Danh sách chấm bài không được trống");
        }
        List<AssignmentSubmission> result = new ArrayList<>();
        for (BatchGradeEntry entry : request.entries()) {
            result.add(grade(entry.submissionId(),
                    new GradeSubmissionRequest(entry.score(), entry.feedback(), entry.reason()),
                    gradedBy, isAdmin));
        }
        return result;
    }

    @Transactional
    public SubmissionResubmissionRequest requestResubmission(
            String submissionId, RequestResubmissionRequest request,
            String actorId, boolean isAdmin) {
        requireAdvancedRepositories();
        AssignmentSubmission submission = submissions.findById(submissionId)
                .orElseThrow(() -> ApiException.notFound("Bài nộp"));
        requireTeacherOwnsAssignment(submission.getAssignmentId(), actorId, isAdmin);
        if (!"GRADED".equals(submission.getStatus())) {
            throw ApiException.conflict("Chỉ yêu cầu nộp lại sau khi bài đã được chấm");
        }
        if (request.allowedUntil() != null
                && !request.allowedUntil().isAfter(Instant.now())) {
            throw ApiException.badRequest("Hạn nộp lại phải ở tương lai");
        }
        resubmissionRequests
                .findFirstBySubmissionIdAndStatusOrderByRequestedAtDesc(
                        submissionId, "OPEN")
                .ifPresent(row -> {
                    row.setStatus("CANCELLED");
                    resubmissionRequests.save(row);
                });
        SubmissionResubmissionRequest row =
                resubmissionRequests.save(SubmissionResubmissionRequest.builder()
                        .id(Ids.gen("srr"))
                        .submissionId(submissionId)
                        .assignmentId(submission.getAssignmentId())
                        .studentId(submission.getStudentId())
                        .reason(request.reason().trim())
                        .status("OPEN")
                        .allowedUntil(request.allowedUntil())
                        .requestedBy(actorId)
                        .requestedAt(Instant.now())
                        .build());
        events.publish("academic.submission.resubmission_requested",
                actorId, "submission", submissionId,
                Map.of("studentId", submission.getStudentId(),
                        "assignmentId", submission.getAssignmentId(),
                        "message", "Giáo viên yêu cầu nộp lại bài: "
                                + request.reason().trim()));
        audit.record(actorId, users.fullNameOf(actorId),
                isAdmin ? "ADMIN" : "TEACHER",
                "REQUEST_RESUBMISSION", "academic",
                "assignment_submission", submissionId,
                "lý do=" + request.reason().trim()
                        + "; hạn=" + request.allowedUntil());
        return row;
    }

    public List<SubmissionResubmissionRequest> resubmissionRequests(
            String submissionId, String actorId, boolean isAdmin) {
        requireAdvancedRepositories();
        AssignmentSubmission submission = submissions.findById(submissionId)
                .orElseThrow(() -> ApiException.notFound("Bài nộp"));
        if (!submission.getStudentId().equals(actorId)) {
            requireTeacherOwnsAssignment(
                    submission.getAssignmentId(), actorId, isAdmin);
        }
        return resubmissionRequests
                .findBySubmissionIdOrderByRequestedAtDesc(submissionId);
    }

    public List<AssignmentSubmissionVersion> submissionVersions(
            String submissionId, String actorId, boolean isAdmin) {
        requireAdvancedRepositories();
        AssignmentSubmission submission = submissions.findById(submissionId)
                .orElseThrow(() -> ApiException.notFound("Bài nộp"));
        if (!submission.getStudentId().equals(actorId)) {
            requireTeacherOwnsAssignment(
                    submission.getAssignmentId(), actorId, isAdmin);
        }
        return versions.findBySubmissionIdOrderByVersionNoDesc(submissionId);
    }

    @Transactional
    public AssignmentReminderResponse remindDue(
            String assignmentId, String actorId, boolean isAdmin) {
        Assignment assignment = get(assignmentId);
        requireTeacherOwnsAssignment(assignmentId, actorId, isAdmin);
        if (!"PUBLISHED".equals(assignment.getStatus())) {
            throw ApiException.conflict("Chỉ nhắc hạn bài tập đã phát hành");
        }
        List<String> submitted = submissions.findByAssignmentId(assignmentId)
                .stream().map(AssignmentSubmission::getStudentId).toList();
        List<String> pendingStudents = users.list(
                        "STUDENT", null, assignment.getClassId()).stream()
                .map(com.sse.app.identity.UserDto::id)
                .filter(id -> !submitted.contains(id))
                .toList();
        Instant now = Instant.now();
        assignment.setLastReminderAt(now);
        assignment.setReminderCount(assignment.getReminderCount() == null
                ? 1 : assignment.getReminderCount() + 1);
        assignment.setUpdatedAt(now);
        assignments.save(assignment);
        for (String studentId : pendingStudents) {
            events.publish("academic.assignment.deadline_reminder",
                    actorId, "assignment", assignmentId,
                    Map.of("studentId", studentId,
                            "title", assignment.getTitle(),
                            "deadline", assignment.getDeadline() == null
                                    ? "" : assignment.getDeadline().toString(),
                            "message", "Bạn chưa nộp bài "
                                    + assignment.getTitle()));
        }
        return new AssignmentReminderResponse(
                assignmentId, pendingStudents.size(), now);
    }

    public AssignmentExportFile exportSubmissions(
            String assignmentId, String actorId, boolean isAdmin) {
        if (excel == null) {
            throw new IllegalStateException("Assignment exporter is unavailable");
        }
        Assignment assignment = get(assignmentId);
        requireTeacherOwnsAssignment(assignmentId, actorId, isAdmin);
        byte[] content = excel.export(
                assignment, submissions.findByAssignmentId(assignmentId));
        return new AssignmentExportFile(
                "submissions-" + assignmentId + ".xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                content);
    }

    private String gradeCorrectionDetail(AssignmentSubmission submission, Double oldScore,
                                         String oldFeedback, String reason) {
        return "Sửa kết quả bài tập; học sinh=" + auditValue(submission.getStudentName(), 80)
                + " (" + submission.getStudentId() + ")"
                + "; điểm=" + (oldScore == null ? "-" : oldScore) + " -> " + submission.getScore()
                + "; nhận xét=" + auditValue(oldFeedback, 160) + " -> " + auditValue(submission.getFeedback(), 160)
                + "; lý do=" + auditValue(reason, 350);
    }

    private String auditValue(String value, int maxLength) {
        if (value == null || value.isBlank()) return "-";
        String normalized = value.trim().replaceAll("\\s+", " ");
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength - 3) + "...";
    }

    public List<AssignmentSubmission> submissionsOf(String assignmentId) {
        return submissions.findByAssignmentId(assignmentId);
    }

    public List<AssignmentSubmission> submissionsOf(String assignmentId, String teacherId, boolean isAdmin) {
        requireTeacherOwnsAssignment(assignmentId, teacherId, isAdmin);
        return submissionsOf(assignmentId);
    }

    public PresignDownloadResponse submissionDownloadUrl(String assignmentId, String submissionId,
                                                          String teacherId, boolean isAdmin) {
        requireTeacherOwnsAssignment(assignmentId, teacherId, isAdmin);
        AssignmentSubmission submission = submissions.findById(submissionId)
                .filter(s -> assignmentId.equals(s.getAssignmentId()))
                .orElseThrow(() -> ApiException.notFound("Bài nộp"));
        if (submission.getAttachmentFileId() == null || submission.getAttachmentFileId().isBlank()) {
            throw ApiException.badRequest("Submission has no attachment");
        }
        return files.createDownloadUrlForAuthorizedAccess(submission.getAttachmentFileId());
    }

    public List<AssignmentSubmission> submissionsByStudent(String studentId) {
        return submissions.findByStudentId(studentId);
    }

    public List<Assignment> assignmentsForStudent(String studentId) {
        return list(users.getById(studentId).getClassId(), null, null, true);
    }

    public PresignDownloadResponse assignmentAttachmentDownloadUrlForStudent(String assignmentId, String studentId) {
        Assignment assignment = requirePublishedAssignmentForStudent(assignmentId, studentId);
        if (assignment.getAttachmentFileId() == null || assignment.getAttachmentFileId().isBlank()) {
            throw ApiException.badRequest("Assignment has no attachment");
        }
        return files.createDownloadUrlForAuthorizedAccess(assignment.getAttachmentFileId());
    }

    public PresignDownloadResponse submissionDownloadUrlForStudent(String assignmentId, String submissionId, String studentId) {
        requirePublishedAssignmentForStudent(assignmentId, studentId);
        AssignmentSubmission submission = submissions.findById(submissionId)
                .filter(s -> assignmentId.equals(s.getAssignmentId()) && studentId.equals(s.getStudentId()))
                .orElseThrow(() -> ApiException.notFound("Bai nop"));
        if (submission.getAttachmentFileId() == null || submission.getAttachmentFileId().isBlank()) {
            throw ApiException.badRequest("Submission has no attachment");
        }
        return files.createDownloadUrlForAuthorizedAccess(submission.getAttachmentFileId());
    }

    private Assignment requirePublishedAssignmentForStudent(String assignmentId, String studentId) {
        Assignment assignment = get(assignmentId);
        if (!"PUBLISHED".equals(assignment.getStatus())) {
            throw ApiException.forbidden("Assignment is not published");
        }
        User student = users.getById(studentId);
        if (!assignment.getClassId().equals(student.getClassId())) {
            throw ApiException.forbidden("You cannot access this assignment");
        }
        return assignment;
    }

    private void requireTeacherOwnsAssignment(String assignmentId, String teacherId, boolean isAdmin) {
        Assignment assignment = get(assignmentId);
        if (!isAdmin && !teacherId.equals(assignment.getTeacherId())) {
            throw ApiException.forbidden("You can only access submissions for assignments you created");
        }
    }

    private void requireAdvancedRepositories() {
        if (versions == null || resubmissionRequests == null) {
            throw new IllegalStateException(
                    "Assignment advanced repositories are unavailable");
        }
    }
}
