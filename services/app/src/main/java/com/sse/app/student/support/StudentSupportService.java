package com.sse.app.student.support;

import com.sse.app.academic.structure.SchoolClass;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.academic.timetable.TimetableService;
import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.common.PageResponse;
import com.sse.app.common.Paging;
import com.sse.app.identity.User;
import com.sse.app.identity.UserService;
import com.sse.app.notification.NotificationService;
import com.sse.app.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.*;

import static com.sse.app.student.support.StudentSupportDtos.*;

@Service
@RequiredArgsConstructor
public class StudentSupportService {
    private static final Set<String> CATEGORIES = Set.of("ACADEMIC", "ATTENDANCE", "BEHAVIOR", "WELLBEING", "OTHER");
    private static final Set<String> SEVERITIES = Set.of("LOW", "MEDIUM", "HIGH");
    private static final Set<String> STATUSES = Set.of("OPEN", "MONITORING", "RESOLVED");

    private final StudentInterventionRepository interventions;
    private final StructureService structure;
    private final TimetableService timetable;
    private final UserService users;
    private final NotificationService notifications;
    private final Clock clock;

    public PageResponse<InterventionView> page(CurrentUser actor, String classId, String studentId,
                                                String status, String severity, String query,
                                                int page, int size) {
        Access access = requireClassAccess(actor, classId);
        Specification<StudentIntervention> scope = (root, ignored, builder) ->
                builder.equal(root.get("classId"), access.schoolClass().getId());
        if (!access.homeroom()) {
            scope = scope.and((root, ignored, builder) -> builder.equal(root.get("teacherId"), actor.id()));
        }
        if (studentId != null && !studentId.isBlank()) {
            assertStudentInClass(studentId, access.schoolClass().getId());
            scope = scope.and((root, ignored, builder) -> builder.equal(root.get("studentId"), studentId));
        }
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            String normalized = normalize(status);
            scope = scope.and((root, ignored, builder) -> builder.equal(root.get("status"), normalized));
        }
        if (severity != null && !severity.isBlank() && !"ALL".equalsIgnoreCase(severity)) {
            String normalized = normalize(severity);
            scope = scope.and((root, ignored, builder) -> builder.equal(root.get("severity"), normalized));
        }
        if (query != null && !query.isBlank()) {
            String pattern = "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
            scope = scope.and((root, ignored, builder) -> builder.or(
                    builder.like(builder.lower(root.get("title")), pattern),
                    builder.like(builder.lower(root.get("description")), pattern),
                    builder.like(builder.lower(root.get("actionTaken")), pattern)
            ));
        }
        Page<InterventionView> result = interventions.findAll(scope,
                        Paging.request(page, size, Sort.by(Sort.Direction.DESC, "updatedAt")))
                .map(item -> view(item, actor));
        return PageResponse.from(result, summary(scope));
    }

    public InterventionView get(CurrentUser actor, String id) {
        StudentIntervention item = requireIntervention(id);
        Access access = requireClassAccess(actor, item.getClassId());
        assertVisible(actor, access, item);
        return view(item, actor);
    }

    @Transactional
    public InterventionView create(CurrentUser actor, SaveInterventionRequest request) {
        Access access = requireClassAccess(actor, request.classId());
        User student = assertStudentInClass(request.studentId(), access.schoolClass().getId());
        Values values = validate(request.category(), request.severity(), request.status());
        assertCategoryAllowed(access, values.category());
        Instant now = clock.instant();
        StudentIntervention item = StudentIntervention.builder()
                .id(Ids.gen("si"))
                .studentId(student.getId())
                .classId(access.schoolClass().getId())
                .teacherId(actor.id())
                .category(values.category())
                .severity(values.severity())
                .title(request.title().trim())
                .description(request.description().trim())
                .actionTaken(trim(request.actionTaken()))
                .followUpDate(request.followUpDate())
                .status(values.status())
                .parentContacted(false)
                .resolvedAt("RESOLVED".equals(values.status()) ? now : null)
                .createdAt(now)
                .updatedAt(now)
                .build();
        return view(interventions.save(item), actor);
    }

    @Transactional
    public InterventionView update(CurrentUser actor, String id, UpdateInterventionRequest request) {
        StudentIntervention item = requireIntervention(id);
        Access access = requireClassAccess(actor, item.getClassId());
        assertEditable(actor, access, item);
        Values values = validate(request.category(), request.severity(), request.status());
        assertCategoryAllowed(access, values.category());
        Instant now = clock.instant();
        item.setCategory(values.category());
        item.setSeverity(values.severity());
        item.setTitle(request.title().trim());
        item.setDescription(request.description().trim());
        item.setActionTaken(trim(request.actionTaken()));
        item.setFollowUpDate(request.followUpDate());
        item.setStatus(values.status());
        item.setResolvedAt("RESOLVED".equals(values.status()) ?
                (item.getResolvedAt() == null ? now : item.getResolvedAt()) : null);
        item.setUpdatedAt(now);
        return view(interventions.save(item), actor);
    }

    @Transactional
    public FamilyContactResult contactFamily(CurrentUser actor, String id, FamilyContactRequest request) {
        StudentIntervention item = requireIntervention(id);
        Access access = requireClassAccess(actor, item.getClassId());
        if (!access.homeroom()) {
            throw ApiException.forbidden("Chỉ giáo viên chủ nhiệm được trao đổi nội dung can thiệp với gia đình");
        }
        User student = assertStudentInClass(item.getStudentId(), item.getClassId());
        LinkedHashSet<String> recipients = new LinkedHashSet<>(users.parentIdsOf(student.getId()));
        recipients.add(student.getId());
        Instant sentAt = clock.instant();
        notifications.notifyUsers(new ArrayList<>(recipients), "STUDENT_SUPPORT", "IMPORTANT",
                "GVCN trao đổi về kế hoạch hỗ trợ học sinh",
                request.message().trim(), "STUDENT_INTERVENTION", item.getId());
        item.setParentContacted(true);
        item.setParentContactedAt(sentAt);
        item.setUpdatedAt(sentAt);
        interventions.save(item);
        return new FamilyContactResult(item.getId(), recipients.size(), sentAt);
    }

    private Map<String, Long> summary(Specification<StudentIntervention> scope) {
        long total = interventions.count(scope);
        long open = interventions.count(scope.and((root, ignored, builder) ->
                builder.notEqual(root.get("status"), "RESOLVED")));
        long high = interventions.count(scope.and((root, ignored, builder) ->
                builder.equal(root.get("severity"), "HIGH")));
        long contacted = interventions.count(scope.and((root, ignored, builder) ->
                builder.isTrue(root.get("parentContacted"))));
        return Map.of("TOTAL", total, "OPEN", open, "HIGH", high, "FAMILY_CONTACTED", contacted);
    }

    private InterventionView view(StudentIntervention item, CurrentUser actor) {
        User student = users.getById(item.getStudentId());
        SchoolClass schoolClass = structure.getClass(item.getClassId());
        String teacherName = users.fullNameOf(item.getTeacherId());
        boolean homeroom = actor.id().equals(schoolClass.getHomeroomTeacherId());
        boolean editable = homeroom || actor.id().equals(item.getTeacherId());
        List<ParentContact> parentContacts = users.parentIdsOf(student.getId()).stream()
                .map(parentId -> new ParentContact(parentId, users.fullNameOf(parentId)))
                .toList();
        return new InterventionView(item.getId(), student.getId(), student.getStudentCode(), student.getFullName(),
                schoolClass.getId(), schoolClass.getCode(), item.getTeacherId(), teacherName,
                item.getCategory(), item.getSeverity(), item.getTitle(), item.getDescription(),
                item.getActionTaken(), item.getFollowUpDate(), item.getStatus(), item.isParentContacted(),
                item.getParentContactedAt(), item.getResolvedAt(), item.getCreatedAt(), item.getUpdatedAt(),
                parentContacts, editable, homeroom);
    }

    private Access requireClassAccess(CurrentUser actor, String classId) {
        if (!actor.isTeacher()) throw ApiException.forbidden("Chỉ giáo viên được sử dụng hồ sơ hỗ trợ học sinh");
        if (classId == null || classId.isBlank()) throw ApiException.badRequest("Cần chọn lớp");
        SchoolClass schoolClass = structure.getClass(classId);
        boolean homeroom = actor.id().equals(schoolClass.getHomeroomTeacherId());
        boolean subjectTeacher = timetable.teacherTeachesClass(actor.id(), classId);
        if (!homeroom && !subjectTeacher) {
            throw ApiException.forbidden("Giáo viên chỉ được theo dõi học sinh thuộc lớp đang dạy hoặc chủ nhiệm");
        }
        return new Access(schoolClass, homeroom);
    }

    private User assertStudentInClass(String studentId, String classId) {
        User student = users.getById(studentId);
        if (!"STUDENT".equals(student.getRole()) || !classId.equals(student.getClassId())) {
            throw ApiException.badRequest("Học sinh không thuộc lớp đã chọn");
        }
        return student;
    }

    private void assertVisible(CurrentUser actor, Access access, StudentIntervention item) {
        if (!access.homeroom() && !actor.id().equals(item.getTeacherId())) {
            throw ApiException.forbidden("Giáo viên bộ môn chỉ được xem ghi nhận do mình tạo");
        }
    }

    private void assertEditable(CurrentUser actor, Access access, StudentIntervention item) {
        assertVisible(actor, access, item);
        if (!access.homeroom() && !actor.id().equals(item.getTeacherId())) {
            throw ApiException.forbidden("Không có quyền cập nhật ghi nhận này");
        }
    }

    private void assertCategoryAllowed(Access access, String category) {
        if (!access.homeroom() && !"ACADEMIC".equals(category)) {
            throw ApiException.forbidden("Giáo viên bộ môn chỉ ghi nhận hỗ trợ học tập; GVCN phụ trách chuyên cần, hành vi và trao đổi gia đình");
        }
    }

    private Values validate(String category, String severity, String status) {
        String safeCategory = normalize(category);
        String safeSeverity = normalize(severity);
        String safeStatus = normalize(status);
        if (!CATEGORIES.contains(safeCategory)) throw ApiException.badRequest("Nhóm hỗ trợ không hợp lệ");
        if (!SEVERITIES.contains(safeSeverity)) throw ApiException.badRequest("Mức độ hỗ trợ không hợp lệ");
        if (!STATUSES.contains(safeStatus)) throw ApiException.badRequest("Trạng thái hỗ trợ không hợp lệ");
        return new Values(safeCategory, safeSeverity, safeStatus);
    }

    private StudentIntervention requireIntervention(String id) {
        return interventions.findById(id).orElseThrow(() -> ApiException.notFound("Ghi nhận hỗ trợ học sinh"));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record Access(SchoolClass schoolClass, boolean homeroom) {}
    private record Values(String category, String severity, String status) {}
}
