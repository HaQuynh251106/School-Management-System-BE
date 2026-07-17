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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    public AssignmentService(AssignmentRepository assignments, AssignmentSubmissionRepository submissions,
                             StructureService structure, UserService users, DomainEventPublisher events,
                             FileStorageService files, TeachingAssignmentService teachingAssignments,
                             AuditService audit) {
        this.assignments = assignments;
        this.submissions = submissions;
        this.structure = structure;
        this.users = users;
        this.events = events;
        this.files = files;
        this.teachingAssignments = teachingAssignments;
        this.audit = audit;
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
        if (!teachingAssignments.teacherAssignedToClassSubject(teacherId, r.classId(), r.subjectId())) {
            throw ApiException.forbidden("Ban chi co the giao bai cho lop va mon dang duoc phan cong");
        }
        boolean publish = Boolean.TRUE.equals(r.publishNow());
        StoredFile attachment = null;
        if (r.attachmentFileId() != null && !r.attachmentFileId().isBlank()) {
            attachment = files.requireReadyOwnedFile(r.attachmentFileId(), "ASSIGNMENT", teacherId);
        }
        Assignment a = assignments.save(Assignment.builder()
                .id(r.id() == null || r.id().isBlank() ? Ids.gen("asg") : r.id())
                .classId(r.classId()).subjectId(r.subjectId())
                .subjectName(structure.subjectName(r.subjectId()))
                .teacherId(teacherId).teacherName(users.fullNameOf(teacherId))
                .title(r.title()).description(r.description())
                .status(publish ? "PUBLISHED" : "DRAFT")
                .deadline(r.deadline()).allowLate(Boolean.TRUE.equals(r.allowLate()))
                .attachmentName(attachment == null ? r.attachmentName() : attachment.getOriginalName())
                .attachmentFileId(attachment == null ? null : attachment.getId())
                .attachmentFileKey(attachment == null ? null : attachment.getFileKey())
                .attachmentContentType(attachment == null ? null : attachment.getContentType())
                .attachmentSizeBytes(attachment == null ? null : attachment.getSizeBytes())
                .createdAt(Instant.now()).build());
        if (publish) publishAssignmentEvent(a);
        return a;
    }

    @Transactional
    public Assignment publish(String id, String teacherId, boolean isAdmin) {
        Assignment a = get(id);
        requireTeacherOwnsAssignment(id, teacherId, isAdmin);
        if ("PUBLISHED".equals(a.getStatus())) return a;
        a.setStatus("PUBLISHED");
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
        boolean hasNewAttachment = r.attachmentFileId() != null && !r.attachmentFileId().isBlank();
        boolean hasExistingAttachment = s.getAttachmentFileId() != null && !s.getAttachmentFileId().isBlank();
        if ((content == null || content.isBlank()) && !hasNewAttachment && !hasExistingAttachment) {
            throw ApiException.badRequest("Nhap noi dung bai lam hoac dinh kem file truoc khi nop");
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
        return submissions.save(s);
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
}
