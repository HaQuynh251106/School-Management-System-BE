package com.sse.app.workcenter;

import com.sse.app.audit.AuditService;
import com.sse.app.common.*;
import com.sse.app.identity.*;
import com.sse.app.notification.NotificationService;
import com.sse.app.security.*;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

import static com.sse.app.workcenter.WorkCenterDtos.*;

@Service @RequiredArgsConstructor
public class WorkCenterService {
    private static final Set<String> PRIORITIES = Set.of("LOW", "NORMAL", "HIGH", "URGENT");
    private static final Set<String> ROLES = Set.of("ADMIN", "ACADEMIC_STAFF", "ACCOUNTANT", "TEACHER");
    private static final Set<String> TERMINAL = Set.of("COMPLETED", "REJECTED", "CANCELLED");
    private static final Set<String> ACTIVE = Set.of("NEW", "ACCEPTED", "IN_PROGRESS", "WAITING_CONFIRMATION", "OVERDUE");

    private final OperationTaskRepository tasks;
    private final OperationTaskCommentRepository comments;
    private final OperationTaskChecklistRepository checklist;
    private final OperationTaskHistoryRepository history;
    private final OperationTaskAttachmentRepository attachments;
    private final UserRepository users;
    private final NotificationService notifications;
    private final AuditService audit;

    public PageResponse<TaskSummary> page(String query, String status, String priority, String module,
                                          String assignedRole, String assignedTo, LocalDate dueFrom,
                                          LocalDate dueTo, Boolean overdue, Boolean active, int page, int size,
                                          String sort, String direction) {
        CurrentUser actor = CurrentUserHolder.require();
        Specification<OperationTask> spec = scope(actor)
                .and(filters(query, status, priority, module, assignedRole, assignedTo, dueFrom, dueTo, overdue, active));
        Sort.Direction dir = "asc".equalsIgnoreCase(direction) ? Sort.Direction.ASC : Sort.Direction.DESC;
        String safeSort = Set.of("createdAt", "updatedAt", "dueDate", "priorityScore", "title")
                .contains(sort) ? sort : "updatedAt";
        Page<OperationTask> result = tasks.findAll(spec, Paging.request(page, size, Sort.by(dir, safeSort)));
        List<TaskSummary> items = result.getContent().stream().map(this::summary).toList();
        Map<String, Long> counts = scopedCounts(actor, spec);
        return new PageResponse<>(items, result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages(), result.isFirst(), result.isLast(), counts);
    }

    public TaskDetail detail(String id) {
        OperationTask task = requireVisible(id, CurrentUserHolder.require());
        return detailOf(task);
    }

    @Transactional
    public TaskDetail create(CreateTaskRequest request) {
        CurrentUser actor = CurrentUserHolder.require();
        validateAssignment(actor, request.module(), request.assignedRole(), request.assignedTo());
        Instant now = Instant.now();
        User actorUser = users.findById(actor.id()).orElse(null);
        User assignee = resolveAssignee(request.assignedTo(), request.assignedRole());
        OperationTask task = OperationTask.builder()
                .id(Ids.gen("task")).title(request.title().trim()).description(trim(request.description()))
                .module(normalize(request.module())).priority(validatePriority(request.priority()))
                .status("NEW").assignedRole(normalize(request.assignedRole()))
                .assignedTo(assignee == null ? null : assignee.getId())
                .assignedToName(assignee == null ? null : assignee.getFullName())
                .sourceType(trim(request.sourceType())).sourceId(trim(request.sourceId()))
                .parentTaskId(trim(request.parentTaskId())).dueDate(request.dueDate())
                .createdBy(actor.id()).creatorName(displayName(actorUser, actor.username()))
                .priorityScore(priorityScore(request.priority())).slaLevel(sla(request.dueDate(), "NEW"))
                .createdAt(now).updatedAt(now).build();
        tasks.save(task);
        if (request.checklist() != null) {
            int position = 0;
            for (String title : request.checklist()) addChecklistInternal(task, title, position++, actor, now);
            refreshProgress(task);
        }
        addHistory(task, actor, "CREATED", null, "NEW", "Tạo công việc");
        audit(actor, "CREATE", task, "Tạo và giao công việc: " + task.getTitle());
        notifyAssignment(task, "Bạn có công việc mới", false);
        return detailOf(task);
    }

    @Transactional
    public TaskDetail update(String id, UpdateTaskRequest request) {
        CurrentUser actor = CurrentUserHolder.require();
        OperationTask task = requireVisible(id, actor);
        requireManager(task, actor);
        if (TERMINAL.contains(task.getStatus())) throw ApiException.conflict("Công việc đã kết thúc, không thể sửa");
        validateAssignment(actor, request.module(), request.assignedRole(), request.assignedTo());
        String oldAssignee = task.getAssignedTo();
        User assignee = resolveAssignee(request.assignedTo(), request.assignedRole());
        task.setTitle(request.title().trim());
        task.setDescription(trim(request.description()));
        task.setModule(normalize(request.module()));
        task.setPriority(validatePriority(request.priority()));
        task.setPriorityScore(priorityScore(request.priority()));
        task.setAssignedRole(normalize(request.assignedRole()));
        task.setAssignedTo(assignee == null ? null : assignee.getId());
        task.setAssignedToName(assignee == null ? null : assignee.getFullName());
        task.setDueDate(request.dueDate());
        if (request.progressPercent() != null) task.setProgressPercent(request.progressPercent());
        task.setSlaLevel(sla(task.getDueDate(), task.getStatus()));
        task.setUpdatedAt(Instant.now());
        tasks.save(task);
        addHistory(task, actor, "UPDATED", task.getStatus(), task.getStatus(), "Cập nhật nội dung hoặc người phụ trách");
        audit(actor, "UPDATE", task, "Cập nhật công việc: " + task.getTitle());
        if (!Objects.equals(oldAssignee, task.getAssignedTo())) notifyAssignment(task, "Bạn được phân công công việc", false);
        return detailOf(task);
    }

    @Transactional
    public TaskDetail transition(String id, TransitionRequest request) {
        CurrentUser actor = CurrentUserHolder.require();
        OperationTask task = requireVisible(id, actor);
        requireParticipant(task, actor);
        String from = task.getStatus();
        String to = normalize(request.status());
        validateTransition(task, actor, from, to, request.note());
        Instant now = Instant.now();
        task.setPreviousStatus(from);
        task.setStatus(to);
        if (request.progressPercent() != null) task.setProgressPercent(request.progressPercent());
        switch (to) {
            case "ACCEPTED" -> task.setAcceptedAt(now);
            case "IN_PROGRESS" -> { if (task.getStartedAt() == null) task.setStartedAt(now); }
            case "WAITING_CONFIRMATION" -> { task.setSubmittedAt(now); task.setProgressPercent(100); }
            case "COMPLETED" -> {
                task.setCompletedAt(now); task.setResolvedAt(now); task.setProgressPercent(100);
                task.setResolution(trim(request.note()));
                task.setCompletedOnTime(task.getDueDate() == null || !LocalDate.now().isAfter(task.getDueDate()));
            }
            case "REJECTED" -> { task.setRejectedAt(now); task.setResolvedAt(now); task.setRejectionReason(trim(request.note())); }
            default -> { }
        }
        if ("OVERDUE".equals(from) && !TERMINAL.contains(to)) task.setDelayReason(trim(request.note()));
        task.setSlaLevel(sla(task.getDueDate(), to));
        task.setUpdatedAt(now);
        tasks.save(task);
        addHistory(task, actor, "STATUS_CHANGED", from, to, request.note());
        audit(actor, "STATUS_CHANGED", task, from + " → " + to);
        notifyStatus(task, actor, from, to);
        return detailOf(task);
    }

    @Transactional
    public OperationTaskComment addComment(String id, AddCommentRequest request) {
        CurrentUser actor = CurrentUserHolder.require();
        OperationTask task = requireVisible(id, actor);
        requireParticipant(task, actor);
        OperationTaskComment comment = comments.save(OperationTaskComment.builder()
                .id(Ids.gen("tcmt")).taskId(id).authorId(actor.id()).authorName(actorName(actor))
                .body(request.body().trim()).createdAt(Instant.now()).build());
        task.setUpdatedAt(Instant.now()); tasks.save(task);
        addHistory(task, actor, "COMMENTED", task.getStatus(), task.getStatus(), "Thêm trao đổi");
        notifyOtherParty(task, actor, "Có trao đổi mới", request.body());
        return comment;
    }

    @Transactional
    public OperationTaskChecklistItem addChecklist(String id, AddChecklistRequest request) {
        CurrentUser actor = CurrentUserHolder.require();
        OperationTask task = requireVisible(id, actor); requireParticipant(task, actor);
        int position = request.position() == null ? (int) checklist.countByTaskId(id) : request.position();
        OperationTaskChecklistItem item = addChecklistInternal(task, request.title(), position, actor, Instant.now());
        refreshProgress(task);
        addHistory(task, actor, "CHECKLIST_ADDED", task.getStatus(), task.getStatus(), request.title());
        return item;
    }

    @Transactional
    public OperationTaskChecklistItem setChecklistState(String taskId, String itemId, ChecklistStateRequest request) {
        CurrentUser actor = CurrentUserHolder.require();
        OperationTask task = requireVisible(taskId, actor); requireParticipant(task, actor);
        OperationTaskChecklistItem item = checklist.findById(itemId).orElseThrow(() -> ApiException.notFound("Hạng mục"));
        if (!taskId.equals(item.getTaskId())) throw ApiException.badRequest("Hạng mục không thuộc công việc");
        item.setCompleted(request.completed());
        item.setCompletedBy(request.completed() ? actor.id() : null);
        item.setCompletedAt(request.completed() ? Instant.now() : null);
        checklist.save(item); refreshProgress(task);
        addHistory(task, actor, request.completed() ? "CHECKED" : "UNCHECKED", task.getStatus(), task.getStatus(), item.getTitle());
        return item;
    }

    @Transactional
    public void deleteChecklist(String taskId, String itemId) {
        CurrentUser actor = CurrentUserHolder.require();
        OperationTask task = requireVisible(taskId, actor); requireParticipant(task, actor);
        OperationTaskChecklistItem item = checklist.findById(itemId).orElseThrow(() -> ApiException.notFound("Hạng mục"));
        if (!taskId.equals(item.getTaskId())) throw ApiException.badRequest("Hạng mục không thuộc công việc");
        checklist.delete(item); refreshProgress(task);
        addHistory(task, actor, "CHECKLIST_REMOVED", task.getStatus(), task.getStatus(), item.getTitle());
    }

    @Transactional
    public OperationTaskAttachment addAttachment(String id, AddAttachmentRequest request) {
        CurrentUser actor = CurrentUserHolder.require();
        OperationTask task = requireVisible(id, actor); requireParticipant(task, actor);
        OperationTaskAttachment result = attachments.save(OperationTaskAttachment.builder()
                .id(Ids.gen("tfile")).taskId(id).fileName(request.fileName().trim())
                .fileUrl(request.fileUrl().trim()).contentType(trim(request.contentType()))
                .fileSize(request.fileSize()).uploadedBy(actor.id()).uploadedAt(Instant.now()).build());
        addHistory(task, actor, "ATTACHMENT_ADDED", task.getStatus(), task.getStatus(), result.getFileName());
        return result;
    }

    public boolean canAccessFile(String fileId, CurrentUser actor) {
        return attachments.findFirstByFileUrl(fileId)
                .flatMap(attachment -> tasks.findById(attachment.getTaskId()))
                .map(task -> isVisible(task, actor))
                .orElse(false);
    }

    @Transactional
    public TaskDetail snooze(String id, SnoozeRequest request) {
        CurrentUser actor = CurrentUserHolder.require();
        OperationTask task = requireVisible(id, actor); requireParticipant(task, actor);
        if (!request.until().isAfter(Instant.now())) throw ApiException.badRequest("Thời gian nhắc lại phải ở tương lai");
        task.setSnoozedUntil(request.until()); task.setDelayReason(request.reason().trim()); task.setUpdatedAt(Instant.now());
        tasks.save(task);
        addHistory(task, actor, "SNOOZED", task.getStatus(), task.getStatus(), request.reason());
        return detailOf(task);
    }

    public WorkCenterStats stats() {
        CurrentUser actor = CurrentUserHolder.require();
        List<OperationTask> data = tasks.findAll(scope(actor));
        long total = data.size();
        long completed = count(data, "COMPLETED");
        long onTime = data.stream().filter(t -> "COMPLETED".equals(t.getStatus()) && Boolean.TRUE.equals(t.getCompletedOnTime())).count();
        return new WorkCenterStats(total, count(data, "NEW"), count(data, "ACCEPTED") + count(data, "IN_PROGRESS"),
                count(data, "WAITING_CONFIRMATION"), completed, data.stream().filter(this::isOverdue).count(),
                count(data, "REJECTED"), onTime, completed == 0 ? 0 : Math.round(onTime * 1000.0 / completed) / 10.0,
                grouped(data, OperationTask::getPriority), grouped(data, OperationTask::getModule),
                grouped(data, t -> t.getAssignedToName() == null ? t.getAssignedRole() : t.getAssignedToName()));
    }

    public List<AssigneeOption> assignees(String role) {
        CurrentUser actor = CurrentUserHolder.require();
        String targetRole = normalize(role);
        validateAssignment(actor, "ACCOUNTANT".equals(targetRole) ? "FINANCE" : "OPERATIONS", targetRole, null);
        return users.findByRole(targetRole).stream()
                .filter(user -> "ACTIVE".equals(user.getStatus()))
                .filter(user -> !actor.isTeacher() || actor.id().equals(user.getId()))
                .sorted(Comparator.comparing(user -> Objects.toString(user.getFullName(), user.getUsername())))
                .map(user -> new AssigneeOption(user.getId(), displayName(user, user.getUsername()), user.getRole(),
                        user.getTeacherCode() != null ? user.getTeacherCode() : user.getEmail()))
                .toList();
    }

    public byte[] exportCsv(String status, String module) {
        CurrentUser actor = CurrentUserHolder.require();
        List<OperationTask> data = tasks.findAll(scope(actor).and(filters(null, status, null, module, null, null, null, null, null, null)),
                Sort.by(Sort.Direction.DESC, "updatedAt"));
        StringBuilder csv = new StringBuilder("Mã công việc,Tiêu đề,Nhóm,Ưu tiên,Trạng thái,Người phụ trách,Hạn hoàn thành,Tiến độ\n");
        for (OperationTask t : data) csv.append(cell(t.getId())).append(',').append(cell(t.getTitle())).append(',')
                .append(cell(t.getModule())).append(',').append(cell(t.getPriority())).append(',')
                .append(cell(effectiveStatus(t))).append(',').append(cell(t.getAssignedToName())).append(',')
                .append(cell(t.getDueDate())).append(',').append(t.getProgressPercent()).append("%\n");
        return ("\uFEFF" + csv).getBytes(StandardCharsets.UTF_8);
    }

    @Transactional
    public OperationTask upsertAutoTask(AutoTaskCommand command) {
        OperationTask task = tasks.findBySourceKey(command.sourceKey()).orElse(null);
        Instant now = Instant.now();
        if (command.resolved()) {
            if (task != null && !TERMINAL.contains(task.getStatus())) {
                task.setPreviousStatus(task.getStatus()); task.setStatus("COMPLETED"); task.setProgressPercent(100);
                task.setResolution("Hệ thống tự đóng vì dữ liệu nguồn đã hoàn tất"); task.setResolvedAt(now); task.setCompletedAt(now);
                task.setCompletedOnTime(task.getDueDate() == null || !LocalDate.now().isAfter(task.getDueDate()));
                task.setUpdatedAt(now); tasks.save(task);
                systemHistory(task, "AUTO_COMPLETED", "Nguồn nghiệp vụ đã hoàn tất");
            }
            return task;
        }
        User assignee = resolveAssignee(command.assignedTo(), command.assignedRole());
        if (task == null) {
            task = OperationTask.builder().id(Ids.gen("task")).title(command.title()).description(command.description())
                    .module(normalize(command.module())).priority(validatePriority(command.priority())).status("NEW")
                    .assignedRole(normalize(command.assignedRole())).assignedTo(assignee == null ? null : assignee.getId())
                    .assignedToName(assignee == null ? null : assignee.getFullName()).sourceType(command.sourceType())
                    .sourceId(command.sourceId()).sourceKey(command.sourceKey()).dueDate(command.dueDate())
                    .createdBy("SYSTEM").creatorName("Hệ thống").autoManaged(true)
                    .priorityScore(priorityScore(command.priority())).slaLevel(sla(command.dueDate(), "NEW"))
                    .createdAt(now).updatedAt(now).build();
            tasks.save(task); systemHistory(task, "AUTO_CREATED", "Tạo từ dữ liệu nghiệp vụ");
            notifyAssignment(task, "Hệ thống giao công việc mới", true);
        } else if (!TERMINAL.contains(task.getStatus())) {
            task.setTitle(command.title()); task.setDescription(command.description()); task.setDueDate(command.dueDate());
            task.setPriority(validatePriority(command.priority())); task.setPriorityScore(priorityScore(command.priority()));
            task.setAssignedTo(assignee == null ? null : assignee.getId()); task.setAssignedToName(assignee == null ? null : assignee.getFullName());
            task.setUpdatedAt(now); task.setSlaLevel(sla(task.getDueDate(), task.getStatus())); tasks.save(task);
        }
        return task;
    }

    @Transactional
    public void closeAutoTasksNotIn(String sourceType, Set<String> activeSourceKeys) {
        Instant now = Instant.now();
        for (OperationTask task : tasks.findBySourceTypeAndAutoManagedTrueAndStatusNotIn(sourceType, TERMINAL)) {
            if (task.getSourceKey() == null || activeSourceKeys.contains(task.getSourceKey())) continue;
            task.setPreviousStatus(task.getStatus()); task.setStatus("COMPLETED"); task.setProgressPercent(100);
            task.setResolution("Hệ thống tự đóng vì cảnh báo nguồn không còn tồn tại");
            task.setResolvedAt(now); task.setCompletedAt(now); task.setCompletedOnTime(true); task.setUpdatedAt(now);
            tasks.save(task); systemHistory(task, "AUTO_COMPLETED", "Cảnh báo nguồn đã được xử lý");
        }
    }

    private TaskDetail detailOf(OperationTask task) {
        return new TaskDetail(summary(task), task.getResolution(), task.getRejectionReason(), task.getDelayReason(),
                task.getAcceptedAt(), task.getStartedAt(), task.getSubmittedAt(), task.getCompletedAt(), task.getRejectedAt(),
                task.getCompletedOnTime(), checklist.findByTaskIdOrderByPositionAscCreatedAtAsc(task.getId()),
                comments.findByTaskIdOrderByCreatedAtAsc(task.getId()), attachments.findByTaskIdOrderByUploadedAtDesc(task.getId()),
                history.findByTaskIdOrderByCreatedAtDesc(task.getId()));
    }

    private TaskSummary summary(OperationTask t) {
        return new TaskSummary(t.getId(), t.getTitle(), t.getDescription(), t.getModule(), t.getPriority(), t.getStatus(),
                effectiveStatus(t), t.getAssignedRole(), t.getAssignedTo(), t.getAssignedToName(), t.getDueDate(),
                t.getProgressPercent(), sla(t.getDueDate(), t.getStatus()), t.isAutoManaged(), t.getSourceType(), t.getSourceId(),
                t.getParentTaskId(), t.getCreatedBy(), t.getCreatorName(), t.getCreatedAt(), t.getUpdatedAt(), t.getSnoozedUntil(), isOverdue(t));
    }

    private Specification<OperationTask> scope(CurrentUser actor) {
        return (root, query, cb) -> {
            if (actor.isAdmin()) return cb.conjunction();
            if (actor.isTeacher()) return cb.or(cb.equal(root.get("assignedTo"), actor.id()),
                    cb.and(cb.equal(root.get("assignedRole"), "TEACHER"), cb.equal(root.get("createdBy"), actor.id())));
            return cb.or(cb.equal(root.get("assignedTo"), actor.id()), cb.equal(root.get("assignedRole"), actor.role()),
                    cb.equal(root.get("createdBy"), actor.id()));
        };
    }

    private Specification<OperationTask> filters(String queryText, String status, String priority, String module,
                                                  String assignedRole, String assignedTo, LocalDate dueFrom,
                                                  LocalDate dueTo, Boolean overdue, Boolean activeOnly) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (queryText != null && !queryText.isBlank()) {
                String p = "%" + queryText.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(cb.like(cb.lower(root.get("title")), p), cb.like(cb.lower(root.get("description")), p),
                        cb.like(cb.lower(root.get("assignedToName")), p), cb.like(cb.lower(root.get("sourceId")), p)));
            }
            if (status != null && !status.isBlank()) {
                if ("OVERDUE".equalsIgnoreCase(status)) predicates.add(cb.and(root.get("status").in(ACTIVE), cb.lessThan(root.get("dueDate"), LocalDate.now())));
                else predicates.add(cb.equal(root.get("status"), normalize(status)));
            }
            if (priority != null && !priority.isBlank()) predicates.add(cb.equal(root.get("priority"), normalize(priority)));
            if (module != null && !module.isBlank()) predicates.add(cb.equal(root.get("module"), normalize(module)));
            if (assignedRole != null && !assignedRole.isBlank()) predicates.add(cb.equal(root.get("assignedRole"), normalize(assignedRole)));
            if (assignedTo != null && !assignedTo.isBlank()) predicates.add(cb.equal(root.get("assignedTo"), assignedTo));
            if (dueFrom != null) predicates.add(cb.greaterThanOrEqualTo(root.get("dueDate"), dueFrom));
            if (dueTo != null) predicates.add(cb.lessThanOrEqualTo(root.get("dueDate"), dueTo));
            if (Boolean.TRUE.equals(overdue)) predicates.add(cb.and(root.get("status").in(ACTIVE), cb.lessThan(root.get("dueDate"), LocalDate.now())));
            if (Boolean.TRUE.equals(activeOnly)) predicates.add(root.get("status").in(ACTIVE));
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private OperationTask requireVisible(String id, CurrentUser actor) {
        return tasks.findOne(scope(actor).and((root, query, cb) -> cb.equal(root.get("id"), id)))
                .orElseThrow(() -> ApiException.notFound("Công việc"));
    }

    private boolean isVisible(OperationTask task, CurrentUser actor) {
        if (actor.isAdmin()) return true;
        if (actor.isTeacher()) return actor.id().equals(task.getAssignedTo())
                || "TEACHER".equals(task.getAssignedRole()) && actor.id().equals(task.getCreatedBy());
        return actor.id().equals(task.getAssignedTo()) || actor.role().equals(task.getAssignedRole())
                || actor.id().equals(task.getCreatedBy());
    }

    private void validateAssignment(CurrentUser actor, String module, String role, String assigneeId) {
        String targetRole = normalize(role); String targetModule = normalize(module);
        if (!ROLES.contains(targetRole)) throw ApiException.badRequest("Vai trò nhận việc không hợp lệ");
        if (!actor.isAdmin()) {
            if (actor.isAcademicStaff() && ("FINANCE".equals(targetModule) || "ACCOUNTANT".equals(targetRole))) throw ApiException.forbidden("Giáo vụ không được giao nghiệp vụ tài chính");
            if (actor.isAccountant() && (!"FINANCE".equals(targetModule) || !"ACCOUNTANT".equals(targetRole))) throw ApiException.forbidden("Kế toán chỉ được giao nghiệp vụ tài chính");
            if (actor.isTeacher() && (!"TEACHER".equals(targetRole) || assigneeId != null && !actor.id().equals(assigneeId))) throw ApiException.forbidden("Giáo viên chỉ được tạo công việc cá nhân");
        }
        resolveAssignee(assigneeId, targetRole);
    }

    private User resolveAssignee(String id, String role) {
        if (id == null || id.isBlank()) return null;
        User user = users.findById(id).orElseThrow(() -> ApiException.notFound("Người phụ trách"));
        if (!normalize(role).equals(user.getRole())) throw ApiException.badRequest("Người phụ trách không thuộc vai trò đã chọn");
        if (!"ACTIVE".equals(user.getStatus())) throw ApiException.badRequest("Tài khoản người phụ trách không hoạt động");
        return user;
    }

    private void requireParticipant(OperationTask task, CurrentUser actor) {
        if (actor.isAdmin() || actor.id().equals(task.getCreatedBy()) || actor.id().equals(task.getAssignedTo())
                || actor.role().equals(task.getAssignedRole())) return;
        throw ApiException.forbidden("Bạn không tham gia công việc này");
    }

    private void requireManager(OperationTask task, CurrentUser actor) {
        if (actor.isAdmin() || actor.id().equals(task.getCreatedBy()) || (actor.role().equals(task.getAssignedRole()) && !actor.isTeacher())) return;
        throw ApiException.forbidden("Bạn không có quyền điều chỉnh công việc này");
    }

    private void validateTransition(OperationTask task, CurrentUser actor, String from, String to, String note) {
        Map<String, Set<String>> allowed = Map.of(
                "NEW", Set.of("ACCEPTED", "REJECTED", "CANCELLED"),
                "ACCEPTED", Set.of("IN_PROGRESS", "REJECTED", "CANCELLED"),
                "IN_PROGRESS", Set.of("WAITING_CONFIRMATION", "REJECTED", "CANCELLED"),
                "WAITING_CONFIRMATION", Set.of("COMPLETED", "IN_PROGRESS", "REJECTED"),
                "OVERDUE", Set.of("IN_PROGRESS", "WAITING_CONFIRMATION", "COMPLETED", "REJECTED"));
        if (!allowed.getOrDefault(from, Set.of()).contains(to)) throw ApiException.conflict("Không thể chuyển từ " + from + " sang " + to);
        if (("REJECTED".equals(to) || "CANCELLED".equals(to) || "COMPLETED".equals(to) && "OVERDUE".equals(from))
                && (note == null || note.isBlank())) throw ApiException.badRequest("Vui lòng nhập lý do hoặc kết quả xử lý");
        if ("COMPLETED".equals(to) && !actor.isAdmin() && !actor.id().equals(task.getCreatedBy())
                && !(task.isAutoManaged() && actor.role().equals(task.getAssignedRole()) && !actor.isTeacher()))
            throw ApiException.forbidden("Người giao việc hoặc quản lý mới được xác nhận hoàn thành");
    }

    private OperationTaskChecklistItem addChecklistInternal(OperationTask task, String title, int position, CurrentUser actor, Instant now) {
        return checklist.save(OperationTaskChecklistItem.builder().id(Ids.gen("tchk")).taskId(task.getId())
                .title(title.trim()).position(position).createdAt(now).build());
    }

    private void refreshProgress(OperationTask task) {
        long total = checklist.countByTaskId(task.getId()); long done = checklist.countByTaskIdAndCompletedTrue(task.getId());
        task.setProgressPercent(total == 0 ? task.getProgressPercent() : (int) Math.round(done * 100.0 / total));
        task.setUpdatedAt(Instant.now()); tasks.save(task);
    }

    private void addHistory(OperationTask task, CurrentUser actor, String action, String from, String to, String detail) {
        history.save(OperationTaskHistory.builder().id(Ids.gen("thist")).taskId(task.getId()).actorId(actor.id())
                .actorName(actorName(actor)).action(action).fromStatus(from).toStatus(to).detail(trim(detail)).createdAt(Instant.now()).build());
    }

    private void systemHistory(OperationTask task, String action, String detail) {
        history.save(OperationTaskHistory.builder().id(Ids.gen("thist")).taskId(task.getId()).actorId("SYSTEM")
                .actorName("Hệ thống").action(action).fromStatus(task.getPreviousStatus()).toStatus(task.getStatus())
                .detail(detail).createdAt(Instant.now()).build());
    }

    private void audit(CurrentUser actor, String action, OperationTask task, String detail) {
        audit.record(actor.id(), actorName(actor), actor.role(), action, "WORK_CENTER", "OPERATION_TASK", task.getId(), detail);
    }

    private void notifyAssignment(OperationTask task, String title, boolean once) {
        String body = task.getTitle() + (task.getDueDate() == null ? "" : " · Hạn " + task.getDueDate());
        List<String> recipients = task.getAssignedTo() == null
                ? users.findByRole(task.getAssignedRole()).stream().filter(user -> "ACTIVE".equals(user.getStatus())).map(User::getId).toList()
                : List.of(task.getAssignedTo());
        for (String recipient : recipients) {
            String actionUrl = taskUrl(recipient, task.getId());
            if (once) notifications.notifyUserOnce(recipient, "WORK_TASK", notificationPriority(task), title, body,
                    "OPERATION_TASK", task.getId(), actionUrl);
            else notifications.notifyUser(recipient, "WORK_TASK", notificationPriority(task), title, body,
                    "OPERATION_TASK", task.getId(), actionUrl);
        }
    }

    private void notifyStatus(OperationTask task, CurrentUser actor, String from, String to) {
        String title = "Công việc đã chuyển trạng thái";
        String body = task.getTitle() + " · " + from + " → " + to;
        Set<String> recipients = new LinkedHashSet<>(); recipients.add(task.getCreatedBy()); recipients.add(task.getAssignedTo()); recipients.remove(actor.id()); recipients.remove(null);
        for (String recipient : recipients) notifications.notifyUser(recipient, "WORK_TASK", notificationPriority(task), title, body,
                "OPERATION_TASK", task.getId(), taskUrl(recipient, task.getId()));
    }

    private void notifyOtherParty(OperationTask task, CurrentUser actor, String title, String body) {
        String recipient = actor.id().equals(task.getAssignedTo()) ? task.getCreatedBy() : task.getAssignedTo();
        if (recipient != null && !recipient.equals(actor.id())) notifications.notifyUser(recipient, "WORK_TASK", "NORMAL", title,
                task.getTitle() + ": " + body, "OPERATION_TASK_COMMENT", task.getId(), taskUrl(recipient, task.getId()));
    }

    private String taskUrl(String userId, String taskId) {
        String role = users.findById(userId).map(User::getRole).orElse("ADMIN");
        String path = switch (role) {
            case "ACADEMIC_STAFF" -> "giao-vu/cong-viec-hoc-vu";
            case "ACCOUNTANT" -> "ke-toan/cong-viec-tai-chinh";
            case "TEACHER" -> "giao-vien/viec-can-lam";
            default -> "quan-tri/trung-tam-cong-viec";
        };
        return "#/" + path + "?task=" + taskId;
    }

    private Map<String, Long> scopedCounts(CurrentUser actor, Specification<OperationTask> base) {
        List<OperationTask> data = tasks.findAll(base);
        Map<String, Long> result = new LinkedHashMap<>();
        result.put("all", (long) data.size()); result.put("new", count(data, "NEW"));
        result.put("inProgress", count(data, "ACCEPTED") + count(data, "IN_PROGRESS"));
        result.put("waitingConfirmation", count(data, "WAITING_CONFIRMATION"));
        result.put("overdue", data.stream().filter(this::isOverdue).count()); result.put("completed", count(data, "COMPLETED"));
        return result;
    }

    private long count(List<OperationTask> data, String status) { return data.stream().filter(t -> status.equals(t.getStatus())).count(); }
    private Map<String, Long> grouped(List<OperationTask> data, java.util.function.Function<OperationTask, String> key) {
        return data.stream().collect(Collectors.groupingBy(t -> Objects.toString(key.apply(t), "Khác"), LinkedHashMap::new, Collectors.counting()));
    }
    private boolean isOverdue(OperationTask t) { return !TERMINAL.contains(t.getStatus()) && t.getDueDate() != null && LocalDate.now().isAfter(t.getDueDate()); }
    private String effectiveStatus(OperationTask t) { return isOverdue(t) ? "OVERDUE" : t.getStatus(); }
    private String sla(LocalDate due, String status) {
        if (TERMINAL.contains(status) || due == null) return "ON_TRACK";
        long days = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), due);
        return days < 0 ? "OVERDUE" : days <= 1 ? "AT_RISK" : "ON_TRACK";
    }
    private String actorName(CurrentUser actor) { return users.findById(actor.id()).map(User::getFullName).filter(n -> !n.isBlank()).orElse(actor.username()); }
    private String displayName(User user, String fallback) { return user == null || user.getFullName() == null || user.getFullName().isBlank() ? fallback : user.getFullName(); }
    private String validatePriority(String value) { String result = normalize(value); if (!PRIORITIES.contains(result)) throw ApiException.badRequest("Mức ưu tiên không hợp lệ"); return result; }
    private int priorityScore(String priority) { return switch (normalize(priority)) { case "URGENT" -> 100; case "HIGH" -> 70; case "NORMAL" -> 40; default -> 10; }; }
    private String notificationPriority(OperationTask task) { return "URGENT".equals(task.getPriority()) ? "URGENT" : "HIGH".equals(task.getPriority()) ? "IMPORTANT" : "NORMAL"; }
    private String normalize(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
    private String trim(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private String cell(Object value) { return "\"" + Objects.toString(value, "").replace("\"", "\"\"") + "\""; }
}
