package com.sse.app.academic.timetable.solver;

import ai.timefold.solver.core.api.score.buildin.hardsoft.HardSoftScore;
import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.SolverConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoTimetableSolverTest {

    @Test
    void createsFeasibleScheduleWithoutClassTeacherOrRoomConflicts() {
        AutoTimeslot mondayOne = new AutoTimeslot(
                "MON-1", "MON", 1, "07:00", "07:45");
        AutoTimeslot mondayTwo = new AutoTimeslot(
                "MON-2", "MON", 2, "07:50", "08:35");
        AutoTimeslot tuesdayOne = new AutoTimeslot(
                "TUE-1", "TUE", 1, "07:00", "07:45");
        AutoRoom general = new AutoRoom("room-general", "P101", "GENERAL", 45);
        AutoRoom otherClassroom = new AutoRoom("room-other", "P102", "GENERAL", 45);
        AutoRoom laboratory = new AutoRoom("room-lab", "LAB1", "LAB", 40);

        List<AutoLesson> lessons = List.of(
                lesson("l1", "10A1", "PHYS", "teacher-physics", "LAB", 1),
                lesson("l2", "10A1", "MATH", "teacher-math", "GENERAL", 1),
                lesson("l3", "10A2", "PHYS", "teacher-physics", "LAB", 1));
        lessons.forEach(lesson -> lesson.setRoomRange(
                "GENERAL".equals(lesson.getRequiredRoomType())
                        ? List.of(general) : List.of(laboratory)));
        lessons.forEach(lesson -> lesson.setTimeslotRange(
                List.of(mondayOne, mondayTwo, tuesdayOne)));

        SolverFactory<AutoTimetable> factory = SolverFactory.create(
                new SolverConfig()
                        .withSolutionClass(AutoTimetable.class)
                        .withEntityClasses(AutoLesson.class)
                        .withConstraintProviderClass(
                                AutoTimetableConstraintProvider.class)
                        .withTerminationSpentLimit(Duration.ofSeconds(2)));
        Solver<AutoTimetable> solver = factory.buildSolver();
        AutoTimetable solved = solver.solve(new AutoTimetable(
                List.of(mondayOne, mondayTwo, tuesdayOne),
                List.of(general, otherClassroom, laboratory), lessons));

        assertNotNull(solved.getScore());
        assertEquals(0, solved.getScore().hardScore());
        assertTrue(solved.getScore().compareTo(HardSoftScore.ZERO) >= 0);
        assertEquals(3, solved.getLessons().stream()
                .map(AutoLesson::getTimeslot).filter(java.util.Objects::nonNull).count());

        Set<String> classSlots = new HashSet<>();
        Set<String> teacherSlots = new HashSet<>();
        Set<String> roomSlots = new HashSet<>();
        for (AutoLesson item : solved.getLessons()) {
            assertNotNull(item.getRoom());
            assertNotNull(item.getTimeslot());
            String timeslot = item.getTimeslot().getId();
            assertTrue(classSlots.add(item.getClassId() + ":" + timeslot));
            assertTrue(teacherSlots.add(item.getTeacherId() + ":" + timeslot));
            assertTrue(roomSlots.add(item.getRoom().getId() + ":" + timeslot));
            if ("LAB".equals(item.getRequiredRoomType())) {
                assertEquals("LAB", item.getRoom().getRoomType());
            } else {
                assertEquals(item.getHomeRoomId(), item.getRoom().getId());
            }
        }
    }

    @Test
    void ignoresConflictsBetweenImmutableExternalSlots() {
        AutoTimeslot mondayOne = new AutoTimeslot(
                "MON-1", "MON", 1, "07:00", "07:45");
        AutoTimeslot tuesdayOne = new AutoTimeslot(
                "TUE-1", "TUE", 1, "07:00", "07:45");
        AutoRoom general = new AutoRoom("room-general", "P101", "GENERAL", 45);

        AutoLesson firstPinned = pinnedLesson(
                "p1", "10A1", "teacher-old-1", mondayOne, general);
        AutoLesson secondPinned = pinnedLesson(
                "p2", "10A1", "teacher-old-1", mondayOne, general);
        AutoLesson target = lesson(
                "target", "11A1", "MATH", "teacher-new", "GENERAL", 1);
        target.setTimeslotRange(List.of(tuesdayOne));
        target.setRoomRange(List.of(general));

        SolverFactory<AutoTimetable> factory = SolverFactory.create(
                new SolverConfig()
                        .withSolutionClass(AutoTimetable.class)
                        .withEntityClasses(AutoLesson.class)
                        .withConstraintProviderClass(
                                AutoTimetableConstraintProvider.class)
                        .withTerminationSpentLimit(Duration.ofSeconds(1)));
        AutoTimetable solved = factory.buildSolver().solve(new AutoTimetable(
                List.of(mondayOne, tuesdayOne), List.of(general),
                List.of(firstPinned, secondPinned, target)));

        assertNotNull(solved.getScore());
        assertEquals(0, solved.getScore().hardScore());
    }

    @Test
    void afternoonMainShiftStartsAfterMondayFlagCeremony() {
        AutoTimeslot flagSlot = new AutoTimeslot(
                "MON-6", "MON", 6, "13:30", "14:15");
        AutoTimeslot firstLessonSlot = new AutoTimeslot(
                "MON-7", "MON", 7, "14:20", "15:05");
        AutoTimeslot secondLessonSlot = new AutoTimeslot(
                "MON-8", "MON", 8, "15:15", "16:00");
        AutoRoom room = new AutoRoom("room-general", "P201", "GENERAL", 45);

        AutoLesson flag = lesson("flag", "10A1", "FLAG", "homeroom", "GENERAL", 1);
        flag.setActivity(true);
        flag.setMainShiftStartPeriod(6);
        flag.setTimeslotRange(List.of(flagSlot));
        flag.setRoomRange(List.of(room));
        AutoLesson first = lesson("first", "10A1", "MATH", "teacher-math", "GENERAL", 1);
        first.setMainShiftStartPeriod(6);
        first.setTimeslotRange(List.of(firstLessonSlot));
        first.setRoomRange(List.of(room));
        AutoLesson second = lesson("second", "10A1", "LIT", "teacher-lit", "GENERAL", 1);
        second.setMainShiftStartPeriod(6);
        second.setTimeslotRange(List.of(secondLessonSlot));
        second.setRoomRange(List.of(room));

        SolverFactory<AutoTimetable> factory = SolverFactory.create(
                new SolverConfig()
                        .withSolutionClass(AutoTimetable.class)
                        .withEntityClasses(AutoLesson.class)
                        .withConstraintProviderClass(AutoTimetableConstraintProvider.class)
                        .withTerminationSpentLimit(Duration.ofSeconds(1)));
        AutoTimetable solved = factory.buildSolver().solve(new AutoTimetable(
                List.of(flagSlot, firstLessonSlot, secondLessonSlot), List.of(room),
                List.of(flag, first, second)));

        assertNotNull(solved.getScore());
        assertEquals(0, solved.getScore().hardScore());
    }

    private AutoLesson lesson(String id, String classId, String subjectId,
                              String teacherId, String roomType, int index) {
        AutoLesson lesson = new AutoLesson(id, "assignment-" + id, classId,
                subjectId, subjectId, teacherId, teacherId, roomType,
                "room-general", "NATURAL",
                "MATH".equals(subjectId) || "PHYS".equals(subjectId),
                "MATH".equals(subjectId), false,
                2, "FRI", 35, index, 5, false);
        lesson.setMainShiftStartPeriod(1);
        return lesson;
    }

    private AutoLesson pinnedLesson(String id, String classId, String teacherId,
                                    AutoTimeslot timeslot, AutoRoom room) {
        AutoLesson lesson = new AutoLesson(id, "assignment-" + id, classId,
                "OLD", "OLD", teacherId, teacherId, "GENERAL",
                null, "OTHER", false, false, false,
                2, null, 35, 1, 1, true);
        lesson.setTimeslot(timeslot);
        lesson.setRoom(room);
        lesson.setTimeslotRange(List.of(timeslot));
        lesson.setRoomRange(List.of(room));
        return lesson;
    }
}
