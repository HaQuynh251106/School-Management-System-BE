package com.sse.app.academic.timetable;

import ai.timefold.solver.core.api.score.buildin.hardsoft.HardSoftScore;
import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.SolverConfig;
import com.sse.app.academic.structure.AcademicYear;
import com.sse.app.academic.structure.Room;
import com.sse.app.academic.structure.SchoolClass;
import com.sse.app.academic.structure.Semester;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.academic.structure.Subject;
import com.sse.app.academic.planning.TeacherStaffingDtos.TeacherStaffingAnalysis;
import com.sse.app.academic.planning.TeacherStaffingService;
import com.sse.app.academic.teaching.TeacherClassSubject;
import com.sse.app.academic.teaching.TeachingAssignmentRepository;
import com.sse.app.academic.timetable.TimetableDtos.GenerateScheduleRequest;
import com.sse.app.academic.timetable.TimetableDtos.GenerationResult;
import com.sse.app.academic.timetable.TimetableDtos.GenerationReadiness;
import com.sse.app.academic.timetable.TimetableDtos.MoveDraftSlotRequest;
import com.sse.app.academic.timetable.TimetableDtos.ScheduleIssue;
import com.sse.app.academic.timetable.TimetableDtos.ScheduleValidation;
import com.sse.app.academic.timetable.solver.AutoLesson;
import com.sse.app.academic.timetable.solver.AutoRoom;
import com.sse.app.academic.timetable.solver.AutoTimeslot;
import com.sse.app.academic.timetable.solver.AutoTimetable;
import com.sse.app.academic.timetable.solver.AutoTimetableConstraintProvider;
import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.event.DomainEventPublisher;
import com.sse.app.identity.User;
import com.sse.app.identity.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AutomaticTimetableService {
    private static final List<String> VALID_DAYS =
            List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");
    private static final Map<Integer, String[]> PERIOD_TIMES = Map.ofEntries(
            Map.entry(1, new String[]{"07:00", "07:45"}),
            Map.entry(2, new String[]{"07:50", "08:35"}),
            Map.entry(3, new String[]{"08:45", "09:30"}),
            Map.entry(4, new String[]{"09:35", "10:20"}),
            Map.entry(5, new String[]{"10:25", "11:10"}),
            Map.entry(6, new String[]{"13:30", "14:15"}),
            Map.entry(7, new String[]{"14:20", "15:05"}),
            Map.entry(8, new String[]{"15:15", "16:00"}),
            Map.entry(9, new String[]{"16:05", "16:50"}),
            Map.entry(10, new String[]{"17:00", "17:45"}),
            Map.entry(11, new String[]{"18:00", "18:45"}),
            Map.entry(12, new String[]{"18:50", "19:35"}));

    private final TimetableScheduleRepository schedules;
    private final TimetableDraftSlotRepository draftSlots;
    private final TimetableRepository liveSlots;
    private final TeachingAssignmentRepository assignments;
    private final StructureService structure;
    private final UserService users;
    private final DomainEventPublisher events;
    private final TimetablePlanSourceService planSources;
    private final TeacherStaffingService staffing;

    public AutomaticTimetableService(
            TimetableScheduleRepository schedules,
            TimetableDraftSlotRepository draftSlots,
            TimetableRepository liveSlots,
            TeachingAssignmentRepository assignments,
            StructureService structure,
            UserService users,
            DomainEventPublisher events,
            TimetablePlanSourceService planSources,
            TeacherStaffingService staffing) {
        this.schedules = schedules;
        this.draftSlots = draftSlots;
        this.liveSlots = liveSlots;
        this.assignments = assignments;
        this.structure = structure;
        this.users = users;
        this.events = events;
        this.planSources = planSources;
        this.staffing = staffing;
    }

    public List<TimetableSchedule> listSchedules(String semesterId) {
        return semesterId == null || semesterId.isBlank()
                ? schedules.findAll().stream()
                    .sorted(Comparator.comparing(TimetableSchedule::getCreatedAt,
                            Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList()
                : schedules.findBySemesterIdOrderByCreatedAtDesc(semesterId);
    }

    public TimetableSchedule getSchedule(String id) {
        return schedules.findById(id)
                .orElseThrow(() -> ApiException.notFound("Bản thời khóa biểu"));
    }

    public List<TimetableDraftSlot> listDraftSlots(String scheduleId, String classId) {
        getSchedule(scheduleId);
        return classId == null || classId.isBlank()
                ? draftSlots.findByScheduleIdOrderByClassIdAscDayOfWeekAscPeriodNoAsc(scheduleId)
                : draftSlots.findByScheduleIdAndClassIdOrderByDayOfWeekAscPeriodNoAsc(
                        scheduleId, classId);
    }

    @Transactional
    public void deleteDraft(String scheduleId) {
        TimetableSchedule schedule = requireDraft(scheduleId);
        schedules.delete(schedule);
    }

    @Transactional
    public GenerationResult generate(GenerateScheduleRequest request, String actorId) {
        GenerationReadiness readiness = generationReadiness(
                request.academicYearId(), request.semesterId(),
                request.scopeGradeLevel());
        if (!readiness.ready()) {
            String details = readiness.issues().stream()
                    .filter(issue -> "ERROR".equals(issue.level()))
                    .limit(8).map(ScheduleIssue::message)
                    .collect(Collectors.joining("; "));
            throw ApiException.conflict("Chưa đủ điều kiện tạo lịch"
                    + (details.isBlank() ? "." : ": " + details));
        }
        AcademicYear year = structure.getYear(request.academicYearId());
        Semester semester = structure.getSemester(request.semesterId());
        if (!year.getId().equals(semester.getAcademicYearId())) {
            throw ApiException.badRequest("Học kỳ không thuộc năm học đã chọn");
        }
        String grade = normalizeGrade(request.scopeGradeLevel());
        List<String> days = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT");
        int firstPeriod = 1;
        int lastPeriod = 10;
        int maxDaily = 8;
        int solveSeconds = between(request.solveSeconds(), 1, 120, 30,
                "Thời gian chạy bộ giải");
        int gapDays = between(request.maxProgressGapDays(), 0, 14, 2,
                "Ngưỡng lệch ngày");
        int gapPeriods = between(request.maxProgressGapPeriods(), 0, 20, 2,
                "Ngưỡng lệch tiết");
        int gapLessons = between(request.maxCurriculumGapLessons(), 0, 10, 1,
                "Ngưỡng lệch bài học");

        List<SchoolClass> targetClasses = structure.listClasses(year.getId(), grade);
        if (targetClasses.isEmpty()) {
            throw ApiException.badRequest("Phạm vi đã chọn chưa có lớp học");
        }
        Set<String> targetClassIds = targetClasses.stream()
                .map(SchoolClass::getId).collect(Collectors.toSet());
        List<TimetablePlanSourceService.PlanSnapshot> sourceSnapshots =
                planSources.resolve(year.getId(), semester.getId(),
                        targetClasses.stream().map(SchoolClass::getGradeLevel)
                                .collect(Collectors.toSet()));
        ensureActivityAssignments(targetClasses, semester);
        List<TeacherClassSubject> targetAssignments = assignments
                .findBySemesterIdAndStatus(semester.getId(), "ACTIVE").stream()
                .filter(item -> targetClassIds.contains(item.getClassId()))
                .toList();
        validateAssignments(targetClasses, targetAssignments, sourceSnapshots,
                days.size() * maxDaily, semester.getId());

        List<Room> availableRooms = structure.listRooms().stream()
                .filter(Room::isActive).toList();
        if (availableRooms.isEmpty()) {
            throw ApiException.badRequest("Chưa có phòng học đang hoạt động");
        }
        Map<String, Room> availableRoomById = availableRooms.stream()
                .collect(Collectors.toMap(Room::getId, Function.identity()));
        List<String> classesWithoutHomeRoom = targetClasses.stream()
                .filter(item -> item.getHomeRoomId() == null
                        || !availableRoomById.containsKey(item.getHomeRoomId()))
                .map(SchoolClass::getCode).toList();
        if (!classesWithoutHomeRoom.isEmpty()) {
            throw ApiException.badRequest("Các lớp chưa có phòng học cố định: "
                    + String.join(", ", classesWithoutHomeRoom));
        }
        List<AutoTimeslot> timeslots = buildTimeslots(days, firstPeriod, lastPeriod);
        List<AutoRoom> solverRooms = availableRooms.stream()
                .map(room -> new AutoRoom(room.getId(), room.getCode(),
                        roomType(room.getRoomType()),
                        room.getCapacity() == null ? 45 : room.getCapacity()))
                .toList();
        Map<String, AutoRoom> solverRoomById = solverRooms.stream()
                .collect(Collectors.toMap(AutoRoom::getId, Function.identity()));
        Map<String, AutoTimeslot> timeslotByKey = timeslots.stream()
                .collect(Collectors.toMap(AutoTimeslot::getId, Function.identity()));
        Map<String, AutoRoom> roomByCode = solverRooms.stream()
                .collect(Collectors.toMap(AutoRoom::getCode, Function.identity(),
                        (left, right) -> left));

        List<AutoLesson> lessons = new ArrayList<>();
        Map<String, SchoolClass> classById = targetClasses.stream()
                .collect(Collectors.toMap(SchoolClass::getId, Function.identity()));
        Map<String, BlockPlacement> blockPlan = planBlockPlacements(
                targetClasses, targetAssignments, availableRooms, sourceSnapshots);
        Map<String, String> teacherRestDays = policyTeacherRestDays(
                targetAssignments, targetClasses, blockPlan, days);
        for (TeacherClassSubject assignment : targetAssignments) {
            SchoolClass schoolClass = classById.get(assignment.getClassId());
            Subject subject = structure.getSubject(assignment.getSubjectId());
            User teacher = users.getById(assignment.getTeacherId());
            assertSpecialty(teacher, subject, schoolClass);
            String subjectGroup = subjectGroup(subject.getId(), subject.getCode(), subject.getName());
            boolean heavySubject = isHeavySubject(subject.getId(), subject.getCode(), subject.getName());
            boolean morningPriority = isMorningPriority(subject.getId(), subject.getCode(), subject.getName());
            boolean latePriority = isLatePriority(subject.getId(), subject.getCode(), subject.getName());
            int weekly = plannedWeeklyPeriods(
                    sourceSnapshots, schoolClass, assignment);
            String requiredRoomType = roomType(subject.getRequiredRoomType());
            int specialized = "GENERAL".equals(requiredRoomType) ? 0 : weekly;
            for (int index = 1; index <= weekly; index++) {
                String lessonRoomType = index <= specialized
                        ? roomType(subject.getRequiredRoomType()) : "GENERAL";
                AutoLesson lesson = new AutoLesson(
                        assignment.getId() + "-" + index,
                        assignment.getId(), assignment.getClassId(),
                        assignment.getSubjectId(), assignment.getSubjectName(),
                        assignment.getTeacherId(), assignment.getTeacherName(),
                        lessonRoomType,
                        schoolClass.getHomeRoomId(),
                        subjectGroup, heavySubject, morningPriority, latePriority,
                        days.size(), teacherRestDays.get(assignment.getTeacherId()),
                        Math.max(1, schoolClass.getStudentCount()), index,
                        maxDaily, false);
                lesson.setRoomRange(eligibleRooms(
                        schoolClass, lessonRoomType, subject.getName(),
                        availableRooms, solverRoomById));
                lesson.setMainShiftStartPeriod(mainShiftStart(schoolClass));
                BlockPlacement block = blockPlan.get(assignment.getId());
                if (block != null && index <= 3) {
                    AutoTimeslot exact = timeslotByKey.get(block.dayOfWeek()
                            + "-" + (block.startPeriod() + index - 1));
                    lesson.setTimeslotRange(List.of(exact));
                    lesson.setBlockLesson(true);
                } else {
                    int shiftStart = mainShiftStart(schoolClass);
                    int flagPeriod = shiftStart;
                    int homeroomPeriod = shiftStart + 4;
                    lesson.setTimeslotRange(timeslots.stream()
                            .filter(timeslot -> timeslot.getPeriodNo() >= shiftStart
                                    && timeslot.getPeriodNo() < shiftStart + 5)
                            .filter(timeslot -> !("MON".equals(timeslot.getDayOfWeek())
                                    && timeslot.getPeriodNo() == flagPeriod))
                            .filter(timeslot -> !("SAT".equals(timeslot.getDayOfWeek())
                                    && timeslot.getPeriodNo() == homeroomPeriod))
                            .filter(timeslot -> !timeslot.getDayOfWeek().equals(
                                    teacherRestDays.get(assignment.getTeacherId())))
                            .toList());
                }
                lessons.add(lesson);
            }
        }
        for (SchoolClass schoolClass : targetClasses) {
            String homeroomTeacherId = schoolClass.getHomeroomTeacherId();
            if (homeroomTeacherId == null || homeroomTeacherId.isBlank()) {
                throw ApiException.badRequest("Lớp " + schoolClass.getCode()
                        + " chưa có giáo viên chủ nhiệm");
            }
            User homeroom = users.getById(homeroomTeacherId);
            int shiftStart = mainShiftStart(schoolClass);
            lessons.add(fixedActivity(schoolClass, timeslotByKey,
                    solverRoomById, "FLAG", activityAssignmentId(
                            "FLAG", schoolClass.getId(), semester.getId()),
                    "sj-flag", "Chào cờ", "MON", shiftStart,
                    homeroom.getId(), homeroom.getFullName(),
                    teacherRestDays.get(homeroom.getId()), maxDaily));
            lessons.add(fixedActivity(schoolClass, timeslotByKey,
                    solverRoomById, "HOMEROOM", activityAssignmentId(
                            "HOMEROOM", schoolClass.getId(), semester.getId()),
                    "sj-homeroom", "Sinh hoạt lớp",
                    "SAT", shiftStart + 4, homeroom.getId(), homeroom.getFullName(),
                    teacherRestDays.get(homeroom.getId()), maxDaily));
        }

        if (grade != null) {
            Set<String> outside = structure.listClasses(year.getId(), null).stream()
                    .map(SchoolClass::getId)
                    .filter(id -> !targetClassIds.contains(id))
                    .collect(Collectors.toSet());
            for (TimetableSlot slot : liveSlots.findBySemesterId(semester.getId())) {
                if (!outside.contains(slot.getClassId())) continue;
                AutoTimeslot timeslot = timeslotByKey.get(
                        slot.getDayOfWeek() + "-" + slot.getPeriodNo());
                AutoRoom room = roomByCode.get(slot.getRoomCode());
                if (timeslot == null || room == null) continue;
                AutoLesson pinned = new AutoLesson(
                        "pinned-" + slot.getId(), "pinned-" + slot.getId(),
                        slot.getClassId(), slot.getSubjectId(), slot.getSubjectName(),
                        slot.getTeacherId(), slot.getTeacherName(), "GENERAL", null,
                        subjectGroup(slot.getSubjectId(), slot.getSubjectName()),
                        isHeavySubject(slot.getSubjectId(), slot.getSubjectName()),
                        isMorningPriority(slot.getSubjectId(), slot.getSubjectName()),
                        isLatePriority(slot.getSubjectId(), slot.getSubjectName()),
                        days.size(), null, 1,
                        1, maxDaily, true);
                pinned.setTimeslot(timeslot);
                pinned.setRoom(room);
                pinned.setTimeslotRange(List.of(timeslot));
                pinned.setRoomRange(List.of(room));
                lessons.add(pinned);
            }
        }

        assertSpecializedRoomCapacity(lessons, timeslots, solverRooms);
        assertTeacherCapacity(lessons, days.size());

        SolveOutcome outcome = grade == null
                ? solveWholeSchoolInStages(timeslots, solverRooms, lessons,
                        classById, solveSeconds)
                : solveOnce(timeslots, solverRooms, lessons, solveSeconds);
        List<AutoLesson> solvedLessons = outcome.lessons();
        HardSoftScore score = outcome.score();
        if (score == null || score.hardScore() < 0) {
            int conflicts = score == null ? 1 : -score.hardScore();
            String detail = score == null ? "" : describeHardViolations(
                    new AutoTimetable(timeslots, solverRooms, solvedLessons));
            throw ApiException.conflict("Bộ giải chưa tìm được lịch không xung đột sau "
                    + solveSeconds + " giây (còn " + conflicts
                    + " vi phạm bắt buộc" + detail
                    + "). Bản lỗi không được lưu; hãy tăng thời gian giải hoặc điều chỉnh ràng buộc.");
        }
        Instant now = Instant.now();
        TimetableSchedule schedule = schedules.save(TimetableSchedule.builder()
                .id(request.id() == null || request.id().isBlank()
                        ? Ids.gen("tts") : request.id())
                .academicYearId(year.getId())
                .semesterId(semester.getId())
                .scopeGradeLevel(grade)
                .name(request.name() == null || request.name().isBlank()
                        ? "Lịch tự động " + semester.getCode()
                        : request.name().trim())
                .status("DRAFT")
                .teachingDays(String.join(",", days))
                .firstPeriod(firstPeriod).lastPeriod(lastPeriod)
                .maxPeriodsPerDay(maxDaily)
                .maxProgressGapDays(gapDays)
                .maxProgressGapPeriods(gapPeriods)
                .maxCurriculumGapLessons(gapLessons)
                .solveSeconds(solveSeconds)
                .solverScore(score == null ? null : score.toString())
                .hardViolationCount(score == null ? 1
                        : Math.max(0, -score.hardScore()))
                .warningCount(0)
                .generationSummary("classes=" + targetClasses.size()
                        + ";assignments=" + targetAssignments.size()
                        + ";periods=" + targetAssignments.stream()
                            .mapToInt(item -> plannedWeeklyPeriods(sourceSnapshots,
                                    classById.get(item.getClassId()), item)).sum()
                        + ";activities=" + (targetClasses.size() * 2))
                .sourcePlanSummary(planSources.summary(sourceSnapshots))
                .sourcePlanSnapshot(planSources.serialize(sourceSnapshots))
                .generatedAt(now).generatedBy(actorId)
                .createdAt(now).updatedAt(now).build());

        List<TimetableDraftSlot> persisted = solvedLessons.stream()
                .filter(item -> !item.isPinned())
                .map(item -> toDraftSlot(schedule, semester, item, now))
                .toList();
        draftSlots.saveAll(persisted);
        ScheduleValidation validation = validate(schedule.getId());
        if (!validation.valid()) {
            String details = validation.issues().stream()
                    .filter(issue -> "ERROR".equals(issue.level()))
                    .limit(5)
                    .map(issue -> issue.code() + ": " + issue.message())
                    .collect(Collectors.joining("; "));
            throw ApiException.conflict("Lịch vừa tạo còn " + validation.errorCount()
                    + " lỗi bắt buộc nên đã được hủy"
                    + (details.isBlank() ? "." : ": " + details));
        }
        return new GenerationResult(getSchedule(schedule.getId()), validation);
    }

    public GenerationReadiness generationReadiness(
            String academicYearId, String semesterId, String scopeGradeLevel) {
        AcademicYear year = structure.getYear(academicYearId);
        Semester semester = structure.getSemester(semesterId);
        String grade = normalizeGrade(scopeGradeLevel);
        List<ScheduleIssue> issues = new ArrayList<>();
        if (!year.getId().equals(semester.getAcademicYearId())) {
            issues.add(readinessIssue("SEMESTER_YEAR_MISMATCH",
                    "Học kỳ không thuộc năm học đã chọn", null, null));
            return readinessResult(year, semester, grade, List.of(), List.of(),
                    0, issues);
        }

        List<SchoolClass> targetClasses = structure.listClasses(year.getId(), grade);
        if (targetClasses.isEmpty()) {
            issues.add(readinessIssue("CLASS_MISSING",
                    grade == null ? "Năm học chưa có lớp để xếp lịch"
                            : "Khối " + grade.substring(1) + " chưa có lớp để xếp lịch",
                    null, null));
            return readinessResult(year, semester, grade, targetClasses, List.of(),
                    0, issues);
        }

        Set<String> targetGrades = targetClasses.stream()
                .map(SchoolClass::getGradeLevel).collect(Collectors.toSet());
        List<TimetablePlanSourceService.PlanSnapshot> sourceSnapshots =
                new ArrayList<>();
        for (String targetGrade : targetGrades.stream().sorted().toList()) {
            try {
                sourceSnapshots.addAll(planSources.resolve(year.getId(), semester.getId(),
                        Set.of(targetGrade)));
            } catch (ApiException exception) {
                issues.add(readinessIssue("SOURCE_PLAN_MISSING",
                        targetGrade + ": " + exception.getMessage(), null, null));
            }
        }

        Set<String> classIds = targetClasses.stream().map(SchoolClass::getId)
                .collect(Collectors.toSet());
        List<TeacherClassSubject> targetAssignments = assignments
                .findBySemesterIdAndStatus(semester.getId(), "ACTIVE").stream()
                .filter(item -> classIds.contains(item.getClassId())).toList();
        Map<String, SchoolClass> classById = targetClasses.stream()
                .collect(Collectors.toMap(SchoolClass::getId, Function.identity()));
        Set<String> sourceGrades = sourceSnapshots.stream()
                .map(TimetablePlanSourceService.PlanSnapshot::gradeLevel)
                .collect(Collectors.toSet());
        TeacherStaffingAnalysis staffingAnalysis = null;
        if (sourceGrades.containsAll(targetGrades)) {
            staffingAnalysis = staffing.analyze(year.getId(), semester.getId(), grade);
            staffingAnalysis.subjects().stream()
                    .filter(item -> item.shortage() > 0)
                    .forEach(item -> issues.add(new ScheduleIssue(
                            "ERROR", "TEACHER_STAFFING_SHORTAGE",
                            item.subjectName() + " thiếu " + item.shortage()
                                    + " giáo viên đúng chuyên môn để xếp đủ lịch (cần "
                                    + item.minimumTeachersForYear() + ", hiện có "
                                    + item.qualifiedTeacherCount() + ")",
                            null, null, item.subjectId(), null, null)));
            if (!staffingAnalysis.withinLegalCeiling()) {
                issues.add(new ScheduleIssue(
                        "WARNING", "TEACHER_STAFFING_CEILING",
                        "Toàn trường hiện có " + staffingAnalysis.currentActiveTeacherCount()
                                + " giáo viên, vượt trần nguyên người "
                                + staffingAnalysis.maximumWholeTeachers() + " theo loại trường đã chọn",
                        null, null, null, null, null));
            }
        }
        Map<String, Integer> classPeriods = new HashMap<>();
        Map<String, Integer> teacherPeriods = new HashMap<>();
        Map<String, String> teacherNames = new HashMap<>();
        int requiredPeriods = 0;

        for (TeacherClassSubject assignment : targetAssignments) {
            SchoolClass schoolClass = classById.get(assignment.getClassId());
            if (schoolClass == null
                    || !sourceGrades.contains(schoolClass.getGradeLevel())) continue;
            try {
                int weekly = plannedWeeklyPeriods(sourceSnapshots, schoolClass, assignment);
                requiredPeriods += weekly;
                classPeriods.merge(assignment.getClassId(), weekly, Integer::sum);
                teacherPeriods.merge(assignment.getTeacherId(), weekly, Integer::sum);
                teacherNames.put(assignment.getTeacherId(), assignment.getTeacherName());
                try {
                    assertSpecialty(users.getById(assignment.getTeacherId()),
                            structure.getSubject(assignment.getSubjectId()), schoolClass);
                } catch (ApiException exception) {
                    issues.add(readinessIssue("TEACHER_SPECIALTY",
                            exception.getMessage(), assignment.getClassId(),
                            assignment.getTeacherId()));
                }
            } catch (ApiException exception) {
                issues.add(readinessIssue("PLAN_SUBJECT_MISSING",
                        schoolClass.getCode() + ": " + exception.getMessage(),
                        assignment.getClassId(), assignment.getTeacherId()));
            }
        }

        Set<String> availableRoomIds = structure.listRooms().stream()
                .filter(Room::isActive).map(Room::getId).collect(Collectors.toSet());
        for (SchoolClass schoolClass : targetClasses) {
            boolean hasSource = sourceGrades.contains(schoolClass.getGradeLevel());
            if (hasSource && classPeriods.getOrDefault(schoolClass.getId(), 0) == 0) {
                issues.add(readinessIssue("ASSIGNMENT_MISSING",
                        schoolClass.getCode() + " chưa có phân công giáo viên",
                        schoolClass.getId(), null));
            }
            if (schoolClass.getHomeRoomId() == null
                    || !availableRoomIds.contains(schoolClass.getHomeRoomId())) {
                issues.add(readinessIssue("HOME_ROOM_MISSING",
                        schoolClass.getCode() + " chưa có phòng học cố định đang hoạt động",
                        schoolClass.getId(), null));
            }
            if (schoolClass.getHomeroomTeacherId() == null
                    || schoolClass.getHomeroomTeacherId().isBlank()) {
                issues.add(readinessIssue("HOMEROOM_TEACHER_MISSING",
                        schoolClass.getCode() + " chưa có giáo viên chủ nhiệm",
                        schoolClass.getId(), null));
            } else if (hasSource) {
                // Chào cờ và sinh hoạt lớp là hai tiết cố định do GVCN phụ trách.
                teacherPeriods.merge(schoolClass.getHomeroomTeacherId(), 2, Integer::sum);
                teacherNames.putIfAbsent(schoolClass.getHomeroomTeacherId(),
                        users.getById(schoolClass.getHomeroomTeacherId()).getFullName());
                requiredPeriods += 2;
            }
        }

        int weeklyTeachingNorm = staffingAnalysis == null
                ? 17 : staffingAnalysis.policy().weeklyTeachingNorm();
        int weeklyTeacherCapacity = 25;
        teacherPeriods.forEach((teacherId, periods) -> {
            if (periods > weeklyTeacherCapacity) {
                issues.add(readinessIssue("TEACHER_OVERLOAD",
                        teacherNames.getOrDefault(teacherId, teacherId) + " cần "
                                + periods + " tiết/tuần, vượt mức "
                                + weeklyTeacherCapacity
                                + " tiết (5 tiết/ngày và một ngày nghỉ)",
                        null, teacherId));
            } else if (periods > weeklyTeachingNorm) {
                issues.add(new ScheduleIssue(
                        "WARNING", "TEACHER_NORM_EXCEEDED",
                        teacherNames.getOrDefault(teacherId, teacherId) + " được phân "
                                + periods + " tiết/tuần, vượt định mức "
                                + weeklyTeachingNorm + " tiết/tuần",
                        null, teacherId, null, null, null));
            }
        });

        return readinessResult(year, semester, grade, targetClasses,
                targetAssignments, requiredPeriods, issues,
                sourceSnapshots.isEmpty() ? null : planSources.summary(sourceSnapshots));
    }

    private GenerationReadiness readinessResult(
            AcademicYear year, Semester semester, String grade,
            List<SchoolClass> targetClasses,
            List<TeacherClassSubject> targetAssignments,
            int requiredPeriods, List<ScheduleIssue> issues) {
        return readinessResult(year, semester, grade, targetClasses,
                targetAssignments, requiredPeriods, issues, null);
    }

    private GenerationReadiness readinessResult(
            AcademicYear year, Semester semester, String grade,
            List<SchoolClass> targetClasses,
            List<TeacherClassSubject> targetAssignments,
            int requiredPeriods, List<ScheduleIssue> issues,
            String sourcePlanSummary) {
        return new GenerationReadiness(issues.stream()
                .noneMatch(issue -> "ERROR".equals(issue.level())),
                year.getId(), semester.getId(), grade, sourcePlanSummary,
                targetClasses.size(), targetAssignments.size(), requiredPeriods,
                List.copyOf(issues));
    }

    private ScheduleIssue readinessIssue(String code, String message,
                                         String classId, String teacherId) {
        return new ScheduleIssue("ERROR", code, message, classId, teacherId,
                null, null, null);
    }

    private SolveOutcome solveWholeSchoolInStages(
            List<AutoTimeslot> timeslots, List<AutoRoom> rooms,
            List<AutoLesson> lessons, Map<String, SchoolClass> classById,
            int totalSolveSeconds) {
        List<String> stages = lessons.stream().filter(item -> !item.isPinned())
                .map(item -> classById.get(item.getClassId()))
                .filter(Objects::nonNull).map(SchoolClass::getGradeLevel)
                .filter(Objects::nonNull).distinct()
                .sorted(Comparator.comparingInt(this::gradeOrder)).toList();
        if (stages.size() < 2) {
            return solveOnce(timeslots, rooms, lessons, totalSolveSeconds);
        }

        // A 300-lesson grade needs enough time to reach a feasible seed before
        // soft optimization. Whole-school requests therefore use the selected
        // budget per grade instead of splitting it across all three grades.
        int secondsPerStage = Math.max(60, totalSolveSeconds);
        List<AutoLesson> accumulated = lessons.stream()
                .filter(AutoLesson::isPinned).collect(Collectors.toCollection(ArrayList::new));
        int aggregateSoftScore = 0;
        for (String stage : stages) {
            List<AutoLesson> current = lessons.stream()
                    .filter(item -> !item.isPinned())
                    .filter(item -> {
                        SchoolClass schoolClass = classById.get(item.getClassId());
                        return schoolClass != null && Objects.equals(
                                stage, schoolClass.getGradeLevel());
                    }).toList();
            List<AutoLesson> stageProblem = new ArrayList<>(accumulated);
            stageProblem.addAll(current);
            SolveOutcome stageOutcome = solveOnce(
                    timeslots, rooms, stageProblem, secondsPerStage);
            if (stageOutcome.score() == null || stageOutcome.score().hardScore() < 0) {
                int remaining = stageOutcome.score() == null
                        ? 1 : -stageOutcome.score().hardScore();
                String detail = stageOutcome.score() == null ? ""
                        : describeHardViolations(new AutoTimetable(
                                timeslots, rooms, stageOutcome.lessons()));
                throw ApiException.conflict("Chưa thể xếp toàn trường: pha " + stage
                        + " còn " + remaining + " xung đột sau "
                        + secondsPerStage + " giây" + detail
                        + ". Hãy kiểm tra tải giáo viên và phòng chuyên dụng.");
            }
            aggregateSoftScore += stageOutcome.score().softScore();
            accumulated = new ArrayList<>(stageOutcome.lessons());
            accumulated.forEach(item -> item.setPinned(true));
        }
        accumulated.forEach(item -> item.setPinned(false));
        return new SolveOutcome(accumulated,
                HardSoftScore.of(0, aggregateSoftScore));
    }

    private SolveOutcome solveOnce(List<AutoTimeslot> timeslots,
                                   List<AutoRoom> rooms,
                                   List<AutoLesson> lessons,
                                   int solveSeconds) {
        SolverFactory<AutoTimetable> solverFactory = SolverFactory.create(
                new SolverConfig()
                        .withSolutionClass(AutoTimetable.class)
                        .withEntityClasses(AutoLesson.class)
                        .withConstraintProviderClass(
                                AutoTimetableConstraintProvider.class)
                        .withTerminationSpentLimit(Duration.ofSeconds(solveSeconds)));
        Solver<AutoTimetable> solver = solverFactory.buildSolver();
        AutoTimetable solution = solver.solve(
                new AutoTimetable(timeslots, rooms, lessons));
        return new SolveOutcome(solution.getLessons(), solution.getScore());
    }

    private int gradeOrder(String grade) {
        return switch (grade == null ? "" : grade) {
            case "K10" -> 10;
            case "K11" -> 11;
            case "K12" -> 12;
            default -> 99;
        };
    }

    private record SolveOutcome(List<AutoLesson> lessons, HardSoftScore score) {}

    public ScheduleValidation validate(String scheduleId) {
        TimetableSchedule schedule = getSchedule(scheduleId);
        List<TimetablePlanSourceService.PlanSnapshot> sourceSnapshots =
                planSources.parse(schedule);
        List<TimetableDraftSlot> rows =
                draftSlots.findByScheduleIdOrderByClassIdAscDayOfWeekAscPeriodNoAsc(
                        scheduleId);
        List<SchoolClass> classes = structure.listClasses(
                schedule.getAcademicYearId(), schedule.getScopeGradeLevel());
        Set<String> classIds = classes.stream().map(SchoolClass::getId)
                .collect(Collectors.toSet());
        List<TeacherClassSubject> required = assignments
                .findBySemesterIdAndStatus(schedule.getSemesterId(), "ACTIVE").stream()
                .filter(item -> classIds.contains(item.getClassId())).toList();
        Map<String, TeacherClassSubject> assignmentById = required.stream()
                .collect(Collectors.toMap(TeacherClassSubject::getId,
                        Function.identity()));
        Map<String, Room> roomsById = structure.listRooms().stream()
                .collect(Collectors.toMap(Room::getId, Function.identity()));
        Map<String, SchoolClass> classById = classes.stream()
                .collect(Collectors.toMap(SchoolClass::getId, Function.identity()));
        List<ScheduleIssue> issues = new ArrayList<>();
        Set<String> classCells = new HashSet<>();
        Set<String> teacherCells = new HashSet<>();
        Set<String> roomCells = new HashSet<>();
        Map<String, Integer> assignmentCounts = new HashMap<>();
        Map<String, Integer> dailyCounts = new HashMap<>();
        Map<String, Integer> subjectDaily = new HashMap<>();
        Map<String, Integer> teacherDailyCounts = new HashMap<>();
        Map<String, Set<String>> teacherWorkDays = new HashMap<>();
        Map<String, List<Integer>> heavyPeriods = new HashMap<>();
        Map<String, List<Integer>> teacherPeriods = new HashMap<>();
        Set<String> targetTeacherIds = rows.stream().map(TimetableDraftSlot::getTeacherId)
                .collect(Collectors.toSet());
        Map<String, BlockPlacement> validationBlockPlan = planBlockPlacements(
                classes, required, structure.listRooms().stream()
                        .filter(Room::isActive).toList(), sourceSnapshots);
        Map<String, String> teacherRestDays = policyTeacherRestDays(
                required, classes, validationBlockPlan,
                List.of(schedule.getTeachingDays().split(",")));
        int teachingDayCount = (int) List.of(schedule.getTeachingDays().split(","))
                .stream().filter(day -> !day.isBlank()).distinct().count();

        List<TimetableSlot> externalLive = liveSlots
                .findBySemesterId(schedule.getSemesterId()).stream()
                .filter(item -> !classIds.contains(item.getClassId()))
                .toList();
        for (TimetableSlot live : externalLive) {
            String cell = live.getDayOfWeek() + "-" + live.getPeriodNo();
            teacherCells.add(live.getTeacherId() + "-" + cell);
            String teacherDay = live.getTeacherId() + "-" + live.getDayOfWeek();
            teacherDailyCounts.merge(teacherDay, 1, Integer::sum);
            teacherWorkDays.computeIfAbsent(live.getTeacherId(), key -> new HashSet<>())
                    .add(live.getDayOfWeek());
            teacherPeriods.computeIfAbsent(teacherDay, key -> new ArrayList<>())
                    .add(live.getPeriodNo());
            if (live.getRoomCode() != null && !live.getRoomCode().isBlank()) {
                roomCells.add(live.getRoomCode().trim().toUpperCase(Locale.ROOT)
                        + "-" + cell);
            }
        }

        for (TimetableDraftSlot row : rows) {
            TeacherClassSubject assignment = assignmentById.get(row.getAssignmentId());
            boolean activity = row.getAssignmentId() != null
                    && row.getAssignmentId().startsWith("activity-");
            if (!activity && (assignment == null
                    || !assignment.getClassId().equals(row.getClassId())
                    || !assignment.getTeacherId().equals(row.getTeacherId())
                    || !assignment.getSubjectId().equals(row.getSubjectId()))) {
                issues.add(issue("ERROR", "INVALID_ASSIGNMENT",
                        "Tiết học không còn khớp phân công giáo viên", row));
            }
            String cell = row.getDayOfWeek() + "-" + row.getPeriodNo();
            if (!classCells.add(row.getClassId() + "-" + cell)) {
                issues.add(issue("ERROR", "CLASS_CONFLICT", "Trùng lịch lớp", row));
            }
            if (!teacherCells.add(row.getTeacherId() + "-" + cell)) {
                issues.add(issue("ERROR", "TEACHER_CONFLICT", "Trùng lịch giáo viên", row));
            }
            if (row.getRoomCode() != null && !row.getRoomCode().isBlank()
                    && !roomCells.add(row.getRoomCode().trim()
                            .toUpperCase(Locale.ROOT) + "-" + cell)) {
                issues.add(issue("ERROR", "ROOM_CONFLICT", "Trùng phòng học", row));
            }
            if (!activity) {
                assignmentCounts.merge(row.getAssignmentId(), 1, Integer::sum);
            }
            String dailyKey = row.getClassId() + "-" + row.getDayOfWeek();
            dailyCounts.merge(dailyKey, 1, Integer::sum);
            String spreadKey = dailyKey + "-" + row.getSubjectId();
            subjectDaily.merge(spreadKey, 1, Integer::sum);
            String teacherDay = row.getTeacherId() + "-" + row.getDayOfWeek();
            teacherDailyCounts.merge(teacherDay, 1, Integer::sum);
            teacherWorkDays.computeIfAbsent(row.getTeacherId(), key -> new HashSet<>())
                    .add(row.getDayOfWeek());
            teacherPeriods.computeIfAbsent(teacherDay, key -> new ArrayList<>())
                    .add(row.getPeriodNo());
            if (isHeavySubject(row.getSubjectId(), row.getSubjectName())) {
                heavyPeriods.computeIfAbsent(dailyKey, key -> new ArrayList<>())
                        .add(row.getPeriodNo());
            }

            Room room = row.getRoomId() == null ? null : roomsById.get(row.getRoomId());
            SchoolClass schoolClass = classById.get(row.getClassId());
            if (room == null) {
                issues.add(issue("ERROR", "ROOM_REQUIRED", "Tiết học chưa có phòng", row));
            } else {
                String requiredType = roomType(row.getRequiredRoomType());
                if ("GENERAL".equals(requiredType) && schoolClass != null
                        && schoolClass.getHomeRoomId() != null
                        && !schoolClass.getHomeRoomId().equals(room.getId())) {
                    issues.add(issue("ERROR", "HOME_ROOM_REQUIRED",
                            "Môn học thường phải học tại phòng cố định của lớp", row));
                }
                if (!"GENERAL".equals(requiredType)
                        && !requiredType.equals(roomType(room.getRoomType()))) {
                    issues.add(issue("ERROR", "ROOM_TYPE",
                            "Phòng không đúng loại yêu cầu của môn", row));
                }
                if (schoolClass != null && room.getCapacity() != null
                        && room.getCapacity() < schoolClass.getStudentCount()) {
                    issues.add(issue("ERROR", "ROOM_CAPACITY",
                            "Phòng không đủ sức chứa lớp", row));
                }
            }
        }
        for (TeacherClassSubject assignment : required) {
            int expected = plannedWeeklyPeriods(sourceSnapshots,
                    classById.get(assignment.getClassId()), assignment);
            int actual = assignmentCounts.getOrDefault(assignment.getId(), 0);
            if (actual != expected) {
                issues.add(new ScheduleIssue("ERROR", "WEEKLY_COVERAGE",
                        assignment.getClassCode() + " · " + assignment.getSubjectName()
                                + " cần " + expected + " tiết, hiện có " + actual,
                        assignment.getClassId(), assignment.getTeacherId(),
                        assignment.getSubjectId(), null, null));
            }
        }
        for (SchoolClass schoolClass : classes) {
            int shiftStart = mainShiftStart(schoolClass);
            int flagPeriod = shiftStart;
            int homeroomPeriod = shiftStart + 4;
            boolean hasFlag = rows.stream().anyMatch(row ->
                    schoolClass.getId().equals(row.getClassId())
                            && "sj-flag".equals(row.getSubjectId())
                            && "MON".equals(row.getDayOfWeek())
                            && row.getPeriodNo() == flagPeriod);
            boolean hasHomeroom = rows.stream().anyMatch(row ->
                    schoolClass.getId().equals(row.getClassId())
                            && "sj-homeroom".equals(row.getSubjectId())
                            && "SAT".equals(row.getDayOfWeek())
                            && row.getPeriodNo() == homeroomPeriod);
            if (!hasFlag) {
                issues.add(new ScheduleIssue("ERROR", "FLAG_CEREMONY_REQUIRED",
                        schoolClass.getCode() + " thiếu Chào cờ đầu ca thứ Hai, tiết "
                                + flagPeriod,
                        schoolClass.getId(), null, "sj-flag", "MON", flagPeriod));
            }
            if (!hasHomeroom) {
                issues.add(new ScheduleIssue("ERROR", "HOMEROOM_REQUIRED",
                        schoolClass.getCode() + " thiếu Sinh hoạt lớp cuối ca thứ Bảy, tiết "
                                + homeroomPeriod,
                        schoolClass.getId(), schoolClass.getHomeroomTeacherId(),
                        "sj-homeroom", "SAT", homeroomPeriod));
            }
        }
        validateSchoolShiftPolicy(classes, required, rows, issues, sourceSnapshots);
        dailyCounts.forEach((key, count) -> {
            if (count > schedule.getMaxPeriodsPerDay()) {
                issues.add(new ScheduleIssue("ERROR", "DAILY_LIMIT",
                        "Lớp vượt số tiết tối đa trong một ngày: " + count,
                        key.substring(0, key.lastIndexOf('-')), null, null,
                        key.substring(key.lastIndexOf('-') + 1), null));
            }
        });
        subjectDaily.forEach((key, count) -> {
            if (count > 1) {
                issues.add(new ScheduleIssue("WARNING", "SUBJECT_CONCENTRATION",
                        "Một môn có " + count + " tiết trong cùng ngày",
                        null, null, null, null, null));
            }
        });
        teacherDailyCounts.forEach((key, count) -> {
            int separator = key.lastIndexOf('-');
            if (count > 5 && targetTeacherIds.contains(key.substring(0, separator))) {
                issues.add(new ScheduleIssue("ERROR", "TEACHER_DAILY_LIMIT",
                        "Giáo viên vượt giới hạn 5 tiết trong ngày: " + count + " tiết",
                        null, key.substring(0, separator), null,
                        key.substring(separator + 1), null));
            }
        });
        if (teachingDayCount >= 2) {
            rows.stream().filter(row -> Objects.equals(
                            teacherRestDays.get(row.getTeacherId()), row.getDayOfWeek()))
                    .forEach(row -> issues.add(issue("ERROR", "TEACHER_REST_DAY",
                            "Tiết học trùng ngày nghỉ đã phân bổ cho giáo viên", row)));
        }
        heavyPeriods.forEach((key, periods) -> {
            if (longestConsecutivePeriods(periods) >= 4) {
                int separator = key.lastIndexOf('-');
                issues.add(new ScheduleIssue("ERROR", "HEAVY_SUBJECT_RUN",
                        "Lớp có từ 4 tiết Toán, Vật lý hoặc Hóa học liên tiếp",
                        key.substring(0, separator), null, null,
                        key.substring(separator + 1), null));
            }
        });
        teacherPeriods.forEach((key, periods) -> {
            int gaps = internalGapCount(periods);
            if (gaps > 0) {
                issues.add(new ScheduleIssue("WARNING", "TEACHER_INTERNAL_GAPS",
                        "Lịch giáo viên có " + gaps + " tiết trống giữa giờ",
                        null, key.substring(0, key.lastIndexOf('-')), null,
                        key.substring(key.lastIndexOf('-') + 1), null));
            }
        });
        int requiredPeriods = required.stream().mapToInt(item ->
                plannedWeeklyPeriods(sourceSnapshots,
                        classById.get(item.getClassId()), item)).sum()
                + classes.size() * 2;
        int errorCount = (int) issues.stream()
                .filter(item -> "ERROR".equals(item.level())).count();
        int warningCount = (int) issues.stream()
                .filter(item -> "WARNING".equals(item.level())).count();
        schedule.setHardViolationCount(errorCount);
        schedule.setWarningCount(warningCount);
        schedule.setUpdatedAt(Instant.now());
        schedules.save(schedule);
        return new ScheduleValidation(errorCount == 0
                && rows.size() == requiredPeriods,
                requiredPeriods, rows.size(), errorCount, warningCount, issues);
    }

    private void validateSchoolShiftPolicy(
            List<SchoolClass> classes,
            List<TeacherClassSubject> required,
            List<TimetableDraftSlot> rows,
            List<ScheduleIssue> issues,
            List<TimetablePlanSourceService.PlanSnapshot> sourceSnapshots) {
        List<String> teachingDays = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT");
        Map<String, TeacherClassSubject> byClassSubject = required.stream()
                .collect(Collectors.toMap(item -> item.getClassId() + "|"
                                + item.getSubjectId(), Function.identity(),
                        (left, right) -> left));

        for (SchoolClass schoolClass : classes) {
            List<TimetableDraftSlot> classRows = rows.stream()
                    .filter(row -> schoolClass.getId().equals(row.getClassId()))
                    .toList();
            List<TeacherClassSubject> blockAssignments = selectBlockAssignments(
                    schoolClass, byClassSubject, required, sourceSnapshots);
            Set<String> blockAssignmentIds = blockAssignments.stream()
                    .map(TeacherClassSubject::getId).collect(Collectors.toSet());
            List<String> allowedBlockDays = isAfternoonMain(schoolClass)
                    ? List.of("TUE", "THU", "SAT")
                    : List.of("MON", "WED", "FRI");
            int blockStart = isAfternoonMain(schoolClass) ? 1 : 6;
            Set<String> actualBlockDays = new HashSet<>();

            for (TeacherClassSubject assignment : blockAssignments) {
                List<TimetableDraftSlot> blockRows = classRows.stream()
                        .filter(row -> assignment.getId().equals(row.getAssignmentId()))
                        .filter(row -> row.getLessonIndex() >= 1 && row.getLessonIndex() <= 3)
                        .sorted(Comparator.comparingInt(TimetableDraftSlot::getPeriodNo))
                        .toList();
                boolean validBlock = blockRows.size() == 3;
                if (validBlock) {
                    String day = blockRows.get(0).getDayOfWeek();
                    actualBlockDays.add(day);
                    validBlock = allowedBlockDays.contains(day)
                            && blockRows.stream().allMatch(row -> day.equals(row.getDayOfWeek()))
                            && blockRows.get(0).getPeriodNo() == blockStart
                            && blockRows.get(1).getPeriodNo() == blockStart + 1
                            && blockRows.get(2).getPeriodNo() == blockStart + 2;
                }
                if (!validBlock) {
                    issues.add(policyIssue("THREE_PERIOD_BLOCK_REQUIRED",
                            schoolClass.getCode() + " · " + assignment.getSubjectName()
                                    + " phải học ba tiết liên tục trong buổi đối ca",
                            schoolClass, assignment, null));
                }
            }
            if (!blockAssignments.isEmpty()
                    && actualBlockDays.size() != blockAssignments.size()) {
                issues.add(policyIssue("ALTERNATING_BLOCK_DAYS_REQUIRED",
                        schoolClass.getCode()
                                + " phải có các buổi block xen kẽ đúng lịch quy định",
                        schoolClass, null, null));
            }

            List<TimetableDraftSlot> regularRows = classRows.stream()
                    .filter(row -> row.getAssignmentId() != null
                            && !row.getAssignmentId().startsWith("activity-"))
                    .filter(row -> !(blockAssignmentIds.contains(row.getAssignmentId())
                            && row.getLessonIndex() >= 1 && row.getLessonIndex() <= 3))
                    .toList();
            int mainStart = mainShiftStart(schoolClass);
            for (String day : teachingDays) {
                List<Integer> periods = regularRows.stream()
                        .filter(row -> day.equals(row.getDayOfWeek()))
                        .map(TimetableDraftSlot::getPeriodNo)
                        .sorted().toList();
                if (periods.size() < 2 || periods.size() > 4) {
                    issues.add(policyIssue("MAIN_SESSION_DAILY_LOAD",
                            schoolClass.getCode() + " · " + day
                                    + " phải có từ 2 đến 4 tiết chính",
                            schoolClass, null, day));
                    continue;
                }
                int expectedStart = "MON".equals(day) ? mainStart + 1 : mainStart;
                boolean compact = periods.get(0) == expectedStart;
                for (int index = 1; index < periods.size(); index++) {
                    compact = compact && periods.get(index) == periods.get(index - 1) + 1;
                }
                if (!compact) {
                    issues.add(policyIssue("MAIN_SESSION_COMPACT",
                            schoolClass.getCode() + " · " + day
                                    + " phải học liền từ tiết đầu ca, không để tiết trống",
                            schoolClass, null, day));
                }
            }
        }
    }

    private ScheduleIssue policyIssue(String code, String message,
                                      SchoolClass schoolClass,
                                      TeacherClassSubject assignment,
                                      String day) {
        return new ScheduleIssue("ERROR", code, message, schoolClass.getId(),
                assignment == null ? null : assignment.getTeacherId(),
                assignment == null ? null : assignment.getSubjectId(), day, null);
    }

    @Transactional
    public TimetableDraftSlot move(String scheduleId, String slotId,
                                   MoveDraftSlotRequest request) {
        TimetableSchedule schedule = requireDraft(scheduleId);
        TimetableDraftSlot slot = draftSlots.findById(slotId)
                .filter(item -> scheduleId.equals(item.getScheduleId()))
                .orElseThrow(() -> ApiException.notFound("Tiết học trong bản nháp"));
        if ("FIXED_ACTIVITY".equals(slot.getSource())
                || "AUTO_BLOCK".equals(slot.getSource())) {
            throw ApiException.conflict(
                    "Tiết cố định và block ba tiết không thể kéo riêng lẻ");
        }
        String day = request.dayOfWeek().trim().toUpperCase(Locale.ROOT);
        if (!List.of(schedule.getTeachingDays().split(",")).contains(day)) {
            throw ApiException.badRequest("Ngày đã chọn không thuộc ca học của bản lịch");
        }
        if (request.periodNo() < schedule.getFirstPeriod()
                || request.periodNo() > schedule.getLastPeriod()) {
            throw ApiException.badRequest("Tiết học nằm ngoài ca học đã cấu hình");
        }
        Room room = structure.getRoom(request.roomId());
        if (!room.isActive()) throw ApiException.badRequest("Phòng học đã ngừng sử dụng");
        SchoolClass schoolClass = structure.getClass(slot.getClassId());
        String requiredType = roomType(slot.getRequiredRoomType());
        if ("GENERAL".equals(requiredType)
                && schoolClass.getHomeRoomId() != null
                && !schoolClass.getHomeRoomId().equals(room.getId())) {
            throw ApiException.conflict("Môn học thường phải học tại phòng cố định của lớp");
        }
        if (!"GENERAL".equals(requiredType)
                && !requiredType.equals(roomType(room.getRoomType()))) {
            throw ApiException.conflict("Phòng không đúng loại yêu cầu của môn học");
        }
        if (room.getCapacity() != null
                && room.getCapacity() < schoolClass.getStudentCount()) {
            throw ApiException.conflict("Phòng không đủ sức chứa lớp");
        }
        List<TimetableDraftSlot> rows = draftSlots
                .findByScheduleIdOrderByClassIdAscDayOfWeekAscPeriodNoAsc(scheduleId);
        for (TimetableDraftSlot other : rows) {
            if (other.getId().equals(slotId)
                    || !day.equals(other.getDayOfWeek())
                    || request.periodNo() != other.getPeriodNo()) continue;
            if (slot.getClassId().equals(other.getClassId()))
                throw ApiException.conflict("Lớp đã có tiết học tại vị trí này");
            if (slot.getTeacherId().equals(other.getTeacherId()))
                throw ApiException.conflict("Giáo viên đang dạy lớp khác tại vị trí này");
            if (room.getId().equals(other.getRoomId()))
                throw ApiException.conflict("Phòng đang được sử dụng tại vị trí này");
        }
        Set<String> targetClasses = rows.stream()
                .map(TimetableDraftSlot::getClassId).collect(Collectors.toSet());
        for (TimetableSlot live : liveSlots.findBySemesterId(schedule.getSemesterId())) {
            if (targetClasses.contains(live.getClassId())
                    || !day.equals(live.getDayOfWeek())
                    || request.periodNo() != live.getPeriodNo()) continue;
            if (slot.getTeacherId().equals(live.getTeacherId())) {
                throw ApiException.conflict(
                        "Giáo viên đang có tiết ở lớp ngoài phạm vi bản nháp");
            }
            if (room.getCode().equalsIgnoreCase(live.getRoomCode())) {
                throw ApiException.conflict(
                        "Phòng đang được dùng bởi lớp ngoài phạm vi bản nháp");
            }
        }
        long dayCount = rows.stream()
                .filter(item -> !item.getId().equals(slotId))
                .filter(item -> slot.getClassId().equals(item.getClassId()))
                .filter(item -> day.equals(item.getDayOfWeek())).count();
        if (dayCount >= schedule.getMaxPeriodsPerDay()) {
            throw ApiException.conflict("Lớp đã đạt số tiết tối đa trong ngày");
        }
        String[] time = periodTime(request.periodNo());
        slot.setDayOfWeek(day);
        slot.setPeriodNo(request.periodNo());
        slot.setRoomId(room.getId());
        slot.setRoomCode(room.getCode());
        slot.setStartTime(time[0]);
        slot.setEndTime(time[1]);
        slot.setSource("MOVED");
        slot.setPinned(true);
        slot.setUpdatedAt(Instant.now());
        TimetableDraftSlot saved = draftSlots.save(slot);
        ScheduleValidation validation = validate(scheduleId);
        if (!validation.valid()) {
            throw ApiException.conflict("Không thể lưu thay đổi vì lịch phát sinh "
                    + validation.errorCount() + " vi phạm bắt buộc. Hãy chọn vị trí khác.");
        }
        return saved;
    }

    @Transactional
    public TimetableSchedule publish(String scheduleId, String actorId) {
        TimetableSchedule schedule = requireDraft(scheduleId);
        ScheduleValidation validation = validate(scheduleId);
        if (!validation.valid()) {
            throw ApiException.conflict("Bản lịch còn " + validation.errorCount()
                    + " lỗi bắt buộc; hãy xử lý trước khi phát hành");
        }
        List<TimetableDraftSlot> rows = draftSlots
                .findByScheduleIdOrderByClassIdAscDayOfWeekAscPeriodNoAsc(scheduleId);
        List<String> classIds = rows.stream().map(TimetableDraftSlot::getClassId)
                .distinct().toList();
        if (classIds.isEmpty()) throw ApiException.badRequest("Bản lịch chưa có tiết học");

        Instant now = Instant.now();
        schedules.findBySemesterIdAndStatus(schedule.getSemesterId(), "PUBLISHED")
                .stream()
                .filter(previous -> overlapsScope(previous, schedule))
                .forEach(previous -> {
                    previous.setStatus("LOCKED");
                    previous.setLockedAt(now);
                    previous.setUpdatedAt(now);
                    schedules.save(previous);
                });
        liveSlots.deleteBySemesterIdAndClassIdIn(schedule.getSemesterId(), classIds);
        // The live timetable has a unique class/semester/day/period key. Force
        // replacement deletes to reach PostgreSQL before Hibernate queues inserts.
        liveSlots.flush();
        liveSlots.saveAll(rows.stream().map(row -> TimetableSlot.builder()
                .id(Ids.gen("tt"))
                .classId(row.getClassId()).subjectId(row.getSubjectId())
                .subjectName(row.getSubjectName())
                .teacherId(row.getTeacherId()).teacherName(row.getTeacherName())
                .roomCode(row.getRoomCode()).dayOfWeek(row.getDayOfWeek())
                .periodNo(row.getPeriodNo()).startTime(row.getStartTime())
                .endTime(row.getEndTime()).semesterId(row.getSemesterId())
                .sourceScheduleId(schedule.getId()).build()).toList());
        schedule.setStatus("PUBLISHED");
        schedule.setPublishedAt(now);
        schedule.setPublishedBy(actorId);
        schedule.setUpdatedAt(now);
        TimetableSchedule saved = schedules.save(schedule);

        Map<String, List<TimetableDraftSlot>> byClass = rows.stream()
                .collect(Collectors.groupingBy(TimetableDraftSlot::getClassId));
        byClass.forEach((classId, classRows) -> events.publish(
                "academic.timetable.published", actorId,
                "timetable_schedule", saved.getId(),
                Map.of("classId", classId,
                        "teacherIds", classRows.stream()
                                .map(TimetableDraftSlot::getTeacherId)
                                .distinct().toList(),
                        "semesterId", saved.getSemesterId(),
                        "message", "Nhà trường đã phát hành thời khóa biểu mới.")));
        return saved;
    }

    private boolean overlapsScope(TimetableSchedule current,
                                  TimetableSchedule replacement) {
        return current.getScopeGradeLevel() == null
                || replacement.getScopeGradeLevel() == null
                || current.getScopeGradeLevel().equals(
                        replacement.getScopeGradeLevel());
    }

    private TimetableSchedule requireDraft(String id) {
        TimetableSchedule schedule = getSchedule(id);
        if (!"DRAFT".equals(schedule.getStatus())) {
            throw ApiException.conflict("Chỉ bản nháp mới được chỉnh sửa hoặc phát hành");
        }
        return schedule;
    }

    private TimetableDraftSlot toDraftSlot(TimetableSchedule schedule,
            Semester semester, AutoLesson item, Instant now) {
        if (item.getTimeslot() == null || item.getRoom() == null) {
            throw ApiException.conflict("Bộ giải chưa gán đủ thời gian hoặc phòng học");
        }
        return TimetableDraftSlot.builder()
                .id(Ids.gen("ttd")).scheduleId(schedule.getId())
                .assignmentId(item.getAssignmentId())
                .classId(item.getClassId()).subjectId(item.getSubjectId())
                .subjectName(item.getSubjectName())
                .teacherId(item.getTeacherId()).teacherName(item.getTeacherName())
                .roomId(item.getRoom().getId()).roomCode(item.getRoom().getCode())
                .requiredRoomType(item.getRequiredRoomType())
                .dayOfWeek(item.getTimeslot().getDayOfWeek())
                .periodNo(item.getTimeslot().getPeriodNo())
                .startTime(item.getTimeslot().getStartTime())
                .endTime(item.getTimeslot().getEndTime())
                .semesterId(semester.getId()).lessonIndex(item.getLessonIndex())
                .source(item.isActivity() ? "FIXED_ACTIVITY"
                        : item.isBlockLesson() ? "AUTO_BLOCK" : "AUTO")
                .pinned(false).createdAt(now).updatedAt(now).build();
    }

    private void validateAssignments(List<SchoolClass> classes,
            List<TeacherClassSubject> rows,
            List<TimetablePlanSourceService.PlanSnapshot> sourceSnapshots,
            int capacity, String semesterId) {
        Map<String, SchoolClass> classById = classes.stream()
                .collect(Collectors.toMap(SchoolClass::getId, Function.identity()));
        Map<String, Integer> totals = new HashMap<>();
        for (TeacherClassSubject row : rows) {
            SchoolClass schoolClass = classById.get(row.getClassId());
            totals.merge(row.getClassId(),
                    plannedWeeklyPeriods(sourceSnapshots, schoolClass, row),
                    Integer::sum);
        }
        List<String> issues = new ArrayList<>();
        for (SchoolClass schoolClass : classes) {
            int total = totals.getOrDefault(schoolClass.getId(), 0);
            if (total == 0) issues.add(schoolClass.getCode() + " chưa có phân công");
            if (total > capacity) issues.add(schoolClass.getCode()
                    + " cần " + total + " tiết nhưng ca học chỉ có " + capacity);
        }
        if (!issues.isEmpty()) {
            throw ApiException.badRequest("Không thể tạo lịch " + semesterId
                    + ": " + String.join("; ", issues));
        }
    }

    private void assertSpecialty(User teacher, Subject subject,
                                 SchoolClass schoolClass) {
        String specialty = normalizeText(teacher.getMainSubject());
        Set<String> accepted = Set.of(normalizeText(subject.getId()),
                normalizeText(subject.getCode()), normalizeText(subject.getName()));
        boolean matches = accepted.contains(specialty)
                || accepted.stream().anyMatch(value -> value.length() >= 3
                    && specialty.length() >= 3
                    && (value.contains(specialty) || specialty.contains(value)));
        if (!matches) {
            throw ApiException.badRequest("Giáo viên " + teacher.getFullName()
                    + " không đúng chuyên môn " + subject.getName()
                    + " của lớp " + schoolClass.getCode());
        }
    }

    private List<AutoTimeslot> buildTimeslots(List<String> days,
                                               int first, int last) {
        List<AutoTimeslot> result = new ArrayList<>();
        for (String day : days) {
            for (int period = first; period <= last; period++) {
                String[] time = periodTime(period);
                result.add(new AutoTimeslot(day + "-" + period, day, period,
                        time[0], time[1]));
            }
        }
        return result;
    }

    private List<String> normalizeDays(List<String> requested) {
        List<String> days = requested == null || requested.isEmpty()
                ? List.of("MON", "TUE", "WED", "THU", "FRI")
                : requested.stream().map(value -> value.trim().toUpperCase(Locale.ROOT))
                    .distinct().sorted(Comparator.comparingInt(VALID_DAYS::indexOf)).toList();
        if (days.isEmpty() || days.stream().anyMatch(day -> !VALID_DAYS.contains(day))) {
            throw ApiException.badRequest("Ngày học không hợp lệ");
        }
        return days;
    }

    private String normalizeGrade(String value) {
        if (value == null || value.isBlank() || "ALL".equalsIgnoreCase(value)) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (normalized.matches("10|11|12")) normalized = "K" + normalized;
        if (!Set.of("K10", "K11", "K12").contains(normalized)) {
            throw ApiException.badRequest("Khối không hợp lệ");
        }
        return normalized;
    }

    private int between(Integer value, int min, int max, int defaultValue,
                        String label) {
        int result = value == null ? defaultValue : value;
        if (result < min || result > max) {
            throw ApiException.badRequest(label + " phải từ " + min + " đến " + max);
        }
        return result;
    }

    private List<AutoRoom> eligibleRooms(
            SchoolClass schoolClass, String requiredType, String subjectName,
            List<Room> availableRooms,
            Map<String, AutoRoom> solverRoomById) {
        List<AutoRoom> eligible = availableRooms.stream()
                .filter(room -> {
                    if (room.getCapacity() != null
                            && room.getCapacity() < schoolClass.getStudentCount()) {
                        return false;
                    }
                    if ("GENERAL".equals(requiredType)) {
                        return room.getId().equals(schoolClass.getHomeRoomId());
                    }
                    return requiredType.equals(roomType(room.getRoomType()));
                })
                .map(room -> solverRoomById.get(room.getId()))
                .toList();
        if (eligible.isEmpty()) {
            String expected = "GENERAL".equals(requiredType)
                    ? "phòng cố định của lớp"
                    : "phòng loại " + requiredType;
            throw ApiException.badRequest("Lớp " + schoolClass.getCode()
                    + " không có " + expected + " đủ sức chứa cho môn "
                    + subjectName);
        }
        return eligible;
    }

    private void assertSpecializedRoomCapacity(
            List<AutoLesson> lessons, List<AutoTimeslot> timeslots,
            List<AutoRoom> rooms) {
        Set<String> blocked = lessons.stream()
                .filter(AutoLesson::isPinned)
                .filter(item -> item.getRoom() != null && item.getTimeslot() != null)
                .map(item -> item.getRoom().getId() + "|" + item.getTimeslot().getId())
                .collect(Collectors.toSet());
        Map<String, List<AutoLesson>> demandByType = lessons.stream()
                .filter(item -> !item.isPinned())
                .filter(item -> !"GENERAL".equals(item.getRequiredRoomType()))
                .collect(Collectors.groupingBy(AutoLesson::getRequiredRoomType));

        for (Map.Entry<String, List<AutoLesson>> entry : demandByType.entrySet()) {
            String type = entry.getKey();
            List<Integer> capacities = new ArrayList<>();
            rooms.stream().filter(room -> type.equals(room.getRoomType())).forEach(room ->
                    timeslots.stream()
                            .filter(timeslot -> !blocked.contains(
                                    room.getId() + "|" + timeslot.getId()))
                            .forEach(timeslot -> capacities.add(room.getCapacity())));
            capacities.sort(Integer::compareTo);
            List<AutoLesson> demand = entry.getValue().stream()
                    .sorted(Comparator.comparingInt(AutoLesson::getStudentCount).reversed())
                    .toList();
            int matched = 0;
            for (AutoLesson lesson : demand) {
                int slotIndex = -1;
                for (int index = 0; index < capacities.size(); index++) {
                    if (capacities.get(index) >= lesson.getStudentCount()) {
                        slotIndex = index;
                        break;
                    }
                }
                if (slotIndex < 0) break;
                capacities.remove(slotIndex);
                matched++;
            }
            if (matched < demand.size()) {
                int shortage = demand.size() - matched;
                throw ApiException.conflict("Không đủ phòng " + type + " còn trống: cần "
                        + demand.size() + " tiết, chỉ xếp được " + matched
                        + ", thiếu " + shortage
                        + " tiết. Hãy thêm phòng, giảm số tiết dùng phòng chuyên dụng hoặc đổi phạm vi lịch.");
            }
        }
    }

    private void assertTeacherCapacity(List<AutoLesson> lessons, int teachingDayCount) {
        if (teachingDayCount < 2) return;
        int weeklyCapacity = (teachingDayCount - 1) * 5;
        Map<String, List<AutoLesson>> byTeacher = lessons.stream()
                .collect(Collectors.groupingBy(AutoLesson::getTeacherId));
        List<String> overloaded = byTeacher.values().stream()
                .filter(items -> items.stream().anyMatch(item -> !item.isPinned()))
                .filter(items -> items.size() > weeklyCapacity)
                .map(items -> items.get(0).getTeacherName() + " cần " + items.size()
                        + " tiết/tuần, tối đa " + weeklyCapacity)
                .sorted().toList();
        if (!overloaded.isEmpty()) {
            throw ApiException.conflict("Chưa thể tạo lịch vì tải giảng dạy vượt giới hạn "
                    + "5 tiết/ngày và phải có 1 ngày nghỉ: " + String.join("; ", overloaded)
                    + ". Hãy phân công thêm giáo viên hoặc giảm số tiết.");
        }
    }

    private String describeHardViolations(AutoTimetable solution) {
        List<AutoLesson> lessons = solution.getLessons();
        Set<String> targetTeachers = lessons.stream().filter(item -> !item.isPinned())
                .map(AutoLesson::getTeacherId).collect(Collectors.toSet());
        Map<String, Integer> counts = new LinkedHashMap<>();
        countDuplicateGroups(counts, "trùng lớp", lessons.stream().collect(Collectors.groupingBy(
                item -> item.getClassId() + "|" + item.getTimeslot().getId(), Collectors.counting())));
        countDuplicateGroups(counts, "trùng giáo viên", lessons.stream().collect(Collectors.groupingBy(
                item -> item.getTeacherId() + "|" + item.getTimeslot().getId(), Collectors.counting())));
        countDuplicateGroups(counts, "trùng phòng", lessons.stream().collect(Collectors.groupingBy(
                item -> item.getRoom().getId() + "|" + item.getTimeslot().getId(), Collectors.counting())));

        int roomRules = (int) lessons.stream().filter(item ->
                item.getRoom() == null
                || item.getRoom().getCapacity() < item.getStudentCount()
                || (!"GENERAL".equals(item.getRequiredRoomType())
                    && !item.getRequiredRoomType().equals(item.getRoom().getRoomType()))
                || ("GENERAL".equals(item.getRequiredRoomType())
                    && item.getHomeRoomId() != null
                    && !item.getHomeRoomId().equals(item.getRoom().getId()))).count();
        if (roomRules > 0) counts.put("sai phòng", roomRules);

        Map<String, Long> teacherDaily = lessons.stream()
                .filter(item -> targetTeachers.contains(item.getTeacherId()))
                .collect(Collectors.groupingBy(item -> item.getTeacherId() + "|"
                        + item.getTimeslot().getDayOfWeek(), Collectors.counting()));
        int teacherOverload = teacherDaily.values().stream()
                .mapToInt(value -> Math.max(0, value.intValue() - 5)).sum();
        if (teacherOverload > 0) counts.put("quá 5 tiết giáo viên/ngày", teacherOverload);

        int restDay = (int) lessons.stream().filter(item -> !item.isPinned()
                && item.getTeacherRestDay() != null
                && item.getTeacherRestDay().equals(item.getTimeslot().getDayOfWeek())).count();
        if (restDay > 0) counts.put("trùng ngày nghỉ giáo viên", restDay);

        Map<String, List<AutoLesson>> heavyByClassDay = lessons.stream()
                .filter(AutoLesson::isHeavySubject)
                .collect(Collectors.groupingBy(item -> item.getClassId() + "|"
                        + item.getTimeslot().getDayOfWeek()));
        int heavy = heavyByClassDay.values().stream()
                .mapToInt(items -> Math.max(0,
                        com.sse.app.academic.timetable.solver.AutoTimetableConstraintProvider
                                .longestConsecutiveRun(items) - 3)).sum();
        if (heavy > 0) counts.put("chuỗi môn nặng", heavy);
        return counts.isEmpty() ? "" : ": " + counts.entrySet().stream()
                .map(entry -> entry.getKey() + " " + entry.getValue())
                .collect(Collectors.joining(", "));
    }

    private void countDuplicateGroups(Map<String, Integer> result, String label,
                                      Map<String, Long> grouped) {
        int violations = grouped.values().stream().mapToInt(count ->
                count < 2 ? 0 : (int) (count * (count - 1) / 2)).sum();
        if (violations > 0) result.put(label, violations);
    }

    private Map<String, BlockPlacement> planBlockPlacements(
            List<SchoolClass> classes,
            List<TeacherClassSubject> teachingAssignments,
            List<Room> rooms,
            List<TimetablePlanSourceService.PlanSnapshot> sourceSnapshots) {
        Map<String, TeacherClassSubject> byClassSubject = teachingAssignments.stream()
                .collect(Collectors.toMap(item -> item.getClassId() + "|"
                                + item.getSubjectId(), Function.identity(),
                        (left, right) -> left));
        List<BlockRequest> requests = new ArrayList<>();
        for (SchoolClass schoolClass : classes.stream()
                .sorted(Comparator.comparing(SchoolClass::getCode)).toList()) {
            List<TeacherClassSubject> selected = selectBlockAssignments(
                    schoolClass, byClassSubject, teachingAssignments, sourceSnapshots);
            if (selected.isEmpty()) continue;
            boolean afternoonMain = isAfternoonMain(schoolClass);
            requests.add(new BlockRequest(schoolClass, selected,
                    afternoonMain
                            ? List.of("TUE", "THU", "SAT")
                            : List.of("MON", "WED", "FRI"),
                    afternoonMain ? 1 : 6));
        }
        Map<String, Integer> roomCapacity = rooms.stream()
                .collect(Collectors.groupingBy(
                        room -> roomType(room.getRoomType()),
                        Collectors.summingInt(room -> 1)));
        Map<String, BlockPlacement> result = new HashMap<>();
        if (!placeBlockRequest(0, requests, result, new HashSet<>(),
                new HashMap<>(), roomCapacity)) {
            throw ApiException.conflict("Không thể phân ba buổi học đối ca mà không "
                    + "trùng giáo viên hoặc phòng chuyên dụng. Hãy bổ sung giáo viên/phòng.");
        }
        return result;
    }

    private int plannedWeeklyPeriods(
            List<TimetablePlanSourceService.PlanSnapshot> sourceSnapshots,
            SchoolClass schoolClass, TeacherClassSubject assignment) {
        if (schoolClass == null) {
            throw ApiException.badRequest("Phân công không còn thuộc lớp hợp lệ");
        }
        if (sourceSnapshots == null || sourceSnapshots.isEmpty()) {
            return Math.max(1, assignment.getWeeklyPeriods() == null
                    ? 1 : assignment.getWeeklyPeriods());
        }
        return Math.max(1, planSources.weeklyPeriods(sourceSnapshots,
                schoolClass.getGradeLevel(), assignment.getSubjectId()));
    }

    private boolean placeBlockRequest(
            int index, List<BlockRequest> requests,
            Map<String, BlockPlacement> result,
            Set<String> teacherDays,
            Map<String, Integer> roomUse,
            Map<String, Integer> roomCapacity) {
        if (index >= requests.size()) return true;
        BlockRequest request = requests.get(index);
        for (int[] order : blockOrders(request.assignments().size())) {
            for (int[] dayOrder : blockDayOrders(request.assignments().size())) {
            List<String> teacherKeys = new ArrayList<>();
            List<String> roomKeys = new ArrayList<>();
            boolean valid = true;
            for (int dayIndex = 0; dayIndex < request.assignments().size(); dayIndex++) {
                TeacherClassSubject assignment = request.assignments().get(order[dayIndex]);
                String day = request.days().get(dayOrder[dayIndex]);
                String teacherKey = assignment.getTeacherId() + "|" + day
                        + "|" + request.startPeriod();
                if (teacherDays.contains(teacherKey)) { valid = false; break; }
                String roomType = roomType(structure.getSubject(
                        assignment.getSubjectId()).getRequiredRoomType());
                String roomKey = roomType + "|" + day + "|" + request.startPeriod();
                if (!"GENERAL".equals(roomType)
                        && roomUse.getOrDefault(roomKey, 0) + 1
                        > roomCapacity.getOrDefault(roomType, 0)) {
                    valid = false; break;
                }
                teacherKeys.add(teacherKey);
                roomKeys.add(roomKey);
            }
            if (!valid) continue;
            for (int dayIndex = 0; dayIndex < request.assignments().size(); dayIndex++) {
                TeacherClassSubject assignment = request.assignments().get(order[dayIndex]);
                String day = request.days().get(dayOrder[dayIndex]);
                result.put(assignment.getId(),
                        new BlockPlacement(day, request.startPeriod()));
                teacherDays.add(teacherKeys.get(dayIndex));
                String roomKey = roomKeys.get(dayIndex);
                if (!roomKey.startsWith("GENERAL|")) {
                    roomUse.merge(roomKey, 1, Integer::sum);
                }
            }
            if (placeBlockRequest(index + 1, requests, result,
                    teacherDays, roomUse, roomCapacity)) return true;
            for (int dayIndex = 0; dayIndex < request.assignments().size(); dayIndex++) {
                TeacherClassSubject assignment = request.assignments().get(order[dayIndex]);
                result.remove(assignment.getId());
                teacherDays.remove(teacherKeys.get(dayIndex));
                String roomKey = roomKeys.get(dayIndex);
                if (!roomKey.startsWith("GENERAL|")) {
                    roomUse.computeIfPresent(roomKey,
                            (key, count) -> count <= 1 ? null : count - 1);
                }
            }
            }
        }
        return false;
    }

    private List<TeacherClassSubject> selectBlockAssignments(
            SchoolClass schoolClass,
            Map<String, TeacherClassSubject> byClassSubject,
            List<TeacherClassSubject> teachingAssignments,
            List<TimetablePlanSourceService.PlanSnapshot> sourceSnapshots) {
        List<TeacherClassSubject> preferred = blockSubjectIds(schoolClass.getCode())
                .stream().map(subjectId -> byClassSubject.get(
                        schoolClass.getId() + "|" + subjectId))
                .filter(Objects::nonNull)
                .filter(item -> plannedWeeklyPeriods(
                        sourceSnapshots, schoolClass, item) >= 3)
                .limit(3).toList();
        if (!preferred.isEmpty()) return preferred;
        return teachingAssignments.stream()
                .filter(item -> schoolClass.getId().equals(item.getClassId()))
                .filter(item -> plannedWeeklyPeriods(
                        sourceSnapshots, schoolClass, item) >= 3)
                .sorted(Comparator.comparing(TeacherClassSubject::getSubjectId))
                .limit(3).toList();
    }

    private List<int[]> blockOrders(int size) {
        return switch (size) {
            case 1 -> List.of(new int[]{0});
            case 2 -> List.of(new int[]{0, 1}, new int[]{1, 0});
            case 3 -> List.of(
                    new int[]{0, 1, 2}, new int[]{0, 2, 1},
                    new int[]{1, 0, 2}, new int[]{1, 2, 0},
                    new int[]{2, 0, 1}, new int[]{2, 1, 0});
            default -> List.of();
        };
    }

    private List<int[]> blockDayOrders(int size) {
        return switch (size) {
            case 1 -> List.of(new int[]{0}, new int[]{1}, new int[]{2});
            case 2 -> List.of(
                    new int[]{0, 1}, new int[]{0, 2},
                    new int[]{1, 0}, new int[]{1, 2},
                    new int[]{2, 0}, new int[]{2, 1});
            case 3 -> blockOrders(3);
            default -> List.of();
        };
    }

    private List<String> blockSubjectIds(String classCode) {
        int number = classNumber(classCode);
        return switch (number) {
            case 1 -> List.of("sj-phys", "sj-chem", "sj-bio");
            case 2 -> List.of("sj-math", "sj-phys", "sj-chem");
            case 3 -> List.of("sj-eng", "sj-bio", "sj-chem");
            case 4 -> List.of("sj-math", "sj-phys", "sj-bio");
            case 5 -> List.of("sj-eng", "sj-chem", "sj-bio");
            default -> List.of("sj-math", "sj-lit", "sj-eng");
        };
    }

    private Map<String, String> policyTeacherRestDays(
            List<TeacherClassSubject> teachingAssignments,
            List<SchoolClass> classes,
            Map<String, BlockPlacement> blockPlan,
            List<String> teachingDays) {
        Map<String, Set<String>> unavailable = new HashMap<>();
        for (TeacherClassSubject assignment : teachingAssignments) {
            BlockPlacement placement = blockPlan.get(assignment.getId());
            if (placement != null) {
                unavailable.computeIfAbsent(assignment.getTeacherId(), key -> new HashSet<>())
                        .add(placement.dayOfWeek());
            }
        }
        for (SchoolClass schoolClass : classes) {
            if (schoolClass.getHomeroomTeacherId() != null) {
                Set<String> fixedDays = unavailable.computeIfAbsent(
                        schoolClass.getHomeroomTeacherId(), key -> new HashSet<>());
                fixedDays.add("MON");
                fixedDays.add("SAT");
            }
        }
        List<String> teachers = teachingAssignments.stream()
                .map(TeacherClassSubject::getTeacherId).distinct().sorted().toList();
        Map<String, String> result = new HashMap<>();
        for (int index = 0; index < teachers.size(); index++) {
            String teacherId = teachers.get(index);
            Set<String> blocked = unavailable.getOrDefault(teacherId, Set.of());
            List<String> choices = teachingDays.stream()
                    .filter(day -> !blocked.contains(day)).toList();
            if (choices.isEmpty()) {
                throw ApiException.conflict("Giáo viên " + users.getById(teacherId).getFullName()
                        + " đã có block học trong cả sáu ngày, không còn ngày nghỉ");
            }
            result.put(teacherId, choices.get(index % choices.size()));
        }
        return result;
    }

    private void ensureActivityAssignments(List<SchoolClass> classes,
                                           Semester semester) {
        Instant now = Instant.now();
        List<TeacherClassSubject> systemAssignments = new ArrayList<>();
        for (SchoolClass schoolClass : classes) {
            if (schoolClass.getHomeroomTeacherId() == null
                    || schoolClass.getHomeroomTeacherId().isBlank()) {
                throw ApiException.badRequest("Lớp " + schoolClass.getCode()
                        + " chưa có giáo viên chủ nhiệm");
            }
            User homeroom = users.getById(schoolClass.getHomeroomTeacherId());
            systemAssignments.add(systemActivityAssignment(
                    "FLAG", "sj-flag", "Chào cờ", schoolClass,
                    semester, homeroom, now));
            systemAssignments.add(systemActivityAssignment(
                    "HOMEROOM", "sj-homeroom", "Sinh hoạt lớp", schoolClass,
                    semester, homeroom, now));
        }
        assignments.saveAll(systemAssignments);
        assignments.flush();
    }

    private TeacherClassSubject systemActivityAssignment(
            String code, String subjectId, String subjectName,
            SchoolClass schoolClass, Semester semester,
            User teacher, Instant now) {
        String id = activityAssignmentId(code, schoolClass.getId(), semester.getId());
        TeacherClassSubject assignment = assignments.findById(id)
                .orElseGet(() -> TeacherClassSubject.builder()
                        .id(id).createdAt(now).build());
        assignment.setTeacherId(teacher.getId());
        assignment.setTeacherName(teacher.getFullName());
        assignment.setClassId(schoolClass.getId());
        assignment.setClassCode(schoolClass.getCode());
        assignment.setSubjectId(subjectId);
        assignment.setSubjectName(subjectName);
        assignment.setSemesterId(semester.getId());
        assignment.setWeeklyPeriods(1);
        assignment.setSpecializedRoomPeriods(0);
        assignment.setStatus("SYSTEM");
        assignment.setUpdatedAt(now);
        return assignment;
    }

    private String activityAssignmentId(String code, String classId,
                                        String semesterId) {
        return "activity-" + code + "-" + classId + "-" + semesterId;
    }

    private AutoLesson fixedActivity(
            SchoolClass schoolClass,
            Map<String, AutoTimeslot> timeslotByKey,
            Map<String, AutoRoom> roomById,
            String code, String assignmentId,
            String subjectId, String subjectName,
            String day, int period, String teacherId, String teacherName,
            String restDay, int maxDaily) {
        AutoLesson activity = new AutoLesson(
                assignmentId + "-1", assignmentId,
                schoolClass.getId(), subjectId, subjectName,
                teacherId, teacherName, "GENERAL", schoolClass.getHomeRoomId(),
                "OTHER", false, false, false, 6, restDay,
                Math.max(1, schoolClass.getStudentCount()), 1, maxDaily, false);
        AutoTimeslot timeslot = timeslotByKey.get(day + "-" + period);
        AutoRoom room = roomById.get(schoolClass.getHomeRoomId());
        activity.setTimeslotRange(List.of(timeslot));
        activity.setRoomRange(List.of(room));
        activity.setActivity(true);
        activity.setMainShiftStartPeriod(mainShiftStart(schoolClass));
        return activity;
    }

    private boolean isAfternoonMain(SchoolClass schoolClass) {
        return "K10".equals(schoolClass.getGradeLevel())
                || ("K11".equals(schoolClass.getGradeLevel())
                    && classNumber(schoolClass.getCode()) <= 5);
    }

    private int mainShiftStart(SchoolClass schoolClass) {
        return isAfternoonMain(schoolClass) ? 6 : 1;
    }

    private int classNumber(String classCode) {
        if (classCode == null) return 99;
        int marker = classCode.toUpperCase(Locale.ROOT).lastIndexOf('A');
        if (marker < 0 || marker == classCode.length() - 1) return 99;
        try { return Integer.parseInt(classCode.substring(marker + 1)); }
        catch (NumberFormatException ignored) { return 99; }
    }

    private record BlockRequest(SchoolClass schoolClass,
                                List<TeacherClassSubject> assignments,
                                List<String> days, int startPeriod) {}
    private record BlockPlacement(String dayOfWeek, int startPeriod) {}

    private Map<String, String> balancedTeacherRestDaysByGrade(
            List<TeacherClassSubject> teachingAssignments,
            Map<String, SchoolClass> classById,
            List<String> teachingDays) {
        if (teachingDays.size() < 2) return Map.of();
        Map<String, String> result = new HashMap<>();
        Map<String, List<String>> teachersByGrade = teachingAssignments.stream()
                .filter(item -> classById.containsKey(item.getClassId()))
                .collect(Collectors.groupingBy(item -> {
                    String grade = classById.get(item.getClassId()).getGradeLevel();
                    return grade == null ? "OTHER" : grade;
                }, Collectors.mapping(TeacherClassSubject::getTeacherId,
                        Collectors.collectingAndThen(Collectors.toSet(), teachers ->
                                teachers.stream().sorted().toList()))));
        for (String grade : teachersByGrade.keySet().stream()
                .sorted(Comparator.comparingInt(this::gradeOrder)).toList()) {
            List<String> sortedTeachers = teachersByGrade.get(grade);
            for (int index = 0; index < sortedTeachers.size(); index++) {
                result.putIfAbsent(sortedTeachers.get(index),
                        teachingDays.get(index % teachingDays.size()));
            }
        }
        return result;
    }

    private String subjectGroup(String... values) {
        String text = normalizedSubject(values);
        if (containsAny(text, "toan", "vatly", "physics", "hoahoc", "chemistry",
                "sinhhoc", "biology", "tinhoc", "informatics", "congnghe")) {
            return "NATURAL";
        }
        if (containsAny(text, "nguvan", "literature", "lichsu", "history", "dialy",
                "geography", "gdkt", "gdcd", "quocphong", "gdqp")) {
            return "SOCIAL";
        }
        if (containsAny(text, "tienganh", "english")) return "LANGUAGE";
        if (containsAny(text, "theduc", "giaoducthechat", "music", "amnhac", "mythuat")) {
            return "TALENT";
        }
        return "OTHER";
    }

    private boolean isHeavySubject(String... values) {
        return containsAny(normalizedSubject(values), "toan", "math", "vatly", "physics",
                "hoahoc", "chemistry");
    }

    private boolean isMorningPriority(String... values) {
        return containsAny(normalizedSubject(values), "toan", "math", "nguvan", "literature",
                "tienganh", "english");
    }

    private boolean isLatePriority(String... values) {
        return containsAny(normalizedSubject(values), "theduc", "giaoducthechat", "gdqp",
                "quocphong", "congnghe", "technology");
    }

    private String normalizedSubject(String... values) {
        return java.util.Arrays.stream(values).map(this::normalizeText)
                .collect(Collectors.joining("|"));
    }

    private boolean containsAny(String value, String... needles) {
        return java.util.Arrays.stream(needles).anyMatch(value::contains);
    }

    private int longestConsecutivePeriods(List<Integer> periods) {
        List<Integer> sorted = periods.stream().distinct().sorted().toList();
        int longest = 0;
        int current = 0;
        int previous = Integer.MIN_VALUE;
        for (int period : sorted) {
            current = period == previous + 1 ? current + 1 : 1;
            longest = Math.max(longest, current);
            previous = period;
        }
        return longest;
    }

    private int internalGapCount(List<Integer> periods) {
        List<Integer> sorted = periods.stream().distinct().sorted().toList();
        if (sorted.size() < 2) return 0;
        return sorted.get(sorted.size() - 1) - sorted.get(0) + 1 - sorted.size();
    }

    private String roomType(String value) {
        return value == null || value.isBlank()
                ? "GENERAL" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeText(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replaceAll("[^a-zA-Z0-9]", "")
                .toLowerCase(Locale.ROOT);
    }

    private String[] periodTime(int period) {
        return PERIOD_TIMES.getOrDefault(period,
                new String[]{"", ""});
    }

    private ScheduleIssue issue(String level, String code, String message,
                                TimetableDraftSlot row) {
        return new ScheduleIssue(level, code, message, row.getClassId(),
                row.getTeacherId(), row.getSubjectId(), row.getDayOfWeek(),
                row.getPeriodNo());
    }
}
