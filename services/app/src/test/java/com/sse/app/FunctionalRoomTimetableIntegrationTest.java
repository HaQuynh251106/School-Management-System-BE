package com.sse.app;

import com.sse.app.academic.structure.StructureDtos.*;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.academic.structure.SubjectRoomRequirementDtos.SaveRequest;
import com.sse.app.academic.structure.SubjectRoomRequirementService;
import com.sse.app.academic.timetable.AutomaticTimetableService;
import com.sse.app.academic.timetable.TimetableDtos.AutoTimetableRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:functional-room-timetable;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=validate", "sse.seed.enabled=false"
})
@ActiveProfiles("demo")
class FunctionalRoomTimetableIntegrationTest {
    @Autowired StructureService structure;
    @Autowired SubjectRoomRequirementService requirements;
    @Autowired AutomaticTimetableService automaticTimetable;
    @Autowired JdbcTemplate jdbc;

    @Test
    void schedulesRequiredLaboratoryPeriodAndKeepsOtherPeriodsInHomeRoom() {
        var year = structure.createYear(new CreateAcademicYearRequest("ay-functional", "2034-2035",
                "Năm học 2034-2035", LocalDate.of(2034, 8, 15), LocalDate.of(2035, 5, 31), "PLANNED"));
        var semester = structure.listSemesters(year.getId()).get(0);
        var home = structure.createRoom(new CreateRoomRequest("room-home", "A101", "Phòng 10A1", 45,
                true, true, "GENERAL", "máy chiếu", "ACTIVE", true, null));
        structure.createRoom(new CreateRoomRequest("room-lab", "LAB-HOA", "Phòng thí nghiệm Hóa", 45,
                true, true, "LAB", "bộ thí nghiệm,máy chiếu", "ACTIVE", false, null));
        var schoolClass = structure.createClass(new CreateClassRequest("class-functional", "10A1", "Lớp 10A1",
                "K10", year.getId(), null, "MORNING", 45, home.getId()));
        var subject = structure.createSubject(new CreateSubjectRequest("subject-chem", "CHEM", "Hóa học", 1D));
        requirements.save(new SaveRequest(subject.getId(), "LAB", "bộ thí nghiệm", 1, true, 80));

        jdbc.update("insert into teacher_load_registrations (id,teacher_id,teacher_name,semester_id,max_weekly_periods,status,created_at,updated_at) values (?,?,?,?,?,'APPROVED',current_timestamp,current_timestamp)",
                "load-chem", "teacher-chem", "Nguyễn Ngọc Lan", semester.getId(), 18);
        jdbc.update("insert into teaching_assignments (id,class_id,class_code,subject_id,subject_name,teacher_id,teacher_name,semester_id,weekly_periods,assigned_at,assigned_by,updated_at) values (?,?,?,?,?,?,?,?,?,current_timestamp,'u-test',current_timestamp)",
                "ta-chem", schoolClass.getId(), schoolClass.getCode(), subject.getId(), subject.getName(),
                "teacher-chem", "Nguyễn Ngọc Lan", semester.getId(), 3);

        var plan = automaticTimetable.plan(new AutoTimetableRequest(semester.getId(), false, false));
        assertThat(plan.unscheduledSlots()).isZero();
        assertThat(plan.items()).hasSize(3);
        assertThat(plan.items()).filteredOn(item -> "LAB-HOA".equals(item.roomCode())).hasSize(1);
        assertThat(plan.items()).filteredOn(item -> "A101".equals(item.roomCode())).hasSize(2);
        assertThat(plan.items()).extracting(item -> item.dayOfWeek() + ":" + item.periodNo()).doesNotHaveDuplicates();
    }
}
