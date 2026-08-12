package com.sse.app.academic.exam;

import com.sse.app.academic.exam.ExamDtos.AutoGenerateRequest;
import com.sse.app.academic.exam.ExamDtos.ExamPeriodRequest;
import com.sse.app.academic.exam.ExamDtos.ExamPeriodResponse;
import com.sse.app.academic.exam.ExamDtos.ExamRoomResponse;
import com.sse.app.academic.exam.ExamDtos.ExamSessionResponse;
import com.sse.app.academic.exam.ExamDtos.ExamStudentResponse;
import com.sse.app.academic.exam.ExamDtos.ExamValidationIssue;
import com.sse.app.academic.exam.ExamDtos.ExamValidationResponse;
import com.sse.app.academic.exam.ExamDtos.ExamVersionChange;
import com.sse.app.academic.exam.ExamDtos.ExamVersionDetail;
import com.sse.app.academic.exam.ExamDtos.ExamVersionDiff;
import com.sse.app.academic.exam.ExamDtos.ExamVersionResponse;
import com.sse.app.academic.exam.ExamDtos.PublishedExamView;
import com.sse.app.academic.exam.ExamDtos.RoomAssignmentRequest;
import com.sse.app.academic.exam.ExamDtos.SessionRequest;
import com.sse.app.academic.exam.ExamDtos.TeacherUnavailabilityRequest;
import com.sse.app.academic.exam.ExamDtos.TeacherUnavailabilityResponse;
import com.sse.app.academic.structure.AcademicYear;
import com.sse.app.academic.structure.Room;
import com.sse.app.academic.structure.SchoolClass;
import com.sse.app.academic.structure.SchoolHoliday;
import com.sse.app.academic.structure.Semester;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.academic.structure.Subject;
import com.sse.app.academic.planning.ExamAssessmentSourceService;
import com.sse.app.academic.planning.ExamAssessmentSourceService.ExamAssessmentSource;
import com.sse.app.academic.planning.ExamAssessmentSourceService.SourceReadiness;
import com.sse.app.academic.teaching.TeacherClassSubject;
import com.sse.app.academic.teaching.TeachingAssignmentRepository;
import com.sse.app.academic.timetable.TimetableService;
import com.sse.app.academic.timetable.TimetableSlot;
import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.event.DomainEventPublisher;
import com.sse.app.identity.User;
import com.sse.app.identity.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ExamScheduleService {
    private static final Set<String> NON_EXAM_SUBJECT_CODES = Set.of(
            "FLAG", "HOMEROOM", "CHAOCO", "SHL");
    private static final Set<String> GRADES = Set.of("K10", "K11", "K12");
    private static final Set<String> TYPES = Set.of("MIDTERM", "FINAL", "MAKEUP");

    private final ExamPeriodRepository periods;
    private final ExamScheduleVersionRepository versions;
    private final ExamSessionRepository sessions;
    private final ExamRoomAssignmentRepository roomAssignments;
    private final ExamRoomStudentRepository roomStudents;
    private final ExamTeacherUnavailabilityRepository unavailability;
    private final StructureService structure;
    private final UserRepository users;
    private final TeachingAssignmentRepository teachingAssignments;
    private final TimetableService timetable;
    private final DomainEventPublisher events;
    private final ExamAssessmentSourceService assessmentSources;

    public ExamScheduleService(
            ExamPeriodRepository periods,
            ExamScheduleVersionRepository versions,
            ExamSessionRepository sessions,
            ExamRoomAssignmentRepository roomAssignments,
            ExamRoomStudentRepository roomStudents,
            ExamTeacherUnavailabilityRepository unavailability,
            StructureService structure,
            UserRepository users,
            TeachingAssignmentRepository teachingAssignments,
            TimetableService timetable,
            DomainEventPublisher events,
            ExamAssessmentSourceService assessmentSources) {
        this.periods = periods;
        this.versions = versions;
        this.sessions = sessions;
        this.roomAssignments = roomAssignments;
        this.roomStudents = roomStudents;
        this.unavailability = unavailability;
        this.structure = structure;
        this.users = users;
        this.teachingAssignments = teachingAssignments;
        this.timetable = timetable;
        this.events = events;
        this.assessmentSources = assessmentSources;
    }

    public SourceReadiness assessmentSourceReadiness(
            String academicYearId, String semesterId,
            String examType, List<String> gradeLevels) {
        return assessmentSources.readiness(
                academicYearId, semesterId, examType, gradeLevels);
    }

    public List<ExamPeriodResponse> listPeriods(String academicYearId) {
        List<ExamPeriod> rows = academicYearId == null || academicYearId.isBlank()
                ? periods.findAll()
                : periods.findByAcademicYearIdOrderByStartDateDesc(academicYearId);
        return rows.stream()
                .sorted(Comparator.comparing(ExamPeriod::getStartDate).reversed()
                        .thenComparing(ExamPeriod::getCode))
                .map(this::periodResponse)
                .toList();
    }

    public ExamPeriodResponse getPeriodResponse(String id) {
        return periodResponse(requirePeriod(id));
    }

    @Transactional
    public ExamPeriodResponse createPeriod(ExamPeriodRequest request, String actorId) {
        AcademicYear year = structure.getYear(request.academicYearId());
        Semester semester = structure.getSemester(request.semesterId());
        if (!year.getId().equals(semester.getAcademicYearId())) {
            throw ApiException.badRequest("Học kỳ không thuộc năm học đã chọn");
        }
        String code = normalizeCode(request.code());
        if (periods.existsByAcademicYearIdAndCodeIgnoreCase(year.getId(), code)) {
            throw ApiException.conflict("Mã đợt thi đã tồn tại trong năm học");
        }
        validatePeriodRequest(request, semester);
        Instant now = Instant.now();
        ExamPeriod period = periods.save(ExamPeriod.builder()
                .id(Ids.gen("exam-period"))
                .code(code)
                .name(request.name().trim())
                .academicYearId(year.getId())
                .semesterId(semester.getId())
                .examType(normalizeType(request.examType()))
                .status("DRAFT")
                .scopeGrades(joinGrades(request.gradeLevels()))
                .allowSubjectTeacherProctor(request.allowSubjectTeacherProctor())
                .startDate(request.startDate())
                .endDate(request.endDate())
                .createdBy(actorId)
                .createdAt(now)
                .updatedAt(now)
                .build());
        versions.save(ExamScheduleVersion.builder()
                .id(Ids.gen("exam-ver"))
                .examPeriodId(period.getId())
                .versionNo(1)
                .status("DRAFT")
                .changeReason("Khởi tạo đợt thi")
                .createdBy(actorId)
                .createdAt(now)
                .contentUpdatedAt(now)
                .build());
        return periodResponse(period);
    }

    @Transactional
    public ExamPeriodResponse updatePeriod(String id, ExamPeriodRequest request) {
        ExamPeriod period = requirePeriod(id);
        if (!"DRAFT".equals(period.getStatus())) {
            throw ApiException.conflict("Chỉ đợt thi ở trạng thái nháp mới được sửa thông tin chung");
        }
        Semester semester = structure.getSemester(request.semesterId());
        if (!request.academicYearId().equals(semester.getAcademicYearId())) {
            throw ApiException.badRequest("Học kỳ không thuộc năm học đã chọn");
        }
        validatePeriodRequest(request, semester);
        String code = normalizeCode(request.code());
        if (!period.getCode().equalsIgnoreCase(code)
                && periods.existsByAcademicYearIdAndCodeIgnoreCase(request.academicYearId(), code)) {
            throw ApiException.conflict("Mã đợt thi đã tồn tại trong năm học");
        }
        period.setCode(code);
        period.setName(request.name().trim());
        period.setAcademicYearId(request.academicYearId());
        period.setSemesterId(request.semesterId());
        period.setExamType(normalizeType(request.examType()));
        period.setScopeGrades(joinGrades(request.gradeLevels()));
        period.setAllowSubjectTeacherProctor(request.allowSubjectTeacherProctor());
        period.setStartDate(request.startDate());
        period.setEndDate(request.endDate());
        period.setUpdatedAt(Instant.now());
        ExamPeriodResponse response = periodResponse(periods.save(period));
        touchDraftVersions(period.getId());
        return response;
    }

    @Transactional
    public void deletePeriod(String id) {
        ExamPeriod period = requirePeriod(id);
        List<ExamScheduleVersion> history = versions.findByExamPeriodIdOrderByVersionNoDesc(id);
        boolean hasPublishedHistory = history.stream().anyMatch(version ->
                Set.of("PUBLISHED", "ARCHIVED", "RECALLED").contains(version.getStatus()));
        if (period.getPublishedVersionId() != null || hasPublishedHistory) {
            throw ApiException.conflict(
                    "Đợt thi đã từng phát hành nên không thể xóa. Hãy đóng hoặc hủy hiệu lực để giữ lịch sử và audit.");
        }
        periods.delete(period);
        periods.flush();
    }

    @Transactional
    public ExamPeriodResponse changePeriodStatus(String id, String status) {
        ExamPeriod period = requirePeriod(id);
        String normalized = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("CLOSED", "CANCELLED").contains(normalized)) {
            throw ApiException.badRequest("Trạng thái đợt thi không hợp lệ");
        }
        if (period.getPublishedVersionId() == null && "CLOSED".equals(normalized)) {
            throw ApiException.conflict("Đợt thi chưa có lịch được phát hành");
        }
        period.setStatus(normalized);
        period.setUpdatedAt(Instant.now());
        return periodResponse(periods.save(period));
    }

    public List<ExamVersionResponse> listVersions(String periodId) {
        requirePeriod(periodId);
        return versions.findByExamPeriodIdOrderByVersionNoDesc(periodId).stream()
                .map(this::versionResponse)
                .toList();
    }

    @Transactional
    public ExamVersionResponse createVersion(String periodId, String reason, String actorId) {
        ExamPeriod period = requirePeriod(periodId);
        if ("CLOSED".equals(period.getStatus()) || "CANCELLED".equals(period.getStatus())) {
            throw ApiException.conflict("Đợt thi đã đóng hoặc hủy");
        }
        if (versions.findByExamPeriodIdAndStatus(periodId, "DRAFT").isPresent()) {
            throw ApiException.conflict("Đợt thi đang có một phiên bản nháp cần xử lý trước");
        }
        List<ExamScheduleVersion> history = versions.findByExamPeriodIdOrderByVersionNoDesc(periodId);
        ExamScheduleVersion source = period.getPublishedVersionId() == null ? null
                : requireVersion(period.getPublishedVersionId());
        Instant now = Instant.now();
        ExamScheduleVersion target = versions.save(ExamScheduleVersion.builder()
                .id(Ids.gen("exam-ver"))
                .examPeriodId(periodId)
                .versionNo(history.stream().mapToInt(ExamScheduleVersion::getVersionNo).max().orElse(0) + 1)
                .status("DRAFT")
                .basedOnVersionId(source == null ? null : source.getId())
                .changeReason(reason.trim())
                .createdBy(actorId)
                .createdAt(now)
                .contentUpdatedAt(now)
                .build());
        if (source != null) cloneVersionContent(source.getId(), target.getId(), now);
        return versionResponse(target);
    }

    public ExamVersionDetail detail(String versionId) {
        ExamScheduleVersion version = requireVersion(versionId);
        ExamPeriod period = requirePeriod(version.getExamPeriodId());
        return new ExamVersionDetail(
                periodResponse(period), versionResponse(version),
                sessionResponses(versionId), validate(versionId),
                unavailableResponses(period.getId()), compareVersion(version));
    }

    @Transactional
    public ExamVersionDetail generate(String versionId, AutoGenerateRequest request) {
        ExamScheduleVersion version = requireDraftVersion(versionId);
        ExamPeriod period = requirePeriod(version.getExamPeriodId());
        List<String> grades = splitGrades(period.getScopeGrades());
        SourceReadiness sourceReadiness = assessmentSources.readiness(
                period.getAcademicYearId(), period.getSemesterId(),
                period.getExamType(), grades);
        if (!sourceReadiness.ready()) {
            throw ApiException.conflict("Chưa thể tạo lịch từ GĐ3: "
                    + String.join("; ", sourceReadiness.issues()));
        }
        List<ExamAssessmentSource> sourceRows = sourceReadiness.sources();
        Map<String, List<ExamAssessmentSource>> sourcesBySubject = sourceRows.stream()
                .collect(Collectors.groupingBy(
                        source -> source.subjectId() + "|" + source.weekNumber(), LinkedHashMap::new,
                        Collectors.toList()));
        List<LocalDate> dates = request.examDates().stream().distinct().sorted().toList();
        List<LocalTime> times = request.startTimes().stream().distinct().sorted().toList();
        validateGenerationSlots(period, dates, times, sourcesBySubject.size());

        clearVersion(versionId);
        List<Room> activeRooms = structure.listRooms().stream()
                .filter(Room::isActive)
                .filter(room -> room.getCapacity() != null && room.getCapacity() > 0)
                .filter(room -> !"GYM".equalsIgnoreCase(room.getRoomType()))
                .sorted(Comparator.comparing(Room::getCapacity).reversed().thenComparing(Room::getCode))
                .toList();
        if (activeRooms.isEmpty()) throw ApiException.conflict("Không có phòng thi đang hoạt động và có sức chứa");
        List<User> teachers = users.findByRoleAndStatusNot("TEACHER", "DELETED").stream()
                .filter(user -> "ACTIVE".equals(user.getStatus()) && user.getDeletedAt() == null)
                .sorted(Comparator.comparing(User::getTeacherCode, Comparator.nullsLast(String::compareTo))
                        .thenComparing(User::getFullName))
                .toList();
        if (teachers.size() < 2) throw ApiException.conflict("Cần ít nhất hai giáo viên hoạt động để xếp coi thi");

        Map<String, List<User>> studentsByGrade = grades.stream().collect(Collectors.toMap(
                Function.identity(), grade -> activeStudents(period.getAcademicYearId(), grade),
                (left, right) -> left, LinkedHashMap::new));
        Map<String, Subject> subjectMap = structure.listSubjects().stream()
                .collect(Collectors.toMap(Subject::getId, Function.identity()));
        Map<String, Set<String>> subjectTeachers = subjectTeachers(period.getSemesterId());
        Map<String, List<TimetableSlot>> teacherTimetable = timetable.allSlots().stream()
                .filter(slot -> period.getSemesterId().equals(slot.getSemesterId()))
                .filter(slot -> slot.getTeacherId() != null)
                .collect(Collectors.groupingBy(TimetableSlot::getTeacherId));
        Map<String, List<ExamTeacherUnavailability>> unavailableByTeacher = unavailability
                .findByExamPeriodIdOrderByUnavailableDateAsc(period.getId()).stream()
                .collect(Collectors.groupingBy(ExamTeacherUnavailability::getTeacherId));
        PublishedUsage publishedUsage = publishedUsage(period.getId());
        Map<String, Integer> roomUsage = new HashMap<>(publishedUsage.roomCounts());
        Map<String, Integer> teacherUsage = new HashMap<>(publishedUsage.teacherCounts());

        Instant now = Instant.now();
        List<Map.Entry<String, List<ExamAssessmentSource>>> sourceGroups =
                new ArrayList<>(sourcesBySubject.entrySet());
        sourceGroups.sort(Comparator
                .comparingInt((Map.Entry<String, List<ExamAssessmentSource>> entry) -> entry.getValue().stream()
                        .mapToInt(ExamAssessmentSource::weekNumber).min().orElse(Integer.MAX_VALUE))
                .thenComparingInt(entry -> subjectPriority(subjectMap.get(entry.getValue().get(0).subjectId())))
                .thenComparing(entry -> subjectMap.get(entry.getValue().get(0).subjectId()).getName(), String.CASE_INSENSITIVE_ORDER));
        List<ExamSlot> candidateSlots = dates.stream()
                .flatMap(date -> times.stream().map(time -> new ExamSlot(date, time)))
                .toList();
        Set<ExamSlot> selectedSlots = new HashSet<>();
        for (int subjectIndex = 0; subjectIndex < sourceGroups.size(); subjectIndex++) {
            Map.Entry<String, List<ExamAssessmentSource>> group = sourceGroups.get(subjectIndex);
            Subject subject = subjectMap.get(group.getValue().get(0).subjectId());
            ExamSlot slot = candidateSlots.stream()
                    .filter(candidate -> !selectedSlots.contains(candidate))
                    .filter(candidate -> group.getValue().stream().allMatch(source ->
                            isWithinPlannedWindow(source, candidate.date())))
                    .filter(candidate -> canScheduleGroup(candidate, group.getValue(), period,
                            activeRooms, teachers, studentsByGrade, subjectTeachers,
                            unavailableByTeacher, publishedUsage))
                    .findFirst()
                    .orElseThrow(() -> ApiException.conflict(
                            "Không tìm được ca hợp lệ cho " + subject.getName()
                                    + " trong tuần kế hoạch " + group.getValue().get(0).weekNumber()
                                    + " (" + group.getValue().get(0).plannedStartDate() + " đến "
                                    + group.getValue().get(0).plannedEndDate()
                                    + "). Hãy mở rộng đợt thi đúng tuần GĐ3, thêm phòng/giáo viên hoặc kiểm tra lịch bận và các đợt thi đã phát hành."));
            selectedSlots.add(slot);
            Set<String> usedRooms = new HashSet<>(occupiedRoomIds(publishedUsage, slot,
                    group.getValue().stream().mapToInt(ExamAssessmentSource::durationMinutes).max().orElse(90)));
            Set<String> usedTeachers = new HashSet<>(occupiedTeacherIds(publishedUsage, slot,
                    group.getValue().stream().mapToInt(ExamAssessmentSource::durationMinutes).max().orElse(90)));
            for (ExamAssessmentSource source : group.getValue()) {
                String grade = source.gradeLevel();
                int durationMinutes = source.durationMinutes();
                LocalTime end = slot.startTime().plusMinutes(durationMinutes);
                List<User> gradeStudents = studentsByGrade.getOrDefault(grade, List.of());
                if (gradeStudents.isEmpty()) {
                    throw ApiException.conflict("Khối " + grade.substring(1) + " chưa có học sinh hoạt động");
                }
                ExamSession session = sessions.save(ExamSession.builder()
                        .id(Ids.gen("exam-session"))
                        .versionId(versionId)
                        .sourceAssessmentPlanId(source.assessmentPlanId())
                        .sourceTrainingPlanId(source.trainingPlanId())
                        .sourcePlanVersion(source.planVersion())
                        .sourcePlanName(source.planName())
                        .sourcePlanStatus(source.planStatus())
                        .sourceAssessmentName(source.assessmentName())
                        .sourceAssessmentType(source.assessmentType())
                        .sourceAssessmentForm(source.assessmentForm())
                        .sourceAssessmentWeek(source.weekNumber())
                        .sourcePlannedStartDate(source.plannedStartDate())
                        .sourcePlannedEndDate(source.plannedEndDate())
                        .sourceSyncedAt(now)
                        .sourceUpdatedAt(source.sourceUpdatedAt())
                        .subjectId(source.subjectId())
                        .gradeLevel(grade)
                        .examDate(slot.date())
                        .startTime(slot.startTime())
                        .durationMinutes(durationMinutes)
                        .createdAt(now).updatedAt(now).build());
                allocateSession(session, period, subject, gradeStudents,
                        activeRooms, teachers, usedRooms, usedTeachers, subjectTeachers,
                        teacherTimetable, unavailableByTeacher, roomUsage, teacherUsage, end, now);
            }
        }
        ExamValidationResponse checked = validate(versionId);
        if (!checked.valid()) {
            throw ApiException.conflict("Bộ xếp lịch còn " + checked.errorCount()
                    + " lỗi bắt buộc: " + checked.issues().stream()
                    .filter(issue -> "ERROR".equals(issue.severity()))
                    .limit(3).map(ExamValidationIssue::message).collect(Collectors.joining("; ")));
        }
        touchVersion(versionId);
        return detail(versionId);
    }

    @Transactional
    public ExamSessionResponse addSession(String versionId, SessionRequest request) {
        requireDraftVersion(versionId);
        ExamPeriod period = periodOfVersion(versionId);
        ExamAssessmentSource source = requireRequestSource(period, request);
        validateSessionRequest(period, request.examDate(), request.startTime());
        validatePlannedWeek(source, request.examDate(), request.scheduleDeviationReason());
        if (sessions.existsByVersionIdAndSourceAssessmentPlanId(
                versionId, source.assessmentPlanId())) {
            throw ApiException.conflict("Kế hoạch kiểm tra này đã có ca thi trong phiên bản");
        }
        Instant now = Instant.now();
        ExamSession saved = sessions.save(ExamSession.builder()
                .id(Ids.gen("exam-session")).versionId(versionId)
                .sourceAssessmentPlanId(source.assessmentPlanId())
                .sourceTrainingPlanId(source.trainingPlanId())
                .sourcePlanVersion(source.planVersion())
                .sourcePlanName(source.planName()).sourcePlanStatus(source.planStatus())
                .sourceAssessmentName(source.assessmentName())
                .sourceAssessmentType(source.assessmentType())
                .sourceAssessmentForm(source.assessmentForm())
                .sourceAssessmentWeek(source.weekNumber())
                .sourcePlannedStartDate(source.plannedStartDate())
                .sourcePlannedEndDate(source.plannedEndDate())
                .sourceSyncedAt(now).sourceUpdatedAt(source.sourceUpdatedAt())
                .subjectId(source.subjectId()).gradeLevel(source.gradeLevel())
                .examDate(request.examDate()).startTime(request.startTime())
                .durationMinutes(source.durationMinutes())
                .scheduleDeviationReason(trimToNull(request.scheduleDeviationReason()))
                .notes(trimToNull(request.notes()))
                .createdAt(now).updatedAt(now).build());
        allocateManualSession(saved, period);
        touchVersion(versionId);
        return detail(versionId).sessions().stream()
                .filter(row -> row.id().equals(saved.getId())).findFirst().orElseThrow();
    }

    @Transactional
    public ExamSessionResponse updateSession(String versionId, String sessionId, SessionRequest request) {
        requireDraftVersion(versionId);
        ExamPeriod period = periodOfVersion(versionId);
        ExamSession session = requireSession(versionId, sessionId);
        validateSessionRequest(period, request.examDate(), request.startTime());
        if (!Objects.equals(session.getSourceAssessmentPlanId(),
                request.sourceAssessmentPlanId())) {
            throw ApiException.badRequest(
                    "Không thể đổi kế hoạch kiểm tra nguồn của ca thi; hãy xóa ca và thêm lại từ nguồn GĐ3");
        }
        ExamAssessmentSource source = requireRequestSource(period, request);
        validatePlannedWeek(source, request.examDate(), request.scheduleDeviationReason());
        session.setExamDate(request.examDate());
        session.setStartTime(request.startTime());
        session.setScheduleDeviationReason(trimToNull(request.scheduleDeviationReason()));
        session.setNotes(trimToNull(request.notes()));
        session.setUpdatedAt(Instant.now());
        sessions.save(session);
        touchVersion(versionId);
        return detail(versionId).sessions().stream().filter(row -> row.id().equals(sessionId)).findFirst().orElseThrow();
    }

    @Transactional
    public void deleteSession(String versionId, String sessionId) {
        requireDraftVersion(versionId);
        ExamSession session = requireSession(versionId, sessionId);
        List<String> ids = List.of(session.getId());
        roomStudents.deleteBySessionIdIn(ids);
        roomAssignments.deleteBySessionIdIn(ids);
        sessions.delete(session);
        touchVersion(versionId);
    }

    @Transactional
    public ExamRoomResponse updateRoom(String versionId, String assignmentId, RoomAssignmentRequest request) {
        ExamScheduleVersion version = requireDraftVersion(versionId);
        ExamRoomAssignment assignment = roomAssignments.findById(assignmentId)
                .orElseThrow(() -> ApiException.notFound("Phân công phòng thi"));
        ExamSession session = requireSession(version.getId(), assignment.getSessionId());
        ExamPeriod period = requirePeriod(version.getExamPeriodId());
        Room room = structure.getRoom(request.roomId());
        User primary = requireTeacher(request.primaryProctorId());
        User backup = requireTeacher(request.backupProctorId());
        if (primary.getId().equals(backup.getId())) {
            throw ApiException.badRequest("Giám thị chính và dự phòng phải là hai giáo viên khác nhau");
        }
        int studentCount = roomStudents.findByRoomAssignmentId(assignmentId).size();
        if (!room.isActive() || room.getCapacity() == null || room.getCapacity() < studentCount) {
            throw ApiException.conflict("Phòng mới không hoạt động hoặc không đủ sức chứa cho học sinh đã xếp");
        }
        if (occupiedRoomIds(publishedUsage(period.getId()),
                new ExamSlot(session.getExamDate(), session.getStartTime()),
                session.getDurationMinutes()).contains(room.getId())) {
            throw ApiException.conflict("Phòng đã được dùng trong một đợt thi khác được phát hành cùng ca");
        }
        assertTeacherEligible(period, session, primary.getId(), assignmentId);
        assertTeacherEligible(period, session, backup.getId(), assignmentId);
        assignment.setRoomId(room.getId());
        assignment.setCapacitySnapshot(room.getCapacity());
        assignment.setPrimaryProctorId(primary.getId());
        assignment.setBackupProctorId(backup.getId());
        assignment.setUpdatedAt(Instant.now());
        roomAssignments.save(assignment);
        touchVersion(versionId);
        return detail(versionId).sessions().stream()
                .flatMap(row -> row.rooms().stream()).filter(row -> row.id().equals(assignmentId))
                .findFirst().orElseThrow();
    }

    @Transactional
    public TeacherUnavailabilityResponse addUnavailability(
            String periodId, TeacherUnavailabilityRequest request, String actorId) {
        requirePeriod(periodId);
        requireEditablePeriod(periodId);
        User teacher = requireTeacher(request.teacherId());
        if ((request.startTime() == null) != (request.endTime() == null)
                || (request.startTime() != null && !request.endTime().isAfter(request.startTime()))) {
            throw ApiException.badRequest("Khoảng thời gian bận không hợp lệ");
        }
        ExamPeriod period = requirePeriod(periodId);
        LocalDate awayEndDate = request.endDate() == null
                ? request.unavailableDate() : request.endDate();
        if (awayEndDate.isBefore(request.unavailableDate())) {
            throw ApiException.badRequest("Ngày kết thúc nghỉ/bận phải bằng hoặc sau ngày bắt đầu");
        }
        if (request.unavailableDate().isBefore(period.getStartDate())
                || awayEndDate.isAfter(period.getEndDate())) {
            throw ApiException.badRequest("Khoảng ngày giáo viên bận/nghỉ phải nằm trong đợt thi");
        }
        ExamTeacherUnavailability saved = unavailability.save(ExamTeacherUnavailability.builder()
                .id(Ids.gen("exam-away")).examPeriodId(periodId).teacherId(teacher.getId())
                .unavailableDate(request.unavailableDate()).endDate(awayEndDate)
                .startTime(request.startTime())
                .endTime(request.endTime())
                .unavailabilityType(normalizeUnavailabilityType(request.unavailabilityType()))
                .status("ACTIVE").reason(request.reason().trim())
                .createdBy(actorId).createdAt(Instant.now()).build());
        touchDraftVersions(periodId);
        return unavailableResponse(saved);
    }

    @Transactional
    public TeacherUnavailabilityResponse updateUnavailability(
            String periodId, String id, TeacherUnavailabilityRequest request) {
        requireEditablePeriod(periodId);
        ExamTeacherUnavailability row = unavailability.findById(id)
                .orElseThrow(() -> ApiException.notFound("Lịch bận của giáo viên"));
        if (!row.getExamPeriodId().equals(periodId)) {
            throw ApiException.notFound("Lịch bận của giáo viên");
        }
        requireTeacher(request.teacherId());
        LocalDate awayEndDate = request.endDate() == null
                ? request.unavailableDate() : request.endDate();
        ExamPeriod period = requirePeriod(periodId);
        if (awayEndDate.isBefore(request.unavailableDate())
                || request.unavailableDate().isBefore(period.getStartDate())
                || awayEndDate.isAfter(period.getEndDate())) {
            throw ApiException.badRequest("Khoảng ngày giáo viên bận/nghỉ không hợp lệ hoặc nằm ngoài đợt thi");
        }
        if ((request.startTime() == null) != (request.endTime() == null)
                || (request.startTime() != null && !request.endTime().isAfter(request.startTime()))) {
            throw ApiException.badRequest("Khoảng thời gian bận không hợp lệ");
        }
        row.setTeacherId(request.teacherId());
        row.setUnavailableDate(request.unavailableDate());
        row.setEndDate(awayEndDate);
        row.setStartTime(request.startTime());
        row.setEndTime(request.endTime());
        row.setUnavailabilityType(normalizeUnavailabilityType(request.unavailabilityType()));
        row.setReason(request.reason().trim());
        ExamTeacherUnavailability saved = unavailability.save(row);
        touchDraftVersions(periodId);
        return unavailableResponse(saved);
    }

    @Transactional
    public void deleteUnavailability(String periodId, String id) {
        requireEditablePeriod(periodId);
        ExamTeacherUnavailability row = unavailability.findById(id)
                .orElseThrow(() -> ApiException.notFound("Lịch bận của giáo viên"));
        if (!row.getExamPeriodId().equals(periodId)) throw ApiException.notFound("Lịch bận của giáo viên");
        unavailability.delete(row);
        touchDraftVersions(periodId);
    }

    public ExamValidationResponse validate(String versionId) {
        ExamScheduleVersion version = requireVersion(versionId);
        ExamPeriod period = requirePeriod(version.getExamPeriodId());
        List<ExamSession> sessionRows = sessions.findByVersionIdOrderByExamDateAscStartTimeAscGradeLevelAsc(versionId);
        List<String> sessionIds = sessionRows.stream().map(ExamSession::getId).toList();
        List<ExamRoomAssignment> roomRows = sessionIds.isEmpty() ? List.of() : roomAssignments.findBySessionIdIn(sessionIds);
        List<String> roomIds = roomRows.stream().map(ExamRoomAssignment::getId).toList();
        List<ExamRoomStudent> studentRows = roomIds.isEmpty() ? List.of() : roomStudents.findByRoomAssignmentIdIn(roomIds);
        Map<String, ExamSession> sessionMap = sessionRows.stream().collect(Collectors.toMap(ExamSession::getId, Function.identity()));
        Map<String, List<ExamRoomAssignment>> roomsBySession = roomRows.stream().collect(Collectors.groupingBy(ExamRoomAssignment::getSessionId));
        Map<String, List<ExamRoomStudent>> studentsByRoom = studentRows.stream().collect(Collectors.groupingBy(ExamRoomStudent::getRoomAssignmentId));
        Map<String, Subject> subjects = structure.listSubjects().stream().collect(Collectors.toMap(Subject::getId, Function.identity()));
        Map<String, Room> rooms = structure.listRooms().stream().collect(Collectors.toMap(Room::getId, Function.identity()));
        Map<String, User> userMap = users.findAll().stream().collect(Collectors.toMap(User::getId, Function.identity()));
        Map<String, Set<String>> subjectTeachers = subjectTeachers(period.getSemesterId());
        List<ExamTeacherUnavailability> away = unavailability.findByExamPeriodIdOrderByUnavailableDateAsc(period.getId());
        Map<String, List<TimetableSlot>> teacherTimetable = timetable.allSlots().stream()
                .filter(slot -> period.getSemesterId().equals(slot.getSemesterId()))
                .filter(slot -> slot.getTeacherId() != null)
                .collect(Collectors.groupingBy(TimetableSlot::getTeacherId));
        List<ExamValidationIssue> issues = new ArrayList<>();

        SourceReadiness sourceReadiness = assessmentSources.readiness(
                period.getAcademicYearId(), period.getSemesterId(),
                period.getExamType(), splitGrades(period.getScopeGrades()));
        for (String issue : sourceReadiness.issues()) {
            issues.add(error("G3_SOURCE_NOT_READY", issue, null, null));
        }
        Set<String> expectedSourceIds = sourceReadiness.sources().stream()
                .map(ExamAssessmentSource::assessmentPlanId)
                .collect(Collectors.toSet());
        Map<String, ExamAssessmentSource> currentSources = sourceReadiness.sources().stream()
                .collect(Collectors.toMap(ExamAssessmentSource::assessmentPlanId, Function.identity(),
                        (left, right) -> left, LinkedHashMap::new));
        Set<String> actualSourceIds = sessionRows.stream()
                .map(ExamSession::getSourceAssessmentPlanId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        for (ExamAssessmentSource source : sourceReadiness.sources()) {
            if (!actualSourceIds.contains(source.assessmentPlanId())) {
                issues.add(error("MISSING_G3_ASSESSMENT",
                        "Chưa xếp ca thi cho " + source.subjectName() + " · Khối "
                                + source.gradeLevel().substring(1), null, null));
            }
        }
        for (ExamSession session : sessionRows) {
            if (session.getSourceAssessmentPlanId() == null) {
                issues.add(error("LEGACY_SESSION_WITHOUT_SOURCE",
                        "Ca thi dữ liệu cũ chưa liên kết kế hoạch kiểm tra GĐ3",
                        session.getId(), null));
            } else if (!expectedSourceIds.contains(session.getSourceAssessmentPlanId())) {
                issues.add(error("STALE_G3_SOURCE",
                        "Nguồn GĐ3 của ca thi không còn thuộc kế hoạch đã công bố hiện tại",
                        session.getId(), null));
            } else {
                ExamAssessmentSource current = currentSources.get(session.getSourceAssessmentPlanId());
                if (!Objects.equals(session.getSourcePlanVersion(), current.planVersion())
                        || (session.getSourceUpdatedAt() != null && current.sourceUpdatedAt() != null
                        && current.sourceUpdatedAt().isAfter(session.getSourceUpdatedAt()))) {
                    issues.add(error("G3_SOURCE_UPDATED",
                            current.subjectName() + " · Khối " + current.gradeLevel().substring(1)
                                    + " đã thay đổi trong GĐ3 sau lần đồng bộ. Hãy tạo lại bản nháp từ nguồn mới.",
                            session.getId(), null));
                }
                if (session.getDurationMinutes() != current.durationMinutes()) {
                    issues.add(error("SOURCE_DURATION_CHANGED",
                            current.subjectName() + " có thời lượng nguồn " + current.durationMinutes()
                                    + " phút nhưng ca hiện tại là " + session.getDurationMinutes() + " phút",
                            session.getId(), null));
                }
            }
        }

        ExamVersionDiff versionDiff = compareVersion(version);
        if (versionDiff.comparisonAvailable() && !versionDiff.hasChanges()) {
            issues.add(error("NO_VERSION_CHANGES",
                    "Bản chỉnh sửa chưa có thay đổi so với phiên bản gốc", null, null));
        }

        if (sessionRows.isEmpty()) issues.add(error("NO_SESSIONS", "Phiên bản chưa có môn thi nào", null, null));
        for (ExamSession session : sessionRows) {
            if (session.getExamDate().isBefore(period.getStartDate()) || session.getExamDate().isAfter(period.getEndDate())) {
                issues.add(error("OUTSIDE_PERIOD", "Ngày thi nằm ngoài đợt thi", session.getId(), null));
            }
            if (isSchoolHoliday(period, session.getExamDate())) {
                issues.add(error("SCHOOL_HOLIDAY", "Ngày thi trùng ngày nghỉ của trường", session.getId(), null));
            }
            if (session.getSourcePlannedStartDate() != null && session.getSourcePlannedEndDate() != null
                    && (session.getExamDate().isBefore(session.getSourcePlannedStartDate())
                    || session.getExamDate().isAfter(session.getSourcePlannedEndDate()))) {
                if (session.getScheduleDeviationReason() == null
                        || session.getScheduleDeviationReason().isBlank()) {
                    issues.add(error("ASSESSMENT_WEEK_MISMATCH",
                            "Ngày thi nằm ngoài tuần dự kiến từ GĐ3 và chưa có lý do điều chỉnh",
                            session.getId(), null));
                } else {
                    issues.add(warning("ASSESSMENT_WEEK_OVERRIDDEN",
                            "Ngày thi lệch tuần GĐ3. Lý do: " + session.getScheduleDeviationReason(),
                            session.getId(), null));
                }
            }
            List<ExamRoomAssignment> assignedRooms = roomsBySession.getOrDefault(session.getId(), List.of());
            if (assignedRooms.isEmpty()) issues.add(error("NO_ROOM", "Môn thi chưa được xếp phòng", session.getId(), null));
            Set<String> allocatedStudents = new HashSet<>();
            for (ExamRoomAssignment assignment : assignedRooms) {
                Room room = rooms.get(assignment.getRoomId());
                List<ExamRoomStudent> assignedStudents = studentsByRoom.getOrDefault(assignment.getId(), List.of());
                if (room == null || !room.isActive()) issues.add(error("INVALID_ROOM", "Phòng thi không hoạt động", session.getId(), assignment.getId()));
                if (room != null && assignedStudents.size() > room.getCapacity()) {
                    issues.add(error("ROOM_CAPACITY", "Số học sinh vượt sức chứa phòng " + room.getCode(), session.getId(), assignment.getId()));
                }
                if (assignment.getPrimaryProctorId() == null || assignment.getBackupProctorId() == null) {
                    issues.add(error("MISSING_PROCTOR", "Phòng thi thiếu giám thị chính hoặc dự phòng", session.getId(), assignment.getId()));
                }
                if (assignment.getPrimaryProctorId() != null && assignment.getPrimaryProctorId().equals(assignment.getBackupProctorId())) {
                    issues.add(error("SAME_PROCTOR", "Giám thị chính và dự phòng bị trùng", session.getId(), assignment.getId()));
                }
                for (String teacherId : new String[]{assignment.getPrimaryProctorId(), assignment.getBackupProctorId()}) {
                    if (teacherId == null) continue;
                    User teacher = userMap.get(teacherId);
                    if (teacher == null || !"ACTIVE".equals(teacher.getStatus())) {
                        issues.add(error("INACTIVE_PROCTOR", "Giám thị không còn hoạt động", session.getId(), assignment.getId()));
                    }
                    if (!period.isAllowSubjectTeacherProctor()
                            && subjectTeachers.getOrDefault(session.getSubjectId(), Set.of()).contains(teacherId)) {
                        issues.add(error("OWN_SUBJECT", "Giáo viên được xếp coi môn mình đang dạy", session.getId(), assignment.getId()));
                    }
                    if (isUnavailable(away, teacherId, session)) {
                        issues.add(error("PROCTOR_UNAVAILABLE", "Giám thị có lịch bận hoặc nghỉ trong ca thi", session.getId(), assignment.getId()));
                    }
                    if (isTeachingBusy(teacherTimetable.getOrDefault(teacherId, List.of()), session,
                            session.getStartTime().plusMinutes(session.getDurationMinutes()))) {
                        issues.add(warning("PROCTOR_TEACHING",
                                "Giám thị có lịch dạy tuần trùng ca; lịch thi sẽ thay thế lịch học trong ngày thi",
                                session.getId(), assignment.getId()));
                    }
                }
                for (ExamRoomStudent student : assignedStudents) {
                    if (!allocatedStudents.add(student.getStudentId())) {
                        issues.add(error("DUPLICATE_STUDENT", "Học sinh bị xếp hai phòng trong cùng môn thi", session.getId(), assignment.getId()));
                    }
                }
            }
            int expected = activeStudents(period.getAcademicYearId(), session.getGradeLevel()).size();
            if (allocatedStudents.size() != expected) {
                issues.add(error("INCOMPLETE_STUDENTS", "Đã xếp " + allocatedStudents.size() + "/" + expected
                        + " học sinh khối " + session.getGradeLevel().substring(1), session.getId(), null));
            }
            if (!subjects.containsKey(session.getSubjectId())) {
                issues.add(error("INVALID_SUBJECT", "Môn thi không tồn tại", session.getId(), null));
            }
        }
        checkOverlaps(sessionRows, roomRows, studentRows, issues);
        checkPublishedOverlaps(period, sessionRows, roomRows, studentRows, issues);
        long errors = issues.stream().filter(issue -> "ERROR".equals(issue.severity())).count();
        long warnings = issues.stream().filter(issue -> "WARNING".equals(issue.severity())).count();
        return new ExamValidationResponse(errors == 0, sessionRows.size(), roomRows.size(), studentRows.size(),
                (int) errors, (int) warnings, List.copyOf(issues));
    }

    @Transactional
    public ExamValidationResponse validateAndMark(String versionId) {
        ExamScheduleVersion version = requireDraftVersion(versionId);
        ExamValidationResponse result = validate(versionId);
        version.setLastValidatedAt(Instant.now());
        version.setLastValidationErrorCount(result.errorCount());
        version.setLastValidationWarningCount(result.warningCount());
        versions.save(version);
        return result;
    }

    @Transactional
    public ExamVersionDetail publish(String versionId, String actorId) {
        ExamScheduleVersion version = requireDraftVersion(versionId);
        ExamPeriod period = requirePeriod(version.getExamPeriodId());
        if (!isValidationCurrent(version)) {
            throw ApiException.conflict(
                    "Lịch đã thay đổi sau lần kiểm tra gần nhất. Vui lòng bấm Kiểm tra lại trước khi phát hành.");
        }
        ExamValidationResponse validation = validate(versionId);
        if (!validation.valid()) {
            throw ApiException.conflict("Chưa thể phát hành: còn " + validation.errorCount() + " lỗi bắt buộc");
        }
        Instant now = Instant.now();
        versions.findByExamPeriodIdOrderByVersionNoDesc(period.getId()).stream()
                .filter(other -> !other.getId().equals(versionId) && "PUBLISHED".equals(other.getStatus()))
                .forEach(other -> {
                    other.setStatus("ARCHIVED");
                    versions.save(other);
                });
        version.setStatus("PUBLISHED");
        version.setPublishedBy(actorId);
        version.setPublishedAt(now);
        versions.save(version);
        period.setPublishedVersionId(versionId);
        period.setStatus("PUBLISHED");
        period.setUpdatedAt(now);
        periods.save(period);
        publishNotifications(period, version, actorId);
        return detail(versionId);
    }

    @Transactional
    public ExamVersionDetail recallPublished(String periodId, String reason, String actorId) {
        ExamPeriod period = requirePeriod(periodId);
        if (!isRecallablePeriodStatus(period.getStatus()) || period.getPublishedVersionId() == null) {
            throw ApiException.conflict("Đợt thi chưa có lịch đang phát hành để thu hồi");
        }
        ExamScheduleVersion published = requireVersion(period.getPublishedVersionId());
        if (!"PUBLISHED".equals(published.getStatus())) {
            throw ApiException.conflict("Phiên bản đang phát hành không hợp lệ");
        }

        Instant now = Instant.now();
        published.setStatus("RECALLED");
        versions.save(published);
        ExamScheduleVersion draft = versions.findByExamPeriodIdAndStatus(periodId, "DRAFT").orElse(null);
        if (draft == null) {
            int nextVersion = versions.findByExamPeriodIdOrderByVersionNoDesc(periodId).stream()
                    .mapToInt(ExamScheduleVersion::getVersionNo).max().orElse(0) + 1;
            draft = versions.save(ExamScheduleVersion.builder()
                    .id(Ids.gen("exam-ver"))
                    .examPeriodId(periodId)
                    .versionNo(nextVersion)
                    .status("DRAFT")
                    .basedOnVersionId(published.getId())
                    .changeReason("Thu hồi để chỉnh sửa: " + reason.trim())
                    .createdBy(actorId)
                    .createdAt(now)
                    .contentUpdatedAt(now)
                    .build());
            cloneVersionContent(published.getId(), draft.getId(), now);
        } else {
            String currentReason = draft.getChangeReason() == null ? "" : draft.getChangeReason().trim();
            draft.setChangeReason((currentReason.isEmpty() ? "" : currentReason + " · ")
                    + "Thu hồi lịch: " + reason.trim());
            versions.save(draft);
        }

        period.setPublishedVersionId(null);
        period.setStatus("DRAFT");
        period.setUpdatedAt(now);
        periods.save(period);
        events.publish("academic.exam_schedule.recalled", actorId, "exam_period", periodId,
                Map.of("periodId", periodId, "periodName", period.getName(), "reason", reason.trim()));
        return detail(draft.getId());
    }

    static boolean isRecallablePeriodStatus(String status) {
        return Set.of("PUBLISHED", "CLOSED").contains(status);
    }

    public List<PublishedExamView> studentExams(String studentId) {
        User student = users.findById(studentId).orElseThrow(() -> ApiException.notFound("Học sinh"));
        return roomStudents.findByStudentId(studentId).stream()
                .map(allocation -> publishedStudentView(student, allocation))
                .filter(java.util.Objects::nonNull)
                .sorted(viewComparator())
                .toList();
    }

    public List<PublishedExamView> teacherExams(String teacherId) {
        List<PublishedExamView> result = new ArrayList<>();
        for (ExamPeriod period : periods.findAll()) {
            if (period.getPublishedVersionId() == null || !"PUBLISHED".equals(period.getStatus())) continue;
            ExamScheduleVersion version = requireVersion(period.getPublishedVersionId());
            Map<String, ExamSession> sessionMap = sessions.findByVersionIdOrderByExamDateAscStartTimeAscGradeLevelAsc(version.getId())
                    .stream().collect(Collectors.toMap(ExamSession::getId, Function.identity()));
            for (ExamRoomAssignment room : roomAssignments.findBySessionIdIn(new ArrayList<>(sessionMap.keySet()))) {
                String role = teacherId.equals(room.getPrimaryProctorId()) ? "PRIMARY"
                        : teacherId.equals(room.getBackupProctorId()) ? "BACKUP" : null;
                if (role == null) continue;
                ExamSession session = sessionMap.get(room.getSessionId());
                result.add(publishedView(period, session, room, role, null));
            }
        }
        return result.stream().sorted(viewComparator()).toList();
    }

    private void allocateSession(
            ExamSession session, ExamPeriod period, Subject subject, List<User> gradeStudents,
            List<Room> activeRooms, List<User> teachers, Set<String> usedRooms,
            Set<String> usedTeachers, Map<String, Set<String>> subjectTeachers,
            Map<String, List<TimetableSlot>> teacherTimetable,
            Map<String, List<ExamTeacherUnavailability>> unavailableByTeacher,
            Map<String, Integer> roomUsage, Map<String, Integer> teacherUsage,
            LocalTime end, Instant now) {
        int offset = 0;
        List<Room> balancedRooms = activeRooms.stream()
                .sorted(Comparator.comparingInt((Room room) -> roomUsage.getOrDefault(room.getId(), 0))
                        .thenComparing(Room::getCapacity, Comparator.reverseOrder())
                        .thenComparing(Room::getCode))
                .toList();
        for (Room room : balancedRooms) {
            if (offset >= gradeStudents.size()) break;
            if (usedRooms.contains(room.getId())) continue;
            int take = Math.min(room.getCapacity(), gradeStudents.size() - offset);
            List<User> slice = gradeStudents.subList(offset, offset + take);
            User primary = nextTeacher(teachers, usedTeachers, period, session, subjectTeachers,
                    teacherTimetable, unavailableByTeacher, teacherUsage, end);
            usedTeachers.add(primary.getId());
            User backup = nextTeacher(teachers, usedTeachers, period, session, subjectTeachers,
                    teacherTimetable, unavailableByTeacher, teacherUsage, end);
            usedTeachers.add(backup.getId());
            ExamRoomAssignment assignment = roomAssignments.save(ExamRoomAssignment.builder()
                    .id(Ids.gen("exam-room")).sessionId(session.getId()).roomId(room.getId())
                    .capacitySnapshot(room.getCapacity()).primaryProctorId(primary.getId())
                    .backupProctorId(backup.getId()).createdAt(now).updatedAt(now).build());
            List<ExamRoomStudent> allocations = new ArrayList<>();
            for (int i = 0; i < slice.size(); i++) {
                User student = slice.get(i);
                allocations.add(ExamRoomStudent.builder()
                        .id(Ids.gen("exam-seat")).sessionId(session.getId())
                        .roomAssignmentId(assignment.getId()).studentId(student.getId())
                        .studentCode(student.getStudentCode()).studentName(student.getFullName())
                        .classId(student.getClassId()).classCode(student.getClassName())
                        .seatNo(offset + i + 1).build());
            }
            roomStudents.saveAll(allocations);
            usedRooms.add(room.getId());
            roomUsage.merge(room.getId(), 1, Integer::sum);
            teacherUsage.merge(primary.getId(), 1, Integer::sum);
            teacherUsage.merge(backup.getId(), 1, Integer::sum);
            offset += take;
        }
        if (offset < gradeStudents.size()) {
            throw ApiException.conflict("Không đủ phòng thi cho " + subject.getName() + " khối "
                    + session.getGradeLevel().substring(1) + " trong cùng ca");
        }
    }

    private void allocateManualSession(ExamSession session, ExamPeriod period) {
        List<Room> activeRooms = structure.listRooms().stream()
                .filter(Room::isActive)
                .filter(room -> room.getCapacity() != null && room.getCapacity() > 0)
                .filter(room -> !"GYM".equalsIgnoreCase(room.getRoomType()))
                .sorted(Comparator.comparing(Room::getCapacity).reversed().thenComparing(Room::getCode))
                .toList();
        if (activeRooms.isEmpty()) throw ApiException.conflict("Không có phòng thi đang hoạt động và có sức chứa");
        List<User> teachers = users.findByRoleAndStatusNot("TEACHER", "DELETED").stream()
                .filter(user -> "ACTIVE".equals(user.getStatus()) && user.getDeletedAt() == null)
                .sorted(Comparator.comparing(User::getTeacherCode, Comparator.nullsLast(String::compareTo))
                        .thenComparing(User::getFullName))
                .toList();
        if (teachers.size() < 2) throw ApiException.conflict("Cần ít nhất hai giáo viên hoạt động để xếp coi thi");

        List<ExamSession> overlapping = sessions
                .findByVersionIdOrderByExamDateAscStartTimeAscGradeLevelAsc(session.getVersionId()).stream()
                .filter(other -> !other.getId().equals(session.getId()) && overlaps(session, other))
                .toList();
        List<ExamRoomAssignment> occupiedAssignments = overlapping.isEmpty() ? List.of()
                : roomAssignments.findBySessionIdIn(overlapping.stream().map(ExamSession::getId).toList());
        Set<String> usedRooms = occupiedAssignments.stream()
                .map(ExamRoomAssignment::getRoomId).collect(Collectors.toSet());
        Set<String> usedTeachers = occupiedAssignments.stream()
                .flatMap(row -> java.util.stream.Stream.of(row.getPrimaryProctorId(), row.getBackupProctorId()))
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<String, Set<String>> subjectTeachers = subjectTeachers(period.getSemesterId());
        Map<String, List<TimetableSlot>> teacherTimetable = timetable.allSlots().stream()
                .filter(slot -> period.getSemesterId().equals(slot.getSemesterId()))
                .filter(slot -> slot.getTeacherId() != null)
                .collect(Collectors.groupingBy(TimetableSlot::getTeacherId));
        Map<String, List<ExamTeacherUnavailability>> unavailableByTeacher = unavailability
                .findByExamPeriodIdOrderByUnavailableDateAsc(period.getId()).stream()
                .collect(Collectors.groupingBy(ExamTeacherUnavailability::getTeacherId));
        PublishedUsage publishedUsage = publishedUsage(period.getId());
        ExamSlot manualSlot = new ExamSlot(session.getExamDate(), session.getStartTime());
        usedRooms.addAll(occupiedRoomIds(publishedUsage, manualSlot, session.getDurationMinutes()));
        usedTeachers.addAll(occupiedTeacherIds(publishedUsage, manualSlot, session.getDurationMinutes()));
        Subject subject = structure.getSubject(session.getSubjectId());
        List<User> students = activeStudents(period.getAcademicYearId(), session.getGradeLevel());
        if (students.isEmpty()) {
            throw ApiException.conflict("Khối " + session.getGradeLevel().substring(1) + " chưa có học sinh hoạt động");
        }
        Set<String> externallyBusyStudents = occupiedStudentIds(
                publishedUsage, manualSlot, session.getDurationMinutes());
        if (students.stream().map(User::getId).anyMatch(externallyBusyStudents::contains)) {
            throw ApiException.conflict("Học sinh của khối đã có lịch thi khác được phát hành trong ca này");
        }
        allocateSession(session, period, subject, students, activeRooms, teachers, usedRooms, usedTeachers,
                subjectTeachers, teacherTimetable, unavailableByTeacher,
                new HashMap<>(publishedUsage.roomCounts()), new HashMap<>(publishedUsage.teacherCounts()),
                session.getStartTime().plusMinutes(session.getDurationMinutes()), Instant.now());
    }

    private User nextTeacher(
            List<User> teachers, Set<String> usedTeachers, ExamPeriod period, ExamSession session,
            Map<String, Set<String>> subjectTeachers, Map<String, List<TimetableSlot>> teacherTimetable,
            Map<String, List<ExamTeacherUnavailability>> unavailableByTeacher,
            Map<String, Integer> teacherUsage, LocalTime end) {
        return teachers.stream()
                .filter(teacher -> !usedTeachers.contains(teacher.getId()))
                .filter(teacher -> period.isAllowSubjectTeacherProctor()
                        || !subjectTeachers.getOrDefault(session.getSubjectId(), Set.of()).contains(teacher.getId()))
                .filter(teacher -> !isUnavailable(unavailableByTeacher.getOrDefault(teacher.getId(), List.of()), session))
                .sorted(Comparator.comparingInt((User teacher) -> teacherUsage.getOrDefault(teacher.getId(), 0))
                        .thenComparing(User::getTeacherCode, Comparator.nullsLast(String::compareTo))
                        .thenComparing(User::getFullName))
                .findFirst()
                .orElseThrow(() -> ApiException.conflict("Không đủ giáo viên hợp lệ cho ca "
                        + session.getExamDate() + " " + session.getStartTime()
                        + ". Hãy bổ sung giáo viên, khai báo lại lịch bận hoặc cho phép coi môn đang dạy."));
    }

    private void checkOverlaps(
            List<ExamSession> sessionRows, List<ExamRoomAssignment> roomRows,
            List<ExamRoomStudent> studentRows, List<ExamValidationIssue> issues) {
        Map<String, ExamSession> sessionsById = sessionRows.stream().collect(Collectors.toMap(ExamSession::getId, Function.identity()));
        for (int i = 0; i < roomRows.size(); i++) {
            ExamRoomAssignment left = roomRows.get(i);
            ExamSession leftSession = sessionsById.get(left.getSessionId());
            for (int j = i + 1; j < roomRows.size(); j++) {
                ExamRoomAssignment right = roomRows.get(j);
                ExamSession rightSession = sessionsById.get(right.getSessionId());
                if (!overlaps(leftSession, rightSession)) continue;
                if (left.getRoomId().equals(right.getRoomId())) {
                    issues.add(error("ROOM_OVERLAP", "Phòng thi bị trùng ca", rightSession.getId(), right.getId()));
                }
                Set<String> leftTeachers = new HashSet<>();
                if (left.getPrimaryProctorId() != null) leftTeachers.add(left.getPrimaryProctorId());
                if (left.getBackupProctorId() != null) leftTeachers.add(left.getBackupProctorId());
                if (leftTeachers.contains(right.getPrimaryProctorId()) || leftTeachers.contains(right.getBackupProctorId())) {
                    issues.add(error("PROCTOR_OVERLAP", "Giáo viên bị xếp coi hai phòng cùng ca", rightSession.getId(), right.getId()));
                }
            }
        }
        Map<String, List<ExamRoomStudent>> byStudent = studentRows.stream().collect(Collectors.groupingBy(ExamRoomStudent::getStudentId));
        Map<String, ExamRoomAssignment> roomsById = roomRows.stream().collect(Collectors.toMap(ExamRoomAssignment::getId, Function.identity()));
        byStudent.forEach((studentId, allocations) -> {
            for (int i = 0; i < allocations.size(); i++) for (int j = i + 1; j < allocations.size(); j++) {
                ExamSession left = sessionsById.get(roomsById.get(allocations.get(i).getRoomAssignmentId()).getSessionId());
                ExamSession right = sessionsById.get(roomsById.get(allocations.get(j).getRoomAssignmentId()).getSessionId());
                if (overlaps(left, right)) issues.add(error("STUDENT_OVERLAP", "Học sinh bị trùng lịch thi", right.getId(), allocations.get(j).getRoomAssignmentId()));
            }
        });
        for (int i = 0; i < sessionRows.size(); i++) for (int j = i + 1; j < sessionRows.size(); j++) {
            ExamSession left = sessionRows.get(i); ExamSession right = sessionRows.get(j);
            if (left.getGradeLevel().equals(right.getGradeLevel()) && overlaps(left, right)) {
                issues.add(error("GRADE_OVERLAP", "Khối " + left.getGradeLevel().substring(1) + " có hai môn thi trùng ca", right.getId(), null));
            }
        }
    }

    private List<ExamSessionResponse> sessionResponses(String versionId) {
        List<ExamSession> rows = sessions.findByVersionIdOrderByExamDateAscStartTimeAscGradeLevelAsc(versionId);
        List<String> sessionIds = rows.stream().map(ExamSession::getId).toList();
        List<ExamRoomAssignment> roomRows = sessionIds.isEmpty() ? List.of() : roomAssignments.findBySessionIdIn(sessionIds);
        List<String> roomIds = roomRows.stream().map(ExamRoomAssignment::getId).toList();
        List<ExamRoomStudent> studentRows = roomIds.isEmpty() ? List.of() : roomStudents.findByRoomAssignmentIdIn(roomIds);
        Map<String, List<ExamRoomAssignment>> roomsBySession = roomRows.stream().collect(Collectors.groupingBy(ExamRoomAssignment::getSessionId));
        Map<String, List<ExamRoomStudent>> studentsByRoom = studentRows.stream().collect(Collectors.groupingBy(ExamRoomStudent::getRoomAssignmentId));
        ExamScheduleVersion version = requireVersion(versionId);
        ExamPeriod period = requirePeriod(version.getExamPeriodId());
        Map<String, ExamAssessmentSource> currentSources = assessmentSources.readiness(
                        period.getAcademicYearId(), period.getSemesterId(), period.getExamType(),
                        splitGrades(period.getScopeGrades())).sources().stream()
                .collect(Collectors.toMap(ExamAssessmentSource::assessmentPlanId, Function.identity(),
                        (left, right) -> left, LinkedHashMap::new));
        return rows.stream().map(row -> sessionResponse(
                row, roomsBySession, studentsByRoom, currentSources)).toList();
    }

    private ExamSessionResponse sessionResponse(
            ExamSession session, Map<String, List<ExamRoomAssignment>> roomsBySession,
            Map<String, List<ExamRoomStudent>> studentsByRoom,
            Map<String, ExamAssessmentSource> currentSources) {
        Subject subject = structure.getSubject(session.getSubjectId());
        ExamAssessmentSource currentSource = session.getSourceAssessmentPlanId() == null
                ? null : currentSources.get(session.getSourceAssessmentPlanId());
        String sourceSyncStatus = session.getSourceAssessmentPlanId() == null ? "LEGACY"
                : currentSource == null ? "SOURCE_CHANGED"
                : !Objects.equals(session.getSourcePlanVersion(), currentSource.planVersion())
                || (session.getSourceUpdatedAt() != null && currentSource.sourceUpdatedAt() != null
                && currentSource.sourceUpdatedAt().isAfter(session.getSourceUpdatedAt()))
                ? "SOURCE_CHANGED" : "CURRENT";
        List<ExamRoomResponse> roomResponses = roomsBySession.getOrDefault(session.getId(), List.of()).stream()
                .sorted(Comparator.comparing(room -> structure.getRoom(room.getRoomId()).getCode()))
                .map(room -> roomResponse(room, studentsByRoom.getOrDefault(room.getId(), List.of())))
                .toList();
        return new ExamSessionResponse(session.getId(), session.getSourceAssessmentPlanId(),
                session.getSourceTrainingPlanId(), session.getSourcePlanVersion(),
                session.getSourcePlanName(), session.getSourcePlanStatus(),
                session.getSourceAssessmentName() == null ? "Dữ liệu lịch thi cũ" : session.getSourceAssessmentName(),
                session.getSourceAssessmentType() == null ? "LEGACY" : session.getSourceAssessmentType(),
                session.getSourceAssessmentWeek() == null ? 0 : session.getSourceAssessmentWeek(),
                session.getSourceAssessmentForm(), session.getSourcePlannedStartDate(),
                session.getSourcePlannedEndDate(), sourceSyncStatus,
                session.getSourceSyncedAt(), session.getScheduleDeviationReason(),
                subject.getId(), subject.getCode(), subject.getName(),
                session.getGradeLevel(), session.getExamDate(), session.getStartTime(),
                session.getStartTime().plusMinutes(session.getDurationMinutes()), session.getDurationMinutes(),
                session.getNotes(), roomResponses.stream().mapToInt(room -> room.students().size()).sum(), roomResponses);
    }

    private ExamRoomResponse roomResponse(ExamRoomAssignment assignment, List<ExamRoomStudent> allocations) {
        Room room = structure.getRoom(assignment.getRoomId());
        return new ExamRoomResponse(assignment.getId(), room.getId(), room.getCode(), room.getName(),
                assignment.getCapacitySnapshot(), assignment.getPrimaryProctorId(), userName(assignment.getPrimaryProctorId()),
                assignment.getBackupProctorId(), userName(assignment.getBackupProctorId()),
                allocations.stream().sorted(Comparator.comparingInt(ExamRoomStudent::getSeatNo))
                        .map(row -> new ExamStudentResponse(row.getStudentId(), row.getStudentCode(), row.getStudentName(),
                                row.getClassId(), row.getClassCode(), row.getSeatNo())).toList());
    }

    private List<TeacherUnavailabilityResponse> unavailableResponses(String periodId) {
        return unavailability.findByExamPeriodIdOrderByUnavailableDateAsc(periodId).stream()
                .map(this::unavailableResponse).toList();
    }

    private TeacherUnavailabilityResponse unavailableResponse(ExamTeacherUnavailability row) {
        return new TeacherUnavailabilityResponse(row.getId(), row.getTeacherId(), userName(row.getTeacherId()),
                row.getUnavailableDate(), row.getEndDate(), row.getStartTime(), row.getEndTime(),
                row.getUnavailabilityType(), row.getStatus(), row.getReason(),
                userName(row.getCreatedBy()), row.getCreatedAt(), affectedSessionCount(row));
    }

    private int affectedSessionCount(ExamTeacherUnavailability away) {
        Set<String> versionIds = versions.findByExamPeriodIdOrderByVersionNoDesc(away.getExamPeriodId()).stream()
                .filter(version -> Set.of("DRAFT", "PUBLISHED").contains(version.getStatus()))
                .map(ExamScheduleVersion::getId).collect(Collectors.toSet());
        if (versionIds.isEmpty()) return 0;
        List<ExamSession> affectedSessions = versionIds.stream()
                .flatMap(versionId -> sessions.findByVersionIdOrderByExamDateAscStartTimeAscGradeLevelAsc(versionId).stream())
                .filter(session -> isUnavailable(List.of(away), session)).toList();
        if (affectedSessions.isEmpty()) return 0;
        Set<String> affectedIds = affectedSessions.stream().map(ExamSession::getId).collect(Collectors.toSet());
        return (int) roomAssignments.findBySessionIdIn(List.copyOf(affectedIds)).stream()
                .filter(room -> away.getTeacherId().equals(room.getPrimaryProctorId())
                        || away.getTeacherId().equals(room.getBackupProctorId()))
                .map(ExamRoomAssignment::getSessionId).distinct().count();
    }

    private PublishedExamView publishedStudentView(User student, ExamRoomStudent allocation) {
        ExamRoomAssignment room = roomAssignments.findById(allocation.getRoomAssignmentId()).orElse(null);
        if (room == null) return null;
        ExamSession session = sessions.findById(room.getSessionId()).orElse(null);
        if (session == null) return null;
        ExamScheduleVersion version = versions.findById(session.getVersionId()).orElse(null);
        if (version == null || !"PUBLISHED".equals(version.getStatus())) return null;
        ExamPeriod period = periods.findById(version.getExamPeriodId()).orElse(null);
        if (period == null || !version.getId().equals(period.getPublishedVersionId())) return null;
        return publishedView(period, session, room, "STUDENT", allocation);
    }

    private PublishedExamView publishedView(
            ExamPeriod period, ExamSession session, ExamRoomAssignment room,
            String role, ExamRoomStudent student) {
        Subject subject = structure.getSubject(session.getSubjectId());
        Semester semester = structure.getSemester(period.getSemesterId());
        Room roomInfo = structure.getRoom(room.getRoomId());
        return new PublishedExamView(period.getId(), period.getName(), period.getExamType(), semester.getName(),
                subject.getId(), subject.getName(), session.getGradeLevel(), session.getExamDate(), session.getStartTime(),
                session.getStartTime().plusMinutes(session.getDurationMinutes()), session.getDurationMinutes(),
                roomInfo.getCode(), student == null ? 0 : student.getSeatNo(), userName(room.getPrimaryProctorId()),
                userName(room.getBackupProctorId()), role,
                student == null ? null : student.getStudentName(), student == null ? null : student.getStudentCode());
    }

    private void publishNotifications(ExamPeriod period, ExamScheduleVersion version, String actorId) {
        List<ExamSession> sessionRows = sessions.findByVersionIdOrderByExamDateAscStartTimeAscGradeLevelAsc(version.getId());
        List<String> sessionIds = sessionRows.stream().map(ExamSession::getId).toList();
        List<ExamRoomAssignment> roomRows = roomAssignments.findBySessionIdIn(sessionIds);
        List<String> roomIds = roomRows.stream().map(ExamRoomAssignment::getId).toList();
        List<String> studentIds = roomStudents.findByRoomAssignmentIdIn(roomIds).stream()
                .map(ExamRoomStudent::getStudentId).distinct().toList();
        List<String> teacherIds = roomRows.stream()
                .flatMap(row -> java.util.stream.Stream.of(row.getPrimaryProctorId(), row.getBackupProctorId()))
                .filter(java.util.Objects::nonNull).distinct().toList();
        events.publish("academic.exam_schedule.published", actorId, "exam_period", period.getId(),
                Map.of("periodId", period.getId(), "periodName", period.getName(),
                        "studentIds", studentIds, "teacherIds", teacherIds));
    }

    private ExamVersionDiff compareVersion(ExamScheduleVersion version) {
        if (version.getBasedOnVersionId() == null) {
            return new ExamVersionDiff(false, null, null, true, 0,
                    0, 0, 0, 0, 0, 0, List.of());
        }
        ExamScheduleVersion base = versions.findById(version.getBasedOnVersionId()).orElse(null);
        if (base == null) {
            return new ExamVersionDiff(false, version.getBasedOnVersionId(), null, true, 0,
                    0, 0, 0, 0, 0, 0, List.of());
        }

        Map<String, ExamSessionResponse> before = sessionResponses(base.getId()).stream()
                .collect(Collectors.toMap(this::sessionBusinessKey, Function.identity()));
        Map<String, ExamSessionResponse> after = sessionResponses(version.getId()).stream()
                .collect(Collectors.toMap(this::sessionBusinessKey, Function.identity()));
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(before.keySet());
        keys.addAll(after.keySet());

        int addedSessions = 0;
        int removedSessions = 0;
        int changedSessions = 0;
        int changedRooms = 0;
        int changedProctors = 0;
        int changedStudents = 0;
        List<ExamVersionChange> changes = new ArrayList<>();

        for (String key : keys) {
            ExamSessionResponse oldSession = before.get(key);
            ExamSessionResponse newSession = after.get(key);
            String label = sessionLabel(newSession != null ? newSession : oldSession);
            if (oldSession == null) {
                addedSessions++;
                changes.add(new ExamVersionChange("SESSION_ADDED", label, "Chưa có", sessionTiming(newSession)));
                continue;
            }
            if (newSession == null) {
                removedSessions++;
                changes.add(new ExamVersionChange("SESSION_REMOVED", label, sessionTiming(oldSession), "Đã xóa"));
                continue;
            }
            if (!sameSessionDetails(oldSession, newSession)) {
                changedSessions++;
                changes.add(new ExamVersionChange("SESSION_CHANGED", label,
                        sessionTiming(oldSession), sessionTiming(newSession)));
            }

            Map<String, ExamRoomResponse> oldRooms = oldSession.rooms().stream()
                    .collect(Collectors.toMap(ExamRoomResponse::roomId, Function.identity()));
            Map<String, ExamRoomResponse> newRooms = newSession.rooms().stream()
                    .collect(Collectors.toMap(ExamRoomResponse::roomId, Function.identity()));
            Set<String> roomKeys = new LinkedHashSet<>();
            roomKeys.addAll(oldRooms.keySet());
            roomKeys.addAll(newRooms.keySet());
            for (String roomKey : roomKeys) {
                ExamRoomResponse oldRoom = oldRooms.get(roomKey);
                ExamRoomResponse newRoom = newRooms.get(roomKey);
                if (oldRoom == null || newRoom == null
                        || oldRoom.capacity() != newRoom.capacity()) {
                    changedRooms++;
                    changes.add(new ExamVersionChange("ROOM_CHANGED", label,
                            roomSummary(oldRoom), roomSummary(newRoom)));
                    continue;
                }
                if (!Objects.equals(oldRoom.primaryProctorId(), newRoom.primaryProctorId())
                        || !Objects.equals(oldRoom.backupProctorId(), newRoom.backupProctorId())) {
                    changedProctors++;
                    changes.add(new ExamVersionChange("PROCTOR_CHANGED", label + " · " + newRoom.roomCode(),
                            proctorSummary(oldRoom), proctorSummary(newRoom)));
                }
            }

            Map<String, String> oldSeats = studentSeatMap(oldSession);
            Map<String, String> newSeats = studentSeatMap(newSession);
            Set<String> studentIds = new HashSet<>();
            studentIds.addAll(oldSeats.keySet());
            studentIds.addAll(newSeats.keySet());
            int sessionStudentChanges = (int) studentIds.stream()
                    .filter(studentId -> !Objects.equals(oldSeats.get(studentId), newSeats.get(studentId)))
                    .count();
            if (sessionStudentChanges > 0) {
                changedStudents += sessionStudentChanges;
                changes.add(new ExamVersionChange("STUDENTS_CHANGED", label,
                        oldSeats.size() + " học sinh", newSeats.size() + " học sinh; "
                        + sessionStudentChanges + " vị trí thay đổi"));
            }
        }

        return new ExamVersionDiff(true, base.getId(), base.getVersionNo(), !changes.isEmpty(), changes.size(),
                addedSessions, removedSessions, changedSessions, changedRooms,
                changedProctors, changedStudents, List.copyOf(changes));
    }

    private String sessionBusinessKey(ExamSessionResponse session) {
        return session.subjectId() + "|" + session.gradeLevel();
    }

    private String sessionLabel(ExamSessionResponse session) {
        return session.subjectName() + " · Khối " + session.gradeLevel().substring(1);
    }

    private boolean sameSessionDetails(ExamSessionResponse left, ExamSessionResponse right) {
        return Objects.equals(left.examDate(), right.examDate())
                && Objects.equals(left.startTime(), right.startTime())
                && left.durationMinutes() == right.durationMinutes()
                && Objects.equals(trimToNull(left.notes()), trimToNull(right.notes()));
    }

    private String sessionTiming(ExamSessionResponse session) {
        if (session == null) return "Chưa có";
        String value = session.examDate() + " · " + session.startTime() + "–" + session.endTime()
                + " · " + session.durationMinutes() + " phút";
        return session.notes() == null || session.notes().isBlank() ? value : value + " · " + session.notes().trim();
    }

    private String roomSummary(ExamRoomResponse room) {
        return room == null ? "Không có" : room.roomCode() + " · sức chứa " + room.capacity();
    }

    private String proctorSummary(ExamRoomResponse room) {
        return room.primaryProctorName() + " (chính), " + room.backupProctorName() + " (dự phòng)";
    }

    private Map<String, String> studentSeatMap(ExamSessionResponse session) {
        Map<String, String> result = new HashMap<>();
        for (ExamRoomResponse room : session.rooms()) {
            for (ExamStudentResponse student : room.students()) {
                result.put(student.studentId(), room.roomId() + "|" + student.seatNo());
            }
        }
        return result;
    }

    private void cloneVersionContent(String sourceVersionId, String targetVersionId, Instant now) {
        Map<String, String> sessionIdMap = new HashMap<>();
        Map<String, String> roomIdMap = new HashMap<>();
        List<ExamSession> sourceSessions = sessions.findByVersionIdOrderByExamDateAscStartTimeAscGradeLevelAsc(sourceVersionId);
        for (ExamSession source : sourceSessions) {
            ExamSession target = sessions.save(ExamSession.builder()
                    .id(Ids.gen("exam-session")).versionId(targetVersionId)
                    .sourceAssessmentPlanId(source.getSourceAssessmentPlanId())
                    .sourceTrainingPlanId(source.getSourceTrainingPlanId())
                    .sourcePlanVersion(source.getSourcePlanVersion())
                    .sourcePlanName(source.getSourcePlanName())
                    .sourcePlanStatus(source.getSourcePlanStatus())
                    .sourceAssessmentName(source.getSourceAssessmentName())
                    .sourceAssessmentType(source.getSourceAssessmentType())
                    .sourceAssessmentForm(source.getSourceAssessmentForm())
                    .sourceAssessmentWeek(source.getSourceAssessmentWeek())
                    .sourcePlannedStartDate(source.getSourcePlannedStartDate())
                    .sourcePlannedEndDate(source.getSourcePlannedEndDate())
                    .sourceSyncedAt(source.getSourceSyncedAt())
                    .sourceUpdatedAt(source.getSourceUpdatedAt())
                    .subjectId(source.getSubjectId())
                    .gradeLevel(source.getGradeLevel()).examDate(source.getExamDate()).startTime(source.getStartTime())
                    .durationMinutes(source.getDurationMinutes())
                    .scheduleDeviationReason(source.getScheduleDeviationReason())
                    .notes(source.getNotes()).createdAt(now).updatedAt(now).build());
            sessionIdMap.put(source.getId(), target.getId());
        }
        List<ExamRoomAssignment> sourceRooms = sourceSessions.isEmpty() ? List.of()
                : roomAssignments.findBySessionIdIn(sourceSessions.stream().map(ExamSession::getId).toList());
        for (ExamRoomAssignment source : sourceRooms) {
            ExamRoomAssignment target = roomAssignments.save(ExamRoomAssignment.builder()
                    .id(Ids.gen("exam-room")).sessionId(sessionIdMap.get(source.getSessionId()))
                    .roomId(source.getRoomId()).capacitySnapshot(source.getCapacitySnapshot())
                    .primaryProctorId(source.getPrimaryProctorId()).backupProctorId(source.getBackupProctorId())
                    .createdAt(now).updatedAt(now).build());
            roomIdMap.put(source.getId(), target.getId());
        }
        List<ExamRoomStudent> allocations = sourceRooms.isEmpty() ? List.of()
                : roomStudents.findByRoomAssignmentIdIn(sourceRooms.stream().map(ExamRoomAssignment::getId).toList());
        roomStudents.saveAll(allocations.stream().map(source -> ExamRoomStudent.builder()
                .id(Ids.gen("exam-seat")).sessionId(sessionIdMap.get(source.getSessionId()))
                .roomAssignmentId(roomIdMap.get(source.getRoomAssignmentId())).studentId(source.getStudentId())
                .studentCode(source.getStudentCode()).studentName(source.getStudentName()).classId(source.getClassId())
                .classCode(source.getClassCode()).seatNo(source.getSeatNo()).build()).toList());
    }

    private void clearVersion(String versionId) {
        List<String> ids = sessions.findByVersionIdOrderByExamDateAscStartTimeAscGradeLevelAsc(versionId)
                .stream().map(ExamSession::getId).toList();
        if (!ids.isEmpty()) {
            roomStudents.deleteBySessionIdIn(ids);
            roomAssignments.deleteBySessionIdIn(ids);
        }
        sessions.deleteByVersionId(versionId);
        roomStudents.flush();
        roomAssignments.flush();
        sessions.flush();
    }

    private void assertTeacherEligible(ExamPeriod period, ExamSession session, String teacherId, String ignoredAssignmentId) {
        if (!period.isAllowSubjectTeacherProctor()
                && subjectTeachers(period.getSemesterId()).getOrDefault(session.getSubjectId(), Set.of()).contains(teacherId)) {
            throw ApiException.conflict("Giáo viên không được coi môn mình đang dạy");
        }
        if (isUnavailable(unavailability.findByTeacherId(teacherId), session)) {
            throw ApiException.conflict("Giáo viên có lịch bận hoặc nghỉ trong ca thi");
        }
        if (occupiedTeacherIds(publishedUsage(period.getId()),
                new ExamSlot(session.getExamDate(), session.getStartTime()),
                session.getDurationMinutes()).contains(teacherId)) {
            throw ApiException.conflict("Giáo viên đã được xếp coi một đợt thi khác được phát hành trong ca này");
        }
        for (ExamRoomAssignment existing : roomAssignments.findAll()) {
            if (existing.getId().equals(ignoredAssignmentId)) continue;
            if (!teacherId.equals(existing.getPrimaryProctorId()) && !teacherId.equals(existing.getBackupProctorId())) continue;
            ExamSession other = sessions.findById(existing.getSessionId()).orElse(null);
            if (other != null && other.getVersionId().equals(session.getVersionId()) && overlaps(session, other)) {
                throw ApiException.conflict("Giáo viên đã được xếp coi một phòng khác cùng ca");
            }
        }
    }

    private List<User> activeStudents(String academicYearId, String grade) {
        Set<String> classIds = structure.listClasses(academicYearId, grade).stream().map(SchoolClass::getId).collect(Collectors.toSet());
        return classIds.stream().flatMap(classId -> users.findByClassId(classId).stream())
                .filter(user -> "STUDENT".equals(user.getRole()) && "ACTIVE".equals(user.getStatus()) && user.getDeletedAt() == null)
                .sorted(Comparator.comparing(User::getClassName, Comparator.nullsLast(String::compareTo))
                        .thenComparing(User::getStudentCode, Comparator.nullsLast(String::compareTo))
                        .thenComparing(User::getFullName))
                .toList();
    }

    private Map<String, Set<String>> subjectTeachers(String semesterId) {
        Map<String, Set<String>> result = new HashMap<>();
        for (TeacherClassSubject row : teachingAssignments.findBySemesterIdAndStatus(semesterId, "ACTIVE")) {
            result.computeIfAbsent(row.getSubjectId(), ignored -> new HashSet<>()).add(row.getTeacherId());
        }
        return result;
    }

    private boolean isTeachingBusy(List<TimetableSlot> slots, ExamSession session, LocalTime examEnd) {
        String day = dayCode(session.getExamDate().getDayOfWeek());
        return slots.stream().filter(slot -> day.equals(slot.getDayOfWeek()))
                .anyMatch(slot -> overlaps(session.getStartTime(), examEnd,
                        parseTime(slot.getStartTime()), parseTime(slot.getEndTime())));
    }

    private boolean isUnavailable(List<ExamTeacherUnavailability> rows, ExamSession session) {
        return rows.stream().filter(row -> {
                    LocalDate end = row.getEndDate() == null ? row.getUnavailableDate() : row.getEndDate();
                    return !session.getExamDate().isBefore(row.getUnavailableDate())
                            && !session.getExamDate().isAfter(end);
                })
                .anyMatch(row -> row.getStartTime() == null || overlaps(session.getStartTime(),
                        session.getStartTime().plusMinutes(session.getDurationMinutes()), row.getStartTime(), row.getEndTime()));
    }

    private boolean isUnavailable(List<ExamTeacherUnavailability> rows, String teacherId, ExamSession session) {
        return isUnavailable(rows.stream().filter(row -> row.getTeacherId().equals(teacherId)).toList(), session);
    }

    private boolean overlaps(ExamSession left, ExamSession right) {
        return left.getExamDate().equals(right.getExamDate()) && overlaps(left.getStartTime(),
                left.getStartTime().plusMinutes(left.getDurationMinutes()), right.getStartTime(),
                right.getStartTime().plusMinutes(right.getDurationMinutes()));
    }

    private boolean overlaps(LocalTime leftStart, LocalTime leftEnd, LocalTime rightStart, LocalTime rightEnd) {
        return leftStart.isBefore(rightEnd) && rightStart.isBefore(leftEnd);
    }

    private boolean canScheduleGroup(
            ExamSlot slot, List<ExamAssessmentSource> sources, ExamPeriod period,
            List<Room> activeRooms, List<User> teachers,
            Map<String, List<User>> studentsByGrade,
            Map<String, Set<String>> subjectTeachers,
            Map<String, List<ExamTeacherUnavailability>> unavailableByTeacher,
            PublishedUsage publishedUsage) {
        int maxDuration = sources.stream().mapToInt(ExamAssessmentSource::durationMinutes).max().orElse(90);
        Set<String> occupiedRooms = occupiedRoomIds(publishedUsage, slot, maxDuration);
        Set<String> occupiedTeachers = occupiedTeacherIds(publishedUsage, slot, maxDuration);
        Set<String> occupiedStudents = occupiedStudentIds(publishedUsage, slot, maxDuration);
        for (ExamAssessmentSource source : sources) {
            if (studentsByGrade.getOrDefault(source.gradeLevel(), List.of()).stream()
                    .map(User::getId).anyMatch(occupiedStudents::contains)) return false;
        }

        List<Room> availableRooms = activeRooms.stream()
                .filter(room -> !occupiedRooms.contains(room.getId()))
                .sorted(Comparator.comparing(Room::getCapacity).reversed().thenComparing(Room::getCode))
                .toList();
        int roomCursor = 0;
        int requiredRooms = 0;
        for (ExamAssessmentSource source : sources) {
            int remaining = studentsByGrade.getOrDefault(source.gradeLevel(), List.of()).size();
            if (remaining == 0) return false;
            while (remaining > 0 && roomCursor < availableRooms.size()) {
                remaining -= availableRooms.get(roomCursor++).getCapacity();
                requiredRooms++;
            }
            if (remaining > 0) return false;
        }

        ExamAssessmentSource first = sources.get(0);
        ExamSession probe = ExamSession.builder()
                .subjectId(first.subjectId()).examDate(slot.date()).startTime(slot.startTime())
                .durationMinutes(maxDuration).build();
        long eligibleTeachers = teachers.stream()
                .filter(teacher -> !occupiedTeachers.contains(teacher.getId()))
                .filter(teacher -> period.isAllowSubjectTeacherProctor()
                        || !subjectTeachers.getOrDefault(first.subjectId(), Set.of()).contains(teacher.getId()))
                .filter(teacher -> !isUnavailable(
                        unavailableByTeacher.getOrDefault(teacher.getId(), List.of()), probe))
                .count();
        return eligibleTeachers >= requiredRooms * 2L;
    }

    private PublishedUsage publishedUsage(String excludedPeriodId) {
        List<PublishedAssignment> entries = new ArrayList<>();
        Map<String, Integer> roomCounts = new HashMap<>();
        Map<String, Integer> teacherCounts = new HashMap<>();
        for (ExamPeriod period : periods.findAll()) {
            if (period.getId().equals(excludedPeriodId)
                    || period.getPublishedVersionId() == null
                    || "CANCELLED".equals(period.getStatus())) continue;
            List<ExamSession> publishedSessions = sessions
                    .findByVersionIdOrderByExamDateAscStartTimeAscGradeLevelAsc(period.getPublishedVersionId());
            if (publishedSessions.isEmpty()) continue;
            Map<String, ExamSession> byId = publishedSessions.stream()
                    .collect(Collectors.toMap(ExamSession::getId, Function.identity()));
            List<ExamRoomAssignment> assignments = roomAssignments
                    .findBySessionIdIn(new ArrayList<>(byId.keySet()));
            Map<String, List<ExamRoomStudent>> studentsByRoom = assignments.isEmpty() ? Map.of()
                    : roomStudents.findByRoomAssignmentIdIn(assignments.stream().map(ExamRoomAssignment::getId).toList())
                    .stream().collect(Collectors.groupingBy(ExamRoomStudent::getRoomAssignmentId));
            for (ExamRoomAssignment assignment : assignments) {
                entries.add(new PublishedAssignment(period.getName(), byId.get(assignment.getSessionId()),
                        assignment, studentsByRoom.getOrDefault(assignment.getId(), List.of())));
                roomCounts.merge(assignment.getRoomId(), 1, Integer::sum);
                if (assignment.getPrimaryProctorId() != null) {
                    teacherCounts.merge(assignment.getPrimaryProctorId(), 1, Integer::sum);
                }
                if (assignment.getBackupProctorId() != null) {
                    teacherCounts.merge(assignment.getBackupProctorId(), 1, Integer::sum);
                }
            }
        }
        return new PublishedUsage(List.copyOf(entries), Map.copyOf(roomCounts), Map.copyOf(teacherCounts));
    }

    private Set<String> occupiedRoomIds(PublishedUsage usage, ExamSlot slot, int duration) {
        return usage.assignments().stream()
                .filter(row -> overlaps(slot, duration, row.session()))
                .map(row -> row.assignment().getRoomId()).collect(Collectors.toSet());
    }

    private Set<String> occupiedTeacherIds(PublishedUsage usage, ExamSlot slot, int duration) {
        return usage.assignments().stream()
                .filter(row -> overlaps(slot, duration, row.session()))
                .flatMap(row -> java.util.stream.Stream.of(
                        row.assignment().getPrimaryProctorId(), row.assignment().getBackupProctorId()))
                .filter(Objects::nonNull).collect(Collectors.toSet());
    }

    private Set<String> occupiedStudentIds(PublishedUsage usage, ExamSlot slot, int duration) {
        return usage.assignments().stream()
                .filter(row -> overlaps(slot, duration, row.session()))
                .flatMap(row -> row.students().stream())
                .map(ExamRoomStudent::getStudentId).collect(Collectors.toSet());
    }

    private boolean overlaps(ExamSlot slot, int duration, ExamSession other) {
        return slot.date().equals(other.getExamDate()) && overlaps(
                slot.startTime(), slot.startTime().plusMinutes(duration),
                other.getStartTime(), other.getStartTime().plusMinutes(other.getDurationMinutes()));
    }

    private void checkPublishedOverlaps(
            ExamPeriod period, List<ExamSession> currentSessions,
            List<ExamRoomAssignment> currentRooms,
            List<ExamRoomStudent> currentStudents,
            List<ExamValidationIssue> issues) {
        PublishedUsage usage = publishedUsage(period.getId());
        if (usage.assignments().isEmpty()) return;
        Map<String, ExamSession> currentById = currentSessions.stream()
                .collect(Collectors.toMap(ExamSession::getId, Function.identity()));
        Map<String, List<ExamRoomStudent>> studentsByRoom = currentStudents.stream()
                .collect(Collectors.groupingBy(ExamRoomStudent::getRoomAssignmentId));
        Set<String> reported = new HashSet<>();
        for (ExamRoomAssignment room : currentRooms) {
            ExamSession session = currentById.get(room.getSessionId());
            if (session == null) continue;
            Set<String> currentStudentIds = studentsByRoom.getOrDefault(room.getId(), List.of()).stream()
                    .map(ExamRoomStudent::getStudentId).collect(Collectors.toSet());
            for (PublishedAssignment external : usage.assignments()) {
                if (!overlaps(session, external.session())) continue;
                if (room.getRoomId().equals(external.assignment().getRoomId())
                        && reported.add("ROOM|" + room.getId())) {
                    issues.add(error("PUBLISHED_ROOM_OVERLAP",
                            "Phòng thi trùng với đợt đã phát hành: " + external.periodName(),
                            session.getId(), room.getId()));
                }
                Set<String> teachers = new HashSet<>();
                if (room.getPrimaryProctorId() != null) teachers.add(room.getPrimaryProctorId());
                if (room.getBackupProctorId() != null) teachers.add(room.getBackupProctorId());
                if ((teachers.contains(external.assignment().getPrimaryProctorId())
                        || teachers.contains(external.assignment().getBackupProctorId()))
                        && reported.add("TEACHER|" + room.getId())) {
                    issues.add(error("PUBLISHED_PROCTOR_OVERLAP",
                            "Giám thị trùng ca với đợt đã phát hành: " + external.periodName(),
                            session.getId(), room.getId()));
                }
                if (external.students().stream().map(ExamRoomStudent::getStudentId)
                        .anyMatch(currentStudentIds::contains)
                        && reported.add("STUDENT|" + session.getId())) {
                    issues.add(error("PUBLISHED_STUDENT_OVERLAP",
                            "Học sinh trùng ca với đợt đã phát hành: " + external.periodName(),
                            session.getId(), room.getId()));
                }
            }
        }
    }

    private int subjectPriority(Subject subject) {
        if (subject == null) return 99;
        String value = Normalizer.normalize(subject.getName(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toUpperCase(Locale.ROOT);
        if (value.contains("NGU VAN")) return 1;
        if (value.equals("TOAN") || value.contains("TOAN HOC")) return 2;
        if (value.contains("TIENG ANH") || value.contains("NGOAI NGU")) return 3;
        if (value.contains("VAT LY")) return 4;
        if (value.contains("HOA HOC")) return 5;
        if (value.contains("SINH HOC")) return 6;
        if (value.contains("LICH SU")) return 7;
        if (value.contains("DIA LY")) return 8;
        if (value.contains("KINH TE") || value.contains("PHAP LUAT")) return 9;
        if (value.contains("TIN HOC")) return 10;
        return 50;
    }

    private record ExamSlot(LocalDate date, LocalTime startTime) {}
    private record PublishedAssignment(
            String periodName, ExamSession session,
            ExamRoomAssignment assignment, List<ExamRoomStudent> students) {}
    private record PublishedUsage(
            List<PublishedAssignment> assignments,
            Map<String, Integer> roomCounts,
            Map<String, Integer> teacherCounts) {}

    private void validateGenerationSlots(ExamPeriod period, List<LocalDate> dates, List<LocalTime> times, int subjectCount) {
        if (dates.isEmpty() || times.isEmpty()) throw ApiException.badRequest("Cần ít nhất một ngày và một giờ bắt đầu");
        if (dates.size() * times.size() < subjectCount) {
            throw ApiException.badRequest("Không đủ ca thi: cần " + subjectCount + " ca cho " + subjectCount + " môn");
        }
        for (LocalDate date : dates) {
            if (date.isBefore(period.getStartDate()) || date.isAfter(period.getEndDate())) {
                throw ApiException.badRequest("Ngày " + date + " nằm ngoài đợt thi");
            }
            if (isSchoolHoliday(period, date)) throw ApiException.conflict("Ngày " + date + " là ngày nghỉ của trường");
        }
    }

    private void validatePeriodRequest(ExamPeriodRequest request, Semester semester) {
        String type = normalizeType(request.examType());
        List<String> grades = normalizeGrades(request.gradeLevels());
        if (request.endDate().isBefore(request.startDate())) throw ApiException.badRequest("Ngày kết thúc phải sau ngày bắt đầu");
        if (request.startDate().isBefore(semester.getStartDate()) || request.endDate().isAfter(semester.getEndDate())) {
            throw ApiException.badRequest("Thời gian đợt thi phải nằm trong học kỳ");
        }
        List<SchoolHoliday> holidays = structure.listHolidays(semester.getAcademicYearId());
        long schedulableDays = request.startDate().datesUntil(request.endDate().plusDays(1))
                .filter(date -> date.getDayOfWeek() != DayOfWeek.SUNDAY)
                .filter(date -> holidays.stream().noneMatch(holiday -> {
                    LocalDate end = holiday.getEndDate() == null ? holiday.getDate() : holiday.getEndDate();
                    return !date.isBefore(holiday.getDate()) && !date.isAfter(end);
                }))
                .count();
        if (schedulableDays == 0) {
            throw ApiException.badRequest("Đợt thi phải có ít nhất một ngày từ Thứ 2 đến Thứ 7 và không trùng ngày nghỉ");
        }
        SourceReadiness sourceReadiness = assessmentSources.readiness(
                request.academicYearId(), semester.getId(), type, grades);
        if (!sourceReadiness.ready()) {
            throw ApiException.badRequest("Đợt thi chưa đủ đầu vào từ GĐ3: "
                    + String.join("; ", sourceReadiness.issues()));
        }
        if (schedulableDays < sourceReadiness.requiredDays()) {
            throw ApiException.badRequest("Khoảng thời gian chỉ có " + schedulableDays
                    + " ngày thi hợp lệ, nhưng " + sourceReadiness.subjectCount()
                    + " môn từ GĐ3 cần tối thiểu " + sourceReadiness.requiredDays()
                    + " ngày (tối đa hai ca mỗi ngày). Thời gian gợi ý: "
                    + sourceReadiness.suggestedStartDate() + " đến "
                    + sourceReadiness.suggestedEndDate());
        }
        List<ExamAssessmentSource> outsideSources = sourceReadiness.sources().stream()
                .filter(source -> request.endDate().isBefore(source.plannedStartDate())
                        || request.startDate().isAfter(source.plannedEndDate()))
                .toList();
        if (!outsideSources.isEmpty()) {
            String examples = outsideSources.stream().limit(3)
                    .map(source -> source.subjectName() + " khối " + source.gradeLevel().substring(1)
                            + " (tuần " + source.weekNumber() + ": " + source.plannedStartDate()
                            + " đến " + source.plannedEndDate() + ")")
                    .collect(Collectors.joining("; "));
            throw ApiException.badRequest("Khoảng thời gian đợt thi không giao với tuần kiểm tra GĐ3 của: "
                    + examples + ". Thời gian gợi ý: " + sourceReadiness.suggestedStartDate()
                    + " đến " + sourceReadiness.suggestedEndDate());
        }
        boolean hasExamRoom = structure.listRooms().stream()
                .anyMatch(room -> room.isActive() && room.getCapacity() != null && room.getCapacity() > 0
                        && !"GYM".equalsIgnoreCase(room.getRoomType()));
        if (!hasExamRoom) {
            throw ApiException.badRequest("Chưa có phòng thi đang hoạt động và có sức chứa");
        }
        if (users.countByRoleAndStatusAndDeletedAtIsNull("TEACHER", "ACTIVE") < 2) {
            throw ApiException.badRequest("Cần ít nhất hai giáo viên đang hoạt động để xếp giám thị");
        }
    }

    private ExamAssessmentSource requireRequestSource(
            ExamPeriod period, SessionRequest request) {
        return assessmentSources.requireSource(
                request.sourceAssessmentPlanId(), period.getAcademicYearId(),
                period.getSemesterId(), period.getExamType(),
                splitGrades(period.getScopeGrades()));
    }

    private void validateSessionRequest(
            ExamPeriod period, LocalDate examDate, LocalTime startTime) {
        if (examDate.isBefore(period.getStartDate()) || examDate.isAfter(period.getEndDate())) {
            throw ApiException.badRequest("Ngày thi nằm ngoài đợt thi");
        }
        if (examDate.getDayOfWeek() == DayOfWeek.SUNDAY) {
            throw ApiException.badRequest("Không thể xếp thi vào Chủ nhật");
        }
        if (isSchoolHoliday(period, examDate)) {
            throw ApiException.badRequest("Ngày thi là ngày nghỉ của trường");
        }
        if (startTime == null) throw ApiException.badRequest("Chọn giờ bắt đầu ca thi");
    }

    private void validatePlannedWeek(
            ExamAssessmentSource source, LocalDate examDate, String deviationReason) {
        if (isWithinPlannedWindow(source, examDate)) return;
        if (deviationReason == null || deviationReason.trim().isEmpty()) {
            throw ApiException.badRequest("Ngày thi " + examDate + " nằm ngoài tuần "
                    + source.weekNumber() + " đã lập ở GĐ3 (" + source.plannedStartDate()
                    + " đến " + source.plannedEndDate()
                    + "). Hãy chọn ngày đúng kế hoạch hoặc nhập lý do điều chỉnh.");
        }
    }

    private boolean isWithinPlannedWindow(ExamAssessmentSource source, LocalDate date) {
        return !date.isBefore(source.plannedStartDate()) && !date.isAfter(source.plannedEndDate());
    }

    private void touchVersion(String versionId) {
        ExamScheduleVersion version = requireVersion(versionId);
        version.setContentUpdatedAt(Instant.now());
        version.setLastValidatedAt(null);
        version.setLastValidationErrorCount(null);
        version.setLastValidationWarningCount(null);
        versions.save(version);
    }

    private void touchDraftVersions(String periodId) {
        versions.findByExamPeriodIdOrderByVersionNoDesc(periodId).stream()
                .filter(version -> "DRAFT".equals(version.getStatus()))
                .forEach(version -> touchVersion(version.getId()));
    }

    private boolean isValidationCurrent(ExamScheduleVersion version) {
        return version.getLastValidatedAt() != null
                && (version.getContentUpdatedAt() == null
                || !version.getLastValidatedAt().isBefore(version.getContentUpdatedAt()));
    }

    private String normalizeUnavailabilityType(String value) {
        String normalized = value == null || value.isBlank()
                ? "OTHER" : value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("LEAVE", "BUSINESS_TRIP", "PROFESSIONAL", "SICK", "NO_INVIGILATION", "OTHER")
                .contains(normalized)) {
            throw ApiException.badRequest("Loại bận/nghỉ không hợp lệ");
        }
        return normalized;
    }

    private boolean isExamSubject(Subject subject) {
        return subject != null && subject.getCode() != null
                && !NON_EXAM_SUBJECT_CODES.contains(subject.getCode().trim().toUpperCase(Locale.ROOT));
    }

    private ExamPeriodResponse periodResponse(ExamPeriod period) {
        AcademicYear year = structure.getYear(period.getAcademicYearId());
        Semester semester = structure.getSemester(period.getSemesterId());
        List<ExamScheduleVersion> history = versions.findByExamPeriodIdOrderByVersionNoDesc(period.getId());
        int latestVersion = history.stream()
                .mapToInt(ExamScheduleVersion::getVersionNo).max().orElse(0);
        boolean hasPublishedHistory = history.stream().anyMatch(version ->
                Set.of("PUBLISHED", "ARCHIVED", "RECALLED").contains(version.getStatus()));
        boolean canDelete = period.getPublishedVersionId() == null && !hasPublishedHistory;
        return new ExamPeriodResponse(period.getId(), period.getCode(), period.getName(), year.getId(), year.getName(),
                semester.getId(), semester.getName(), period.getExamType(), period.getStatus(),
                splitGrades(period.getScopeGrades()), period.isAllowSubjectTeacherProctor(), period.getStartDate(),
                period.getEndDate(), period.getPublishedVersionId(), latestVersion,
                period.getCreatedBy(), userName(period.getCreatedBy()), canDelete,
                canDelete ? null : "Đợt thi đã từng phát hành; chỉ có thể đóng hoặc hủy hiệu lực",
                period.getCreatedAt(), period.getUpdatedAt());
    }

    private ExamVersionResponse versionResponse(ExamScheduleVersion version) {
        return new ExamVersionResponse(version.getId(), version.getExamPeriodId(), version.getVersionNo(), version.getStatus(),
                version.getBasedOnVersionId(), version.getChangeReason(), version.getCreatedBy(), userName(version.getCreatedBy()),
                version.getCreatedAt(), version.getPublishedBy(), userName(version.getPublishedBy()), version.getPublishedAt(),
                version.getContentUpdatedAt(), version.getLastValidatedAt(), isValidationCurrent(version),
                version.getLastValidationErrorCount(), version.getLastValidationWarningCount());
    }

    private ExamPeriod requirePeriod(String id) {
        return periods.findById(id).orElseThrow(() -> ApiException.notFound("Đợt thi"));
    }

    private ExamScheduleVersion requireVersion(String id) {
        return versions.findById(id).orElseThrow(() -> ApiException.notFound("Phiên bản lịch thi"));
    }

    private ExamScheduleVersion requireDraftVersion(String id) {
        ExamScheduleVersion version = requireVersion(id);
        if (!"DRAFT".equals(version.getStatus())) throw ApiException.conflict("Chỉ phiên bản nháp mới được chỉnh sửa");
        return version;
    }

    private void requireEditablePeriod(String periodId) {
        if (versions.findByExamPeriodIdAndStatus(periodId, "DRAFT").isEmpty()) {
            throw ApiException.conflict("Hãy tạo hoặc thu hồi một phiên bản nháp trước khi sửa lịch giáo viên bận/nghỉ");
        }
    }

    private ExamPeriod periodOfVersion(String versionId) {
        return requirePeriod(requireVersion(versionId).getExamPeriodId());
    }

    private ExamSession requireSession(String versionId, String sessionId) {
        ExamSession session = sessions.findById(sessionId).orElseThrow(() -> ApiException.notFound("Ca thi"));
        if (!session.getVersionId().equals(versionId)) throw ApiException.notFound("Ca thi");
        return session;
    }

    private User requireTeacher(String id) {
        User user = users.findById(id).orElseThrow(() -> ApiException.notFound("Giáo viên"));
        if (!"TEACHER".equals(user.getRole()) || !"ACTIVE".equals(user.getStatus()) || user.getDeletedAt() != null) {
            throw ApiException.badRequest("Tài khoản không phải giáo viên đang hoạt động");
        }
        return user;
    }

    private String userName(String id) {
        return id == null ? null : users.findById(id).map(User::getFullName).orElse(id);
    }

    private boolean isSchoolHoliday(ExamPeriod period, LocalDate date) {
        return structure.listHolidays(period.getAcademicYearId()).stream().anyMatch(holiday -> {
            LocalDate end = holiday.getEndDate() == null ? holiday.getDate() : holiday.getEndDate();
            return !date.isBefore(holiday.getDate()) && !date.isAfter(end);
        });
    }

    private ExamValidationIssue error(String code, String message, String sessionId, String roomId) {
        return new ExamValidationIssue("ERROR", code, message, sessionId, roomId);
    }

    private ExamValidationIssue warning(String code, String message, String sessionId, String roomId) {
        return new ExamValidationIssue("WARNING", code, message, sessionId, roomId);
    }

    private Comparator<PublishedExamView> viewComparator() {
        return Comparator.comparing(PublishedExamView::examDate)
                .thenComparing(PublishedExamView::startTime)
                .thenComparing(PublishedExamView::subjectName);
    }

    private String normalizeCode(String value) {
        return value.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_-]", "-");
    }

    private String normalizeType(String value) {
        String type = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!TYPES.contains(type)) throw ApiException.badRequest("Loại kỳ thi không hợp lệ");
        return type;
    }

    private String normalizeGrade(String value) {
        String grade = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!grade.startsWith("K")) grade = "K" + grade;
        if (!GRADES.contains(grade)) throw ApiException.badRequest("Khối thi chỉ hỗ trợ 10, 11 hoặc 12");
        return grade;
    }

    private List<String> normalizeGrades(List<String> values) {
        if (values == null || values.isEmpty()) throw ApiException.badRequest("Phải chọn ít nhất một khối thi");
        return values.stream().map(this::normalizeGrade).distinct().sorted().toList();
    }

    private String joinGrades(List<String> grades) {
        return String.join(",", normalizeGrades(grades));
    }

    private List<String> splitGrades(String value) {
        return value == null || value.isBlank() ? List.of()
                : java.util.Arrays.stream(value.split(",")).map(String::trim).filter(part -> !part.isBlank()).sorted().toList();
    }

    private LocalTime parseTime(String value) {
        return LocalTime.parse(value.length() == 5 ? value : value.substring(0, 5));
    }

    private String dayCode(DayOfWeek day) {
        return switch (day) {
            case MONDAY -> "MON"; case TUESDAY -> "TUE"; case WEDNESDAY -> "WED";
            case THURSDAY -> "THU"; case FRIDAY -> "FRI"; case SATURDAY -> "SAT";
            case SUNDAY -> "SUN";
        };
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
