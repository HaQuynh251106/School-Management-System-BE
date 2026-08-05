package com.sse.app.academic.timetable;

import com.sse.app.academic.structure.SchoolClass;
import com.sse.app.academic.structure.Room;
import com.sse.app.academic.structure.SubjectRoomRequirement;
import com.sse.app.academic.structure.SubjectRoomRequirementService;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.academic.timetable.TimetableDtos.*;
import com.sse.app.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.*;

@Service
public class AutomaticTimetableService {
    // Giới hạn theo số nhánh là chốt an toàn chính. Thời gian chỉ là chốt phụ để
    // tránh việc cùng một dữ liệu cho kết quả khác nhau khi CPU tạm thời bận.
    private static final long MAX_SEARCH_NODES = 5_000_000;
    private static final long MAX_SEARCH_MILLIS = 30_000;
    private static final List<String> DAYS = TimetableRulePolicy.OPERATING_DAYS;
    private static final Map<Integer, String[]> MORNING = Map.of(
            1, new String[]{"07:00", "07:45"}, 2, new String[]{"07:50", "08:35"},
            3, new String[]{"08:50", "09:35"}, 4, new String[]{"09:40", "10:25"},
            5, new String[]{"10:35", "11:20"});
    private static final Map<Integer, String[]> AFTERNOON = Map.of(
            1, new String[]{"13:00", "13:45"}, 2, new String[]{"13:50", "14:35"},
            3, new String[]{"14:50", "15:35"}, 4, new String[]{"15:40", "16:25"},
            5, new String[]{"16:35", "17:20"});

    private final TimetableRepository slots;
    private final TeachingAssignmentRepository assignments;
    private final TeacherLoadRegistrationRepository registrations;
    private final StructureService structure;
    private final SubjectRoomRequirementService roomRequirements;
    private final TimetableVersionService versions;
    private final TimetableBusinessRuleService businessRules;
    private final TeacherWorkloadPolicyService workloadPolicies;
    private final TeacherScheduleRestrictionService scheduleRestrictions;

    public AutomaticTimetableService(TimetableRepository slots, TeachingAssignmentRepository assignments,
                                     TeacherLoadRegistrationRepository registrations,
                                     StructureService structure, SubjectRoomRequirementService roomRequirements,
                                     TimetableVersionService versions,
                                     TimetableBusinessRuleService businessRules,
                                     TeacherWorkloadPolicyService workloadPolicies,
                                     TeacherScheduleRestrictionService scheduleRestrictions) {
        this.slots = slots;
        this.assignments = assignments;
        this.registrations = registrations;
        this.structure = structure;
        this.roomRequirements = roomRequirements;
        this.versions = versions;
        this.businessRules = businessRules;
        this.workloadPolicies = workloadPolicies;
        this.scheduleRestrictions = scheduleRestrictions;
    }

    @Transactional
    public AutoTimetablePlan plan(AutoTimetableRequest request) {
        return plan(request, "SYSTEM");
    }

    @Transactional
    public AutoTimetablePlan plan(AutoTimetableRequest request, String actorId) {
        structure.assertSemesterWritable(request.semesterId());
        String strategy = normalizeStrategy(request.strategy());
        List<TimetableSlot> liveSlots = slots.findBySemesterId(request.semesterId());
        boolean rebuildExisting = Boolean.TRUE.equals(request.rebuildExisting());
        List<TimetableSlot> occupied = rebuildExisting ? new ArrayList<>() : new ArrayList<>(liveSlots);
        int existingCount = rebuildExisting ? 0 : occupied.size();
        Map<String, TeacherLoadRegistration> loads = new HashMap<>();
        registrations.findBySemesterId(request.semesterId()).forEach(item -> {
                    workloadPolicies.apply(item);
                    loads.put(item.getTeacherId(), item);
                });

        List<TeachingAssignment> semesterAssignments = assignments.findAll().stream()
                .filter(item -> request.semesterId().equals(item.getSemesterId())).toList();
        semesterAssignments.forEach(item -> loads.computeIfAbsent(item.getTeacherId(), ignored ->
                workloadPolicies.ensureRegistration(item.getTeacherId(), item.getTeacherName(), request.semesterId())));
        Map<String, Set<String>> approvedRestrictions = new HashMap<>();
        semesterAssignments.stream().map(TeachingAssignment::getTeacherId).distinct().forEach(teacherId ->
                approvedRestrictions.put(teacherId, scheduleRestrictions.approvedSlots(teacherId, request.semesterId())));
        Map<String, Integer> teacherDemand = new HashMap<>();
        semesterAssignments.forEach(item -> teacherDemand.merge(
                item.getTeacherId(), item.getWeeklyPeriods(), Integer::sum));
        if (semesterAssignments.isEmpty()) throw ApiException.badRequest("Chưa có phân công bộ môn trong học kỳ đã chọn");
        Map<String, Integer> classDemand = new HashMap<>();
        semesterAssignments.forEach(item -> classDemand.merge(
                item.getClassId(), item.getWeeklyPeriods(), Integer::sum));
        long overloadedClasses = classDemand.values().stream()
                .filter(periods -> periods > TimetableRulePolicy.PERIODS_PER_WEEK).count();
        if (overloadedClasses > 0) {
            throw ApiException.badRequest(overloadedClasses
                    + " lớp đang có tổng định mức vượt 25 tiết/tuần; hãy điều chỉnh định mức và phân công trước");
        }
        List<TeachingAssignment> work = semesterAssignments.stream()
                .filter(item -> occupied.stream().filter(slot -> sameAssignment(slot, item)).count()
                        < item.getWeeklyPeriods())
                // Schedule the teachers with the least spare availability first.
                // This prevents high-load subjects (for example 24 Mathematics
                // periods/week) from being left until every class is almost full.
                .sorted(Comparator.comparingInt((TeachingAssignment item) ->
                                availableCandidateCount(item, loads.get(item.getTeacherId()),
                                        approvedRestrictions.getOrDefault(item.getTeacherId(), Set.of()), occupied)
                                        - teacherDemand.getOrDefault(item.getTeacherId(), 0))
                        .thenComparing(Comparator.comparingInt(TeachingAssignment::getWeeklyPeriods).reversed())
                        .thenComparing(TeachingAssignment::getSubjectName)
                        .thenComparing(TeachingAssignment::getClassCode)).toList();
        if (work.isEmpty()) {
            TimetableRulePolicy.Validation currentValidation = businessRules.validate(occupied.stream()
                    .map(item -> new TimetableRulePolicy.SlotView(item.getId(), item.getClassId(), item.getSubjectId(),
                            item.getTeacherId(), item.getRoomCode(), item.getDayOfWeek(), item.getPeriodNo(),
                            item.getStartTime(), item.getEndTime(), request.semesterId())).toList());
            if (Boolean.TRUE.equals(request.apply())) {
                versions.draftFromSlots(request.semesterId(), rebuildExisting
                        ? "Thời khóa biểu tái tạo toàn bộ" : "Thời khóa biểu tự động", occupied, actorId);
            }
            return new AutoTimetablePlan(request.semesterId(), existingCount, 0,
                    currentValidation.valid() ? 0 : currentValidation.messages().size(),
                    Boolean.TRUE.equals(request.apply()), List.of(), currentValidation.messages(), strategy,
                    currentValidation.valid() ? 100 : 0,
                    strategySummary(strategy));
        }

        List<AutoTimetableItem> result = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<ScheduleNeed> needs = new ArrayList<>();
        List<Room> activeRooms = structure.listRooms().stream()
                .filter(room -> "ACTIVE".equalsIgnoreCase(room.getStatus())).toList();
        Map<String, Room> roomsByCode = new HashMap<>();
        activeRooms.forEach(room -> roomsByCode.put(room.getCode().toUpperCase(Locale.ROOT), room));
        for (TeachingAssignment assignment : work) {
            int already = (int) occupied.stream().filter(slot -> sameAssignment(slot, assignment)).count();
            int missing = Math.max(0, assignment.getWeeklyPeriods() - already);
            if (missing == 0) continue;
            TeacherLoadRegistration load = loads.get(assignment.getTeacherId());
            SchoolClass schoolClass = structure.getClass(assignment.getClassId());
            if (schoolClass.getRoomCode() == null || schoolClass.getRoomCode().isBlank()) {
                String message = "Lớp chưa được gán phòng học";
                for (int index = 0; index < missing; index++)
                    result.add(item(assignment, schoolClass, null, "UNSCHEDULED", message));
                warnings.add(assignment.getClassCode() + " · " + assignment.getSubjectName() + ": " + message);
            } else {
                List<SubjectRoomRequirement> rules = roomRequirements.rulesFor(assignment.getSubjectId());
                SubjectRoomRequirement rule = rules.isEmpty() ? null : rules.get(0);
                int functionalMissing = 0;
                List<Room> functionalRooms = List.of();
                if (rule != null && rule.getWeeklyPeriods() > 0) {
                    int alreadyFunctional = (int) occupied.stream().filter(slot -> sameAssignment(slot, assignment))
                            .map(TimetableSlot::getRoomCode).filter(Objects::nonNull)
                            .map(code -> roomsByCode.get(code.toUpperCase(Locale.ROOT))).filter(Objects::nonNull)
                            .filter(room -> roomMatches(room, rule, schoolClass)).count();
                    functionalMissing = Math.min(missing, Math.max(0, rule.getWeeklyPeriods() - alreadyFunctional));
                    functionalRooms = activeRooms.stream().filter(room -> roomMatches(room, rule, schoolClass))
                            .sorted(Comparator.comparing(Room::getCode)).toList();
                    if (functionalMissing > 0 && functionalRooms.isEmpty()) {
                        String message = "Không có phòng " + roomTypeLabel(rule.getRoomType())
                                + " phù hợp sức chứa, ca học và thiết bị yêu cầu";
                        if (rule.isMandatory()) {
                            for (int index = 0; index < functionalMissing; index++)
                                result.add(item(assignment, schoolClass, null, null, "UNSCHEDULED", message));
                            missing -= functionalMissing;
                        } else message += "; hệ thống chuyển các tiết này về phòng chủ nhiệm";
                        warnings.add(assignment.getClassCode() + " · " + assignment.getSubjectName() + ": " + message);
                        functionalMissing = 0;
                    }
                }
                if (functionalMissing > 0) {
                    needs.add(new ScheduleNeed(assignment, schoolClass, load,
                            approvedRestrictions.getOrDefault(assignment.getTeacherId(), Set.of()), functionalMissing,
                            functionalRooms, "Phòng " + roomTypeLabel(rule.getRoomType())));
                }
                int regularMissing = missing - functionalMissing;
                if (regularMissing > 0) {
                    Room homeRoom = roomsByCode.get(schoolClass.getRoomCode().toUpperCase(Locale.ROOT));
                    if (homeRoom == null) {
                        String message = "Phòng chủ nhiệm chưa sẵn sàng sử dụng";
                        for (int index = 0; index < regularMissing; index++)
                            result.add(item(assignment, schoolClass, null, null, "UNSCHEDULED", message));
                        warnings.add(assignment.getClassCode() + " · " + assignment.getSubjectName() + ": " + message);
                    } else needs.add(new ScheduleNeed(assignment, schoolClass, load,
                            approvedRestrictions.getOrDefault(assignment.getTeacherId(), Set.of()), regularMissing,
                            List.of(homeRoom), "Phòng chủ nhiệm"));
                }
            }
        }

        int baseOccupiedSize = occupied.size();
        int requestedPeriods = needs.stream().mapToInt(need -> need.remaining).sum();
        boolean solved;
        if (requestedPeriods > 300) {
            long deadline = System.nanoTime() + MAX_SEARCH_MILLIS * 1_000_000L;
            solved = edgeColorCompleteWeek(needs, occupied, request.semesterId(), baseOccupiedSize, deadline);
            if (!solved) solved = denseCompleteWeek(needs, occupied, request.semesterId(), baseOccupiedSize, deadline);
            if (!solved) solved = multiStartBestEffort(needs, occupied, request.semesterId(), strategy,
                    baseOccupiedSize, deadline);
        } else {
            SearchState search = new SearchState(MAX_SEARCH_NODES,
                    System.nanoTime() + MAX_SEARCH_MILLIS * 1_000_000L);
            solved = scheduleAll(needs, occupied, request.semesterId(), search, strategy);
            if (!solved) {
                while (occupied.size() > baseOccupiedSize) occupied.remove(occupied.size() - 1);
                needs.forEach(ScheduleNeed::reset);
                solved = multiStartBestEffort(needs, occupied, request.semesterId(), strategy,
                        baseOccupiedSize, System.nanoTime() + MAX_SEARCH_MILLIS * 1_000_000L);
            }
        }

        for (ScheduleNeed need : needs) {
            for (Candidate candidate : need.scheduled)
                result.add(item(need.assignment, need.schoolClass, candidate, "PROPOSED",
                        solved ? "Đã tối ưu và kiểm tra xung đột toàn cục" : "Khung giờ khả dụng tốt nhất"));
            for (int index = 0; index < need.remaining; index++) {
                String message = explainNoCandidate(need, occupied);
                result.add(item(need.assignment, need.schoolClass, null, "UNSCHEDULED", message));
                warnings.add(need.assignment.getClassCode() + " · "
                        + need.assignment.getSubjectName() + ": " + message);
            }
        }
        int proposed = (int) result.stream().filter(item -> "PROPOSED".equals(item.status())).count();
        int unscheduled = (int) result.stream().filter(item -> "UNSCHEDULED".equals(item.status())).count();
        boolean apply = Boolean.TRUE.equals(request.apply());
        if (apply && unscheduled > 0 && !Boolean.TRUE.equals(request.allowPartial())) {
            throw ApiException.conflict("Còn " + unscheduled
                    + " tiết chưa xếp được. Hãy xử lý cảnh báo trước khi áp dụng.");
        }
        int qualityScore = qualityScore(occupied, unscheduled);
        if (apply) versions.draftFromSlots(request.semesterId(), rebuildExisting
                ? "Thời khóa biểu tái tạo toàn bộ" : "Thời khóa biểu tự động", occupied, actorId,
                qualityScore, strategy);
        return new AutoTimetablePlan(request.semesterId(), existingCount, proposed, unscheduled,
                apply, result, warnings, strategy, qualityScore, strategySummary(strategy));
    }

    /**
     * Xếp toàn bộ các tiết còn thiếu bằng MRV và quay lui. Khi một lựa chọn làm một
     * môn-lớp khác hết chỗ, thuật toán rút lựa chọn đó và thử khung giờ kế tiếp.
     */
    private boolean scheduleAll(List<ScheduleNeed> needs, List<TimetableSlot> occupied,
                                String semesterId, SearchState search, String strategy) {
        if (needs.stream().allMatch(need -> need.remaining == 0)) return true;
        if (search.exhausted()) return false;

        ScheduleNeed selected = null;
        List<Candidate> selectedOptions = List.of();
        int selectedSlack = Integer.MAX_VALUE;
        for (ScheduleNeed need : needs) {
            if (need.remaining == 0) continue;
            List<Candidate> options = candidateOptions(need, occupied, strategy, 0);
            if (options.size() < need.remaining) return false;
            int slack = options.size() - need.remaining;
            if (selected == null || slack < selectedSlack
                    || slack == selectedSlack && options.size() < selectedOptions.size()
                    || slack == selectedSlack && options.size() == selectedOptions.size()
                    && need.remaining > selected.remaining) {
                selected = need;
                selectedOptions = options;
                selectedSlack = slack;
            }
        }
        if (selected == null) return true;

        for (Candidate candidate : selectedOptions) {
            TimetableSlot simulated = simulated(selected.assignment, selected.schoolClass,
                    candidate, semesterId, search.nodes);
            occupied.add(simulated);
            selected.scheduled.add(candidate);
            selected.remaining--;
            if (scheduleAll(needs, occupied, semesterId, search, strategy)) return true;
            selected.remaining++;
            selected.scheduled.remove(selected.scheduled.size() - 1);
            occupied.remove(occupied.size() - 1);
            if (search.exhausted()) return false;
        }
        return false;
    }

    private void fillBestEffort(List<ScheduleNeed> needs, List<TimetableSlot> occupied,
                                String semesterId, String strategy, int variant) {
        Map<String, Integer> teacherDemand = new HashMap<>();
        needs.forEach(need -> teacherDemand.merge(need.assignment.getTeacherId(),
                need.assignment.getWeeklyPeriods(), Integer::sum));
        needs.sort(Comparator.comparingInt((ScheduleNeed need) ->
                        candidateOptions(need, occupied, strategy, variant).size()
                                - teacherDemand.getOrDefault(need.assignment.getTeacherId(), 0))
                .thenComparing(Comparator.comparingInt(
                        (ScheduleNeed need) -> need.assignment.getWeeklyPeriods()).reversed())
                .thenComparingInt(need -> Math.floorMod(Objects.hash(
                        need.assignment.getId(), variant), 10_000))
                .thenComparing(need -> need.assignment.getClassCode())
                .thenComparing(need -> need.assignment.getSubjectName()));
        for (ScheduleNeed need : needs) while (need.remaining > 0) {
            List<Candidate> options = candidateOptions(need, occupied, strategy, variant);
            if (options.isEmpty()) break;
            Candidate candidate = options.get(0);
            occupied.add(simulated(need.assignment, need.schoolClass, candidate,
                    semesterId, occupied.size()));
            need.scheduled.add(candidate);
            need.remaining--;
        }
    }

    private boolean multiStartBestEffort(List<ScheduleNeed> needs, List<TimetableSlot> occupied,
                                         String semesterId, String strategy, int baseOccupiedSize,
                                         long deadlineNanos) {
        int total = needs.stream().mapToInt(need -> need.originalRemaining).sum();
        int bestScheduled = -1;
        List<TimetableSlot> bestSlots = List.of();
        Map<ScheduleNeed, List<Candidate>> bestByNeed = new IdentityHashMap<>();
        int variant = 1;
        do {
            while (occupied.size() > baseOccupiedSize) occupied.remove(occupied.size() - 1);
            needs.forEach(ScheduleNeed::reset);
            fillBestEffort(needs, occupied, semesterId, strategy, variant);
            int scheduled = needs.stream().mapToInt(need -> need.scheduled.size()).sum();
            if (scheduled > bestScheduled) {
                bestScheduled = scheduled;
                bestSlots = new ArrayList<>(occupied.subList(baseOccupiedSize, occupied.size()));
                bestByNeed.clear();
                for (ScheduleNeed need : needs) bestByNeed.put(need, new ArrayList<>(need.scheduled));
            }
            if (scheduled == total) break;
            variant++;
        } while (variant <= 40 && System.nanoTime() < deadlineNanos);

        while (occupied.size() > baseOccupiedSize) occupied.remove(occupied.size() - 1);
        occupied.addAll(bestSlots);
        for (ScheduleNeed need : needs) {
            need.reset();
            List<Candidate> selected = bestByNeed.getOrDefault(need, List.of());
            need.scheduled.addAll(selected);
            need.remaining -= selected.size();
        }
        return bestScheduled == total;
    }

    /**
     * Xếp lịch 5x5 như một bài toán ghép cặp lớp-giáo viên tại từng khung giờ.
     * Mỗi lớp cần đúng một giáo viên ở mỗi ô; thuật toán đường tăng bảo đảm một
     * giáo viên không bị dùng cho hai lớp cùng lúc và có thể đổi môn đã chọn
     * trong cùng khung giờ để tìm phương án đầy đủ.
     */
    private boolean denseCompleteWeek(List<ScheduleNeed> needs, List<TimetableSlot> occupied,
                                      String semesterId, int baseOccupiedSize, long deadlineNanos) {
        Set<String> classIds = needs.stream().map(need -> need.assignment.getClassId())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        int requested = needs.stream().mapToInt(need -> need.remaining).sum();
        if (requested != classIds.size() * TimetableRulePolicy.PERIODS_PER_WEEK
                || baseOccupiedSize != 0 || !denseRoomsAreIndependent(needs)) return false;

        List<DenseCoordinate> coordinates = new ArrayList<>();
        for (String shift : List.of("MORNING", "AFTERNOON")) {
            Map<Integer, String[]> times = "AFTERNOON".equals(shift) ? AFTERNOON : MORNING;
            for (String day : DAYS) for (int period = 1; period <= TimetableRulePolicy.PERIODS_PER_DAY; period++) {
                String[] time = times.get(period);
                coordinates.add(new DenseCoordinate(shift, day, period, time[0], time[1]));
            }
        }

        int variant = 0;
        do {
            while (occupied.size() > baseOccupiedSize) occupied.remove(occupied.size() - 1);
            needs.forEach(ScheduleNeed::reset);
            List<DenseCoordinate> attemptOrder = new ArrayList<>(coordinates);
            int seed = variant++;
            attemptOrder.sort(Comparator.comparingInt(value -> Math.floorMod(
                    Objects.hash(value.shift(), value.day(), value.period(), seed * 104729), Integer.MAX_VALUE)));
            boolean complete = true;
            for (DenseCoordinate coordinate : attemptOrder) {
                List<String> shiftClasses = classIds.stream().filter(classId -> needs.stream().anyMatch(need ->
                        classId.equals(need.assignment.getClassId())
                                && coordinate.shift().equalsIgnoreCase(need.schoolClass.getStudyShift()))).toList();
                if (shiftClasses.isEmpty()) continue;
                Map<String, DenseChoice> selected = new HashMap<>();
                Map<String, String> teacherOwner = new HashMap<>();
                List<String> classOrder = new ArrayList<>(shiftClasses);
                classOrder.sort(Comparator.comparingInt((String classId) -> denseCandidateCount(
                                classId, coordinate, needs, occupied))
                        .thenComparingInt(classId -> Math.floorMod(Objects.hash(classId, seed), Integer.MAX_VALUE)));
                for (String classId : classOrder) {
                    if (!augmentDense(classId, coordinate, needs, occupied, selected,
                            teacherOwner, new HashSet<>(), seed)) {
                        complete = false;
                        break;
                    }
                }
                if (!complete) break;
                for (String classId : shiftClasses) {
                    DenseChoice choice = selected.get(classId);
                    if (choice == null) {
                        complete = false;
                        break;
                    }
                    choice.need().remaining--;
                    choice.need().scheduled.add(choice.candidate());
                    occupied.add(simulated(choice.need().assignment, choice.need().schoolClass,
                            choice.candidate(), semesterId, occupied.size() + 1L));
                }
                if (!complete) break;
            }
            if (complete && needs.stream().allMatch(need -> need.remaining == 0)) return true;
        } while (variant < 240 && System.nanoTime() < deadlineNanos);

        while (occupied.size() > baseOccupiedSize) occupied.remove(occupied.size() - 1);
        needs.forEach(ScheduleNeed::reset);
        return false;
    }

    /**
     * Phân rã đồ thị hai phía lớp-giáo viên thành 25 matching. Vì mỗi lớp có
     * đúng 25 cạnh (tiết học), mỗi matching tạo chính xác một tiết cho mỗi lớp
     * và không giáo viên nào xuất hiện hai lần trong cùng khung giờ.
     */
    private boolean edgeColorCompleteWeek(List<ScheduleNeed> needs, List<TimetableSlot> occupied,
                                          String semesterId, int baseOccupiedSize, long deadlineNanos) {
        Set<String> classIds = needs.stream().map(need -> need.assignment.getClassId())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (baseOccupiedSize != 0 || !denseRoomsAreIndependent(needs)
                || needs.stream().mapToInt(need -> need.remaining).sum()
                != classIds.size() * TimetableRulePolicy.PERIODS_PER_WEEK) return false;

        int variant = 0;
        do {
            List<ColorChoice> colored = new ArrayList<>();
            boolean coloredAll = true;
            for (String shift : List.of("MORNING", "AFTERNOON")) {
                List<ScheduleNeed> shiftNeeds = needs.stream().filter(need ->
                        shift.equalsIgnoreCase(need.schoolClass.getStudyShift())).toList();
                if (!shiftNeeds.isEmpty() && !colorShift(shiftNeeds, variant, colored)) {
                    coloredAll = false;
                    break;
                }
            }
            if (coloredAll) {
                for (int permutation = 0; permutation < 80 && System.nanoTime() < deadlineNanos; permutation++) {
                    List<Integer> slotOrder = new ArrayList<>();
                    for (int index = 0; index < TimetableRulePolicy.PERIODS_PER_WEEK; index++) slotOrder.add(index);
                    int seed = variant * 1009 + permutation;
                    slotOrder.sort(Comparator.comparingInt(index -> mixedRank(index, seed)));
                    int[] slotForColor = new int[TimetableRulePolicy.PERIODS_PER_WEEK];
                    for (int index = 0; index < slotOrder.size(); index++) slotForColor[index] = slotOrder.get(index);

                    List<TimetableSlot> proposed = new ArrayList<>();
                    Map<ScheduleNeed, List<Candidate>> selectedByNeed = new IdentityHashMap<>();
                    List<ColorChoice> actualOrder = new ArrayList<>(colored);
                    actualOrder.sort(Comparator.comparingInt(choice -> slotForColor[choice.color()]));
                    boolean valid = true;
                    for (ColorChoice choice : actualOrder) {
                        int slot = slotForColor[choice.color()];
                        String day = DAYS.get(slot / TimetableRulePolicy.PERIODS_PER_DAY);
                        int period = slot % TimetableRulePolicy.PERIODS_PER_DAY + 1;
                        Map<Integer, String[]> times = "AFTERNOON".equalsIgnoreCase(
                                choice.need().schoolClass.getStudyShift()) ? AFTERNOON : MORNING;
                        String[] time = times.get(period);
                        Candidate candidate = new Candidate(day, period, time[0], time[1],
                                choice.need().candidateRooms.get(0).getCode());
                        if (isUnavailable(choice.need().restrictedSlots,
                                choice.need().schoolClass, candidate)
                                || !noConflict(choice.need().assignment, choice.need().schoolClass,
                                choice.need().load, candidate, proposed)) {
                            valid = false;
                            break;
                        }
                        selectedByNeed.computeIfAbsent(choice.need(), ignored -> new ArrayList<>()).add(candidate);
                        proposed.add(simulated(choice.need().assignment, choice.need().schoolClass,
                                candidate, semesterId, proposed.size() + 1L));
                    }
                    if (valid && proposed.size() == classIds.size() * TimetableRulePolicy.PERIODS_PER_WEEK) {
                        while (occupied.size() > baseOccupiedSize) occupied.remove(occupied.size() - 1);
                        occupied.addAll(proposed);
                        needs.forEach(ScheduleNeed::reset);
                        selectedByNeed.forEach((need, candidates) -> {
                            need.scheduled.addAll(candidates);
                            need.remaining -= candidates.size();
                        });
                        return true;
                    }
                }
            }
            variant++;
        } while (variant < 80 && System.nanoTime() < deadlineNanos);
        return false;
    }

    private boolean colorShift(List<ScheduleNeed> needs, int variant, List<ColorChoice> output) {
        List<String> realLeft = needs.stream().map(need -> need.assignment.getClassId()).distinct().sorted().toList();
        List<String> realRight = needs.stream().map(need -> need.assignment.getTeacherId()).distinct().sorted().toList();
        int size = Math.max(realLeft.size(), realRight.size());
        List<String> left = new ArrayList<>(realLeft);
        List<String> right = new ArrayList<>(realRight);
        while (left.size() < size) left.add("__DUMMY_CLASS_" + left.size());
        while (right.size() < size) right.add("__DUMMY_TEACHER_" + right.size());

        Map<String, List<ScheduleNeed>> edges = new HashMap<>();
        Map<String, Integer> leftDegree = new HashMap<>();
        Map<String, Integer> rightDegree = new HashMap<>();
        for (ScheduleNeed need : needs) for (int count = 0; count < need.originalRemaining; count++) {
            String l = need.assignment.getClassId(), r = need.assignment.getTeacherId();
            edges.computeIfAbsent(edgeKey(l, r), ignored -> new ArrayList<>()).add(need);
            leftDegree.merge(l, 1, Integer::sum);
            rightDegree.merge(r, 1, Integer::sum);
        }
        int rightCursor = 0;
        for (String l : left) {
            int deficit = TimetableRulePolicy.PERIODS_PER_WEEK - leftDegree.getOrDefault(l, 0);
            while (deficit > 0) {
                while (rightCursor < right.size() && rightDegree.getOrDefault(right.get(rightCursor), 0)
                        >= TimetableRulePolicy.PERIODS_PER_WEEK) rightCursor++;
                if (rightCursor >= right.size()) return false;
                String r = right.get(rightCursor);
                int room = TimetableRulePolicy.PERIODS_PER_WEEK - rightDegree.getOrDefault(r, 0);
                int add = Math.min(deficit, room);
                List<ScheduleNeed> pair = edges.computeIfAbsent(edgeKey(l, r), ignored -> new ArrayList<>());
                for (int index = 0; index < add; index++) pair.add(null);
                leftDegree.merge(l, add, Integer::sum);
                rightDegree.merge(r, add, Integer::sum);
                deficit -= add;
            }
        }

        for (int color = 0; color < TimetableRulePolicy.PERIODS_PER_WEEK; color++) {
            Map<String, String> rightOwner = new HashMap<>();
            List<String> leftOrder = new ArrayList<>(left);
            int seed = variant * 31 + color;
            leftOrder.sort(Comparator.comparingInt(value -> mixedRank(value, seed)));
            for (String l : leftOrder) if (!matchColor(l, right, edges, rightOwner,
                    new HashSet<>(), seed)) return false;
            Map<String, String> rightByLeft = new HashMap<>();
            rightOwner.forEach((r, l) -> rightByLeft.put(l, r));
            for (String l : left) {
                String r = rightByLeft.get(l);
                if (r == null) return false;
                List<ScheduleNeed> pair = edges.get(edgeKey(l, r));
                int selectedIndex = Math.floorMod(mixedRank(edgeKey(l, r), seed), pair.size());
                ScheduleNeed selected = pair.remove(selectedIndex);
                if (realLeft.contains(l)) {
                    if (selected == null) return false;
                    output.add(new ColorChoice(selected, color));
                }
            }
        }
        return true;
    }

    private boolean matchColor(String left, List<String> right,
                               Map<String, List<ScheduleNeed>> edges,
                               Map<String, String> rightOwner, Set<String> seenRight, int seed) {
        List<String> candidates = right.stream().filter(r -> {
                    List<ScheduleNeed> pair = edges.get(edgeKey(left, r));
                    return pair != null && !pair.isEmpty();
                }).sorted(Comparator.comparingInt(value -> mixedRank(edgeKey(left, value), seed))).toList();
        for (String r : candidates) {
            if (!seenRight.add(r)) continue;
            String owner = rightOwner.get(r);
            if (owner == null || matchColor(owner, right, edges, rightOwner, seenRight, seed)) {
                rightOwner.put(r, left);
                return true;
            }
        }
        return false;
    }

    private static String edgeKey(String left, String right) {
        return left + '\u001f' + right;
    }

    private static int mixedRank(Object value, int seed) {
        int number = Objects.hashCode(value) ^ seed * 0x9E3779B9;
        number ^= number >>> 16;
        number *= 0x7FEB352D;
        number ^= number >>> 15;
        number *= 0x846CA68B;
        number ^= number >>> 16;
        return number & Integer.MAX_VALUE;
    }

    private boolean augmentDense(String classId, DenseCoordinate coordinate, List<ScheduleNeed> needs,
                                 List<TimetableSlot> occupied, Map<String, DenseChoice> selected,
                                 Map<String, String> teacherOwner, Set<String> visitedTeachers, int variant) {
        List<ScheduleNeed> options = needs.stream()
                .filter(need -> need.remaining > 0 && classId.equals(need.assignment.getClassId()))
                .filter(need -> denseCandidate(need, coordinate, occupied) != null)
                .sorted(Comparator.comparingInt((ScheduleNeed need) -> -teacherRemaining(
                                need.assignment.getTeacherId(), needs))
                        .thenComparingInt(need -> -need.remaining)
                        .thenComparingInt(need -> Math.floorMod(Objects.hash(
                                need.assignment.getId(), variant * 65537), Integer.MAX_VALUE)))
                .toList();
        for (ScheduleNeed need : options) {
            String teacherId = need.assignment.getTeacherId();
            if (!visitedTeachers.add(teacherId)) continue;
            String owner = teacherOwner.get(teacherId);
            if (owner == null || (!owner.equals(classId) && augmentDense(owner, coordinate, needs, occupied,
                    selected, teacherOwner, visitedTeachers, variant))) {
                Candidate candidate = denseCandidate(need, coordinate, occupied);
                if (candidate == null) continue;
                teacherOwner.put(teacherId, classId);
                selected.put(classId, new DenseChoice(need, candidate));
                return true;
            }
        }
        return false;
    }

    private Candidate denseCandidate(ScheduleNeed need, DenseCoordinate coordinate,
                                     List<TimetableSlot> occupied) {
        if (!coordinate.shift().equalsIgnoreCase(need.schoolClass.getStudyShift())) return null;
        for (Room room : need.candidateRooms) {
            Candidate candidate = new Candidate(coordinate.day(), coordinate.period(),
                    coordinate.start(), coordinate.end(), room.getCode());
            if (!isUnavailable(need.restrictedSlots,
                    need.schoolClass, candidate)
                    && noConflict(need.assignment, need.schoolClass, need.load, candidate, occupied)) return candidate;
        }
        return null;
    }

    private int denseCandidateCount(String classId, DenseCoordinate coordinate,
                                    List<ScheduleNeed> needs, List<TimetableSlot> occupied) {
        return (int) needs.stream().filter(need -> need.remaining > 0
                        && classId.equals(need.assignment.getClassId())
                        && denseCandidate(need, coordinate, occupied) != null)
                .map(need -> need.assignment.getTeacherId()).distinct().count();
    }

    private static int teacherRemaining(String teacherId, List<ScheduleNeed> needs) {
        return needs.stream().filter(need -> teacherId.equals(need.assignment.getTeacherId()))
                .mapToInt(need -> need.remaining).sum();
    }

    private static boolean denseRoomsAreIndependent(List<ScheduleNeed> needs) {
        Map<String, String> roomByClassShift = new HashMap<>();
        for (ScheduleNeed need : needs) {
            if (need.candidateRooms.size() != 1) return false;
            String key = need.schoolClass.getStudyShift() + "|" + need.assignment.getClassId();
            String room = need.candidateRooms.get(0).getCode();
            String previous = roomByClassShift.putIfAbsent(key, room);
            if (previous != null && !previous.equals(room)) return false;
        }
        Map<String, String> ownerByShiftRoom = new HashMap<>();
        for (ScheduleNeed need : needs) {
            String key = need.schoolClass.getStudyShift() + "|" + need.candidateRooms.get(0).getCode();
            String owner = ownerByShiftRoom.putIfAbsent(key, need.assignment.getClassId());
            if (owner != null && !owner.equals(need.assignment.getClassId())) return false;
        }
        return true;
    }

    private List<Candidate> candidateOptions(ScheduleNeed need, List<TimetableSlot> occupied,
                                             String strategy, int variant) {
        Set<String> unavailable = need.restrictedSlots;
        Map<Integer, String[]> times = "AFTERNOON".equalsIgnoreCase(need.schoolClass.getStudyShift())
                ? AFTERNOON : MORNING;
        int lastIndex = need.scheduled.isEmpty() ? -1
                : need.scheduled.stream().mapToInt(this::slotIndex).max().orElse(-1);
        return DAYS.stream().flatMap(day -> times.entrySet().stream()
                        .flatMap(entry -> need.candidateRooms.stream().map(room -> new Candidate(
                                day, entry.getKey(), entry.getValue()[0], entry.getValue()[1], room.getCode()))))
                .filter(candidate -> variant > 0 || slotIndex(candidate) > lastIndex)
                .filter(candidate -> !isUnavailable(unavailable, need.schoolClass, candidate))
                .filter(candidate -> noConflict(need.assignment, need.schoolClass, need.load, candidate, occupied))
                // Các tiết của cùng một môn-lớp là tương đương. Duyệt theo thứ tự cố định
                // kết hợp với ràng buộc slotIndex tăng dần loại bỏ các hoán vị trùng lặp
                // và giúp kết quả không phụ thuộc tốc độ máy tại thời điểm chạy.
                .sorted(candidateComparator(need, occupied, strategy, variant)).toList();
    }

    private Comparator<Candidate> candidateComparator(ScheduleNeed need, List<TimetableSlot> occupied,
                                                       String strategy, int variant) {
        Comparator<Candidate> base = Comparator.comparingInt(candidate -> 0);
        if ("TEACHER_COMFORT".equals(strategy)) {
            return base.thenComparingInt(candidate -> teacherDayLoad(
                            need.assignment.getTeacherId(), candidate.day(), occupied) == 0 ? 1 : 0)
                    .thenComparing(Comparator.comparingInt((Candidate candidate) -> teacherDayLoad(
                            need.assignment.getTeacherId(), candidate.day(), occupied)).reversed())
                    .thenComparingInt(candidate -> classDayLoad(
                            need.assignment.getClassId(), candidate.day(), occupied))
                    .thenComparingInt(candidate -> rotatedSlotIndex(candidate, variant));
        }
        if ("EARLY_WEEK".equals(strategy)) {
            return base.thenComparingInt(candidate -> DAYS.indexOf(candidate.day()))
                    .thenComparingInt(candidate -> rotatedSlotIndex(candidate, variant))
                    .thenComparing(Candidate::roomCode);
        }
        return base.thenComparingInt(candidate -> classDayLoad(
                        need.assignment.getClassId(), candidate.day(), occupied))
                .thenComparingInt(candidate -> subjectDayLoad(
                        need.assignment, candidate.day(), occupied))
                .thenComparingInt(candidate -> teacherDayLoad(
                        need.assignment.getTeacherId(), candidate.day(), occupied))
                .thenComparingInt(candidate -> rotatedSlotIndex(candidate, variant));
    }

    private int rotatedSlotIndex(Candidate candidate, int variant) {
        int size = DAYS.size() * MORNING.size();
        return Math.floorMod(slotIndex(candidate) + variant * 7, size);
    }

    private static String normalizeStrategy(String value) {
        String strategy = value == null ? "BALANCED" : value.trim().toUpperCase(Locale.ROOT);
        return Set.of("BALANCED", "TEACHER_COMFORT", "EARLY_WEEK").contains(strategy)
                ? strategy : "BALANCED";
    }

    private static String strategySummary(String strategy) {
        return switch (strategy) {
            case "TEACHER_COMFORT" -> "Ưu tiên gom lịch dạy, giảm ngày giáo viên phải đến trường";
            case "EARLY_WEEK" -> "Ưu tiên hoàn thành định mức vào các ngày đầu tuần";
            default -> "Cân bằng tải giữa các ngày, lớp học và giáo viên";
        };
    }

    private static int qualityScore(List<TimetableSlot> slots, int unscheduled) {
        Map<String, List<TimetableSlot>> teacherDays = new HashMap<>();
        for (TimetableSlot slot : slots) teacherDays.computeIfAbsent(
                slot.getTeacherId() + "|" + slot.getDayOfWeek(), ignored -> new ArrayList<>()).add(slot);
        int gaps = 0;
        for (List<TimetableSlot> day : teacherDays.values()) {
            List<Integer> periods = day.stream().map(TimetableSlot::getPeriodNo).sorted().toList();
            if (periods.size() > 1) gaps += Math.max(0,
                    periods.get(periods.size() - 1) - periods.get(0) + 1 - periods.size());
        }
        int gapPenalty = slots.isEmpty() ? 0 : Math.min(20, gaps * 20 / slots.size());
        return Math.max(0, 100 - Math.min(70, unscheduled * 5) - gapPenalty);
    }

    private int slotIndex(Candidate candidate) {
        // Prefer spreading lessons across the week before adding another period
        // to the same day. Besides producing a healthier timetable, this ordering
        // keeps the canonical-combination pruning below without forcing every
        // subject into consecutive periods on the earliest available day.
        return (candidate.period() - 1) * DAYS.size() + DAYS.indexOf(candidate.day());
    }

    private static TimetableSlot simulated(TeachingAssignment assignment, SchoolClass schoolClass,
                                           Candidate candidate, String semesterId, long sequence) {
        return TimetableSlot.builder().id("preview-" + sequence)
                .classId(assignment.getClassId()).classCode(assignment.getClassCode())
                .studyShift(schoolClass.getStudyShift()).subjectId(assignment.getSubjectId())
                .subjectName(assignment.getSubjectName()).teacherId(assignment.getTeacherId())
                .teacherName(assignment.getTeacherName()).roomCode(candidate.roomCode())
                .dayOfWeek(candidate.day()).periodNo(candidate.period())
                .startTime(candidate.start()).endTime(candidate.end()).semesterId(semesterId).build();
    }

    private String explainNoCandidate(ScheduleNeed need, List<TimetableSlot> occupied) {
        int classBusy = 0, teacherBusy = 0, unavailable = 0;
        Set<String> blocked = need.restrictedSlots;
        Map<Integer, String[]> times = "AFTERNOON".equalsIgnoreCase(need.schoolClass.getStudyShift())
                ? AFTERNOON : MORNING;
        for (String day : DAYS) for (var entry : times.entrySet()) {
            Candidate candidate = new Candidate(day, entry.getKey(), entry.getValue()[0], entry.getValue()[1],
                    need.candidateRooms.get(0).getCode());
            if (isUnavailable(blocked, need.schoolClass, candidate)) unavailable++;
            for (TimetableSlot slot : occupied) {
                if (!day.equals(slot.getDayOfWeek()) || !overlaps(candidate, slot)) continue;
                if (need.assignment.getClassId().equals(slot.getClassId())
                        || candidate.roomCode().equals(slot.getRoomCode())) classBusy++;
                if (need.assignment.getTeacherId().equals(slot.getTeacherId())) teacherBusy++;
            }
        }
        return "Không có khung giờ đồng thời trống cho lớp, giáo viên và phòng"
                + " (lớp/phòng bận " + classBusy + " lượt, giáo viên bận " + teacherBusy
                + " lượt, ngoại lệ lịch đã duyệt " + unavailable + " khung)";
    }

    private Candidate chooseCandidate(TeachingAssignment assignment, SchoolClass schoolClass,
                                      TeacherLoadRegistration load, List<TimetableSlot> occupied) {
        if (schoolClass.getRoomCode() == null || schoolClass.getRoomCode().isBlank()) return null;
        Set<String> unavailable = scheduleRestrictions.approvedSlots(
                assignment.getTeacherId(), assignment.getSemesterId());
        Map<Integer, String[]> times = "AFTERNOON".equalsIgnoreCase(schoolClass.getStudyShift())
                ? AFTERNOON : MORNING;
        return DAYS.stream().flatMap(day -> times.entrySet().stream()
                        .map(entry -> new Candidate(day, entry.getKey(), entry.getValue()[0], entry.getValue()[1],
                                schoolClass.getRoomCode())))
                .filter(candidate -> !isUnavailable(unavailable, schoolClass, candidate))
                .filter(candidate -> noConflict(assignment, schoolClass, load, candidate, occupied))
                .min(Comparator.comparingInt((Candidate candidate) -> classDayLoad(
                                assignment.getClassId(), candidate.day(), occupied))
                        .thenComparingInt(candidate -> subjectDayLoad(assignment, candidate.day(), occupied))
                        .thenComparingInt(candidate -> teacherDayLoad(
                                assignment.getTeacherId(), candidate.day(), occupied))
                        .thenComparing(Candidate::day).thenComparingInt(Candidate::period)).orElse(null);
    }

    private boolean noConflict(TeachingAssignment assignment, SchoolClass schoolClass,
                               TeacherLoadRegistration load, Candidate candidate,
                               List<TimetableSlot> occupied) {
        int sameSubjectDay = subjectDayLoad(assignment, candidate.day(), occupied);
        if (sameSubjectDay >= TimetableRulePolicy.MAX_SUBJECT_PERIODS_PER_CLASS_DAY) return false;
        if (teacherDayLoad(assignment.getTeacherId(), candidate.day(), occupied) >= load.getMaxDailyPeriods()) return false;
        if (wouldExceedConsecutive(assignment.getTeacherId(), candidate, occupied,
                load.getMaxConsecutivePeriods())) return false;
        for (TimetableSlot slot : occupied) {
            if (!candidate.day().equals(slot.getDayOfWeek())) continue;
            if (!overlaps(candidate, slot)) continue;
            if (assignment.getClassId().equals(slot.getClassId())
                    || assignment.getTeacherId().equals(slot.getTeacherId())
                    || candidate.roomCode().equals(slot.getRoomCode())) return false;
        }
        return true;
    }

    private int availableCandidateCount(TeachingAssignment item, TeacherLoadRegistration load,
                                        Set<String> unavailable,
                                        List<TimetableSlot> occupied) {
        if (load == null) return 0;
        SchoolClass schoolClass = structure.getClass(item.getClassId());
        int count = 0;
        Map<Integer, String[]> times = "AFTERNOON".equalsIgnoreCase(schoolClass.getStudyShift()) ? AFTERNOON : MORNING;
        for (String day : DAYS) for (var entry : times.entrySet()) {
            Candidate candidate = new Candidate(day, entry.getKey(), entry.getValue()[0], entry.getValue()[1],
                    schoolClass.getRoomCode());
            if (!isUnavailable(unavailable, schoolClass, candidate)
                    && noConflict(item, schoolClass, load, candidate, occupied)) count++;
        }
        return count;
    }

    private static boolean isUnavailable(Set<String> unavailable, SchoolClass schoolClass, Candidate candidate) {
        String basic = candidate.day() + ":" + candidate.period();
        String shift = ("AFTERNOON".equalsIgnoreCase(schoolClass.getStudyShift()) ? "AFTERNOON" : "MORNING")
                + ":" + basic;
        return unavailable.contains(basic) || unavailable.contains(shift);
    }

    private static boolean wouldExceedConsecutive(String teacherId, Candidate candidate,
                                                  List<TimetableSlot> occupied, int limit) {
        List<String[]> intervals = new ArrayList<>();
        occupied.stream().filter(slot -> teacherId.equals(slot.getTeacherId()))
                .filter(slot -> candidate.day().equals(slot.getDayOfWeek()))
                .forEach(slot -> intervals.add(new String[]{slot.getStartTime(), slot.getEndTime()}));
        intervals.add(new String[]{candidate.start(), candidate.end()});
        intervals.sort(Comparator.comparing(item -> LocalTime.parse(item[0])));
        int current = 0;
        LocalTime previousEnd = null;
        for (String[] interval : intervals) {
            LocalTime start = LocalTime.parse(interval[0]);
            LocalTime end = LocalTime.parse(interval[1]);
            current = previousEnd != null && !start.isAfter(previousEnd.plusMinutes(20)) ? current + 1 : 1;
            if (current > limit) return true;
            previousEnd = end;
        }
        return false;
    }

    private static boolean overlaps(Candidate candidate, TimetableSlot slot) {
        LocalTime start = LocalTime.parse(candidate.start()), end = LocalTime.parse(candidate.end());
        LocalTime otherStart = LocalTime.parse(slot.getStartTime()), otherEnd = LocalTime.parse(slot.getEndTime());
        return start.isBefore(otherEnd) && otherStart.isBefore(end);
    }

    private static boolean sameAssignment(TimetableSlot slot, TeachingAssignment assignment) {
        return assignment.getClassId().equals(slot.getClassId())
                && assignment.getSubjectId().equals(slot.getSubjectId())
                && assignment.getTeacherId().equals(slot.getTeacherId());
    }

    private static int classDayLoad(String classId, String day, List<TimetableSlot> occupied) {
        return (int) occupied.stream().filter(slot -> classId.equals(slot.getClassId())
                && day.equals(slot.getDayOfWeek())).count();
    }

    private static int teacherDayLoad(String teacherId, String day, List<TimetableSlot> occupied) {
        return (int) occupied.stream().filter(slot -> teacherId.equals(slot.getTeacherId())
                && day.equals(slot.getDayOfWeek())).count();
    }

    private static int subjectDayLoad(TeachingAssignment assignment, String day, List<TimetableSlot> occupied) {
        return (int) occupied.stream().filter(slot -> assignment.getClassId().equals(slot.getClassId())
                && assignment.getSubjectId().equals(slot.getSubjectId()) && day.equals(slot.getDayOfWeek())).count();
    }

    private static boolean roomMatches(Room room, SubjectRoomRequirement rule, SchoolClass schoolClass) {
        if (!rule.getRoomType().equalsIgnoreCase(room.getRoomType())) return false;
        if ("MORNING".equalsIgnoreCase(schoolClass.getStudyShift()) && !room.isSupportsMorning()) return false;
        if ("AFTERNOON".equalsIgnoreCase(schoolClass.getStudyShift()) && !room.isSupportsAfternoon()) return false;
        int requiredCapacity = Math.max(schoolClass.getCapacity(), schoolClass.getStudentCount());
        if (room.getCapacity() != null && room.getCapacity() < requiredCapacity) return false;
        Set<String> required = new HashSet<>(csv(rule.getRequiredEquipment()));
        Set<String> available = new HashSet<>(csv(room.getEquipmentTags()));
        return available.containsAll(required);
    }

    private static String roomTypeLabel(String type) {
        return switch (type == null ? "" : type.toUpperCase(Locale.ROOT)) {
            case "LAB" -> "thí nghiệm";
            case "COMPUTER" -> "máy tính";
            case "LANGUAGE" -> "ngoại ngữ";
            case "SPORT" -> "thể chất";
            case "ART" -> "nghệ thuật";
            case "LIBRARY" -> "thư viện";
            case "MULTIPURPOSE" -> "đa năng";
            default -> "chuyên dụng";
        };
    }

    private static List<String> csv(String value) {
        return value == null || value.isBlank() ? List.of() : Arrays.stream(value.split(","))
                .map(String::trim).map(part -> part.toUpperCase(Locale.ROOT)).filter(part -> !part.isBlank()).toList();
    }

    private static AutoTimetableItem item(TeachingAssignment assignment, SchoolClass schoolClass,
                                          Candidate candidate, String status, String message) {
        return new AutoTimetableItem(assignment.getClassId(), assignment.getClassCode(), schoolClass.getStudyShift(),
                assignment.getSubjectId(), assignment.getSubjectName(), assignment.getTeacherId(),
                assignment.getTeacherName(), candidate == null ? schoolClass.getRoomCode() : candidate.roomCode(),
                candidate == null ? null : candidate.day(), candidate == null ? 0 : candidate.period(),
                candidate == null ? null : candidate.start(), candidate == null ? null : candidate.end(),
                status, message);
    }

    private static AutoTimetableItem item(TeachingAssignment assignment, SchoolClass schoolClass,
                                          Candidate candidate, String roomCode, String status, String message) {
        AutoTimetableItem value = item(assignment, schoolClass, candidate, status, message);
        if (candidate != null || roomCode == null) return value;
        return new AutoTimetableItem(value.classId(), value.classCode(), value.studyShift(), value.subjectId(),
                value.subjectName(), value.teacherId(), value.teacherName(), roomCode, value.dayOfWeek(),
                value.periodNo(), value.startTime(), value.endTime(), value.status(), value.message());
    }

    private record Candidate(String day, int period, String start, String end, String roomCode) {}

    private record DenseCoordinate(String shift, String day, int period, String start, String end) {}

    private record DenseChoice(ScheduleNeed need, Candidate candidate) {}

    private record ColorChoice(ScheduleNeed need, int color) {}

    private static final class ScheduleNeed {
        private final TeachingAssignment assignment;
        private final SchoolClass schoolClass;
        private final TeacherLoadRegistration load;
        private final Set<String> restrictedSlots;
        private final List<Room> candidateRooms;
        private final String roomLabel;
        private final int originalRemaining;
        private final List<Candidate> scheduled = new ArrayList<>();
        private int remaining;

        private ScheduleNeed(TeachingAssignment assignment, SchoolClass schoolClass,
                             TeacherLoadRegistration load, Set<String> restrictedSlots, int remaining,
                             List<Room> candidateRooms, String roomLabel) {
            this.assignment = assignment;
            this.schoolClass = schoolClass;
            this.load = load;
            this.restrictedSlots = Set.copyOf(restrictedSlots);
            this.candidateRooms = candidateRooms;
            this.roomLabel = roomLabel;
            this.originalRemaining = remaining;
            this.remaining = remaining;
        }

        private void reset() {
            scheduled.clear();
            remaining = originalRemaining;
        }
    }

    private static final class SearchState {
        private final long maxNodes;
        private final long deadlineNanos;
        private long nodes;

        private SearchState(long maxNodes, long deadlineNanos) {
            this.maxNodes = maxNodes;
            this.deadlineNanos = deadlineNanos;
        }

        private boolean exhausted() {
            nodes++;
            return nodes > maxNodes || System.nanoTime() > deadlineNanos;
        }
    }
}
