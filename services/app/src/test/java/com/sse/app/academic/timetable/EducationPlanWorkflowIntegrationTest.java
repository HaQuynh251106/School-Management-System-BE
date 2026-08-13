package com.sse.app.academic.timetable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:education-plan-workflow;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "sse.exam-reminders.enabled=false",
        "sse.attendance-reminders.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class EducationPlanWorkflowIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    @DisplayName("GĐ3 giữ bất biến bản công bố và buộc phiên bản điều chỉnh đi đúng workflow")
    void publishedPlanIsImmutableAndRevisionKeepsItsSource() throws Exception {
        String admin = login("admin", "Admin123@@");
        String plansPayload = mvc.perform(get("/education-plans")
                        .param("academicYearId", "ay-2026")
                        .param("gradeLevel", "K10")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", greaterThan(0)))
                .andExpect(jsonPath("$[0].status").value("PUBLISHED"))
                .andReturn().getResponse().getContentAsString();
        String publishedId = json.readTree(plansPayload).get(0).path("id").asText();

        mvc.perform(put("/education-plans/{id}/requirements", publishedId)
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requirementPayload()))
                .andExpect(status().isConflict());

        mvc.perform(post("/education-plans")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of(
                                "academicYearId", "ay-2026", "gradeLevel", "K10",
                                "name", "Điều chỉnh GĐ3 không có lý do", "sourcePlanId", publishedId))))
                .andExpect(status().isBadRequest());

        String revisionPayload = mvc.perform(post("/education-plans")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of(
                                "academicYearId", "ay-2026", "gradeLevel", "K10",
                                "name", "Điều chỉnh GĐ3 học kỳ mới", "sourcePlanId", publishedId,
                                "revisionReason", "Điều chỉnh định mức theo quyết định chuyên môn"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.versionNo").value(2))
                .andExpect(jsonPath("$.sourcePlanId").value(publishedId))
                .andReturn().getResponse().getContentAsString();
        String revisionId = json.readTree(revisionPayload).path("id").asText();

        mvc.perform(get("/education-plans/{id}/requirements", revisionId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", greaterThan(0)));
        mvc.perform(post("/education-plans/{id}/publish", revisionId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isConflict());

        mvc.perform(get("/timetable/generation-readiness")
                        .param("semesterId", "sm-2026-1")
                        .param("scopeGradeLevel", "K10")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(true))
                .andExpect(jsonPath("$.activeClassCount").value(1))
                .andExpect(jsonPath("$.sourceEducationPlanIds.length()").value(1))
                .andExpect(jsonPath("$.sourceEducationPlanIds[0]").value(publishedId));
    }

    private String requirementPayload() throws Exception {
        return json.writeValueAsString(java.util.Map.of(
                "semesterId", "sm-2026-1", "gradeLevel", "K10", "subjectId", "sj-math",
                "weeklyPeriods", 3, "totalPeriods", 54));
    }

    private String login(String username, String password) throws Exception {
        String payload = mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of(
                                "username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode body = json.readTree(payload);
        return body.path("accessToken").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
