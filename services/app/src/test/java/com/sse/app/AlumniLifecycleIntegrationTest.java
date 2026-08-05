package com.sse.app;

import com.sse.app.academic.structure.StructureDtos.CreateAcademicYearRequest;
import com.sse.app.academic.structure.StructureDtos.CreateClassRequest;
import com.sse.app.academic.structure.IntakeClassPlacementService;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.identity.AlumniService;
import com.sse.app.identity.IdentityDtos.CreateUserRequest;
import com.sse.app.identity.UserService;
import com.sse.app.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:alumni-lifecycle;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=validate",
        "sse.seed.enabled=false"
})
@ActiveProfiles("demo")
class AlumniLifecycleIntegrationTest {
    @Autowired StructureService structure;
    @Autowired UserService users;
    @Autowired AlumniService alumni;
    @Autowired IntakeClassPlacementService intakePlacement;
    @Autowired JdbcTemplate jdbc;

    @Test
    void graduationClosesActiveEnrollmentAndPreservesHistoricalProfile() {
        var year = structure.createYear(new CreateAcademicYearRequest(
                "ay-graduation-test", "2030-2031", "Năm học 2030-2031",
                LocalDate.of(2030, 8, 15), LocalDate.of(2031, 5, 31), "PLANNED"));
        var schoolClass = structure.createClass(new CreateClassRequest(
                "class-12a1-test", "12A1-TEST", "Lớp 12A1 kiểm thử", "K12",
                year.getId(), null, "MORNING", 45, null));
        var student = users.create(new CreateUserRequest(
                "student-graduation-test", "hs.graduation.test",
                "Học sinh Tốt Nghiệp", "STUDENT", "graduate@test.edu.vn", "0901234567",
                null, null, null, "HS-GRAD-001", schoolClass.getId(), schoolClass.getCode(),
                LocalDate.of(2013, 3, 20), "FEMALE", "Hà Nội", "Kinh", "Việt Nam",
                "Hà Nội", LocalDate.of(2028, 8, 15), "Phụ huynh kiểm thử", "0907654321"));

        assertThat(jdbc.queryForObject("SELECT status FROM class_enrollments WHERE student_id=?", String.class, student.id()))
                .isEqualTo("ACTIVE");
        String cohortId = student.cohortId();
        Instant graduatedAt = Instant.parse("2031-06-01T00:00:00Z");

        var graduated = users.graduateStudent(student.id(), year.getId(), schoolClass.getId(), graduatedAt);

        assertThat(graduated.studentStatus()).isEqualTo("GRADUATED");
        assertThat(graduated.classId()).isNull();
        assertThat(graduated.cohortId()).isEqualTo(cohortId);
        assertThat(graduated.status()).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT status FROM class_enrollments WHERE student_id=?", String.class, student.id()))
                .isEqualTo("GRADUATED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM class_enrollments WHERE student_id=?", Long.class, student.id()))
                .isEqualTo(1L);

        var page = alumni.page("Tốt Nghiệp", cohortId, year.getId(), 0, 20);
        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.items()).singleElement().satisfies(record -> {
            assertThat(record.id()).isEqualTo(student.id());
            assertThat(record.graduationClassCode()).isEqualTo("12A1-TEST");
            assertThat(record.graduationAcademicYearCode()).isEqualTo("2030-2031");
            assertThat(record.cohortId()).isEqualTo(cohortId);
        });

        var alumniClasses = alumni.classes(cohortId, year.getId());
        assertThat(alumniClasses).singleElement().satisfies(item -> {
            assertThat(item.classId()).isEqualTo(schoolClass.getId());
            assertThat(item.classCode()).isEqualTo("12A1-TEST");
            assertThat(item.studentCount()).isEqualTo(1);
        });
        assertThat(alumni.currentClasses()).extracting("classId").contains(schoolClass.getId());
        assertThat(alumni.archivedClasses(null, null)).isEmpty();
        assertThat(alumni.page(null, cohortId, year.getId(), schoolClass.getId(), 0, 20).totalElements())
                .isEqualTo(1);

        assertThat(users.list("STUDENT", null, null)).noneMatch(item -> item.id().equals(student.id()));
        assertThat(intakePlacement.candidates(year.getId(), "K10"))
                .noneMatch(item -> item.id().equals(student.id()));
        assertThat(users.getById(student.id()).getId()).isEqualTo(student.id());
    }

    @Test
    void closedAcademicYearCannotBeUsedForNewIntakePlacement() {
        var year = structure.createYear(new CreateAcademicYearRequest(
                "ay-closed-intake-test", "2032-2033", "Năm học 2032-2033",
                LocalDate.of(2032, 8, 15), LocalDate.of(2033, 5, 31), "PLANNED"));
        jdbc.update("update academic_years set status='CLOSED' where id=?", year.getId());

        assertThatThrownBy(() -> intakePlacement.candidates(year.getId(), "K10"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("đã đóng")
                .hasMessageContaining("chỉ được phép tra cứu lịch sử");
    }
}
