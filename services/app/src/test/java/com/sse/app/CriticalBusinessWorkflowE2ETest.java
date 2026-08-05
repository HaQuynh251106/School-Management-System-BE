package com.sse.app;

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
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Curated HTTP-level journeys for the workflows that must never regress.
 * These tests cross authentication, authorization, controller, service and persistence boundaries.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:critical-workflow-e2e;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "sse.storage.path=target/e2e-uploads",
        "sse.exam-reminders.enabled=false",
        "sse.attendance-reminders.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class CriticalBusinessWorkflowE2ETest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("API trả lỗi nghiệp vụ rõ ràng khi JSON không hợp lệ")
    void malformedJsonReturnsBadRequestInsteadOfInternalServerError() throws Exception {
        mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{username:admin}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    @DisplayName("Phân quyền tách biệt Giáo vụ và Kế toán xuyên suốt API")
    void roleBasedOfficesCannotCrossAdministrativeBoundaries() throws Exception {
        String academicStaff = login("giaovu", "Giaovu123@@");
        String accountant = login("ketoan", "Ketoan123@@");

        mvc.perform(get("/exam-periods").header("Authorization", bearer(academicStaff)))
                .andExpect(status().isOk());
        mvc.perform(post("/fee-periods")
                        .header("Authorization", bearer(academicStaff))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"E2E-DENIED","name":"Không được tạo","academicYearId":"ay-2026","dueDate":"2026-10-01"}
                                """))
                .andExpect(status().isForbidden());

        mvc.perform(get("/finance/overview").header("Authorization", bearer(accountant)))
                .andExpect(status().isOk());
        mvc.perform(post("/academicYears")
                        .header("Authorization", bearer(accountant))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"ay-e2e-denied","code":"2098-2099","name":"Không được tạo",
                                 "startDate":"2098-08-15","endDate":"2099-05-31"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Học bạ chỉ hiển thị cho đúng vai trò và chỉ công khai sau khi phát hành")
    void reportCardWorkflowProtectsUnpublishedStudentRecords() throws Exception {
        String academicStaff = login("giaovu", "Giaovu123@@");
        String teacher = login("gv.nguyenminh", "nguyenminh123@");
        String student = login("hs.nguyenminhan", "nguyenminhanh123@@");
        String parent = login("ph.nguyenvanhung", "nguyenvanhung123@");
        String accountant = login("ketoan", "Ketoan123@@");

        JsonNode cards = response(get("/report-cards")
                        .queryParam("academicYearId", "ay-2026")
                        .header("Authorization", bearer(academicStaff)), 200);
        String cardId = cards.get(0).path("id").asText();
        org.assertj.core.api.Assertions.assertThat(cards.get(0).path("status").asText()).isEqualTo("DRAFT");

        mvc.perform(get("/report-cards/overview")
                        .queryParam("academicYearId", "ay-2026")
                        .header("Authorization", bearer(academicStaff)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.classCount").isNumber())
                .andExpect(jsonPath("$.studentCount").isNumber());
        mvc.perform(get("/report-cards/classes")
                        .queryParam("academicYearId", "ay-2026")
                        .header("Authorization", bearer(academicStaff)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].classId").isNotEmpty())
                .andExpect(jsonPath("$[0].studentCount").isNumber());
        mvc.perform(get("/report-cards/students")
                        .queryParam("academicYearId", "ay-2026")
                        .queryParam("classId", "c-10a1")
                        .queryParam("page", "0")
                        .queryParam("size", "5")
                        .header("Authorization", bearer(academicStaff)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(5));

        jdbc.update("update report_cards set status='HOMEROOM_SUBMITTED' where id=?", cardId);
        mvc.perform(post("/report-cards/u-student-1/approve")
                        .queryParam("academicYearId", "ay-2026")
                        .header("Authorization", bearer(academicStaff))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        mvc.perform(post("/report-cards/u-student-1/publish")
                        .queryParam("academicYearId", "ay-2026")
                        .header("Authorization", bearer(academicStaff))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict());

        mvc.perform(get("/report-cards")
                        .queryParam("academicYearId", "ay-2026")
                        .header("Authorization", bearer(teacher)))
                .andExpect(status().isOk());
        mvc.perform(get("/report-cards/students")
                        .queryParam("academicYearId", "ay-2026")
                        .queryParam("classId", "c-10a1")
                        .header("Authorization", bearer(teacher)))
                .andExpect(status().isOk());

        mvc.perform(get("/report-cards/u-student-1")
                        .queryParam("academicYearId", "ay-2026")
                        .header("Authorization", bearer(student)))
                .andExpect(status().isNotFound());

        mvc.perform(get("/report-cards/u-student-1")
                        .queryParam("academicYearId", "ay-2026")
                        .header("Authorization", bearer(parent)))
                .andExpect(status().isNotFound());

        mvc.perform(get("/report-cards")
                        .queryParam("academicYearId", "ay-2026")
                        .header("Authorization", bearer(accountant)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/report-cards/overview")
                        .queryParam("academicYearId", "ay-2026")
                        .header("Authorization", bearer(accountant)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Tạo năm học tự sinh đủ hai học kỳ")
    void academicYearCreationAutomaticallyCreatesTwoSemesters() throws Exception {
        String academicStaff = login("giaovu", "Giaovu123@@");

        mvc.perform(post("/academicYears")
                        .header("Authorization", bearer(academicStaff))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"ay-e2e-2028","code":"2028-2029","name":"Năm học 2028-2029",
                                 "startDate":"2028-08-14","endDate":"2029-05-31"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PLANNED"));

        mvc.perform(get("/semesters")
                        .queryParam("academicYearId", "ay-e2e-2028")
                        .header("Authorization", bearer(academicStaff)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].code").value("HK1"))
                .andExpect(jsonPath("$[1].code").value("HK2"));
    }

    @Test
    @DisplayName("Kế toán phát hành công nợ và phụ huynh nhận mã VietQR")
    void financePublicationProducesInvoicesAndParentPaymentQr() throws Exception {
        String accountant = login("ketoan", "Ketoan123@@");
        String parent = login("ph.nguyenvanhung", "nguyenvanhung123@");

        JsonNode period = response(post("/fee-periods")
                        .header("Authorization", bearer(accountant))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"E2E-FIN-2026","name":"Khoản thu E2E","academicYearId":"ay-2026","applyToGrades":"K10",
                                 "dueDate":"2026-10-01"}
                                """), 200);
        String periodId = period.path("id").asText();

        mvc.perform(post("/fee-periods/{id}/items", periodId)
                        .header("Authorization", bearer(accountant))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Học phí kiểm thử","amount":350000,"gradeLevel":"K10"}
                                """))
                .andExpect(status().isOk());
        mvc.perform(post("/fee-periods/{id}/open", periodId)
                        .header("Authorization", bearer(accountant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));

        JsonNode invoices = response(post("/fee-periods/{id}/generate-invoices", periodId)
                .header("Authorization", bearer(accountant)), 200);
        assertFalse(invoices.isEmpty());
        String invoiceId = invoices.get(0).path("id").asText();

        mvc.perform(post("/payments")
                        .header("Authorization", bearer(parent))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"invoiceId\":\"" + invoiceId + "\",\"method\":\"VIETQR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gateway").value("VIETQR"))
                .andExpect(jsonPath("$.payment.status").value("PENDING"))
                .andExpect(jsonPath("$.qrImageUrl").isNotEmpty())
                .andExpect(jsonPath("$.transferContent").isNotEmpty());
    }

    private String login(String username, String password) throws Exception {
        JsonNode body = response(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new Login(username, password))), 200);
        return body.path("accessToken").asText();
    }

    private JsonNode response(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                              int expectedStatus) throws Exception {
        String content = mvc.perform(request)
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(content);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private record Login(String username, String password) {}
}
