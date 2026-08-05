package com.sse.app.academic.timetable.solver;

import ai.timefold.solver.core.api.domain.solution.PlanningEntityCollectionProperty;
import ai.timefold.solver.core.api.domain.solution.PlanningScore;
import ai.timefold.solver.core.api.domain.solution.PlanningSolution;
import ai.timefold.solver.core.api.domain.solution.ProblemFactCollectionProperty;
import ai.timefold.solver.core.api.domain.valuerange.ValueRangeProvider;
import ai.timefold.solver.core.api.score.buildin.hardsoft.HardSoftScore;

import java.util.List;

@PlanningSolution
public class AutoTimetable {
    @ValueRangeProvider(id = "timeslotRange")
    @ProblemFactCollectionProperty
    private List<AutoTimeslot> timeslots;

    @ValueRangeProvider(id = "roomRange")
    @ProblemFactCollectionProperty
    private List<AutoRoom> rooms;

    @PlanningEntityCollectionProperty
    private List<AutoLesson> lessons;

    @PlanningScore
    private HardSoftScore score;

    public AutoTimetable() {}

    public AutoTimetable(List<AutoTimeslot> timeslots, List<AutoRoom> rooms,
                         List<AutoLesson> lessons) {
        this.timeslots = timeslots;
        this.rooms = rooms;
        this.lessons = lessons;
    }

    public List<AutoTimeslot> getTimeslots() { return timeslots; }
    public List<AutoRoom> getRooms() { return rooms; }
    public List<AutoLesson> getLessons() { return lessons; }
    public HardSoftScore getScore() { return score; }
}
