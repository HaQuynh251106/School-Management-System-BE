package com.sse.app.academic.timetable;

import com.sse.app.common.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:teacher-workload-policy;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=validate",
        "sse.exam-reminders.enabled=false",
        "sse.attendance-reminders.enabled=false"
})
@ActiveProfiles("demo")
@Transactional
@AutoConfigureMockMvc
class TeacherWorkloadPolicyIntegrationTest {
    @Autowired TeacherWorkloadPolicyService policies;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void statutoryTargetIsCalculatedBySystemAndOvertimeRequiresApproval() {
        var initial = policies.snapshot("u-teacher-1", "sm-2026-1");
        assertThat(initial.baseWeeklyPeriods()).isEqualTo(17);
        assertThat(initial.homeroomTeacher()).isTrue();
        assertThat(initial.reductionWeeklyPeriods()).isEqualTo(4);
        assertThat(initial.targetDirectWeeklyPeriods()).isEqualTo(13);
        assertThat(initial.legalWeeklyCap()).isEqualTo(21);
        assertThat(initial.effectiveMaximumWeeklyPeriods()).isEqualTo(13);

        var overtime = policies.saveAdjustment("u-teacher-1", "ay-2026", "OVERTIME",
                "APPROVED_OVERTIME", "Dạy thay giáo viên nghỉ dài hạn", 8,
                null, null, "Quyết định phân công dạy thay", "u-academic-staff-1");
        var approved = policies.snapshot("u-teacher-1", "sm-2026-1");
        assertThat(approved.approvedOvertimeWeeklyPeriods()).isEqualTo(8);
        assertThat(approved.effectiveMaximumWeeklyPeriods()).isEqualTo(21);

        policies.revokeAdjustment(overtime.getId(), "Đã bố trí được giáo viên thay thế", "u-academic-staff-1");
        assertThat(policies.snapshot("u-teacher-1", "sm-2026-1").effectiveMaximumWeeklyPeriods())
                .isEqualTo(13);
    }

    @Test
    void rejectsUnexplainedOrExcessiveOvertime() {
        assertThatThrownBy(() -> policies.saveAdjustment("u-teacher-1", "ay-2026", "OVERTIME",
                "APPROVED_OVERTIME", "Dạy vượt", 9, null, null,
                "Quyết định hợp lệ", "u-academic-staff-1"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("50%");
        assertThatThrownBy(() -> policies.saveAdjustment("u-teacher-1", "ay-2026", "REDUCTION",
                "OTHER_DUTY", "Nhiệm vụ khác", 1, null, null,
                "", "u-academic-staff-1"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("lý do");
    }

    @Test
    void onlyAcademicStaffCanApproveWorkloadAdjustments() throws Exception {
        String teacher = login("gv.nguyenminh", "nguyenminh123@");
        String academicStaff = login("giaovu", "Giaovu123@@");
        String admin = login("admin", "Admin123@@");

        mvc.perform(get("/teacher-workload-policy").queryParam("academicYearId", "ay-2026")
                        .header("Authorization", bearer(teacher)))
                .andExpect(status().isOk());
        String body = """
                {"teacherId":"u-teacher-1","academicYearId":"ay-2026","category":"REDUCTION",
                 "dutyType":"OTHER_DUTY","title":"Nhiệm vụ chuyên môn khác","weeklyPeriods":1,
                 "reason":"Quyết định phân công nhiệm vụ"}
                """;
        mvc.perform(post("/teacher-workload-adjustments").header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/teacher-workload-adjustments").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/teacher-workload-adjustments").header("Authorization", bearer(academicStaff))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private String login(String username, String password) throws Exception {
        String response = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of(
                                "username", username, "password", password))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("accessToken").asText();
    }

    private static String bearer(String token) { return "Bearer " + token; }
}
