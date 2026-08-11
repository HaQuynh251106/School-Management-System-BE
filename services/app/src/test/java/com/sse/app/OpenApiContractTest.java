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
                "club.yaml")) {
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

        assertOperation(paths, "/payments/cash", "post", "recordCashPayment");
        assertOperation(paths, "/payments/{paymentId}/confirm-vietqr", "post", "confirmVietQrPayment");
        assertOperation(paths, "/payments/{paymentId}/reject-vietqr", "post", "rejectVietQrPayment");
        assertOperation(paths, "/invoices/{invoiceId}/refund", "post", "refundInvoice");
        assertOperation(paths, "/invoices/{invoiceId}", "get", "getInvoiceDetail");

        Map<String, Object> status = map(resolve(contract, "components", "schemas", "Invoice",
                "properties", "status"));
        assertEquals(Set.of("UNPAID", "PARTIAL", "PAID", "OVERDUE", "CANCELLED",
                        "PARTIALLY_REFUNDED", "REFUNDED"),
                Set.copyOf(list(status.get("enum"))));
        Map<String, Object> transitions = map(status.get("x-state-transitions"));
        assertEquals(List.of("PARTIAL", "PAID", "OVERDUE", "CANCELLED"),
                list(transitions.get("UNPAID")));
        assertEquals(List.of("REFUNDED"), list(transitions.get("PARTIALLY_REFUNDED")));
        assertTrue(list(transitions.get("REFUNDED")).isEmpty());
        assertTrue(list(transitions.get("CANCELLED")).isEmpty());
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
