package com.sse.app;

import com.sse.app.academic.structure.RoomAllocationDtos.PreviewRequest;
import com.sse.app.academic.structure.RoomAllocationPlanningService;
import com.sse.app.academic.structure.StructureDtos.CreateAcademicYearRequest;
import com.sse.app.academic.structure.StructureDtos.CreateClassRequest;
import com.sse.app.academic.structure.StructureDtos.CreateRoomRequest;
import com.sse.app.academic.structure.StructureService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:room-allocation;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=validate",
        "sse.seed.enabled=false"
})
@ActiveProfiles("demo")
class RoomAllocationPlanningIntegrationTest {
    @Autowired StructureService structure;
    @Autowired RoomAllocationPlanningService planning;

    @Test
    void balancesFortyFiveClassesAcrossThirtyRoomsAndCanUndoSafely() {
        var year = structure.createYear(new CreateAcademicYearRequest(
                "ay-room-plan", "2032-2033", "Năm học 2032-2033",
                LocalDate.of(2032, 8, 16), LocalDate.of(2033, 5, 31), "PLANNED"));
        for (int room = 1; room <= 30; room++) {
            structure.createRoom(new CreateRoomRequest(
                    "room-plan-" + room, "A" + String.format("%02d", room), "Phòng học " + room,
                    45, true, true, "GENERAL", "TV,MÁY CHIẾU", "ACTIVE", true, null));
        }
        structure.createRoom(new CreateRoomRequest(
                "room-plan-lab", "LAB-CHEM", "Phòng thí nghiệm Hóa",
                40, true, true, "LAB", "BÀN THÍ NGHIỆM", "ACTIVE", false, null));
        for (int index = 1; index <= 45; index++) {
            int grade = 10 + (index - 1) / 15;
            String code = grade + "A" + ((index - 1) % 15 + 1);
            structure.createClass(new CreateClassRequest(
                    "class-room-plan-" + index, code, "Lớp " + code, "K" + grade,
                    year.getId(), null, "MORNING", 45, null));
        }

        var preview = planning.preview(new PreviewRequest(
                year.getId(), "Phương án kiểm thử 45 lớp", true, true, List.of()), "u-test");

        assertThat(preview.totalClasses()).isEqualTo(45);
        assertThat(preview.assignedClasses()).isEqualTo(45);
        assertThat(preview.unassignedClasses()).isZero();
        assertThat(preview.morningClasses()).isEqualTo(23);
        assertThat(preview.afternoonClasses()).isEqualTo(22);
        assertThat(preview.capacity().mainRooms()).isEqualTo(30);
        assertThat(preview.capacity().functionalRooms()).isEqualTo(1);
        assertThat(preview.capacity().totalClassSlots()).isEqualTo(60);
        assertThat(preview.items()).allMatch(item -> item.proposedRoomCode() != null);
        assertThat(preview.items()).extracting(item -> item.proposedRoomId() + "|" + item.proposedShift())
                .doesNotHaveDuplicates();

        var applied = planning.apply(preview.id(), "u-test");
        assertThat(applied.status()).isEqualTo("APPLIED");
        var usedSlots = new HashSet<String>();
        for (var schoolClass : structure.listClasses(year.getId(), null)) {
            assertThat(schoolClass.getRoomId()).isNotBlank();
            assertThat(usedSlots.add(schoolClass.getRoomId() + "|" + schoolClass.getStudyShift())).isTrue();
        }

        var undone = planning.undo(preview.id(), "u-test");
        assertThat(undone.status()).isEqualTo("UNDONE");
        assertThat(structure.listClasses(year.getId(), null))
                .allMatch(item -> item.getRoomId() == null && "MORNING".equals(item.getStudyShift()));
    }
}
