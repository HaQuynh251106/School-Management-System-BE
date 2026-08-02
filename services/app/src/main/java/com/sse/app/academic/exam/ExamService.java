package com.sse.app.academic.exam;

import com.sse.app.academic.exam.ExamDtos.*;
import com.sse.app.academic.structure.*;
import com.sse.app.academic.timetable.TeachingAssignmentService;
import com.sse.app.common.*;
import com.sse.app.identity.*;
import com.sse.app.notification.NotificationService;
import com.sse.app.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.*;

@Service @RequiredArgsConstructor
public class ExamService {
    private static final ZoneId SCHOOL_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final ExamPeriodRepository periods;
    private final ExamScheduleRepository schedules;
    private final ExamRoomRepository rooms;
    private final ExamGradingAssignmentRepository gradingAssignments;
    private final ExamCandidateRepository candidates;
    private final ExamSeatingPlanRepository seatingPlans;
    private final ExamSeatingPlanItemRepository seatingPlanItems;
    private final ExamProctorPlanRepository proctorPlans;
    private final ExamProctorPlanItemRepository proctorPlanItems;
    private final ExamResultRepository results;
    private final ExamReviewRepository reviews;
    private final ExamScoreAdjustmentRepository adjustments;
    private final StructureService structure;
    private final UserService users;
    private final TeachingAssignmentService teachingAssignments;
    private final NotificationService notifications;
    private final JdbcTemplate jdbc;

    public List<PeriodSummary> listPeriods(String academicYearId, String semesterId) {
        return periods.findAll().stream()
                .filter(p -> academicYearId == null || academicYearId.equals(p.getAcademicYearId()))
                .filter(p -> semesterId == null || semesterId.equals(p.getSemesterId()))
                .sorted(Comparator.comparing(ExamPeriod::getStartDate).reversed())
                .map(this::summary).toList();
    }

    public ExamPeriod requirePeriod(String id) {
        return periods.findById(id).orElseThrow(() -> ApiException.notFound("Kỳ thi"));
    }

    @Transactional
    public ExamPeriod createPeriod(SavePeriodRequest r, String actorId) {
        validatePeriod(r, null);
        Instant now = Instant.now();
        return periods.save(ExamPeriod.builder().id(idOr(r.id(), "ep"))
                .code(r.code().trim().toUpperCase()).name(r.name().trim())
                .academicYearId(r.academicYearId()).semesterId(r.semesterId())
                .gradeLevel(clean(r.gradeLevel())).startDate(r.startDate()).endDate(r.endDate())
                .status("DRAFT").createdAt(now).createdBy(actorId).updatedAt(now).build());
    }

    @Transactional
    public ExamPeriod updatePeriod(String id, SavePeriodRequest r) {
        ExamPeriod p = requirePeriod(id);
        assertEditable(p);
        validatePeriod(r, id);
        p.setCode(r.code().trim().toUpperCase()); p.setName(r.name().trim());
        p.setAcademicYearId(r.academicYearId()); p.setSemesterId(r.semesterId());
        p.setGradeLevel(clean(r.gradeLevel())); p.setStartDate(r.startDate()); p.setEndDate(r.endDate());
        invalidatePublishedSchedule(p);
        p.setUpdatedAt(Instant.now());
        return periods.save(p);
    }

    @Transactional
    public void deletePeriod(String id) {
        ExamPeriod p = requirePeriod(id);
        if (!"DRAFT".equals(p.getStatus())) throw ApiException.conflict("Chỉ được xóa kỳ thi đang ở trạng thái nháp");
        adjustments.deleteAll(adjustments.findByExamPeriodIdOrderByAdjustedAtDesc(id));
        reviews.deleteAll(reviews.findByExamPeriodId(id));
        results.deleteAll(results.findByExamPeriodId(id));
        candidates.deleteAll(candidates.findByExamPeriodId(id));
        List<ExamSchedule> periodSchedules = schedules.findByExamPeriodId(id);
        periodSchedules.forEach(schedule -> rooms.deleteAll(rooms.findByScheduleId(schedule.getId())));
        schedules.deleteAll(periodSchedules);
        periods.delete(p);
    }

    @Transactional
    public ExamPeriod setScoreLock(String id, boolean locked, String actorId) {
        ExamPeriod p = requirePeriod(id);
        if (locked && !p.isSchedulePublished()) {
            throw ApiException.conflict("Cần công bố lịch thi trước khi khóa nhập điểm");
        }
        p.setScoreEntryLocked(locked);
        p.setStatus(locked ? "SCORE_LOCKED" : "OPEN");
        p.setUpdatedAt(Instant.now());
        List<ExamResult> periodResults = results.findByExamPeriodId(id);
        if (locked) {
            periodResults.forEach(result -> result.setStatus("PUBLISHED"));
            results.saveAll(periodResults);
        }
        return periods.save(p);
    }

    @Transactional
    public ExamPeriod confirm(String id, String actorId) {
        ExamPeriod p = requirePeriod(id);
        if (!p.isScoreEntryLocked()) throw ApiException.conflict("Phải khóa nhập điểm trước khi xác nhận kỳ thi");
        p.setStatus("CONFIRMED"); p.setConfirmedAt(Instant.now()); p.setConfirmedBy(actorId); p.setUpdatedAt(Instant.now());
        return periods.save(p);
    }

    @Transactional
    public ExamPeriod publishSchedule(String id, String actorId) {
        ExamPeriod period = requirePeriod(id);
        List<ExamSchedule> periodSchedules = schedules.findByExamPeriodId(id);
        if (periodSchedules.isEmpty()) throw ApiException.conflict("Kỳ thi chưa có lịch thi để công bố");
        validateNoPeriodConflicts(periodSchedules);
        for (ExamSchedule schedule : periodSchedules) {
            if (schedule.getClassIds() == null || schedule.getClassIds().isEmpty()) {
                throw ApiException.conflict("Môn " + schedule.getSubjectName() + " chưa chọn lớp dự thi");
            }
            List<ExamRoom> scheduleRooms = rooms.findByScheduleId(schedule.getId());
            if (scheduleRooms.isEmpty()) {
                throw ApiException.conflict("Môn " + schedule.getSubjectName() + " chưa được phân phòng");
            }
            if (scheduleRooms.stream().anyMatch(room -> room.getProctorOneId() == null || room.getProctorOneId().isBlank())) {
                throw ApiException.conflict("Môn " + schedule.getSubjectName() + " còn phòng chưa có giám thị chính");
            }
            OrganizationReadiness readiness = organizationReadiness(schedule.getId());
            if (readiness.missingSeats() > 0) {
                throw ApiException.conflict("Môn " + schedule.getSubjectName() + " còn thiếu "
                        + readiness.missingSeats() + " chỗ ngồi");
            }
            if (!readiness.candidatesReady()) {
                throw ApiException.conflict("Môn " + schedule.getSubjectName() + " còn "
                        + readiness.missingCandidates() + " thí sinh chưa được xếp phòng");
            }
            Set<String> allocatedClasses = candidates.findByScheduleId(schedule.getId()).stream()
                    .map(ExamCandidate::getClassId).collect(java.util.stream.Collectors.toSet());
            List<String> missingClasses = schedule.getClassIds().stream().filter(classId -> !allocatedClasses.contains(classId))
                    .map(classId -> structure.getClass(classId).getCode()).sorted().toList();
            if (!missingClasses.isEmpty()) throw ApiException.conflict("Môn " + schedule.getSubjectName()
                    + " chưa xếp thí sinh cho lớp " + String.join(", ", missingClasses));
        }

        for (ExamSchedule schedule : periodSchedules) {
            List<ExamGradingAssignment> scheduleGraders = gradingAssignments.findByScheduleId(schedule.getId());
            Set<String> gradedClasses = scheduleGraders.stream()
                    .map(ExamGradingAssignment::getClassId)
                    .collect(java.util.stream.Collectors.toSet());
            List<String> classesWithoutGrader = schedule.getClassIds().stream()
                    .filter(classId -> !gradedClasses.contains(classId))
                    .map(classId -> structure.getClass(classId).getCode())
                    .sorted()
                    .toList();
            if (!classesWithoutGrader.isEmpty()) {
                throw ApiException.conflict("Môn " + schedule.getSubjectName()
                        + " chưa phân công giáo viên chấm thi cho lớp "
                        + String.join(", ", classesWithoutGrader));
            }
            Set<String> eligibleTeacherIds = eligibleGraders(schedule.getId()).stream()
                    .map(EligibleGrader::teacherId)
                    .collect(java.util.stream.Collectors.toSet());
            Optional<ExamGradingAssignment> invalidGrader = scheduleGraders.stream()
                    .filter(assignment -> !schedule.getSubjectId().equals(assignment.getSubjectId())
                            || !eligibleTeacherIds.contains(assignment.getTeacherId()))
                    .findFirst();
            if (invalidGrader.isPresent()) {
                throw ApiException.conflict("Giáo viên " + invalidGrader.get().getTeacherName()
                        + " không còn đúng chuyên môn " + schedule.getSubjectName());
            }
        }

        int nextRevision = period.getScheduleRevision() + 1;
        period.setSchedulePublished(true);
        period.setScheduleRevision(nextRevision);
        period.setSchedulePublishedAt(Instant.now());
        period.setSchedulePublishedBy(actorId);
        if ("DRAFT".equals(period.getStatus())) period.setStatus("OPEN");
        period.setUpdatedAt(Instant.now());
        ExamPeriod saved = periods.save(period);
        notifySchedulePublication(saved, periodSchedules, nextRevision > 1);
        return saved;
    }

    public List<ExamAgendaItem> agenda(CurrentUser actor, String childId) {
        if (actor.isParent() && childId != null && !childId.isBlank()) users.assertParentOf(actor.id(), childId);
        List<String> studentIds = actor.isStudent() ? List.of(actor.id())
                : actor.isParent() ? (childId == null || childId.isBlank()
                    ? users.childrenOf(actor.id()).stream().map(UserDto::id).toList() : List.of(childId))
                : List.of();
        List<ExamAgendaItem> items = new ArrayList<>();
        if (actor.isStudent() || actor.isParent()) {
            for (String studentId : studentIds) {
                for (ExamCandidate candidate : candidates.findByStudentId(studentId)) {
                    ExamPeriod period = periods.findById(candidate.getExamPeriodId()).orElse(null);
                    if (period == null || !period.isSchedulePublished()) continue;
                    ExamSchedule schedule = schedules.findById(candidate.getScheduleId()).orElse(null);
                    ExamRoom room = rooms.findById(candidate.getExamRoomId()).orElse(null);
                    if (schedule == null || room == null) continue;
                    items.add(agendaItem(candidate.getId(), "CANDIDATE", "Lịch thi",
                            period, schedule, room, candidate, examStatus(schedule)));
                }
            }
        } else if (actor.isTeacher()) {
            for (ExamPeriod period : periods.findAll()) {
                if (!period.isSchedulePublished()) continue;
                for (ExamSchedule schedule : schedules.findByExamPeriodId(period.getId())) {
                    for (ExamRoom room : rooms.findByScheduleId(schedule.getId())) {
                        if (actor.id().equals(room.getProctorOneId()) || actor.id().equals(room.getProctorTwoId())) {
                            items.add(agendaItem(room.getId() + ":proctor", "PROCTOR", "Coi thi",
                                    period, schedule, room, null, examStatus(schedule)));
                        }
                    }
                    for (ExamGradingAssignment assignment : gradingAssignments.findByScheduleId(schedule.getId())) {
                        if (!actor.id().equals(assignment.getTeacherId())) continue;
                        ExamCandidate sample = candidates
                                .findByScheduleIdAndClassId(schedule.getId(), assignment.getClassId())
                                .stream().findFirst().orElse(null);
                        items.add(agendaItem(assignment.getId() + ":grading", "GRADING", "Chấm thi",
                                period, schedule, null, sample, examStatus(schedule)));
                        items.add(agendaItem(assignment.getId() + ":score-entry", "GRADE_ENTRY", "Nhập điểm",
                                period, schedule, null, sample,
                                gradingStatus(period, schedule, assignment.getClassId())));
                    }
                }
            }
        }
        return items.stream().sorted(Comparator.comparing(ExamAgendaItem::examDate)
                .thenComparing(ExamAgendaItem::startTime).thenComparing(ExamAgendaItem::subjectName)).toList();
    }

    public List<ExamSchedule> schedules(String periodId) {
        requirePeriod(periodId);
        return schedules.findByExamPeriodId(periodId).stream()
                .sorted(Comparator.comparing(ExamSchedule::getExamDate).thenComparing(ExamSchedule::getStartTime)).toList();
    }

    @Transactional
    public ExamSchedule createSchedule(String periodId, SaveScheduleRequest r) {
        ExamPeriod period = requirePeriod(periodId); assertEditable(period);
        validateSchedule(period, r, null);
        invalidatePublishedSchedule(period);
        return schedules.save(ExamSchedule.builder().id(idOr(r.id(), "es"))
                .examPeriodId(periodId).subjectId(r.subjectId()).subjectName(structure.requireSubjectName(r.subjectId()))
                .examDate(r.examDate()).startTime(r.startTime()).durationMinutes(r.durationMinutes()).notes(clean(r.notes()))
                .classIds(new LinkedHashSet<>(r.classIds())).build());
    }

    @Transactional
    public ExamSchedule updateSchedule(String id, SaveScheduleRequest r) {
        ExamSchedule s = requireSchedule(id); ExamPeriod period = requirePeriod(s.getExamPeriodId()); assertEditable(period);
        validateSchedule(period, r, id);
        if (results.findByExamPeriodId(period.getId()).stream().anyMatch(result -> id.equals(result.getScheduleId()))) {
            throw ApiException.conflict("Ca thi đã có điểm; không thể sửa lịch thi");
        }
        clearDutyNotificationsForSchedule(s);
        List<ExamGradingAssignment> staleGraders = gradingAssignments.findByScheduleId(id).stream()
                .filter(assignment -> !r.subjectId().equals(assignment.getSubjectId())
                        || !r.classIds().contains(assignment.getClassId()))
                .toList();
        staleGraders.forEach(this::removeGradingNotifications);
        gradingAssignments.deleteAll(staleGraders);
        invalidatePublishedSchedule(period);
        s.setSubjectId(r.subjectId()); s.setSubjectName(structure.requireSubjectName(r.subjectId()));
        s.setExamDate(r.examDate()); s.setStartTime(r.startTime()); s.setDurationMinutes(r.durationMinutes()); s.setNotes(clean(r.notes()));
        s.setClassIds(new LinkedHashSet<>(r.classIds()));
        return schedules.save(s);
    }

    @Transactional public void deleteSchedule(String id) {
        ExamSchedule s = requireSchedule(id); ExamPeriod period = requirePeriod(s.getExamPeriodId()); assertEditable(period);
        if (results.findByExamPeriodId(period.getId()).stream().anyMatch(result -> id.equals(result.getScheduleId()))) {
            throw ApiException.conflict("Ca thi đã có điểm; không thể xóa lịch thi");
        }
        clearDutyNotificationsForSchedule(s);
        gradingAssignments.deleteAll(gradingAssignments.findByScheduleId(id));
        invalidatePublishedSchedule(period); schedules.delete(s);
    }

    public List<ExamRoom> rooms(String scheduleId) { requireSchedule(scheduleId); return rooms.findByScheduleId(scheduleId); }

    public ExamDayPolicy examDayPolicy(String scheduleId) {
        ExamSchedule schedule = requireSchedule(scheduleId);
        return new ExamDayPolicy(schedule.getExamDate(), true, "Ngày thi không học thời khóa biểu thường",
                "Các khối không thi được nghỉ cả ngày. Học sinh dự thi ra về sau ca thi cuối; "
                        + "lịch dạy thường không làm giáo viên hoặc phòng học bị đánh dấu bận.");
    }

    public boolean isPublishedExamDay(LocalDate date) {
        if (date == null) return false;
        return periods.findAll().stream().filter(ExamPeriod::isSchedulePublished)
                .flatMap(period -> schedules.findByExamPeriodId(period.getId()).stream())
                .anyMatch(schedule -> date.equals(schedule.getExamDate()));
    }

    public List<ExamRoomAvailability> roomAvailability(String scheduleId) {
        ExamSchedule schedule = requireSchedule(scheduleId);
        Map<String, ExamRoom> selected = rooms.findByScheduleId(scheduleId).stream()
                .collect(java.util.stream.Collectors.toMap(room -> room.getRoomCode().trim().toUpperCase(),
                        room -> room, (first, ignored) -> first));
        return structure.listRooms().stream().filter(room -> "ACTIVE".equalsIgnoreCase(room.getStatus()))
                .map(physical -> {
                    String code = physical.getCode().trim().toUpperCase();
                    ExamRoom selectedRoom = selected.get(code);
                    ExamSchedule conflict = rooms.findAll().stream()
                            .filter(existing -> existing.getRoomCode().equalsIgnoreCase(code)
                                    && !existing.getScheduleId().equals(scheduleId))
                            .map(existing -> schedules.findById(existing.getScheduleId()).orElse(null))
                            .filter(Objects::nonNull).filter(other -> overlaps(other, schedule)).findFirst().orElse(null);
                    boolean available = conflict == null;
                    String reason = selectedRoom != null ? "Đã chọn cho ca thi này"
                            : available ? "Sẵn sàng vì lịch học thường được tạm dừng trong ngày thi"
                            : "Đang được dùng cho ca thi trùng giờ";
                    return new ExamRoomAvailability(physical.getId(), code, physical.getName(),
                            physical.getCapacity() == null ? 0 : physical.getCapacity(), physical.getRoomType(),
                            available, selectedRoom != null, reason,
                            conflict == null ? null : conflict.getSubjectName(),
                            conflict == null ? null : conflict.getStartTime());
                })
                .sorted(Comparator.comparingInt((ExamRoomAvailability item) -> item.selected() ? 0 : item.available() ? 1 : 2)
                        .thenComparing(ExamRoomAvailability::roomCode))
                .toList();
    }

    @Transactional
    public List<ExamRoom> saveRooms(String scheduleId, BatchSaveRoomsRequest request) {
        ExamSchedule schedule = requireSchedule(scheduleId);
        ExamPeriod period = requirePeriod(schedule.getExamPeriodId());
        assertEditable(period);
        Map<String, com.sse.app.academic.structure.Room> physicalRooms = structure.listRooms().stream()
                .filter(room -> "ACTIVE".equalsIgnoreCase(room.getStatus()))
                .collect(java.util.stream.Collectors.toMap(
                        room -> room.getCode().trim().toUpperCase(), room -> room, (first, ignored) -> first));
        Set<String> existingCodes = rooms.findByScheduleId(scheduleId).stream()
                .map(room -> room.getRoomCode().toUpperCase()).collect(java.util.stream.Collectors.toSet());
        List<String> requestedCodes = request.roomCodes().stream().map(String::trim).map(String::toUpperCase)
                .distinct().filter(code -> !existingCodes.contains(code)).toList();
        if (requestedCodes.isEmpty()) throw ApiException.badRequest("Hãy chọn ít nhất một phòng chưa được thêm vào ca thi");
        List<ExamRoom> created = new ArrayList<>();
        for (String code : requestedCodes) {
            com.sse.app.academic.structure.Room physical = physicalRooms.get(code);
            if (physical == null) throw ApiException.badRequest("Phòng " + code + " không tồn tại hoặc không hoạt động");
            int capacity = physical.getCapacity() == null || physical.getCapacity() < 1 ? 1 : physical.getCapacity();
            SaveRoomRequest roomRequest = new SaveRoomRequest(null, code, capacity, null, null);
            assertRoomAndProctorsAvailable(schedule, roomRequest, null, null);
            created.add(rooms.save(ExamRoom.builder().id(Ids.gen("er")).scheduleId(scheduleId)
                    .roomCode(code).capacity(capacity).build()));
        }
        invalidatePublishedSchedule(period);
        return created;
    }

    @Transactional
    public ExamRoom saveRoom(String scheduleId, SaveRoomRequest r) {
        ExamSchedule schedule = requireSchedule(scheduleId); ExamPeriod period = requirePeriod(schedule.getExamPeriodId()); assertEditable(period);
        com.sse.app.academic.structure.Room physicalRoom = structure.listRooms().stream()
                .filter(room -> room.getCode().equalsIgnoreCase(r.roomCode())).findFirst()
                .orElseThrow(() -> ApiException.badRequest("Phòng thi không tồn tại"));
        if (!"ACTIVE".equalsIgnoreCase(physicalRoom.getStatus())) throw ApiException.badRequest("Phòng thi không hoạt động");
        if (physicalRoom.getCapacity() != null && r.capacity() > physicalRoom.getCapacity()) {
            throw ApiException.badRequest("Sức chứa sử dụng không được vượt quá sức chứa thực tế " + physicalRoom.getCapacity() + " chỗ");
        }
        User p1 = teacher(r.proctorOneId()); User p2 = teacher(r.proctorTwoId());
        if (p1 != null && p2 != null && p1.getId().equals(p2.getId())) throw ApiException.badRequest("Hai giám thị phải khác nhau");
        assertRoomAndProctorsAvailable(schedule, r, p1, p2);
        invalidatePublishedSchedule(period);
        ExamRoom room = r.id() == null ? new ExamRoom() : rooms.findById(r.id()).orElseThrow(() -> ApiException.notFound("Phòng thi"));
        if (room.getId() != null && !scheduleId.equals(room.getScheduleId())) {
            throw ApiException.badRequest("Phòng thi không thuộc ca thi đã chọn");
        }
        removeProctorNotifications(room);
        room.setId(room.getId() == null ? Ids.gen("er") : room.getId()); room.setScheduleId(scheduleId);
        room.setRoomCode(r.roomCode().trim().toUpperCase()); room.setCapacity(r.capacity());
        room.setProctorOneId(p1 == null ? null : p1.getId()); room.setProctorOneName(p1 == null ? null : p1.getFullName());
        room.setProctorTwoId(p2 == null ? null : p2.getId()); room.setProctorTwoName(p2 == null ? null : p2.getFullName());
        return rooms.save(room);
    }

    @Transactional
    public void deleteRoom(String id) {
        ExamRoom room = rooms.findById(id).orElseThrow(() -> ApiException.notFound("Phòng thi"));
        ExamSchedule schedule = requireSchedule(room.getScheduleId());
        assertEditable(requirePeriod(schedule.getExamPeriodId()));
        if (candidates.countByExamRoomId(id) > 0) {
            throw ApiException.conflict("Phòng thi đã có thí sinh; hãy phân lại phòng trước khi xóa");
        }
        ExamPeriod period = requirePeriod(schedule.getExamPeriodId());
        removeProctorNotifications(room);
        invalidatePublishedSchedule(period);
        rooms.delete(room);
    }

    public List<ExamGradingAssignment> gradingAssignments(String scheduleId) {
        requireSchedule(scheduleId);
        return gradingAssignments.findByScheduleId(scheduleId).stream()
                .sorted(Comparator.comparing(ExamGradingAssignment::getClassCode))
                .toList();
    }

    public List<EligibleGrader> eligibleGraders(String scheduleId) {
        ExamSchedule schedule = requireSchedule(scheduleId);
        ExamPeriod period = requirePeriod(schedule.getExamPeriodId());
        return users.list("TEACHER", null, null).stream()
                .filter(user -> isQualifiedForSubject(user, schedule, period))
                .map(user -> new EligibleGrader(user.id(), user.teacherCode(), user.fullName()))
                .sorted(Comparator.comparing(EligibleGrader::teacherName))
                .toList();
    }

    @Transactional
    public ExamGradingAssignment saveGradingAssignment(String scheduleId,
                                                        SaveGradingAssignmentRequest request,
                                                        String actorId) {
        ExamSchedule schedule = requireSchedule(scheduleId);
        ExamPeriod period = requirePeriod(schedule.getExamPeriodId());
        assertEditable(period);
        if (schedule.getClassIds() == null || !schedule.getClassIds().contains(request.classId())) {
            throw ApiException.badRequest("Lớp không thuộc phạm vi của ca thi đã chọn");
        }
        SchoolClass schoolClass = structure.getClass(request.classId());
        User teacher = users.getById(request.teacherId());
        if (!"TEACHER".equals(teacher.getRole()) || !"ACTIVE".equals(teacher.getStatus())) {
            throw ApiException.badRequest("Chỉ được phân công giáo viên đang hoạt động");
        }
        if (!isQualifiedForSubject(teacher, schedule, period)) {
            throw ApiException.badRequest("Giáo viên không đúng chuyên môn "
                    + schedule.getSubjectName() + " của bài thi");
        }

        ExamGradingAssignment assignment = gradingAssignments
                .findByScheduleIdAndClassId(scheduleId, request.classId())
                .orElseGet(() -> ExamGradingAssignment.builder()
                        .id(Ids.gen("ega"))
                        .examPeriodId(period.getId())
                        .scheduleId(scheduleId)
                        .classId(schoolClass.getId())
                        .classCode(schoolClass.getCode())
                        .subjectId(schedule.getSubjectId())
                        .subjectName(schedule.getSubjectName())
                        .build());
        if (assignment.getTeacherId() != null && !assignment.getTeacherId().equals(teacher.getId())
                && hasEnteredScores(schedule, request.classId())) {
            throw ApiException.conflict("Lớp đã có điểm thi; không thể đổi giáo viên chấm thi");
        }
        removeGradingNotifications(assignment);
        assignment.setExamPeriodId(period.getId());
        assignment.setClassCode(schoolClass.getCode());
        assignment.setSubjectId(schedule.getSubjectId());
        assignment.setSubjectName(schedule.getSubjectName());
        assignment.setTeacherId(teacher.getId());
        assignment.setTeacherName(teacher.getFullName());
        assignment.setAssignedAt(Instant.now());
        assignment.setAssignedBy(actorId);
        invalidatePublishedSchedule(period);
        return gradingAssignments.save(assignment);
    }

    @Transactional
    public void deleteGradingAssignment(String id) {
        ExamGradingAssignment assignment = gradingAssignments.findById(id)
                .orElseThrow(() -> ApiException.notFound("Phân công chấm thi"));
        ExamSchedule schedule = requireSchedule(assignment.getScheduleId());
        ExamPeriod period = requirePeriod(schedule.getExamPeriodId());
        assertEditable(period);
        if (hasEnteredScores(schedule, assignment.getClassId())) {
            throw ApiException.conflict("Lớp đã có điểm thi; không thể xóa phân công chấm thi");
        }
        removeGradingNotifications(assignment);
        invalidatePublishedSchedule(period);
        gradingAssignments.delete(assignment);
    }

    @Transactional
    public List<ExamCandidate> allocate(String roomId, String classId) {
        ExamRoom room = rooms.findById(roomId).orElseThrow(() -> ApiException.notFound("Phòng thi"));
        ExamSchedule schedule = requireSchedule(room.getScheduleId()); ExamPeriod period = requirePeriod(schedule.getExamPeriodId()); assertEditable(period);
        SchoolClass schoolClass = structure.getClass(classId);
        if (period.getGradeLevel() != null && !period.getGradeLevel().equals(schoolClass.getGradeLevel()))
            throw ApiException.badRequest("Lớp không thuộc khối áp dụng của kỳ thi");
        if (schedule.getClassIds() == null || !schedule.getClassIds().contains(classId))
            throw ApiException.badRequest("Lớp chưa được chọn trong phạm vi của ca thi");
        assertClassScheduleAvailable(schedule, classId);
        List<UserDto> students = users.list("STUDENT", null, classId).stream()
                .sorted(Comparator.comparing(UserDto::studentCode, Comparator.nullsLast(String::compareTo)).thenComparing(UserDto::fullName)).toList();
        long occupiedByOtherClasses = candidates.findByScheduleId(schedule.getId()).stream()
                .filter(candidate -> !classId.equals(candidate.getClassId()))
                .filter(candidate -> roomId.equals(candidate.getExamRoomId()))
                .count();
        if (occupiedByOtherClasses + students.size() > room.getCapacity()) {
            throw ApiException.conflict("Số thí sinh vượt quá sức chứa còn lại của phòng thi");
        }
        candidates.deleteByScheduleIdAndClassId(schedule.getId(), classId);
        invalidatePublishedSchedule(period);
        List<ExamCandidate> created = new ArrayList<>();
        Map<String, String> existingNumbers = candidates.findByExamPeriodId(period.getId()).stream()
                .collect(java.util.stream.Collectors.toMap(ExamCandidate::getStudentId, ExamCandidate::getCandidateNo, (first, second) -> first));
        int nextCandidateNumber = candidates.findByExamPeriodId(period.getId()).stream()
                .map(ExamCandidate::getCandidateNo).filter(Objects::nonNull).filter(value -> value.matches("\\d{6}"))
                .mapToInt(Integer::parseInt).max().orElse(0) + 1;
        for (int index = 0; index < students.size(); index++) {
            UserDto student = students.get(index);
            String candidateNo = existingNumbers.get(student.id());
            if (candidateNo == null || !candidateNo.matches("\\d{6}")) {
                if (nextCandidateNumber > 999999) throw ApiException.conflict("Đã vượt quá giới hạn số báo danh 6 chữ số");
                candidateNo = String.format("%06d", nextCandidateNumber++);
                existingNumbers.put(student.id(), candidateNo);
            }
            created.add(ExamCandidate.builder().id(Ids.gen("ec")).examPeriodId(period.getId()).scheduleId(schedule.getId())
                    .examRoomId(roomId).studentId(student.id()).studentName(student.fullName()).studentCode(student.studentCode())
                    .classId(classId).classCode(schoolClass.getCode()).candidateNo(candidateNo)
                    .seatNo(Math.toIntExact(occupiedByOtherClasses) + index + 1).build());
        }
        return candidates.saveAll(created);
    }

    public List<ExamCandidate> candidates(String periodId, String scheduleId, String classId) {
        requirePeriod(periodId);
        return candidates.findByExamPeriodId(periodId).stream()
                .filter(c -> scheduleId == null || scheduleId.equals(c.getScheduleId()))
                .filter(c -> classId == null || classId.equals(c.getClassId()))
                .sorted(Comparator.comparing(ExamCandidate::getCandidateNo).thenComparingInt(ExamCandidate::getSeatNo)).toList();
    }

    public OrganizationReadiness organizationReadiness(String scheduleId) {
        ExamSchedule schedule = requireSchedule(scheduleId);
        List<CandidateSeed> expected = expectedCandidates(schedule);
        List<ExamRoom> scheduleRooms = rooms.findByScheduleId(scheduleId);
        List<ExamCandidate> allocated = candidates.findByScheduleId(scheduleId);
        int totalCapacity = scheduleRooms.stream().mapToInt(ExamRoom::getCapacity).sum();
        int proctoredCapacity = scheduleRooms.stream().filter(this::hasMainProctor).mapToInt(ExamRoom::getCapacity).sum();
        int proctoredRooms = (int) scheduleRooms.stream().filter(this::hasMainProctor).count();
        Set<String> expectedIds = expected.stream().map(CandidateSeed::studentId).collect(java.util.stream.Collectors.toSet());
        int allocatedCount = (int) allocated.stream().filter(candidate -> expectedIds.contains(candidate.getStudentId()))
                .map(ExamCandidate::getStudentId).distinct().count();
        Map<String, ExamRoom> roomById = scheduleRooms.stream()
                .collect(java.util.stream.Collectors.toMap(ExamRoom::getId, room -> room));
        long invalidRoomAssignments = allocated.stream().filter(candidate -> !roomById.containsKey(candidate.getExamRoomId())).count();
        List<String> overCapacityRooms = allocated.stream().filter(candidate -> roomById.containsKey(candidate.getExamRoomId()))
                .collect(java.util.stream.Collectors.groupingBy(ExamCandidate::getExamRoomId, java.util.stream.Collectors.counting()))
                .entrySet().stream().filter(entry -> entry.getValue() > roomById.get(entry.getKey()).getCapacity())
                .map(entry -> roomById.get(entry.getKey()).getRoomCode()).sorted().toList();
        long uniqueSeats = allocated.stream().map(candidate -> candidate.getExamRoomId() + "|" + candidate.getSeatNo()).distinct().count();
        long validCandidateNumbers = allocated.stream().map(ExamCandidate::getCandidateNo)
                .filter(Objects::nonNull).filter(number -> number.matches("\\d{6}")).distinct().count();
        boolean arrangementValid = invalidRoomAssignments == 0 && overCapacityRooms.isEmpty()
                && uniqueSeats == allocated.size() && validCandidateNumbers == allocated.size();
        int missingSeats = Math.max(0, expected.size() - totalCapacity);
        int missingCandidates = Math.max(0, expected.size() - allocatedCount);
        List<String> warnings = new ArrayList<>();
        if (expected.isEmpty()) warnings.add("Chưa có học sinh thuộc các lớp dự thi");
        if (scheduleRooms.isEmpty()) warnings.add("Chưa chọn phòng thi");
        if (missingSeats > 0) warnings.add("Thiếu " + missingSeats + " chỗ ngồi");
        if (proctoredRooms < scheduleRooms.size()) warnings.add("Còn " + (scheduleRooms.size() - proctoredRooms) + " phòng chưa có giám thị chính");
        if (missingCandidates > 0) warnings.add("Còn " + missingCandidates + " thí sinh chưa được xếp phòng");
        if (invalidRoomAssignments > 0) warnings.add(invalidRoomAssignments + " thí sinh đang tham chiếu phòng không hợp lệ");
        if (!overCapacityRooms.isEmpty()) warnings.add("Phòng vượt sức chứa: " + String.join(", ", overCapacityRooms));
        if (uniqueSeats != allocated.size()) warnings.add("Có số ghế bị trùng trong cùng phòng");
        if (validCandidateNumbers != allocated.size()) warnings.add("Số báo danh chưa đủ 6 chữ số hoặc bị trùng");
        boolean roomsReady = !expected.isEmpty() && !scheduleRooms.isEmpty() && missingSeats == 0
                && proctoredRooms == scheduleRooms.size();
        boolean candidatesReady = !expected.isEmpty() && missingCandidates == 0 && allocatedCount == expected.size()
                && allocated.size() == expected.size() && arrangementValid;
        return new OrganizationReadiness(expected.size(), allocatedCount, totalCapacity, proctoredCapacity,
                scheduleRooms.size(), proctoredRooms, missingSeats, missingCandidates, roomsReady, candidatesReady,
                List.copyOf(warnings));
    }

    public List<OrganizationPlanView> organizationPlans(String scheduleId) {
        requireSchedule(scheduleId);
        return jdbc.query("select id from exam_organization_plans where schedule_id=? order by created_at desc",
                (rs, row) -> rs.getString(1), scheduleId).stream().map(this::organizationPlan).toList();
    }

    @Transactional
    public OrganizationPlanView previewOrganizationPlan(String scheduleId, PreviewOrganizationPlanRequest request,
                                                        String actorId) {
        ExamSchedule schedule = requireSchedule(scheduleId);
        ExamPeriod period = requirePeriod(schedule.getExamPeriodId());
        assertEditable(period);
        int maxPerRoom = request.maxCandidatesPerRoom();
        int studentsPerDesk = request.studentsPerDesk();
        boolean includeSecond = Boolean.TRUE.equals(request.includeSecondProctor());
        List<CandidateSeed> expected = expectedCandidates(schedule);
        if (expected.isEmpty()) throw ApiException.conflict("Các lớp dự thi chưa có học sinh để tổ chức ca thi");

        Map<String, com.sse.app.academic.structure.Room> physicalByCode = structure.listRooms().stream()
                .filter(room -> "ACTIVE".equalsIgnoreCase(room.getStatus()))
                .collect(java.util.stream.Collectors.toMap(room -> room.getCode().trim().toUpperCase(),
                        room -> room, (first, ignored) -> first));
        List<ExamRoomAvailability> available = roomAvailability(scheduleId).stream()
                .filter(ExamRoomAvailability::available).filter(item -> item.capacity() > 0)
                .sorted(Comparator.comparingInt((ExamRoomAvailability item) -> -Math.min(item.capacity(), maxPerRoom))
                        .thenComparing(ExamRoomAvailability::roomCode)).toList();
        List<OrganizationRoomDraft> selected = new ArrayList<>();
        int selectedCapacity = 0;
        for (ExamRoomAvailability item : available) {
            if (selectedCapacity >= expected.size()) break;
            int effective = Math.min(item.capacity(), maxPerRoom);
            selected.add(new OrganizationRoomDraft(Ids.gen("er"), item.roomCode(), item.capacity(), effective,
                    null, null, null, null, 0));
            selectedCapacity += effective;
        }
        if (selected.isEmpty()) throw ApiException.conflict("Không có phòng thi khả dụng trong khung giờ đã chọn");

        Map<String, Integer> dutyCounts = proctorDutyCounts();
        List<UserDto> teacherPool = activeTeachers();
        Set<String> usedTeachers = new HashSet<>();
        List<OrganizationRoomDraft> proctored = new ArrayList<>();
        for (OrganizationRoomDraft room : selected) {
            UserDto first = chooseProctor(teacherPool, usedTeachers, schedule, period, dutyCounts);
            if (first != null) usedTeachers.add(first.id());
            UserDto second = includeSecond ? chooseProctor(teacherPool, usedTeachers, schedule, period, dutyCounts) : null;
            if (second != null) usedTeachers.add(second.id());
            proctored.add(room.withProctors(first, second));
        }

        Map<String, String> stableNumbers = candidates.findByExamPeriodId(period.getId()).stream()
                .filter(candidate -> candidate.getCandidateNo() != null && candidate.getCandidateNo().matches("\\d{6}"))
                .collect(java.util.stream.Collectors.toMap(ExamCandidate::getStudentId, ExamCandidate::getCandidateNo,
                        (first, ignored) -> first, LinkedHashMap::new));
        Set<String> usedNumbers = new HashSet<>(stableNumbers.values());
        int nextNumber = usedNumbers.stream().mapToInt(Integer::parseInt).max().orElse(0) + 1;
        Map<String, Integer> remaining = new LinkedHashMap<>();
        Map<String, Integer> nextSeat = new LinkedHashMap<>();
        proctored.forEach(room -> { remaining.put(room.roomId(), room.effectiveCapacity()); nextSeat.put(room.roomId(), 1); });
        Map<String, OrganizationRoomDraft> lastRoomByClass = new HashMap<>();
        List<OrganizationCandidateDraft> proposed = new ArrayList<>();
        for (CandidateSeed seed : expected) {
            String number = stableNumbers.get(seed.studentId());
            if (number == null) {
                while (usedNumbers.contains(String.format("%06d", nextNumber))) nextNumber++;
                if (nextNumber > 999999) throw ApiException.conflict("Đã vượt giới hạn số báo danh 6 chữ số");
                number = String.format("%06d", nextNumber++);
                stableNumbers.put(seed.studentId(), number); usedNumbers.add(number);
            }
            OrganizationRoomDraft target = lastRoomByClass.get(seed.classId());
            if (target == null || remaining.getOrDefault(target.roomId(), 0) == 0) {
                target = proctored.stream().filter(room -> remaining.getOrDefault(room.roomId(), 0) > 0)
                        .max(Comparator.comparingInt(room -> remaining.get(room.roomId()))).orElse(null);
            }
            if (target == null) continue;
            lastRoomByClass.put(seed.classId(), target);
            int seatNo = nextSeat.get(target.roomId());
            nextSeat.put(target.roomId(), seatNo + 1);
            remaining.put(target.roomId(), remaining.get(target.roomId()) - 1);
            proposed.add(new OrganizationCandidateDraft(Ids.gen("ec"), seed, number, target.roomId(),
                    target.roomCode(), seatNo, ((seatNo - 1) / studentsPerDesk) + 1,
                    ((seatNo - 1) % studentsPerDesk) + 1));
        }
        Map<String, Long> counts = proposed.stream().collect(java.util.stream.Collectors.groupingBy(
                OrganizationCandidateDraft::roomId, java.util.stream.Collectors.counting()));
        List<OrganizationRoomDraft> finalizedRooms = proctored.stream()
                .map(room -> room.withCandidateCount(counts.getOrDefault(room.roomId(), 0L).intValue())).toList();
        int missingCandidates = expected.size() - proposed.size();
        int missingProctors = (int) finalizedRooms.stream().filter(room -> room.proctorOneId() == null
                || (includeSecond && room.proctorTwoId() == null)).count();
        List<String> warnings = new ArrayList<>();
        if (missingCandidates > 0) warnings.add("Thiếu " + missingCandidates + " chỗ ngồi");
        if (missingProctors > 0) warnings.add("Thiếu giám thị cho " + missingProctors + " phòng");
        if (maxPerRoom < available.stream().mapToInt(ExamRoomAvailability::capacity).max().orElse(maxPerRoom))
            warnings.add("Đã giới hạn tối đa " + maxPerRoom + " thí sinh mỗi phòng theo cấu hình");

        jdbc.update("update exam_organization_plans set status='SUPERSEDED' where schedule_id=? and status='PREVIEW'", scheduleId);
        String planId = Ids.gen("eop");
        jdbc.update("""
                insert into exam_organization_plans
                (id,schedule_id,status,max_candidates_per_room,students_per_desk,include_second_proctor,
                 candidate_count,room_count,effective_capacity,assigned_count,missing_assignment_count,
                 source_fingerprint,warning_summary,created_by,created_at)
                values (?,?,'PREVIEW',?,?,?,?,?,?,?,?,?,?,?,?)
                """, planId, scheduleId, maxPerRoom, studentsPerDesk, includeSecond, expected.size(),
                finalizedRooms.size(), selectedCapacity, proposed.size(), missingCandidates + missingProctors,
                organizationSourceFingerprint(schedule), warnings.isEmpty() ? null : String.join(" · ", warnings),
                actorId, Timestamp.from(Instant.now()));
        snapshotCurrentOrganization(planId, scheduleId, physicalByCode);
        finalizedRooms.forEach(room -> insertOrganizationRoom(planId, "PROPOSED", room));
        proposed.forEach(candidate -> insertOrganizationCandidate(planId, "PROPOSED", candidate));
        return organizationPlan(planId);
    }

    @Transactional
    public OrganizationPlanView applyOrganizationPlan(String planId, String actorId) {
        OrganizationPlanView plan = lockedOrganizationPlan(planId);
        if (!"PREVIEW".equals(plan.status())) throw ApiException.conflict("Chỉ bản xem trước mới có thể áp dụng");
        if (plan.missingAssignmentCount() > 0) throw ApiException.conflict("Phương án chưa đủ phòng, giám thị hoặc chỗ ngồi");
        ExamSchedule schedule = requireSchedule(plan.scheduleId());
        ExamPeriod period = requirePeriod(schedule.getExamPeriodId());
        assertEditable(period);
        if (!organizationPlanFingerprint(planId).equals(organizationSourceFingerprint(schedule)))
            throw ApiException.conflict("Dữ liệu phòng, giáo viên hoặc thí sinh đã thay đổi; hãy tạo lại bản xem trước");
        validateOrganizationProposal(schedule, period, plan.rooms(), plan.candidates(),
                plan.includeSecondProctor(), plan.studentsPerDesk());
        replaceOrganizationState(schedule, plan.rooms(), plan.candidates());
        jdbc.update("update exam_organization_plans set status='SUPERSEDED' where schedule_id=? and status='APPLIED'", schedule.getId());
        jdbc.update("update exam_organization_plans set status='APPLIED',applied_by=?,applied_at=? where id=?",
                actorId, Timestamp.from(Instant.now()), planId);
        invalidatePublishedSchedule(period);
        return organizationPlan(planId);
    }

    @Transactional
    public OrganizationPlanView undoOrganizationPlan(String planId, String actorId) {
        OrganizationPlanView plan = lockedOrganizationPlan(planId);
        if (!"APPLIED".equals(plan.status())) throw ApiException.conflict("Chỉ phương án đang áp dụng mới có thể hoàn tác");
        ExamSchedule schedule = requireSchedule(plan.scheduleId());
        ExamPeriod period = requirePeriod(schedule.getExamPeriodId());
        assertEditable(period);
        if (!matchesOrganizationState(schedule.getId(), plan.rooms(), plan.candidates()))
            throw ApiException.conflict("Dữ liệu tổ chức ca thi đã được chỉnh sửa; không thể hoàn tác an toàn");
        replaceOrganizationState(schedule, organizationRoomRows(planId, "PREVIOUS", plan.studentsPerDesk()),
                organizationCandidateRows(planId, "PREVIOUS"));
        jdbc.update("update exam_organization_plans set status='UNDONE',undone_by=?,undone_at=? where id=?",
                actorId, Timestamp.from(Instant.now()), planId);
        invalidatePublishedSchedule(period);
        return organizationPlan(planId);
    }

    public List<SeatingPlanView> seatingPlans(String scheduleId) {
        requireSchedule(scheduleId);
        return seatingPlans.findByScheduleIdOrderByCreatedAtDesc(scheduleId).stream().map(this::seatingPlanView).toList();
    }

    @Transactional
    public SeatingPlanView previewSeatingPlan(String scheduleId, PreviewSeatingPlanRequest request, String actorId) {
        ExamSchedule schedule = requireSchedule(scheduleId);
        ExamPeriod period = requirePeriod(schedule.getExamPeriodId());
        assertEditable(period);
        List<ExamRoom> availableRooms = rooms.findByScheduleId(scheduleId).stream()
                .sorted(Comparator.comparing(ExamRoom::getRoomCode)).toList();
        Set<String> selectedIds = request != null && request.roomIds() != null && !request.roomIds().isEmpty()
                ? new LinkedHashSet<>(request.roomIds())
                : availableRooms.stream().map(ExamRoom::getId).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<ExamRoom> selectedRooms = availableRooms.stream().filter(room -> selectedIds.contains(room.getId())).toList();
        if (selectedRooms.isEmpty()) throw ApiException.badRequest("Hãy chọn ít nhất một phòng để tạo phương án");
        if (selectedRooms.size() != selectedIds.size()) throw ApiException.badRequest("Có phòng không thuộc ca thi đã chọn");

        List<CandidateSeed> expected = expectedCandidates(schedule);
        if (expected.isEmpty()) throw ApiException.conflict("Các lớp dự thi chưa có học sinh để xếp phòng");
        List<ExamCandidate> previous = candidates.findByScheduleId(scheduleId);
        Map<String, String> stableNumbers = candidates.findByExamPeriodId(period.getId()).stream()
                .filter(candidate -> candidate.getCandidateNo() != null && candidate.getCandidateNo().matches("\\d{6}"))
                .collect(java.util.stream.Collectors.toMap(ExamCandidate::getStudentId, ExamCandidate::getCandidateNo,
                        (first, ignored) -> first, LinkedHashMap::new));
        Set<String> usedNumbers = new HashSet<>(stableNumbers.values());
        int nextNumber = usedNumbers.stream().mapToInt(Integer::parseInt).max().orElse(0) + 1;

        Map<String, Integer> remaining = new LinkedHashMap<>();
        Map<String, Integer> nextSeat = new LinkedHashMap<>();
        selectedRooms.forEach(room -> { remaining.put(room.getId(), room.getCapacity()); nextSeat.put(room.getId(), 1); });
        Map<String, ExamRoom> roomById = selectedRooms.stream().collect(java.util.stream.Collectors.toMap(ExamRoom::getId, room -> room));
        List<PlannedCandidate> proposed = new ArrayList<>();
        for (CandidateSeed seed : expected) {
            String number = stableNumbers.get(seed.studentId());
            if (number == null) {
                while (usedNumbers.contains(String.format("%06d", nextNumber))) nextNumber++;
                if (nextNumber > 999999) throw ApiException.conflict("Đã vượt giới hạn số báo danh 6 chữ số");
                number = String.format("%06d", nextNumber++);
                stableNumbers.put(seed.studentId(), number);
                usedNumbers.add(number);
            }
            ExamRoom target = chooseRoomForClass(seed.classId(), proposed, selectedRooms, remaining);
            if (target == null) {
                proposed.add(new PlannedCandidate(seed, number, null, null));
            } else {
                int seat = nextSeat.get(target.getId());
                nextSeat.put(target.getId(), seat + 1);
                remaining.put(target.getId(), remaining.get(target.getId()) - 1);
                proposed.add(new PlannedCandidate(seed, number, target, seat));
            }
        }

        int assigned = (int) proposed.stream().filter(item -> item.room() != null).count();
        int totalCapacity = selectedRooms.stream().mapToInt(ExamRoom::getCapacity).sum();
        List<String> warnings = new ArrayList<>();
        if (assigned < expected.size()) warnings.add("Thiếu " + (expected.size() - assigned) + " chỗ ngồi; hãy chọn thêm phòng");
        long missingProctors = selectedRooms.stream().filter(room -> !hasMainProctor(room)).count();
        if (missingProctors > 0) warnings.add("Còn " + missingProctors + " phòng chưa có giám thị chính");
        long splitClasses = proposed.stream().filter(item -> item.room() != null)
                .collect(java.util.stream.Collectors.groupingBy(item -> item.seed().classId(),
                        java.util.stream.Collectors.mapping(item -> item.room().getId(), java.util.stream.Collectors.toSet())))
                .values().stream().filter(roomIds -> roomIds.size() > 1).count();
        if (splitClasses > 0) warnings.add(splitClasses + " lớp được chia sang nhiều phòng để tận dụng sức chứa");

        seatingPlans.findByScheduleIdOrderByCreatedAtDesc(scheduleId).stream()
                .filter(existing -> "PREVIEW".equals(existing.getStatus()))
                .forEach(existing -> existing.setStatus("SUPERSEDED"));
        ExamSeatingPlan plan = seatingPlans.save(ExamSeatingPlan.builder().id(Ids.gen("esp"))
                .examPeriodId(period.getId()).scheduleId(scheduleId).status("PREVIEW")
                .candidateCount(expected.size()).totalCapacity(totalCapacity).assignedCount(assigned)
                .unassignedCount(expected.size() - assigned)
                .sourceFingerprint(scheduleSourceFingerprint(schedule, selectedRooms, expected, previous))
                .selectedRoomIds(selectedRooms.stream().map(ExamRoom::getId).collect(java.util.stream.Collectors.joining(",")))
                .warningSummary(String.join(" · ", warnings)).createdBy(actorId).createdAt(Instant.now()).build());
        List<ExamSeatingPlanItem> items = new ArrayList<>();
        previous.forEach(candidate -> items.add(planItem(plan.getId(), "PREVIOUS", candidate)));
        proposed.forEach(item -> items.add(planItem(plan.getId(), item)));
        seatingPlanItems.saveAll(items);
        return seatingPlanView(plan);
    }

    @Transactional
    public SeatingPlanView applySeatingPlan(String planId, String actorId) {
        ExamSeatingPlan plan = seatingPlans.findById(planId).orElseThrow(() -> ApiException.notFound("Phương án xếp phòng"));
        if (!"PREVIEW".equals(plan.getStatus())) throw ApiException.conflict("Chỉ phương án đang xem trước mới có thể áp dụng");
        ExamSchedule schedule = requireSchedule(plan.getScheduleId());
        ExamPeriod period = requirePeriod(schedule.getExamPeriodId());
        assertEditable(period);
        List<ExamRoom> selectedRooms = selectedRooms(plan, schedule);
        List<CandidateSeed> expected = expectedCandidates(schedule);
        String currentFingerprint = scheduleSourceFingerprint(schedule, selectedRooms, expected, candidates.findByScheduleId(schedule.getId()));
        if (!plan.getSourceFingerprint().equals(currentFingerprint)) {
            throw ApiException.conflict("Dữ liệu lớp, phòng hoặc thí sinh đã thay đổi; hãy tạo lại bản xem trước");
        }
        if (plan.getUnassignedCount() > 0) throw ApiException.conflict("Phương án còn " + plan.getUnassignedCount() + " thí sinh chưa có chỗ");
        List<ExamRoom> missingProctor = selectedRooms.stream().filter(room -> !hasMainProctor(room)).toList();
        if (!missingProctor.isEmpty()) throw ApiException.conflict("Chưa có giám thị chính cho phòng "
                + missingProctor.stream().map(ExamRoom::getRoomCode).collect(java.util.stream.Collectors.joining(", ")));
        selectedRooms.forEach(room -> assertRoomAndProctorsAvailable(schedule,
                new SaveRoomRequest(room.getId(), room.getRoomCode(), room.getCapacity(), room.getProctorOneId(), room.getProctorTwoId()),
                teacher(room.getProctorOneId()), teacher(room.getProctorTwoId())));
        List<ExamSeatingPlanItem> proposed = seatingPlanItems.findByPlanIdAndRowType(planId, "PROPOSED");
        if (proposed.size() != plan.getCandidateCount() || proposed.stream().anyMatch(item -> item.getExamRoomId() == null)) {
            throw ApiException.conflict("Phương án không đầy đủ; hãy tạo lại bản xem trước");
        }
        candidates.deleteByScheduleId(schedule.getId());
        candidates.flush();
        candidates.saveAll(proposed.stream().map(item -> candidateFromPlan(plan, item)).toList());
        seatingPlans.findByScheduleIdOrderByCreatedAtDesc(schedule.getId()).stream()
                .filter(other -> "APPLIED".equals(other.getStatus())).forEach(other -> other.setStatus("SUPERSEDED"));
        plan.setStatus("APPLIED"); plan.setAppliedBy(actorId); plan.setAppliedAt(Instant.now());
        invalidatePublishedSchedule(period);
        return seatingPlanView(seatingPlans.save(plan));
    }

    @Transactional
    public SeatingPlanView undoSeatingPlan(String planId, String actorId) {
        ExamSeatingPlan plan = seatingPlans.findById(planId).orElseThrow(() -> ApiException.notFound("Phương án xếp phòng"));
        if (!"APPLIED".equals(plan.getStatus())) throw ApiException.conflict("Chỉ phương án đang được áp dụng mới có thể hoàn tác");
        ExamSchedule schedule = requireSchedule(plan.getScheduleId());
        ExamPeriod period = requirePeriod(schedule.getExamPeriodId());
        assertEditable(period);
        List<ExamSeatingPlanItem> proposed = seatingPlanItems.findByPlanIdAndRowType(planId, "PROPOSED");
        if (!matchesCurrentCandidates(proposed, candidates.findByScheduleId(schedule.getId()))) {
            throw ApiException.conflict("Danh sách hiện tại đã được chỉnh sửa; không thể hoàn tác an toàn");
        }
        List<ExamSeatingPlanItem> previous = seatingPlanItems.findByPlanIdAndRowType(planId, "PREVIOUS");
        candidates.deleteByScheduleId(schedule.getId());
        candidates.flush();
        candidates.saveAll(previous.stream().map(item -> candidateFromPlan(plan, item)).toList());
        plan.setStatus("UNDONE"); plan.setUndoneBy(actorId); plan.setUndoneAt(Instant.now());
        invalidatePublishedSchedule(period);
        return seatingPlanView(seatingPlans.save(plan));
    }

    public List<EligibleProctor> eligibleProctors(String scheduleId, String roomId) {
        ExamSchedule schedule = requireSchedule(scheduleId);
        ExamPeriod period = requirePeriod(schedule.getExamPeriodId());
        if (roomId != null && !roomId.isBlank()) {
            ExamRoom room = rooms.findById(roomId).orElseThrow(() -> ApiException.notFound("Phòng thi"));
            if (!scheduleId.equals(room.getScheduleId())) throw ApiException.badRequest("Phòng không thuộc ca thi đã chọn");
        }
        Map<String, Integer> dutyCounts = proctorDutyCounts();
        Set<String> occupiedInSchedule = rooms.findByScheduleId(scheduleId).stream()
                .filter(room -> roomId == null || !room.getId().equals(roomId))
                .flatMap(room -> java.util.stream.Stream.of(room.getProctorOneId(), room.getProctorTwoId()))
                .filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        return activeTeachers().stream()
                .filter(teacher -> !occupiedInSchedule.contains(teacher.id()))
                .filter(teacher -> teacherBusyReason(schedule, period, teacher.id(), true) == null)
                .sorted(proctorComparator(schedule, period, dutyCounts))
                .map(teacher -> new EligibleProctor(teacher.id(), teacher.teacherCode(), teacher.fullName(),
                        dutyCounts.getOrDefault(teacher.id(), 0), isQualifiedForSubject(teacher, schedule, period),
                        proctorRecommendation(teacher, schedule, period, dutyCounts)))
                .toList();
    }

    public List<ProctorPlanView> proctorPlans(String scheduleId) {
        requireSchedule(scheduleId);
        return proctorPlans.findByScheduleIdOrderByCreatedAtDesc(scheduleId).stream()
                .map(this::proctorPlanView).toList();
    }

    @Transactional
    public ProctorPlanView previewProctorPlan(String scheduleId, PreviewProctorPlanRequest request, String actorId) {
        ExamSchedule schedule = requireSchedule(scheduleId);
        ExamPeriod period = requirePeriod(schedule.getExamPeriodId());
        assertEditable(period);
        List<ExamRoom> scheduleRooms = rooms.findByScheduleId(scheduleId).stream()
                .sorted(Comparator.comparing(ExamRoom::getRoomCode)).toList();
        if (scheduleRooms.isEmpty()) throw ApiException.conflict("Hãy chọn phòng thi trước khi phân công giám thị");
        Set<String> lockedIds = request != null && request.lockedRoomIds() != null
                ? new HashSet<>(request.lockedRoomIds()) : Set.of();
        if (lockedIds.stream().anyMatch(id -> scheduleRooms.stream().noneMatch(room -> room.getId().equals(id)))) {
            throw ApiException.badRequest("Có phòng được khóa không thuộc ca thi đã chọn");
        }
        boolean includeSecond = request != null && Boolean.TRUE.equals(request.includeSecondProctor());
        Map<String, Integer> dutyCounts = proctorDutyCounts();
        List<UserDto> teacherPool = activeTeachers();
        Set<String> usedTeachers = new HashSet<>();
        List<ExamProctorPlanItem> drafted = new ArrayList<>();

        for (ExamRoom room : scheduleRooms) {
            if (!lockedIds.contains(room.getId())) continue;
            String status = "READY";
            String message = "Giữ nguyên theo lựa chọn đã khóa";
            if (!hasMainProctor(room)) {
                status = "MISSING"; message = "Phòng đã khóa nhưng chưa có giám thị chính";
            } else if (!reserveProctor(room.getProctorOneId(), usedTeachers)
                    || (room.getProctorTwoId() != null && !reserveProctor(room.getProctorTwoId(), usedTeachers))) {
                status = "CONFLICT"; message = "Giáo viên bị trùng giữa các phòng đã khóa";
            } else {
                String busy = teacherBusyReason(schedule, period, room.getProctorOneId(), true);
                if (busy == null && room.getProctorTwoId() != null) busy = teacherBusyReason(schedule, period, room.getProctorTwoId(), true);
                if (busy != null) { status = "CONFLICT"; message = busy; }
            }
            drafted.add(proctorPlanItem(null, room, true, room.getProctorOneId(), room.getProctorOneName(),
                    room.getProctorTwoId(), room.getProctorTwoName(), status, message, dutyCounts));
        }

        for (ExamRoom room : scheduleRooms) {
            if (lockedIds.contains(room.getId())) continue;
            UserDto first = chooseProctor(teacherPool, usedTeachers, schedule, period, dutyCounts);
            if (first != null) usedTeachers.add(first.id());
            UserDto second = includeSecond ? chooseProctor(teacherPool, usedTeachers, schedule, period, dutyCounts) : null;
            if (second != null) usedTeachers.add(second.id());
            String status = first == null || (includeSecond && second == null) ? "MISSING" : "READY";
            String message = first == null ? "Không còn giáo viên khả dụng cho giám thị chính"
                    : includeSecond && second == null ? "Đã có giám thị chính nhưng thiếu giám thị hỗ trợ"
                    : "Đề xuất theo lịch rảnh, khác môn thi và số ca đang phụ trách";
            drafted.add(proctorPlanItem(null, room, false,
                    first == null ? null : first.id(), first == null ? null : first.fullName(),
                    second == null ? null : second.id(), second == null ? null : second.fullName(),
                    status, message, dutyCounts));
        }
        drafted.sort(Comparator.comparing(ExamProctorPlanItem::getRoomCode));
        int readyCount = (int) drafted.stream().filter(item -> "READY".equals(item.getStatus())).count();
        int missingCount = drafted.size() - readyCount;
        String warning = missingCount == 0 ? "" : "Còn " + missingCount + " phòng cần điều chỉnh thủ công";
        proctorPlans.findByScheduleIdOrderByCreatedAtDesc(scheduleId).stream()
                .filter(existing -> "PREVIEW".equals(existing.getStatus()))
                .forEach(existing -> existing.setStatus("SUPERSEDED"));
        ExamProctorPlan plan = proctorPlans.save(ExamProctorPlan.builder().id(Ids.gen("epp"))
                .scheduleId(scheduleId).status("PREVIEW").includeSecondProctor(includeSecond)
                .roomCount(scheduleRooms.size()).readyRoomCount(readyCount).missingAssignmentCount(missingCount)
                .sourceFingerprint(proctorSourceFingerprint(schedule, period)).warningSummary(warning)
                .createdBy(actorId).createdAt(Instant.now()).build());
        drafted.forEach(item -> item.setPlanId(plan.getId()));
        proctorPlanItems.saveAll(drafted);
        return proctorPlanView(plan);
    }

    @Transactional
    public ProctorPlanView applyProctorPlan(String planId, String actorId) {
        ExamProctorPlan plan = proctorPlans.findById(planId).orElseThrow(() -> ApiException.notFound("Phương án giám thị"));
        if (!"PREVIEW".equals(plan.getStatus())) throw ApiException.conflict("Chỉ bản xem trước mới có thể áp dụng");
        ExamSchedule schedule = requireSchedule(plan.getScheduleId());
        ExamPeriod period = requirePeriod(schedule.getExamPeriodId());
        assertEditable(period);
        if (!plan.getSourceFingerprint().equals(proctorSourceFingerprint(schedule, period))) {
            throw ApiException.conflict("Lịch hoặc phân công đã thay đổi; hãy tạo lại bản xem trước");
        }
        if (plan.getMissingAssignmentCount() > 0) throw ApiException.conflict("Phương án còn phòng chưa đủ giám thị");
        List<ExamProctorPlanItem> items = proctorPlanItems.findByPlanId(planId);
        validateProctorProposal(schedule, period, items, false);
        Map<String, ExamRoom> roomById = rooms.findByScheduleId(schedule.getId()).stream()
                .collect(java.util.stream.Collectors.toMap(ExamRoom::getId, room -> room));
        for (ExamProctorPlanItem item : items) {
            ExamRoom room = roomById.get(item.getRoomId());
            if (room == null) throw ApiException.conflict("Phòng thi đã thay đổi; hãy tạo lại phương án");
            removeProctorNotifications(room);
            room.setProctorOneId(item.getProposedProctorOneId()); room.setProctorOneName(item.getProposedProctorOneName());
            room.setProctorTwoId(item.getProposedProctorTwoId()); room.setProctorTwoName(item.getProposedProctorTwoName());
        }
        rooms.saveAll(roomById.values());
        proctorPlans.findByScheduleIdOrderByCreatedAtDesc(schedule.getId()).stream()
                .filter(existing -> "APPLIED".equals(existing.getStatus())).forEach(existing -> existing.setStatus("SUPERSEDED"));
        plan.setStatus("APPLIED"); plan.setAppliedBy(actorId); plan.setAppliedAt(Instant.now());
        invalidatePublishedSchedule(period);
        return proctorPlanView(proctorPlans.save(plan));
    }

    @Transactional
    public ProctorPlanView undoProctorPlan(String planId, String actorId) {
        ExamProctorPlan plan = proctorPlans.findById(planId).orElseThrow(() -> ApiException.notFound("Phương án giám thị"));
        if (!"APPLIED".equals(plan.getStatus())) throw ApiException.conflict("Chỉ phương án đang áp dụng mới có thể hoàn tác");
        ExamSchedule schedule = requireSchedule(plan.getScheduleId());
        ExamPeriod period = requirePeriod(schedule.getExamPeriodId());
        assertEditable(period);
        List<ExamProctorPlanItem> items = proctorPlanItems.findByPlanId(planId);
        Map<String, ExamRoom> roomById = rooms.findByScheduleId(schedule.getId()).stream()
                .collect(java.util.stream.Collectors.toMap(ExamRoom::getId, room -> room));
        boolean unchanged = items.stream().allMatch(item -> {
            ExamRoom room = roomById.get(item.getRoomId());
            return room != null && Objects.equals(room.getProctorOneId(), item.getProposedProctorOneId())
                    && Objects.equals(room.getProctorTwoId(), item.getProposedProctorTwoId());
        });
        if (!unchanged) throw ApiException.conflict("Phân công hiện tại đã được chỉnh sửa; không thể hoàn tác an toàn");
        validateProctorProposal(schedule, period, items, true);
        for (ExamProctorPlanItem item : items) {
            ExamRoom room = roomById.get(item.getRoomId());
            removeProctorNotifications(room);
            room.setProctorOneId(item.getPreviousProctorOneId()); room.setProctorOneName(item.getPreviousProctorOneName());
            room.setProctorTwoId(item.getPreviousProctorTwoId()); room.setProctorTwoName(item.getPreviousProctorTwoName());
        }
        rooms.saveAll(roomById.values());
        plan.setStatus("UNDONE"); plan.setUndoneBy(actorId); plan.setUndoneAt(Instant.now());
        invalidatePublishedSchedule(period);
        return proctorPlanView(proctorPlans.save(plan));
    }

    public List<ExamResult> results(String periodId, String scheduleId, String studentId) {
        requirePeriod(periodId);
        return results.findByExamPeriodId(periodId).stream()
                .filter(r -> scheduleId == null || scheduleId.equals(r.getScheduleId()))
                .filter(r -> studentId == null || studentId.equals(r.getStudentId())).toList();
    }

    public List<TeacherGradingTask> gradingTasks(String teacherId) {
        User teacher = users.getById(teacherId);
        if (!"TEACHER".equals(teacher.getRole())) {
            throw ApiException.forbidden("Chỉ giáo viên được xem danh sách nhập điểm thi");
        }
        List<TeacherGradingTask> tasks = new ArrayList<>();
        for (ExamGradingAssignment assignment : gradingAssignments.findByTeacherId(teacherId)) {
            ExamPeriod period = periods.findById(assignment.getExamPeriodId()).orElse(null);
            if (period == null) continue;
            if (!period.isSchedulePublished()) continue;
            ExamSchedule schedule = schedules.findById(assignment.getScheduleId()).orElse(null);
            if (schedule == null || !schedule.getSubjectId().equals(assignment.getSubjectId())) continue;
            Map<String, ExamRoom> roomById = rooms.findByScheduleId(schedule.getId()).stream()
                    .collect(java.util.stream.Collectors.toMap(ExamRoom::getId, room -> room));
            Map<String, ExamResult> resultByStudent = results.findByExamPeriodId(period.getId()).stream()
                    .filter(result -> schedule.getId().equals(result.getScheduleId()))
                    .collect(java.util.stream.Collectors.toMap(ExamResult::getStudentId, result -> result));
            List<TeacherExamCandidateRow> rows = candidates
                    .findByScheduleIdAndClassId(schedule.getId(), assignment.getClassId()).stream()
                    .sorted(Comparator.comparing(ExamCandidate::getCandidateNo))
                    .map(candidate -> {
                        ExamResult result = resultByStudent.get(candidate.getStudentId());
                        ExamRoom room = roomById.get(candidate.getExamRoomId());
                        return new TeacherExamCandidateRow(candidate.getId(), candidate.getStudentId(),
                                candidate.getStudentName(), candidate.getStudentCode(), candidate.getCandidateNo(),
                                candidate.getSeatNo(), room == null ? null : room.getRoomCode(),
                                result == null ? null : result.getId(),
                                result == null ? null : result.getScore(),
                                result == null ? null : result.getNote(),
                                result == null ? "DRAFT" : result.getStatus(),
                                result == null ? null : result.getVersion());
                    }).toList();
            Instant opensAt = scoreEntryOpensAt(schedule);
            boolean available = !Instant.now().isBefore(opensAt);
            tasks.add(new TeacherGradingTask(period.getId(), period.getName(), schedule.getId(),
                    schedule.getSubjectId(), schedule.getSubjectName(), assignment.getClassId(),
                    assignment.getClassCode(), schedule.getExamDate(), schedule.getStartTime(), opensAt,
                    available, period.isScoreEntryLocked() || !available, rows));
        }
        return tasks.stream().sorted(Comparator.comparing(TeacherGradingTask::examDate)
                .thenComparing(TeacherGradingTask::startTime).thenComparing(TeacherGradingTask::classCode)).toList();
    }

    public List<StudentExamResultView> studentResults(String studentId) {
        User student = users.getById(studentId);
        if (!"STUDENT".equals(student.getRole())) throw ApiException.forbidden("Chỉ học sinh được xem kết quả thi cá nhân");
        Map<String, ExamReviewRequest> latestReview = reviews.findByStudentIdOrderByRequestedAtDesc(studentId).stream()
                .collect(java.util.stream.Collectors.toMap(ExamReviewRequest::getResultId, review -> review, (first, second) -> first));
        List<StudentExamResultView> output = new ArrayList<>();
        for (ExamPeriod period : periods.findAll()) {
            for (ExamResult result : results.findByExamPeriodIdAndStudentId(period.getId(), studentId)) {
                if (!"PUBLISHED".equals(result.getStatus())) continue;
                ExamSchedule schedule = requireSchedule(result.getScheduleId());
                ExamReviewRequest review = latestReview.get(result.getId());
                output.add(new StudentExamResultView(result.getId(), period.getId(), period.getName(), schedule.getId(),
                        schedule.getSubjectId(), schedule.getSubjectName(), result.getScore(), result.getNote(), result.getStatus(),
                        review == null ? null : review.getId(), review == null ? null : review.getStatus(),
                        review == null ? null : review.getReason(), review == null ? null : review.getResolution(),
                        review == null ? null : review.getResolvedScore()));
            }
        }
        return output;
    }

    @Transactional
    public List<ExamResult> saveResults(String periodId, SaveResultsRequest request, String actorId) {
        ExamPeriod period = requirePeriod(periodId);
        if (period.isScoreEntryLocked() || "CONFIRMED".equals(period.getStatus())) throw ApiException.conflict("Kỳ thi đã khóa nhập điểm");
        if (!period.isSchedulePublished()) {
            throw ApiException.conflict("Lịch thi chưa được công bố");
        }
        ExamSchedule schedule = requireSchedule(request.scheduleId());
        if (!periodId.equals(schedule.getExamPeriodId())) throw ApiException.badRequest("Lịch thi không thuộc kỳ thi");
        Instant opensAt = scoreEntryOpensAt(schedule);
        if (Instant.now().isBefore(opensAt)) {
            throw ApiException.conflict("Chức năng nhập điểm mở từ "
                    + opensAt.atZone(SCHOOL_ZONE).toLocalDateTime());
        }
        Map<String, ExamCandidate> candidateByStudent = candidates.findByScheduleId(schedule.getId()).stream()
                .collect(java.util.stream.Collectors.toMap(ExamCandidate::getStudentId, candidate -> candidate));
        User actor = users.getById(actorId);
        Instant now = Instant.now(); List<ExamResult> saved = new ArrayList<>();
        for (ResultEntry entry : request.entries()) {
            ExamCandidate candidate = candidateByStudent.get(entry.studentId());
            if (candidate == null) throw ApiException.badRequest("Học sinh chưa được phân phòng cho môn thi");
            ExamGradingAssignment gradingAssignment = gradingAssignments
                    .findByScheduleIdAndClassId(schedule.getId(), candidate.getClassId())
                    .orElse(null);
            if (!"TEACHER".equals(actor.getRole()) || gradingAssignment == null
                    || !actorId.equals(gradingAssignment.getTeacherId())
                    || !schedule.getSubjectId().equals(gradingAssignment.getSubjectId())) {
                throw ApiException.forbidden(
                        "Chỉ giáo viên được phân công chấm đúng môn thi và lớp này mới được nhập điểm");
            }
            ExamResult result = results.findByExamPeriodIdAndStudentIdAndSubjectId(periodId, entry.studentId(), schedule.getSubjectId())
                    .orElseGet(() -> ExamResult.builder().id(Ids.gen("exr")).examPeriodId(periodId).scheduleId(schedule.getId())
                            .studentId(entry.studentId()).subjectId(schedule.getSubjectId()).status("DRAFT").recordedAt(now).recordedBy(actorId).build());
            if (entry.expectedVersion() != null && !entry.expectedVersion().equals(result.getVersion())) throw ApiException.conflict("Điểm đã được người khác cập nhật, vui lòng tải lại");
            result.setScore(entry.score()); result.setNote(clean(entry.note())); result.setUpdatedAt(now); result.setUpdatedBy(actorId);
            saved.add(results.save(result));
        }
        return saved;
    }

    public List<ExamReviewRequest> reviews(String periodId, String status, String teacherId) {
        requirePeriod(periodId);
        return reviews.findByExamPeriodId(periodId).stream().filter(r -> status == null || status.equals(r.getStatus()))
                .filter(review -> canTeacherManageReview(teacherId, review))
                .sorted(Comparator.comparing(ExamReviewRequest::getRequestedAt).reversed()).toList();
    }

    public List<ExamReviewRequest> teacherReviews(String teacherId, String status) {
        return periods.findAll().stream().flatMap(period -> reviews.findByExamPeriodId(period.getId()).stream())
                .filter(review -> status == null || status.equals(review.getStatus()))
                .filter(review -> canTeacherManageReview(teacherId, review))
                .sorted(Comparator.comparing(ExamReviewRequest::getRequestedAt).reversed()).toList();
    }

    @Transactional
    public ExamReviewRequest requestReview(String periodId, CreateReviewRequest request, CurrentUser actor) {
        ExamResult result = results.findById(request.resultId()).orElseThrow(() -> ApiException.notFound("Kết quả thi"));
        if (!periodId.equals(result.getExamPeriodId())) throw ApiException.badRequest("Kết quả không thuộc kỳ thi");
        if (!"PUBLISHED".equals(result.getStatus())) throw ApiException.conflict("Kết quả chưa được công bố nên chưa thể phúc khảo");
        String studentId = actor.isStudent() ? actor.id() : result.getStudentId();
        if (actor.isStudent() && !actor.id().equals(result.getStudentId())) throw ApiException.forbidden("Không được phúc khảo kết quả của học sinh khác");
        if (actor.isParent()) users.assertParentOf(actor.id(), result.getStudentId());
        ExamSchedule schedule = requireSchedule(result.getScheduleId()); User student = users.getById(studentId);
        boolean hasPending = reviews.findByExamPeriodId(periodId).stream()
                .anyMatch(existing -> result.getId().equals(existing.getResultId()) && "PENDING".equals(existing.getStatus()));
        if (hasPending) throw ApiException.conflict("Môn học đang có yêu cầu phúc khảo chờ xử lý");
        ExamReviewRequest saved = reviews.save(ExamReviewRequest.builder().id(Ids.gen("erv")).examPeriodId(periodId).resultId(result.getId())
                .studentId(studentId).studentName(student.getFullName()).subjectId(result.getSubjectId()).subjectName(schedule.getSubjectName())
                .originalScore(result.getScore()).reason(request.reason().trim()).status("PENDING")
                .requestedAt(Instant.now()).requestedBy(actor.id()).build());
        ExamCandidate candidate = candidates.findByScheduleIdAndStudentId(schedule.getId(), studentId)
                .orElseThrow(() -> ApiException.notFound("Thí sinh"));
        gradingAssignments.findByScheduleIdAndClassId(schedule.getId(), candidate.getClassId())
                .map(ExamGradingAssignment::getTeacherId)
                .ifPresent(teacherId -> notifications.notifyUser(teacherId, "EXAM_REVIEW", "IMPORTANT",
                        "Có yêu cầu phúc khảo mới", student.getFullName() + " yêu cầu phúc khảo môn "
                                + schedule.getSubjectName() + ".", "EXAM_REVIEW", saved.getId()));
        return saved;
    }

    @Transactional
    public ExamReviewRequest resolveReview(String id, ResolveReviewRequest request, String actorId) {
        ExamReviewRequest review = reviews.findById(id).orElseThrow(() -> ApiException.notFound("Yêu cầu phúc khảo"));
        if (!"PENDING".equals(review.getStatus())) throw ApiException.conflict("Yêu cầu phúc khảo đã được xử lý");
        if (!canTeacherManageReview(actorId, review)) throw ApiException.forbidden("Giáo viên không phụ trách môn hoặc lớp của yêu cầu này");
        ExamResult result = results.findById(review.getResultId()).orElseThrow(() -> ApiException.notFound("Kết quả thi"));
        Double old = result.getScore(); Double resolved = "APPROVED".equals(request.status()) ? request.resolvedScore() : old;
        if ("APPROVED".equals(request.status()) && resolved == null) throw ApiException.badRequest("Cần nhập điểm sau phúc khảo");
        if (!Objects.equals(old, resolved)) {
            result.setScore(resolved); result.setUpdatedAt(Instant.now()); result.setUpdatedBy(actorId); results.save(result);
            adjustments.save(ExamScoreAdjustment.builder().id(Ids.gen("exa")).examPeriodId(review.getExamPeriodId())
                    .resultId(result.getId()).reviewRequestId(review.getId()).oldScore(old).newScore(resolved)
                    .reason(request.resolution().trim()).adjustedAt(Instant.now()).adjustedBy(actorId).build());
        }
        review.setStatus(request.status()); review.setResolution(request.resolution().trim()); review.setResolvedScore(resolved);
        review.setResolvedAt(Instant.now()); review.setResolvedBy(actorId); ExamReviewRequest saved = reviews.save(review);
        notifications.notifyUser(review.getStudentId(), "EXAM_REVIEW", "IMPORTANT", "Yêu cầu phúc khảo đã được xử lý",
                review.getSubjectName() + ": " + ("APPROVED".equals(review.getStatus()) ? "đã điều chỉnh kết quả" : "giữ nguyên kết quả")
                        + ". Mở Kết quả thi để xem chi tiết.", "EXAM_REVIEW", review.getId());
        notifications.notifyParentsOfStudent(review.getStudentId(), "EXAM_REVIEW", "IMPORTANT",
                "Kết quả phúc khảo đã có", review.getStudentName() + " · " + review.getSubjectName()
                        + ": " + ("APPROVED".equals(review.getStatus()) ? "đã điều chỉnh kết quả" : "giữ nguyên kết quả") + ".",
                "EXAM_REVIEW", review.getId());
        return saved;
    }

    private boolean canTeacherManageReview(String teacherId, ExamReviewRequest review) {
        ExamResult result = results.findById(review.getResultId()).orElse(null);
        if (result == null) return false;
        ExamCandidate candidate = candidates.findByScheduleIdAndStudentId(result.getScheduleId(), result.getStudentId()).orElse(null);
        if (candidate == null) return false;
        return gradingAssignments.findByScheduleIdAndClassId(result.getScheduleId(), candidate.getClassId())
                .filter(assignment -> teacherId.equals(assignment.getTeacherId()))
                .filter(assignment -> result.getSubjectId().equals(assignment.getSubjectId()))
                .isPresent();
    }

    public List<ExamScoreAdjustment> adjustments(String periodId) { requirePeriod(periodId); return adjustments.findByExamPeriodIdOrderByAdjustedAtDesc(periodId); }

    @Transactional
    public synchronized int sendDueDutyNotifications(ZonedDateTime now) {
        ZonedDateTime schoolNow = now.withZoneSameInstant(SCHOOL_ZONE);
        int sent = 0;
        for (ExamPeriod period : periods.findAll()) {
            if (!period.isSchedulePublished()) continue;
            for (ExamSchedule schedule : schedules.findByExamPeriodId(period.getId())) {
                ZonedDateTime examStart = scheduleStartAt(schedule);
                ZonedDateTime examEnd = scheduleEndAt(schedule);
                ZonedDateTime dutyReminderAt = examStart.minusDays(7);
                ZonedDateTime scoreEntryAt = scoreEntryOpensAt(schedule).atZone(SCHOOL_ZONE);

                if (!schoolNow.isBefore(dutyReminderAt) && schoolNow.isBefore(examEnd)) {
                    for (ExamRoom room : rooms.findByScheduleId(schedule.getId())) {
                        sent += notifyProctorDuty(period, schedule, room, room.getProctorOneId(), 1);
                        sent += notifyProctorDuty(period, schedule, room, room.getProctorTwoId(), 2);
                    }
                }

                for (ExamGradingAssignment assignment : gradingAssignments.findByScheduleId(schedule.getId())) {
                    if (!schoolNow.isBefore(dutyReminderAt) && schoolNow.isBefore(scoreEntryAt)) {
                        String refId = gradingDutyRef(assignment);
                        if (!notifications.hasNotification(assignment.getTeacherId(), "EXAM_PERIOD", refId)) {
                            notifications.notifyUser(assignment.getTeacherId(), "EXAM_GRADING_DUTY", "IMPORTANT",
                                    "Nhiệm vụ chấm thi trong 1 tuần tới",
                                    period.getName() + " · " + schedule.getSubjectName() + " · Lớp "
                                            + assignment.getClassCode() + " · " + schedule.getExamDate()
                                            + " lúc " + schedule.getStartTime()
                                            + ". Mở Lịch thi & nhiệm vụ để xem chi tiết.",
                                    "EXAM_PERIOD", refId);
                            sent++;
                        }
                    }
                    if (!schoolNow.isBefore(scoreEntryAt)
                            && !period.isScoreEntryLocked()
                            && !"CONFIRMED".equals(period.getStatus())) {
                        String refId = scoreEntryRef(assignment);
                        if (!notifications.hasNotification(assignment.getTeacherId(), "EXAM_PERIOD", refId)) {
                            notifications.notifyUser(assignment.getTeacherId(), "EXAM_SCORE_ENTRY", "URGENT",
                                    "Đã mở nhập điểm thi",
                                    period.getName() + " · " + schedule.getSubjectName() + " · Lớp "
                                            + assignment.getClassCode()
                                            + ". Thầy/cô vui lòng hoàn thành nhập điểm theo phân công.",
                                    "EXAM_PERIOD", refId);
                            sent++;
                        }
                    }
                }
            }
        }
        return sent;
    }

    private List<UserDto> activeTeachers() {
        return users.list("TEACHER", null, null).stream()
                .filter(teacher -> "ACTIVE".equals(teacher.status())).toList();
    }

    private Map<String, Integer> proctorDutyCounts() {
        Map<String, Integer> counts = new HashMap<>();
        for (ExamRoom room : rooms.findAll()) {
            if (room.getProctorOneId() != null) counts.merge(room.getProctorOneId(), 1, Integer::sum);
            if (room.getProctorTwoId() != null) counts.merge(room.getProctorTwoId(), 1, Integer::sum);
        }
        return counts;
    }

    private Comparator<UserDto> proctorComparator(ExamSchedule schedule, ExamPeriod period,
                                                   Map<String, Integer> dutyCounts) {
        return Comparator.<UserDto>comparingInt(teacher -> isQualifiedForSubject(teacher, schedule, period) ? 1 : 0)
                .thenComparingInt(teacher -> dutyCounts.getOrDefault(teacher.id(), 0))
                .thenComparing(UserDto::fullName);
    }

    private UserDto chooseProctor(List<UserDto> pool, Set<String> usedTeachers, ExamSchedule schedule,
                                  ExamPeriod period, Map<String, Integer> dutyCounts) {
        return pool.stream().filter(teacher -> !usedTeachers.contains(teacher.id()))
                .filter(teacher -> teacherBusyReason(schedule, period, teacher.id(), true) == null)
                .sorted(proctorComparator(schedule, period, dutyCounts)).findFirst().orElse(null);
    }

    private boolean reserveProctor(String teacherId, Set<String> usedTeachers) {
        if (teacherId == null || teacherId.isBlank()) return true;
        return usedTeachers.add(teacherId);
    }

    private String teacherBusyReason(ExamSchedule schedule, ExamPeriod period, String teacherId,
                                     boolean ignoreCurrentSchedule) {
        if (teacherId == null || teacherId.isBlank()) return null;
        for (ExamRoom room : rooms.findAll()) {
            ExamSchedule other = schedules.findById(room.getScheduleId()).orElse(null);
            if (other == null || (ignoreCurrentSchedule && other.getId().equals(schedule.getId())) || !overlaps(schedule, other)) continue;
            if (teacherId.equals(room.getProctorOneId()) || teacherId.equals(room.getProctorTwoId())) {
                return "Đã coi thi môn " + other.getSubjectName() + " cùng thời gian";
            }
        }
        // Ngày thi thay thế lịch học thường: khối không thi nghỉ cả ngày, học sinh dự thi
        // ra về sau ca cuối. Vì vậy chỉ ca coi thi khác thực sự trùng giờ mới làm giáo viên bận.
        return null;
    }

    private String proctorRecommendation(UserDto teacher, ExamSchedule schedule, ExamPeriod period,
                                         Map<String, Integer> dutyCounts) {
        int duties = dutyCounts.getOrDefault(teacher.id(), 0);
        return (isQualifiedForSubject(teacher, schedule, period) ? "Cùng chuyên môn môn thi" : "Khác chuyên môn môn thi")
                + " · đang có " + duties + " ca coi · lịch dạy thường được tạm dừng";
    }

    private ExamProctorPlanItem proctorPlanItem(String planId, ExamRoom room, boolean locked,
                                                String proposedOneId, String proposedOneName,
                                                String proposedTwoId, String proposedTwoName,
                                                String status, String message, Map<String, Integer> dutyCounts) {
        return ExamProctorPlanItem.builder().id(Ids.gen("eppi")).planId(planId)
                .roomId(room.getId()).roomCode(room.getRoomCode()).locked(locked)
                .previousProctorOneId(room.getProctorOneId()).previousProctorOneName(room.getProctorOneName())
                .previousProctorTwoId(room.getProctorTwoId()).previousProctorTwoName(room.getProctorTwoName())
                .proposedProctorOneId(proposedOneId).proposedProctorOneName(proposedOneName)
                .proposedProctorTwoId(proposedTwoId).proposedProctorTwoName(proposedTwoName)
                .status(status).message(message)
                .proctorOneDutyCount(proposedOneId == null ? null : dutyCounts.getOrDefault(proposedOneId, 0))
                .proctorTwoDutyCount(proposedTwoId == null ? null : dutyCounts.getOrDefault(proposedTwoId, 0)).build();
    }

    private ProctorPlanView proctorPlanView(ExamProctorPlan plan) {
        List<ProctorPlanItem> items = proctorPlanItems.findByPlanId(plan.getId()).stream()
                .sorted(Comparator.comparing(ExamProctorPlanItem::getRoomCode))
                .map(item -> new ProctorPlanItem(item.getRoomId(), item.getRoomCode(), item.isLocked(),
                        item.getPreviousProctorOneId(), item.getPreviousProctorOneName(),
                        item.getPreviousProctorTwoId(), item.getPreviousProctorTwoName(),
                        item.getProposedProctorOneId(), item.getProposedProctorOneName(),
                        item.getProposedProctorTwoId(), item.getProposedProctorTwoName(), item.getStatus(),
                        item.getMessage(), item.getProctorOneDutyCount(), item.getProctorTwoDutyCount())).toList();
        return new ProctorPlanView(plan.getId(), plan.getScheduleId(), plan.getStatus(), plan.isIncludeSecondProctor(),
                plan.getRoomCount(), plan.getReadyRoomCount(), plan.getMissingAssignmentCount(), plan.getWarningSummary(),
                plan.getCreatedAt(), plan.getAppliedAt(), plan.getUndoneAt(), items);
    }

    private String proctorSourceFingerprint(ExamSchedule schedule, ExamPeriod period) {
        StringBuilder source = new StringBuilder(schedule.getId()).append('|').append(schedule.getExamDate())
                .append('|').append(schedule.getStartTime()).append('|').append(schedule.getDurationMinutes());
        rooms.findByScheduleId(schedule.getId()).stream().sorted(Comparator.comparing(ExamRoom::getId))
                .forEach(room -> source.append("|CURRENT:").append(room.getId()).append(':')
                        .append(Objects.toString(room.getProctorOneId(), "")).append(':')
                        .append(Objects.toString(room.getProctorTwoId(), "")));
        rooms.findAll().stream().sorted(Comparator.comparing(ExamRoom::getId)).forEach(room -> {
            ExamSchedule other = schedules.findById(room.getScheduleId()).orElse(null);
            if (other != null && !other.getId().equals(schedule.getId()) && overlaps(schedule, other)) {
                source.append("|OTHER:").append(room.getId()).append(':')
                        .append(Objects.toString(room.getProctorOneId(), "")).append(':')
                        .append(Objects.toString(room.getProctorTwoId(), ""));
            }
        });
        return sha256(source.toString());
    }

    private void validateProctorProposal(ExamSchedule schedule, ExamPeriod period,
                                         List<ExamProctorPlanItem> items, boolean previous) {
        Set<String> used = new HashSet<>();
        for (ExamProctorPlanItem item : items) {
            String first = previous ? item.getPreviousProctorOneId() : item.getProposedProctorOneId();
            String second = previous ? item.getPreviousProctorTwoId() : item.getProposedProctorTwoId();
            if (!previous && (first == null || first.isBlank())) throw ApiException.conflict("Phòng " + item.getRoomCode() + " chưa có giám thị chính");
            for (String teacherId : List.of(first == null ? "" : first, second == null ? "" : second)) {
                if (teacherId.isBlank()) continue;
                teacher(teacherId);
                if (!used.add(teacherId)) throw ApiException.conflict("Một giáo viên đang được xếp cho nhiều phòng trong cùng ca thi");
                String busy = teacherBusyReason(schedule, period, teacherId, true);
                if (busy != null) throw ApiException.conflict(busy);
            }
        }
    }

    private String sha256(String source) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record OrganizationRoomDraft(String roomId, String roomCode, int physicalCapacity,
                                         int effectiveCapacity, String proctorOneId, String proctorOneName,
                                         String proctorTwoId, String proctorTwoName, int candidateCount) {
        OrganizationRoomDraft withProctors(UserDto first, UserDto second) {
            return new OrganizationRoomDraft(roomId, roomCode, physicalCapacity, effectiveCapacity,
                    first == null ? null : first.id(), first == null ? null : first.fullName(),
                    second == null ? null : second.id(), second == null ? null : second.fullName(), candidateCount);
        }
        OrganizationRoomDraft withCandidateCount(int count) {
            return new OrganizationRoomDraft(roomId, roomCode, physicalCapacity, effectiveCapacity,
                    proctorOneId, proctorOneName, proctorTwoId, proctorTwoName, count);
        }
    }
    private record OrganizationCandidateDraft(String candidateId, CandidateSeed seed, String candidateNo,
                                              String roomId, String roomCode, int seatNo,
                                              int deskNo, int seatPosition) {}

    private OrganizationPlanView lockedOrganizationPlan(String planId) {
        List<String> ids = jdbc.query("select id from exam_organization_plans where id=? for update",
                (rs, row) -> rs.getString(1), planId);
        if (ids.isEmpty()) throw ApiException.notFound("Phương án tổ chức ca thi");
        return organizationPlan(planId);
    }

    private OrganizationPlanView organizationPlan(String planId) {
        List<OrganizationPlanView> values = jdbc.query("select * from exam_organization_plans where id=?", (rs, row) -> {
            int studentsPerDesk = rs.getInt("students_per_desk");
            return new OrganizationPlanView(rs.getString("id"), rs.getString("schedule_id"), rs.getString("status"),
                    rs.getInt("max_candidates_per_room"), studentsPerDesk, rs.getBoolean("include_second_proctor"),
                    rs.getInt("candidate_count"), rs.getInt("room_count"), rs.getInt("effective_capacity"),
                    rs.getInt("assigned_count"), rs.getInt("missing_assignment_count"), rs.getString("warning_summary"),
                    instant(rs, "created_at"), instant(rs, "applied_at"), instant(rs, "undone_at"),
                    organizationRoomRows(planId, "PROPOSED", studentsPerDesk), organizationCandidateRows(planId, "PROPOSED"));
        }, planId);
        if (values.isEmpty()) throw ApiException.notFound("Phương án tổ chức ca thi");
        return values.get(0);
    }

    private List<OrganizationPlanRoom> organizationRoomRows(String planId, String rowType, int studentsPerDesk) {
        return jdbc.query("""
                select * from exam_organization_plan_rooms
                where plan_id=? and row_type=? order by room_code
                """, (rs, row) -> {
            int effective = rs.getInt("effective_capacity");
            int candidateCount = rs.getInt("candidate_count");
            String first = rs.getString("proctor_one_id");
            String second = rs.getString("proctor_two_id");
            return new OrganizationPlanRoom(rs.getString("room_id"), rs.getString("room_code"),
                    rs.getInt("physical_capacity"), effective,
                    (candidateCount + studentsPerDesk - 1) / studentsPerDesk,
                    first, rs.getString("proctor_one_name"), second, rs.getString("proctor_two_name"),
                    candidateCount, first != null && !first.isBlank());
        }, planId, rowType);
    }

    private List<OrganizationPlanCandidate> organizationCandidateRows(String planId, String rowType) {
        return jdbc.query("""
                select * from exam_organization_plan_candidates
                where plan_id=? and row_type=? order by candidate_no
                """, (rs, row) -> new OrganizationPlanCandidate(rs.getString("student_id"),
                rs.getString("student_name"), rs.getString("student_code"), rs.getString("class_id"),
                rs.getString("class_code"), rs.getString("candidate_no"), rs.getString("room_id"),
                rs.getString("room_code"), rs.getInt("seat_no"), rs.getInt("desk_no"),
                rs.getInt("seat_position")), planId, rowType);
    }

    private void snapshotCurrentOrganization(String planId, String scheduleId,
                                             Map<String, com.sse.app.academic.structure.Room> physicalByCode) {
        Map<String, Integer> counts = candidates.findByScheduleId(scheduleId).stream().collect(
                java.util.stream.Collectors.groupingBy(ExamCandidate::getExamRoomId,
                        java.util.stream.Collectors.collectingAndThen(java.util.stream.Collectors.counting(), Long::intValue)));
        for (ExamRoom room : rooms.findByScheduleId(scheduleId)) {
            com.sse.app.academic.structure.Room physical = physicalByCode.get(room.getRoomCode().toUpperCase());
            insertOrganizationRoom(planId, "PREVIOUS", new OrganizationRoomDraft(room.getId(), room.getRoomCode(),
                    physical == null || physical.getCapacity() == null ? room.getCapacity() : physical.getCapacity(),
                    room.getCapacity(), room.getProctorOneId(), room.getProctorOneName(), room.getProctorTwoId(),
                    room.getProctorTwoName(), counts.getOrDefault(room.getId(), 0)));
        }
        for (ExamCandidate candidate : candidates.findByScheduleId(scheduleId)) {
            int deskNo = candidate.getDeskNo() == null ? Math.max(1, candidate.getSeatNo()) : candidate.getDeskNo();
            int position = candidate.getSeatPosition() == null ? 1 : candidate.getSeatPosition();
            jdbc.update("""
                    insert into exam_organization_plan_candidates
                    (id,plan_id,row_type,candidate_id,student_id,student_name,student_code,class_id,class_code,
                     candidate_no,room_id,room_code,seat_no,desk_no,seat_position)
                    values (?,?, 'PREVIOUS',?,?,?,?,?,?,?,?,?,?,?,?)
                    """, Ids.gen("eopc"), planId, candidate.getId(), candidate.getStudentId(), candidate.getStudentName(),
                    candidate.getStudentCode(), candidate.getClassId(), candidate.getClassCode(), candidate.getCandidateNo(),
                    candidate.getExamRoomId(), rooms.findById(candidate.getExamRoomId()).map(ExamRoom::getRoomCode).orElse(""),
                    candidate.getSeatNo(), deskNo, position);
        }
    }

    private void insertOrganizationRoom(String planId, String rowType, OrganizationRoomDraft room) {
        jdbc.update("""
                insert into exam_organization_plan_rooms
                (id,plan_id,row_type,room_id,room_code,physical_capacity,effective_capacity,proctor_one_id,
                 proctor_one_name,proctor_two_id,proctor_two_name,candidate_count)
                values (?,?,?,?,?,?,?,?,?,?,?,?)
                """, Ids.gen("eopr"), planId, rowType, room.roomId(), room.roomCode(), room.physicalCapacity(),
                room.effectiveCapacity(), room.proctorOneId(), room.proctorOneName(), room.proctorTwoId(),
                room.proctorTwoName(), room.candidateCount());
    }

    private void insertOrganizationCandidate(String planId, String rowType, OrganizationCandidateDraft candidate) {
        jdbc.update("""
                insert into exam_organization_plan_candidates
                (id,plan_id,row_type,candidate_id,student_id,student_name,student_code,class_id,class_code,
                 candidate_no,room_id,room_code,seat_no,desk_no,seat_position)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, Ids.gen("eopc"), planId, rowType, candidate.candidateId(), candidate.seed().studentId(),
                candidate.seed().studentName(), candidate.seed().studentCode(), candidate.seed().classId(),
                candidate.seed().classCode(), candidate.candidateNo(), candidate.roomId(), candidate.roomCode(),
                candidate.seatNo(), candidate.deskNo(), candidate.seatPosition());
    }

    private void validateOrganizationProposal(ExamSchedule schedule, ExamPeriod period,
                                              List<OrganizationPlanRoom> plannedRooms,
                                              List<OrganizationPlanCandidate> plannedCandidates,
                                              boolean includeSecond, int studentsPerDesk) {
        if (plannedRooms.isEmpty()) throw ApiException.conflict("Phương án chưa có phòng thi");
        Map<String, ExamRoomAvailability> availability = roomAvailability(schedule.getId()).stream()
                .collect(java.util.stream.Collectors.toMap(ExamRoomAvailability::roomCode, item -> item));
        Set<String> teachers = new HashSet<>();
        Set<String> roomIds = new HashSet<>();
        for (OrganizationPlanRoom room : plannedRooms) {
            ExamRoomAvailability physical = availability.get(room.roomCode());
            if (physical == null || !physical.available()) throw ApiException.conflict("Phòng " + room.roomCode() + " không còn khả dụng");
            if (room.effectiveCapacity() < 1 || room.effectiveCapacity() > room.physicalCapacity())
                throw ApiException.conflict("Sức chứa phòng " + room.roomCode() + " không hợp lệ");
            if (!roomIds.add(room.roomId())) throw ApiException.conflict("Phòng thi bị trùng trong phương án");
            if (room.proctorOneId() == null || (includeSecond && room.proctorTwoId() == null))
                throw ApiException.conflict("Phòng " + room.roomCode() + " chưa đủ giám thị");
            for (String teacherId : Arrays.asList(room.proctorOneId(), room.proctorTwoId())) {
                if (teacherId == null || teacherId.isBlank()) continue;
                teacher(teacherId);
                if (!teachers.add(teacherId)) throw ApiException.conflict("Một giáo viên đang được xếp cho nhiều phòng");
                String busy = teacherBusyReason(schedule, period, teacherId, true);
                if (busy != null) throw ApiException.conflict(busy);
            }
        }
        List<CandidateSeed> expected = expectedCandidates(schedule);
        Set<String> expectedStudents = expected.stream().map(CandidateSeed::studentId).collect(java.util.stream.Collectors.toSet());
        Set<String> students = new HashSet<>(); Set<String> numbers = new HashSet<>(); Set<String> seats = new HashSet<>();
        Map<String, Integer> counts = new HashMap<>();
        for (OrganizationPlanCandidate candidate : plannedCandidates) {
            if (!expectedStudents.contains(candidate.studentId()) || !students.add(candidate.studentId()))
                throw ApiException.conflict("Danh sách thí sinh không khớp lớp dự thi");
            if (!candidate.candidateNo().matches("\\d{6}") || !numbers.add(candidate.candidateNo()))
                throw ApiException.conflict("Số báo danh phải gồm 6 chữ số và không được trùng");
            if (!roomIds.contains(candidate.roomId()) || !seats.add(candidate.roomId() + "|" + candidate.seatNo()))
                throw ApiException.conflict("Số ghế hoặc phòng thi của thí sinh không hợp lệ");
            if (candidate.deskNo() != ((candidate.seatNo() - 1) / studentsPerDesk) + 1
                    || candidate.seatPosition() != ((candidate.seatNo() - 1) % studentsPerDesk) + 1)
                throw ApiException.conflict("Số bàn hoặc vị trí ngồi không đúng cấu hình");
            counts.merge(candidate.roomId(), 1, Integer::sum);
        }
        if (students.size() != expectedStudents.size()) throw ApiException.conflict("Phương án chưa xếp đủ thí sinh");
        plannedRooms.forEach(room -> {
            if (counts.getOrDefault(room.roomId(), 0) > room.effectiveCapacity())
                throw ApiException.conflict("Phòng " + room.roomCode() + " vượt sức chứa cấu hình");
        });
    }

    private void replaceOrganizationState(ExamSchedule schedule, List<OrganizationPlanRoom> plannedRooms,
                                          List<OrganizationPlanCandidate> plannedCandidates) {
        candidates.deleteByScheduleId(schedule.getId()); candidates.flush();
        List<ExamRoom> currentRooms = rooms.findByScheduleId(schedule.getId());
        currentRooms.forEach(this::removeProctorNotifications);
        rooms.deleteAll(currentRooms); rooms.flush();
        List<ExamRoom> replacements = plannedRooms.stream().map(room -> ExamRoom.builder().id(room.roomId())
                .scheduleId(schedule.getId()).roomCode(room.roomCode()).capacity(room.effectiveCapacity())
                .proctorOneId(room.proctorOneId()).proctorOneName(room.proctorOneName())
                .proctorTwoId(room.proctorTwoId()).proctorTwoName(room.proctorTwoName()).build()).toList();
        rooms.saveAll(replacements); rooms.flush();
        String periodId = schedule.getExamPeriodId();
        candidates.saveAll(plannedCandidates.stream().map(item -> ExamCandidate.builder().id(Ids.gen("ec"))
                .examPeriodId(periodId).scheduleId(schedule.getId()).examRoomId(item.roomId())
                .studentId(item.studentId()).studentName(item.studentName()).studentCode(item.studentCode())
                .classId(item.classId()).classCode(item.classCode()).candidateNo(item.candidateNo())
                .seatNo(item.seatNo()).deskNo(item.deskNo()).seatPosition(item.seatPosition()).build()).toList());
    }

    private boolean matchesOrganizationState(String scheduleId, List<OrganizationPlanRoom> plannedRooms,
                                             List<OrganizationPlanCandidate> plannedCandidates) {
        Set<String> expectedRooms = plannedRooms.stream().map(room -> room.roomId() + "|" + room.roomCode() + "|"
                + room.effectiveCapacity() + "|" + Objects.toString(room.proctorOneId(), "") + "|"
                + Objects.toString(room.proctorTwoId(), "")).collect(java.util.stream.Collectors.toSet());
        Set<String> currentRooms = rooms.findByScheduleId(scheduleId).stream().map(room -> room.getId() + "|"
                + room.getRoomCode() + "|" + room.getCapacity() + "|" + Objects.toString(room.getProctorOneId(), "")
                + "|" + Objects.toString(room.getProctorTwoId(), "")).collect(java.util.stream.Collectors.toSet());
        Set<String> expectedCandidates = plannedCandidates.stream().map(candidate -> candidate.studentId() + "|"
                + candidate.candidateNo() + "|" + candidate.roomId() + "|" + candidate.seatNo() + "|"
                + candidate.deskNo() + "|" + candidate.seatPosition()).collect(java.util.stream.Collectors.toSet());
        Set<String> currentCandidates = candidates.findByScheduleId(scheduleId).stream().map(candidate -> candidate.getStudentId()
                + "|" + candidate.getCandidateNo() + "|" + candidate.getExamRoomId() + "|" + candidate.getSeatNo()
                + "|" + candidate.getDeskNo() + "|" + candidate.getSeatPosition()).collect(java.util.stream.Collectors.toSet());
        return expectedRooms.equals(currentRooms) && expectedCandidates.equals(currentCandidates);
    }

    private String organizationSourceFingerprint(ExamSchedule schedule) {
        StringBuilder source = new StringBuilder(schedule.getId()).append('|').append(schedule.getExamDate())
                .append('|').append(schedule.getStartTime()).append('|').append(schedule.getDurationMinutes());
        Optional.ofNullable(schedule.getClassIds()).orElse(Set.of()).stream().sorted().forEach(id -> source.append("|CLASS:").append(id));
        expectedCandidates(schedule).stream().sorted(Comparator.comparing(CandidateSeed::studentId))
                .forEach(seed -> source.append("|STUDENT:").append(seed.studentId()).append(':').append(seed.classId()));
        structure.listRooms().stream().sorted(Comparator.comparing(com.sse.app.academic.structure.Room::getCode))
                .forEach(room -> source.append("|PHYSICAL:").append(room.getCode()).append(':')
                        .append(room.getCapacity()).append(':').append(room.getStatus()));
        rooms.findByScheduleId(schedule.getId()).stream().sorted(Comparator.comparing(ExamRoom::getId))
                .forEach(room -> source.append("|ROOM:").append(room.getId()).append(':').append(room.getRoomCode())
                        .append(':').append(room.getCapacity()).append(':').append(room.getProctorOneId())
                        .append(':').append(room.getProctorTwoId()));
        candidates.findByScheduleId(schedule.getId()).stream().sorted(Comparator.comparing(ExamCandidate::getStudentId))
                .forEach(candidate -> source.append("|CANDIDATE:").append(candidate.getStudentId()).append(':')
                        .append(candidate.getCandidateNo()).append(':').append(candidate.getExamRoomId()).append(':')
                        .append(candidate.getSeatNo()));
        rooms.findAll().stream().sorted(Comparator.comparing(ExamRoom::getId)).forEach(room -> {
            ExamSchedule other = schedules.findById(room.getScheduleId()).orElse(null);
            if (other != null && !other.getId().equals(schedule.getId()) && overlaps(schedule, other))
                source.append("|CONFLICT:").append(room.getRoomCode()).append(':').append(room.getProctorOneId())
                        .append(':').append(room.getProctorTwoId());
        });
        return sha256(source.toString());
    }

    private String organizationPlanFingerprint(String planId) {
        List<String> values = jdbc.query("select source_fingerprint from exam_organization_plans where id=?",
                (rs, row) -> rs.getString(1), planId);
        if (values.isEmpty()) throw ApiException.notFound("Phương án tổ chức ca thi");
        return values.get(0);
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private record CandidateSeed(String studentId, String studentName, String studentCode,
                                 String classId, String classCode) {}
    private record PlannedCandidate(CandidateSeed seed, String candidateNo, ExamRoom room, Integer seatNo) {}

    private List<CandidateSeed> expectedCandidates(ExamSchedule schedule) {
        if (schedule.getClassIds() == null) return List.of();
        List<CandidateSeed> expected = new ArrayList<>();
        for (String classId : schedule.getClassIds().stream()
                .sorted(Comparator.comparing(classId -> structure.getClass(classId).getCode())).toList()) {
            SchoolClass schoolClass = structure.getClass(classId);
            users.list("STUDENT", null, classId).stream()
                    .sorted(Comparator.comparing(UserDto::studentCode, Comparator.nullsLast(String::compareTo))
                            .thenComparing(UserDto::fullName))
                    .forEach(student -> expected.add(new CandidateSeed(student.id(), student.fullName(),
                            student.studentCode(), classId, schoolClass.getCode())));
        }
        return expected.stream().collect(java.util.stream.Collectors.toMap(CandidateSeed::studentId,
                seed -> seed, (first, ignored) -> first, LinkedHashMap::new)).values().stream().toList();
    }

    private ExamRoom chooseRoomForClass(String classId, List<PlannedCandidate> proposed,
                                        List<ExamRoom> selectedRooms, Map<String, Integer> remaining) {
        Optional<ExamRoom> current = proposed.stream()
                .filter(item -> item.seed().classId().equals(classId) && item.room() != null
                        && remaining.getOrDefault(item.room().getId(), 0) > 0)
                .map(PlannedCandidate::room).findFirst();
        if (current.isPresent()) return current.get();
        return selectedRooms.stream().filter(room -> remaining.getOrDefault(room.getId(), 0) > 0)
                .max(Comparator.<ExamRoom>comparingInt(room -> remaining.get(room.getId()))
                        .thenComparing(ExamRoom::getRoomCode, Comparator.reverseOrder()))
                .orElse(null);
    }

    private ExamSeatingPlanItem planItem(String planId, String rowType, ExamCandidate candidate) {
        return ExamSeatingPlanItem.builder().id(Ids.gen("espi")).planId(planId).rowType(rowType)
                .studentId(candidate.getStudentId()).studentName(candidate.getStudentName())
                .studentCode(candidate.getStudentCode()).classId(candidate.getClassId())
                .classCode(candidate.getClassCode()).candidateNo(candidate.getCandidateNo())
                .examRoomId(candidate.getExamRoomId())
                .roomCode(rooms.findById(candidate.getExamRoomId()).map(ExamRoom::getRoomCode).orElse(null))
                .seatNo(candidate.getSeatNo()).build();
    }

    private ExamSeatingPlanItem planItem(String planId, PlannedCandidate candidate) {
        return ExamSeatingPlanItem.builder().id(Ids.gen("espi")).planId(planId).rowType("PROPOSED")
                .studentId(candidate.seed().studentId()).studentName(candidate.seed().studentName())
                .studentCode(candidate.seed().studentCode()).classId(candidate.seed().classId())
                .classCode(candidate.seed().classCode()).candidateNo(candidate.candidateNo())
                .examRoomId(candidate.room() == null ? null : candidate.room().getId())
                .roomCode(candidate.room() == null ? null : candidate.room().getRoomCode())
                .seatNo(candidate.seatNo()).build();
    }

    private ExamCandidate candidateFromPlan(ExamSeatingPlan plan, ExamSeatingPlanItem item) {
        return ExamCandidate.builder().id(Ids.gen("ec")).examPeriodId(plan.getExamPeriodId())
                .scheduleId(plan.getScheduleId()).examRoomId(item.getExamRoomId())
                .studentId(item.getStudentId()).studentName(item.getStudentName()).studentCode(item.getStudentCode())
                .classId(item.getClassId()).classCode(item.getClassCode()).candidateNo(item.getCandidateNo())
                .seatNo(item.getSeatNo() == null ? 0 : item.getSeatNo()).build();
    }

    private List<ExamRoom> selectedRooms(ExamSeatingPlan plan, ExamSchedule schedule) {
        Set<String> ids = Arrays.stream(plan.getSelectedRoomIds().split(","))
                .filter(value -> !value.isBlank()).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<ExamRoom> selected = rooms.findByScheduleId(schedule.getId()).stream()
                .filter(room -> ids.contains(room.getId())).sorted(Comparator.comparing(ExamRoom::getRoomCode)).toList();
        if (selected.size() != ids.size()) throw ApiException.conflict("Danh sách phòng đã thay đổi; hãy tạo lại bản xem trước");
        return selected;
    }

    private SeatingPlanView seatingPlanView(ExamSeatingPlan plan) {
        List<ExamSeatingPlanItem> proposed = seatingPlanItems.findByPlanIdAndRowType(plan.getId(), "PROPOSED").stream()
                .sorted(Comparator.comparing(ExamSeatingPlanItem::getClassCode)
                        .thenComparing(ExamSeatingPlanItem::getCandidateNo)).toList();
        Map<String, ExamRoom> existingRooms = rooms.findByScheduleId(plan.getScheduleId()).stream()
                .collect(java.util.stream.Collectors.toMap(ExamRoom::getId, room -> room));
        List<SeatingPlanRoom> roomViews = Arrays.stream(plan.getSelectedRoomIds().split(","))
                .map(existingRooms::get).filter(Objects::nonNull).sorted(Comparator.comparing(ExamRoom::getRoomCode))
                .map(room -> {
                    List<ExamSeatingPlanItem> assigned = proposed.stream()
                            .filter(item -> room.getId().equals(item.getExamRoomId())).toList();
                    List<String> classCodes = assigned.stream().map(ExamSeatingPlanItem::getClassCode).distinct().sorted().toList();
                    return new SeatingPlanRoom(room.getId(), room.getRoomCode(), room.getCapacity(), assigned.size(),
                            Math.max(0, room.getCapacity() - assigned.size()), hasMainProctor(room), classCodes);
                }).toList();
        List<SeatingPlanClass> classViews = proposed.stream().collect(java.util.stream.Collectors.groupingBy(
                        ExamSeatingPlanItem::getClassId, LinkedHashMap::new, java.util.stream.Collectors.toList()))
                .values().stream().map(items -> {
                    List<String> roomCodes = items.stream().map(ExamSeatingPlanItem::getRoomCode)
                            .filter(Objects::nonNull).distinct().sorted().toList();
                    int assigned = (int) items.stream().filter(item -> item.getExamRoomId() != null).count();
                    return new SeatingPlanClass(items.get(0).getClassId(), items.get(0).getClassCode(), items.size(),
                            assigned, roomCodes.size(), roomCodes);
                }).toList();
        List<SeatingPlanCandidate> candidateViews = proposed.stream().map(item -> new SeatingPlanCandidate(
                item.getStudentId(), item.getStudentName(), item.getStudentCode(), item.getClassId(), item.getClassCode(),
                item.getCandidateNo(), item.getExamRoomId(), item.getRoomCode(), item.getSeatNo(), item.getExamRoomId() != null)).toList();
        return new SeatingPlanView(plan.getId(), plan.getScheduleId(), plan.getStatus(), plan.getCandidateCount(),
                plan.getTotalCapacity(), plan.getAssignedCount(), plan.getUnassignedCount(), plan.getWarningSummary(),
                plan.getCreatedAt(), plan.getAppliedAt(), plan.getUndoneAt(), roomViews, classViews, candidateViews);
    }

    private String scheduleSourceFingerprint(ExamSchedule schedule, List<ExamRoom> selectedRooms,
                                             List<CandidateSeed> expected, List<ExamCandidate> current) {
        StringBuilder source = new StringBuilder(schedule.getId()).append('|').append(schedule.getExamDate())
                .append('|').append(schedule.getStartTime()).append('|').append(schedule.getDurationMinutes());
        selectedRooms.stream().sorted(Comparator.comparing(ExamRoom::getId)).forEach(room -> source.append("|R:")
                .append(room.getId()).append(':').append(room.getCapacity()).append(':')
                .append(Objects.toString(room.getProctorOneId(), "")).append(':')
                .append(Objects.toString(room.getProctorTwoId(), "")));
        expected.stream().sorted(Comparator.comparing(CandidateSeed::studentId)).forEach(seed -> source.append("|S:")
                .append(seed.studentId()).append(':').append(seed.classId()));
        current.stream().sorted(Comparator.comparing(ExamCandidate::getStudentId)).forEach(candidate -> source.append("|C:")
                .append(candidate.getStudentId()).append(':').append(candidate.getExamRoomId()).append(':')
                .append(candidate.getCandidateNo()).append(':').append(candidate.getSeatNo()));
        return sha256(source.toString());
    }

    private boolean matchesCurrentCandidates(List<ExamSeatingPlanItem> planned, List<ExamCandidate> current) {
        Set<String> plannedRows = planned.stream().map(item -> item.getStudentId() + "|" + item.getExamRoomId()
                + "|" + item.getCandidateNo() + "|" + item.getSeatNo()).collect(java.util.stream.Collectors.toSet());
        Set<String> currentRows = current.stream().map(item -> item.getStudentId() + "|" + item.getExamRoomId()
                + "|" + item.getCandidateNo() + "|" + item.getSeatNo()).collect(java.util.stream.Collectors.toSet());
        return planned.size() == current.size() && plannedRows.equals(currentRows);
    }

    private boolean hasMainProctor(ExamRoom room) {
        return room.getProctorOneId() != null && !room.getProctorOneId().isBlank();
    }

    private void invalidatePublishedSchedule(ExamPeriod period) {
        if (!period.isSchedulePublished()) return;
        period.setSchedulePublished(false);
        period.setSchedulePublishedAt(null);
        period.setSchedulePublishedBy(null);
        period.setUpdatedAt(Instant.now());
        periods.save(period);
    }

    private void notifySchedulePublication(ExamPeriod period, List<ExamSchedule> periodSchedules, boolean updated) {
        String title = updated ? "Lịch thi đã được cập nhật" : "Lịch thi đã được công bố";
        String dateRange = period.getStartDate().equals(period.getEndDate())
                ? period.getStartDate().toString() : period.getStartDate() + " – " + period.getEndDate();
        Set<String> studentIds = candidates.findByExamPeriodId(period.getId()).stream()
                .map(ExamCandidate::getStudentId).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for (String studentId : studentIds) {
            String body = period.getName() + " gồm " + periodSchedules.size() + " môn, diễn ra " + dateRange
                    + ". Mở Lịch thi để xem phòng, số báo danh và chỗ ngồi.";
            notifications.notifyUser(studentId, "EXAM_SCHEDULE", "IMPORTANT", title, body, "EXAM_PERIOD", period.getId());
            notifications.notifyParentsOfStudent(studentId, "EXAM_SCHEDULE", "IMPORTANT", title,
                    body, "EXAM_PERIOD", period.getId());
        }

        // Nhiệm vụ của giáo viên được gửi đúng mốc thời gian bởi ExamDutyReminderScheduler:
        // coi thi/chấm thi trước 7 ngày và nhập điểm sau khi ca thi kết thúc 7 ngày.
    }

    private ExamAgendaItem agendaItem(String id, String taskType, String taskLabel, ExamPeriod period,
                                      ExamSchedule schedule, ExamRoom room, ExamCandidate candidate, String status) {
        String proctors = room == null ? null : java.util.stream.Stream.of(room.getProctorOneName(), room.getProctorTwoName())
                .filter(Objects::nonNull).filter(name -> !name.isBlank()).collect(java.util.stream.Collectors.joining(" · "));
        return new ExamAgendaItem(id, taskType, taskLabel, period.getId(), period.getName(), period.getScheduleRevision(),
                schedule.getId(), schedule.getSubjectId(), schedule.getSubjectName(), schedule.getExamDate(),
                schedule.getStartTime(), schedule.getDurationMinutes(), schedule.getNotes(), room == null ? null : room.getRoomCode(),
                candidate == null ? null : candidate.getStudentId(), candidate == null ? null : candidate.getStudentName(),
                candidate == null ? null : candidate.getClassCode(), candidate == null ? null : candidate.getCandidateNo(),
                candidate == null ? null : candidate.getSeatNo(), proctors, status);
    }

    private String examStatus(ExamSchedule schedule) {
        LocalDate today = LocalDate.now();
        if (schedule.getExamDate().isBefore(today)) return "COMPLETED";
        if (schedule.getExamDate().equals(today)) return "TODAY";
        return "UPCOMING";
    }

    private String gradingStatus(ExamPeriod period, ExamSchedule schedule, String classId) {
        if (period.isScoreEntryLocked()) return "LOCKED";
        List<ExamCandidate> classCandidates = candidates.findByScheduleIdAndClassId(schedule.getId(), classId);
        long entered = classCandidates.stream().filter(candidate -> results
                .findByExamPeriodIdAndStudentIdAndSubjectId(period.getId(), candidate.getStudentId(), schedule.getSubjectId())
                .map(result -> result.getScore() != null).orElse(false)).count();
        if (!classCandidates.isEmpty() && entered == classCandidates.size()) return "COMPLETED";
        if (Instant.now().isBefore(scoreEntryOpensAt(schedule))) return "NOT_STARTED";
        return entered > 0 ? "IN_PROGRESS" : "PENDING";
    }

    private PeriodSummary summary(ExamPeriod p) {
        List<ExamSchedule> ss = schedules.findByExamPeriodId(p.getId());
        int roomCount = ss.stream().mapToInt(s -> rooms.findByScheduleId(s.getId()).size()).sum();
        int uniqueCandidateCount = (int) candidates.findByExamPeriodId(p.getId()).stream()
                .map(ExamCandidate::getStudentId).distinct().count();
        return new PeriodSummary(p, ss.size(), roomCount, uniqueCandidateCount,
                results.findByExamPeriodId(p.getId()).size(), (int) reviews.findByExamPeriodId(p.getId()).stream().filter(r -> "PENDING".equals(r.getStatus())).count());
    }

    private void validatePeriod(SavePeriodRequest r, String ignoredId) {
        AcademicYear year = structure.getYear(r.academicYearId());
        Semester sem = structure.getSemester(r.semesterId());
        if ("CLOSED".equals(year.getStatus()) || "CLOSED".equals(sem.getStatus())) {
            throw ApiException.conflict("Năm học hoặc học kỳ đã đóng chỉ được phép tra cứu lịch sử, không thể tạo hay chỉnh sửa kỳ thi");
        }
        if (!r.academicYearId().equals(sem.getAcademicYearId())) throw ApiException.badRequest("Học kỳ không thuộc năm học");
        if (r.endDate().isBefore(r.startDate())) throw ApiException.badRequest("Ngày kết thúc phải sau ngày bắt đầu");
        periods.findByCode(r.code().trim().toUpperCase()).filter(p -> !p.getId().equals(ignoredId))
                .ifPresent(p -> { throw ApiException.conflict("Mã kỳ thi đã tồn tại"); });
    }
    private void validateSchedule(ExamPeriod period, SaveScheduleRequest r, String ignoredId) {
        structure.requireSubjectName(r.subjectId());
        if (r.examDate().isBefore(period.getStartDate()) || r.examDate().isAfter(period.getEndDate()))
            throw ApiException.badRequest("Ngày thi phải nằm trong thời gian kỳ thi");
        Set<String> requestedClasses = new LinkedHashSet<>(r.classIds());
        if (requestedClasses.size() != r.classIds().size()) throw ApiException.badRequest("Danh sách lớp bị trùng");
        for (String classId : requestedClasses) {
            SchoolClass schoolClass = structure.getClass(classId);
            if (!period.getAcademicYearId().equals(schoolClass.getAcademicYearId())) {
                throw ApiException.badRequest("Lớp " + schoolClass.getCode()
                        + " không thuộc năm học của kỳ thi");
            }
            if (period.getGradeLevel() != null && !period.getGradeLevel().equals(schoolClass.getGradeLevel()))
                throw ApiException.badRequest("Lớp " + schoolClass.getCode() + " không thuộc khối áp dụng của kỳ thi");
        }
        ExamSchedule candidate = ExamSchedule.builder().id(ignoredId).examPeriodId(period.getId())
                .subjectId(r.subjectId()).subjectName(structure.requireSubjectName(r.subjectId()))
                .classIds(requestedClasses).examDate(r.examDate()).startTime(r.startTime())
                .durationMinutes(r.durationMinutes()).build();
        for (ExamSchedule existing : schedules.findByExamPeriodId(period.getId())) {
            if (Objects.equals(existing.getId(), ignoredId)) continue;
            Set<String> shared = new LinkedHashSet<>(existing.getClassIds() == null ? Set.of() : existing.getClassIds());
            shared.retainAll(requestedClasses);
            if (shared.isEmpty()) continue;
            String classNames = shared.stream().map(id -> structure.getClass(id).getCode()).sorted()
                    .collect(java.util.stream.Collectors.joining(", "));
            if (existing.getSubjectId().equals(r.subjectId()))
                throw ApiException.conflict("Môn " + existing.getSubjectName() + " đã có lịch thi cho lớp " + classNames);
            if (overlaps(existing, candidate))
                throw ApiException.conflict("Trùng giờ với môn " + existing.getSubjectName() + " của lớp " + classNames
                        + " (" + existing.getExamDate() + " " + existing.getStartTime() + ")");
        }
    }

    private void validateNoPeriodConflicts(List<ExamSchedule> periodSchedules) {
        for (int i = 0; i < periodSchedules.size(); i++) {
            for (int j = i + 1; j < periodSchedules.size(); j++) {
                ExamSchedule first = periodSchedules.get(i), second = periodSchedules.get(j);
                Set<String> shared = new LinkedHashSet<>(first.getClassIds() == null ? Set.of() : first.getClassIds());
                shared.retainAll(second.getClassIds() == null ? Set.of() : second.getClassIds());
                if (shared.isEmpty() || !overlaps(first, second)) continue;
                String classes = shared.stream().map(id -> structure.getClass(id).getCode()).sorted()
                        .collect(java.util.stream.Collectors.joining(", "));
                throw ApiException.conflict("Lịch thi bị trùng: " + first.getSubjectName() + " và "
                        + second.getSubjectName() + " cùng giờ đối với lớp " + classes);
            }
        }
    }

    private void assertClassScheduleAvailable(ExamSchedule schedule, String classId) {
        for (ExamSchedule other : schedules.findByExamPeriodId(schedule.getExamPeriodId())) {
            if (other.getId().equals(schedule.getId()) || other.getClassIds() == null
                    || !other.getClassIds().contains(classId) || !overlaps(schedule, other)) continue;
            throw ApiException.conflict("Lớp đã có lịch thi môn " + other.getSubjectName()
                    + " trong khoảng thời gian này");
        }
    }

    private boolean overlaps(ExamSchedule first, ExamSchedule second) {
        if (!first.getExamDate().equals(second.getExamDate())) return false;
        LocalTime firstStart = LocalTime.parse(first.getStartTime());
        LocalTime secondStart = LocalTime.parse(second.getStartTime());
        return firstStart.isBefore(secondStart.plusMinutes(second.getDurationMinutes()))
                && secondStart.isBefore(firstStart.plusMinutes(first.getDurationMinutes()));
    }
    private void assertRoomAndProctorsAvailable(ExamSchedule schedule, SaveRoomRequest r, User p1, User p2) {
        Set<String> requested = new HashSet<>(); if (p1 != null) requested.add(p1.getId()); if (p2 != null) requested.add(p2.getId());
        for (ExamRoom existing : rooms.findAll()) {
            if (r.id() != null && r.id().equals(existing.getId())) continue;
            ExamSchedule other = schedules.findById(existing.getScheduleId()).orElse(null);
            if (other == null || !overlaps(other, schedule)) continue;
            if (existing.getRoomCode().equalsIgnoreCase(r.roomCode())) throw ApiException.conflict("Phòng thi đã được sử dụng trong khoảng thời gian này");
            if (requested.contains(existing.getProctorOneId()) || requested.contains(existing.getProctorTwoId()))
                throw ApiException.conflict("Giám thị đã được phân công trong khoảng thời gian này");
        }
    }
    private User teacher(String id) {
        if (id == null || id.isBlank()) return null; User u = users.getById(id);
        if (!"TEACHER".equals(u.getRole()) || !"ACTIVE".equals(u.getStatus())) throw ApiException.badRequest("Giám thị phải là giáo viên đang hoạt động");
        return u;
    }

    private int notifyProctorDuty(ExamPeriod period, ExamSchedule schedule, ExamRoom room,
                                  String teacherId, int position) {
        if (teacherId == null || teacherId.isBlank()) return 0;
        String refId = proctorDutyRef(room, teacherId, position);
        if (notifications.hasNotification(teacherId, "EXAM_PERIOD", refId)) return 0;
        notifications.notifyUser(teacherId, "EXAM_PROCTOR_DUTY", "IMPORTANT",
                "Nhiệm vụ coi thi trong 1 tuần tới",
                period.getName() + " · " + schedule.getSubjectName() + " · Phòng "
                        + room.getRoomCode() + " · " + schedule.getExamDate() + " lúc "
                        + schedule.getStartTime() + ". Mở Lịch thi & nhiệm vụ để xem chi tiết.",
                "EXAM_PERIOD", refId);
        return 1;
    }

    private Instant scoreEntryOpensAt(ExamSchedule schedule) {
        return scheduleEndAt(schedule).plusDays(7).toInstant();
    }

    private ZonedDateTime scheduleStartAt(ExamSchedule schedule) {
        return ZonedDateTime.of(schedule.getExamDate(), LocalTime.parse(schedule.getStartTime()), SCHOOL_ZONE);
    }

    private ZonedDateTime scheduleEndAt(ExamSchedule schedule) {
        return scheduleStartAt(schedule).plusMinutes(schedule.getDurationMinutes());
    }

    private boolean hasEnteredScores(ExamSchedule schedule, String classId) {
        Set<String> studentIds = candidates.findByScheduleIdAndClassId(schedule.getId(), classId).stream()
                .map(ExamCandidate::getStudentId)
                .collect(java.util.stream.Collectors.toSet());
        return results.findByExamPeriodId(schedule.getExamPeriodId()).stream()
                .anyMatch(result -> schedule.getId().equals(result.getScheduleId())
                        && studentIds.contains(result.getStudentId())
                        && result.getScore() != null);
    }

    private boolean isQualifiedForSubject(User teacher, ExamSchedule schedule, ExamPeriod period) {
        return isQualifiedForSubject(teacher.getId(), teacher.getMainSubject(), schedule, period);
    }

    private boolean isQualifiedForSubject(UserDto teacher, ExamSchedule schedule, ExamPeriod period) {
        return isQualifiedForSubject(teacher.id(), teacher.mainSubject(), schedule, period);
    }

    private boolean isQualifiedForSubject(String teacherId, String teacherMainSubject,
                                          ExamSchedule schedule, ExamPeriod period) {
        String mainSubject = clean(teacherMainSubject);
        boolean profileMatches = mainSubject != null
                && (schedule.getSubjectId().equalsIgnoreCase(mainSubject)
                    || schedule.getSubjectName().equalsIgnoreCase(mainSubject));
        return profileMatches || teachingAssignments.assignmentsOfTeacher(teacherId).stream()
                .anyMatch(item -> schedule.getSubjectId().equals(item.getSubjectId())
                        && period.getSemesterId().equals(item.getSemesterId()));
    }

    private void clearDutyNotificationsForSchedule(ExamSchedule schedule) {
        rooms.findByScheduleId(schedule.getId()).forEach(this::removeProctorNotifications);
        gradingAssignments.findByScheduleId(schedule.getId()).forEach(this::removeGradingNotifications);
    }

    private void removeProctorNotifications(ExamRoom room) {
        if (room == null || room.getId() == null) return;
        if (room.getProctorOneId() != null) {
            notifications.removeByReference("EXAM_PERIOD",
                    proctorDutyRef(room, room.getProctorOneId(), 1));
        }
        if (room.getProctorTwoId() != null) {
            notifications.removeByReference("EXAM_PERIOD",
                    proctorDutyRef(room, room.getProctorTwoId(), 2));
        }
    }

    private void removeGradingNotifications(ExamGradingAssignment assignment) {
        if (assignment == null || assignment.getId() == null) return;
        notifications.removeByReference("EXAM_PERIOD", gradingDutyRef(assignment));
        notifications.removeByReference("EXAM_PERIOD", scoreEntryRef(assignment));
    }

    private String proctorDutyRef(ExamRoom room, String teacherId, int position) {
        return room.getScheduleId() + ":proctor:" + room.getId() + ":" + position + ":" + teacherId;
    }

    private String gradingDutyRef(ExamGradingAssignment assignment) {
        return assignment.getScheduleId() + ":grading:" + assignment.getId();
    }

    private String scoreEntryRef(ExamGradingAssignment assignment) {
        return assignment.getScheduleId() + ":score-entry:" + assignment.getId();
    }

    private ExamSchedule requireSchedule(String id) { return schedules.findById(id).orElseThrow(() -> ApiException.notFound("Lịch thi")); }
    private void assertEditable(ExamPeriod p) { if (p.isScoreEntryLocked() || "CONFIRMED".equals(p.getStatus())) throw ApiException.conflict("Kỳ thi đã khóa hoặc xác nhận"); }
    private String idOr(String id, String prefix) { return id == null || id.isBlank() ? Ids.gen(prefix) : id; }
    private String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
