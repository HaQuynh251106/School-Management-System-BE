package com.sse.app.academic.leave;

import com.sse.app.academic.leave.LeaveRequestDtos.CreateLeaveRequest;
import com.sse.app.academic.attendance.ApprovedLeaveAttendanceService;
import com.sse.app.academic.structure.SchoolClass;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.identity.User;
import com.sse.app.identity.UserDto;
import com.sse.app.identity.UserService;
import com.sse.app.notification.NotificationService;
import com.sse.app.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

@Service
public class LeaveRequestService {
    private final LeaveRequestRepository requests;
    private final UserService users;
    private final StructureService structure;
    private final NotificationService notifications;
    private final ApprovedLeaveAttendanceService approvedLeaveAttendance;

    public LeaveRequestService(LeaveRequestRepository requests, UserService users,
                               StructureService structure, NotificationService notifications,
                               ApprovedLeaveAttendanceService approvedLeaveAttendance) {
        this.requests = requests;
        this.users = users;
        this.structure = structure;
        this.notifications = notifications;
        this.approvedLeaveAttendance = approvedLeaveAttendance;
    }

    public List<LeaveRequest> list(CurrentUser actor) {
        List<LeaveRequest> result = new ArrayList<>();
        if (actor.isStudent()) result.addAll(requests.findByStudentId(actor.id()));
        else if (actor.isParent()) {
            for (UserDto child : users.childrenOf(actor.id())) result.addAll(requests.findByStudentId(child.id()));
        } else if (actor.isTeacher()) {
            for (SchoolClass schoolClass : structure.classesOfHomeroom(actor.id())) result.addAll(requests.findByClassId(schoolClass.getId()));
        } else if (actor.isAdmin()) result.addAll(requests.findAll());
        else throw ApiException.forbidden("Không có quyền xem đơn xin nghỉ");
        return result.stream().distinct().sorted(Comparator.comparing(LeaveRequest::getCreatedAt).reversed()).toList();
    }

    @Transactional
    public LeaveRequest create(CreateLeaveRequest input, String studentId) {
        User student = users.getById(studentId);
        if (!"STUDENT".equals(student.getRole()) || student.getClassId() == null) {
            throw ApiException.badRequest("Học sinh chưa được xếp lớp");
        }
        validateDates(input.startDate(), input.endDate());
        boolean overlaps = requests.findByStudentIdAndEndDateGreaterThanEqualAndStartDateLessThanEqual(
                        studentId, input.startDate(), input.endDate()).stream()
                .anyMatch(item -> !List.of("REJECTED", "CANCELLED").contains(item.getStatus()));
        if (overlaps) throw ApiException.conflict("Học sinh đã có đơn xin nghỉ trùng khoảng thời gian này");
        SchoolClass schoolClass = structure.getClass(student.getClassId());
        String reason = input.reason().trim();
        LeaveRequest request = requests.save(LeaveRequest.builder()
                .id(Ids.gen("leave")).studentId(studentId).studentName(student.getFullName())
                .classId(student.getClassId()).classCode(schoolClass.getCode())
                .startDate(input.startDate()).endDate(input.endDate()).reason(reason)
                .status("PENDING_PARENT").homeroomTeacherId(schoolClass.getHomeroomTeacherId())
                .homeroomTeacherName(schoolClass.getHomeroomTeacherName())
                .createdAt(Instant.now()).updatedAt(Instant.now()).build());
        notifications.notifyUsers(users.parentIdsOf(studentId), "LEAVE_REQUEST", "IMPORTANT",
                "Cần xác nhận đơn xin nghỉ", student.getFullName() + " xin nghỉ từ " + input.startDate() + " đến " + input.endDate(),
                "LEAVE_REQUEST", request.getId());
        return request;
    }

    @Transactional
    public LeaveRequest parentDecision(String id, String parentId, boolean confirm, String note) {
        LeaveRequest request = get(id);
        users.assertParentOf(parentId, request.getStudentId());
        requireStatus(request, "PENDING_PARENT", "Đơn không còn chờ phụ huynh xác nhận");
        request.setParentId(parentId);
        request.setParentName(users.fullNameOf(parentId));
        request.setParentConfirmedAt(Instant.now());
        request.setDecisionNote(clean(note));
        request.setStatus(confirm ? "PENDING_HOMEROOM" : "REJECTED");
        request.setUpdatedAt(Instant.now());
        requests.save(request);
        if (confirm) {
            if (request.getHomeroomTeacherId() == null) throw ApiException.badRequest("Lớp chưa được phân công giáo viên chủ nhiệm");
            notifications.notifyUser(request.getHomeroomTeacherId(), "LEAVE_REQUEST", "IMPORTANT",
                    "Đơn xin nghỉ chờ duyệt", request.getStudentName() + " · " + request.getClassCode()
                            + " · " + request.getStartDate() + " đến " + request.getEndDate(), "LEAVE_REQUEST", id);
        } else notifyStudentAndParents(request, "Đơn xin nghỉ chưa được xác nhận", "Phụ huynh đã từ chối xác nhận đơn.");
        return request;
    }

    @Transactional
    public LeaveRequest homeroomDecision(String id, String teacherId, boolean approve, String note) {
        LeaveRequest request = get(id);
        requireStatus(request, "PENDING_HOMEROOM", "Đơn chưa được phụ huynh xác nhận hoặc đã được xử lý");
        if (!teacherId.equals(request.getHomeroomTeacherId())) throw ApiException.forbidden("Chỉ GVCN của lớp được duyệt đơn này");
        request.setStatus(approve ? "APPROVED" : "REJECTED");
        request.setDecidedAt(Instant.now());
        request.setDecisionNote(clean(note));
        request.setUpdatedAt(Instant.now());
        requests.save(request);
        if (approve) {
            approvedLeaveAttendance.reconcile(request.getStudentId(), request.getStartDate(), request.getEndDate());
        }
        notifyStudentAndParents(request, approve ? "Đơn xin nghỉ đã được duyệt" : "Đơn xin nghỉ bị từ chối",
                request.getStartDate() + " đến " + request.getEndDate() + (clean(note) == null ? "" : " · " + clean(note)));
        return request;
    }

    @Transactional
    public LeaveRequest cancel(String id, String studentId) {
        LeaveRequest request = get(id);
        if (!studentId.equals(request.getStudentId())) throw ApiException.forbidden("Không thể hủy đơn của học sinh khác");
        if (!List.of("PENDING_PARENT", "PENDING_HOMEROOM").contains(request.getStatus())) {
            throw ApiException.badRequest("Chỉ có thể hủy đơn đang chờ xử lý");
        }
        request.setStatus("CANCELLED");
        request.setUpdatedAt(Instant.now());
        requests.save(request);
        LinkedHashSet<String> recipients = new LinkedHashSet<>(users.parentIdsOf(studentId));
        if (request.getHomeroomTeacherId() != null) recipients.add(request.getHomeroomTeacherId());
        notifications.notifyUsers(recipients.stream().toList(), "LEAVE_REQUEST", "Đơn xin nghỉ đã hủy",
                request.getStudentName() + " đã hủy đơn " + request.getStartDate() + " đến " + request.getEndDate(), "LEAVE_REQUEST", id);
        return request;
    }

    private LeaveRequest get(String id) {
        return requests.findById(id).orElseThrow(() -> ApiException.notFound("Đơn xin nghỉ"));
    }

    public boolean hasApprovedLeave(String studentId, LocalDate date) {
        return requests.findByStudentIdAndEndDateGreaterThanEqualAndStartDateLessThanEqual(studentId, date, date)
                .stream().anyMatch(item -> "APPROVED".equals(item.getStatus()));
    }

    public List<LeaveRequest> approvedForClassOn(String classId, LocalDate date) {
        return requests.findByClassIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(classId, date, date).stream()
                .filter(item -> "APPROVED".equals(item.getStatus()))
                .sorted(Comparator.comparing(LeaveRequest::getStudentName))
                .toList();
    }

    private void validateDates(LocalDate start, LocalDate end) {
        if (start.isBefore(LocalDate.now())) throw ApiException.badRequest("Ngày bắt đầu không được ở quá khứ");
        if (end.isBefore(start)) throw ApiException.badRequest("Ngày kết thúc phải từ ngày bắt đầu trở đi");
        if (start.plusDays(30).isBefore(end)) throw ApiException.badRequest("Một đơn xin nghỉ không được vượt quá 31 ngày");
    }

    private void requireStatus(LeaveRequest request, String status, String message) {
        if (!status.equals(request.getStatus())) throw ApiException.badRequest(message);
    }

    private void notifyStudentAndParents(LeaveRequest request, String title, String body) {
        LinkedHashSet<String> recipients = new LinkedHashSet<>(users.parentIdsOf(request.getStudentId()));
        recipients.add(request.getStudentId());
        notifications.notifyUsers(recipients.stream().toList(), "LEAVE_REQUEST", "IMPORTANT", title, body,
                "LEAVE_REQUEST", request.getId());
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
