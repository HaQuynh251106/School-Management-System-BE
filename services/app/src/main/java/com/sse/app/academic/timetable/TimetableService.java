package com.sse.app.academic.timetable;

import com.sse.app.academic.structure.StructureService;
import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.academic.timetable.TimetableDtos.CreateSlotRequest;
import com.sse.app.identity.User;
import com.sse.app.identity.UserService;
import org.springframework.stereotype.Service;

import java.util.List;

/** A3: Xếp TKB + Conflict Resolution (flowchart 2.4). */
@Service
public class TimetableService {

    private final TimetableRepository slots;
    private final StructureService structure;
    private final UserService users;

    public TimetableService(TimetableRepository slots, StructureService structure, UserService users) {
        this.slots = slots;
        this.structure = structure;
        this.users = users;
    }

    public List<TimetableSlot> list(String classId, String teacherId, String semesterId, String dayOfWeek) {
        List<TimetableSlot> base;
        if (classId != null)        base = slots.findByClassId(classId);
        else if (teacherId != null) base = slots.findByTeacherId(teacherId);
        else if (semesterId != null) base = slots.findBySemesterId(semesterId);
        else base = slots.findAll();
        return base.stream()
                .filter(s -> semesterId == null || semesterId.equals(s.getSemesterId()))
                .filter(s -> dayOfWeek == null || dayOfWeek.equalsIgnoreCase(s.getDayOfWeek()))
                .sorted((a, b) -> {
                    int d = dayIndex(a.getDayOfWeek()) - dayIndex(b.getDayOfWeek());
                    return d != 0 ? d : Integer.compare(a.getPeriodNo(), b.getPeriodNo());
                })
                .toList();
    }

    public TimetableSlot create(CreateSlotRequest r) {
        checkConflicts(r);

        User teacher = users.getById(r.teacherId());
        String subjectName = structure.subjectName(r.subjectId());

        return slots.save(TimetableSlot.builder()
                .id(r.id() == null || r.id().isBlank() ? Ids.gen("tt") : r.id())
                .classId(r.classId())
                .subjectId(r.subjectId())
                .subjectName(subjectName)
                .teacherId(r.teacherId())
                .teacherName(teacher.getFullName())
                .roomCode(r.roomCode())
                .dayOfWeek(r.dayOfWeek().toUpperCase())
                .periodNo(r.periodNo())
                .startTime(r.startTime())
                .endTime(r.endTime())
                .semesterId(r.semesterId())
                .build());
    }

    private void checkConflicts(CreateSlotRequest r) {
        List<TimetableSlot> sameCell = slots.findByDayOfWeekAndPeriodNo(r.dayOfWeek().toUpperCase(), r.periodNo());
        for (TimetableSlot s : sameCell) {
            if (differentSemester(r.semesterId(), s.getSemesterId())) continue;
            if (r.classId().equals(s.getClassId()))
                throw ApiException.conflict("Lớp đã có tiết khác ở thứ " + r.dayOfWeek() + " tiết " + r.periodNo());
            if (r.teacherId().equals(s.getTeacherId()))
                throw ApiException.conflict("Giáo viên bận tiết này (" + s.getSubjectName() + ")");
            if (r.roomCode() != null && r.roomCode().equals(s.getRoomCode()))
                throw ApiException.conflict("Phòng " + r.roomCode() + " đang được dùng tiết này");
        }
    }

    private boolean differentSemester(String a, String b) {
        return a != null && b != null && !a.equals(b);
    }

    public void delete(String id) {
        if (!slots.existsById(id)) throw ApiException.notFound("Tiết học");
        slots.deleteById(id);
    }

    /** Cross-domain: lấy 1 tiết học (điểm danh cần subjectName/periodNo/classId). */
    public TimetableSlot findSlot(String id) {
        return id == null ? null : slots.findById(id).orElse(null);
    }

    /** Seed raw (bỏ qua conflict-check) — dùng bởi DataSeeder. */
    public void seedSlots(List<TimetableSlot> list) {
        slots.saveAll(list);
    }

    private int dayIndex(String d) {
        return switch (d == null ? "" : d.toUpperCase()) {
            case "MON" -> 1; case "TUE" -> 2; case "WED" -> 3; case "THU" -> 4;
            case "FRI" -> 5; case "SAT" -> 6; case "SUN" -> 7; default -> 9;
        };
    }
}
