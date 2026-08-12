package com.sse.app.workcenter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.empty;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:work-center;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "sse.storage.path=target/work-center-uploads",
        "sse.exam-reminders.enabled=false",
        "sse.attendance-reminders.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@Transactional
class WorkCenterIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired WorkCenterService workCenter;
    @Autowired OperationTaskRepository tasks;
    @Autowired WorkCenterReminderScheduler reminderScheduler;
    @Autowired OperationTaskReminderRepository reminders;

    @Test
    void taskLifecycleIsScopedAndRequiresManagerConfirmation() throws Exception {
        String admin = login("admin", "Admin123@@");
        String academic = login("giaovu", "Giaovu123@@");
        String accountant = login("ketoan", "Ketoan123@@");

        JsonNode created = response(post("/work-center/tasks")
                .header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"title":"Nghiệm thu phân lớp","description":"Kiểm tra dữ liệu đầu cấp",
                         "module":"ACADEMIC","priority":"HIGH","assignedRole":"ACADEMIC_STAFF",
                         "dueDate":"2026-09-05","checklist":["Đối chiếu hồ sơ","Xác nhận sĩ số"]}
                        """), 201);
        String taskId = created.path("task").path("id").asText();

        mvc.perform(get("/work-center/tasks").header("Authorization", bearer(academic)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[?(@.id=='" + taskId + "')]").isNotEmpty());
        mvc.perform(get("/work-center/tasks/{id}", taskId).header("Authorization", bearer(accountant)))
                .andExpect(status().isNotFound());

        transition(academic, taskId, "ACCEPTED", null, 200);
        transition(academic, taskId, "IN_PROGRESS", null, 200);
        transition(academic, taskId, "WAITING_CONFIRMATION", "Đã hoàn thành kiểm tra", 200);
        transition(academic, taskId, "COMPLETED", null, 403);
        transition(admin, taskId, "COMPLETED", "Đã nghiệm thu", 200);

        mvc.perform(get("/work-center/tasks/{id}", taskId).header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("COMPLETED"))
                .andExpect(jsonPath("$.history", not(empty())));
    }

    @Test
    void attachmentsAndRoleSpecificAssigneesAreProtected() throws Exception {
        String admin = login("admin", "Admin123@@");
        String academic = login("giaovu", "Giaovu123@@");
        String accountant = login("ketoan", "Ketoan123@@");
        String teacher = login("gv.nguyenminh", "nguyenminh123@");
        JsonNode created = createTask(admin, "Tệp nghiệm thu", "ACADEMIC", "ACADEMIC_STAFF");
        String taskId = created.path("task").path("id").asText();

        MockMultipartFile upload = new MockMultipartFile("file", "bien-ban.txt", "text/plain",
                "Nội dung nghiệm thu".getBytes(StandardCharsets.UTF_8));
        JsonNode stored = response(multipart("/files").file(upload).header("Authorization", bearer(admin)), 200);
        String fileId = stored.path("id").asText();
        mvc.perform(post("/work-center/tasks/{id}/attachments", taskId)
                        .header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new Attachment("bien-ban.txt", fileId, "text/plain", 20))))
                .andExpect(status().isCreated());
        mvc.perform(get("/files/{id}/content", fileId).header("Authorization", bearer(academic)))
                .andExpect(status().isOk());

        mvc.perform(get("/work-center/assignees").param("role", "TEACHER")
                        .header("Authorization", bearer(teacher)))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value("u-teacher-1"));
        mvc.perform(get("/work-center/assignees").param("role", "ACCOUNTANT")
                        .header("Authorization", bearer(accountant)))
                .andExpect(status().isOk()).andExpect(jsonPath("$", not(empty())));
    }

    @Test
    void automaticTaskUpsertIsIdempotent() {
        var command = new WorkCenterDtos.AutoTaskCommand("TEST:IDEMPOTENT", "TEST", "source-1",
                "Công việc tự động", "Không sinh trùng", "OPERATIONS", "NORMAL",
                "ADMIN", null, LocalDate.now().plusDays(2), false);
        String first = workCenter.upsertAutoTask(command).getId();
        String second = workCenter.upsertAutoTask(command).getId();
        assertEquals(first, second);
        assertEquals(1, tasks.findAll().stream().filter(task -> "TEST:IDEMPOTENT".equals(task.getSourceKey())).count());
    }

    @Test
    void dueReminderIsIdempotentForTheSameDay() throws Exception {
        String admin = login("admin", "Admin123@@");
        JsonNode created = response(post("/work-center/tasks").header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new NewTask("Nhắc việc hôm nay", "ADMIN", "URGENT", "ADMIN", LocalDate.now()))), 201);
        String taskId = created.path("task").path("id").asText();
        reminderScheduler.scheduleAndDispatch();
        reminderScheduler.scheduleAndDispatch();
        assertEquals(1, reminders.findAll().stream().filter(item -> taskId.equals(item.getTaskId())
                && "DUE_TODAY".equals(item.getReminderType())).count());
    }

    private JsonNode createTask(String token, String title, String module, String role) throws Exception {
        return response(post("/work-center/tasks").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new NewTask(title, module, "NORMAL", role, LocalDate.now().plusDays(3)))), 201);
    }

    private void transition(String token, String taskId, String target, String note, int expected) throws Exception {
        mvc.perform(post("/work-center/tasks/{id}/transitions", taskId)
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new Transition(target, note))))
                .andExpect(status().is(expected));
    }

    private String login(String username, String password) throws Exception {
        return response(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new Login(username, password))), 200).path("accessToken").asText();
    }

    private JsonNode response(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                              int statusCode) throws Exception {
        return json.readTree(mvc.perform(request).andExpect(status().is(statusCode))
                .andReturn().getResponse().getContentAsString());
    }

    private String bearer(String token) { return "Bearer " + token; }
    private record Login(String username, String password) {}
    private record Transition(String status, String note) {}
    private record Attachment(String fileName, String fileUrl, String contentType, long fileSize) {}
    private record NewTask(String title, String module, String priority, String assignedRole, LocalDate dueDate) {}
}
