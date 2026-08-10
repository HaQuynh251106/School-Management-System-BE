package com.sse.app.academic.grade;

import com.sse.app.academic.grade.GradeDtos.BulkGradeRequest;
import com.sse.app.academic.grade.GradeDtos.UpsertGradeConfigurationRequest;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.academic.teaching.TeachingAssignmentService;
import com.sse.app.academic.timetable.TimetableService;
import com.sse.app.common.ApiException;
import com.sse.app.event.DomainEventPublisher;
import com.sse.app.identity.UserService;
import com.sse.app.report.AcademicResultLockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GradeConfigurationServiceTest {
    @Mock GradeRepository grades;
    @Mock GradeChangeLogRepository logs;
    @Mock ExamCategoryRepository categories;
    @Mock GradeConfigurationRepository configurations;
    @Mock StructureService structure;
    @Mock TimetableService timetable;
    @Mock TeachingAssignmentService teachingAssignments;
    @Mock UserService users;
    @Mock DomainEventPublisher events;
    @Mock AcademicResultLockService resultLocks;
    GradeService service;

    @BeforeEach
    void setUp() {
        service = new GradeService(grades, logs, categories, configurations,
                structure, timetable, teachingAssignments, users, events, resultLocks);
    }

    @Test
    void savesSubjectSemesterSpecificRequiredCountAndWeight() {
        when(categories.findByCode("ORAL")).thenReturn(Optional.of(
                ExamCategory.builder().id("cat-1").code("ORAL").name("Điểm miệng").weight(1).build()));
        when(configurations.findBySubjectIdAndSemesterIdAndCategoryCode("math", "hk1", "ORAL"))
                .thenReturn(Optional.empty());
        when(configurations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        GradeConfiguration saved = service.upsertConfiguration(
                new UpsertGradeConfigurationRequest("math", "hk1", "oral", null, 3, 1.5, true),
                "admin-1");

        assertEquals("ORAL", saved.getCategoryCode());
        assertEquals(3, saved.getRequiredCount());
        assertEquals(1.5, saved.getWeight());
        assertEquals("admin-1", saved.getUpdatedBy());
    }

    @Test
    void rejectsEntryOutsideConfiguredCountBeforeWritingGrades() {
        when(structure.subjectName("math")).thenReturn("Toán");
        when(categories.findByCode("ORAL")).thenReturn(Optional.empty());
        when(configurations.findBySubjectIdAndSemesterIdAndCategoryCode("math", "hk1", "ORAL"))
                .thenReturn(Optional.of(GradeConfiguration.builder()
                        .subjectId("math").semesterId("hk1").categoryCode("ORAL")
                        .categoryName("Điểm miệng").requiredCount(2).weight(1).active(true).build()));

        assertThrows(ApiException.class, () -> service.bulkUpsert(
                new BulkGradeRequest("math", "hk1", "ORAL", 3, null, List.of()),
                "teacher-1", true));
    }

    @Test
    void validatesConfigurationBounds() {
        assertThrows(ApiException.class, () -> service.upsertConfiguration(
                new UpsertGradeConfigurationRequest("math", "hk1", "ORAL", null, 0, 1.0, true),
                "admin-1"));
        assertThrows(ApiException.class, () -> service.upsertConfiguration(
                new UpsertGradeConfigurationRequest("math", "hk1", "ORAL", null, 1, 11.0, true),
                "admin-1"));
    }
}
