package com.sse.app;

import com.sse.app.academic.structure.*;
import com.sse.app.identity.User;
import com.sse.app.identity.UserRepository;
import com.sse.app.security.CurrentUser;
import com.sse.app.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;

import static com.sse.app.academic.structure.StructureDtos.*;
import static com.sse.app.academic.structure.StudentClassTransferDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:class-transfer;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=validate",
        "sse.seed.enabled=false"
})
@ActiveProfiles("demo")
@AutoConfigureMockMvc
class StudentClassTransferIntegrationTest {
    @Autowired StructureService structure;
    @Autowired StudentClassTransferService transfers;
    @Autowired UserRepository users;
    @Autowired JwtService jwt;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void blocksDuringSemesterAndAllowsThenUndoesAtSemesterBoundary() throws Exception {
        LocalDate today = LocalDate.now();
        var year = structure.createYear(new CreateAcademicYearRequest(
                "ay-transfer", "2035-2036", "Năm học kiểm thử chuyển lớp",
                today.minusDays(220), today.plusDays(180), "PLANNED"));
        structure.changeYearStatus(year.getId(), "ACTIVE");
        var semesters = structure.listSemesters(year.getId());
        var firstSemester = semesters.get(0);
        structure.changeSemesterStatus(firstSemester.getId(), "ACTIVE");

        var source = structure.createClass(new CreateClassRequest(
                "class-transfer-a", "10A1", "Lớp 10A1", "K10", year.getId(), null,
                "MORNING", 45, null));
        var target = structure.createClass(new CreateClassRequest(
                "class-transfer-b", "10A2", "Lớp 10A2", "K10", year.getId(), null,
                "MORNING", 45, null));
        users.save(User.builder().id("student-transfer").username("hs.transfer")
                .passwordHash("x").fullName("Học sinh chuyển lớp").role("STUDENT")
                .status("ACTIVE").studentCode("HS-TRANSFER").classId(source.getId())
                .className(source.getCode()).studentStatus("ENROLLED").build());
        structure.recordEnrollment("student-transfer", source.getId());
        users.save(User.builder().id("academic-transfer").username("giaovu.transfer")
                .passwordHash("x").fullName("Giáo vụ kiểm thử").role("ACADEMIC_STAFF")
                .status("ACTIVE").build());
        users.save(User.builder().id("admin-transfer").username("admin.transfer")
                .passwordHash("x").fullName("Admin kiểm thử").role("ADMIN")
                .status("ACTIVE").build());
        CurrentUser actor = new CurrentUser("academic-transfer", "giaovu.transfer", "ACADEMIC_STAFF");
        TransferRequest request = new TransferRequest("student-transfer", target.getId(), today,
                "Điều chỉnh lớp theo nguyện vọng đã được nhà trường phê duyệt");

        assertThat(transfers.window(year.getId()).eligible()).isFalse();
        mvc.perform(get("/student-class-transfers/window").param("academicYearId", year.getId())
                        .header("Authorization", "Bearer " + jwt.createAccessToken(
                                "admin-transfer", "admin.transfer", "ADMIN", 0)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/student-class-transfers/window").param("academicYearId", year.getId())
                        .header("Authorization", "Bearer " + jwt.createAccessToken(
                                "academic-transfer", "giaovu.transfer", "ACADEMIC_STAFF", 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(false));
        assertThatThrownBy(() -> transfers.transfer(request, actor))
                .hasMessageContaining("đang diễn ra");

        structure.changeSemesterStatus(firstSemester.getId(), "CLOSED");
        assertThat(transfers.window(year.getId()).eligible()).isTrue();
        StudentClassTransfer applied = transfers.transfer(request, actor);
        assertThat(applied.getStatus()).isEqualTo("APPLIED");
        assertThat(users.findById("student-transfer").orElseThrow().getClassId()).isEqualTo(target.getId());
        assertThat(jdbc.queryForObject("select count(*) from notifications where recipient_id=? and ref_type='CLASS_TRANSFER' and ref_id=?",
                Long.class, "student-transfer", applied.getId())).isGreaterThanOrEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action='STUDENT_CLASS_TRANSFERRED' and entity_id=?",
                Long.class, applied.getId())).isEqualTo(1L);
        assertThat(structure.enrollmentHistory("student-transfer"))
                .anyMatch(item -> item.getClassId().equals(source.getId()) && "TRANSFERRED".equals(item.getStatus()))
                .anyMatch(item -> item.getClassId().equals(target.getId()) && "ACTIVE".equals(item.getStatus()));

        StudentClassTransfer undone = transfers.undo(applied.getId(),
                new UndoRequest("Hoàn tác do quyết định điều chuyển bị thu hồi"), actor);
        assertThat(undone.getStatus()).isEqualTo("ROLLED_BACK");
        assertThat(users.findById("student-transfer").orElseThrow().getClassId()).isEqualTo(source.getId());
        assertThat(structure.enrollmentHistory("student-transfer"))
                .anyMatch(item -> item.getClassId().equals(source.getId()) && "ACTIVE".equals(item.getStatus()))
                .anyMatch(item -> item.getClassId().equals(target.getId()) && "ROLLED_BACK".equals(item.getStatus()));
    }
}
