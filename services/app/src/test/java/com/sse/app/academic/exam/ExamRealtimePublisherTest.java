package com.sse.app.academic.exam;

import com.sse.app.identity.UserService;
import com.sse.app.realtime.RealtimeEventHub;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExamRealtimePublisherTest {
    @Mock ExamCandidateRepository candidates;
    @Mock ExamResultRepository results;
    @Mock ExamReviewRepository reviews;
    @Mock ExamRoomRepository rooms;
    @Mock ExamGradingAssignmentRepository graders;
    @Mock UserService users;
    @Mock RealtimeEventHub realtime;

    @Test
    void publishedResultsInvalidateOnlyStudentAndLinkedParents() {
        ExamResult result = ExamResult.builder().id("result-1").examPeriodId("period-1")
                .scheduleId("schedule-1").studentId("student-1")
                .subjectId("subject-1").status("PUBLISHED").build();
        when(results.findById("result-1")).thenReturn(Optional.of(result));
        when(users.parentIdsOf("student-1")).thenReturn(List.of("parent-1"));

        publisher().publish(new ExamChangedEvent(
                "period-1", "result-1", null, "RESULTS_PUBLISHED"));

        verify(realtime).publish(eq("student-1"), eq("EXAM_UPDATED"), any());
        verify(realtime).publish(eq("parent-1"), eq("EXAM_UPDATED"), any());
        verify(realtime, never()).publish(eq("unrelated"), any(), any());
    }

    @Test
    void requestedReviewInvalidatesTheAssignedGrader() {
        ExamResult result = ExamResult.builder().id("result-1").examPeriodId("period-1")
                .scheduleId("schedule-1").studentId("student-1")
                .subjectId("subject-1").status("PUBLISHED").build();
        ExamReviewRequest review = ExamReviewRequest.builder().id("review-1")
                .examPeriodId("period-1").resultId("result-1")
                .studentId("student-1").studentName("Học sinh")
                .subjectId("subject-1").subjectName("Toán")
                .reason("Cần kiểm tra lại bài thi").status("PENDING")
                .requestedBy("student-1").build();
        ExamCandidate candidate = ExamCandidate.builder().id("candidate-1")
                .scheduleId("schedule-1").studentId("student-1").classId("class-1")
                .build();
        ExamGradingAssignment grader = ExamGradingAssignment.builder()
                .teacherId("teacher-1").build();
        when(results.findById("result-1")).thenReturn(Optional.of(result));
        when(reviews.findById("review-1")).thenReturn(Optional.of(review));
        when(users.parentIdsOf("student-1")).thenReturn(List.of("parent-1"));
        when(candidates.findByScheduleIdAndStudentId("schedule-1", "student-1"))
                .thenReturn(Optional.of(candidate));
        when(graders.findByScheduleIdAndClassId("schedule-1", "class-1"))
                .thenReturn(Optional.of(grader));

        publisher().publish(new ExamChangedEvent(
                "period-1", "result-1", "review-1", "REVIEW_REQUESTED"));

        verify(realtime).publish(eq("teacher-1"), eq("EXAM_UPDATED"), any());
        verify(realtime).publish(eq("student-1"), eq("EXAM_UPDATED"), any());
        verify(realtime).publish(eq("parent-1"), eq("EXAM_UPDATED"), any());
    }

    private ExamRealtimePublisher publisher() {
        return new ExamRealtimePublisher(candidates, results, reviews, rooms,
                graders, users, realtime);
    }
}
