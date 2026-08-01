package com.sse.app.academic.structure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sse.app.academic.structure.RoomAllocationDtos.*;
import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class RoomAllocationPlanningService {
    private static final Set<String> ACTIVE_ROOM_STATUSES = Set.of("ACTIVE");

    private final StructureService structure;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public RoomAllocationPlanningService(StructureService structure, JdbcTemplate jdbc, ObjectMapper json) {
        this.structure = structure;
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    public AllocationPlan preview(PreviewRequest request, String actorId) {
        AcademicYear year = structure.getYear(request.academicYearId());
        if ("CLOSED".equals(year.getStatus())) {
            throw ApiException.conflict("Không thể lập phương án ca và phòng cho năm học đã đóng");
        }
        List<SchoolClass> classes = new ArrayList<>(structure.listClasses(year.getId(), null));
        if (classes.isEmpty()) throw ApiException.badRequest("Năm học chưa có lớp để phân ca và phòng");
        classes.sort(Comparator.comparingInt((SchoolClass item) -> -Math.max(item.getCapacity(), studentCount(item.getId())))
                .thenComparing(SchoolClass::getCode));

        List<Room> allRooms = structure.listRooms();
        List<Room> mainRooms = allRooms.stream()
                .filter(room -> ACTIVE_ROOM_STATUSES.contains(room.getStatus()))
                .filter(Room::isHomeRoomEligible)
                .sorted(Comparator.comparing(Room::getCode)).toList();
        if (mainRooms.isEmpty()) throw ApiException.badRequest("Chưa có phòng học chính đang hoạt động");

        int morningSlots = (int) mainRooms.stream().filter(Room::isSupportsMorning).count();
        int afternoonSlots = (int) mainRooms.stream().filter(Room::isSupportsAfternoon).count();
        int total = classes.size();
        int minMorning = Math.max(0, total - afternoonSlots);
        int maxMorning = Math.min(total, morningSlots);
        int desiredMorning = Boolean.FALSE.equals(request.balanceShifts()) ? maxMorning : (total + 1) / 2;
        int targetMorning = Math.max(minMorning, Math.min(maxMorning, desiredMorning));
        int targetAfternoon = total - targetMorning;

        Map<String, SchoolClass> classById = classes.stream().collect(Collectors.toMap(SchoolClass::getId, Function.identity()));
        Map<String, LockedAllocation> locks = normalizeLocks(request.lockedAllocations(), classById);
        Map<String, Room> roomById = mainRooms.stream().collect(Collectors.toMap(Room::getId, Function.identity()));
        Set<String> occupied = new HashSet<>();
        Map<String, DraftItem> drafted = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        int[] shiftCounts = {0, 0};

        for (LockedAllocation lock : locks.values()) {
            SchoolClass schoolClass = classById.get(lock.classId());
            Room room = roomById.get(lock.roomId());
            String problem = validateSlot(schoolClass, room, lock.studyShift(), occupied);
            if (problem != null) {
                drafted.put(schoolClass.getId(), DraftItem.unassigned(schoolClass, studentCount(schoolClass.getId()), true, problem));
                warnings.add(schoolClass.getCode() + ": " + problem);
                continue;
            }
            occupy(occupied, room, lock.studyShift());
            increment(shiftCounts, lock.studyShift());
            drafted.put(schoolClass.getId(), DraftItem.assigned(schoolClass, studentCount(schoolClass.getId()),
                    room, lock.studyShift(), true, "LOCKED", "Giữ theo lựa chọn đã khóa"));
        }

        targetMorning = Math.max(shiftCounts[0], Math.min(targetMorning, total - shiftCounts[1]));
        targetAfternoon = total - targetMorning;
        boolean preserve = !Boolean.FALSE.equals(request.preserveExisting());
        if (preserve) for (SchoolClass schoolClass : classes) {
            if (drafted.containsKey(schoolClass.getId()) || schoolClass.getRoomId() == null) continue;
            Room room = roomById.get(schoolClass.getRoomId());
            String shift = normalizeShift(schoolClass.getStudyShift());
            int target = "MORNING".equals(shift) ? targetMorning : targetAfternoon;
            int current = "MORNING".equals(shift) ? shiftCounts[0] : shiftCounts[1];
            if (current >= target || validateSlot(schoolClass, room, shift, occupied) != null) continue;
            occupy(occupied, room, shift);
            increment(shiftCounts, shift);
            drafted.put(schoolClass.getId(), DraftItem.assigned(schoolClass, studentCount(schoolClass.getId()),
                    room, shift, false, "PRESERVED", "Giữ phòng và ca hiện tại"));
        }

        for (SchoolClass schoolClass : classes) {
            if (drafted.containsKey(schoolClass.getId())) continue;
            String preferred = chooseShift(shiftCounts, targetMorning, targetAfternoon, schoolClass.getStudyShift());
            RoomSlot chosen = findRoom(mainRooms, schoolClass, preferred, occupied);
            if (chosen == null) chosen = findRoom(mainRooms, schoolClass, otherShift(preferred), occupied);
            int count = studentCount(schoolClass.getId());
            if (chosen == null) {
                String message = "Không còn phòng học chính đủ sức chứa ở cả hai ca";
                drafted.put(schoolClass.getId(), DraftItem.unassigned(schoolClass, count, false, message));
                warnings.add(schoolClass.getCode() + ": " + message);
            } else {
                occupy(occupied, chosen.room(), chosen.shift());
                increment(shiftCounts, chosen.shift());
                drafted.put(schoolClass.getId(), DraftItem.assigned(schoolClass, count, chosen.room(), chosen.shift(),
                        false, "PROPOSED", "Phân bổ theo sức chứa và cân bằng ca"));
            }
        }

        String planId = Ids.gen("room-plan");
        Instant now = Instant.now();
        List<DraftItem> draftItems = new ArrayList<>(drafted.values());
        int assigned = (int) draftItems.stream().filter(item -> !"UNASSIGNED".equals(item.status)).count();
        int unassigned = total - assigned;
        String name = request.name() == null || request.name().isBlank()
                ? "Phương án ca - phòng " + year.getCode() : request.name().trim();
        String warningSummary = warnings.isEmpty() ? null : String.join("; ", warnings);
        jdbc.update("""
                insert into room_allocation_plans
                (id,academic_year_id,name,status,total_classes,assigned_classes,unassigned_classes,
                 morning_classes,afternoon_classes,configuration_json,warning_summary,created_by,created_at)
                values (?,?,?,'PREVIEW',?,?,?,?,?,?,?,?,?)
                """, planId, year.getId(), name, total, assigned, unassigned, shiftCounts[0], shiftCounts[1],
                toJson(request), warningSummary, actorId, Timestamp.from(now));
        for (DraftItem item : draftItems) insertItem(planId, item);
        return require(planId);
    }

    public List<AllocationPlan> list(String academicYearId) {
        structure.getYear(academicYearId);
        return jdbc.query("select id from room_allocation_plans where academic_year_id=? order by created_at desc",
                (rs, row) -> rs.getString(1), academicYearId).stream().map(this::require).toList();
    }

    public AllocationPlan require(String planId) {
        List<AllocationPlan> plans = jdbc.query("select * from room_allocation_plans where id=?",
                (rs, row) -> plan(rs), planId);
        if (plans.isEmpty()) throw ApiException.notFound("Phương án ca và phòng");
        return plans.get(0);
    }

    @Transactional
    public AllocationPlan apply(String planId, String actorId) {
        AllocationPlan plan = lockedPlan(planId);
        if (!"PREVIEW".equals(plan.status())) throw ApiException.conflict("Chỉ phương án xem trước mới có thể áp dụng");
        if (plan.unassignedClasses() > 0) throw ApiException.conflict("Phương án còn lớp chưa có phòng; không thể áp dụng");
        for (AllocationItem item : plan.items()) {
            SchoolClass current = structure.getClass(item.classId());
            if (!Objects.equals(normalizeShift(current.getStudyShift()), normalizeShift(item.previousShift()))
                    || !Objects.equals(current.getRoomId(), item.previousRoomId())) {
                throw ApiException.conflict("Lớp " + item.classCode() + " đã thay đổi sau khi tạo bản xem trước");
            }
        }
        jdbc.update("update room_allocation_plans set status='SUPERSEDED' where academic_year_id=? and status='APPLIED'",
                plan.academicYearId());
        for (AllocationItem item : plan.items()) {
            jdbc.update("update classes set study_shift=?,room_id=?,room_code=? where id=?",
                    item.proposedShift(), item.proposedRoomId(), item.proposedRoomCode(), item.classId());
        }
        jdbc.update("update room_allocation_plans set status='APPLIED',applied_by=?,applied_at=? where id=?",
                actorId, Timestamp.from(Instant.now()), planId);
        return require(planId);
    }

    @Transactional
    public AllocationPlan undo(String planId, String actorId) {
        AllocationPlan plan = lockedPlan(planId);
        if (!"APPLIED".equals(plan.status())) throw ApiException.conflict("Chỉ phương án đang áp dụng mới có thể hoàn tác");
        for (AllocationItem item : plan.items()) {
            SchoolClass current = structure.getClass(item.classId());
            if (!Objects.equals(normalizeShift(current.getStudyShift()), normalizeShift(item.proposedShift()))
                    || !Objects.equals(current.getRoomId(), item.proposedRoomId())) {
                throw ApiException.conflict("Lớp " + item.classCode() + " đã thay đổi sau khi áp dụng; không thể hoàn tác an toàn");
            }
        }
        for (AllocationItem item : plan.items()) {
            jdbc.update("update classes set study_shift=?,room_id=?,room_code=? where id=?",
                    item.previousShift(), item.previousRoomId(), item.previousRoomCode(), item.classId());
        }
        jdbc.update("update room_allocation_plans set status='UNDONE',undone_by=?,undone_at=? where id=?",
                actorId, Timestamp.from(Instant.now()), planId);
        return require(planId);
    }

    private AllocationPlan lockedPlan(String planId) {
        List<String> ids = jdbc.query("select id from room_allocation_plans where id=? for update",
                (rs, row) -> rs.getString(1), planId);
        if (ids.isEmpty()) throw ApiException.notFound("Phương án ca và phòng");
        return require(planId);
    }

    private Map<String, LockedAllocation> normalizeLocks(List<LockedAllocation> values, Map<String, SchoolClass> classes) {
        Map<String, LockedAllocation> result = new LinkedHashMap<>();
        for (LockedAllocation lock : values == null ? List.<LockedAllocation>of() : values) {
            if (!classes.containsKey(lock.classId())) throw ApiException.badRequest("Lớp khóa không thuộc năm học đã chọn");
            if (result.putIfAbsent(lock.classId(), lock) != null) throw ApiException.badRequest("Một lớp chỉ được khóa một lựa chọn ca và phòng");
        }
        return result;
    }

    private String validateSlot(SchoolClass schoolClass, Room room, String shift, Set<String> occupied) {
        if (room == null) return "Phòng đã khóa không phải phòng học chính đang hoạt động";
        if ("MORNING".equals(shift) && !room.isSupportsMorning()) return "Phòng không phục vụ ca sáng";
        if ("AFTERNOON".equals(shift) && !room.isSupportsAfternoon()) return "Phòng không phục vụ ca chiều";
        if (room.getCapacity() != null && room.getCapacity() < schoolClass.getCapacity()) return "Phòng không đủ sức chứa dự kiến của lớp";
        if (occupied.contains(slotKey(room.getId(), shift))) return "Phòng đã được một lớp khác sử dụng trong cùng ca";
        return null;
    }

    private RoomSlot findRoom(List<Room> rooms, SchoolClass schoolClass, String shift, Set<String> occupied) {
        return rooms.stream()
                .filter(room -> "MORNING".equals(shift) ? room.isSupportsMorning() : room.isSupportsAfternoon())
                .filter(room -> room.getCapacity() == null || room.getCapacity() >= schoolClass.getCapacity())
                .filter(room -> !occupied.contains(slotKey(room.getId(), shift)))
                .min(Comparator.comparingInt((Room room) -> room.getCapacity() == null ? Integer.MAX_VALUE : room.getCapacity() - schoolClass.getCapacity())
                        .thenComparing(Room::getCode))
                .map(room -> new RoomSlot(room, shift)).orElse(null);
    }

    private String chooseShift(int[] counts, int targetMorning, int targetAfternoon, String currentShift) {
        if (counts[0] >= targetMorning) return "AFTERNOON";
        if (counts[1] >= targetAfternoon) return "MORNING";
        double morningRatio = targetMorning == 0 ? 1 : counts[0] / (double) targetMorning;
        double afternoonRatio = targetAfternoon == 0 ? 1 : counts[1] / (double) targetAfternoon;
        if (Math.abs(morningRatio - afternoonRatio) < 0.0001) return normalizeShift(currentShift);
        return morningRatio < afternoonRatio ? "MORNING" : "AFTERNOON";
    }

    private int studentCount(String classId) {
        Integer value = jdbc.queryForObject("select count(*) from users where role='STUDENT' and class_id=?", Integer.class, classId);
        return value == null ? 0 : value;
    }

    private void occupy(Set<String> occupied, Room room, String shift) { occupied.add(slotKey(room.getId(), shift)); }
    private String slotKey(String roomId, String shift) { return roomId + "|" + normalizeShift(shift); }
    private void increment(int[] counts, String shift) { counts["MORNING".equals(normalizeShift(shift)) ? 0 : 1]++; }
    private String normalizeShift(String value) { return "AFTERNOON".equalsIgnoreCase(value) ? "AFTERNOON" : "MORNING"; }
    private String otherShift(String value) { return "MORNING".equals(normalizeShift(value)) ? "AFTERNOON" : "MORNING"; }

    private void insertItem(String planId, DraftItem item) {
        jdbc.update("""
                insert into room_allocation_plan_items
                (id,plan_id,class_id,class_code,student_count,class_capacity,previous_shift,previous_room_id,
                 previous_room_code,proposed_shift,proposed_room_id,proposed_room_code,locked,status,message,created_at)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, Ids.gen("room-plan-item"), planId, item.schoolClass.getId(), item.schoolClass.getCode(),
                item.studentCount, item.schoolClass.getCapacity(), item.schoolClass.getStudyShift(),
                item.schoolClass.getRoomId(), item.schoolClass.getRoomCode(), item.proposedShift,
                item.room == null ? null : item.room.getId(), item.room == null ? null : item.room.getCode(),
                item.locked, item.status, item.message, Timestamp.from(Instant.now()));
    }

    private AllocationPlan plan(ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        List<AllocationItem> items = jdbc.query("select * from room_allocation_plan_items where plan_id=? order by class_code",
                (itemRs, row) -> item(itemRs), id);
        List<Room> all = structure.listRooms();
        int main = (int) all.stream().filter(room -> "ACTIVE".equals(room.getStatus()) && room.isHomeRoomEligible()).count();
        int functional = (int) all.stream().filter(room -> !"GENERAL".equals(room.getRoomType()) || !room.isHomeRoomEligible()).count();
        int morningSlots = (int) all.stream().filter(room -> "ACTIVE".equals(room.getStatus()) && room.isHomeRoomEligible() && room.isSupportsMorning()).count();
        int afternoonSlots = (int) all.stream().filter(room -> "ACTIVE".equals(room.getStatus()) && room.isHomeRoomEligible() && room.isSupportsAfternoon()).count();
        CapacitySummary capacity = new CapacitySummary(all.size(), main, functional, morningSlots, afternoonSlots,
                morningSlots + afternoonSlots, rs.getInt("total_classes"), rs.getInt("morning_classes"),
                rs.getInt("afternoon_classes"), morningSlots + afternoonSlots - rs.getInt("total_classes"));
        String warning = rs.getString("warning_summary");
        return new AllocationPlan(id, rs.getString("academic_year_id"), rs.getString("name"), rs.getString("status"),
                rs.getInt("total_classes"), rs.getInt("assigned_classes"), rs.getInt("unassigned_classes"),
                rs.getInt("morning_classes"), rs.getInt("afternoon_classes"), capacity, items,
                warning == null || warning.isBlank() ? List.of() : Arrays.asList(warning.split("; ")),
                rs.getString("created_by"), instant(rs, "created_at"), rs.getString("applied_by"), instant(rs, "applied_at"),
                rs.getString("undone_by"), instant(rs, "undone_at"));
    }

    private AllocationItem item(ResultSet rs) throws SQLException {
        return new AllocationItem(rs.getString("id"), rs.getString("class_id"), rs.getString("class_code"),
                rs.getInt("student_count"), rs.getInt("class_capacity"), rs.getString("previous_shift"),
                rs.getString("previous_room_id"), rs.getString("previous_room_code"), rs.getString("proposed_shift"),
                rs.getString("proposed_room_id"), rs.getString("proposed_room_code"), rs.getBoolean("locked"),
                rs.getString("status"), rs.getString("message"));
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private String toJson(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw ApiException.badRequest("Không thể lưu cấu hình phương án"); }
    }

    private record RoomSlot(Room room, String shift) {}

    private static final class DraftItem {
        private final SchoolClass schoolClass;
        private final int studentCount;
        private final Room room;
        private final String proposedShift;
        private final boolean locked;
        private final String status;
        private final String message;

        private DraftItem(SchoolClass schoolClass, int studentCount, Room room, String proposedShift,
                          boolean locked, String status, String message) {
            this.schoolClass = schoolClass; this.studentCount = studentCount; this.room = room;
            this.proposedShift = proposedShift; this.locked = locked; this.status = status; this.message = message;
        }

        private static DraftItem assigned(SchoolClass schoolClass, int studentCount, Room room, String shift,
                                          boolean locked, String status, String message) {
            return new DraftItem(schoolClass, studentCount, room, shift, locked, status, message);
        }

        private static DraftItem unassigned(SchoolClass schoolClass, int studentCount, boolean locked, String message) {
            return new DraftItem(schoolClass, studentCount, null, schoolClass.getStudyShift(), locked, "UNASSIGNED", message);
        }
    }
}
