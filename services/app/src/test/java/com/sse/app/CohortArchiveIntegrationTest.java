package com.sse.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:cohort-archive;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class CohortArchiveIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void prepareCohortArchiveFixture() {
        jdbc.update("""
                INSERT INTO cohorts(id,code,name,entry_year,graduation_year,duration_years,status,created_at,created_by)
                SELECT 'cohort-archive-test','2023-2026','Niên khóa 2023-2026',2023,2026,3,'COMPLETED',CURRENT_TIMESTAMP,'test'
                WHERE NOT EXISTS (SELECT 1 FROM cohorts WHERE id='cohort-archive-test')
                """);
        jdbc.update("""
                UPDATE users SET cohort_id='cohort-archive-test',student_status='GRADUATED',
                  graduation_academic_year_id='ay-2026',graduation_class_id='c-10a1',graduated_at=CURRENT_TIMESTAMP
                WHERE id='u-student-1'
                """);
    }

    @Test
    void academicStaffCanBrowsePagedCohortAndOpenThreeYearProfile() throws Exception {
        String token = login("giaovu", "Giaovu123@@");
        JsonNode cohorts = body(mvc.perform(get("/alumni/cohorts")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(cohorts.isArray()).isTrue();
        assertThat(cohorts.size()).isGreaterThan(0);

        String cohortId = cohorts.get(0).path("id").asText();
        mvc.perform(get("/alumni/cohorts/{cohortId}/overview", cohortId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cohort.id").value(cohortId));

        JsonNode page = body(mvc.perform(get("/alumni/cohorts/{cohortId}/students", cohortId)
                        .param("page", "0").param("size", "10")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(10))
                .andReturn().getResponse().getContentAsString());
        assertThat(page.path("items").size()).isGreaterThan(0);

        mvc.perform(get("/alumni/cohorts/{cohortId}/students", cohortId)
                        .param("finalClassId", "c-10a1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].finalClassId").value("c-10a1"));

        String studentId = page.path("items").get(0).path("id").asText();
        mvc.perform(get("/alumni/cohorts/{cohortId}/students/{studentId}", cohortId, studentId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cohortId").value(cohortId))
                .andExpect(jsonPath("$.academicYears.length()").value(3));
    }

    @Test
    void adminHasReadAccessButTeacherCannotBrowseWholeCohort() throws Exception {
        String admin = login("admin", "Admin123@@");
        mvc.perform(get("/alumni/cohorts").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());

        String teacher = login("gv.nguyenminh", "nguyenminh123@");
        mvc.perform(get("/alumni/cohorts").header("Authorization", "Bearer " + teacher))
                .andExpect(status().isForbidden());
    }

    private String login(String username, String password) throws Exception {
        String response = mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new Login(username, password, ""))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return body(response).path("accessToken").asText();
    }

    private JsonNode body(String raw) throws Exception {
        return json.readTree(raw);
    }

    private record Login(String username, String password, String twoFactorCode) {}
}
