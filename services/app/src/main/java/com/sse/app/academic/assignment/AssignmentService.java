package com.sse.app.academic.assignment;

import com.sse.app.academic.assignment.AssignmentDtos.*;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.academic.timetable.TimetableService;
import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.file.FileStorageService;
import com.sse.app.file.StoredFile;
import com.sse.app.identity.User;
import com.sse.app.identity.UserDto;
import com.sse.app.identity.UserService;
import com.sse.app.notification.NotificationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** B5 + C4: vòng đời giao bài, đính kèm tệp, nộp bài và chấm bài. */
@Service
public class AssignmentService {

    private final AssignmentRepository assignments;
    private final AssignmentSubmissionRepository submissions;
    private final StructureService structure;
    private final TimetableService timetable;
    private final UserService users;
    private final NotificationService notifications;
    private final FileStorageService storage;

    public AssignmentService(AssignmentRepository assignments, AssignmentSubmissionRepository submissions,
                             StructureService structure, TimetableService timetable, UserService users,
                             NotificationService notifications, FileStorageService storage) {
        this.assignments = assignments;
        this.submissions = submissions;
        this.structure = structure;
        this.timetable = timetable;
        this.users = users;
        this.notifications = notifications;
        this.storage = storage;
    }

    public List<Assignment> list(String classId, String teacherId, String status, boolean onlyPublished) {
        List<Assignment> base;
        if (classId != null)        base = assignments.findByClassId(classId);
        else if (teacherId != null) base = assignments.findByTeacherId(teacherId);
        else base = assignments.findAll();

        Map<String, Integer> classSizes = new HashMap<>();
        return base.stream()
                .filter(a -> status == null || status.equals(a.getStatus()))
                .filter(a -> !onlyPublished || "PUBLISHED".equals(a.getStatus()))
                .peek(a -> {
                    a.setSubmissionCount((int) submissions.countByAssignmentId(a.getId()));
                    a.setStudentCount(classSizes.computeIfAbsent(a.getClassId(), id ->
                            users.list("STUDENT", null, id).size()));
                })
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .toList();
    }

    public Assignment get(String id) {
        return assignments.findById(id).orElseThrow(() -> ApiException.notFound("Bài tập"));
    }

    @Transactional
    public Assignment create(CreateAssignmentRequest request, String actorId, String actorRole) {
        assertTeacherAssignment(actorId, actorRole, request.classId(), request.subjectId());
        String subjectName = structure.requireSubjectName(request.subjectId());
        if (request.deadline() != null && !request.deadline().isAfter(Instant.now())) {
            throw ApiException.badRequest("Hạn nộp phải ở tương lai");
        }
        StoredFile attachment = ownedFile(request.attachmentFileId(), actorId);
        boolean publish = Boolean.TRUE.equals(request.publishNow());

        Assignment assignment = assignments.save(Assignment.builder()
                .id(request.id() == null || request.id().isBlank() ? Ids.gen("asg") : request.id())
                .classId(request.classId()).subjectId(request.subjectId()).subjectName(subjectName)
                .teacherId(actorId).teacherName(users.fullNameOf(actorId))
                .title(request.title().trim()).description(clean(request.description()))
                .status(publish ? "PUBLISHED" : "DRAFT")
                .deadline(request.deadline()).allowLate(Boolean.TRUE.equals(request.allowLate()))
                .attachmentFileId(attachment == null ? null : attachment.getId())
                .attachmentName(attachment == null ? null : attachment.getOriginalName())
                .createdAt(Instant.now()).build());
        if (publish) notifyClass(assignment);
        return assignment;
    }

    @Transactional
    public Assignment publish(String id, String actorId, String actorRole) {
        Assignment assignment = get(id);
        assertCanManage(assignment, actorId, actorRole);
        if ("PUBLISHED".equals(assignment.getStatus())) return assignment;
        if (assignment.getDeadline() != null && !assignment.getDeadline().isAfter(Instant.now())) {
            throw ApiException.badRequest("Không thể phát hành bài tập đã quá hạn");
        }
        assignment.setStatus("PUBLISHED");
        assignments.save(assignment);
        notifyClass(assignment);
        return assignment;
    }

    private void notifyClass(Assignment assignment) {
        List<String> studentIds = users.list("STUDENT", null, assignment.getClassId()).stream()
                .map(UserDto::id).toList();
        notifications.notifyUsers(studentIds, "ASSIGNMENT", "Bài tập mới: " + assignment.getTitle(),
                "Môn " + assignment.getSubjectName()
                        + (assignment.getDeadline() != null ? " — hạn " + assignment.getDeadline() : ""),
                "ASSIGNMENT", assignment.getId());
    }

    @Transactional
    public AssignmentSubmission submit(String assignmentId, String studentId, SubmitRequest request) {
        Assignment assignment = get(assignmentId);
        User student = users.getById(studentId);
        if (!"STUDENT".equals(student.getRole()) || !Objects.equals(student.getClassId(), assignment.getClassId())) {
            throw ApiException.forbidden("Bài tập không thuộc lớp của học sinh");
        }
        if (!"PUBLISHED".equals(assignment.getStatus())) throw ApiException.badRequest("Bài tập chưa mở nộp");

        String status = "SUBMITTED";
        if (assignment.getDeadline() != null && Instant.now().isAfter(assignment.getDeadline())) {
            if (!assignment.isAllowLate()) throw ApiException.badRequest("Đã quá hạn nộp bài");
            status = "LATE";
        }

        StoredFile attachment = ownedFile(request.attachmentFileId(), studentId);
        String content = clean(request.content());
        if (content == null && attachment == null) {
            throw ApiException.badRequest("Cần nhập nội dung hoặc đính kèm tệp bài làm");
        }

        AssignmentSubmission submission = submissions.findByAssignmentIdAndStudentId(assignmentId, studentId)
                .orElseGet(() -> AssignmentSubmission.builder().id(Ids.gen("sub")).build());
        if ("GRADED".equals(submission.getStatus())) {
            throw ApiException.badRequest("Bài đã được chấm, không thể nộp lại");
        }
        submission.setAssignmentId(assignmentId);
        submission.setStudentId(studentId);
        submission.setStudentName(student.getFullName());
        submission.setStatus(status);
        submission.setContent(content);
        submission.setAttachmentFileId(attachment == null ? null : attachment.getId());
        submission.setAttachmentName(attachment == null ? null : attachment.getOriginalName());
        submission.setSubmittedAt(Instant.now());
        AssignmentSubmission saved = submissions.save(submission);

        notifications.notifyUser(assignment.getTeacherId(), "ASSIGNMENT", "Có bài nộp mới",
                student.getFullName() + " đã nộp: " + assignment.getTitle(), "SUBMISSION", saved.getId());
        return saved;
    }

    @Transactional
    public AssignmentSubmission grade(String submissionId, GradeSubmissionRequest request,
                                      String actorId, String actorRole) {
        AssignmentSubmission submission = submissions.findById(submissionId)
                .orElseThrow(() -> ApiException.notFound("Bài nộp"));
        Assignment assignment = get(submission.getAssignmentId());
        assertCanManage(assignment, actorId, actorRole);
        if (request.score() == null || !Double.isFinite(request.score())
                || request.score() < 0 || request.score() > 10) {
            throw ApiException.badRequest("Điểm phải trong 0..10");
        }
        submission.setScore(request.score());
        submission.setFeedback(clean(request.feedback()));
        submission.setStatus("GRADED");
        submission.setGradedBy(actorId);
        submission.setGradedAt(Instant.now());
        submissions.save(submission);
        notifications.notifyUser(submission.getStudentId(), "ASSIGNMENT", "Bài tập đã được chấm",
                assignment.getTitle() + " — Điểm: " + request.score(), "SUBMISSION", submission.getId());
        return submission;
    }

    public List<AssignmentSubmission> submissionsOf(String assignmentId, String actorId, String actorRole) {
        assertCanManage(get(assignmentId), actorId, actorRole);
        return submissions.findByAssignmentId(assignmentId).stream()
                .sorted((a, b) -> b.getSubmittedAt().compareTo(a.getSubmittedAt()))
                .toList();
    }

    public List<AssignmentSubmission> submissionsByStudent(String studentId) {
        return submissions.findByStudentId(studentId);
    }

    private void assertTeacherAssignment(String actorId, String actorRole, String classId, String subjectId) {
        structure.getClass(classId);
        if ("ADMIN".equals(actorRole)) return;
        boolean assigned = timetable.list(classId, null, null, null).stream().anyMatch(slot ->
                actorId.equals(slot.getTeacherId()) && subjectId.equals(slot.getSubjectId()));
        if (!assigned) throw ApiException.forbidden("Giáo viên không được phân công môn/lớp này");
    }

    private void assertCanManage(Assignment assignment, String actorId, String actorRole) {
        if (!"ADMIN".equals(actorRole) && !actorId.equals(assignment.getTeacherId())) {
            throw ApiException.forbidden("Không có quyền quản lý bài tập này");
        }
    }

    private StoredFile ownedFile(String fileId, String userId) {
        return fileId == null || fileId.isBlank() ? null : storage.ownedMetadata(fileId, userId);
    }

    private String clean(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
