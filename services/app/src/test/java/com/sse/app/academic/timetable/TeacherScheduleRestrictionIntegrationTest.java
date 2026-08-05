package com.sse.app.academic.timetable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sse.app.academic.structure.StructureService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:teacher-schedule-restriction;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=validate", "sse.exam-reminders.enabled=false",
        "sse.attendance-reminders.enabled=false"
})
@ActiveProfiles("demo")
@Transactional
@AutoConfigureMockMvc
class TeacherScheduleRestrictionIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired StructureService structure;
    @Autowired TeacherScheduleRestrictionService restrictions;

    @Test
    void onlyApprovedRequestBlocksTimetableAndAcademicStaffOwnsDecision() throws Exception {
        String teacher = login("gv.nguyenminh", "nguyenminh123@");
        String academic = login("giaovu", "Giaovu123@@");
        String admin = login("admin", "Admin123@@");
        var semester = structure.getSemester("sm-2026-1");
        String payload = json.writeValueAsString(Map.of(
                "semesterId", semester.getId(),
                "restrictedSlots", java.util.List.of("MORNING:MON:1"),
                "effectiveFrom", semester.getStartDate().toString(),
                "effectiveTo", semester.getEndDate().toString(),
                "reason", "Điều trị y tế định kỳ theo lịch bệnh viện"
        ));
        String response = mvc.perform(post("/me/schedule-restriction-requests")
                        .header("Authorization", bearer(teacher)).contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode submitted = json.readTree(response);
        String id = submitted.path("id").asText();
        assertThat(submitted.path("status").asText()).isEqualTo("PENDING");
        assertThat(restrictions.approvedSlots("u-teacher-1", semester.getId())).isEmpty();

        String approval = "{\"action\":\"APPROVED\",\"decisionNote\":\"Đã kiểm tra minh chứng\"}";
        mvc.perform(put("/schedule-restriction-requests/{id}/review", id)
                        .header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON).content(approval))
                .andExpect(status().isForbidden());
        mvc.perform(put("/schedule-restriction-requests/{id}/review", id)
                        .header("Authorization", bearer(academic)).contentType(MediaType.APPLICATION_JSON).content(approval))
                .andExpect(status().isOk());
        assertThat(restrictions.approvedSlots("u-teacher-1", semester.getId()))
                .containsExactly("MORNING:MON:1");

        mvc.perform(post("/schedule-restriction-requests/{id}/revoke", id)
                        .header("Authorization", bearer(academic)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Giáo viên đã hoàn tất điều trị\"}"))
                .andExpect(status().isOk());
        assertThat(restrictions.approvedSlots("u-teacher-1", semester.getId())).isEmpty();
    }

    private String login(String username, String password) throws Exception {
        String response = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("accessToken").asText();
    }

    private static String bearer(String token) { return "Bearer " + token; }
}
