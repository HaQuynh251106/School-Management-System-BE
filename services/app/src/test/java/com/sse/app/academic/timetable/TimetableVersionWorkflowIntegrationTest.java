package com.sse.app.academic.timetable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

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
    @Autowired JdbcTemplate jdbc;

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
        org.junit.jupiter.api.Assertions.assertEquals(0, count(
                "select count(*) from timetable_publication_events where plan_id=?", firstId));

        String admin = login("admin", "Admin123@@");
        mvc.perform(post("/timetable-versions/{id}/publish", firstId)
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"versionName":"Không được phép","reason":"Admin không thuộc vai trò phát hành lịch"}
                                """))
                .andExpect(status().isForbidden());
        mvc.perform(post("/timetable-versions/{id}/publish", firstId)
                        .header("Authorization", bearer(academicStaff))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"versionName":"Bản phát hành đầu tiên","reason":""}
                                """))
                .andExpect(status().isBadRequest());
        org.junit.jupiter.api.Assertions.assertEquals(0, count(
                "select count(*) from timetable_publication_events where plan_id=?", firstId));

        mvc.perform(get("/timetable-versions/{id}/publication-preview", firstId)
                        .header("Authorization", bearer(academicStaff)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstPublication").value(true))
                .andExpect(jsonPath("$.teacherRecipientCount").value(2))
                .andExpect(jsonPath("$.studentRecipientCount").value(2))
                .andExpect(jsonPath("$.parentRecipientCount").value(2))
                .andExpect(jsonPath("$.totalRecipientCount").value(6));

        mvc.perform(post("/timetable-versions/{id}/publish", firstId)
                        .header("Authorization", bearer(academicStaff))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"versionName":"Bản phát hành đầu tiên","reason":"Áp dụng lịch chính thức học kỳ 1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.publication.eventType").value("FIRST_PUBLICATION"));
        waitForPublication(firstId, 6);
        org.junit.jupiter.api.Assertions.assertEquals(6, count(
                "select count(*) from timetable_publication_recipients r join timetable_publication_events e on e.id=r.event_id where e.plan_id=? and r.status='DELIVERED'",
                firstId));
        String studentToken = login("hs.nguyenminhan", "nguyenminhanh123@@");
        String studentNotificationId = jdbc.queryForObject("""
                select n.id from notifications n join timetable_publication_events e on n.ref_id like concat(e.id,'%')
                where e.plan_id=? and n.recipient_id='u-student-1' and n.ref_type='TIMETABLE_PUBLICATION'
                """, String.class, firstId);
        mvc.perform(post("/notifications/{id}/read", studentNotificationId)
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(true))
                .andExpect(jsonPath("$.readAt").isNotEmpty());
        org.junit.jupiter.api.Assertions.assertEquals(1, count(
                "select count(*) from notifications where id=? and read_at is not null", studentNotificationId));

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
        jdbc.update("update timetable_plan_slots set room_code='LAB1' where id=(select min(id) from timetable_plan_slots where plan_id=? and class_id='c-10a1')",
                restoredId);

        mvc.perform(get("/timetable-versions/{id}/publication-preview", restoredId)
                        .header("Authorization", bearer(academicStaff)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstPublication").value(false))
                .andExpect(jsonPath("$.changeCount").value(1))
                .andExpect(jsonPath("$.teacherRecipientCount").value(1))
                .andExpect(jsonPath("$.studentRecipientCount").value(1))
                .andExpect(jsonPath("$.parentRecipientCount").value(1));

        mvc.perform(post("/timetable-versions/{id}/publish", restoredId)
                        .header("Authorization", bearer(academicStaff))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"versionName":"Bản thay thế đã kiểm tra","reason":"Điều chỉnh lịch theo phương án mới"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.publication.eventType").value("REPLACEMENT"));
        waitForPublication(restoredId, 3);
        org.junit.jupiter.api.Assertions.assertEquals(1, count(
                "select count(*) from notifications n join timetable_publication_events e on e.plan_id=? "
                        + "where n.recipient_id='u-parent-1' and n.ref_type='TIMETABLE_PUBLICATION' "
                        + "and n.ref_id like concat(e.id,'%') and n.body like '%Nguyễn Minh An%'", restoredId));

        mvc.perform(post("/timetable-versions/{id}/publish", restoredId)
                        .header("Authorization", bearer(academicStaff))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"versionName":"Bản thay thế đã kiểm tra","reason":"Gọi lại do mất kết nối phía người dùng"}
                                """))
                .andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertEquals(1, count(
                "select count(*) from timetable_publication_events where plan_id=?", restoredId));

        String replacementEventId = jdbc.queryForObject(
                "select id from timetable_publication_events where plan_id=?", String.class, restoredId);
        String failedRecipientId = jdbc.queryForObject(
                "select min(id) from timetable_publication_recipients where event_id=?", String.class, replacementEventId);
        int notificationCountBeforeRetry = count(
                "select count(*) from notifications where ref_type='TIMETABLE_PUBLICATION' and ref_id like concat(?,'%')",
                replacementEventId);
        jdbc.update("update timetable_publication_recipients set status='FAILED' where id=?", failedRecipientId);
        jdbc.update("update timetable_publication_events set status='FAILED',failed_recipient_count=1 where id=?",
                replacementEventId);
        mvc.perform(post("/timetable-versions/{id}/publication-retry", restoredId)
                        .header("Authorization", bearer(academicStaff))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"Thử lại lượt gửi bị gián đoạn trong kiểm thử"}
                                """))
                .andExpect(status().isOk());
        waitForPublication(restoredId, 3);
        org.junit.jupiter.api.Assertions.assertEquals(notificationCountBeforeRetry, count(
                "select count(*) from notifications where ref_type='TIMETABLE_PUBLICATION' and ref_id like concat(?,'%')",
                replacementEventId));

        mvc.perform(get("/timetable-versions").param("semesterId", "sm-2026-1")
                        .header("Authorization", bearer(academicStaff)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("PUBLISHED"))
                .andExpect(jsonPath("$[1].status").value("SUPERSEDED"));

        String student = login("hs.binh", "student@123");
        mvc.perform(get("/timetable-versions").param("semesterId", "sm-2026-1")
                        .header("Authorization", bearer(student)))
                .andExpect(status().isForbidden());

        String invalidPayload = mvc.perform(post("/timetable-versions/{id}/restore", restoredId)
                        .header("Authorization", bearer(academicStaff))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Bản lỗi dùng để kiểm tra rollback"}
                                """))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String invalidId = json.readTree(invalidPayload).path("id").asText();
        List<String> collidingSlots = jdbc.queryForList("""
                select id from timetable_plan_slots
                where plan_id=? and class_id='c-10a1'
                order by id limit 2
                """, String.class, invalidId);
        org.junit.jupiter.api.Assertions.assertEquals(2, collidingSlots.size());
        jdbc.update("""
                update timetable_plan_slots
                set day_of_week='MON',period_no=1,start_time='07:00',end_time='07:45'
                where id in (?,?)
                """,
                collidingSlots.get(0), collidingSlots.get(1));
        mvc.perform(post("/timetable-versions/{id}/publish", invalidId)
                        .header("Authorization", bearer(academicStaff))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"versionName":"Không hợp lệ","reason":"Kiểm tra transaction phải rollback an toàn"}
                                """))
                .andExpect(status().isConflict());
        org.junit.jupiter.api.Assertions.assertEquals(0, count(
                "select count(*) from timetable_publication_events where plan_id=?", invalidId));
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

    private void waitForPublication(String planId, int delivered) throws InterruptedException {
        for (int attempt = 0; attempt < 50; attempt++) {
            if (count("select delivered_recipient_count from timetable_publication_events where plan_id=?", planId)
                    == delivered) return;
            Thread.sleep(40);
        }
        org.junit.jupiter.api.Assertions.fail("Thông báo lịch chưa được xử lý xong cho " + planId);
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }
}
