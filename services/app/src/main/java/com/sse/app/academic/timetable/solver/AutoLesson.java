package com.sse.app.academic.timetable.solver;

import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.entity.PlanningPin;
import ai.timefold.solver.core.api.domain.lookup.PlanningId;
import ai.timefold.solver.core.api.domain.valuerange.ValueRangeProvider;
import ai.timefold.solver.core.api.domain.variable.PlanningVariable;

import java.util.List;
import java.util.Map;

@PlanningEntity
public class AutoLesson {
    @PlanningId
    private String id;
    private String assignmentId;
    private String classId;
    private String subjectId;
    private String subjectName;
    private String teacherId;
    private String teacherName;
    private String requiredRoomType;
    private String homeRoomId;
    private String subjectGroup;
    private boolean heavySubject;
    private boolean morningPriority;
    private boolean latePriority;
    private int teachingDayCount;
    private String teacherRestDay;
    private int studentCount;
    private int lessonIndex;
    private int maxPeriodsPerDay;
    private boolean pinned;
    private boolean blockLesson;
    private boolean activity;
    private int mainShiftStartPeriod;
    private List<AutoRoom> roomRange;
    private List<AutoTimeslot> timeslotRange;
    private Map<String, Integer> existingTeacherDailyLoad = Map.of();

    @PlanningVariable(valueRangeProviderRefs = "lessonTimeslotRange")
    private AutoTimeslot timeslot;

    @PlanningVariable(valueRangeProviderRefs = "lessonRoomRange")
    private AutoRoom room;

    public AutoLesson() {}

    public AutoLesson(String id, String assignmentId, String classId,
                      String subjectId, String subjectName, String teacherId,
                      String teacherName, String requiredRoomType,
                      String homeRoomId, String subjectGroup,
                      boolean heavySubject, boolean morningPriority, boolean latePriority,
                      int teachingDayCount, String teacherRestDay,
                      int studentCount, int lessonIndex, int maxPeriodsPerDay,
                      boolean pinned) {
        this.id = id;
        this.assignmentId = assignmentId;
        this.classId = classId;
        this.subjectId = subjectId;
        this.subjectName = subjectName;
        this.teacherId = teacherId;
        this.teacherName = teacherName;
        this.requiredRoomType = requiredRoomType;
        this.homeRoomId = homeRoomId;
        this.subjectGroup = subjectGroup;
        this.heavySubject = heavySubject;
        this.morningPriority = morningPriority;
        this.latePriority = latePriority;
        this.teachingDayCount = teachingDayCount;
        this.teacherRestDay = teacherRestDay;
        this.studentCount = studentCount;
        this.lessonIndex = lessonIndex;
        this.maxPeriodsPerDay = maxPeriodsPerDay;
        this.pinned = pinned;
    }

    @PlanningPin
    public boolean isPinned() { return pinned; }
    public void setPinned(boolean pinned) { this.pinned = pinned; }
    public String getId() { return id; }
    public String getAssignmentId() { return assignmentId; }
    public String getClassId() { return classId; }
    public String getSubjectId() { return subjectId; }
    public String getSubjectName() { return subjectName; }
    public String getTeacherId() { return teacherId; }
    public String getTeacherName() { return teacherName; }
    public String getRequiredRoomType() { return requiredRoomType; }
    public String getHomeRoomId() { return homeRoomId; }
    public String getSubjectGroup() { return subjectGroup; }
    public boolean isHeavySubject() { return heavySubject; }
    public boolean isMorningPriority() { return morningPriority; }
    public boolean isLatePriority() { return latePriority; }
    public int getTeachingDayCount() { return teachingDayCount; }
    public String getTeacherRestDay() { return teacherRestDay; }
    public int getStudentCount() { return studentCount; }
    public int getLessonIndex() { return lessonIndex; }
    public int getMaxPeriodsPerDay() { return maxPeriodsPerDay; }
    public boolean isBlockLesson() { return blockLesson; }
    public void setBlockLesson(boolean blockLesson) { this.blockLesson = blockLesson; }
    public boolean isActivity() { return activity; }
    public void setActivity(boolean activity) { this.activity = activity; }
    public int getMainShiftStartPeriod() { return mainShiftStartPeriod; }
    public void setMainShiftStartPeriod(int mainShiftStartPeriod) {
        this.mainShiftStartPeriod = mainShiftStartPeriod;
    }
    public AutoTimeslot getTimeslot() { return timeslot; }
    public void setTimeslot(AutoTimeslot timeslot) { this.timeslot = timeslot; }
    public AutoRoom getRoom() { return room; }
    public void setRoom(AutoRoom room) { this.room = room; }
    @ValueRangeProvider(id = "lessonTimeslotRange")
    public List<AutoTimeslot> getTimeslotRange() { return timeslotRange; }
    public void setTimeslotRange(List<AutoTimeslot> timeslotRange) {
        this.timeslotRange = timeslotRange;
    }
    @ValueRangeProvider(id = "lessonRoomRange")
    public List<AutoRoom> getRoomRange() { return roomRange; }
    public void setRoomRange(List<AutoRoom> roomRange) { this.roomRange = roomRange; }
    public void setExistingTeacherDailyLoad(Map<String, Integer> values) {
        this.existingTeacherDailyLoad = values == null ? Map.of() : Map.copyOf(values);
    }
    public int getExistingTeacherLoadOnAssignedDay() {
        return timeslot == null ? 0
                : existingTeacherDailyLoad.getOrDefault(timeslot.getDayOfWeek(), 0);
    }
}
