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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:timetable-version-workflow;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "sse.exam-reminders.enabled=false",
        "sse.attendance-reminders.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class TimetableVersionWorkflowIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    @DisplayName("Giáo vụ tạo, phát hành và khôi phục phiên bản TKB mà vẫn giữ lịch sử")
    void publishAndRestoreKeepsExactlyOneCurrentVersion() throws Exception {
        String academicStaff = login("giaovu", "Giaovu123@@");

        String firstPayload = mvc.perform(post("/timetable-versions")
                        .header("Authorization", bearer(academicStaff))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"semesterId":"sm-2026-1","name":"Bản phát hành đầu tiên"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALIDATED"))
                .andExpect(jsonPath("$.totalPeriods", greaterThan(0)))
                .andReturn().getResponse().getContentAsString();
        String firstId = json.readTree(firstPayload).path("id").asText();

        mvc.perform(post("/timetable-versions/{id}/publish", firstId)
                        .header("Authorization", bearer(academicStaff)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));

        String restoredPayload = mvc.perform(post("/timetable-versions/{id}/restore", firstId)
                        .header("Authorization", bearer(academicStaff))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Bản khôi phục đã kiểm tra"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALIDATED"))
                .andExpect(jsonPath("$.sourcePlanId").value(firstId))
                .andReturn().getResponse().getContentAsString();
        String restoredId = json.readTree(restoredPayload).path("id").asText();

        mvc.perform(post("/timetable-versions/{id}/publish", restoredId)
                        .header("Authorization", bearer(academicStaff)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));

        mvc.perform(get("/timetable-versions").param("semesterId", "sm-2026-1")
                        .header("Authorization", bearer(academicStaff)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("PUBLISHED"))
                .andExpect(jsonPath("$[1].status").value("SUPERSEDED"));

        String student = login("hs.binh", "student@123");
        mvc.perform(get("/timetable-versions").param("semesterId", "sm-2026-1")
                        .header("Authorization", bearer(student)))
                .andExpect(status().isForbidden());
    }

    private String login(String username, String password) throws Exception {
        String payload = mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(payload).path("accessToken").asText();
    }

    private String bearer(String token) { return "Bearer " + token; }
}
