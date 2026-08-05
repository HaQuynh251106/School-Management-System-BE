package com.sse.app.academic.exam;

import com.sse.app.academic.exam.ExamDtos.*;
import com.sse.app.academic.structure.StructureDtos.CreateRoomRequest;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.common.ApiException;
import com.sse.app.identity.IdentityDtos.CreateUserRequest;
import com.sse.app.identity.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.DayOfWeek;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:exam-seating-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "sse.exam-reminders.enabled=false"
})
@ActiveProfiles("demo")
class ExamSeatingPlanIntegrationTest {
    @Autowired ExamService exams;
    @Autowired StructureService structure;
    @Autowired UserService users;

    @Test
    @Transactional
    void unifiedOrganizationSelectsRoomsAssignsProctorsSeatsAndCanUndoAtomically() {
        users.create(new CreateUserRequest("u-exam-unified", "hs.exam.unified",
                "Học sinh kiểm thử tổ chức", "STUDENT", null, null, null,
                null, null, null, "c-10a1", "10A1", null, null, null, null,
                null, null, null, null, null));
        structure.createRoom(new CreateRoomRequest("rm-unified-a", "UNI-A", "Phòng tự động A", 30,
                true, true, "GENERAL", null, "ACTIVE", true, null));
        structure.createRoom(new CreateRoomRequest("rm-unified-b", "UNI-B", "Phòng tự động B", 30,
                true, true, "GENERAL", null, "ACTIVE", true, null));
        LocalDate date = LocalDate.now().plusDays(31);
        exams.createPeriod(new SavePeriodRequest("ep-unified", "EX-UNIFIED", "Kỳ thi tổ chức thống nhất",
                "ay-2026", "sm-2026-1", "K10", date, date), "u-admin-1");
        exams.createSchedule("ep-unified", new SaveScheduleRequest("es-unified", "sj-math",
                List.of("c-10a1"), date, "08:00", 60, null));

        OrganizationPlanView preview = exams.previewOrganizationPlan("es-unified",
                new PreviewOrganizationPlanRequest(1, 1, false), "u-admin-1");
        assertEquals("PREVIEW", preview.status());
        assertEquals(preview.candidateCount(), preview.roomCount());
        assertEquals(preview.candidateCount(), preview.assignedCount());
        assertEquals(0, preview.missingAssignmentCount());
        assertTrue(preview.rooms().stream().allMatch(room -> room.effectiveCapacity() == 1
                && room.deskCount() == 1 && room.proctorOneId() != null));
        assertTrue(preview.candidates().stream().allMatch(candidate -> candidate.candidateNo().matches("\\d{6}")
                && candidate.deskNo() == 1 && candidate.seatPosition() == 1));
        assertTrue(exams.rooms("es-unified").isEmpty(), "Bản xem trước không được thay đổi phòng hiện tại");

        OrganizationPlanView applied = exams.applyOrganizationPlan(preview.id(), "u-admin-1");
        assertEquals("APPLIED", applied.status());
        assertEquals(preview.roomCount(), exams.rooms("es-unified").size());
        assertEquals(preview.candidateCount(), exams.candidates("ep-unified", "es-unified", null).size());
        assertTrue(exams.candidates("ep-unified", "es-unified", null).stream()
                .allMatch(candidate -> candidate.getDeskNo() == 1 && candidate.getSeatPosition() == 1));

        OrganizationPlanView undone = exams.undoOrganizationPlan(preview.id(), "u-admin-1");
        assertEquals("UNDONE", undone.status());
        assertTrue(exams.rooms("es-unified").isEmpty());
        assertTrue(exams.candidates("ep-unified", "es-unified", null).isEmpty());
    }

    @Test
    @Transactional
    void previewApplyAndUndoSplitAClassWithoutChangingCandidateNumbers() {
        users.create(new CreateUserRequest("u-exam-split", "hs.exam.split",
                "Học sinh kiểm thử xếp phòng", "STUDENT", null, null, null,
                null, null, null, "c-10a1", "10A1", null, null, null, null,
                null, null, null, null, null));
        structure.createRoom(new CreateRoomRequest("rm-exam-a", "EX-A", "Phòng thi A", 1,
                true, true, "GENERAL", null, "ACTIVE", true, null));
        structure.createRoom(new CreateRoomRequest("rm-exam-b", "EX-B", "Phòng thi B", 1,
                true, true, "GENERAL", null, "ACTIVE", true, null));

        LocalDate date = LocalDate.now().plusDays(20);
        exams.createPeriod(new SavePeriodRequest("ep-seating", "EX-SEATING", "Kỳ thi xếp phòng",
                "ay-2026", "sm-2026-1", "K10", date, date), "u-admin-1");
        exams.createSchedule("ep-seating", new SaveScheduleRequest("es-seating", "sj-math",
                List.of("c-10a1"), date, "08:00", 60, null));
        List<ExamRoom> createdRooms = exams.saveRooms("es-seating", new BatchSaveRoomsRequest(List.of("EX-A", "EX-B")));
        assertEquals(2, createdRooms.size());
        exams.saveRoom("es-seating", new SaveRoomRequest(createdRooms.get(0).getId(), "EX-A", 1, "u-teacher-1", null));
        exams.saveRoom("es-seating", new SaveRoomRequest(createdRooms.get(1).getId(), "EX-B", 1, "u-teacher-2", null));

        SeatingPlanView insufficient = exams.previewSeatingPlan("es-seating",
                new PreviewSeatingPlanRequest(List.of(createdRooms.get(0).getId())), "u-admin-1");
        assertEquals(1, insufficient.unassignedCount());
        assertThrows(ApiException.class, () -> exams.applySeatingPlan(insufficient.id(), "u-admin-1"));

        SeatingPlanView preview = exams.previewSeatingPlan("es-seating",
                new PreviewSeatingPlanRequest(createdRooms.stream().map(ExamRoom::getId).toList()), "u-admin-1");
        assertEquals(2, preview.candidateCount());
        assertEquals(2, preview.assignedCount());
        assertEquals(0, preview.unassignedCount());
        assertEquals(2, preview.classes().get(0).roomCount());
        assertTrue(preview.candidates().stream().allMatch(item -> item.candidateNo().matches("\\d{6}")));

        SeatingPlanView applied = exams.applySeatingPlan(preview.id(), "u-admin-1");
        assertEquals("APPLIED", applied.status());
        OrganizationReadiness readiness = exams.organizationReadiness("es-seating");
        assertTrue(readiness.roomsReady());
        assertTrue(readiness.candidatesReady());
        List<String> stableNumbers = exams.candidates("ep-seating", "es-seating", null).stream()
                .map(ExamCandidate::getCandidateNo).sorted().toList();
        exams.saveGradingAssignment("es-seating", new SaveGradingAssignmentRequest("c-10a1", "u-teacher-1"), "u-admin-1");
        exams.publishSchedule("ep-seating", "u-admin-1");
        assertTrue(exams.isPublishedExamDay(date));

        SeatingPlanView nextPreview = exams.previewSeatingPlan("es-seating",
                new PreviewSeatingPlanRequest(createdRooms.stream().map(ExamRoom::getId).toList()), "u-admin-1");
        assertEquals(stableNumbers, nextPreview.candidates().stream().map(SeatingPlanCandidate::candidateNo).sorted().toList());
        exams.applySeatingPlan(nextPreview.id(), "u-admin-1");
        SeatingPlanView undone = exams.undoSeatingPlan(nextPreview.id(), "u-admin-1");
        assertEquals("UNDONE", undone.status());
        assertEquals(stableNumbers, exams.candidates("ep-seating", "es-seating", null).stream()
                .map(ExamCandidate::getCandidateNo).sorted().toList());
    }

    @Test
    @Transactional
    void automaticProctorPlanHonorsLockedRoomsBalancesTeachersAndCanUndo() {
        structure.createRoom(new CreateRoomRequest("rm-proctor-a", "PROCTOR-A", "Phòng coi thi A", 30,
                true, true, "GENERAL", null, "ACTIVE", true, null));
        structure.createRoom(new CreateRoomRequest("rm-proctor-b", "PROCTOR-B", "Phòng coi thi B", 30,
                true, true, "GENERAL", null, "ACTIVE", true, null));
        structure.createRoom(new CreateRoomRequest("rm-proctor-c", "PROCTOR-C", "Phòng coi thi C", 30,
                true, true, "GENERAL", null, "ACTIVE", true, null));
        LocalDate sunday = LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        exams.createPeriod(new SavePeriodRequest("ep-proctor", "EX-PROCTOR", "Kỳ thi phân giám thị",
                "ay-2026", "sm-2026-1", "K10", sunday, sunday), "u-admin-1");
        exams.createSchedule("ep-proctor", new SaveScheduleRequest("es-proctor", "sj-math",
                List.of("c-10a1"), sunday, "08:00", 60, null));
        exams.createSchedule("ep-proctor", new SaveScheduleRequest("es-proctor-other", "sj-eng",
                List.of("c-10a2"), sunday, "08:00", 60, null));
        exams.saveRooms("es-proctor-other", new BatchSaveRoomsRequest(List.of("PROCTOR-C")));
        List<ExamRoom> examRooms = exams.saveRooms("es-proctor",
                new BatchSaveRoomsRequest(List.of("PROCTOR-A", "PROCTOR-B")));
        ExamRoom lockedRoom = examRooms.stream().filter(room -> "PROCTOR-A".equals(room.getRoomCode())).findFirst().orElseThrow();
        ExamRoom openRoom = examRooms.stream().filter(room -> "PROCTOR-B".equals(room.getRoomCode())).findFirst().orElseThrow();
        exams.saveRoom("es-proctor", new SaveRoomRequest(lockedRoom.getId(), "PROCTOR-A", 30, "u-teacher-1", null));
        ApiException duplicateProctor = assertThrows(ApiException.class, () -> exams.saveRoom("es-proctor",
                new SaveRoomRequest(openRoom.getId(), "PROCTOR-B", 30, "u-teacher-1", null)));
        assertEquals(409, duplicateProctor.getStatus().value());
        assertTrue(exams.examDayPolicy("es-proctor").regularClassesSuspended());
        ExamRoomAvailability unavailable = exams.roomAvailability("es-proctor").stream()
                .filter(room -> "PROCTOR-C".equals(room.roomCode())).findFirst().orElseThrow();
        assertFalse(unavailable.available());
        assertEquals("Tiếng Anh", unavailable.conflictingSubject());

        ProctorPlanView preview = exams.previewProctorPlan("es-proctor",
                new PreviewProctorPlanRequest(List.of(lockedRoom.getId()), false), "u-admin-1");
        assertEquals(2, preview.readyRoomCount());
        assertEquals(0, preview.missingAssignmentCount());
        ProctorPlanItem locked = preview.items().stream().filter(ProctorPlanItem::locked).findFirst().orElseThrow();
        ProctorPlanItem proposed = preview.items().stream().filter(item -> !item.locked()).findFirst().orElseThrow();
        assertEquals("u-teacher-1", locked.proposedProctorOneId());
        assertEquals("u-teacher-2", proposed.proposedProctorOneId());

        assertEquals("APPLIED", exams.applyProctorPlan(preview.id(), "u-admin-1").status());
        assertEquals("u-teacher-2", exams.rooms("es-proctor").stream()
                .filter(room -> room.getId().equals(openRoom.getId())).findFirst().orElseThrow().getProctorOneId());
        assertEquals("UNDONE", exams.undoProctorPlan(preview.id(), "u-admin-1").status());
        assertNull(exams.rooms("es-proctor").stream()
                .filter(room -> room.getId().equals(openRoom.getId())).findFirst().orElseThrow().getProctorOneId());
    }
}
