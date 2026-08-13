package com.sse.app.academic.timetable;

import com.sse.app.academic.structure.SchoolClass;
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
    private static final int MAX_PEER_DAY_GAP = 2;
    private static final List<String> DAYS = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT");
    private static final Map<Integer, String[]> MORNING = Map.of(
            1, new String[]{"07:00", "07:45"}, 2, new String[]{"07:50", "08:35"},
            3, new String[]{"08:50", "09:35"}, 4, new String[]{"09:40", "10:25"},
            5, new String[]{"10:35", "11:20"}, 6, new String[]{"11:25", "12:10"});
    private static final Map<Integer, String[]> AFTERNOON = Map.of(
            1, new String[]{"13:00", "13:45"}, 2, new String[]{"13:50", "14:35"},
            3, new String[]{"14:50", "15:35"}, 4, new String[]{"15:40", "16:25"},
            5, new String[]{"16:35", "17:20"}, 6, new String[]{"17:25", "18:10"});

    private final TimetableRepository slots;
    private final TeachingAssignmentRepository assignments;
    private final TeacherLoadRegistrationRepository registrations;
    private final StructureService structure;
    private final TimetableService timetable;
    private final TimetableVersionService versions;

    public AutomaticTimetableService(TimetableRepository slots, TeachingAssignmentRepository assignments,
                                     TeacherLoadRegistrationRepository registrations,
                                     StructureService structure, TimetableService timetable,
                                     TimetableVersionService versions) {
        this.slots = slots;
        this.assignments = assignments;
        this.registrations = registrations;
        this.structure = structure;
        this.timetable = timetable;
        this.versions = versions;
    }

    @Transactional
    public AutoTimetablePlan plan(AutoTimetableRequest request, String actorId) {
        structure.assertSemesterWritable(request.semesterId());
        List<TimetableSlot> occupied = new ArrayList<>(slots.findBySemesterId(request.semesterId()));
        int existingCount = occupied.size();
        Map<String, TeacherLoadRegistration> loads = new HashMap<>();
        registrations.findBySemesterId(request.semesterId()).stream()
                .filter(item -> Set.of("APPROVED", "LOCKED").contains(item.getStatus()))
                .forEach(item -> loads.put(item.getTeacherId(), item));

        List<TeachingAssignment> work = assignments.findAll().stream()
                .filter(item -> request.semesterId().equals(item.getSemesterId()))
                .filter(item -> request.scopeGradeLevel() == null
                        || request.scopeGradeLevel().equals(structure.getClass(item.getClassId()).getGradeLevel()))
                .sorted(Comparator.comparingInt((TeachingAssignment item) ->
                                availableCandidateCount(item, loads.get(item.getTeacherId()), occupied))
                        .thenComparing(TeachingAssignment::getClassCode)
                        .thenComparing(TeachingAssignment::getSubjectName)).toList();
        if (work.isEmpty()) throw ApiException.badRequest(request.scopeGradeLevel() == null
                ? "Chưa có phân công bộ môn trong học kỳ đã chọn"
                : "Chưa có phân công bộ môn cho " + request.scopeGradeLevel().replace("K", "khối "));

        List<AutoTimetableItem> result = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<ScheduleNeed> needs = new ArrayList<>();
        for (TeachingAssignment assignment : work) {
            int already = (int) occupied.stream().filter(slot -> sameAssignment(slot, assignment)).count();
            int missing = Math.max(0, assignment.getWeeklyPeriods() - already);
            if (missing == 0) continue;
            TeacherLoadRegistration load = loads.get(assignment.getTeacherId());
            SchoolClass schoolClass = structure.getClass(assignment.getClassId());
            if (load == null || schoolClass.getRoomCode() == null || schoolClass.getRoomCode().isBlank()) {
                String message = load == null
                        ? "Giáo viên chưa có đăng ký tải dạy được duyệt"
                        : "Lớp chưa được gán phòng học";
                for (int index = 0; index < missing; index++)
                    result.add(item(assignment, schoolClass, null, "UNSCHEDULED", message));
                warnings.add(assignment.getClassCode() + " · " + assignment.getSubjectName() + ": " + message);
            } else needs.add(new ScheduleNeed(assignment, schoolClass, load, already, missing));
        }

        int baseOccupiedSize = occupied.size();
        SearchState search = new SearchState(MAX_SEARCH_NODES,
                System.nanoTime() + MAX_SEARCH_MILLIS * 1_000_000L);
        boolean solved = scheduleAll(needs, occupied, request.semesterId(), search);
        if (!solved) {
            while (occupied.size() > baseOccupiedSize) occupied.remove(occupied.size() - 1);
            needs.forEach(ScheduleNeed::reset);
            fillBestEffort(needs, occupied, request.semesterId());
        }

        for (ScheduleNeed need : needs) {
            for (Candidate candidate : need.scheduled)
                result.add(item(need.assignment, need.schoolClass, candidate, "PROPOSED",
                        solved ? "Đã tối ưu xung đột và cân bằng tiến độ các lớp cùng khối"
                                : "Khung giờ khả dụng tốt nhất trong ngưỡng lệch tối đa 2 ngày"));
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
        if (apply) for (AutoTimetableItem item : result) if ("PROPOSED".equals(item.status())) {
            timetable.create(new CreateSlotRequest(null, item.classId(), item.subjectId(), item.teacherId(),
                    item.roomCode(), item.dayOfWeek(), item.periodNo(), item.startTime(), item.endTime(),
                    request.semesterId()));
        }
        TimetableVersion draftVersion = null;
        if (apply && request.draftName() != null && !request.draftName().isBlank()) {
            draftVersion = versions.snapshot(request.semesterId(), request.draftName(), actorId);
        }
        return new AutoTimetablePlan(request.semesterId(), request.scopeGradeLevel(), existingCount,
                proposed, unscheduled, apply, result, warnings, draftVersion);
    }

    /**
     * Xếp toàn bộ các tiết còn thiếu bằng MRV và quay lui. Khi một lựa chọn làm một
     * môn-lớp khác hết chỗ, thuật toán rút lựa chọn đó và thử khung giờ kế tiếp.
     */
    private boolean scheduleAll(List<ScheduleNeed> needs, List<TimetableSlot> occupied,
                                String semesterId, SearchState search) {
        if (needs.stream().allMatch(need -> need.remaining == 0)) return true;
        if (search.exhausted()) return false;

        ScheduleNeed selected = null;
        List<Candidate> selectedOptions = List.of();
        int selectedSlack = Integer.MAX_VALUE;
        for (ScheduleNeed need : needs) {
            if (need.remaining == 0) continue;
            List<Candidate> options = candidateOptions(need, occupied);
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
            if (scheduleAll(needs, occupied, semesterId, search)) return true;
            selected.remaining++;
            selected.scheduled.remove(selected.scheduled.size() - 1);
            occupied.remove(occupied.size() - 1);
            if (search.exhausted()) return false;
        }
        return false;
    }

    private void fillBestEffort(List<ScheduleNeed> needs, List<TimetableSlot> occupied,
                                String semesterId) {
        needs.sort(Comparator.comparingInt((ScheduleNeed need) -> candidateOptions(need, occupied).size())
                .thenComparing(need -> need.assignment.getClassCode())
                .thenComparing(need -> need.assignment.getSubjectName()));
        for (ScheduleNeed need : needs) while (need.remaining > 0) {
            List<Candidate> options = candidateOptions(need, occupied);
            if (options.isEmpty()) break;
            Candidate candidate = options.get(0);
            occupied.add(simulated(need.assignment, need.schoolClass, candidate,
                    semesterId, occupied.size()));
            need.scheduled.add(candidate);
            need.remaining--;
        }
    }

    private List<Candidate> candidateOptions(ScheduleNeed need, List<TimetableSlot> occupied) {
        Set<String> unavailable = new HashSet<>(csv(need.load.getUnavailableSlots()));
        Map<Integer, String[]> times = "AFTERNOON".equalsIgnoreCase(need.schoolClass.getStudyShift())
                ? AFTERNOON : MORNING;
        int lastIndex = need.scheduled.isEmpty() ? -1
                : need.scheduled.stream().mapToInt(this::slotIndex).max().orElse(-1);
        int occurrenceIndex = need.existingPeriods + need.scheduled.size();
        List<Integer> peerDays = peerOccurrenceDays(need, occurrenceIndex, occupied);
        return DAYS.stream().flatMap(day -> times.entrySet().stream()
                        .map(entry -> new Candidate(day, entry.getKey(), entry.getValue()[0], entry.getValue()[1])))
                .filter(candidate -> slotIndex(candidate) > lastIndex)
                .filter(candidate -> !unavailable.contains(candidate.day() + ":" + candidate.period()))
                .filter(candidate -> noConflict(need.assignment, need.schoolClass, candidate, occupied))
                .filter(candidate -> peerDays.stream().allMatch(
                        peerDay -> Math.abs(dayIndex(candidate.day()) - peerDay) <= MAX_PEER_DAY_GAP))
                // Các tiết của cùng một môn-lớp là tương đương. Duyệt theo thứ tự cố định
                // kết hợp với ràng buộc slotIndex tăng dần loại bỏ các hoán vị trùng lặp
                // và giúp kết quả không phụ thuộc tốc độ máy tại thời điểm chạy.
                .sorted(Comparator.comparingInt((Candidate candidate) -> peerGapScore(candidate, peerDays))
                        .thenComparingInt(candidate -> classDayLoad(
                                need.assignment.getClassId(), candidate.day(), occupied))
                        .thenComparingInt(candidate -> subjectDayLoad(
                                need.assignment, candidate.day(), occupied))
                        .thenComparingInt(this::slotIndex))
                .toList();
    }

    private int slotIndex(Candidate candidate) {
        return DAYS.indexOf(candidate.day()) * 10 + candidate.period();
    }

    private int slotIndex(TimetableSlot slot) {
        return DAYS.indexOf(slot.getDayOfWeek()) * 10 + slot.getPeriodNo();
    }

    /**
     * Lấy ngày của cùng lần học (tiết thứ n trong tuần) ở các lớp cùng khối và
     * cùng môn. Ứng viên mới bắt buộc nằm trong khoảng ±2 ngày so với tất cả lớp
     * đã có mốc tương ứng.
     */
    private List<Integer> peerOccurrenceDays(ScheduleNeed need, int occurrenceIndex,
                                             List<TimetableSlot> occupied) {
        Map<String, List<TimetableSlot>> peers = new LinkedHashMap<>();
        for (TimetableSlot slot : occupied) {
            if (need.assignment.getClassId().equals(slot.getClassId())
                    || !need.assignment.getSubjectId().equals(slot.getSubjectId())
                    || !need.assignment.getSemesterId().equals(slot.getSemesterId())) continue;
            SchoolClass peerClass = structure.getClass(slot.getClassId());
            if (!Objects.equals(need.schoolClass.getGradeLevel(), peerClass.getGradeLevel())) continue;
            peers.computeIfAbsent(slot.getClassId(), ignored -> new ArrayList<>()).add(slot);
        }
        List<Integer> days = new ArrayList<>();
        for (List<TimetableSlot> peerSlots : peers.values()) {
            peerSlots.sort(Comparator.comparingInt(this::slotIndex));
            if (peerSlots.size() > occurrenceIndex) {
                days.add(dayIndex(peerSlots.get(occurrenceIndex).getDayOfWeek()));
            }
        }
        return days;
    }

    private int peerGapScore(Candidate candidate, List<Integer> peerDays) {
        int candidateDay = dayIndex(candidate.day());
        return peerDays.stream().mapToInt(day -> Math.abs(candidateDay - day)).max().orElse(0);
    }

    private int dayIndex(String day) {
        int index = DAYS.indexOf(day == null ? "" : day.toUpperCase(Locale.ROOT));
        return index < 0 ? 99 : index;
    }

    private static TimetableSlot simulated(TeachingAssignment assignment, SchoolClass schoolClass,
                                           Candidate candidate, String semesterId, long sequence) {
        return TimetableSlot.builder().id("preview-" + sequence)
                .classId(assignment.getClassId()).classCode(assignment.getClassCode())
                .studyShift(schoolClass.getStudyShift()).subjectId(assignment.getSubjectId())
                .subjectName(assignment.getSubjectName()).teacherId(assignment.getTeacherId())
                .teacherName(assignment.getTeacherName()).roomCode(schoolClass.getRoomCode())
                .dayOfWeek(candidate.day()).periodNo(candidate.period())
                .startTime(candidate.start()).endTime(candidate.end()).semesterId(semesterId).build();
    }

    private String explainNoCandidate(ScheduleNeed need, List<TimetableSlot> occupied) {
        int classBusy = 0, teacherBusy = 0, unavailable = 0, pacingBlocked = 0;
        Set<String> blocked = new HashSet<>(csv(need.load.getUnavailableSlots()));
        Map<Integer, String[]> times = "AFTERNOON".equalsIgnoreCase(need.schoolClass.getStudyShift())
                ? AFTERNOON : MORNING;
        int occurrenceIndex = need.existingPeriods + need.scheduled.size();
        List<Integer> peerDays = peerOccurrenceDays(need, occurrenceIndex, occupied);
        for (String day : DAYS) for (var entry : times.entrySet()) {
            Candidate candidate = new Candidate(day, entry.getKey(), entry.getValue()[0], entry.getValue()[1]);
            if (blocked.contains(day + ":" + entry.getKey())) unavailable++;
            if (!peerDays.isEmpty() && peerDays.stream().anyMatch(
                    peerDay -> Math.abs(dayIndex(day) - peerDay) > MAX_PEER_DAY_GAP)) pacingBlocked++;
            for (TimetableSlot slot : occupied) {
                if (!day.equals(slot.getDayOfWeek()) || !overlaps(candidate, slot)) continue;
                if (need.assignment.getClassId().equals(slot.getClassId())
                        || need.schoolClass.getRoomCode().equals(slot.getRoomCode())) classBusy++;
                if (need.assignment.getTeacherId().equals(slot.getTeacherId())) teacherBusy++;
            }
        }
        return "Không có khung giờ đồng thời trống cho lớp, giáo viên và phòng"
                + " (lớp/phòng bận " + classBusy + " lượt, giáo viên bận " + teacherBusy
                + " lượt, giáo viên đăng ký bận " + unavailable + " khung, lệch tiến độ quá 2 ngày "
                + pacingBlocked + " khung)";
    }

    private Candidate chooseCandidate(TeachingAssignment assignment, SchoolClass schoolClass,
                                      TeacherLoadRegistration load, List<TimetableSlot> occupied) {
        if (schoolClass.getRoomCode() == null || schoolClass.getRoomCode().isBlank()) return null;
        Set<String> unavailable = new HashSet<>(csv(load.getUnavailableSlots()));
        Map<Integer, String[]> times = "AFTERNOON".equalsIgnoreCase(schoolClass.getStudyShift())
                ? AFTERNOON : MORNING;
        return DAYS.stream().flatMap(day -> times.entrySet().stream()
                        .map(entry -> new Candidate(day, entry.getKey(), entry.getValue()[0], entry.getValue()[1])))
                .filter(candidate -> !unavailable.contains(candidate.day() + ":" + candidate.period()))
                .filter(candidate -> noConflict(assignment, schoolClass, candidate, occupied))
                .min(Comparator.comparingInt((Candidate candidate) -> classDayLoad(
                                assignment.getClassId(), candidate.day(), occupied))
                        .thenComparingInt(candidate -> subjectDayLoad(assignment, candidate.day(), occupied))
                        .thenComparingInt(candidate -> teacherDayLoad(
                                assignment.getTeacherId(), candidate.day(), occupied))
                        .thenComparing(Candidate::day).thenComparingInt(Candidate::period)).orElse(null);
    }

    private boolean noConflict(TeachingAssignment assignment, SchoolClass schoolClass,
                               Candidate candidate, List<TimetableSlot> occupied) {
        int sameSubjectDay = subjectDayLoad(assignment, candidate.day(), occupied);
        if (sameSubjectDay >= 2) return false;
        for (TimetableSlot slot : occupied) {
            if (!candidate.day().equals(slot.getDayOfWeek())) continue;
            if (!overlaps(candidate, slot)) continue;
            if (assignment.getClassId().equals(slot.getClassId())
                    || assignment.getTeacherId().equals(slot.getTeacherId())
                    || schoolClass.getRoomCode().equals(slot.getRoomCode())) return false;
        }
        return true;
    }

    private int availableCandidateCount(TeachingAssignment item, TeacherLoadRegistration load,
                                        List<TimetableSlot> occupied) {
        if (load == null) return 0;
        SchoolClass schoolClass = structure.getClass(item.getClassId());
        int count = 0;
        Map<Integer, String[]> times = "AFTERNOON".equalsIgnoreCase(schoolClass.getStudyShift()) ? AFTERNOON : MORNING;
        Set<String> unavailable = new HashSet<>(csv(load.getUnavailableSlots()));
        for (String day : DAYS) for (var entry : times.entrySet()) {
            Candidate candidate = new Candidate(day, entry.getKey(), entry.getValue()[0], entry.getValue()[1]);
            if (!unavailable.contains(day + ":" + entry.getKey()) && noConflict(item, schoolClass, candidate, occupied)) count++;
        }
        return count;
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

    private static List<String> csv(String value) {
        return value == null || value.isBlank() ? List.of() : Arrays.stream(value.split(",")).map(String::trim).toList();
    }

    private static AutoTimetableItem item(TeachingAssignment assignment, SchoolClass schoolClass,
                                          Candidate candidate, String status, String message) {
        return new AutoTimetableItem(assignment.getClassId(), assignment.getClassCode(), schoolClass.getStudyShift(),
                assignment.getSubjectId(), assignment.getSubjectName(), assignment.getTeacherId(),
                assignment.getTeacherName(), schoolClass.getRoomCode(),
                candidate == null ? null : candidate.day(), candidate == null ? 0 : candidate.period(),
                candidate == null ? null : candidate.start(), candidate == null ? null : candidate.end(),
                status, message);
    }

    private record Candidate(String day, int period, String start, String end) {}

    private static final class ScheduleNeed {
        private final TeachingAssignment assignment;
        private final SchoolClass schoolClass;
        private final TeacherLoadRegistration load;
        private final int existingPeriods;
        private final int originalRemaining;
        private final List<Candidate> scheduled = new ArrayList<>();
        private int remaining;

        private ScheduleNeed(TeachingAssignment assignment, SchoolClass schoolClass,
                             TeacherLoadRegistration load, int existingPeriods, int remaining) {
            this.assignment = assignment;
            this.schoolClass = schoolClass;
            this.load = load;
            this.existingPeriods = existingPeriods;
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
