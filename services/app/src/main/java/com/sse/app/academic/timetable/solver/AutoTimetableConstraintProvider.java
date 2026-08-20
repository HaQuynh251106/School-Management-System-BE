package com.sse.app.academic.timetable.solver;

import ai.timefold.solver.core.api.score.buildin.hardsoft.HardSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintCollectors;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.ConstraintProvider;
import ai.timefold.solver.core.api.score.stream.Joiners;

import java.util.Comparator;
import java.util.List;

public class AutoTimetableConstraintProvider implements ConstraintProvider {
    @Override
    public Constraint[] defineConstraints(ConstraintFactory factory) {
        return new Constraint[] {
                classConflict(factory), teacherConflict(factory), roomConflict(factory),
                roomType(factory), classHomeRoom(factory), roomCapacity(factory), dailyLimit(factory),
                teacherDailyLimit(factory), teacherRestDay(factory), heavySubjectRun(factory),
                regularDailyLimit(factory), minimumRegularDailyLoad(factory),
                compactMainSession(factory), mainSessionStartsAtFirst(factory),
                spreadSubject(factory), alternateSubjectGroups(factory), morningPriority(factory),
                latePriority(factory), teacherGaps(factory)
        };
    }

    Constraint classConflict(ConstraintFactory factory) {
        return factory.forEachUniquePair(AutoLesson.class,
                        Joiners.equal(AutoLesson::getTimeslot),
                        Joiners.equal(AutoLesson::getClassId))
                .filter((left, right) -> !left.isPinned() || !right.isPinned())
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("Không trùng lớp");
    }

    Constraint teacherConflict(ConstraintFactory factory) {
        return factory.forEachUniquePair(AutoLesson.class,
                        Joiners.equal(AutoLesson::getTimeslot),
                        Joiners.equal(AutoLesson::getTeacherId))
                .filter((left, right) -> !left.isPinned() || !right.isPinned())
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("Không trùng giáo viên");
    }

    Constraint roomConflict(ConstraintFactory factory) {
        return factory.forEachUniquePair(AutoLesson.class,
                        Joiners.equal(AutoLesson::getTimeslot),
                        Joiners.equal(AutoLesson::getRoom))
                .filter((left, right) -> !left.isPinned() || !right.isPinned())
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("Không trùng phòng");
    }

    Constraint roomType(ConstraintFactory factory) {
        return factory.forEach(AutoLesson.class)
                .filter(lesson -> !lesson.isPinned()
                        && lesson.getRoom() != null
                        && !"GENERAL".equals(lesson.getRequiredRoomType())
                        && !lesson.getRequiredRoomType().equals(
                                lesson.getRoom().getRoomType()))
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("Đúng loại phòng");
    }

    Constraint roomCapacity(ConstraintFactory factory) {
        return factory.forEach(AutoLesson.class)
                .filter(lesson -> !lesson.isPinned()
                        && lesson.getRoom() != null
                        && lesson.getRoom().getCapacity() < lesson.getStudentCount())
                .penalize(HardSoftScore.ONE_HARD,
                        lesson -> lesson.getStudentCount()
                                - lesson.getRoom().getCapacity())
                .asConstraint("Đủ sức chứa phòng");
    }

    Constraint classHomeRoom(ConstraintFactory factory) {
        return factory.forEach(AutoLesson.class)
                .filter(lesson -> !lesson.isPinned()
                        && "GENERAL".equals(lesson.getRequiredRoomType())
                        && lesson.getHomeRoomId() != null
                        && lesson.getRoom() != null
                        && !lesson.getHomeRoomId().equals(lesson.getRoom().getId()))
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("Môn thường học đúng phòng cố định của lớp");
    }

    Constraint dailyLimit(ConstraintFactory factory) {
        return factory.forEach(AutoLesson.class)
                .filter(lesson -> !lesson.isPinned())
                .groupBy(AutoLesson::getClassId,
                        lesson -> lesson.getTimeslot().getDayOfWeek(),
                        AutoLesson::getMaxPeriodsPerDay,
                        ConstraintCollectors.count())
                .filter((classId, day, max, count) -> count > max)
                .penalize(HardSoftScore.ONE_HARD,
                        (classId, day, max, count) -> count - max)
                .asConstraint("Không vượt số tiết tối đa mỗi ngày");
    }

    Constraint spreadSubject(ConstraintFactory factory) {
        return factory.forEach(AutoLesson.class)
                .filter(lesson -> !lesson.isPinned())
                .groupBy(AutoLesson::getClassId, AutoLesson::getSubjectId,
                        lesson -> lesson.getTimeslot().getDayOfWeek(),
                        ConstraintCollectors.count())
                .filter((classId, subjectId, day, count) -> count > 1)
                .penalize(HardSoftScore.ONE_SOFT,
                        (classId, subjectId, day, count) -> count - 1)
                .asConstraint("Phân bố môn học giữa các ngày");
    }

    Constraint regularDailyLimit(ConstraintFactory factory) {
        return factory.forEach(AutoLesson.class)
                .filter(lesson -> !lesson.isPinned() && !lesson.isBlockLesson()
                        && !lesson.isActivity())
                .groupBy(AutoLesson::getClassId,
                        lesson -> lesson.getTimeslot().getDayOfWeek(),
                        ConstraintCollectors.count())
                .filter((classId, day, count) -> count > 4)
                .penalize(HardSoftScore.ONE_HARD,
                        (classId, day, count) -> count - 4)
                .asConstraint("Tối đa bốn tiết chính mỗi ngày");
    }

    Constraint minimumRegularDailyLoad(ConstraintFactory factory) {
        return factory.forEach(AutoLesson.class)
                .filter(lesson -> !lesson.isPinned() && !lesson.isBlockLesson()
                        && !lesson.isActivity() && lesson.getTeachingDayCount() >= 6)
                .groupBy(AutoLesson::getClassId,
                        lesson -> lesson.getTimeslot().getDayOfWeek(),
                        ConstraintCollectors.count())
                .filter((classId, day, count) -> count < 2)
                .penalize(HardSoftScore.ONE_HARD,
                        (classId, day, count) -> 2 - count)
                .asConstraint("Tối thiểu hai tiết chính mỗi ngày");
    }

    Constraint compactMainSession(ConstraintFactory factory) {
        return factory.forEach(AutoLesson.class)
                .filter(lesson -> !lesson.isPinned() && !lesson.isBlockLesson()
                        && !lesson.isActivity())
                .groupBy(AutoLesson::getClassId,
                        lesson -> lesson.getTimeslot().getDayOfWeek(),
                        ConstraintCollectors.toList())
                .filter((classId, day, lessons) -> internalGapCount(lessons) > 0)
                .penalize(HardSoftScore.ONE_HARD,
                        (classId, day, lessons) -> internalGapCount(lessons))
                .asConstraint("Tiết chính trong buổi phải liền nhau");
    }

    Constraint mainSessionStartsAtFirst(ConstraintFactory factory) {
        return factory.forEach(AutoLesson.class)
                .filter(lesson -> !lesson.isPinned() && !lesson.isBlockLesson()
                        && !lesson.isActivity())
                .groupBy(AutoLesson::getClassId,
                        lesson -> lesson.getTimeslot().getDayOfWeek(),
                        ConstraintCollectors.toList())
                .filter((classId, day, lessons) -> lessons.stream()
                        .mapToInt(lesson -> lesson.getTimeslot().getPeriodNo()).min()
                        .orElse(0) != expectedMainStart(day, lessons))
                .penalize(HardSoftScore.ONE_HARD,
                        (classId, day, lessons) -> Math.abs(lessons.stream()
                                .mapToInt(lesson -> lesson.getTimeslot().getPeriodNo()).min()
                                .orElse(0) - expectedMainStart(day, lessons)))
                .asConstraint("Ngày học phải bắt đầu từ tiết đầu ca");
    }

    private static int expectedMainStart(String day, List<AutoLesson> lessons) {
        int shiftStart = lessons.get(0).getMainShiftStartPeriod();
        // The first period of each class's main shift is reserved for Monday flag ceremony.
        return "MON".equals(day) ? shiftStart + 1 : shiftStart;
    }

    Constraint teacherDailyLimit(ConstraintFactory factory) {
        return factory.forEach(AutoLesson.class)
                .groupBy(AutoLesson::getTeacherId,
                        lesson -> lesson.getTimeslot().getDayOfWeek(),
                        ConstraintCollectors.toList())
                .filter((teacherId, day, lessons) -> lessons.stream()
                        .anyMatch(lesson -> !lesson.isPinned())
                        && lessons.size() + lessons.stream()
                        .mapToInt(AutoLesson::getExistingTeacherLoadOnAssignedDay)
                        .max().orElse(0) > 5)
                .penalize(HardSoftScore.ONE_HARD,
                        (teacherId, day, lessons) -> lessons.size()
                                + lessons.stream()
                                .mapToInt(AutoLesson::getExistingTeacherLoadOnAssignedDay)
                                .max().orElse(0) - 5)
                .asConstraint("Giáo viên không dạy quá 5 tiết mỗi ngày");
    }

    Constraint teacherRestDay(ConstraintFactory factory) {
        return factory.forEach(AutoLesson.class)
                .filter(lesson -> !lesson.isPinned()
                        && lesson.getTeacherRestDay() != null
                        && lesson.getTeacherRestDay().equals(
                                lesson.getTimeslot().getDayOfWeek()))
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("Không xếp tiết vào ngày nghỉ của giáo viên");
    }

    Constraint heavySubjectRun(ConstraintFactory factory) {
        return factory.forEach(AutoLesson.class)
                .filter(lesson -> !lesson.isPinned() && lesson.isHeavySubject())
                .groupBy(AutoLesson::getClassId,
                        lesson -> lesson.getTimeslot().getDayOfWeek(),
                        ConstraintCollectors.toList())
                .filter((classId, day, lessons) -> longestConsecutiveRun(lessons) >= 4)
                .penalize(HardSoftScore.ONE_HARD,
                        (classId, day, lessons) -> longestConsecutiveRun(lessons) - 3)
                .asConstraint("Không xếp từ 4 tiết môn nặng liên tiếp");
    }

    Constraint alternateSubjectGroups(ConstraintFactory factory) {
        return factory.forEachUniquePair(AutoLesson.class,
                        Joiners.equal(AutoLesson::getClassId),
                        Joiners.equal(lesson -> lesson.getTimeslot().getDayOfWeek()),
                        Joiners.equal(AutoLesson::getSubjectGroup))
                .filter((left, right) -> (!left.isPinned() || !right.isPinned())
                        && !"OTHER".equals(left.getSubjectGroup())
                        && Math.abs(left.getTimeslot().getPeriodNo()
                        - right.getTimeslot().getPeriodNo()) == 1)
                .penalize(HardSoftScore.ofSoft(2))
                .asConstraint("Đan xen nhóm môn học");
    }

    Constraint morningPriority(ConstraintFactory factory) {
        return factory.forEach(AutoLesson.class)
                .filter(lesson -> !lesson.isPinned() && lesson.isMorningPriority()
                        && lesson.getTimeslot().getPeriodNo() > 3)
                .penalize(HardSoftScore.ofSoft(3),
                        lesson -> Math.min(4, lesson.getTimeslot().getPeriodNo() - 3))
                .asConstraint("Ưu tiên môn cần tập trung ở tiết 1 đến 3");
    }

    Constraint latePriority(ConstraintFactory factory) {
        return factory.forEach(AutoLesson.class)
                .filter(lesson -> !lesson.isPinned() && lesson.isLatePriority()
                        && lesson.getTimeslot().getPeriodNo() < 4)
                .penalize(HardSoftScore.ofSoft(2),
                        lesson -> 4 - lesson.getTimeslot().getPeriodNo())
                .asConstraint("Ưu tiên môn vận động và môn nhẹ từ tiết 4");
    }

    Constraint teacherGaps(ConstraintFactory factory) {
        return factory.forEach(AutoLesson.class)
                .groupBy(AutoLesson::getTeacherId,
                        lesson -> lesson.getTimeslot().getDayOfWeek(),
                        ConstraintCollectors.toList(),
                        ConstraintCollectors.sum(lesson -> lesson.isPinned() ? 0 : 1))
                .filter((teacherId, day, lessons, targetCount) ->
                        targetCount > 0 && internalGapCount(lessons) > 0)
                .penalize(HardSoftScore.ofSoft(3),
                        (teacherId, day, lessons, targetCount) -> internalGapCount(lessons))
                .asConstraint("Giảm khoảng trống lịch giáo viên");
    }

    public static int longestConsecutiveRun(List<AutoLesson> lessons) {
        List<Integer> periods = lessons.stream()
                .map(lesson -> lesson.getTimeslot().getPeriodNo())
                .distinct().sorted().toList();
        int longest = 0;
        int current = 0;
        int previous = Integer.MIN_VALUE;
        for (int period : periods) {
            current = period == previous + 1 ? current + 1 : 1;
            longest = Math.max(longest, current);
            previous = period;
        }
        return longest;
    }

    static int internalGapCount(List<AutoLesson> lessons) {
        List<Integer> periods = lessons.stream()
                .map(lesson -> lesson.getTimeslot().getPeriodNo())
                .distinct().sorted(Comparator.naturalOrder()).toList();
        if (periods.size() < 2) return 0;
        return periods.get(periods.size() - 1) - periods.get(0) + 1 - periods.size();
    }
}
