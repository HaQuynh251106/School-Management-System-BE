package com.sse.app;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenApiContractTest {
    private static final Set<String> HTTP_METHODS =
            Set.of("get", "post", "put", "patch", "delete");
    private static final List<String> REQUIRED_OPERATION_METADATA =
            List.of("operationId", "x-roles", "x-object-permission", "x-idempotency", "x-owners");

    @Test
    void contractsAreParseableAndCarryRequiredGovernanceMetadata() throws IOException {
        for (String contract : List.of(
                "finance.yaml", "assignment.yaml", "file.yaml", "chat.yaml", "notification.yaml",
                "club.yaml", "identity.yaml", "academic.yaml", "report.yaml")) {
            assertGovernanceMetadata(loadContract(contract), contract);
        }
    }

    private static void assertGovernanceMetadata(Map<String, Object> document, String contract) {

        assertEquals("3.1.0", document.get("openapi"));
        Map<String, Object> paths = map(document.get("paths"));
        assertFalse(paths.isEmpty());

        paths.forEach((path, rawPathItem) -> map(rawPathItem).forEach((method, rawOperation) -> {
            if (!HTTP_METHODS.contains(method)) return;
            Map<String, Object> operation = map(rawOperation);
            REQUIRED_OPERATION_METADATA.forEach(field ->
                    assertTrue(operation.containsKey(field), path + " " + method + " is missing " + field));
            assertFalse(list(operation.get("x-roles")).isEmpty(), path + " " + method + " has no roles");
            assertFalse(map(operation.get("x-owners")).isEmpty(), path + " " + method + " has no owners");
            assertTrue(map(operation.get("responses")).containsKey("200"),
                    contract + ": " + path + " " + method + " must document a success response");
        }));

        assertNotNull(resolve(document, "components", "schemas", "ApiError"));
        assertNotNull(resolve(document, "components", "securitySchemes", "bearerAuth"));
    }

    @Test
    void financeContractCoversTheCriticalF17Operations() throws IOException {
        Map<String, Object> contract = loadContract("finance.yaml");
        Map<String, Object> paths = map(contract.get("paths"));

        assertOperation(paths, "/invoices", "get", "listInvoices");
        assertOperation(paths, "/payments/cash", "post", "recordCashPayment");
        assertOperation(paths, "/payments/gateway/callback", "post", "processGatewayCallback");
        assertOperation(paths, "/payments/{paymentId}/status", "get", "getPaymentStatus");
        assertOperation(paths, "/payments/{paymentId}/confirm-vietqr", "post", "confirmVietQrPayment");
        assertOperation(paths, "/payments/{paymentId}/reject-vietqr", "post", "rejectVietQrPayment");
        assertOperation(paths, "/invoices/{invoiceId}/refund", "post", "refundInvoice");
        assertOperation(paths, "/invoices/{invoiceId}", "get", "getInvoiceDetail");

        Map<String, Object> status = map(resolve(contract, "components", "schemas",
                "InvoiceStatus"));
        assertEquals(Set.of("UNPAID", "PARTIAL", "PAID", "OVERDUE", "CANCELLED",
                        "PARTIALLY_REFUNDED", "REFUNDED"),
                Set.copyOf(list(status.get("enum"))));
        Map<String, Object> invoiceStatus = map(resolve(contract, "components", "schemas",
                "Invoice", "properties", "status"));
        Map<String, Object> transitions = map(invoiceStatus.get("x-state-transitions"));
        assertEquals(List.of("PARTIAL", "PAID", "OVERDUE", "CANCELLED"),
                list(transitions.get("UNPAID")));
        assertEquals(List.of("REFUNDED"), list(transitions.get("PARTIALLY_REFUNDED")));
        assertTrue(list(transitions.get("REFUNDED")).isEmpty());
        assertTrue(list(transitions.get("CANCELLED")).isEmpty());

        List<Object> vietQrResult = list(resolve(contract, "components", "schemas",
                "VietQrPaymentResult", "allOf"));
        Map<String, Object> vietQrFields = map(map(vietQrResult.get(1)).get("properties"));
        assertTrue(vietQrFields.keySet().containsAll(Set.of(
                "qrImageUrl", "bankId", "accountNo", "accountName", "transferContent")));
    }

    @Test
    void assignmentContractCoversTheCriticalF11Operations() throws IOException {
        Map<String, Object> paths = map(loadContract("assignment.yaml").get("paths"));

        assertOperation(paths, "/assignments", "post", "createAssignment");
        assertOperation(paths, "/assignments/{assignmentId}/publish", "post", "publishAssignment");
        assertOperation(paths, "/assignments/{assignmentId}/submit", "post", "submitAssignment");
        assertOperation(paths, "/submissions/{submissionId}/grade", "post", "gradeSubmission");
        assertOperation(paths, "/submissions/{submissionId}/allow-resubmit", "post",
                "allowAssignmentResubmission");
        assertOperation(paths, "/children/{studentId}/assignments", "get", "listChildAssignments");
    }

    @Test
    void fileContractCoversPrivateUploadMetadataAndDownload() throws IOException {
        Map<String, Object> paths = map(loadContract("file.yaml").get("paths"));

        assertOperation(paths, "/files", "post", "uploadPrivateFile");
        assertOperation(paths, "/files/{fileId}", "get", "getPrivateFileMetadata");
        assertOperation(paths, "/files/{fileId}/content", "get", "downloadPrivateFile");
    }

    @Test
    void chatContractCoversContactsMessagesUnreadAndRealtime() throws IOException {
        Map<String, Object> paths = map(loadContract("chat.yaml").get("paths"));

        assertOperation(paths, "/chat/contacts", "get", "listChatContacts");
        assertOperation(paths, "/chat/messages", "post", "sendChatMessage");
        assertOperation(paths, "/chat/messages", "get", "listChatMessages");
        assertOperation(paths, "/chat/unread-count", "get", "getChatUnreadCount");
        assertOperation(paths, "/realtime/events", "get", "subscribeRealtimeEvents");
    }

    @Test
    void notificationContractCoversInboxPreviewAndIdempotentAnnouncement() throws IOException {
        Map<String, Object> paths = map(loadContract("notification.yaml").get("paths"));

        assertOperation(paths, "/notifications", "get", "listNotifications");
        assertOperation(paths, "/notifications/{notificationId}/read", "post", "markNotificationRead");
        assertOperation(paths, "/announcements/preview", "post", "previewAnnouncementAudience");
        assertOperation(paths, "/announcements", "post", "sendAnnouncement");
        assertOperation(paths, "/teacher/announcements/scopes", "get", "listTeacherAnnouncementScopes");
    }

    @Test
    void clubContractCoversCreationRegistrationApprovalWaitlistAndCancellation() throws IOException {
        Map<String, Object> paths = map(loadContract("club.yaml").get("paths"));

        assertOperation(paths, "/clubs", "post", "createClub");
        assertOperation(paths, "/clubs/{clubId}/registrations", "post", "registerClub");
        assertOperation(paths, "/children/{studentId}/club-registrations", "get",
                "listChildClubRegistrations");
        assertOperation(paths, "/admin/club-registrations", "get",
                "listAdminClubRegistrations");
        assertOperation(paths, "/club-registrations/{registrationId}/approve", "post",
                "approveClubRegistration");
        assertOperation(paths, "/club-registrations/{registrationId}/cancel", "post",
                "cancelClubRegistration");
    }

    @Test
    void identityContractCoversCoreMobileAuthenticationAndProfile() throws IOException {
        Map<String, Object> paths = map(loadContract("identity.yaml").get("paths"));

        assertOperation(paths, "/auth/login", "post", "login");
        assertOperation(paths, "/auth/refresh", "post", "refreshSession");
        assertOperation(paths, "/auth/logout", "post", "logout");
        assertOperation(paths, "/auth/forgot-password", "post", "forgotPassword");
        assertOperation(paths, "/auth/reset-password", "post", "resetPassword");
        assertOperation(paths, "/me", "get", "getCurrentUser");
        assertOperation(paths, "/me/password", "put", "changeMyPassword");
        assertOperation(paths, "/me/profile", "put", "updateMyProfile");
        assertOperation(paths, "/users", "get", "listUsers");
        assertOperation(paths, "/users", "post", "createUser");
        assertOperation(paths, "/users/{id}", "get", "getUser");
        assertOperation(paths, "/users/{id}/lock", "post", "lockUser");
        assertOperation(paths, "/users/{id}/unlock", "post", "unlockUser");
        assertOperation(paths, "/users/{id}/reset-password", "post",
                "adminResetUserPassword");
    }

    @Test
    void academicContractCoversCoreMobileStructureAndTimetableReads() throws IOException {
        Map<String, Object> paths = map(loadContract("academic.yaml").get("paths"));
        assertOperation(paths, "/academicYears", "get", "listAcademicYears");
        assertOperation(paths, "/semesters", "get", "listSemesters");
        assertOperation(paths, "/classes", "get", "listClasses");
        assertOperation(paths, "/subjects", "get", "listSubjects");
        assertOperation(paths, "/rooms", "get", "listRooms");
        assertOperation(paths, "/timetableSlots", "get", "listTimetableSlots");
        assertOperation(paths, "/me/timetable", "get", "getMyTimetable");
        assertOperation(paths, "/classes", "post", "createClass");
        assertOperation(paths, "/classes/{id}", "put", "updateClass");
        assertOperation(paths, "/classes/{id}", "delete", "deleteClass");
        assertOperation(paths, "/subjects", "post", "createSubject");
        assertOperation(paths, "/rooms", "post", "createRoom");
        assertOperation(paths, "/teaching-assignments", "get", "listTeachingAssignments");
        assertOperation(paths, "/teaching-assignments", "post", "createTeachingAssignment");
        assertOperation(paths, "/timetableSlots", "post", "createTimetableSlot");
        assertOperation(paths, "/timetableSlots/auto-plan", "post", "autoPlanTimetable");
        assertOperation(paths, "/attendance", "get", "listAttendance");
        assertOperation(paths, "/attendance/bulk", "post", "bulkMarkAttendance");
        assertOperation(paths, "/attendance/day-status", "get", "getAttendanceDayStatus");
        assertOperation(paths, "/attendance/session-status", "get", "getAttendanceSessionStatus");
        assertOperation(paths, "/attendance/approved-leaves", "get", "listApprovedLeavesForAttendance");
        assertOperation(paths, "/attendance/unlock", "post", "unlockLateAttendance");
        assertOperation(paths, "/grades", "get", "listGrades");
        assertOperation(paths, "/grades", "post", "createGrade");
        assertOperation(paths, "/grades/bulk", "post", "bulkUpsertGrades");
        assertOperation(paths, "/me/gradebook-context", "get", "getTeacherGradebookContext");
        assertOperation(paths, "/grades/{id}", "put", "updateGrade");
        assertOperation(paths, "/grades/{id}/change-logs", "get", "listGradeChangeLogs");
        assertOperation(paths, "/exam-categories", "get", "listExamCategories");
        assertOperation(paths, "/exam-periods", "get", "listExamPeriods");
        assertOperation(paths, "/exam-periods", "post", "createExamPeriod");
        assertOperation(paths, "/exam-periods/{id}", "put", "updateExamPeriod");
        assertOperation(paths, "/exam-periods/{id}", "delete", "deleteExamPeriod");
        assertOperation(paths, "/exam-periods/{id}/publish-schedule", "post", "publishExamSchedule");
        assertOperation(paths, "/exam-periods/{id}/schedules", "get", "listExamSchedules");
        assertOperation(paths, "/exam-periods/{id}/schedules", "post", "createExamSchedule");
        assertOperation(paths, "/exam-schedules/{id}", "put", "updateExamSchedule");
        assertOperation(paths, "/exam-schedules/{id}", "delete", "deleteExamSchedule");
        assertOperation(paths, "/exam-schedules/{id}/rooms", "get", "listExamRooms");
        assertOperation(paths, "/exam-schedules/{id}/rooms", "post", "createExamRoom");
        assertOperation(paths, "/exam-rooms/{id}", "delete", "deleteExamRoom");
        assertOperation(paths, "/exam-rooms/{id}/allocate", "post", "allocateExamCandidates");
        assertOperation(paths, "/exam-schedules/{id}/eligible-graders", "get", "listEligibleExamGraders");
        assertOperation(paths, "/exam-schedules/{id}/graders", "get", "listExamGraders");
        assertOperation(paths, "/exam-schedules/{id}/graders", "put", "saveExamGrader");
        assertOperation(paths, "/exam-periods/{id}/lock-scores", "post", "lockExamScores");
        assertOperation(paths, "/exam-periods/{id}/unlock-scores", "post", "unlockExamScores");
        assertOperation(paths, "/exam-periods/{id}/confirm", "post", "confirmExamPeriod");
        assertOperation(paths, "/exam-periods/{id}/results", "get", "listExamResults");
        assertOperation(paths, "/exam-periods/{id}/results", "put", "saveExamResults");
        assertOperation(paths, "/me/exam-agenda", "get", "getMyExamAgenda");
        assertOperation(paths, "/me/exam-grading", "get", "getMyExamGradingTasks");
        assertOperation(paths, "/me/exam-results", "get", "getMyExamResults");
        assertOperation(paths, "/me/exam-reviews", "get", "getMyExamReviews");
        assertOperation(paths, "/exam-periods/{id}/reviews", "get", "listExamReviews");
        assertOperation(paths, "/exam-periods/{id}/reviews", "post", "requestExamReview");
        assertOperation(paths, "/exam-reviews/{id}/resolve", "put", "resolveExamReview");
        assertOperation(paths, "/exam-periods/{id}/adjustments", "get", "listExamScoreAdjustments");
        assertOperation(paths, "/academic-years/{id}/promotion-preview", "get", "getPromotionPreview");
        assertOperation(paths, "/academic-years/{id}/students/{studentId}/conduct", "put", "setStudentConduct");
        assertOperation(paths, "/academic-years/{id}/homeroom-summaries", "get", "getHomeroomYearlySummaries");
        assertOperation(paths, "/academic-years/{id}/my-summary", "get", "getMyYearlySummary");
        assertOperation(paths, "/academic-years/{id}/children/{studentId}/summary", "get", "getChildYearlySummary");
        assertOperation(paths, "/academic-years/{id}/finalize", "post", "finalizeAcademicYear");
        assertOperation(paths, "/academic-years/{id}/rollover-preview", "get", "getYearRolloverPreview");
        assertOperation(paths, "/academic-years/{id}/rollover", "post", "rolloverAcademicYear");
    }

    @Test
    void reportContractCoversDashboardReportsAndExports() throws IOException {
        Map<String, Object> paths = map(loadContract("report.yaml").get("paths"));
        assertOperation(paths, "/dashboard", "get", "getDashboard");
        assertOperation(paths, "/me/reports", "get", "getPersonalReport");
        assertOperation(paths, "/me/reports/export", "get", "exportPersonalReport");
        assertOperation(paths, "/reports/overview", "get", "getReportOverview");
        assertOperation(paths, "/reports/grade-distribution", "get", "getGradeDistribution");
        assertOperation(paths, "/reports/attendance-summary", "get", "getAttendanceSummary");
        assertOperation(paths, "/reports/revenue", "get", "getRevenueReport");
        assertOperation(paths, "/reports/export", "get", "exportReport");
    }

    private static Map<String, Object> loadContract(String fileName) throws IOException {
        try (InputStream input = Files.newInputStream(locate("docs/openapi/" + fileName))) {
            return new Yaml().load(input);
        }
    }

    private static void assertOperation(Map<String, Object> paths, String path, String method,
                                        String operationId) {
        Map<String, Object> operation = map(map(paths.get(path)).get(method));
        assertEquals(operationId, operation.get("operationId"));
    }

    private static Path locate(String relativePath) {
        Path current = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5 && current != null; i++, current = current.getParent()) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) return candidate;
        }
        throw new IllegalStateException("Cannot locate " + relativePath);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        assertNotNull(value);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        assertNotNull(value);
        return (List<Object>) value;
    }

    private static Object resolve(Map<String, Object> root, String... keys) {
        Object current = root;
        for (String key : keys) current = map(current).get(key);
        return current;
    }
}
