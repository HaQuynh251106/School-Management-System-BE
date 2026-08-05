package com.sse.app.academic.timetable;

import com.sse.app.academic.timetable.TimetableDtos.TimetableChange;
import com.sse.app.academic.timetable.TimetableDtos.TimetableVersionSlot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimetablePublicationDiffServiceTest {
    private final TimetablePublicationDiffService service = new TimetablePublicationDiffService();

    @Test
    void detectsMoveTeacherRoomAddAndRemoveWithoutReportingExactSlots() {
        List<TimetableVersionSlot> previous = List.of(
                slot("old-1", "c1", "10A1", "math", "Toán", "t1", "Cô An", "A101", "MON", 1),
                slot("old-2", "c1", "10A1", "math", "Toán", "t1", "Cô An", "A101", "TUE", 1),
                slot("old-3", "c1", "10A1", "lit", "Ngữ văn", "t2", "Thầy Bình", "A101", "WED", 2),
                slot("old-4", "c2", "10A2", "eng", "Tiếng Anh", "t3", "Cô Chi", "A102", "THU", 3));
        List<TimetableVersionSlot> current = List.of(
                slot("new-1", "c1", "10A1", "math", "Toán", "t1", "Cô An", "A101", "MON", 1),
                slot("new-2", "c1", "10A1", "math", "Toán", "t1", "Cô An", "A101", "FRI", 1),
                slot("new-3", "c1", "10A1", "lit", "Ngữ văn", "t4", "Cô Dung", "A105", "WED", 2),
                slot("new-4", "c2", "10A2", "phys", "Vật lý", "t5", "Thầy Em", "A102", "THU", 4));

        List<TimetableChange> changes = service.compare(previous, current);

        assertEquals(4, changes.size());
        assertTrue(changes.stream().anyMatch(change -> "MOVED".equals(change.type())));
        assertTrue(changes.stream().anyMatch(change -> "TEACHER_AND_ROOM_CHANGED".equals(change.type())));
        assertTrue(changes.stream().anyMatch(change -> "REMOVED".equals(change.type())));
        assertTrue(changes.stream().anyMatch(change -> "ADDED".equals(change.type())));
    }

    @Test
    void identicalVersionsHaveNoChanges() {
        TimetableVersionSlot slot = slot("one", "c1", "10A1", "math", "Toán",
                "t1", "Cô An", "A101", "MON", 1);
        assertTrue(service.compare(List.of(slot), List.of(slot)).isEmpty());
    }

    private TimetableVersionSlot slot(String id, String classId, String classCode, String subjectId,
                                      String subjectName, String teacherId, String teacherName,
                                      String roomCode, String day, int period) {
        return new TimetableVersionSlot(id, "plan", classId, classCode, "MORNING", subjectId, subjectName,
                teacherId, teacherName, roomCode, day, period, "07:00", "07:45", false);
    }
}
