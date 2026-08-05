package com.sse.app.academic.timetable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sse.app.academic.timetable.WorkloadPlanningDtos.AssignmentVersionResponse;
import com.sse.app.academic.timetable.WorkloadPlanningDtos.SchedulingReadinessResponse;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:academic-scheduling-workflow;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=validate", "sse.exam-reminders.enabled=false",
        "sse.attendance-reminders.enabled=false"
})
@ActiveProfiles("demo")
@Transactional
@AutoConfigureMockMvc
class AcademicSchedulingWorkflowIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired WorkloadPlanningService planning;
    @Autowired TeachingAssignmentVersionService versions;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    @Test
    void readinessAndAssignmentVersionsBelongOnlyToAcademicStaff() throws Exception {
        String academic = login("giaovu", "Giaovu123@@");
        String admin = login("admin", "Admin123@@");

        String response = mvc.perform(get("/academic-scheduling/readiness")
                        .param("semesterId", "sm-2026-1").header("Authorization", bearer(academic)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        SchedulingReadinessResponse readiness = json.readValue(response, SchedulingReadinessResponse.class);
        assertThat(readiness.teacherCount()).isPositive();
        assertThat(readiness.classCount()).isPositive();
        assertThat(readiness.expectedTimetableSlotCount()).isEqualTo(readiness.classCount() * 25);

        mvc.perform(get("/academic-scheduling/readiness").param("semesterId", "sm-2026-1")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isForbidden());

        AssignmentVersionResponse published = versions.publishCurrent("sm-2026-1",
                "Phiên bản kiểm thử", java.util.List.of(), "u-academic-staff-1");
        assertThat(published.status()).isEqualTo("PUBLISHED");
        entityManager.flush();
        Integer notificationCount = jdbc.queryForObject(
                "select count(*) from notifications where type='TEACHING_ASSIGNMENT' and ref_id=?",
                Integer.class, published.id());
        assertThat(notificationCount).isPositive();
        AssignmentVersionResponse draft = versions.restore(published.id(), "Bản khôi phục kiểm thử",
                "u-academic-staff-1");
        assertThat(draft.status()).isEqualTo("DRAFT");
        assertThat(versions.items(draft.id())).hasSize(published.assignmentCount());

        mvc.perform(get("/teaching-assignment-versions").param("semesterId", "sm-2026-1")
                        .header("Authorization", bearer(academic)))
                .andExpect(status().isOk());
        mvc.perform(get("/teaching-assignment-versions").param("semesterId", "sm-2026-1")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isForbidden());
    }

    @Test
    void teacherLoadIncludesAssignmentsAndRestrictionCounters() {
        var loads = planning.listRegistrations("sm-2026-1");
        assertThat(loads).isNotEmpty();
        assertThat(loads).allSatisfy(load -> {
            assertThat(load.assignedClasses()).isNotNull();
            assertThat(load.assignedSubjects()).isNotNull();
            assertThat(load.approvedRestrictionCount()).isNotNegative();
            assertThat(load.pendingRestrictionCount()).isNotNegative();
        });
    }

    private String login(String username, String password) throws Exception {
        String response = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode node = json.readTree(response);
        return node.path("accessToken").asText();
    }

    private static String bearer(String token) { return "Bearer " + token; }
}
