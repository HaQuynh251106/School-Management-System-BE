package com.sse.app.academic.timetable.solver;

import ai.timefold.solver.core.api.domain.lookup.PlanningId;

public class AutoRoom {
    @PlanningId
    private String id;
    private String code;
    private String roomType;
    private int capacity;

    public AutoRoom() {}

    public AutoRoom(String id, String code, String roomType, int capacity) {
        this.id = id;
        this.code = code;
        this.roomType = roomType;
        this.capacity = capacity;
    }

    public String getId() { return id; }
    public String getCode() { return code; }
    public String getRoomType() { return roomType; }
    public int getCapacity() { return capacity; }
}
