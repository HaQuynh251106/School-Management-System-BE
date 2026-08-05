package com.sse.app.academic.timetable;

import com.sse.app.academic.timetable.TimetableDtos.TimetableChange;
import com.sse.app.academic.timetable.TimetableDtos.TimetableVersionSlot;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiPredicate;

/** So sánh hai phiên bản lịch theo môn/lớp và diễn giải thay đổi cho người dùng. */
@Service
public class TimetablePublicationDiffService {

    public List<TimetableChange> compare(List<TimetableVersionSlot> previous,
                                         List<TimetableVersionSlot> current) {
        List<TimetableVersionSlot> oldRemaining = new ArrayList<>(previous == null ? List.of() : previous);
        List<TimetableVersionSlot> newRemaining = new ArrayList<>(current == null ? List.of() : current);
        removeExactMatches(oldRemaining, newRemaining);

        Map<String, List<TimetableVersionSlot>> oldGroups = group(oldRemaining);
        Map<String, List<TimetableVersionSlot>> newGroups = group(newRemaining);
        List<String> keys = new ArrayList<>();
        keys.addAll(oldGroups.keySet());
        newGroups.keySet().stream().filter(key -> !keys.contains(key)).forEach(keys::add);

        List<TimetableChange> changes = new ArrayList<>();
        for (String key : keys) {
            List<TimetableVersionSlot> oldItems = new ArrayList<>(oldGroups.getOrDefault(key, List.of()));
            List<TimetableVersionSlot> newItems = new ArrayList<>(newGroups.getOrDefault(key, List.of()));
            pair(oldItems, newItems, this::samePosition, changes);
            pair(oldItems, newItems, this::sameTeacherAndRoom, changes);
            while (!oldItems.isEmpty() && !newItems.isEmpty()) {
                changes.add(changed(oldItems.remove(0), newItems.remove(0)));
            }
            oldItems.forEach(item -> changes.add(removed(item)));
            newItems.forEach(item -> changes.add(added(item)));
        }
        return changes.stream().sorted(Comparator
                .comparing(TimetableChange::classCode, Comparator.nullsLast(String::compareToIgnoreCase))
                .thenComparing(TimetableChange::subjectName, Comparator.nullsLast(String::compareToIgnoreCase))
                .thenComparing(change -> dayOrder(change.newDayOfWeek() == null
                        ? change.previousDayOfWeek() : change.newDayOfWeek()))
                .thenComparing(change -> change.newPeriodNo() == null
                        ? value(change.previousPeriodNo()) : value(change.newPeriodNo())))
                .toList();
    }

    private void removeExactMatches(List<TimetableVersionSlot> previous, List<TimetableVersionSlot> current) {
        for (int oldIndex = previous.size() - 1; oldIndex >= 0; oldIndex--) {
            TimetableVersionSlot oldSlot = previous.get(oldIndex);
            int match = -1;
            for (int newIndex = 0; newIndex < current.size(); newIndex++) {
                if (exact(oldSlot, current.get(newIndex))) {
                    match = newIndex;
                    break;
                }
            }
            if (match >= 0) {
                previous.remove(oldIndex);
                current.remove(match);
            }
        }
    }

    private Map<String, List<TimetableVersionSlot>> group(List<TimetableVersionSlot> slots) {
        Map<String, List<TimetableVersionSlot>> groups = new LinkedHashMap<>();
        slots.stream().sorted(Comparator
                .comparing(TimetableVersionSlot::classCode, Comparator.nullsLast(String::compareToIgnoreCase))
                .thenComparing(TimetableVersionSlot::subjectName, Comparator.nullsLast(String::compareToIgnoreCase))
                .thenComparing(slot -> dayOrder(slot.dayOfWeek()))
                .thenComparing(slot -> value(slot.periodNo())))
                .forEach(slot -> groups.computeIfAbsent(
                        clean(slot.classId()) + "|" + clean(slot.subjectId()), ignored -> new ArrayList<>()).add(slot));
        return groups;
    }

    private void pair(List<TimetableVersionSlot> previous, List<TimetableVersionSlot> current,
                      BiPredicate<TimetableVersionSlot, TimetableVersionSlot> predicate,
                      List<TimetableChange> changes) {
        for (int oldIndex = previous.size() - 1; oldIndex >= 0; oldIndex--) {
            TimetableVersionSlot oldSlot = previous.get(oldIndex);
            int match = -1;
            for (int newIndex = 0; newIndex < current.size(); newIndex++) {
                if (predicate.test(oldSlot, current.get(newIndex))) {
                    match = newIndex;
                    break;
                }
            }
            if (match >= 0) {
                previous.remove(oldIndex);
                changes.add(changed(oldSlot, current.remove(match)));
            }
        }
    }

    private TimetableChange changed(TimetableVersionSlot previous, TimetableVersionSlot current) {
        boolean moved = !samePosition(previous, current);
        boolean teacherChanged = !Objects.equals(previous.teacherId(), current.teacherId());
        boolean roomChanged = !Objects.equals(clean(previous.roomCode()), clean(current.roomCode()));
        String type = moved ? "MOVED" : teacherChanged && roomChanged ? "TEACHER_AND_ROOM_CHANGED"
                : teacherChanged ? "TEACHER_CHANGED" : "ROOM_CHANGED";
        StringBuilder summary = new StringBuilder(current.classCode()).append(" · ")
                .append(current.subjectName()).append(": ");
        if (moved) summary.append(slotLabel(previous)).append(" → ").append(slotLabel(current));
        if (teacherChanged) append(summary, "đổi giáo viên " + previous.teacherName() + " → " + current.teacherName());
        if (roomChanged) append(summary, "đổi phòng " + room(previous.roomCode()) + " → " + room(current.roomCode()));
        return change(type, previous, current, summary.toString());
    }

    private TimetableChange added(TimetableVersionSlot current) {
        return change("ADDED", null, current, current.classCode() + " · " + current.subjectName()
                + ": thêm " + slotLabel(current) + " · " + current.teacherName() + " · " + room(current.roomCode()));
    }

    private TimetableChange removed(TimetableVersionSlot previous) {
        return change("REMOVED", previous, null, previous.classCode() + " · " + previous.subjectName()
                + ": bỏ " + slotLabel(previous) + " · " + previous.teacherName() + " · " + room(previous.roomCode()));
    }

    private TimetableChange change(String type, TimetableVersionSlot previous,
                                   TimetableVersionSlot current, String summary) {
        TimetableVersionSlot base = current == null ? previous : current;
        return new TimetableChange(type, base.classId(), base.classCode(), base.subjectId(), base.subjectName(),
                previous == null ? null : previous.teacherId(), previous == null ? null : previous.teacherName(),
                current == null ? null : current.teacherId(), current == null ? null : current.teacherName(),
                previous == null ? null : previous.roomCode(), current == null ? null : current.roomCode(),
                previous == null ? null : previous.dayOfWeek(), previous == null ? null : previous.periodNo(),
                current == null ? null : current.dayOfWeek(), current == null ? null : current.periodNo(), summary);
    }

    private boolean exact(TimetableVersionSlot left, TimetableVersionSlot right) {
        return Objects.equals(left.classId(), right.classId())
                && Objects.equals(left.subjectId(), right.subjectId())
                && Objects.equals(left.teacherId(), right.teacherId())
                && Objects.equals(clean(left.roomCode()), clean(right.roomCode()))
                && samePosition(left, right);
    }

    private boolean samePosition(TimetableVersionSlot left, TimetableVersionSlot right) {
        return Objects.equals(clean(left.dayOfWeek()), clean(right.dayOfWeek()))
                && Objects.equals(left.periodNo(), right.periodNo());
    }

    private boolean sameTeacherAndRoom(TimetableVersionSlot left, TimetableVersionSlot right) {
        return Objects.equals(left.teacherId(), right.teacherId())
                && Objects.equals(clean(left.roomCode()), clean(right.roomCode()));
    }

    private String slotLabel(TimetableVersionSlot slot) {
        return dayLabel(slot.dayOfWeek()) + ", tiết " + slot.periodNo();
    }

    private String dayLabel(String day) {
        return switch (clean(day).toUpperCase()) {
            case "MON" -> "Thứ 2";
            case "TUE" -> "Thứ 3";
            case "WED" -> "Thứ 4";
            case "THU" -> "Thứ 5";
            case "FRI" -> "Thứ 6";
            default -> day == null ? "Chưa xác định" : day;
        };
    }

    private int dayOrder(String day) {
        return switch (clean(day).toUpperCase()) {
            case "MON" -> 1;
            case "TUE" -> 2;
            case "WED" -> 3;
            case "THU" -> 4;
            case "FRI" -> 5;
            default -> 9;
        };
    }

    private void append(StringBuilder summary, String text) {
        if (!summary.toString().endsWith(": ")) summary.append("; ");
        summary.append(text);
    }

    private String room(String value) {
        return value == null || value.isBlank() ? "chưa có phòng" : value;
    }

    private int value(Integer number) {
        return number == null ? 99 : number;
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
