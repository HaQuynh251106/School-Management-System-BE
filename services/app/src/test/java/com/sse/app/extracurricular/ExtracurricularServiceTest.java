package com.sse.app.extracurricular;

import com.sse.app.common.ApiException;
import com.sse.app.finance.FinanceService;
import com.sse.app.identity.UserService;
import com.sse.app.notification.NotificationService;
import com.sse.app.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExtracurricularServiceTest {
    @Mock ClubRepository clubs;
    @Mock ClubRegistrationRepository registrations;
    @Mock UserService users;
    @Mock NotificationService notifications;
    @Mock FinanceService finance;
    ExtracurricularService service;

    @BeforeEach
    void setUp() {
        service = new ExtracurricularService(clubs, registrations, users, notifications, finance);
    }

    @Test
    void paidRegistrationCreatesAndLinksInvoice() {
        Club club = Club.builder().id("club-1").name("Bóng rổ").fee(500_000)
                .capacity(30).status("OPEN").build();
        when(clubs.findById("club-1")).thenReturn(Optional.of(club));
        when(registrations.findByClubIdAndStudentId("club-1", "student-1")).thenReturn(Optional.empty());
        when(registrations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(users.fullNameOf("student-1")).thenReturn("Nguyễn Minh An");
        when(finance.createActivityInvoice(any(), eq("Bóng rổ"), eq("student-1"), eq(500_000L), any()))
                .thenReturn(new FinanceService.ActivityInvoiceResult("period-1", "invoice-1", "INV-1"));

        ClubRegistration result = service.register("club-1", "student-1", "parent-1");

        assertEquals("period-1", result.getFeePeriodId());
        assertEquals("invoice-1", result.getInvoiceId());
        verify(finance).createActivityInvoice(any(), eq("Bóng rổ"), eq("student-1"), eq(500_000L), any());
    }

    @Test
    void unrelatedStudentCannotCancelRegistration() {
        when(registrations.findById("reg-1")).thenReturn(Optional.of(
                ClubRegistration.builder().id("reg-1").studentId("student-1")
                        .feePeriodId("period-1").status("REGISTERED").build()));

        assertThrows(ApiException.class, () -> service.cancel("reg-1",
                new CurrentUser("student-2", "hs2", "STUDENT")));
        verify(finance, never()).cancelActivityInvoice(any());
    }
}
