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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
    void teacherCannotReadFinanceData() throws Exception {
        String token = login("gv.hoa", "teacher@123");

        mvc.perform(get("/invoices").header("Authorization", "Bearer " + token))
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

    private record Login(String username, String password) {}
    private record Refresh(String refreshToken) {}
}
