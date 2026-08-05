package com.sse.app.academic.timetable;

import com.sse.app.common.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Bộ quy tắc duy nhất cho tạo tay, tự động, kiểm tra bản nháp và phát hành lịch. */
@Service
@RequiredArgsConstructor
public class TimetableBusinessRuleService {
    private final TeacherLoadRegistrationRepository loadRegistrations;
    private final TeacherWorkloadPolicyService workloadPolicies;
    private final TeacherScheduleRestrictionService scheduleRestrictions;

    public void assertCanAdd(TimetableRulePolicy.SlotView candidate,
                             List<TimetableRulePolicy.SlotView> current,
                             String ignoredId) {
        TimetableRulePolicy.assertCanAdd(candidate, current, ignoredId);
        List<TimetableRulePolicy.SlotView> effective = new ArrayList<>(current.stream()
                .filter(item -> ignoredId == null || !ignoredId.equals(item.id()))
                .filter(item -> candidate.semesterId().equals(item.semesterId())).toList());
        effective.add(candidate);
        List<String> messages = teacherLoadViolations(effective, candidate.teacherId(), candidate.semesterId());
        if (!messages.isEmpty()) throw ApiException.conflict(String.join("; ", messages));
    }

    public TimetableRulePolicy.Validation validate(List<TimetableRulePolicy.SlotView> slots) {
        TimetableRulePolicy.Validation base = TimetableRulePolicy.validate(slots);
        List<String> messages = new ArrayList<>(base.messages());
        slots.stream().map(item -> item.semesterId() + "|" + item.teacherId()).distinct().forEach(key -> {
            String[] parts = key.split("\\|", 2);
            messages.addAll(teacherLoadViolations(slots, parts[1], parts[0]));
        });
        return new TimetableRulePolicy.Validation(messages.isEmpty(), messages.stream().distinct().toList());
    }

    private List<String> teacherLoadViolations(List<TimetableRulePolicy.SlotView> slots,
                                               String teacherId, String semesterId) {
        List<String> messages = new ArrayList<>();
        // Historical semesters without workload snapshots remain readable. For current
        // semesters the snapshot is system-managed; teachers never approve their own quota.
        if (!loadRegistrations.existsBySemesterId(semesterId)) return messages;
        TeacherLoadRegistration load = loadRegistrations.findByTeacherIdAndSemesterId(teacherId, semesterId)
                .orElse(null);
        if (load == null) {
            messages.add("Chưa có bản ghi định mức hệ thống của giáo viên");
            return messages;
        }
        workloadPolicies.apply(load);
        List<TimetableRulePolicy.SlotView> teacherSlots = slots.stream()
                .filter(item -> teacherId.equals(item.teacherId()) && semesterId.equals(item.semesterId())).toList();
        if (teacherSlots.size() > load.getMaxWeeklyPeriods()) {
            messages.add(load.getTeacherName() + " có " + teacherSlots.size()
                    + " tiết/tuần, vượt giới hạn tải hệ thống " + load.getMaxWeeklyPeriods() + " tiết/tuần");
        }
        Set<String> unavailable = scheduleRestrictions.approvedSlots(teacherId, semesterId);
        long blocked = teacherSlots.stream().filter(slot -> isUnavailable(slot, unavailable)).count();
        if (blocked > 0) messages.add(load.getTeacherName() + " có " + blocked
                + " tiết nằm trong khung giờ hạn chế đã được Giáo vụ duyệt");

        Map<String, List<TimetableRulePolicy.SlotView>> byDay = new HashMap<>();
        teacherSlots.forEach(slot -> byDay.computeIfAbsent(normalizeDay(slot.dayOfWeek()), ignored -> new ArrayList<>()).add(slot));
        long overloadedDays = byDay.values().stream().filter(day -> day.size() > load.getMaxDailyPeriods()).count();
        if (overloadedDays > 0) messages.add(load.getTeacherName() + " có " + overloadedDays
                + " ngày vượt giới hạn " + load.getMaxDailyPeriods() + " tiết/ngày");
        long consecutive = byDay.values().stream()
                .filter(day -> maxConsecutive(day) > load.getMaxConsecutivePeriods()).count();
        if (consecutive > 0) messages.add(load.getTeacherName() + " có " + consecutive
                + " ngày vượt giới hạn " + load.getMaxConsecutivePeriods() + " tiết liên tiếp");
        return messages;
    }

    private static boolean isUnavailable(TimetableRulePolicy.SlotView slot, Set<String> unavailable) {
        String dayPeriod = normalizeDay(slot.dayOfWeek()) + ":" + slot.periodNo();
        String shift = shift(slot.startTime()) + ":" + dayPeriod;
        return unavailable.contains(dayPeriod) || unavailable.contains(shift);
    }

    private static String shift(String startTime) {
        try {
            return LocalTime.parse(startTime).isBefore(LocalTime.NOON) ? "MORNING" : "AFTERNOON";
        } catch (Exception ignored) {
            return "MORNING";
        }
    }

    private static int maxConsecutive(List<TimetableRulePolicy.SlotView> slots) {
        List<TimetableRulePolicy.SlotView> sorted = slots.stream()
                .sorted(Comparator.comparing(item -> time(item.startTime()))).toList();
        int best = 0, current = 0;
        LocalTime previousEnd = null;
        for (TimetableRulePolicy.SlotView slot : sorted) {
            LocalTime start = time(slot.startTime());
            LocalTime end = time(slot.endTime());
            if (previousEnd != null && start != null && !start.isAfter(previousEnd.plusMinutes(20))) current++;
            else current = 1;
            best = Math.max(best, current);
            previousEnd = end;
        }
        return best;
    }

    private static LocalTime time(String value) {
        try { return value == null ? null : LocalTime.parse(value); }
        catch (Exception ignored) { return null; }
    }

    private static String normalizeDay(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
