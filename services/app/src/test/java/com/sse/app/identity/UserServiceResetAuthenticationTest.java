package com.sse.app.identity;

import com.sse.app.academic.structure.StructureService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceResetAuthenticationTest {
    @Mock private UserRepository users;
    @Mock private ParentStudentRepository relations;
    @Mock private PasswordResetTokenRepository resetTokens;
    @Mock private PasswordEncoder encoder;
    @Mock private RefreshTokenRepository refreshTokens;
    @Mock private StructureService structure;
    @Mock private BusinessCodeCounterRepository codeCounters;
    private UserService service;

    @BeforeEach
    void setUp() {
        service = new UserService(users, relations, resetTokens, encoder, refreshTokens, structure, codeCounters);
    }

    @Test
    void localResetIssuesOneTimeTokenAndRevokesExistingSessions() {
        User user = User.builder().id("u-local").username("local")
                .passwordHash("hash").role("TEACHER").status("ACTIVE")
                .authType("LOCAL").email("local@example.test").build();
        when(users.findById("u-local")).thenReturn(Optional.of(user));
        when(users.save(user)).thenReturn(user);
        when(refreshTokens.findByUserId("u-local")).thenReturn(List.of());
        when(resetTokens.findByUserIdAndUsedAtIsNull("u-local")).thenReturn(List.of());
        when(resetTokens.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        UserService.AdminResetResult result = service.adminResetAuthentication("u-local");

        assertThat(result.authType()).isEqualTo("LOCAL");
        assertThat(result.action()).isEqualTo("RESET_LINK_SENT");
        assertThat(result.mustChangePassword()).isTrue();
        assertThat(result.issue()).isNotNull();
        assertThat(result.issue().token()).hasSize(32);
        assertThat(user.isPasswordChangeRequired()).isTrue();
        assertThat(user.getTokenVersion()).isEqualTo(1);
        verify(refreshTokens).saveAll(List.of());
        verify(resetTokens).save(any(PasswordResetToken.class));
    }

    @Test
    void ssoResetNeverIssuesLocalPasswordToken() {
        User user = User.builder().id("u-sso").username("sso")
                .passwordHash("unusable").role("TEACHER").status("ACTIVE")
                .authType("SSO").build();
        when(users.findById("u-sso")).thenReturn(Optional.of(user));
        when(users.save(user)).thenReturn(user);
        when(refreshTokens.findByUserId("u-sso")).thenReturn(List.of());

        UserService.AdminResetResult result = service.adminResetAuthentication("u-sso");

        assertThat(result.authType()).isEqualTo("SSO");
        assertThat(result.action()).isEqualTo("CONTACT_SSO_ADMIN");
        assertThat(result.mustChangePassword()).isFalse();
        assertThat(result.issue()).isNull();
        assertThat(user.getTokenVersion()).isEqualTo(1);
        verify(refreshTokens).saveAll(List.of());
        verify(resetTokens, never()).save(any());
    }

    @Test
    void forgotPasswordNormalizesEmailAndInvalidatesPreviousToken() {
        User user = User.builder().id("u-local").username("local")
                .passwordHash("hash").role("TEACHER").status("ACTIVE")
                .authType("LOCAL").email("user@example.test").build();
        PasswordResetToken previous = PasswordResetToken.builder()
                .id("old").userId(user.getId()).tokenHash("hash")
                .expiresAt(java.time.Instant.now().plusSeconds(60)).build();
        when(users.findByEmailIgnoreCase("user@example.test")).thenReturn(Optional.of(user));
        when(resetTokens.findByUserIdAndUsedAtIsNull(user.getId())).thenReturn(List.of(previous));
        when(resetTokens.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        UserService.PasswordResetIssue result =
                service.requestPasswordReset("  USER@EXAMPLE.TEST  ", null);

        assertThat(result).isNotNull();
        assertThat(previous.getUsedAt()).isNotNull();
        verify(resetTokens).saveAll(List.of(previous));
        verify(resetTokens).save(any(PasswordResetToken.class));
    }
}
