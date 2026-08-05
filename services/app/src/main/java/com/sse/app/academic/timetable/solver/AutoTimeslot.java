package com.sse.app.academic.timetable.solver;

import ai.timefold.solver.core.api.domain.lookup.PlanningId;

public class AutoTimeslot {
    @PlanningId
    private String id;
    private String dayOfWeek;
    private int periodNo;
    private String startTime;
    private String endTime;

    public AutoTimeslot() {}

    public AutoTimeslot(String id, String dayOfWeek, int periodNo,
                        String startTime, String endTime) {
        this.id = id;
        this.dayOfWeek = dayOfWeek;
        this.periodNo = periodNo;
        this.startTime = startTime;
        this.endTime = endTime;
    }

    public String getId() { return id; }
    public String getDayOfWeek() { return dayOfWeek; }
    public int getPeriodNo() { return periodNo; }
    public String getStartTime() { return startTime; }
    public String getEndTime() { return endTime; }
}
