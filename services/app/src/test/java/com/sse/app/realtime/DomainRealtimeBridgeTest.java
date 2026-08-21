package com.sse.app.realtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DomainRealtimeBridgeTest {
    @Test
    void mapsSharedWebMutationsToMobileDataDomains() {
        assertThat(DomainRealtimeBridge.eventTypeFor("academic.grade.changed"))
                .isEqualTo("GRADE_UPDATED");
        assertThat(DomainRealtimeBridge.eventTypeFor("academic.exam_schedule.published"))
                .isEqualTo("EXAM_UPDATED");
        assertThat(DomainRealtimeBridge.eventTypeFor("academic.timetable.published"))
                .isEqualTo("TIMETABLE_PUBLISHED");
        assertThat(DomainRealtimeBridge.eventTypeFor("academic.education_plan.published"))
                .isEqualTo("ACADEMIC_PLAN_UPDATED");
        assertThat(DomainRealtimeBridge.eventTypeFor("academic.submission.graded"))
                .isEqualTo("ASSIGNMENT_UPDATED");
        assertThat(DomainRealtimeBridge.eventTypeFor("finance.payment.confirmed"))
                .isEqualTo("PAYMENT_STATUS_UPDATED");
        assertThat(DomainRealtimeBridge.eventTypeFor("academic.year_result.published"))
                .isEqualTo("YEAR_RESULT_UPDATED");
    }
}
