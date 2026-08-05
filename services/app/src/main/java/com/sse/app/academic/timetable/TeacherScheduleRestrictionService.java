package com.sse.app.academic.timetable;

import com.sse.app.academic.structure.Semester;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.audit.AuditService;
import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.identity.User;
import com.sse.app.identity.UserService;
import com.sse.app.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.sse.app.academic.timetable.WorkloadPlanningDtos.*;

@Service
@RequiredArgsConstructor
public class TeacherScheduleRestrictionService {
    private static final Set<String> REVIEW_ACTIONS = Set.of("APPROVED", "REJECTED", "NEEDS_INFO");

    private final TeacherScheduleRestrictionRequestRepository requests;
    private final TeacherScheduleRestrictionHistoryRepository history;
    private final StructureService structure;
    private final UserService users;
    private final NotificationService notifications;
    private final AuditService audit;

    public List<ScheduleRestrictionResponse> mine(String teacherId, String semesterId) {
        structure.getSemester(semesterId);
        return requests.findByTeacherIdAndSemesterIdOrderByCreatedAtDesc(teacherId, semesterId)
                .stream().map(this::response).toList();
    }

    public List<ScheduleRestrictionResponse> list(String semesterId, String status) {
        structure.getSemester(semesterId);
        String normalized = cleanUpper(status);
        return requests.findBySemesterIdOrderByCreatedAtDesc(semesterId).stream()
                .filter(item -> normalized == null || normalized.equals(item.getStatus()))
                .map(this::response).toList();
    }

    @Transactional
    public ScheduleRestrictionResponse submit(String teacherId, SaveScheduleRestrictionRequest request) {
        structure.assertSemesterWritable(request.semesterId());
        User teacher = requireActiveTeacher(teacherId);
        ValidatedRequest value = validate(request);
        Instant now = Instant.now();
        TeacherScheduleRestrictionRequest item = TeacherScheduleRestrictionRequest.builder()
                .id(Ids.gen("tsr")).teacherId(teacherId).teacherName(teacher.getFullName())
                .semesterId(request.semesterId()).restrictedSlots(csv(value.slots()))
                .effectiveFrom(value.from()).effectiveTo(value.to()).reason(request.reason().trim())
                .evidenceUrl(clean(request.evidenceUrl())).status("PENDING")
                .submittedAt(now).createdAt(now).updatedAt(now).build();
        TeacherScheduleRestrictionRequest saved = requests.save(item);
        record(saved, "SUBMITTED", null, "PENDING", "Giáo viên gửi đề nghị mới", teacherId);
        notifications.notifyUsers(users.activeUserIdsByRole("ACADEMIC_STAFF"), "SCHEDULE_RESTRICTION",
                "Đề nghị hạn chế lịch dạy mới",
                teacher.getFullName() + " đã gửi đề nghị hạn chế " + value.slots().size()
                        + " khung giờ. Vui lòng kiểm tra và phản hồi.",
                "SCHEDULE_RESTRICTION", saved.getId());
        audit(teacherId, "SUBMIT_SCHEDULE_RESTRICTION", saved,
                "Gửi đề nghị hạn chế " + value.slots().size() + " khung giờ");
        return response(saved);
    }

    @Transactional
    public ScheduleRestrictionResponse revise(String teacherId, String id,
                                               SaveScheduleRestrictionRequest request) {
        TeacherScheduleRestrictionRequest item = requireOwned(id, teacherId);
        structure.assertSemesterWritable(item.getSemesterId());
        if (!Set.of("PENDING", "NEEDS_INFO").contains(item.getStatus())) {
            throw ApiException.conflict("Chỉ có thể cập nhật yêu cầu đang chờ duyệt hoặc cần bổ sung");
        }
        if (!item.getSemesterId().equals(request.semesterId())) {
            throw ApiException.badRequest("Không được thay đổi học kỳ của yêu cầu");
        }
        ValidatedRequest value = validate(request);
        String previous = item.getStatus();
        item.setRestrictedSlots(csv(value.slots()));
        item.setEffectiveFrom(value.from());
        item.setEffectiveTo(value.to());
        item.setReason(request.reason().trim());
        item.setEvidenceUrl(clean(request.evidenceUrl()));
        item.setStatus("PENDING");
        item.setDecisionNote(null);
        item.setReviewedAt(null);
        item.setReviewedBy(null);
        item.setSubmittedAt(Instant.now());
        item.setUpdatedAt(Instant.now());
        TeacherScheduleRestrictionRequest saved = requests.save(item);
        record(saved, "RESUBMITTED", previous, "PENDING", "Giáo viên cập nhật và gửi lại", teacherId);
        notifications.notifyUsers(users.activeUserIdsByRole("ACADEMIC_STAFF"), "SCHEDULE_RESTRICTION",
                "Đề nghị hạn chế lịch dạy đã được bổ sung",
                item.getTeacherName() + " đã cập nhật đề nghị và gửi lại để duyệt.",
                "SCHEDULE_RESTRICTION", saved.getId());
        audit(teacherId, "RESUBMIT_SCHEDULE_RESTRICTION", saved, "Cập nhật và gửi lại đề nghị");
        return response(saved);
    }

    @Transactional
    public ScheduleRestrictionResponse withdraw(String teacherId, String id) {
        TeacherScheduleRestrictionRequest item = requireOwned(id, teacherId);
        if (!Set.of("PENDING", "NEEDS_INFO").contains(item.getStatus())) {
            throw ApiException.conflict("Yêu cầu hiện không thể rút lại");
        }
        String previous = item.getStatus();
        item.setStatus("WITHDRAWN");
        item.setUpdatedAt(Instant.now());
        TeacherScheduleRestrictionRequest saved = requests.save(item);
        record(saved, "WITHDRAWN", previous, "WITHDRAWN", "Giáo viên rút đề nghị", teacherId);
        audit(teacherId, "WITHDRAW_SCHEDULE_RESTRICTION", saved, "Rút đề nghị hạn chế lịch dạy");
        return response(saved);
    }

    @Transactional
    public ScheduleRestrictionResponse review(String id, ReviewScheduleRestrictionRequest request,
                                              String actorId) {
        TeacherScheduleRestrictionRequest item = require(id);
        structure.assertSemesterWritable(item.getSemesterId());
        if (!"PENDING".equals(item.getStatus())) {
            throw ApiException.conflict("Chỉ có thể xử lý yêu cầu đang chờ duyệt");
        }
        String action = cleanUpper(request.action());
        if (!REVIEW_ACTIONS.contains(action)) throw ApiException.badRequest("Kết quả xử lý không hợp lệ");
        String note = clean(request.decisionNote());
        if (Set.of("REJECTED", "NEEDS_INFO").contains(action) && (note == null || note.length() < 5)) {
            throw ApiException.badRequest("Cần ghi rõ phản hồi khi từ chối hoặc yêu cầu bổ sung");
        }
        String previous = item.getStatus();
        item.setStatus(action);
        item.setDecisionNote(note);
        item.setReviewedBy(actorId);
        item.setReviewedAt(Instant.now());
        item.setUpdatedAt(Instant.now());
        TeacherScheduleRestrictionRequest saved = requests.save(item);
        record(saved, "REVIEWED", previous, action, note, actorId);
        String label = switch (action) {
            case "APPROVED" -> "đã được duyệt";
            case "REJECTED" -> "đã bị từ chối";
            default -> "cần bổ sung thông tin";
        };
        notifications.notifyUser(item.getTeacherId(), "SCHEDULE_RESTRICTION",
                "Cập nhật đề nghị hạn chế lịch dạy",
                "Đề nghị của bạn " + label + (note == null ? "." : ": " + note),
                "SCHEDULE_RESTRICTION", saved.getId());
        audit(actorId, "REVIEW_SCHEDULE_RESTRICTION", saved, "Kết quả: " + action);
        return response(saved);
    }

    @Transactional
    public ScheduleRestrictionResponse revoke(String id, String reason, String actorId) {
        TeacherScheduleRestrictionRequest item = require(id);
        structure.assertSemesterWritable(item.getSemesterId());
        if (!"APPROVED".equals(item.getStatus())) {
            throw ApiException.conflict("Chỉ có thể thu hồi hạn chế đang được áp dụng");
        }
        String cleaned = clean(reason);
        if (cleaned == null || cleaned.length() < 5) throw ApiException.badRequest("Cần ghi rõ lý do thu hồi");
        item.setStatus("REVOKED");
        item.setRevokedBy(actorId);
        item.setRevokedAt(Instant.now());
        item.setRevokeReason(cleaned);
        item.setUpdatedAt(Instant.now());
        TeacherScheduleRestrictionRequest saved = requests.save(item);
        record(saved, "REVOKED", "APPROVED", "REVOKED", cleaned, actorId);
        notifications.notifyUser(item.getTeacherId(), "SCHEDULE_RESTRICTION",
                "Hạn chế lịch dạy đã được thu hồi", "Lý do: " + cleaned,
                "SCHEDULE_RESTRICTION", saved.getId());
        audit(actorId, "REVOKE_SCHEDULE_RESTRICTION", saved, cleaned);
        return response(saved);
    }

    public List<ScheduleRestrictionHistoryResponse> history(String requestId, String semesterId,
                                                            String teacherId) {
        List<TeacherScheduleRestrictionHistory> rows;
        if (requestId != null && !requestId.isBlank()) {
            TeacherScheduleRestrictionRequest item = require(requestId);
            if (teacherId != null && !teacherId.equals(item.getTeacherId())) {
                throw ApiException.forbidden("Không được xem lịch sử yêu cầu của giáo viên khác");
            }
            rows = history.findTop100ByRequestIdOrderByCreatedAtDesc(requestId);
        } else {
            structure.getSemester(semesterId);
            rows = history.findTop200BySemesterIdOrderByCreatedAtDesc(semesterId);
            if (teacherId != null) rows = rows.stream().filter(item -> teacherId.equals(item.getTeacherId())).toList();
        }
        return rows.stream().map(item -> new ScheduleRestrictionHistoryResponse(item.getId(), item.getRequestId(),
                item.getSemesterId(), item.getTeacherId(), item.getAction(), item.getPreviousStatus(),
                item.getNewStatus(), item.getDetails(), item.getActorId(), item.getCreatedAt())).toList();
    }

    public Set<String> approvedSlots(String teacherId, String semesterId) {
        Semester semester = structure.getSemester(semesterId);
        Set<String> result = new LinkedHashSet<>();
        requests.findByTeacherIdAndSemesterIdAndStatus(teacherId, semesterId, "APPROVED").stream()
                .filter(item -> overlaps(item.getEffectiveFrom(), item.getEffectiveTo(),
                        semester.getStartDate(), semester.getEndDate()))
                .forEach(item -> result.addAll(list(item.getRestrictedSlots())));
        return result;
    }

    private ValidatedRequest validate(SaveScheduleRestrictionRequest request) {
        Semester semester = structure.getSemester(request.semesterId());
        if (request.effectiveFrom().isAfter(request.effectiveTo())) {
            throw ApiException.badRequest("Ngày bắt đầu không được sau ngày kết thúc");
        }
        if (semester.getStartDate() != null && request.effectiveFrom().isBefore(semester.getStartDate())
                || semester.getEndDate() != null && request.effectiveTo().isAfter(semester.getEndDate())) {
            throw ApiException.badRequest("Thời gian hạn chế phải nằm trong phạm vi học kỳ");
        }
        List<String> slots = request.restrictedSlots().stream().map(TeacherScheduleRestrictionService::normalizeSlot)
                .distinct().toList();
        return new ValidatedRequest(slots, request.effectiveFrom(), request.effectiveTo());
    }

    private static String normalizeSlot(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("(?:MORNING|AFTERNOON):(MON|TUE|WED|THU|FRI):[1-5]")) {
            throw ApiException.badRequest("Khung giờ hạn chế không hợp lệ: " + value);
        }
        return normalized;
    }

    private void record(TeacherScheduleRestrictionRequest item, String action,
                        String previous, String next, String details, String actorId) {
        history.save(TeacherScheduleRestrictionHistory.builder().id(Ids.gen("tsh"))
                .requestId(item.getId()).semesterId(item.getSemesterId()).teacherId(item.getTeacherId())
                .action(action).previousStatus(previous).newStatus(next).details(details)
                .actorId(actorId).createdAt(Instant.now()).build());
    }

    private void audit(String actorId, String action, TeacherScheduleRestrictionRequest item, String detail) {
        User actor = users.getById(actorId);
        audit.record(actorId, actor.getFullName(), actor.getRole(), action, "academic",
                "schedule_restriction", item.getId(), detail);
    }

    private TeacherScheduleRestrictionRequest require(String id) {
        return requests.findById(id).orElseThrow(() -> ApiException.notFound("Đề nghị hạn chế lịch dạy"));
    }

    private TeacherScheduleRestrictionRequest requireOwned(String id, String teacherId) {
        TeacherScheduleRestrictionRequest item = require(id);
        if (!teacherId.equals(item.getTeacherId())) throw ApiException.forbidden("Không được sửa yêu cầu của giáo viên khác");
        return item;
    }

    private User requireActiveTeacher(String id) {
        User teacher = users.getById(id);
        if (!"TEACHER".equals(teacher.getRole()) || !"ACTIVE".equals(teacher.getStatus())) {
            throw ApiException.badRequest("Giáo viên không tồn tại hoặc không hoạt động");
        }
        return teacher;
    }

    private ScheduleRestrictionResponse response(TeacherScheduleRestrictionRequest item) {
        User teacher = users.getById(item.getTeacherId());
        return new ScheduleRestrictionResponse(item.getId(), item.getTeacherId(), item.getTeacherName(),
                teacher.getTeacherCode(), item.getSemesterId(), list(item.getRestrictedSlots()),
                item.getEffectiveFrom(), item.getEffectiveTo(), item.getReason(), item.getEvidenceUrl(),
                item.getStatus(), item.getDecisionNote(), item.getReviewedBy(), item.getReviewedAt(),
                item.getRevokedBy(), item.getRevokedAt(), item.getRevokeReason(), item.getSubmittedAt(),
                item.getCreatedAt(), item.getUpdatedAt());
    }

    private static boolean overlaps(LocalDate from, LocalDate to, LocalDate semesterFrom, LocalDate semesterTo) {
        if (from != null && semesterTo != null && from.isAfter(semesterTo)) return false;
        return to == null || semesterFrom == null || !to.isBefore(semesterFrom);
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String cleanUpper(String value) {
        String cleaned = clean(value);
        return cleaned == null ? null : cleaned.toUpperCase(Locale.ROOT);
    }

    private static String csv(List<String> values) {
        return String.join(",", new LinkedHashSet<>(values));
    }

    private static List<String> list(String value) {
        return value == null || value.isBlank() ? List.of()
                : Arrays.stream(value.split(",")).map(String::trim).filter(item -> !item.isBlank()).toList();
    }

    private record ValidatedRequest(List<String> slots, LocalDate from, LocalDate to) {}
}
