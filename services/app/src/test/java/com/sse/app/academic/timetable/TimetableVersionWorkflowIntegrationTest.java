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
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
    @DisplayName("Quản trị tạo, phát hành và khôi phục phiên bản TKB mà vẫn giữ lịch sử")
    void publishAndRestoreKeepsExactlyOneCurrentVersion() throws Exception {
        String admin = login("admin", "Admin123@@");

        String firstPayload = mvc.perform(post("/timetableSlots/auto-plan")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"semesterId":"sm-2026-1","apply":true,"allowPartial":false,
                                 "scopeGradeLevel":"K10","draftName":"Bản phát hành đầu tiên"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true))
                .andExpect(jsonPath("$.scopeGradeLevel").value("K10"))
                .andExpect(jsonPath("$.sourceEducationPlanIds.length()", greaterThan(0)))
                .andExpect(jsonPath("$.draftVersion.status").value("VALIDATED"))
                .andExpect(jsonPath("$.draftVersion.sourceEducationPlanIds.length()", greaterThan(0)))
                .andExpect(jsonPath("$.draftVersion.totalPeriods", greaterThan(0)))
                .andReturn().getResponse().getContentAsString();
        String firstId = json.readTree(firstPayload).path("draftVersion").path("id").asText();

        mvc.perform(post("/timetable-versions/{id}/publish", firstId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));

        String restoredPayload = mvc.perform(post("/timetable-versions/{id}/restore", firstId)
                        .header("Authorization", bearer(admin))
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
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));

        mvc.perform(get("/timetable-versions").param("semesterId", "sm-2026-1")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("PUBLISHED"))
                .andExpect(jsonPath("$[1].status").value("SUPERSEDED"));

        String teacher = login("gv.nguyenminh", "nguyenminh123@");
        mvc.perform(get("/me/timetable").header("Authorization", bearer(teacher)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", greaterThan(0)))
                .andExpect(jsonPath("$[*].teacherId", everyItem(is("u-teacher-1"))))
                .andExpect(jsonPath("$[*].publishedPlanId", everyItem(is(restoredId))));

        String timetableStudent = login("hs.nguyenminhan", "nguyenminhanh123@@");
        mvc.perform(get("/me/timetable").header("Authorization", bearer(timetableStudent)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", greaterThan(0)))
                .andExpect(jsonPath("$[*].classId", everyItem(is("c-10a1"))))
                .andExpect(jsonPath("$[*].publishedPlanId", everyItem(is(restoredId))));

        mvc.perform(get("/timetableSlots").param("classId", "c-10a1")
                        .header("Authorization", bearer(timetableStudent)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].publishedPlanId", everyItem(is(restoredId))));

        String parent = login("ph.nguyenvanhung", "nguyenvanhung123@");
        mvc.perform(get("/children/{studentId}/timetable", "u-student-1")
                        .header("Authorization", bearer(parent)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", greaterThan(0)))
                .andExpect(jsonPath("$[*].classId", everyItem(is("c-10a1"))))
                .andExpect(jsonPath("$[*].publishedPlanId", everyItem(is(restoredId))));
        mvc.perform(get("/children/{studentId}/timetable", "u-admin-1")
                        .header("Authorization", bearer(parent)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/children/{studentId}/timetable", "u-student-1")
                        .header("Authorization", bearer(timetableStudent)))
                .andExpect(status().isForbidden());

        String publishedSlotId = json.readTree(mvc.perform(get("/me/timetable")
                        .header("Authorization", bearer(timetableStudent)))
                .andReturn().getResponse().getContentAsString()).get(0).path("id").asText();
        mvc.perform(delete("/timetableSlots/{id}", publishedSlotId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isConflict());
        mvc.perform(put("/timetableSlots/{id}", publishedSlotId)
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"classId":"c-10a1","subjectId":"sj-math","teacherId":"u-teacher-1",
                                 "roomCode":"P201","dayOfWeek":"FRI","periodNo":1,
                                 "startTime":"07:00","endTime":"07:45","semesterId":"sm-2026-1"}
                                """))
                .andExpect(status().isConflict());

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
