package com.sse.app.academic.timetable;

import com.sse.app.academic.structure.SchoolClass;
import com.sse.app.academic.structure.Semester;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.identity.User;
import com.sse.app.identity.UserDto;
import com.sse.app.identity.UserService;
import com.sse.app.notification.NotificationService;
import com.sse.app.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static com.sse.app.academic.timetable.TeachingOperationDtos.*;

@Service
@RequiredArgsConstructor
public class TeachingOperationService {
    private static final Set<String> CHANGE_TYPES = Set.of("SUBSTITUTE", "RESCHEDULE", "SUBSTITUTE_AND_RESCHEDULE");
    private static final Set<String> DIARY_STATUSES = Set.of("DRAFT", "SUBMITTED");

    private final TimetableService timetable;
    private final StructureService structure;
    private final UserService users;
    private final TeachingAssignmentService teachingAssignments;
    private final LessonDiaryRepository diaries;
    private final TimetableChangeRequestRepository changes;
    private final NotificationService notifications;
    private final Clock clock;

    public TeachingWorkspace workspace(String teacherId, LocalDate from, LocalDate to) {
        DateRange range = safeRange(from, to);
        List<TimetableChangeRequest> teacherChanges = changes
                .findByRequestedByOrOriginalTeacherIdOrSubstituteTeacherIdOrderByCreatedAtDesc(
                        teacherId, teacherId, teacherId);
        Map<String, TimetableChangeRequest> approved = new HashMap<>();
        teacherChanges.stream().filter(item -> "APPROVED".equals(item.getStatus()))
                .forEach(item -> approved.put(key(item.getSlotId(), item.getOccurrenceDate()), item));

        Map<String, LessonOccurrence> occurrences = new LinkedHashMap<>();
        for (TimetableSlot slot : timetable.list(null, teacherId, null, null)) {
            for (LocalDate date = range.from(); !date.isAfter(range.to()); date = date.plusDays(1)) {
                if (!isRegularOccurrence(slot, date)) continue;
                TimetableChangeRequest change = approved.get(key(slot.getId(), date));
                if (change != null && isReschedule(change)) {
                    LocalDate moved = effectiveDate(change);
                    if (!moved.isBefore(range.from()) && !moved.isAfter(range.to())) {
                        LessonOccurrence occurrence = occurrence(slot, moved, change);
                        occurrences.put(occurrence.occurrenceKey(), occurrence);
                    }
                } else {
                    LessonOccurrence occurrence = occurrence(slot, date, change);
                    occurrences.put(occurrence.occurrenceKey(), occurrence);
                }
            }
        }

        // Giáo viên dạy thay không sở hữu slot gốc, vì vậy cần bổ sung các lượt được phân công.
        teacherChanges.stream()
                .filter(item -> "APPROVED".equals(item.getStatus()))
                .filter(item -> teacherId.equals(effectiveTeacherId(item)))
                .forEach(item -> {
                    TimetableSlot slot = requireSlot(item.getSlotId());
                    LocalDate date = effectiveDate(item);
                    if (!date.isBefore(range.from()) && !date.isAfter(range.to())) {
                        LessonOccurrence occurrence = occurrence(slot, date, item);
                        occurrences.put(occurrence.occurrenceKey(), occurrence);
                    }
                });

        List<LessonOccurrence> lessons = occurrences.values().stream()
                .filter(item -> teacherId.equals(item.originalTeacherId()) || teacherId.equals(item.effectiveTeacherId()))
                .sorted(Comparator.comparing(LessonOccurrence::date)
                        .thenComparingInt(LessonOccurrence::periodNo))
                .toList();
        List<ChangeRequestView> changeViews = teacherChanges.stream().map(this::view).toList();
        return new TeachingWorkspace(lessons, changeViews);
    }

    public List<SubstituteCandidate> substituteCandidates(String teacherId, String slotId, LocalDate date) {
        TimetableSlot slot = requireOwnedSlot(teacherId, slotId);
        requireRegularOccurrence(slot, date);
        return users.list("TEACHER", null, null).stream()
                .filter(candidate -> !candidate.id().equals(teacherId))
                .filter(candidate -> "ACTIVE".equals(candidate.status()))
                .filter(candidate -> teachingAssignments.teacherSupportsSubject(users.getById(candidate.id()), slot.getSubjectId()))
                .map(candidate -> candidate(candidate, slot, date))
                .sorted(Comparator.comparing(SubstituteCandidate::available).reversed()
                        .thenComparing(SubstituteCandidate::fullName))
                .toList();
    }

    @Transactional
    public LessonDiary saveDiary(CurrentUser actor, String slotId, LocalDate date, SaveLessonDiaryRequest request) {
        TimetableSlot slot = requireSlot(slotId);
        assertCanTeachOccurrence(actor.id(), slotId, date);
        String status = normalize(request.status());
        if (!DIARY_STATUSES.contains(status)) throw ApiException.badRequest("Trạng thái sổ đầu bài không hợp lệ");
        if ("SUBMITTED".equals(status) && blank(request.topic()) && blank(request.lessonContent())) {
            throw ApiException.badRequest("Cần nhập chủ đề hoặc nội dung tiết học trước khi hoàn tất sổ đầu bài");
        }
        if ("SUBMITTED".equals(status) && date.isAfter(LocalDate.now(clock))) {
            throw ApiException.badRequest("Có thể chuẩn bị bản nháp trước, nhưng chỉ hoàn tất sổ sau khi tiết học diễn ra");
        }
        Instant now = clock.instant();
        LessonDiary diary = diaries.findBySlotIdAndSessionDate(slotId, date)
                .orElseGet(() -> LessonDiary.builder()
                        .id(Ids.gen("ld")).slotId(slotId).sessionDate(date)
                        .teacherId(slot.getTeacherId()).actualTeacherId(actor.id())
                        .classId(slot.getClassId()).subjectId(slot.getSubjectId())
                        .status("DRAFT").createdAt(now).updatedAt(now).build());
        if ("SUBMITTED".equals(diary.getStatus()) && "DRAFT".equals(status)) {
            throw ApiException.badRequest("Sổ đầu bài đã hoàn tất không thể chuyển lại thành bản nháp");
        }
        diary.setActualTeacherId(actor.id());
        diary.setTopic(trim(request.topic()));
        diary.setLessonContent(trim(request.lessonContent()));
        diary.setHomework(trim(request.homework()));
        diary.setClassNote(trim(request.classNote()));
        diary.setAttendanceSummary(trim(request.attendanceSummary()));
        diary.setStatus(status);
        diary.setSubmittedAt("SUBMITTED".equals(status) ? now : null);
        diary.setUpdatedAt(now);
        return diaries.save(diary);
    }

    public LessonDiary diary(CurrentUser actor, String slotId, LocalDate date) {
        assertCanTeachOccurrence(actor.id(), slotId, date);
        return diaries.findBySlotIdAndSessionDate(slotId, date).orElse(null);
    }

    @Transactional
    public ChangeRequestView createChange(CurrentUser actor, ChangeRequestCreate request) {
        TimetableSlot slot = requireOwnedSlot(actor.id(), request.slotId());
        requireRegularOccurrence(slot, request.occurrenceDate());
        String type = normalize(request.requestType());
        if (!CHANGE_TYPES.contains(type)) throw ApiException.badRequest("Loại yêu cầu điều chỉnh không hợp lệ");
        if (changes.findFirstBySlotIdAndOccurrenceDateAndStatus(slot.getId(), request.occurrenceDate(), "PENDING").isPresent()
                || changes.findFirstBySlotIdAndOccurrenceDateAndStatus(slot.getId(), request.occurrenceDate(), "APPROVED").isPresent()) {
            throw ApiException.conflict("Tiết học này đã có yêu cầu đang xử lý hoặc đã được duyệt");
        }
        if (needsSubstitute(type)) {
            LocalDate substituteDate = isReschedule(type) ? request.proposedDate() : request.occurrenceDate();
            String substituteStart = isReschedule(type) ? request.proposedStartTime() : slot.getStartTime();
            String substituteEnd = isReschedule(type) ? request.proposedEndTime() : slot.getEndTime();
            validateSubstitute(slot, substituteDate, substituteStart, substituteEnd, request.substituteTeacherId());
        }
        if (isReschedule(type)) validateProposedSchedule(slot, request, request.substituteTeacherId());

        Instant now = clock.instant();
        TimetableChangeRequest saved = changes.save(TimetableChangeRequest.builder()
                .id(Ids.gen("tcr")).slotId(slot.getId()).occurrenceDate(request.occurrenceDate())
                .requestType(type).requestedBy(actor.id()).originalTeacherId(slot.getTeacherId())
                .substituteTeacherId(trim(request.substituteTeacherId()))
                .proposedDate(isReschedule(type) ? request.proposedDate() : null)
                .proposedPeriodNo(isReschedule(type) ? request.proposedPeriodNo() : null)
                .proposedStartTime(isReschedule(type) ? trim(request.proposedStartTime()) : null)
                .proposedEndTime(isReschedule(type) ? trim(request.proposedEndTime()) : null)
                .proposedRoomCode(isReschedule(type) ? trim(request.proposedRoomCode()) : null)
                .reason(request.reason().trim()).status("PENDING")
                .createdAt(now).updatedAt(now).build());
        SchoolClass schoolClass = structure.getClass(slot.getClassId());
        notifications.notifyUsers(users.activeUserIdsByRole("ACADEMIC_STAFF"), "TIMETABLE_CHANGE_REQUEST", "IMPORTANT",
                "Yêu cầu điều chỉnh tiết dạy",
                users.fullNameOf(actor.id()) + " đề nghị " + changeTypeLabel(type) + " môn "
                        + slot.getSubjectName() + " lớp " + schoolClass.getCode() + " ngày " + request.occurrenceDate(),
                "TIMETABLE_CHANGE_REQUEST", saved.getId());
        return view(saved);
    }

    @Transactional
    public ChangeRequestView cancel(CurrentUser actor, String id) {
        TimetableChangeRequest request = requireChange(id);
        if (!actor.id().equals(request.getRequestedBy())) throw ApiException.forbidden("Chỉ người tạo mới được hủy yêu cầu");
        if (!"PENDING".equals(request.getStatus())) throw ApiException.badRequest("Chỉ được hủy yêu cầu đang chờ duyệt");
        request.setStatus("CANCELLED");
        request.setUpdatedAt(clock.instant());
        return view(changes.save(request));
    }

    public List<ChangeRequestView> pendingChanges() {
        return changes.findByStatusOrderByCreatedAtAsc("PENDING").stream().map(this::view).toList();
    }

    @Transactional
    public ChangeRequestView decide(CurrentUser actor, String id, ChangeDecision decision) {
        TimetableChangeRequest request = requireChange(id);
        if (!"PENDING".equals(request.getStatus())) throw ApiException.badRequest("Yêu cầu không còn ở trạng thái chờ duyệt");
        if (actor.id().equals(request.getRequestedBy())) throw ApiException.forbidden("Người tạo yêu cầu không được tự duyệt");
        TimetableSlot slot = requireSlot(request.getSlotId());
        if (Boolean.TRUE.equals(decision.approved())) validateBeforeApproval(slot, request);
        Instant now = clock.instant();
        request.setStatus(Boolean.TRUE.equals(decision.approved()) ? "APPROVED" : "REJECTED");
        request.setReviewedBy(actor.id());
        request.setReviewNote(trim(decision.note()));
        request.setReviewedAt(now);
        request.setUpdatedAt(now);
        TimetableChangeRequest saved = changes.save(request);
        notifyDecision(slot, saved);
        return view(saved);
    }

    public boolean canTeachOccurrence(String teacherId, String slotId, LocalDate date) {
        TimetableSlot slot = requireSlot(slotId);
        if (teacherId.equals(slot.getTeacherId())) {
            TimetableChangeRequest effective = approvedForEffectiveOccurrence(slotId, date);
            if (effective != null) return teacherId.equals(effectiveTeacherId(effective));
            TimetableChangeRequest movedAway = approvedChange(slotId, date);
            return isRegularOccurrence(slot, date) && (movedAway == null || !isReschedule(movedAway));
        }
        return changes.findBySubstituteTeacherIdAndStatus(teacherId, "APPROVED").stream()
                .anyMatch(item -> slotId.equals(item.getSlotId()) && effectiveDate(item).equals(date));
    }

    public String effectiveTeacherIdForOccurrence(String slotId, LocalDate date) {
        TimetableSlot slot = requireSlot(slotId);
        TimetableChangeRequest effective = approvedForEffectiveOccurrence(slotId, date);
        return effective == null ? slot.getTeacherId() : effectiveTeacherId(effective);
    }

    public boolean isAssignedAwayFromOriginalTeacher(String slotId, LocalDate date) {
        TimetableSlot slot = requireSlot(slotId);
        TimetableChangeRequest direct = approvedChange(slotId, date);
        if (direct != null) {
            if (isReschedule(direct)) return true;
            return !slot.getTeacherId().equals(effectiveTeacherId(direct));
        }
        TimetableChangeRequest effective = approvedForEffectiveOccurrence(slotId, date);
        return effective != null && !slot.getTeacherId().equals(effectiveTeacherId(effective));
    }

    public boolean isValidOccurrence(String slotId, LocalDate date) {
        TimetableSlot slot = requireSlot(slotId);
        if (isRegularOccurrence(slot, date)) {
            TimetableChangeRequest approved = approvedChange(slotId, date);
            return approved == null || !isReschedule(approved);
        }
        return changes.findByStatusAndProposedDateBetween("APPROVED", date, date).stream()
                .anyMatch(item -> slotId.equals(item.getSlotId()) && date.equals(effectiveDate(item)));
    }

    private LessonOccurrence occurrence(TimetableSlot slot, LocalDate date, TimetableChangeRequest change) {
        String effectiveTeacherId = change == null ? slot.getTeacherId() : effectiveTeacherId(change);
        String state = change == null ? "ORIGINAL" : change.getRequestType();
        LessonDiary diary = diaries.findBySlotIdAndSessionDate(slot.getId(), date).orElse(null);
        return new LessonOccurrence(key(slot.getId(), date), slot.getId(), date, slot.getClassId(),
                structure.getClass(slot.getClassId()).getCode(), slot.getSubjectId(), slot.getSubjectName(),
                change != null && isReschedule(change) && change.getProposedPeriodNo() != null
                        ? change.getProposedPeriodNo() : slot.getPeriodNo(),
                change != null && isReschedule(change) && !blank(change.getProposedStartTime())
                        ? change.getProposedStartTime() : slot.getStartTime(),
                change != null && isReschedule(change) && !blank(change.getProposedEndTime())
                        ? change.getProposedEndTime() : slot.getEndTime(),
                change != null && isReschedule(change) && !blank(change.getProposedRoomCode())
                        ? change.getProposedRoomCode() : slot.getRoomCode(),
                slot.getTeacherId(), slot.getTeacherName(), effectiveTeacherId,
                users.fullNameOf(effectiveTeacherId), state, change == null ? null : change.getId(),
                diary == null ? null : diary.getId(), diary == null ? null : diary.getStatus());
    }

    private SubstituteCandidate candidate(UserDto candidate, TimetableSlot slot, LocalDate date) {
        boolean available = teacherAvailable(candidate.id(), slot, date, slot.getStartTime(), slot.getEndTime());
        return new SubstituteCandidate(candidate.id(), candidate.fullName(), candidate.mainSubject(), available,
                available ? "Đúng chuyên môn và còn trống khung giờ" : "Đang có lịch trùng khung giờ");
    }

    private void validateSubstitute(TimetableSlot slot, LocalDate date, String startTime, String endTime,
                                    String substituteTeacherId) {
        if (blank(substituteTeacherId)) throw ApiException.badRequest("Cần chọn giáo viên dạy thay");
        User substitute = users.getById(substituteTeacherId);
        if (!"TEACHER".equals(substitute.getRole()) || !"ACTIVE".equals(substitute.getStatus())) {
            throw ApiException.badRequest("Giáo viên dạy thay không hoạt động");
        }
        if (!teachingAssignments.teacherSupportsSubject(substitute, slot.getSubjectId())) {
            throw ApiException.badRequest("Giáo viên dạy thay phải đúng chuyên môn " + slot.getSubjectName());
        }
        if (date == null || blank(startTime) || blank(endTime)) {
            throw ApiException.badRequest("Cần chọn thời gian dạy thay hợp lệ");
        }
        if (!teacherAvailable(substituteTeacherId, slot, date, startTime, endTime)) {
            throw ApiException.conflict("Giáo viên dạy thay đã có lịch trùng khung giờ");
        }
    }

    private void validateProposedSchedule(TimetableSlot slot, ChangeRequestCreate request, String substituteTeacherId) {
        if (request.proposedDate() == null || request.proposedPeriodNo() == null
                || blank(request.proposedStartTime()) || blank(request.proposedEndTime())) {
            throw ApiException.badRequest("Cần chọn đầy đủ ngày, tiết và thời gian dạy bù");
        }
        LocalTime start = parseTime(request.proposedStartTime());
        LocalTime end = parseTime(request.proposedEndTime());
        if (start == null || end == null || !start.isBefore(end)) throw ApiException.badRequest("Khung giờ dạy bù không hợp lệ");
        Semester semester = structure.getSemester(slot.getSemesterId());
        if ((semester.getStartDate() != null && request.proposedDate().isBefore(semester.getStartDate()))
                || (semester.getEndDate() != null && request.proposedDate().isAfter(semester.getEndDate()))) {
            throw ApiException.badRequest("Ngày dạy bù phải nằm trong học kỳ của tiết học");
        }
        String effectiveTeacher = blank(substituteTeacherId) ? slot.getTeacherId() : substituteTeacherId;
        if (!teacherAvailable(effectiveTeacher, slot, request.proposedDate(), request.proposedStartTime(), request.proposedEndTime())) {
            throw ApiException.conflict("Giáo viên đã có lịch trùng với thời gian dạy bù");
        }
        for (TimetableSlot existing : timetable.list(null, null, slot.getSemesterId(), dayCode(request.proposedDate()))) {
            if (existing.getId().equals(slot.getId())) continue;
            if (!overlaps(request.proposedStartTime(), request.proposedEndTime(), existing.getStartTime(), existing.getEndTime())) continue;
            if (slot.getClassId().equals(existing.getClassId())) throw ApiException.conflict("Lớp đã có tiết khác vào thời gian dạy bù");
            if (!blank(request.proposedRoomCode()) && request.proposedRoomCode().equals(existing.getRoomCode())) {
                throw ApiException.conflict("Phòng dạy bù đang được sử dụng trong khung giờ này");
            }
        }
    }

    private void validateBeforeApproval(TimetableSlot slot, TimetableChangeRequest request) {
        if (needsSubstitute(request.getRequestType())) validateSubstitute(slot, effectiveDate(request),
                isReschedule(request) ? request.getProposedStartTime() : slot.getStartTime(),
                isReschedule(request) ? request.getProposedEndTime() : slot.getEndTime(),
                request.getSubstituteTeacherId());
        if (isReschedule(request)) validateProposedSchedule(slot, new ChangeRequestCreate(
                request.getSlotId(), request.getOccurrenceDate(), request.getRequestType(), request.getSubstituteTeacherId(),
                request.getProposedDate(), request.getProposedPeriodNo(), request.getProposedStartTime(),
                request.getProposedEndTime(), request.getProposedRoomCode(), request.getReason()), request.getSubstituteTeacherId());
    }

    private boolean teacherAvailable(String teacherId, TimetableSlot ignored, LocalDate date, String start, String end) {
        for (TimetableSlot existing : timetable.list(null, teacherId, ignored.getSemesterId(), dayCode(date))) {
            if (existing.getId().equals(ignored.getId()) && isRegularOccurrence(ignored, date)) continue;
            if (overlaps(start, end, existing.getStartTime(), existing.getEndTime())) return false;
        }
        return changes.findBySubstituteTeacherIdAndStatusAndOccurrenceDateBetween(
                        teacherId, "APPROVED", date, date).stream()
                .noneMatch(item -> !item.getSlotId().equals(ignored.getId()));
    }

    private void notifyDecision(TimetableSlot slot, TimetableChangeRequest request) {
        String classCode = structure.getClass(slot.getClassId()).getCode();
        String label = "APPROVED".equals(request.getStatus()) ? "đã được duyệt" : "đã bị từ chối";
        String body = "Yêu cầu " + changeTypeLabel(request.getRequestType()) + " môn " + slot.getSubjectName()
                + " lớp " + classCode + " ngày " + request.getOccurrenceDate() + " " + label
                + (blank(request.getReviewNote()) ? "." : ". Phản hồi: " + request.getReviewNote());
        notifications.notifyUser(request.getRequestedBy(), "TIMETABLE_CHANGE_DECISION", "IMPORTANT",
                "Kết quả yêu cầu điều chỉnh lịch", body, "TIMETABLE_CHANGE_REQUEST", request.getId());
        if (!"APPROVED".equals(request.getStatus())) return;
        if (!blank(request.getSubstituteTeacherId())) {
            notifications.notifyUser(request.getSubstituteTeacherId(), "SUBSTITUTE_ASSIGNMENT", "IMPORTANT",
                    "Phân công dạy thay", body, "TIMETABLE_CHANGE_REQUEST", request.getId());
        }
        for (UserDto student : users.list("STUDENT", null, slot.getClassId())) {
            notifications.notifyUser(student.id(), "TIMETABLE_CHANGED", "IMPORTANT",
                    "Điều chỉnh lịch học", body, "TIMETABLE_CHANGE_REQUEST", request.getId());
            notifications.notifyParentsOfStudent(student.id(), "TIMETABLE_CHANGED", "IMPORTANT",
                    "Điều chỉnh lịch học của con", body, "TIMETABLE_CHANGE_REQUEST", request.getId());
        }
    }

    private ChangeRequestView view(TimetableChangeRequest item) {
        TimetableSlot slot = requireSlot(item.getSlotId());
        SchoolClass schoolClass = structure.getClass(slot.getClassId());
        return new ChangeRequestView(item.getId(), item.getSlotId(), item.getOccurrenceDate(), item.getRequestType(),
                slot.getClassId(), schoolClass.getCode(), slot.getSubjectName(), item.getOriginalTeacherId(),
                users.fullNameOf(item.getOriginalTeacherId()), item.getSubstituteTeacherId(),
                blank(item.getSubstituteTeacherId()) ? null : users.fullNameOf(item.getSubstituteTeacherId()),
                item.getProposedDate(), item.getProposedPeriodNo(), item.getProposedStartTime(), item.getProposedEndTime(),
                item.getProposedRoomCode(), item.getReason(), item.getStatus(), item.getReviewedBy(),
                blank(item.getReviewedBy()) ? null : users.fullNameOf(item.getReviewedBy()), item.getReviewNote(),
                item.getCreatedAt(), item.getReviewedAt());
    }

    private TimetableSlot requireOwnedSlot(String teacherId, String slotId) {
        TimetableSlot slot = requireSlot(slotId);
        if (!teacherId.equals(slot.getTeacherId())) throw ApiException.forbidden("Chỉ giáo viên phụ trách tiết học được tạo yêu cầu");
        return slot;
    }

    private TimetableSlot requireSlot(String id) {
        TimetableSlot slot = timetable.findSlot(id);
        if (slot == null) throw ApiException.notFound("Tiết học");
        return slot;
    }

    private TimetableChangeRequest requireChange(String id) {
        return changes.findById(id).orElseThrow(() -> ApiException.notFound("Yêu cầu điều chỉnh lịch"));
    }

    private void assertCanTeachOccurrence(String teacherId, String slotId, LocalDate date) {
        if (!canTeachOccurrence(teacherId, slotId, date)) throw ApiException.forbidden("Không có quyền ghi sổ đầu bài cho tiết học này");
        if (!isValidOccurrence(slotId, date)) throw ApiException.badRequest("Ngày đã chọn không phải ngày diễn ra tiết học");
    }

    private void requireRegularOccurrence(TimetableSlot slot, LocalDate date) {
        if (!isRegularOccurrence(slot, date)) throw ApiException.badRequest("Ngày yêu cầu không thuộc lịch học của tiết này");
    }

    private boolean isRegularOccurrence(TimetableSlot slot, LocalDate date) {
        if (date == null) return false;
        Semester semester = structure.getSemester(slot.getSemesterId());
        if ((semester.getStartDate() != null && date.isBefore(semester.getStartDate()))
                || (semester.getEndDate() != null && date.isAfter(semester.getEndDate()))) return false;
        return dayCode(date).equalsIgnoreCase(slot.getDayOfWeek());
    }

    private TimetableChangeRequest approvedChange(String slotId, LocalDate occurrenceDate) {
        return changes.findFirstBySlotIdAndOccurrenceDateAndStatus(slotId, occurrenceDate, "APPROVED").orElse(null);
    }

    private TimetableChangeRequest approvedForEffectiveOccurrence(String slotId, LocalDate date) {
        TimetableChangeRequest direct = approvedChange(slotId, date);
        if (direct != null && !isReschedule(direct)) return direct;
        return changes.findByStatusAndProposedDateBetween("APPROVED", date, date).stream()
                .filter(item -> slotId.equals(item.getSlotId()))
                .findFirst().orElse(null);
    }

    private DateRange safeRange(LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now(clock);
        LocalDate safeFrom = from == null ? today.minusDays(7) : from;
        LocalDate safeTo = to == null ? today.plusDays(14) : to;
        if (safeTo.isBefore(safeFrom)) throw ApiException.badRequest("Khoảng ngày không hợp lệ");
        if (ChronoUnit.DAYS.between(safeFrom, safeTo) > 45) throw ApiException.badRequest("Chỉ được xem tối đa 46 ngày mỗi lần");
        return new DateRange(safeFrom, safeTo);
    }

    private boolean overlaps(String leftStart, String leftEnd, String rightStart, String rightEnd) {
        LocalTime a = parseTime(leftStart), b = parseTime(leftEnd), c = parseTime(rightStart), d = parseTime(rightEnd);
        if (a == null || b == null || c == null || d == null) return false;
        return a.isBefore(d) && c.isBefore(b);
    }

    private LocalTime parseTime(String value) {
        if (blank(value)) return null;
        try { return LocalTime.parse(value.trim()); }
        catch (DateTimeParseException ignored) { return null; }
    }

    private String effectiveTeacherId(TimetableChangeRequest request) {
        return blank(request.getSubstituteTeacherId()) ? request.getOriginalTeacherId() : request.getSubstituteTeacherId();
    }

    private LocalDate effectiveDate(TimetableChangeRequest request) {
        return isReschedule(request) ? request.getProposedDate() : request.getOccurrenceDate();
    }

    private boolean needsSubstitute(String type) { return type != null && type.contains("SUBSTITUTE"); }
    private boolean isReschedule(String type) { return type != null && type.contains("RESCHEDULE"); }
    private boolean isReschedule(TimetableChangeRequest request) { return isReschedule(request.getRequestType()); }
    private String changeTypeLabel(String type) {
        return switch (type) {
            case "SUBSTITUTE" -> "dạy thay";
            case "RESCHEDULE" -> "đổi tiết";
            case "SUBSTITUTE_AND_RESCHEDULE" -> "dạy thay và đổi tiết";
            default -> "điều chỉnh lịch";
        };
    }
    private String dayCode(LocalDate date) { return date.getDayOfWeek().name().substring(0, 3); }
    private String key(String slotId, LocalDate date) { return slotId + ":" + date; }
    private String normalize(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private String trim(String value) { return blank(value) ? null : value.trim(); }
    private record DateRange(LocalDate from, LocalDate to) {}
}
