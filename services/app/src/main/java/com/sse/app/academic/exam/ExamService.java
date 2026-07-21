package com.sse.app.academic.exam;

import com.sse.app.academic.exam.ExamDtos.*;
import com.sse.app.academic.structure.*;
import com.sse.app.academic.timetable.TeachingAssignmentService;
import com.sse.app.common.*;
import com.sse.app.identity.*;
import com.sse.app.notification.NotificationService;
import com.sse.app.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service @RequiredArgsConstructor
public class ExamService {
    private final ExamPeriodRepository periods;
    private final ExamScheduleRepository schedules;
    private final ExamRoomRepository rooms;
    private final ExamCandidateRepository candidates;
    private final ExamResultRepository results;
    private final ExamReviewRepository reviews;
    private final ExamScoreAdjustmentRepository adjustments;
    private final StructureService structure;
    private final UserService users;
    private final TeachingAssignmentService teachingAssignments;
    private final NotificationService notifications;

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
            Set<String> allocatedClasses = candidates.findByScheduleId(schedule.getId()).stream()
                    .map(ExamCandidate::getClassId).collect(java.util.stream.Collectors.toSet());
            List<String> missingClasses = schedule.getClassIds().stream().filter(classId -> !allocatedClasses.contains(classId))
                    .map(classId -> structure.getClass(classId).getCode()).sorted().toList();
            if (!missingClasses.isEmpty()) throw ApiException.conflict("Môn " + schedule.getSubjectName()
                    + " chưa xếp thí sinh cho lớp " + String.join(", ", missingClasses));
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
                    Set<String> assignedClasses = candidates.findByScheduleId(schedule.getId()).stream()
                            .map(ExamCandidate::getClassId)
                            .filter(classId -> teachingAssignments.isAssigned(actor.id(), classId,
                                    schedule.getSubjectId(), period.getSemesterId()))
                            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
                    for (String classId : assignedClasses) {
                        ExamCandidate sample = candidates.findByScheduleIdAndClassId(schedule.getId(), classId).stream().findFirst().orElse(null);
                        items.add(agendaItem(schedule.getId() + ":grade:" + classId, "GRADE_ENTRY", "Nhập điểm",
                                period, schedule, null, sample, gradingStatus(period, schedule, classId)));
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
        invalidatePublishedSchedule(period); schedules.delete(s);
    }

    public List<ExamRoom> rooms(String scheduleId) { requireSchedule(scheduleId); return rooms.findByScheduleId(scheduleId); }

    @Transactional
    public ExamRoom saveRoom(String scheduleId, SaveRoomRequest r) {
        ExamSchedule schedule = requireSchedule(scheduleId); ExamPeriod period = requirePeriod(schedule.getExamPeriodId()); assertEditable(period);
        structure.listRooms().stream().filter(room -> room.getCode().equalsIgnoreCase(r.roomCode())).findFirst()
                .orElseThrow(() -> ApiException.badRequest("Phòng thi không tồn tại"));
        User p1 = teacher(r.proctorOneId()); User p2 = teacher(r.proctorTwoId());
        if (p1 != null && p2 != null && p1.getId().equals(p2.getId())) throw ApiException.badRequest("Hai giám thị phải khác nhau");
        assertRoomAndProctorsAvailable(schedule, r, p1, p2);
        invalidatePublishedSchedule(period);
        ExamRoom room = r.id() == null ? new ExamRoom() : rooms.findById(r.id()).orElseThrow(() -> ApiException.notFound("Phòng thi"));
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
        invalidatePublishedSchedule(period);
        rooms.delete(room);
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

    public List<ExamResult> results(String periodId, String scheduleId, String studentId) {
        requirePeriod(periodId);
        return results.findByExamPeriodId(periodId).stream()
                .filter(r -> scheduleId == null || scheduleId.equals(r.getScheduleId()))
                .filter(r -> studentId == null || studentId.equals(r.getStudentId())).toList();
    }

    public List<TeacherGradingTask> gradingTasks(String teacherId) {
        User teacher = users.getById(teacherId);
        if (!"TEACHER".equals(teacher.getRole())) throw ApiException.forbidden("Chỉ giáo viên được xem danh sách nhập điểm thi");
        List<TeacherGradingTask> tasks = new ArrayList<>();
        for (ExamPeriod period : periods.findAll()) {
            if (!period.isSchedulePublished()) continue;
            for (ExamSchedule schedule : schedules.findByExamPeriodId(period.getId())) {
                for (String classId : schedule.getClassIds()) {
                    if (!teachingAssignments.isAssigned(teacherId, classId, schedule.getSubjectId(), period.getSemesterId())) continue;
                    SchoolClass schoolClass = structure.getClass(classId);
                    Map<String, ExamRoom> roomById = rooms.findByScheduleId(schedule.getId()).stream()
                            .collect(java.util.stream.Collectors.toMap(ExamRoom::getId, room -> room));
                    Map<String, ExamResult> resultByStudent = results.findByExamPeriodId(period.getId()).stream()
                            .filter(result -> schedule.getId().equals(result.getScheduleId()))
                            .collect(java.util.stream.Collectors.toMap(ExamResult::getStudentId, result -> result));
                    List<TeacherExamCandidateRow> rows = candidates.findByScheduleIdAndClassId(schedule.getId(), classId).stream()
                            .sorted(Comparator.comparing(ExamCandidate::getCandidateNo))
                            .map(candidate -> {
                                ExamResult result = resultByStudent.get(candidate.getStudentId());
                                ExamRoom room = roomById.get(candidate.getExamRoomId());
                                return new TeacherExamCandidateRow(candidate.getId(), candidate.getStudentId(), candidate.getStudentName(),
                                        candidate.getStudentCode(), candidate.getCandidateNo(), candidate.getSeatNo(),
                                        room == null ? null : room.getRoomCode(), result == null ? null : result.getId(),
                                        result == null ? null : result.getScore(), result == null ? null : result.getNote(),
                                        result == null ? "DRAFT" : result.getStatus(), result == null ? null : result.getVersion());
                            }).toList();
                    tasks.add(new TeacherGradingTask(period.getId(), period.getName(), schedule.getId(),
                            schedule.getSubjectId(), schedule.getSubjectName(), classId, schoolClass.getCode(),
                            schedule.getExamDate(), schedule.getStartTime(), period.isScoreEntryLocked(), rows));
                }
            }
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
        ExamSchedule schedule = requireSchedule(request.scheduleId());
        if (!periodId.equals(schedule.getExamPeriodId())) throw ApiException.badRequest("Lịch thi không thuộc kỳ thi");
        Map<String, ExamCandidate> candidateByStudent = candidates.findByScheduleId(schedule.getId()).stream()
                .collect(java.util.stream.Collectors.toMap(ExamCandidate::getStudentId, candidate -> candidate));
        User actor = users.getById(actorId);
        Instant now = Instant.now(); List<ExamResult> saved = new ArrayList<>();
        for (ResultEntry entry : request.entries()) {
            ExamCandidate candidate = candidateByStudent.get(entry.studentId());
            if (candidate == null) throw ApiException.badRequest("Học sinh chưa được phân phòng cho môn thi");
            if ("TEACHER".equals(actor.getRole()) && !teachingAssignments.isAssigned(
                    actorId, candidate.getClassId(), schedule.getSubjectId(), period.getSemesterId())) {
                throw ApiException.forbidden("Giáo viên chỉ được nhập điểm môn và lớp đã được phân công");
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
        teachingAssignments.assignmentsOfClass(candidate.getClassId(), requirePeriod(periodId).getSemesterId()).stream()
                .filter(assignment -> schedule.getSubjectId().equals(assignment.getSubjectId()))
                .map(com.sse.app.academic.timetable.TeachingAssignment::getTeacherId).distinct()
                .forEach(teacherId -> notifications.notifyUser(teacherId, "EXAM_REVIEW", "IMPORTANT",
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
        ExamPeriod period = periods.findById(review.getExamPeriodId()).orElse(null);
        return candidate != null && period != null && teachingAssignments.isAssigned(
                teacherId, candidate.getClassId(), result.getSubjectId(), period.getSemesterId());
    }

    public List<ExamScoreAdjustment> adjustments(String periodId) { requirePeriod(periodId); return adjustments.findByExamPeriodIdOrderByAdjustedAtDesc(periodId); }

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

        Map<String, Integer> proctorLoads = new LinkedHashMap<>();
        for (ExamSchedule schedule : periodSchedules) {
            for (ExamRoom room : rooms.findByScheduleId(schedule.getId())) {
                if (room.getProctorOneId() != null) proctorLoads.merge(room.getProctorOneId(), 1, Integer::sum);
                if (room.getProctorTwoId() != null) proctorLoads.merge(room.getProctorTwoId(), 1, Integer::sum);
            }
        }
        proctorLoads.forEach((teacherId, count) -> notifications.notifyUser(teacherId, "EXAM_DUTY", "IMPORTANT",
                updated ? "Nhiệm vụ coi thi đã cập nhật" : "Bạn có nhiệm vụ coi thi",
                period.getName() + ": " + count + " ca coi thi. Mở Lịch thi & nhiệm vụ để xem chi tiết.",
                "EXAM_PERIOD", period.getId()));

        Set<String> gradingTeachers = new LinkedHashSet<>();
        for (ExamSchedule schedule : periodSchedules) {
            Set<String> classIds = candidates.findByScheduleId(schedule.getId()).stream()
                    .map(ExamCandidate::getClassId).collect(java.util.stream.Collectors.toSet());
            for (String classId : classIds) {
                teachingAssignments.assignmentsOfClass(classId, period.getSemesterId()).stream()
                        .filter(item -> schedule.getSubjectId().equals(item.getSubjectId()))
                        .map(com.sse.app.academic.timetable.TeachingAssignment::getTeacherId)
                        .forEach(gradingTeachers::add);
            }
        }
        gradingTeachers.forEach(teacherId -> notifications.notifyUser(teacherId, "EXAM_DUTY", "IMPORTANT",
                updated ? "Nhiệm vụ nhập điểm đã cập nhật" : "Bạn có nhiệm vụ nhập điểm kỳ thi",
                period.getName() + ": xem các lớp, môn phụ trách và trạng thái nhập điểm trong Lịch thi & nhiệm vụ.",
                "EXAM_PERIOD", period.getId()));
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
        if (schedule.getExamDate().isAfter(LocalDate.now())) return "NOT_STARTED";
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
        structure.getYear(r.academicYearId()); Semester sem = structure.getSemester(r.semesterId());
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
    private ExamSchedule requireSchedule(String id) { return schedules.findById(id).orElseThrow(() -> ApiException.notFound("Lịch thi")); }
    private void assertEditable(ExamPeriod p) { if (p.isScoreEntryLocked() || "CONFIRMED".equals(p.getStatus())) throw ApiException.conflict("Kỳ thi đã khóa hoặc xác nhận"); }
    private String idOr(String id, String prefix) { return id == null || id.isBlank() ? Ids.gen(prefix) : id; }
    private String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
