package com.sse.app.academic.exam;

import com.sse.app.academic.exam.ExamDtos.AllocateCandidatesRequest;
import com.sse.app.academic.exam.ExamDtos.ResultEntry;
import com.sse.app.academic.exam.ExamDtos.SaveGradingAssignmentRequest;
import com.sse.app.academic.exam.ExamDtos.SavePeriodRequest;
import com.sse.app.academic.exam.ExamDtos.SaveResultsRequest;
import com.sse.app.academic.exam.ExamDtos.SaveRoomRequest;
import com.sse.app.academic.exam.ExamDtos.SaveScheduleRequest;
import com.sse.app.common.ApiException;
import com.sse.app.academic.structure.StructureDtos.CreateRoomRequest;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.notification.Notification;
import com.sse.app.notification.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:exam-grading-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "sse.exam-reminders.enabled=false"
})
@ActiveProfiles("demo")
class ExamGradingWorkflowIntegrationTest {
    private static final ZoneId SCHOOL_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @Autowired ExamService exams;
    @Autowired NotificationService notifications;
    @Autowired StructureService structure;

    @Test
    @Transactional
    void onlyQualifiedAssignedTeacherCanSeeTaskAndRemindersAreIdempotent() {
        LocalDate examDate = LocalDate.now(SCHOOL_ZONE).plusDays(30);
        ExamFixture fixture = createPublishedMathExam(
                "future", examDate, "u-teacher-1", "u-teacher-2");

        List<ExamDtos.EligibleGrader> eligible = exams.eligibleGraders(fixture.scheduleId());
        assertTrue(eligible.stream().anyMatch(item -> "u-teacher-1".equals(item.teacherId())));
        assertFalse(eligible.stream().anyMatch(item -> "u-teacher-2".equals(item.teacherId())));

        List<ExamDtos.TeacherGradingTask> assignedTasks = exams.gradingTasks("u-teacher-1");
        ExamDtos.TeacherGradingTask task = assignedTasks.stream()
                .filter(item -> fixture.scheduleId().equals(item.scheduleId()))
                .findFirst()
                .orElseThrow();
        assertFalse(task.scoreEntryAvailable());
        assertTrue(task.scoreEntryLocked());
        assertTrue(exams.gradingTasks("u-teacher-2").stream()
                .noneMatch(item -> fixture.scheduleId().equals(item.scheduleId())));

        ZonedDateTime examStart = ZonedDateTime.of(
                examDate, LocalTime.of(8, 0), SCHOOL_ZONE);
        assertEquals(2, exams.sendDueDutyNotifications(examStart.minusDays(7)));
        assertEquals(0, exams.sendDueDutyNotifications(examStart.minusDays(7)));

        List<Notification> graderInbox = notifications.inbox("u-teacher-1", false);
        List<Notification> proctorInbox = notifications.inbox("u-teacher-2", false);
        assertTrue(graderInbox.stream().anyMatch(item ->
                "EXAM_GRADING_DUTY".equals(item.getType())));
        assertTrue(proctorInbox.stream().anyMatch(item ->
                "EXAM_PROCTOR_DUTY".equals(item.getType())));

        ZonedDateTime scoreEntryAt = examStart.plusMinutes(60).plusDays(7);
        assertEquals(1, exams.sendDueDutyNotifications(scoreEntryAt));
        assertEquals(0, exams.sendDueDutyNotifications(scoreEntryAt));
        assertTrue(notifications.inbox("u-teacher-1", false).stream().anyMatch(item ->
                "EXAM_SCORE_ENTRY".equals(item.getType())));

        ApiException tooEarly = assertThrows(ApiException.class, () -> exams.saveResults(
                fixture.periodId(),
                new SaveResultsRequest(fixture.scheduleId(),
                        List.of(new ResultEntry("u-student-1", 8.5, null, null))),
                "u-teacher-1"));
        assertEquals(409, tooEarly.getStatus().value());
    }

    @Test
    @Transactional
    void scoreEntryIsRestrictedToAssignedTeacherAndAssignedClass() {
        LocalDate examDate = LocalDate.now(SCHOOL_ZONE).minusDays(8);
        ExamFixture fixture = createPublishedMathExam(
                "past", examDate, "u-teacher-1", "u-teacher-2");

        SaveResultsRequest request = new SaveResultsRequest(fixture.scheduleId(),
                List.of(new ResultEntry("u-student-1", 8.75, "Đạt yêu cầu", null)));

        ApiException forbidden = assertThrows(ApiException.class, () ->
                exams.saveResults(fixture.periodId(), request, "u-teacher-2"));
        assertEquals(403, forbidden.getStatus().value());

        List<ExamResult> saved = exams.saveResults(
                fixture.periodId(), request, "u-teacher-1");
        assertEquals(1, saved.size());
        assertEquals(8.75, saved.get(0).getScore());
        assertEquals("u-teacher-1", saved.get(0).getRecordedBy());
    }

    private ExamFixture createPublishedMathExam(String suffix, LocalDate examDate,
                                                String graderId, String proctorId) {
        String periodId = "ep-grading-" + suffix;
        String scheduleId = "es-grading-" + suffix;
        String roomCode = "EXAM-" + suffix.toUpperCase();
        structure.createRoom(new CreateRoomRequest(
                "rm-grading-" + suffix, roomCode, "Phòng kiểm thử " + suffix,
                45, true, true, "GENERAL", null, "ACTIVE", true, null));
        exams.createPeriod(new SavePeriodRequest(
                periodId,
                "EXAM-GRADING-" + suffix.toUpperCase(),
                "Kỳ thi kiểm thử " + suffix,
                "ay-2026",
                "sm-2026-1",
                "K10",
                examDate,
                examDate), "u-admin-1");
        exams.createSchedule(periodId, new SaveScheduleRequest(
                scheduleId,
                "sj-math",
                List.of("c-10a1"),
                examDate,
                "08:00",
                60,
                null));

        if ("future".equals(suffix)) {
            ApiException wrongSubject = assertThrows(ApiException.class, () ->
                    exams.saveGradingAssignment(scheduleId,
                            new SaveGradingAssignmentRequest("c-10a1", "u-teacher-2"),
                            "u-admin-1"));
            assertEquals(400, wrongSubject.getStatus().value());
        }

        exams.saveGradingAssignment(scheduleId,
                new SaveGradingAssignmentRequest("c-10a1", graderId),
                "u-admin-1");
        ExamRoom examRoom = exams.saveRoom(scheduleId, new SaveRoomRequest(
                null, roomCode, 45, proctorId, null));
        exams.allocate(examRoom.getId(), new AllocateCandidatesRequest("c-10a1").classId());
        exams.publishSchedule(periodId, "u-admin-1");
        return new ExamFixture(periodId, scheduleId);
    }

    private record ExamFixture(String periodId, String scheduleId) {}
}
