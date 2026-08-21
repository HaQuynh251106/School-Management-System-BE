package com.sse.app.identity;

import com.sse.app.common.ApiException;
import com.sse.app.event.DomainEventPublisher;
import com.sse.app.report.AcademicEnrollmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
import org.mockito.ArgumentCaptor;

@ExtendWith(MockitoExtension.class)
class UserServiceSecurityTest {
    @Mock private UserRepository users;
    @Mock private ParentStudentRepository relations;
    @Mock private PasswordResetTokenRepository resetTokens;
    @Mock private RefreshTokenRepository refreshTokens;
    @Mock private LoginHistoryRepository loginHistory;
    @Mock private UserDeviceRepository devices;
    @Mock private RbacService rbac;
    @Mock private PasswordEncoder encoder;
    @Mock private DomainEventPublisher events;
    @Mock private AcademicEnrollmentService academicEnrollment;

    private UserService service;

    @BeforeEach
    void setUp() {
        service = new UserService(users, relations, resetTokens, refreshTokens,
                loginHistory, devices, rbac, encoder, events, academicEnrollment);
    }

    @Test
    void blocksLoginAfterFiveFailuresInFifteenMinutes() {
        when(loginHistory.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(loginHistory
                .countByUsernameAndIpAddressAndSuccessFalseAndCreatedAtAfter(
                        org.mockito.ArgumentMatchers.eq("admin"),
                        org.mockito.ArgumentMatchers.eq("127.0.0.1"), any()))
                .thenReturn(5L);

        ApiException error = assertThrows(ApiException.class,
                () -> service.authenticate(
                        "admin", "wrong", "127.0.0.1", "test"));

        assertEquals(429, error.getStatus().value());
    }

    @Test
    void adminResetRequiresFirstLoginChangeAndRevokesSessions() {
        User user = User.builder()
                .id("u-1").username("teacher.one").role("TEACHER")
                .status("ACTIVE").passwordHash("old").sessionVersion(3)
                .build();
        when(users.findById("u-1")).thenReturn(Optional.of(user));
        when(encoder.encode("NewStrong@123")).thenReturn("encoded");
        when(users.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(refreshTokens.findByUserIdAndRevokedAtIsNull("u-1"))
                .thenReturn(List.of(RefreshToken.builder().id("rt-1").build()));

        IdentityDtos.PasswordResetResult result = service.adminResetPassword(
                "u-1", "NewStrong@123", "admin-1");

        assertTrue(result.passwordChangeRequired());
        assertEquals(1, result.revokedSessions());
        assertEquals(4, user.getSessionVersion());
        assertTrue(user.isPasswordChangeRequired());
        assertEquals("encoded", user.getPasswordHash());
        verify(refreshTokens).saveAll(any());
    }

    @Test
    void ownPasswordChangeClearsFirstLoginFlagAndInvalidatesVersion() {
        User user = User.builder()
                .id("u-2").username("student.one").role("STUDENT")
                .status("ACTIVE").passwordHash("old")
                .passwordChangeRequired(true).sessionVersion(0)
                .build();
        when(users.findById("u-2")).thenReturn(Optional.of(user));
        when(encoder.matches("Current@123", "old")).thenReturn(true);
        when(encoder.matches("Changed@1234", "old")).thenReturn(false);
        when(encoder.encode("Changed@1234")).thenReturn("new-hash");
        when(users.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(refreshTokens.findByUserIdAndRevokedAtIsNull("u-2")).thenReturn(List.of());

        service.changeOwnPassword("u-2", "Current@123", "Changed@1234");

        assertFalse(user.isPasswordChangeRequired());
        assertEquals(1, user.getSessionVersion());
        assertEquals("new-hash", user.getPasswordHash());
    }

    @Test
    void passwordPolicyRejectsWeakPassword() {
        ApiException error = assertThrows(ApiException.class,
                () -> service.validatePassword("password"));
        assertEquals(400, error.getStatus().value());
    }

    @Test
    void passwordResetEventContainsExpiringFrontendLink() {
        User user = User.builder().id("u-3").username("parent.one")
                .email("parent@example.com").role("PARENT").status("ACTIVE").build();
        when(users.findByEmail("parent@example.com")).thenReturn(Optional.of(user));
        when(resetTokens.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.requestPasswordReset("parent@example.com", null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(events).publish(
                org.mockito.ArgumentMatchers.eq("identity.password.reset_requested"),
                org.mockito.ArgumentMatchers.eq("u-3"),
                org.mockito.ArgumentMatchers.eq("user"),
                org.mockito.ArgumentMatchers.eq("u-3"), payload.capture());
        assertTrue(String.valueOf(payload.getValue().get("resetUrl"))
                .startsWith("http://127.0.0.1:5173/?token="));
        assertEquals(30, payload.getValue().get("expiresInMinutes"));
    }

    @Test
    void adminCanLinkMultipleChildrenWithoutCreatingDuplicateRelation() {
        User parent = User.builder().id("parent-1").username("parent.one")
                .fullName("Phụ huynh Một").role("PARENT").status("ACTIVE").build();
        User student = User.builder().id("student-1").username("student.one")
                .fullName("Học sinh Một").role("STUDENT").status("ACTIVE").build();
        when(users.findById("parent-1")).thenReturn(Optional.of(parent));
        when(users.findById("student-1")).thenReturn(Optional.of(student));
        when(relations.findByParentId("parent-1")).thenReturn(List.of());
        when(relations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(rbac.permissionsFor("parent-1")).thenReturn(Set.of());

        service.linkChild("parent-1", "student-1", true);

        ArgumentCaptor<ParentStudent> relation = ArgumentCaptor.forClass(ParentStudent.class);
        verify(relations).save(relation.capture());
        assertEquals("parent-1", relation.getValue().getParentId());
        assertEquals("student-1", relation.getValue().getStudentId());
        assertTrue(relation.getValue().isPrimaryContact());
    }

    @Test
    void linkChildRejectsNonStudentTarget() {
        User parent = User.builder().id("parent-1").role("PARENT").status("ACTIVE").build();
        User teacher = User.builder().id("teacher-1").role("TEACHER").status("ACTIVE").build();
        when(users.findById("parent-1")).thenReturn(Optional.of(parent));
        when(users.findById("teacher-1")).thenReturn(Optional.of(teacher));

        ApiException error = assertThrows(ApiException.class,
                () -> service.linkChild("parent-1", "teacher-1", true));

        assertEquals(400, error.getStatus().value());
        verify(relations, never()).save(any());
    }
}
