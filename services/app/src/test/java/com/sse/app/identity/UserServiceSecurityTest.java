package com.sse.app.identity;

import com.sse.app.common.ApiException;
import com.sse.app.event.DomainEventPublisher;
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
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

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

    private UserService service;

    @BeforeEach
    void setUp() {
        service = new UserService(users, relations, resetTokens, refreshTokens,
                loginHistory, devices, rbac, encoder, events);
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
}
