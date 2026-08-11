package com.sse.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import jakarta.servlet.http.Cookie;

import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:security-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "sse.storage.path=target/test-uploads"
})
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@Import(SecurityIntegrationTest.FixedClockConfiguration.class)
class SecurityIntegrationTest {
    @TestConfiguration
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock fixedSchoolClock() {
            return Clock.fixed(Instant.parse("2026-09-01T05:00:00Z"),
                    java.time.ZoneId.of("Asia/Ho_Chi_Minh"));
        }
    }


    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired com.sse.app.academic.attendance.AttendanceService attendanceService;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    void healthIsPublicButBusinessEndpointsRequireAuthentication() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mvc.perform(get("/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("X-Request-ID"))
                .andExpect(jsonPath("$.code").value("AUTH_TOKEN_MISSING"))
                .andExpect(jsonPath("$.requestId").isString())
                .andExpect(jsonPath("$.path").value("/users"));
    }

    @Test
    void preservesSafeClientRequestIdInStructuredErrors() throws Exception {
        String requestId = "web-test-20260728-001";
        mvc.perform(get("/users").header("X-Request-ID", requestId))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Request-ID", requestId))
                .andExpect(jsonPath("$.requestId").value(requestId))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.fieldErrors").isMap());
    }

    @Test
    void adminDashboardQueriesWorkOnBothPostgresqlAndDemoH2() throws Exception {
        String admin = login("admin", "admin@123");

        mvc.perform(get("/dashboard")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOf").isString())
                .andExpect(jsonPath("$.scope.role").value("ADMIN"))
                .andExpect(jsonPath("$.scope.objectType").value("SCHOOL"))
                .andExpect(jsonPath("$.metrics.length()").value(4))
                .andExpect(jsonPath("$.metrics[0].trend.direction").value("NONE"))
                .andExpect(jsonPath("$.charts.length()").value(2))
                .andExpect(jsonPath("$.charts[0].data.length()", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.shortcuts.length()", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.errors.length()").value(0));
    }

    @Test
    void dashboardUsesRoleAndObjectScopeWithoutLeakingAnotherStudent() throws Exception {
        String teacher = login("gv.hoa", "teacher@123");
        String student = login("hs.an", "student@123");
        String parent = login("ph.pham", "parent@123");

        mvc.perform(get("/dashboard").header("Authorization", "Bearer " + teacher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope.role").value("TEACHER"))
                .andExpect(jsonPath("$.scope.objectIds[0]").value("u-teacher-1"))
                .andExpect(jsonPath("$.shortcuts[0].target").value("timetable"));

        mvc.perform(get("/dashboard").header("Authorization", "Bearer " + student))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope.role").value("STUDENT"))
                .andExpect(jsonPath("$.scope.objectIds[0]").value("u-student-1"))
                .andExpect(jsonPath("$.shortcuts[1].target").value("assignments"));

        mvc.perform(get("/dashboard")
                        .queryParam("childId", "u-student-1")
                        .header("Authorization", "Bearer " + parent))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope.role").value("PARENT"))
                .andExpect(jsonPath("$.scope.objectIds.length()").value(1))
                .andExpect(jsonPath("$.scope.objectIds[0]").value("u-student-1"))
                .andExpect(jsonPath("$.shortcuts[0].filters.childId").value("u-student-1"));

        mvc.perform(get("/dashboard")
                        .queryParam("childId", "u-teacher-2")
                        .header("Authorization", "Bearer " + parent))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanFilterStudentsAndLinkedParentsByClass() throws Exception {
        String admin = login("admin", "admin@123");

        mvc.perform(get("/users")
                        .queryParam("role", "STUDENT")
                        .queryParam("classId", "c-10a1")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(hasItem("u-student-1")))
                .andExpect(jsonPath("$[*].classId").value(everyItem(is("c-10a1"))));

        mvc.perform(get("/users")
                        .queryParam("role", "PARENT")
                        .queryParam("classId", "c-10a1")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("u-parent-1"))
                .andExpect(jsonPath("$[0].childrenIds[0]").isString());

        mvc.perform(get("/users")
                        .queryParam("role", "PARENT")
                        .queryParam("classId", "c-10a2")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
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
    void webSessionCanRotateRefreshTokenUsingHttpOnlyCookie() throws Exception {
        var loginResult = mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(currentSeedCredentials("admin", "admin@123"))))
                .andExpect(status().isOk())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("HttpOnly")))
                .andReturn();
        Cookie cookie = loginResult.getResponse().getCookie("sse_refresh");
        org.junit.jupiter.api.Assertions.assertNotNull(cookie);

        mvc.perform(post("/auth/refresh")
                        .cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("sse_refresh=")));
    }

    @Test
    void teacherAndParentCanUseTheirPersonalNotificationInbox() throws Exception {
        String admin = login("admin", "admin@123");
        String teacher = login("gv.hoa", "teacher@123");
        String parent = login("ph.pham", "parent@123");

        mvc.perform(post("/announcements")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"audience":"ALL","category":"GENERAL","priority":"IMPORTANT",
                                 "title":"Thông báo kiểm thử hộp thư","body":"Nội dung dành cho giáo viên và phụ huynh"}
                                """))
                .andExpect(status().isOk());

        JsonNode teacherInbox = body(mvc.perform(get("/notifications")
                        .header("Authorization", "Bearer " + teacher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Thông báo kiểm thử hộp thư"))
                .andReturn().getResponse().getContentAsString());
        String notificationId = teacherInbox.get(0).path("id").asText();

        mvc.perform(post("/notifications/{id}/read", notificationId)
                        .header("Authorization", "Bearer " + teacher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(true));
        mvc.perform(post("/notifications/{id}/unread", notificationId)
                        .header("Authorization", "Bearer " + teacher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(false));

        mvc.perform(get("/notifications")
                        .header("Authorization", "Bearer " + parent))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Thông báo kiểm thử hộp thư"));
    }

    @Test
    void teacherCannotReadFinanceData() throws Exception {
        String token = login("gv.hoa", "teacher@123");

        mvc.perform(get("/invoices").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void attendanceRegisterIsIdempotentAndRestrictedToTheAssignedTeacher() throws Exception {
        String teacher = login("gv.hoa", "teacher@123");

        mvc.perform(get("/attendance")
                        .queryParam("slotId", "tt-1")
                        .queryParam("date", "2026-05-18")
                        .header("Authorization", "Bearer " + teacher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].studentId").value("u-student-1"));

        mvc.perform(get("/attendance")
                        .queryParam("slotId", "tt-2")
                        .queryParam("date", "2026-05-18")
                        .header("Authorization", "Bearer " + teacher))
                .andExpect(status().isForbidden());

        mvc.perform(get("/attendance")
                        .queryParam("slotId", "tt-3")
                        .queryParam("date", "2026-05-18")
                        .header("Authorization", "Bearer " + teacher))
                .andExpect(status().isForbidden());

        mvc.perform(post("/attendance/bulk")
                        .header("Authorization", "Bearer " + teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slotId":"tt-3","date":"2026-06-01","marks":[
                                  {"studentId":"u-student-1","status":"PRESENT"}]}
                                """))
                .andExpect(status().isForbidden());

        mvc.perform(post("/attendance/unlock")
                        .header("Authorization", "Bearer " + teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slotId":"tt-1","date":"2026-08-24",
                                 "reason":"Bổ sung sổ điểm danh cũ sau khi đối soát dữ liệu lớp học"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("LATE_UNLOCKED"));

        mvc.perform(post("/attendance/bulk")
                        .header("Authorization", "Bearer " + teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slotId":"tt-1","date":"2026-08-24","classId":"c-8a1","subjectName":"Sai",
                                 "periodNo":9,"marks":[{"studentId":"u-student-1","status":"LATE","note":"  Muộn 5 phút  "}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].classId").value("c-10a1"))
                .andExpect(jsonPath("$[0].subjectName").value("Toán"))
                .andExpect(jsonPath("$[0].periodNo").value(1))
                .andExpect(jsonPath("$[0].note").value("Muộn 5 phút"));

        String student = login("hs.an", "student@123");
        String parent = login("ph.pham", "parent@123");
        mvc.perform(get("/notifications")
                        .header("Authorization", "Bearer " + student))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("ATTENDANCE"))
                .andExpect(jsonPath("$[0].priority").value("IMPORTANT"))
                .andExpect(jsonPath("$[0].body").value(org.hamcrest.Matchers.containsString("đi muộn")));
        mvc.perform(get("/notifications")
                        .header("Authorization", "Bearer " + parent))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("ATTENDANCE"))
                .andExpect(jsonPath("$[0].body").value(org.hamcrest.Matchers.containsString("Nguyễn Minh An")));

        int unreadBeforeUnchangedSave = body(mvc.perform(get("/notifications/unread-count")
                        .header("Authorization", "Bearer " + student))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("count").asInt();
        mvc.perform(post("/attendance/bulk")
                        .header("Authorization", "Bearer " + teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slotId":"tt-1","date":"2026-08-24","marks":[
                                  {"studentId":"u-student-1","status":"LATE","note":"Muộn 5 phút"}]}
                                """))
                .andExpect(status().isOk());
        int unreadAfterUnchangedSave = body(mvc.perform(get("/notifications/unread-count")
                        .header("Authorization", "Bearer " + student))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("count").asInt();
        org.junit.jupiter.api.Assertions.assertEquals(unreadBeforeUnchangedSave, unreadAfterUnchangedSave);

        mvc.perform(post("/attendance/bulk")
                        .header("Authorization", "Bearer " + teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slotId":"tt-1","date":"2026-08-24","marks":[
                                  {"studentId":"u-student-1","status":"PRESENT"}]}
                                """))
                .andExpect(status().isOk());
        mvc.perform(get("/notifications")
                        .header("Authorization", "Bearer " + student))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("ATTENDANCE"))
                .andExpect(jsonPath("$[0].priority").value("NORMAL"))
                .andExpect(jsonPath("$[0].body").value(org.hamcrest.Matchers.containsString("có mặt")));

        mvc.perform(post("/attendance/bulk")
                        .header("Authorization", "Bearer " + teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slotId":"tt-1","date":"2026-08-24","marks":[
                                  {"studentId":"u-student-1","status":"PRESENT"},
                                  {"studentId":"u-student-1","status":"PRESENT"}]}
                                """))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/attendance/bulk")
                        .header("Authorization", "Bearer " + teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slotId":"tt-1","date":"2026-08-24","marks":[
                                  {"studentId":"u-student-1","status":"ABSENT_EXCUSED"}]}
                                """))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/attendance/bulk")
                        .header("Authorization", "Bearer " + teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slotId":"tt-1","date":"2026-08-24","marks":[
                                  {"studentId":"u-student-2","status":"PRESENT"}]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void schoolHolidayAnnouncementAutomaticallyDisablesAttendance() throws Exception {
        String admin = login("admin", "admin@123");
        String teacher = login("gv.hoa", "teacher@123");

        mvc.perform(post("/announcements")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"audience":"ALL","category":"HOLIDAY","priority":"IMPORTANT",
                                 "title":"Nghỉ Tết thử nghiệm","body":"Nhà trường nghỉ theo thông báo.",
                                 "holidayStartDate":"2026-02-02","holidayEndDate":"2026-02-03"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holidayStartDate").value("2026-02-02"))
                .andExpect(jsonPath("$.holidayEndDate").value("2026-02-03"));

        mvc.perform(get("/attendance/day-status")
                        .queryParam("date", "2026-02-02")
                        .header("Authorization", "Bearer " + teacher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attendanceRequired").value(false))
                .andExpect(jsonPath("$.title").value("Nghỉ Tết thử nghiệm"));

        mvc.perform(post("/attendance/bulk")
                        .header("Authorization", "Bearer " + teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slotId":"tt-1","date":"2026-02-02","marks":[
                                  {"studentId":"u-student-1","status":"PRESENT"}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Không cần điểm danh ngày nghỉ")));

        mvc.perform(get("/attendance/day-status")
                        .queryParam("date", "2026-02-04")
                        .header("Authorization", "Bearer " + teacher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attendanceRequired").value(true));
    }

    @Test
    void teacherIsRemindedAndMustExplainBeforeLateAttendanceUnlock() throws Exception {
        String teacher = login("gv.hoa", "teacher@123");
        String admin = login("admin", "admin@123");
        var lessonTime = java.time.ZonedDateTime.of(2026, 8, 31, 7, 5, 0, 0,
                java.time.ZoneId.of("Asia/Ho_Chi_Minh"));

        org.junit.jupiter.api.Assertions.assertEquals(1, attendanceService.sendDueReminders(lessonTime));
        org.junit.jupiter.api.Assertions.assertEquals(0, attendanceService.sendDueReminders(lessonTime.plusMinutes(1)));

        mvc.perform(get("/notifications")
                        .header("Authorization", "Bearer " + teacher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("ATTENDANCE_REMINDER"))
                .andExpect(jsonPath("$[0].refType").value("ATTENDANCE_SESSION"));

        assertTrue(attendanceService.sendDueReminders(lessonTime.withHour(8).withMinute(0)) >= 1);
        org.junit.jupiter.api.Assertions.assertEquals(0,
                attendanceService.sendDueReminders(lessonTime.withHour(8).withMinute(1)));

        mvc.perform(get("/notifications")
                        .header("Authorization", "Bearer " + teacher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("ATTENDANCE_MISSED"))
                .andExpect(jsonPath("$[0].priority").value("URGENT"))
                .andExpect(jsonPath("$[0].refType").value("ATTENDANCE_MISSED"));

        mvc.perform(get("/attendance/session-status")
                        .queryParam("slotId", "tt-1")
                        .queryParam("date", "2026-08-31")
                        .header("Authorization", "Bearer " + teacher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("LOCKED_REASON_REQUIRED"))
                .andExpect(jsonPath("$.canMark").value(false))
                .andExpect(jsonPath("$.requiresUnlockReason").value(true));

        mvc.perform(post("/attendance/unlock")
                        .header("Authorization", "Bearer " + teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slotId":"tt-1","date":"2026-08-31","reason":"Mạng lỗi"}
                                """))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/attendance/unlock")
                        .header("Authorization", "Bearer " + teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slotId":"tt-1","date":"2026-08-31",
                                 "reason":"Thiết bị lớp học mất kết nối mạng trong suốt tiết học"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("LATE_UNLOCKED"))
                .andExpect(jsonPath("$.canMark").value(true))
                .andExpect(jsonPath("$.unlockReason").exists());

        mvc.perform(post("/attendance/bulk")
                        .header("Authorization", "Bearer " + teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slotId":"tt-1","date":"2026-08-31","marks":[
                                  {"studentId":"u-student-1","status":"PRESENT"}]}
                                """))
                .andExpect(status().isOk());

        mvc.perform(get("/attendance/session-status")
                        .queryParam("slotId", "tt-1")
                        .queryParam("date", "2026-08-31")
                        .header("Authorization", "Bearer " + teacher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("COMPLETED_LATE"));

        mvc.perform(get("/notifications")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("ATTENDANCE_UNLOCK"));
    }

    @Test
    void onlyHomeroomTeacherCanReadAStudentsFullProfile() throws Exception {
        String homeroomTeacher = login("gv.hoa", "teacher@123");

        mvc.perform(get("/classes/c-10a1/students/u-student-1/profile")
                        .header("Authorization", "Bearer " + homeroomTeacher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Nguyễn Minh An"))
                .andExpect(jsonPath("$.dateOfBirth").value("2010-03-18"))
                .andExpect(jsonPath("$.guardianName").value("Phạm Văn Quân"));

        mvc.perform(get("/users")
                        .queryParam("role", "STUDENT")
                        .queryParam("classId", "c-10a1")
                        .queryParam("q", "hs.nguyenminhan")
                        .header("Authorization", "Bearer " + homeroomTeacher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fullName").value("Nguyễn Minh An"))
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
                .andExpect(jsonPath("$.storageName").doesNotExist())
                .andExpect(jsonPath("$.uploadedBy").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String id = body(response).path("id").asText();

        String otherStudent = login("hs.binh", "student@123");
        mvc.perform(get("/files/{id}", id)
                        .header("Authorization", "Bearer " + otherStudent))
                .andExpect(status().isForbidden());
        mvc.perform(get("/files/{id}/content", id)
                        .header("Authorization", "Bearer " + otherStudent))
                .andExpect(status().isForbidden());

        mvc.perform(get("/files/{id}/content", id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        MockMultipartFile disguisedExecutable = new MockMultipartFile(
                "file", "not-really.pdf", "application/pdf", "MZ executable".getBytes());
        mvc.perform(multipart("/files").file(disguisedExecutable)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("định dạng")));
    }

    @Test
    void gradeCreateUpdateAndAuditFlowIsVersionSafe() throws Exception {
        String teacher = login("gv.hoa", "teacher@123");
        String student = login("hs.binh", "student@123");
        String parent = login("ph.pham", "parent@123");

        String createdResponse = mvc.perform(post("/grades")
                        .header("Authorization", "Bearer " + teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"studentId":"u-student-2","subjectId":"sj-math","semesterId":"sm-2026-1",
                                 "category":"FINAL","assessmentIndex":1,"score":7.5,"note":"Lần đầu"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assessmentIndex").value(1))
                .andExpect(jsonPath("$.createdBy").value("u-teacher-1"))
                .andReturn().getResponse().getContentAsString();
        JsonNode created = body(createdResponse);
        String gradeId = created.path("id").asText();
        long version = created.path("version").asLong();

        mvc.perform(get("/notifications")
                        .header("Authorization", "Bearer " + student))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("GRADE"))
                .andExpect(jsonPath("$[0].priority").value("IMPORTANT"))
                .andExpect(jsonPath("$[0].title").value("Có điểm mới"))
                .andExpect(jsonPath("$[0].body").value(org.hamcrest.Matchers.containsString("7.5")));
        mvc.perform(get("/notifications")
                        .header("Authorization", "Bearer " + parent))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("GRADE"))
                .andExpect(jsonPath("$[0].body").value(org.hamcrest.Matchers.containsString("Phạm Hoài Bình")));

        mvc.perform(post("/grades")
                        .header("Authorization", "Bearer " + teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"studentId":"u-student-2","subjectId":"sj-math","semesterId":"sm-2026-1",
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

        mvc.perform(get("/notifications")
                        .header("Authorization", "Bearer " + student))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Điểm được cập nhật"))
                .andExpect(jsonPath("$[0].body").value(org.hamcrest.Matchers.containsString("8.0")));

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

        String extendedDeadline = Instant.now().plusSeconds(7 * 24 * 60 * 60).toString();
        mvc.perform(put("/assignments/{id}", assignmentId)
                        .header("Authorization", "Bearer " + teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Bài tập đã chỉnh sửa","description":"Nội dung sau chỉnh sửa",
                                 "allowLate":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Bài tập đã chỉnh sửa"));
        mvc.perform(post("/assignments/{id}/extend", assignmentId)
                        .header("Authorization", "Bearer " + teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deadline\":\"" + extendedDeadline + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deadline").value(extendedDeadline));
        mvc.perform(post("/assignments/{id}/close", assignmentId)
                        .header("Authorization", "Bearer " + teacher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        String parent = login("ph.pham", "parent@123");
        mvc.perform(get("/children/u-student-1/assignments")
                        .header("Authorization", "Bearer " + parent))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(hasItem(assignmentId)));
        mvc.perform(post("/assignments/{id}/reopen", assignmentId)
                        .header("Authorization", "Bearer " + teacher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));

        String student = login("hs.an", "student@123");
        String otherStudent = login("hs.binh", "student@123");
        mvc.perform(get("/files/{id}/content", assignmentFileId)
                        .header("Authorization", "Bearer " + otherStudent))
                .andExpect(status().isForbidden());
        mvc.perform(get("/files/{id}/content", assignmentFileId)
                        .header("Authorization", "Bearer " + student))
                .andExpect(status().isOk());

        String submissionFileId = upload(student, "bai-lam.pdf", "application/pdf", "%PDF-submission");
        String submissionResponse = mvc.perform(post("/assignments/{id}/submit", assignmentId)
                        .header("Authorization", "Bearer " + student)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"Em gửi bài làm.","attachmentFileId":"%s"}
                                """.formatted(submissionFileId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachmentName").value("bai-lam.pdf"))
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andReturn().getResponse().getContentAsString();
        String submissionId = body(submissionResponse).path("id").asText();

        mvc.perform(get("/assignments/{id}/submissions", assignmentId)
                        .header("Authorization", "Bearer " + teacher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].attachmentFileId").value(submissionFileId));

        mvc.perform(get("/files/{id}/content", submissionFileId)
                        .header("Authorization", "Bearer " + otherStudent))
                .andExpect(status().isForbidden());
        mvc.perform(get("/files/{id}/content", submissionFileId)
                        .header("Authorization", "Bearer " + teacher))
                .andExpect(status().isOk());

        mvc.perform(post("/submissions/{id}/grade", submissionId)
                        .header("Authorization", "Bearer " + teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"score\":8.5,\"feedback\":\"Bài làm tốt\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("GRADED"));
        mvc.perform(post("/submissions/{id}/allow-resubmit", submissionId)
                        .header("Authorization", "Bearer " + teacher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESUBMISSION_ALLOWED"))
                .andExpect(jsonPath("$.resubmissionAllowed").value(true));
        mvc.perform(post("/assignments/{id}/submit", assignmentId)
                        .header("Authorization", "Bearer " + student)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"Em nộp lại bài đã sửa.\",\"attachmentFileId\":\"" + submissionFileId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.attemptNumber").value(2));
        mvc.perform(get("/submissions/{id}/attempts", submissionId)
                        .header("Authorization", "Bearer " + student))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].attemptNumber").value(2))
                .andExpect(jsonPath("$[1].attemptNumber").value(1))
                .andExpect(jsonPath("$[1].score").value(8.5));
        mvc.perform(get("/submissions/{id}/attempts", submissionId)
                        .header("Authorization", "Bearer " + otherStudent))
                .andExpect(status().isForbidden());
        mvc.perform(get("/children/u-student-1/submissions")
                        .header("Authorization", "Bearer " + parent))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(hasItem(submissionId)));
    }

    @Test
    void leaveRequestRequiresParentConfirmationBeforeHomeroomApproval() throws Exception {
        String student = login("hs.an", "student@123");
        String parent = login("ph.pham", "parent@123");
        String homeroomTeacher = login("gv.hoa", "teacher@123");
        LocalDate start = LocalDate.now().plusDays(10);
        LocalDate end = start.plusDays(1);

        String response = mvc.perform(post("/leave-requests")
                        .header("Authorization", "Bearer " + student)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"startDate":"%s","endDate":"%s","reason":"Khám sức khỏe theo lịch hẹn"}
                                """.formatted(start, end)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_PARENT"))
                .andReturn().getResponse().getContentAsString();
        String requestId = body(response).path("id").asText();

        mvc.perform(post("/leave-requests")
                        .header("Authorization", "Bearer " + student)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startDate\":\"" + start + "\",\"endDate\":\"" + end + "\",\"reason\":\"Đơn bị trùng ngày\"}"))
                .andExpect(status().isConflict());

        mvc.perform(post("/leave-requests/{id}/approve", requestId)
                        .header("Authorization", "Bearer " + homeroomTeacher))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/leave-requests/{id}/parent-confirm", requestId)
                        .header("Authorization", "Bearer " + parent)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Phụ huynh xác nhận\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_HOMEROOM"));
        mvc.perform(post("/leave-requests/{id}/approve", requestId)
                        .header("Authorization", "Bearer " + homeroomTeacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Đã duyệt và báo giáo viên bộ môn\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        mvc.perform(get("/attendance/approved-leaves")
                        .queryParam("slotId", "tt-1")
                        .queryParam("date", start.toString())
                        .header("Authorization", "Bearer " + homeroomTeacher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].studentId").value("u-student-1"));
        mvc.perform(get("/leave-requests").header("Authorization", "Bearer " + parent))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(hasItem(requestId)));
    }

    @Test
    void approvedLeaveReconcilesAttendanceAndSuppressesDuplicateAlerts() throws Exception {
        String teacher = login("gv.hoa", "teacher@123");
        LocalDate date = LocalDate.of(2026, 8, 17); // Thứ Hai, khớp tiết tt-1.

        jdbc.update("""
                insert into attendance_records
                    (id, student_id, class_id, slot_id, date, status, note, subject_name, period_no)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, "att-approved-leave-flow", "u-student-1", "c-10a1", "tt-1", date,
                "ABSENT_UNEXCUSED", "Chưa nhận được đơn", "Toán", 1);
        for (String recipientId : List.of("u-student-1", "u-parent-1")) {
            jdbc.update("""
                    insert into notifications
                        (id, recipient_id, type, priority, title, body, read, ref_type, ref_id, created_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, "noti-approved-leave-" + recipientId, recipientId, "ATTENDANCE", "URGENT",
                    "Cảnh báo chuyên cần", "Vắng chưa phép", false,
                    "ATTENDANCE", "att-approved-leave-flow", Instant.now());
        }
        jdbc.update("""
                insert into leave_requests
                    (id, student_id, student_name, class_id, class_code, start_date, end_date, reason,
                     status, parent_id, parent_name, parent_confirmed_at, homeroom_teacher_id,
                     homeroom_teacher_name, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, "leave-approved-attendance-flow", "u-student-1", "Phạm Hoài An",
                "c-10a1", "10A1", date, date, "Nghỉ khám sức khỏe",
                "PENDING_HOMEROOM", "u-parent-1", "Phạm Văn Phúc", Instant.now(),
                "u-teacher-1", "Trần Thị Hoa", Instant.now(), Instant.now());

        mvc.perform(post("/leave-requests/leave-approved-attendance-flow/approve")
                        .header("Authorization", "Bearer " + teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Đã kiểm tra và duyệt đơn\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        assertEquals("ABSENT_EXCUSED", jdbc.queryForObject(
                "select status from attendance_records where id = ?",
                String.class, "att-approved-leave-flow"));
        assertEquals("Đơn xin nghỉ đã được GVCN duyệt", jdbc.queryForObject(
                "select note from attendance_records where id = ?",
                String.class, "att-approved-leave-flow"));
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from notifications where ref_type = 'ATTENDANCE' and ref_id = ?",
                Integer.class, "att-approved-leave-flow"));

        mvc.perform(post("/attendance/bulk")
                        .header("Authorization", "Bearer " + teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slotId":"tt-1","date":"2026-08-17","marks":[
                                  {"studentId":"u-student-1","status":"PRESENT"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("PRESENT"));

        mvc.perform(post("/attendance/bulk")
                        .header("Authorization", "Bearer " + teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slotId":"tt-1","date":"2026-08-17","marks":[
                                  {"studentId":"u-student-1","status":"ABSENT_UNEXCUSED"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("ABSENT_EXCUSED"))
                .andExpect(jsonPath("$[0].note").value("Đơn xin nghỉ đã được GVCN duyệt"));

        assertEquals(0, jdbc.queryForObject(
                "select count(*) from notifications where ref_type = 'ATTENDANCE' and ref_id = ?",
                Integer.class, "att-approved-leave-flow"));

        mvc.perform(post("/attendance/bulk")
                        .header("Authorization", "Bearer " + teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slotId":"tt-1","date":"2026-08-17","marks":[
                                  {"studentId":"u-student-1","status":"LATE"}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("ghi chú")));
    }

    @Test
    void eachFamilyRoleCanUsePersonalReportsAndProfileSettings() throws Exception {
        for (String token : new String[]{
                login("gv.hoa", "teacher@123"),
                login("hs.an", "student@123"),
                login("ph.pham", "parent@123")}) {
            mvc.perform(get("/me/reports").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.studentCount").isNumber())
                    .andExpect(jsonPath("$.subjectAverages").isMap());
            mvc.perform(get("/me/reports/export").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("text/csv")));
            mvc.perform(put("/me/profile")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"phone\":\"0900000099\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.phone").value("0900000099"));
            mvc.perform(get("/notification-preferences").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void scopedFeePeriodPreviewAndBatchGenerationFollowF16() throws Exception {
        String admin = login("admin", "admin@123");
        JsonNode period = body(mvc.perform(post("/fee-periods")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"F16-STUDENT-2026","name":"Đợt thu F16","dueDate":"2026-09-30",
                                 "scopeType":"STUDENTS","studentIds":["u-student-1"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.scopeType").value("STUDENTS"))
                .andReturn().getResponse().getContentAsString());
        String periodId = period.path("id").asText();

        mvc.perform(get("/fee-periods/" + periodId + "/preview")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors[0]").isNotEmpty());

        mvc.perform(post("/fee-periods/" + periodId + "/items")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Học phí F16\",\"amount\":1000000}"))
                .andExpect(status().isOk());
        mvc.perform(post("/fee-periods/" + periodId + "/adjustments")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"studentId":"u-student-1","type":"DISCOUNT","amount":100000,
                                 "reason":"Khuyến học"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("DISCOUNT"));

        mvc.perform(get("/fee-periods/" + periodId + "/preview")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.recipientCount").value(1))
                .andExpect(jsonPath("$.totalAmount").value(900000))
                .andExpect(jsonPath("$.recipients[0].studentId").value("u-student-1"));

        mvc.perform(post("/fee-periods/" + periodId + "/open")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));
        mvc.perform(post("/fee-periods/" + periodId + "/adjustments")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":\"u-student-1\",\"type\":\"EXCLUDE\"}"))
                .andExpect(status().isConflict());

        JsonNode first = body(mvc.perform(post("/fee-periods/" + periodId + "/generate-invoices")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].totalAmount").value(900000))
                .andReturn().getResponse().getContentAsString());
        JsonNode second = body(mvc.perform(post("/fee-periods/" + periodId + "/generate-invoices")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        org.junit.jupiter.api.Assertions.assertEquals(first.get(0).path("id").asText(),
                second.get(0).path("id").asText());
    }

    @Test
    void assignmentQueriesEnforceRoleAndTeacherObjectScope() throws Exception {
        String admin = login("admin", "admin@123");
        String teacher = login("gv.hoa", "teacher@123");
        String parent = login("ph.pham", "parent@123");

        String foreignDraftResponse = mvc.perform(post("/assignments")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"classId":"c-10a1","subjectId":"sj-math",
                                 "title":"F18 scope regression draft","publishNow":false}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString();
        String foreignDraftId = body(foreignDraftResponse).path("id").asText();

        mvc.perform(get("/assignments")
                        .queryParam("classId", "c-10a1")
                        .header("Authorization", "Bearer " + teacher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(org.hamcrest.Matchers.not(hasItem(foreignDraftId))))
                .andExpect(jsonPath("$[*].teacherId").value(everyItem(is("u-teacher-1"))));

        mvc.perform(get("/assignments").header("Authorization", "Bearer " + parent))
                .andExpect(status().isForbidden());
        mvc.perform(get("/me/assignments").header("Authorization", "Bearer " + parent))
                .andExpect(status().isForbidden());
        mvc.perform(get("/me/submissions").header("Authorization", "Bearer " + parent))
                .andExpect(status().isForbidden());

        mvc.perform(get("/children/u-student-1/assignments")
                        .header("Authorization", "Bearer " + parent))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(org.hamcrest.Matchers.not(hasItem(foreignDraftId))));
    }

    @Test
    void invoiceGenerationAndSignedPaymentCallbackAreIdempotent() throws Exception {
        String admin = login("admin", "admin@123");
        mvc.perform(post("/fee-periods/fp-hk1/generate-invoices")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());
        JsonNode afterFirstGeneration = body(mvc.perform(get("/invoices")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        mvc.perform(post("/fee-periods/fp-hk1/generate-invoices")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());
        JsonNode afterSecondGeneration = body(mvc.perform(get("/invoices")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        org.junit.jupiter.api.Assertions.assertEquals(
                afterFirstGeneration.size(), afterSecondGeneration.size());

        String parent = login("ph.pham", "parent@123");
        JsonNode feeNotifications = body(mvc.perform(get("/notifications")
                        .header("Authorization", "Bearer " + parent))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertTrue(feeNotifications.findValuesAsText("type").stream().anyMatch("FEE"::equals));
        JsonNode parentInvoices = body(mvc.perform(get("/invoices")
                        .header("Authorization", "Bearer " + parent))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode invoice = null;
        for (JsonNode item : parentInvoices) {
            if (java.util.Set.of("UNPAID", "PARTIAL", "OVERDUE")
                    .contains(item.path("status").asText())) {
                invoice = item;
                break;
            }
        }
        if (invoice == null) throw new AssertionError("A pending parent invoice is required");

        JsonNode initiated = body(mvc.perform(post("/payments")
                        .header("Authorization", "Bearer " + parent)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"invoiceId\":\"" + invoice.path("id").asText() + "\",\"method\":\"VIETQR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.status").value("PENDING"))
                .andExpect(jsonPath("$.gateway").value("VIETQR"))
                .andExpect(jsonPath("$.qrImageUrl").isString())
                .andExpect(jsonPath("$.transferContent").isString())
                .andReturn().getResponse().getContentAsString());
        String paymentId = initiated.path("payment").path("id").asText();
        mvc.perform(post("/payments/" + paymentId + "/submitted")
                        .header("Authorization", "Bearer " + parent))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gatewayStatus").value("AWAITING_CONFIRMATION"));
        mvc.perform(post("/payments/" + paymentId + "/confirm-vietqr")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bankTransactionRef\":\"BANK-TEST-001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.status").value("SUCCESS"))
                .andExpect(jsonPath("$.payment.receiptCode").value(org.hamcrest.Matchers.startsWith("REC-")))
                .andExpect(jsonPath("$.payment.recordedBy").value("u-admin-1"))
                .andExpect(jsonPath("$.invoice.status").value("PAID"));
        mvc.perform(post("/payments/" + paymentId + "/confirm-vietqr")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.status").value("SUCCESS"))
                .andExpect(jsonPath("$.invoice.paidAmount").value(invoice.path("totalAmount").asLong()));
    }

    @Test
    void adminFinanceWorkflowSupportsDraftEditingPartialCashAndReminders() throws Exception {
        String admin = login("admin", "admin@123");
        JsonNode period = body(mvc.perform(post("/fee-periods")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"FIN-UX-2026","name":"Khoản thu trải nghiệm mới",
                                 "applyToGrades":"K10","dueDate":"2026-08-15"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString());
        String periodId = period.path("id").asText();

        mvc.perform(put("/fee-periods/{id}", periodId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Học phí trải nghiệm mới","applyToGrades":"K10",
                                 "dueDate":"2026-08-20"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Học phí trải nghiệm mới"));

        mvc.perform(post("/fee-periods/{id}/items", periodId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Học phí tháng 8","amount":1200000,"gradeLevel":"K10"}
                                """))
                .andExpect(status().isOk());
        mvc.perform(post("/fee-periods/{id}/open", periodId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));
        mvc.perform(put("/fee-periods/{id}", periodId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Không được sửa\"}"))
                .andExpect(status().isConflict());

        JsonNode generated = body(mvc.perform(post("/fee-periods/{id}/generate-invoices", periodId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertTrue(generated.size() > 0);
        JsonNode invoice = generated.get(0);
        assertEquals("UNPAID", invoice.path("status").asText());
        assertTrue(invoice.path("classId").isTextual());
        assertTrue(invoice.path("classCode").isTextual());
        assertTrue(invoice.path("gradeLevel").isTextual());
        long partialAmount = 200000L;
        mvc.perform(post("/payments/cash")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"invoiceId":"%s","amount":%d,
                                 "payerName":"Nguyễn Văn Hùng","note":"Thu tại văn phòng"}
                                """.formatted(invoice.path("id").asText(), partialAmount)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invoice.status").value("PARTIAL"))
                .andExpect(jsonPath("$.invoice.paidAmount").value(partialAmount))
                .andExpect(jsonPath("$.payment.receiptCode").value(
                        org.hamcrest.Matchers.startsWith("REC-")))
                .andExpect(jsonPath("$.payment.payerName").value("Nguyễn Văn Hùng"))
                .andExpect(jsonPath("$.payment.note").value("Thu tại văn phòng"))
                .andExpect(jsonPath("$.payment.recordedBy").isNotEmpty());

        mvc.perform(get("/invoices/{id}", invoice.path("id").asText())
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payments[0].receiptCode").value(
                        org.hamcrest.Matchers.startsWith("REC-")));

        mvc.perform(get("/finance/overview").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invoiceCount").isNumber())
                .andExpect(jsonPath("$.collectionRate").isNumber());
        mvc.perform(get("/invoices")
                        .queryParam("periodId", periodId)
                        .queryParam("q", invoice.path("code").asText())
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].feePeriodId").value(periodId));

        mvc.perform(get("/finance/classes")
                        .queryParam("periodId", periodId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].classId").isString())
                .andExpect(jsonPath("$[0].collectionRate").isNumber())
                .andExpect(jsonPath("$[0].reminderSentToday").value(false));

        mvc.perform(get("/finance/classes")
                        .queryParam("periodId", periodId)
                        .queryParam("gradeLevel", "K10")
                        .queryParam("status", "INCOMPLETE")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].gradeLevel").value("K10"))
                .andExpect(jsonPath("$[0].completed").value(false));

        mvc.perform(post("/finance/classes/c-10a1/remind-homeroom")
                        .queryParam("periodId", periodId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.classCount").value(1))
                .andExpect(jsonPath("$.recipientCount").value(1));
        mvc.perform(post("/finance/classes/c-10a1/remind-homeroom")
                        .queryParam("periodId", periodId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isConflict());
        mvc.perform(get("/finance/classes")
                        .queryParam("periodId", periodId)
                        .queryParam("classId", "c-10a1")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].reminderSentToday").value(true));

        String homeroomTeacher = login("gv.hoa", "teacher@123");
        mvc.perform(get("/notifications")
                        .header("Authorization", "Bearer " + homeroomTeacher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("FINANCE_TASK_REMINDER"))
                .andExpect(jsonPath("$[0].title").value(org.hamcrest.Matchers.containsString("10A1")));
        mvc.perform(post("/finance/classes/c-10a1/remind-homeroom")
                        .queryParam("periodId", periodId)
                        .header("Authorization", "Bearer " + homeroomTeacher))
                .andExpect(status().isForbidden());
        mvc.perform(get("/finance/classes")
                        .queryParam("periodId", periodId)
                        .header("Authorization", "Bearer " + homeroomTeacher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].classId").value("c-10a1"));
        mvc.perform(get("/invoices")
                        .queryParam("periodId", periodId)
                        .queryParam("classId", "c-10a1")
                        .header("Authorization", "Bearer " + homeroomTeacher))
                .andExpect(status().isOk());
        mvc.perform(get("/invoices")
                        .queryParam("periodId", periodId)
                        .queryParam("classId", "c-10a2")
                        .header("Authorization", "Bearer " + homeroomTeacher))
                .andExpect(status().isForbidden());
        mvc.perform(post("/finance/homeroom/classes/c-10a1/remind")
                        .queryParam("periodId", periodId)
                        .header("Authorization", "Bearer " + homeroomTeacher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invoiceCount").isNumber())
                .andExpect(jsonPath("$.recipientCount").isNumber());
        mvc.perform(post("/finance/classes/c-10a1/notify-completion")
                        .queryParam("periodId", periodId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isBadRequest());
        JsonNode homeroomInvoices = body(mvc.perform(get("/invoices")
                        .queryParam("periodId", periodId)
                        .queryParam("classId", "c-10a1")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        for (JsonNode row : homeroomInvoices) {
            long remaining = row.path("totalAmount").asLong() - row.path("paidAmount").asLong();
            if (remaining <= 0) continue;
            mvc.perform(post("/payments/cash")
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"invoiceId\":\"%s\",\"amount\":%d}"
                                    .formatted(row.path("id").asText(), remaining)))
                    .andExpect(status().isOk());
        }
        mvc.perform(post("/finance/classes/c-10a1/notify-completion")
                        .queryParam("periodId", periodId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());
        mvc.perform(get("/notifications")
                        .header("Authorization", "Bearer " + homeroomTeacher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("FINANCE_CLASS_COMPLETE"))
                .andExpect(jsonPath("$[0].title").value(org.hamcrest.Matchers.containsString("10A1")));

        String invoiceId = invoice.path("id").asText();
        mvc.perform(post("/invoices/{id}/refund", invoiceId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":300000,\"reason\":\"Điều chỉnh khoản thu\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invoice.status").value("PARTIALLY_REFUNDED"))
                .andExpect(jsonPath("$.invoice.refundedAmount").value(300000));
        mvc.perform(post("/payments/cash")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"invoiceId\":\"%s\",\"amount\":1}".formatted(invoiceId)))
                .andExpect(status().isConflict());
        mvc.perform(post("/invoices/{id}/refund", invoiceId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":900000,\"reason\":\"Hoàn phần còn lại\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invoice.status").value("REFUNDED"))
                .andExpect(jsonPath("$.invoice.refundedAmount").value(1200000));
        mvc.perform(get("/invoices/{id}", invoiceId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refunds.length()").value(2));

        mvc.perform(post("/fee-periods/{id}/close", periodId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
        mvc.perform(delete("/fee-periods/{id}", periodId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isConflict());
    }

    @Test
    void temporaryPasswordMustBeChangedAndOldAccessTokenIsRevoked() throws Exception {
        String admin = login("admin", "admin@123");
        mvc.perform(post("/users")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"forced.change","password":"Temporary@123",
                                 "fullName":"Tài khoản đổi mật khẩu","role":"PARENT"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordChangeRequired").value(true));

        String temporaryToken = login("forced.change", "Temporary@123");
        mvc.perform(get("/classes").header("Authorization", "Bearer " + temporaryToken))
                .andExpect(status().isForbidden());
        mvc.perform(put("/me/password")
                        .header("Authorization", "Bearer " + temporaryToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"Temporary@123","newPassword":"Permanent@456"}
                                """))
                .andExpect(status().isOk());
        mvc.perform(get("/me").header("Authorization", "Bearer " + temporaryToken))
                .andExpect(status().isUnauthorized());
        login("forced.change", "Permanent@456");
    }

    @Test
    void teacherGradeWritesUseMainSubjectAndRejectDuplicateBulkStudents() throws Exception {
        String teacher = login("gv.hoa", "teacher@123");

        mvc.perform(get("/me/gradebook-context")
                        .queryParam("classId", "c-10a1")
                        .queryParam("semesterId", "sm-2026-1")
                        .header("Authorization", "Bearer " + teacher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subjectId").value("sj-math"))
                .andExpect(jsonPath("$.subjectName").value("Toán"))
                .andExpect(jsonPath("$.homeroomTeacher").value(true))
                .andExpect(jsonPath("$.canEdit").value(true))
                .andExpect(jsonPath("$.subjects.length()").value(5));

        mvc.perform(get("/grades")
                        .queryParam("classId", "c-10a1")
                        .queryParam("semesterId", "sm-2026-1")
                        .queryParam("subjectId", "sj-phys")
                        .header("Authorization", "Bearer " + teacher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].subjectId").value("sj-phys"));

        mvc.perform(post("/grades")
                        .header("Authorization", "Bearer " + teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"studentId":"u-student-2","subjectId":"sj-eng","semesterId":"sm-2026-1",
                                 "category":"MID","assessmentIndex":1,"score":8.0}
                                """))
                .andExpect(status().isForbidden());

        mvc.perform(post("/grades/bulk")
                        .header("Authorization", "Bearer " + teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"classId":"c-10a1","semesterId":"sm-2026-1","category":"MID","assessmentIndex":1,
                                 "entries":[{"studentId":"u-student-1","score":8.0},{"studentId":"u-student-1","score":8.5}]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminCanAssignAnActiveTeacherAsHomeroomTeacher() throws Exception {
        String admin = login("admin", "admin@123");

        mvc.perform(put("/classes/c-10a1/homeroom-teacher")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"teacherId":"u-teacher-1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.homeroomTeacherId").value("u-teacher-1"))
                .andExpect(jsonPath("$.homeroomTeacherName").value("Nguyễn Đức Minh"))
                .andExpect(jsonPath("$.homeroomAssignedAt").isNotEmpty())
                .andExpect(jsonPath("$.homeroomAssignedBy").value("u-admin-1"));

        mvc.perform(put("/classes/c-10a2/homeroom-teacher")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"teacherId":"u-teacher-1"}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(
                        "Giáo viên đã chủ nhiệm lớp 10A1 trong năm học 2026-2027. "
                                + "Mỗi giáo viên chỉ được chủ nhiệm một lớp trong cùng năm học."));
    }

    @Test
    void loginHistoryAndReportExportAreAvailableToAdmin() throws Exception {
        String admin = login("admin", "admin@123");

        mvc.perform(get("/users/u-admin-1/login-history")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].username").value("admin"))
                .andExpect(jsonPath("$[0].success").value(true));

        mvc.perform(get("/reports/export")
                        .queryParam("type", "attendance")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"));

        mvc.perform(get("/reports/export")
                        .queryParam("type", "grades")
                        .queryParam("format", "xlsx")
                        .queryParam("classId", "c-10a1")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.matchesPattern("attachment; filename=bao-cao-grades-.*\\.xlsx")))
                .andExpect(header().exists("X-Report-As-Of"))
                .andExpect(result -> assertTrue(result.getResponse().getContentAsByteArray().length > 1000))
                .andExpect(result -> assertEquals((byte) 'P', result.getResponse().getContentAsByteArray()[0]));

        mvc.perform(get("/reports/export")
                        .queryParam("type", "attendance")
                        .queryParam("format", "pdf")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().exists("X-Report-As-Of"))
                .andExpect(result -> assertTrue(result.getResponse().getContentAsByteArray().length > 500))
                .andExpect(result -> assertEquals((byte) '%', result.getResponse().getContentAsByteArray()[0]));
    }

    @Test
    void adminCanLinkAnotherParentToAStudent() throws Exception {
        String admin = login("admin", "admin@123");
        String response = mvc.perform(post("/users")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"ph.flowchart","password":"Parent@123","fullName":"Phụ huynh Flowchart","role":"PARENT"}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String parentId = body(response).path("id").asText();

        mvc.perform(post("/users/" + parentId + "/children")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"studentId":"u-student-1","primaryContact":false}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("u-student-1"));

        mvc.perform(get("/users/" + parentId + "/children")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("u-student-1"));
    }

    @Test
    void adminCanImportUsersFromExcelWithPerRowResult() throws Exception {
        String admin = login("admin", "admin@123");
        byte[] workbook;
        try (XSSFWorkbook excel = new XSSFWorkbook(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            var sheet = excel.createSheet("Nguoi dung");
            var headerRow = sheet.createRow(0);
            String[] headers = {"Tên đăng nhập", "Họ tên", "Vai trò", "Mật khẩu", "Mã lớp", "Ngày sinh"};
            for (int i = 0; i < headers.length; i++) headerRow.createCell(i).setCellValue(headers[i]);
            var data = sheet.createRow(1);
            data.createCell(0).setCellValue("hs.flowchart");
            data.createCell(1).setCellValue("Học sinh Flowchart");
            data.createCell(2).setCellValue("Học sinh");
            data.createCell(3).setCellValue("Student@123");
            data.createCell(4).setCellValue("10A1");
            data.createCell(5).setCellValue("10/10/2010");
            excel.write(bytes);
            workbook = bytes.toByteArray();
        }
        MockMultipartFile file = new MockMultipartFile("file", "nguoi-dung.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook);

        mvc.perform(multipart("/users/import").file(file)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRows").value(1))
                .andExpect(jsonPath("$.importedRows").value(1))
                .andExpect(jsonPath("$.failedRows").value(0));
    }

    @Test
    void subjectCoefficientAndYearEndPreviewFollowTheFlowchart() throws Exception {
        String admin = login("admin", "admin@123");
        mvc.perform(put("/subjects/sj-math")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Toán","coefficient":2.0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coefficient").value(2.0));

        mvc.perform(get("/academic-years/ay-2026/promotion-preview")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].promotionStatus").value("INCOMPLETE"));
    }

    @Test
    void yearEndSummaryAndConductAreScopedToHomeroomStudentAndParent() throws Exception {
        String admin = login("admin", "admin@123");
        String homeroomTeacher = login("gv.hoa", "teacher@123");
        String otherTeacher = login("gv.minh", "teacher@123");
        String student = login("hs.an", "student@123");
        String parent = login("ph.pham", "parent@123");

        mvc.perform(get("/academic-years/ay-2026/homeroom-summaries")
                        .header("Authorization", "Bearer " + homeroomTeacher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].studentId", hasItem("u-student-1")));
        mvc.perform(get("/academic-years/ay-2026/homeroom-summaries")
                        .header("Authorization", "Bearer " + student))
                .andExpect(status().isForbidden());

        mvc.perform(put("/academic-years/ay-2026/students/u-student-1/conduct")
                        .header("Authorization", "Bearer " + homeroomTeacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conductGrade\":\"GOOD\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conductGrade").value("GOOD"));
        mvc.perform(put("/academic-years/ay-2026/students/u-student-1/conduct")
                        .header("Authorization", "Bearer " + otherTeacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conductGrade\":\"FAIR\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/academic-years/ay-2026/students/u-student-1/conduct")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conductGrade\":\"FAIR\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(get("/academic-years/ay-2026/my-summary")
                        .header("Authorization", "Bearer " + student))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.studentId").value("u-student-1"))
                .andExpect(jsonPath("$.conductGrade").value("GOOD"));
        mvc.perform(get("/academic-years/ay-2026/my-summary")
                        .header("Authorization", "Bearer " + parent))
                .andExpect(status().isForbidden());

        mvc.perform(get("/academic-years/ay-2026/children/u-student-1/summary")
                        .header("Authorization", "Bearer " + parent))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.studentId").value("u-student-1"));
        mvc.perform(get("/academic-years/ay-2026/children/u-admin-1/summary")
                        .header("Authorization", "Bearer " + parent))
                .andExpect(status().isForbidden());
    }

    @Test
    void academicYearRolloverRequiresReadinessAndSupportsRecurringClassCodes() throws Exception {
        String admin = login("admin", "admin@123");
        String teacher = login("gv.hoa", "teacher@123");

        mvc.perform(get("/academic-years/ay-2026/rollover-preview")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.studentCount").isNumber())
                .andExpect(jsonPath("$.semesterCount").value(2))
                .andExpect(jsonPath("$.incompleteCount").isNumber())
                .andExpect(jsonPath("$.blockers").isNotEmpty())
                .andExpect(jsonPath("$.classPlan").isArray());

        mvc.perform(get("/academic-years/ay-2026/rollover-preview")
                        .header("Authorization", "Bearer " + teacher))
                .andExpect(status().isForbidden());

        mvc.perform(put("/academicYears/ay-2026/status")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CLOSED\"}"))
                .andExpect(status().isConflict());

        mvc.perform(post("/academicYears")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"ay-rollover-next","code":"2027-2028","name":"Năm học 2027-2028",
                                 "startDate":"2027-08-16","endDate":"2028-05-31"}
                                """))
                .andExpect(status().isOk());
        mvc.perform(get("/semesters")
                        .queryParam("academicYearId", "ay-rollover-next")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].code").value("HK1"))
                .andExpect(jsonPath("$[1].code").value("HK2"));
        mvc.perform(post("/classes")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"c-rollover-10a1","code":"10A1","name":"Lớp 10A1","gradeLevel":"K10",
                                 "academicYearId":"ay-rollover-next","capacity":45}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("10A1"));

        mvc.perform(get("/academic-years/ay-rollover-next/rollover-preview")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.semesterCount").value(2))
                .andExpect(jsonPath("$.blockers", everyItem(
                        org.hamcrest.Matchers.not("Năm học chưa cấu hình học kỳ II"))));

        mvc.perform(put("/academicYears/ay-rollover-next/status")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isConflict());

        mvc.perform(post("/academic-years/ay-2026/rollover")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nextYearCode":"2027-2028","nextYearName":"Năm học 2027-2028",
                                 "startDate":"2027-09-05","endDate":"2028-05-31",
                                 "createIntakeClasses":true,"activateNextYear":true}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void notificationPreferencesArePersistedPerUser() throws Exception {
        String student = login("hs.an", "student@123");
        mvc.perform(get("/notification-preferences")
                        .header("Authorization", "Bearer " + student))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1].channel").value("IN_APP"))
                .andExpect(jsonPath("$[1].enabled").value(true));

        mvc.perform(put("/notification-preferences")
                        .header("Authorization", "Bearer " + student)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"channel":"EMAIL","enabled":false}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void roomCanServeMorningAndAfternoonButCannotBeAssignedTwiceInSameShift() throws Exception {
        String admin = login("admin", "admin@123");

        mvc.perform(post("/rooms")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"rm-shift-test","code":"SHIFT-TEST","name":"Phòng kiểm thử ca",
                                 "capacity":60,"supportsMorning":true,"supportsAfternoon":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supportsMorning").value(true))
                .andExpect(jsonPath("$.supportsAfternoon").value(true));

        mvc.perform(post("/classes")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"c-shift-morning-1","code":"10S1","name":"Lớp ca sáng 1","gradeLevel":"K10",
                                 "academicYearId":"ay-2026","studyShift":"MORNING","capacity":45,"roomId":"rm-shift-test"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomCode").value("SHIFT-TEST"));

        mvc.perform(post("/classes")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"c-shift-morning-2","code":"10S2","name":"Lớp ca sáng 2","gradeLevel":"K10",
                                 "academicYearId":"ay-2026","studyShift":"MORNING","capacity":45,"roomId":"rm-shift-test"}
                                """))
                .andExpect(status().isConflict());

        mvc.perform(post("/classes")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"c-shift-afternoon","code":"10C1","name":"Lớp ca chiều","gradeLevel":"K10",
                                 "academicYearId":"ay-2026","studyShift":"AFTERNOON","capacity":45,"roomId":"rm-shift-test"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomCode").value("SHIFT-TEST"));
    }

    @Test
    void adminCanSendCategorizedAnnouncementToOneRoleOnly() throws Exception {
        String admin = login("admin", "admin@123");
        String student = login("hs.an", "student@123");
        String parent = login("ph.pham", "parent@123");
        String teacher = login("gv.hoa", "teacher@123");

        mvc.perform(get("/admin/announcements/audience-counts")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.STUDENT").value(greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.TEACHER").value(greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.PARENT").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.ALL").value(greaterThanOrEqualTo(5)));

        mvc.perform(post("/announcements")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"audience":"STUDENT","category":"EVENT","priority":"IMPORTANT",
                                 "title":"Sự kiện kiểm thử","body":"Học sinh tham gia sự kiện kiểm thử."}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.audience").value("STUDENT"))
                .andExpect(jsonPath("$.category").value("EVENT"))
                .andExpect(jsonPath("$.priority").value("IMPORTANT"))
                .andExpect(jsonPath("$.status").value("SENT"))
                .andExpect(jsonPath("$.recipientCount").value(greaterThanOrEqualTo(2)));

        mvc.perform(get("/notifications")
                        .header("Authorization", "Bearer " + student))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Sự kiện kiểm thử"))
                .andExpect(jsonPath("$[0].type").value("EVENT"))
                .andExpect(jsonPath("$[0].priority").value("IMPORTANT"));

        JsonNode parentInbox = body(mvc.perform(get("/notifications")
                        .header("Authorization", "Bearer " + parent))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertTrue(parentInbox.findValuesAsText("title").stream()
                .noneMatch("Sự kiện kiểm thử"::equals));

        mvc.perform(post("/announcements")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"audience":"TEACHER","category":"GENERAL","priority":"URGENT",
                                 "title":"Thông báo dành cho giáo viên","body":"Nội dung điều hành từ Ban quản trị."}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipientCount").value(2));

        mvc.perform(get("/notifications")
                        .header("Authorization", "Bearer " + teacher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Thông báo dành cho giáo viên"))
                .andExpect(jsonPath("$[0].priority").value("URGENT"))
                .andExpect(jsonPath("$[0].refType").value("ANNOUNCEMENT"));

        mvc.perform(get("/admin/announcements")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Thông báo dành cho giáo viên"));

        mvc.perform(post("/announcements")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"audience":"STUDENT","category":"GRADE","priority":"NORMAL",
                                 "title":"Sai vai trò","body":"Admin không gửi thông báo điểm."}
                                """))
                .andExpect(status().isForbidden());

        mvc.perform(get("/teacher/announcements/scopes")
                        .header("Authorization", "Bearer " + teacher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].classId").exists())
                .andExpect(jsonPath("$[0].studentCount").isNumber())
                .andExpect(jsonPath("$[0].parentCount").isNumber());

        mvc.perform(post("/announcements")
                        .header("Authorization", "Bearer " + teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"audience":"CLASS_ALL:c-10a1","category":"GRADE","priority":"IMPORTANT",
                                 "title":"Điểm lớp 10A1","body":"Điểm môn học đã được cập nhật."}
                                """))
                .andExpect(status().isForbidden());

        mvc.perform(post("/announcements")
                        .header("Authorization", "Bearer " + teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"audience":"CLASS_ALL:c-10a1","category":"STUDENT_STATUS","priority":"IMPORTANT",
                                 "title":"Tình hình lớp 10A1","body":"Giáo viên cập nhật tình hình học tập và nề nếp."}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.audience").value("CLASS_ALL:c-10a1"))
                .andExpect(jsonPath("$.category").value("STUDENT_STATUS"));

        mvc.perform(get("/teacher/announcements")
                        .header("Authorization", "Bearer " + teacher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Tình hình lớp 10A1"));

        mvc.perform(get("/notifications")
                        .header("Authorization", "Bearer " + parent))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Tình hình lớp 10A1"));

        mvc.perform(post("/announcements")
                        .header("Authorization", "Bearer " + teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"audience":"CLASS_PARENTS:c-10a2","category":"ATTENDANCE","priority":"NORMAL",
                                 "title":"Ngoài phạm vi","body":"Không được gửi tới lớp không phụ trách."}
                                """))
                .andExpect(status().isForbidden());

        mvc.perform(post("/announcements")
                        .header("Authorization", "Bearer " + teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"audience":"TEACHER","category":"GENERAL","priority":"NORMAL",
                                 "title":"Không hợp lệ","body":"Giáo viên không được gửi theo vai trò."}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void announcementPreviewAndIdempotencyPreventDuplicateDelivery() throws Exception {
        String teacher = login("gv.hoa", "teacher@123");

        mvc.perform(post("/announcements/preview")
                        .header("Authorization", "Bearer " + teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"audience":"CLASS_ALL:c-10a1","category":"STUDENT_STATUS"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.audience").value("CLASS_ALL:c-10a1"))
                .andExpect(jsonPath("$.recipientCount").value(greaterThanOrEqualTo(2)));

        String request = """
                {"audience":"CLASS_ALL:c-10a1","category":"STUDENT_STATUS","priority":"IMPORTANT",
                 "title":"Thông báo chống gửi trùng","body":"Nội dung chỉ được phát một lần.",
                 "idempotencyKey":"f12-integration-idempotency"}
                """;
        JsonNode first = body(mvc.perform(post("/announcements")
                        .header("Authorization", "Bearer " + teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        String announcementId = first.path("id").asText();
        int deliveredAfterFirst = jdbc.queryForObject(
                "select count(*) from notifications where ref_type = 'ANNOUNCEMENT' and ref_id = ?",
                Integer.class, announcementId);

        mvc.perform(post("/announcements")
                        .header("Authorization", "Bearer " + teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(announcementId));
        assertEquals(deliveredAfterFirst, jdbc.queryForObject(
                "select count(*) from notifications where ref_type = 'ANNOUNCEMENT' and ref_id = ?",
                Integer.class, announcementId));

        mvc.perform(post("/announcements")
                        .header("Authorization", "Bearer " + teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request.replace("Nội dung chỉ được phát một lần.", "Nội dung khác.")))
                .andExpect(status().isConflict());
    }

    @Test
    void chatAndTimetableQueriesAreRestrictedToTheUsersSchoolScope() throws Exception {
        String student = login("hs.an", "student@123");
        mvc.perform(post("/chat/messages")
                        .header("Authorization", "Bearer " + student)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"toUserId":"u-admin-1","body":"Tin nhắn ngoài phạm vi"}
                                """))
                .andExpect(status().isForbidden());

        mvc.perform(get("/timetableSlots")
                        .queryParam("classId", "c-8a1")
                        .header("Authorization", "Bearer " + student))
                .andExpect(status().isForbidden());
    }

    @Test
    void studentAndAssignedTeacherCanChatAndOpeningConversationMarksMessagesRead() throws Exception {
        String student = login("hs.an", "student@123");
        String teacher = login("gv.hoa", "teacher@123");

        mvc.perform(post("/chat/messages")
                        .header("Authorization", "Bearer " + student)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"toUserId":"u-teacher-1","body":"   Cô cho em hỏi bài tập hôm nay ạ.   "}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value("Cô cho em hỏi bài tập hôm nay ạ."))
                .andExpect(jsonPath("$.readFlag").value(false));

        JsonNode unreadThreads = body(mvc.perform(get("/chat/threads")
                        .header("Authorization", "Bearer " + teacher))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertTrue(findThread(unreadThreads, "u-student-1").path("unread").asInt() > 0);
        mvc.perform(get("/chat/unread-count")
                        .header("Authorization", "Bearer " + teacher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(greaterThanOrEqualTo(1)));

        mvc.perform(get("/chat/messages")
                        .queryParam("withUserId", "u-student-1")
                        .header("Authorization", "Bearer " + teacher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].senderId").exists())
                .andExpect(jsonPath("$[0].readFlag").value(true))
                .andExpect(jsonPath("$[0].readAt").exists());

        JsonNode readThreads = body(mvc.perform(get("/chat/threads")
                        .header("Authorization", "Bearer " + teacher))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertTrue(findThread(readThreads, "u-student-1").path("unread").asInt() == 0);
        mvc.perform(get("/chat/unread-count")
                        .header("Authorization", "Bearer " + teacher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").isNumber());

        mvc.perform(post("/chat/messages")
                        .header("Authorization", "Bearer " + student)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of(
                                "toUserId", "u-teacher-1",
                                "body", "a".repeat(2001)))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void chatContactsFollowHomeroomAndSubjectTeacherCommunicationRules() throws Exception {
        String admin = login("admin", "admin@123");
        String student = login("hs.an", "student@123");
        String otherStudent = login("hs.binh", "student@123");
        String parent = login("ph.pham", "parent@123");
        String homeroomTeacher = login("gv.hoa", "teacher@123");
        String subjectTeacher = login("gv.minh", "teacher@123");

        mvc.perform(post("/chat/messages").header("Authorization", "Bearer " + student)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toUserId\":\"u-teacher-2\",\"body\":\"Em hỏi bài ạ\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/chat/messages").header("Authorization", "Bearer " + student)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toUserId\":\"u-parent-1\",\"body\":\"Không hợp lệ\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/chat/messages").header("Authorization", "Bearer " + student)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toUserId\":\"u-student-2\",\"body\":\"Khác lớp\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(put("/users/u-student-2").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"classId\":\"c-10a1\",\"className\":\"10A1\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/chat/messages").header("Authorization", "Bearer " + student)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toUserId\":\"u-student-2\",\"body\":\"Chào bạn cùng lớp\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/chat/messages").header("Authorization", "Bearer " + otherStudent)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toUserId\":\"u-student-1\",\"body\":\"Chào bạn\"}"))
                .andExpect(status().isOk());
        mvc.perform(put("/users/u-student-2").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"classId\":\"c-8a1\",\"className\":\"8A1\"}"))
                .andExpect(status().isOk());

        mvc.perform(post("/chat/messages").header("Authorization", "Bearer " + parent)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toUserId\":\"u-teacher-1\",\"body\":\"Trao đổi với GVCN\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/chat/messages").header("Authorization", "Bearer " + parent)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toUserId\":\"u-teacher-2\",\"body\":\"Trao đổi với giáo viên bộ môn\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/chat/contacts").header("Authorization", "Bearer " + parent))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(hasItem("u-teacher-1")))
                .andExpect(jsonPath("$[*].id").value(hasItem("u-teacher-2")))
                .andExpect(jsonPath("$[*].id").value(org.hamcrest.Matchers.not(hasItem("u-admin-1"))));

        mvc.perform(post("/chat/messages").header("Authorization", "Bearer " + homeroomTeacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toUserId\":\"u-parent-1\",\"body\":\"Trao đổi tình hình học tập\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/chat/messages").header("Authorization", "Bearer " + homeroomTeacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toUserId\":\"u-teacher-2\",\"body\":\"Phản hồi giáo viên bộ môn\"}"))
                .andExpect(status().isOk());

        mvc.perform(post("/chat/messages").header("Authorization", "Bearer " + subjectTeacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toUserId\":\"u-student-1\",\"body\":\"Trao đổi môn học\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/chat/messages").header("Authorization", "Bearer " + subjectTeacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toUserId\":\"u-parent-1\",\"body\":\"Trao đổi với phụ huynh lớp đang dạy\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/chat/messages").header("Authorization", "Bearer " + subjectTeacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toUserId\":\"u-admin-1\",\"body\":\"Ngoài quan hệ giảng dạy\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/chat/messages").header("Authorization", "Bearer " + subjectTeacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toUserId\":\"u-teacher-1\",\"body\":\"Trao đổi với GVCN lớp 10A1\"}"))
                .andExpect(status().isOk());
    }

    private JsonNode findThread(JsonNode threads, String userId) {
        for (JsonNode thread : threads) {
            if (userId.equals(thread.path("userId").asText())) return thread;
        }
        throw new AssertionError("Không tìm thấy hội thoại với " + userId);
    }

    @Test
    void teachingAssignmentControlsTimetableCapacityAndTeacherConflicts() throws Exception {
        String admin = login("admin", "admin@123");
        String teacher = login("gv.hoa", "teacher@123");

        String batchPayload = """
                {"assignments":[{"classId":"c-10a2","weeklyPeriods":2},
                                  {"classId":"c-8a1","weeklyPeriods":3}],
                 "subjectId":"sj-bio","teacherId":"u-teacher-2","semesterId":"sm-2026-2"}
                """;
        mvc.perform(post("/teaching-assignments/batch")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].classId").value("c-10a2"))
                .andExpect(jsonPath("$[0].weeklyPeriods").value(2))
                .andExpect(jsonPath("$[1].classId").value("c-8a1"))
                .andExpect(jsonPath("$[1].weeklyPeriods").value(3));

        mvc.perform(post("/teaching-assignments/batch")
                        .header("Authorization", "Bearer " + teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchPayload))
                .andExpect(status().isForbidden());

        mvc.perform(post("/teaching-assignments/batch")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchPayload))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error")
                        .value(org.hamcrest.Matchers.containsString("10A2")));

        mvc.perform(get("/teaching-assignments")
                        .queryParam("subjectId", "sj-bio")
                        .queryParam("semesterId", "sm-2026-2")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].teacherId").value("u-teacher-2"))
                .andExpect(jsonPath("$[1].teacherId").value("u-teacher-2"));

        String assignmentPayload = """
                {"classId":"c-10a2","subjectId":"sj-math","teacherId":"u-teacher-1",
                 "semesterId":"sm-2026-2","weeklyPeriods":1}
                """;
        JsonNode assignment = body(mvc.perform(post("/teaching-assignments")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignmentPayload))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        mvc.perform(post("/teaching-assignments")
                        .header("Authorization", "Bearer " + teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignmentPayload))
                .andExpect(status().isForbidden());

        String firstSlot = """
                {"classId":"c-10a2","subjectId":"sj-math","teacherId":"u-teacher-1",
                 "roomCode":"P201","dayOfWeek":"MON","periodNo":1,
                 "startTime":"07:00","endTime":"07:45","semesterId":"sm-2026-2"}
                """;
        mvc.perform(post("/timetableSlots")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(firstSlot))
                .andExpect(status().isOk());

        String crossSpecialtyAssignment = """
                {"classId":"c-8a1","subjectId":"sj-eng","teacherId":"u-teacher-2",
                 "semesterId":"sm-2026-2","weeklyPeriods":2}
                """;
        mvc.perform(post("/teaching-assignments")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(crossSpecialtyAssignment))
                .andExpect(status().isOk());

        String conflictingAssignment = """
                {"classId":"c-8a1","subjectId":"sj-math","teacherId":"u-teacher-1",
                 "semesterId":"sm-2026-2","weeklyPeriods":1}
                """;
        mvc.perform(post("/teaching-assignments")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(conflictingAssignment))
                .andExpect(status().isOk());

        JsonNode workloads = body(mvc.perform(get("/teaching-assignments/workloads")
                        .queryParam("semesterId", "sm-2026-2")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        JsonNode hoaWorkload = null;
        for (JsonNode item : workloads) {
            if ("u-teacher-1".equals(item.path("teacherId").asText())) hoaWorkload = item;
        }
        assertTrue(hoaWorkload != null && hoaWorkload.path("classCount").asInt() == 2);
        assertTrue(hoaWorkload.path("classCodes").toString().contains("10A2"));
        assertTrue(hoaWorkload.path("classCodes").toString().contains("8A1"));

        mvc.perform(get("/teaching-assignments")
                        .queryParam("classId", "c-8a1")
                        .queryParam("subjectId", "sj-math")
                        .queryParam("semesterId", "sm-2026-2")
                        .queryParam("dayOfWeek", "MON")
                        .queryParam("periodNo", "1")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].teacherClassCount").value(2))
                .andExpect(jsonPath("$[0].teacherWeeklyPeriods").value(2))
                .andExpect(jsonPath("$[0].teacherScheduledPeriods").value(1))
                .andExpect(jsonPath("$[0].teacherBusy").value(true))
                .andExpect(jsonPath("$[0].canSchedule").value(false))
                .andExpect(jsonPath("$[0].availabilityMessage")
                        .value(org.hamcrest.Matchers.containsString("đang dạy")));

        mvc.perform(get("/teaching-assignments")
                        .queryParam("classId", "c-8a1")
                        .queryParam("subjectId", "sj-math")
                        .queryParam("semesterId", "sm-2026-2")
                        .queryParam("dayOfWeek", "MON")
                        .queryParam("periodNo", "1")
                        .queryParam("startTime", "13:00")
                        .queryParam("endTime", "13:45")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].teacherBusy").value(false))
                .andExpect(jsonPath("$[0].canSchedule").value(true));

        String teacherConflictSlot = """
                {"classId":"c-8a1","subjectId":"sj-math","teacherId":"u-teacher-1",
                 "roomCode":"P105","dayOfWeek":"MON","periodNo":1,
                 "startTime":"07:00","endTime":"07:45","semesterId":"sm-2026-2"}
                """;
        mvc.perform(post("/timetableSlots")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(teacherConflictSlot))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("kín lịch")));

        String afternoonSlotWithSamePeriodNumber = """
                {"classId":"c-8a1","subjectId":"sj-math","teacherId":"u-teacher-1",
                 "roomCode":"P105","dayOfWeek":"MON","periodNo":1,
                 "startTime":"13:00","endTime":"13:45","semesterId":"sm-2026-2"}
                """;
        mvc.perform(post("/timetableSlots")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(afternoonSlotWithSamePeriodNumber))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodNo").value(1))
                .andExpect(jsonPath("$.startTime").value("13:00"));

        String extraSlot = """
                {"classId":"c-10a2","subjectId":"sj-math","teacherId":"u-teacher-1",
                 "roomCode":"P201","dayOfWeek":"TUE","periodNo":2,
                 "startTime":"07:50","endTime":"08:35","semesterId":"sm-2026-2"}
                """;
        mvc.perform(post("/timetableSlots")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(extraSlot))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("đã xếp đủ")));

        mvc.perform(get("/teaching-assignments")
                        .queryParam("classId", "c-10a2")
                        .queryParam("subjectId", "sj-math")
                        .queryParam("semesterId", "sm-2026-2")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(assignment.path("id").asText()))
                .andExpect(jsonPath("$[0].scheduledPeriods").value(1))
                .andExpect(jsonPath("$[0].fullyScheduled").value(true))
                .andExpect(jsonPath("$[0].canSchedule").value(false));

        mvc.perform(delete("/teaching-assignments/{id}", assignment.path("id").asText())
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isConflict());

        String missingAssignmentSlot = """
                {"classId":"c-10a2","subjectId":"sj-phys","teacherId":"u-teacher-2",
                 "roomCode":"LAB1","dayOfWeek":"WED","periodNo":3,
                 "startTime":"08:45","endTime":"09:30","semesterId":"sm-2026-2"}
                """;
        mvc.perform(post("/timetableSlots")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(missingAssignmentSlot))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Chưa phân công")));
    }

    @Test
    void examinationAdministrationAndStudentDocumentsRespectRoleScope() throws Exception {
        String admin = login("admin", "admin@123");
        String homeroomTeacher = login("gv.hoa", "teacher@123");
        String subjectTeacher = login("gv.minh", "teacher@123");
        String student = login("hs.an", "student@123");
        String parent = login("ph.pham", "parent@123");

        mvc.perform(get("/exam-periods").header("Authorization", "Bearer " + homeroomTeacher))
                .andExpect(status().isForbidden());
        mvc.perform(get("/reports/grade-distribution").header("Authorization", "Bearer " + homeroomTeacher))
                .andExpect(status().isForbidden());
        mvc.perform(get("/me/exam-agenda").header("Authorization", "Bearer " + homeroomTeacher))
                .andExpect(status().isOk());

        mvc.perform(get("/exam-reports/report-card")
                        .queryParam("academicYearId", "ay-2026")
                        .queryParam("studentId", "u-student-1")
                        .header("Authorization", "Bearer " + student))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("application/pdf")));
        mvc.perform(get("/exam-reports/report-card")
                        .queryParam("academicYearId", "ay-2026")
                        .queryParam("studentId", "u-student-1")
                        .header("Authorization", "Bearer " + parent))
                .andExpect(status().isOk());
        mvc.perform(get("/exam-reports/report-card")
                        .queryParam("academicYearId", "ay-2026")
                        .queryParam("studentId", "u-student-1")
                        .header("Authorization", "Bearer " + homeroomTeacher))
                .andExpect(status().isOk());
        mvc.perform(get("/exam-reports/report-card")
                        .queryParam("academicYearId", "ay-2026")
                        .queryParam("studentId", "u-student-1")
                        .header("Authorization", "Bearer " + subjectTeacher))
                .andExpect(status().isForbidden());

        JsonNode period = body(mvc.perform(post("/exam-periods")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"SEC-EXAM-2026","name":"Kỳ thi kiểm thử phân quyền",
                                 "academicYearId":"ay-2026","semesterId":"sm-2026-1","gradeLevel":"K10",
                                 "startDate":"2025-11-10","endDate":"2025-11-12"}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        String periodId = period.path("id").asText();
        mvc.perform(delete("/exam-periods/{id}", periodId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());
    }

    @Test
    void backendPaginationGlobalSearchAndSafeImportWorkTogether() throws Exception {
        String admin = login("admin", "admin@123");

        mvc.perform(get("/users/page")
                        .queryParam("role", "STUDENT")
                        .queryParam("page", "0")
                        .queryParam("size", "5")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.totalElements").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.summary.active").value(greaterThanOrEqualTo(1)));

        mvc.perform(get("/search")
                        .queryParam("q", "10A1")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.items[*].pageId").value(hasItem("A2")));

        mvc.perform(get("/audit-logs/page")
                        .queryParam("page", "0")
                        .queryParam("size", "5")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.totalElements").value(greaterThanOrEqualTo(1)));

        mvc.perform(get("/notifications/page")
                        .queryParam("read", "ALL")
                        .queryParam("page", "0")
                        .queryParam("size", "5")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.summary.unread").value(greaterThanOrEqualTo(0)));

        mvc.perform(get("/invoices/page")
                        .queryParam("page", "0")
                        .queryParam("size", "5")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.size").value(5));

        mvc.perform(post("/chat/messages")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toUserId\":\"u-student-1\",\"body\":\"Kiểm thử phân trang hội thoại\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/chat/messages/page")
                        .queryParam("withUserId", "u-student-1")
                        .queryParam("page", "0")
                        .queryParam("size", "1")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(greaterThanOrEqualTo(1)));

        byte[] workbook = safeImportWorkbook();
        MockMultipartFile previewFile = new MockMultipartFile(
                "file", "safe-import.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook);
        JsonNode preview = body(mvc.perform(multipart("/users/import/preview")
                        .file(previewFile)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRows").value(2))
                .andExpect(jsonPath("$.validRows").value(1))
                .andExpect(jsonPath("$.invalidRows").value(1))
                .andReturn().getResponse().getContentAsString());

        MockMultipartFile strictFile = new MockMultipartFile(
                "file", "safe-import.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook);
        mvc.perform(multipart("/users/import/commit")
                        .file(strictFile)
                        .param("token", preview.path("token").asText())
                        .param("strategy", "ALL_OR_NOTHING")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importedRows").value(0))
                .andExpect(jsonPath("$.failedRows").value(1));

        mvc.perform(get("/users")
                        .queryParam("q", "safe.import.student")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        MockMultipartFile skipFile = new MockMultipartFile(
                "file", "safe-import.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook);
        mvc.perform(multipart("/users/import/commit")
                        .file(skipFile)
                        .param("token", preview.path("token").asText())
                        .param("strategy", "SKIP_ERRORS")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importedRows").value(1))
                .andExpect(jsonPath("$.failedRows").value(1));
    }

    @Test
    void removedHolidayEndpointReturnsNotFound() throws Exception {
        String admin = login("admin", "admin@123");

        mvc.perform(get("/school-holidays")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isNotFound());
    }

    @Test
    void clubRegistrationFlowValidatesOwnershipCapacityApprovalInvoiceAndPromotion() throws Exception {
        String admin = login("admin", "admin@123");
        String studentOne = login("hs.an", "student@123");
        String parent = login("ph.pham", "parent@123");

        mvc.perform(post("/clubs")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"club-f13-core","code":"F13-CORE","name":"CLB Robotics",
                                 "description":"Luồng kiểm thử F13","schedule":"Thứ Bảy 08:00",
                                 "capacity":1,"feeAmount":350000,"approvalRequired":false,
                                 "registrationStart":"2020-01-01","registrationEnd":"2030-12-31"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableSlots").value(1));

        JsonNode first = body(mvc.perform(post("/clubs/club-f13-core/registrations")
                        .header("Authorization", "Bearer " + studentOne)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.invoiceId").isString())
                .andReturn().getResponse().getContentAsString());

        mvc.perform(post("/clubs/club-f13-core/registrations")
                        .header("Authorization", "Bearer " + studentOne)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict());

        mvc.perform(post("/clubs/club-f13-core/registrations")
                        .header("Authorization", "Bearer " + parent)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":\"u-teacher-1\"}"))
                .andExpect(status().isForbidden());

        JsonNode waiting = body(mvc.perform(post("/clubs/club-f13-core/registrations")
                        .header("Authorization", "Bearer " + parent)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":\"u-student-2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITLIST"))
                .andExpect(jsonPath("$.waitlistPosition").value(1))
                .andReturn().getResponse().getContentAsString());

        mvc.perform(post("/club-registrations/" + first.path("id").asText() + "/cancel")
                        .header("Authorization", "Bearer " + studentOne)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Đổi lịch học\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        mvc.perform(get("/children/u-student-2/club-registrations")
                        .header("Authorization", "Bearer " + parent))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(waiting.path("id").asText()))
                .andExpect(jsonPath("$[0].status").value("APPROVED"))
                .andExpect(jsonPath("$[0].invoiceId").isString());

        mvc.perform(get("/invoices/" + first.path("invoiceId").asText())
                        .header("Authorization", "Bearer " + parent))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invoice.status").value("CANCELLED"));

        mvc.perform(post("/clubs")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"club-f13-approval","code":"F13-APPROVAL","name":"CLB Tranh biện",
                                 "schedule":"Thứ Tư 16:30","capacity":10,"feeAmount":0,
                                 "approvalRequired":true,"registrationStart":"2020-01-01",
                                 "registrationEnd":"2030-12-31"}
                                """))
                .andExpect(status().isOk());

        JsonNode pending = body(mvc.perform(post("/clubs/club-f13-approval/registrations")
                        .header("Authorization", "Bearer " + studentOne)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString());

        mvc.perform(post("/club-registrations/" + pending.path("id").asText() + "/approve")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Đủ điều kiện\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.invoiceId").value(nullValue()));
    }

    private String login(String username, String password) throws Exception {
        return loginBody(username, password).path("accessToken").asText();
    }

    private JsonNode loginBody(String username, String password) throws Exception {
        Login credentials = currentSeedCredentials(username, password);
        String response = mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(credentials)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return body(response);
    }

    /**
     * Keeps older workflow scenarios readable while authenticating against the
     * canonical demo accounts exposed by the current seed dataset.
     */
    private Login currentSeedCredentials(String username, String password) {
        return switch (username) {
            case "admin" -> new Login("admin", "Admin123@@");
            case "gv.hoa" -> new Login("gv.nguyenminh", "nguyenminh123@");
            case "hs.an" -> new Login("hs.nguyenminhan", "nguyenminhanh123@@");
            case "ph.pham" -> new Login("ph.nguyenvanhung", "nguyenvanhung123@");
            default -> new Login(username, password);
        };
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

    private byte[] safeImportWorkbook() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Nguoi dung");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("Tên đăng nhập");
            header.createCell(1).setCellValue("Họ tên");
            header.createCell(2).setCellValue("Vai trò");
            header.createCell(3).setCellValue("Mật khẩu");
            header.createCell(4).setCellValue("Mã lớp");

            var valid = sheet.createRow(1);
            valid.createCell(0).setCellValue("safe.import.student");
            valid.createCell(1).setCellValue("Học sinh Import An Toàn");
            valid.createCell(2).setCellValue("Học sinh");
            valid.createCell(3).setCellValue("Safe@123456");
            valid.createCell(4).setCellValue("10A1");

            var invalid = sheet.createRow(2);
            invalid.createCell(0).setCellValue("admin");
            invalid.createCell(1).setCellValue("Tài khoản trùng");
            invalid.createCell(2).setCellValue("Quản trị viên");
            invalid.createCell(3).setCellValue("Safe@123456");

            workbook.write(output);
            return output.toByteArray();
        }
    }

    private record Login(String username, String password) {}
    private record Refresh(String refreshToken) {}
    private record GradeUpdate(Double score, String note, String reason, Long expectedVersion) {}
}
