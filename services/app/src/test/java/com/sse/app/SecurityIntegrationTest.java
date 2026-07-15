package com.sse.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class SecurityIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void healthIsPublicButBusinessEndpointsRequireAuthentication() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mvc.perform(get("/users"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void mobileWebPreviewOriginCanCallTheAuthenticationApi() throws Exception {
        String mobileOrigin = "http://127.0.0.1:8080";

        mvc.perform(options("/auth/login")
                        .header("Origin", mobileOrigin)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", mobileOrigin));
    }

    @Test
    void teacherCannotReadFinanceData() throws Exception {
        String token = login("gv.hoa", "teacher@123");

        mvc.perform(get("/invoices").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void onlyHomeroomTeacherCanReadAStudentsFullProfile() throws Exception {
        String homeroomTeacher = login("gv.hoa", "teacher@123");

        mvc.perform(get("/classes/c-10a1/students/u-student-1/profile")
                        .header("Authorization", "Bearer " + homeroomTeacher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Phạm Hoài An"))
                .andExpect(jsonPath("$.dateOfBirth").value("2010-03-18"))
                .andExpect(jsonPath("$.guardianName").value("Phạm Văn Quân"));

        mvc.perform(get("/users")
                        .queryParam("role", "STUDENT")
                        .queryParam("classId", "c-10a1")
                        .queryParam("q", "hs.an")
                        .header("Authorization", "Bearer " + homeroomTeacher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fullName").value("Phạm Hoài An"))
                .andExpect(jsonPath("$[0].email").value(nullValue()))
                .andExpect(jsonPath("$[0].guardianName").value(nullValue()));

        mvc.perform(get("/users/u-student-1")
                        .header("Authorization", "Bearer " + homeroomTeacher))
                .andExpect(status().isForbidden());

        String otherTeacher = login("gv.minh", "teacher@123");
        mvc.perform(get("/classes/c-10a1/students/u-student-1/profile")
                        .header("Authorization", "Bearer " + otherTeacher))
                .andExpect(status().isForbidden());
    }

    @Test
    void studentCannotReadAnotherStudentsPayments() throws Exception {
        String admin = login("admin", "admin@123");
        JsonNode invoices = body(mvc.perform(get("/invoices")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        String otherInvoiceId = null;
        for (JsonNode invoice : invoices) {
            if ("u-student-2".equals(invoice.path("studentId").asText())) {
                otherInvoiceId = invoice.path("id").asText();
                break;
            }
        }
        if (otherInvoiceId == null) throw new AssertionError("Seed invoice for u-student-2 is missing");

        String student = login("hs.an", "student@123");
        mvc.perform(get("/payments")
                        .queryParam("invoiceId", otherInvoiceId)
                        .header("Authorization", "Bearer " + student))
                .andExpect(status().isForbidden());
    }

    @Test
    void userCreationRejectsWeakPasswordsAndUnknownRoles() throws Exception {
        String admin = login("admin", "admin@123");

        mvc.perform(post("/users")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"weak.user","password":"123","fullName":"Weak User","role":"OWNER"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminCanCreateAndUpdateACompleteStudentProfile() throws Exception {
        String admin = login("admin", "admin@123");

        mvc.perform(post("/users")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "id":"u-student-admin-profile",
                                  "username":"hs.admin.profile",
                                  "password":"Student@123",
                                  "fullName":"Nguyễn Minh Anh",
                                  "role":"STUDENT",
                                  "email":"minhanh@sse.edu.vn",
                                  "phone":"0912345678",
                                  "studentCode":"HS2025099",
                                  "classId":"c-10a1",
                                  "className":"10A1",
                                  "dateOfBirth":"2010-11-20",
                                  "gender":"FEMALE",
                                  "placeOfBirth":"Hà Nội",
                                  "ethnicity":"Kinh",
                                  "nationality":"Việt Nam",
                                  "address":"20 Nguyễn Trãi, Hà Nội",
                                  "enrollmentDate":"2025-09-05",
                                  "guardianName":"Nguyễn Văn Minh",
                                  "guardianPhone":"0987654321"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.studentCode").value("HS2025099"))
                .andExpect(jsonPath("$.dateOfBirth").value("2010-11-20"))
                .andExpect(jsonPath("$.address").value("20 Nguyễn Trãi, Hà Nội"))
                .andExpect(jsonPath("$.guardianName").value("Nguyễn Văn Minh"));

        mvc.perform(put("/users/u-student-admin-profile")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"guardianName":"Nguyễn Thị Hà","guardianPhone":"0901122334"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.guardianName").value("Nguyễn Thị Hà"))
                .andExpect(jsonPath("$.guardianPhone").value("0901122334"));

        mvc.perform(get("/users/u-student-admin-profile")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Nguyễn Minh Anh"))
                .andExpect(jsonPath("$.classId").value("c-10a1"))
                .andExpect(jsonPath("$.guardianName").value("Nguyễn Thị Hà"));
    }

    @Test
    void refreshTokensAreRotatedAndLogoutRevokesTheCurrentToken() throws Exception {
        JsonNode login = loginBody("admin", "admin@123");
        String original = login.path("refreshToken").asText();

        String refreshedResponse = mvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new Refresh(original))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String rotated = body(refreshedResponse).path("refreshToken").asText();

        mvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new Refresh(original))))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new Refresh(rotated))))
                .andExpect(status().isOk());
        mvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new Refresh(rotated))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedUsersCanUploadAndDownloadAllowedAttachments() throws Exception {
        String token = login("hs.an", "student@123");
        MockMultipartFile file = new MockMultipartFile(
                "file", "homework.pdf", "application/pdf", "%PDF-test".getBytes());

        String response = mvc.perform(multipart("/files").file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originalName").value("homework.pdf"))
                .andReturn().getResponse().getContentAsString();
        String id = body(response).path("id").asText();

        mvc.perform(get("/files/{id}/content", id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void gradeCreateUpdateAndAuditFlowIsVersionSafe() throws Exception {
        String teacher = login("gv.hoa", "teacher@123");

        String createdResponse = mvc.perform(post("/grades")
                        .header("Authorization", "Bearer " + teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"studentId":"u-student-2","subjectId":"sj-math","semesterId":"sm-2025-1",
                                 "category":"FINAL","assessmentIndex":1,"score":7.5,"note":"Lần đầu"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assessmentIndex").value(1))
                .andExpect(jsonPath("$.createdBy").value("u-teacher-1"))
                .andReturn().getResponse().getContentAsString();
        JsonNode created = body(createdResponse);
        String gradeId = created.path("id").asText();
        long version = created.path("version").asLong();

        mvc.perform(post("/grades")
                        .header("Authorization", "Bearer " + teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"studentId":"u-student-2","subjectId":"sj-math","semesterId":"sm-2025-1",
                                 "category":"FINAL","assessmentIndex":1,"score":8.0}
                                """))
                .andExpect(status().isConflict());

        mvc.perform(put("/grades/{id}", gradeId)
                        .header("Authorization", "Bearer " + teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new GradeUpdate(8.0, "Sau phúc khảo", "Phúc khảo", version))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(8.0))
                .andExpect(jsonPath("$.updatedBy").value("u-teacher-1"));

        mvc.perform(put("/grades/{id}", gradeId)
                        .header("Authorization", "Bearer " + teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new GradeUpdate(8.5, null, "Bản sửa cũ", version))))
                .andExpect(status().isConflict());

        mvc.perform(get("/grades/{id}/change-logs", gradeId)
                        .header("Authorization", "Bearer " + teacher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("UPDATE"))
                .andExpect(jsonPath("$[1].action").value("CREATE"));
    }

    @Test
    void teacherCanAttachAFileAndStudentCanSubmitAFile() throws Exception {
        String teacher = login("gv.hoa", "teacher@123");
        String assignmentFileId = upload(teacher, "de-bai.pdf", "application/pdf", "%PDF-assignment");

        String assignmentResponse = mvc.perform(post("/assignments")
                        .header("Authorization", "Bearer " + teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"classId":"c-10a1","subjectId":"sj-math","title":"Bài tập có tệp đính kèm",
                                 "description":"Hoàn thành toàn bộ câu hỏi trong đề.","allowLate":true,
                                 "attachmentFileId":"%s","publishNow":true}
                                """.formatted(assignmentFileId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachmentName").value("de-bai.pdf"))
                .andReturn().getResponse().getContentAsString();
        String assignmentId = body(assignmentResponse).path("id").asText();

        String student = login("hs.an", "student@123");
        String submissionFileId = upload(student, "bai-lam.pdf", "application/pdf", "%PDF-submission");
        mvc.perform(post("/assignments/{id}/submit", assignmentId)
                        .header("Authorization", "Bearer " + student)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"Em gửi bài làm.","attachmentFileId":"%s"}
                                """.formatted(submissionFileId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachmentName").value("bai-lam.pdf"))
                .andExpect(jsonPath("$.status").value("SUBMITTED"));

        mvc.perform(get("/assignments/{id}/submissions", assignmentId)
                        .header("Authorization", "Bearer " + teacher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].attachmentFileId").value(submissionFileId));
    }

    @Test
    void teacherGradeWritesUseMainSubjectAndRejectDuplicateBulkStudents() throws Exception {
        String teacher = login("gv.hoa", "teacher@123");

        mvc.perform(get("/me/gradebook-context")
                        .queryParam("classId", "c-10a1")
                        .queryParam("semesterId", "sm-2025-1")
                        .header("Authorization", "Bearer " + teacher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subjectId").value("sj-math"))
                .andExpect(jsonPath("$.subjectName").value("Toán"))
                .andExpect(jsonPath("$.homeroomTeacher").value(true))
                .andExpect(jsonPath("$.canEdit").value(true))
                .andExpect(jsonPath("$.subjects.length()").value(5));

        mvc.perform(get("/grades")
                        .queryParam("classId", "c-10a1")
                        .queryParam("semesterId", "sm-2025-1")
                        .queryParam("subjectId", "sj-phys")
                        .header("Authorization", "Bearer " + teacher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].subjectId").value("sj-phys"));

        mvc.perform(post("/grades")
                        .header("Authorization", "Bearer " + teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"studentId":"u-student-2","subjectId":"sj-eng","semesterId":"sm-2025-1",
                                 "category":"MID","assessmentIndex":1,"score":8.0}
                                """))
                .andExpect(status().isForbidden());

        mvc.perform(post("/grades/bulk")
                        .header("Authorization", "Bearer " + teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"classId":"c-10a1","semesterId":"sm-2025-1","category":"MID","assessmentIndex":1,
                                 "entries":[{"studentId":"u-student-1","score":8.0},{"studentId":"u-student-1","score":8.5}]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminCanAssignAnActiveTeacherAsHomeroomTeacher() throws Exception {
        String admin = login("admin", "admin@123");

        mvc.perform(put("/classes/c-10a2/homeroom-teacher")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"teacherId":"u-teacher-1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.homeroomTeacherId").value("u-teacher-1"))
                .andExpect(jsonPath("$.homeroomTeacherName").value("Trần Thị Hoa"))
                .andExpect(jsonPath("$.homeroomAssignedAt").isNotEmpty())
                .andExpect(jsonPath("$.homeroomAssignedBy").value("u-admin-1"));
    }

    private String login(String username, String password) throws Exception {
        return loginBody(username, password).path("accessToken").asText();
    }

    private JsonNode loginBody(String username, String password) throws Exception {
        String response = mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new Login(username, password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return body(response);
    }

    private JsonNode body(String value) throws Exception {
        return json.readTree(value);
    }

    private String upload(String token, String name, String contentType, String content) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", name, contentType, content.getBytes());
        String response = mvc.perform(multipart("/files").file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return body(response).path("id").asText();
    }

    private record Login(String username, String password) {}
    private record Refresh(String refreshToken) {}
    private record GradeUpdate(Double score, String note, String reason, Long expectedVersion) {}
}
