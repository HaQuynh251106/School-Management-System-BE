package com.sse.app.academic.timetable;

import com.sse.app.common.ApiException;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One authoritative rule set for automatic, manual and published timetables. */
public final class TimetableRulePolicy {
    public static final int MAX_SUBJECT_PERIODS_PER_CLASS_DAY = 2;

    private TimetableRulePolicy() {}

    public static void assertCanAdd(SlotView candidate, List<SlotView> current, String ignoredId) {
        List<SlotView> effective = current.stream()
                .filter(item -> ignoredId == null || !ignoredId.equals(item.id()))
                .filter(item -> sameSemester(candidate, item))
                .toList();
        for (SlotView existing : effective) {
            if (!sameDay(candidate, existing) || !overlaps(candidate, existing)) continue;
            if (candidate.classId().equals(existing.classId())) {
                throw ApiException.conflict("Lớp đã có tiết khác trùng khung giờ");
            }
            if (candidate.teacherId().equals(existing.teacherId())) {
                throw ApiException.conflict("Giáo viên đã kín lịch do trùng khung giờ");
            }
            if (present(candidate.roomCode()) && candidate.roomCode().equals(existing.roomCode())) {
                throw ApiException.conflict("Phòng học đã được sử dụng trong khung giờ này");
            }
        }

        long sameSubjectDay = effective.stream()
                .filter(item -> sameDay(candidate, item))
                .filter(item -> candidate.classId().equals(item.classId()))
                .filter(item -> candidate.subjectId().equals(item.subjectId()))
                .count();
        if (sameSubjectDay >= MAX_SUBJECT_PERIODS_PER_CLASS_DAY) {
            throw ApiException.conflict("Mỗi lớp chỉ được học tối đa "
                    + MAX_SUBJECT_PERIODS_PER_CLASS_DAY + " tiết cùng một môn trong một ngày");
        }
    }

    public static Validation validate(List<SlotView> slots) {
        List<String> messages = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            SlotView left = slots.get(i);
            for (int j = i + 1; j < slots.size(); j++) {
                SlotView right = slots.get(j);
                if (!sameSemester(left, right) || !sameDay(left, right) || !overlaps(left, right)) continue;
                if (left.classId().equals(right.classId())) messages.add("trùng lịch lớp");
                else if (left.teacherId().equals(right.teacherId())) messages.add("trùng lịch giáo viên");
                else if (present(left.roomCode()) && left.roomCode().equals(right.roomCode())) {
                    messages.add("trùng lịch phòng");
                }
            }
        }

        Map<String, Integer> dailySubjectLoads = new HashMap<>();
        for (SlotView slot : slots) {
            String key = clean(slot.semesterId()) + "|" + clean(slot.classId()) + "|"
                    + clean(slot.subjectId()) + "|" + normalizedDay(slot.dayOfWeek());
            dailySubjectLoads.merge(key, 1, Integer::sum);
        }
        long overloaded = dailySubjectLoads.values().stream()
                .filter(value -> value > MAX_SUBJECT_PERIODS_PER_CLASS_DAY).count();
        if (overloaded > 0) messages.add(overloaded + " nhóm môn/lớp/ngày vượt "
                + MAX_SUBJECT_PERIODS_PER_CLASS_DAY + " tiết");
        return new Validation(messages.isEmpty(), messages.stream().distinct().toList());
    }

    private static boolean sameSemester(SlotView left, SlotView right) {
        return !present(left.semesterId()) || !present(right.semesterId())
                || left.semesterId().equals(right.semesterId());
    }

    private static boolean sameDay(SlotView left, SlotView right) {
        return normalizedDay(left.dayOfWeek()).equals(normalizedDay(right.dayOfWeek()));
    }

    private static boolean overlaps(SlotView left, SlotView right) {
        LocalTime leftStart = parse(left.startTime());
        LocalTime leftEnd = parse(left.endTime());
        LocalTime rightStart = parse(right.startTime());
        LocalTime rightEnd = parse(right.endTime());
        if (leftStart == null || leftEnd == null || rightStart == null || rightEnd == null) {
            return left.periodNo() != null && left.periodNo().equals(right.periodNo());
        }
        return leftStart.isBefore(rightEnd) && rightStart.isBefore(leftEnd);
    }

    private static LocalTime parse(String value) {
        try {
            return present(value) ? LocalTime.parse(value.trim()) : null;
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static String normalizedDay(String value) {
        return clean(value).toUpperCase();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    public record SlotView(String id, String classId, String subjectId, String teacherId,
                           String roomCode, String dayOfWeek, Integer periodNo,
                           String startTime, String endTime, String semesterId) {}

    public record Validation(boolean valid, List<String> messages) {
        public String summary() {
            return valid ? null : "Thời khóa biểu chưa hợp lệ: " + String.join(", ", messages);
        }
    }
}
