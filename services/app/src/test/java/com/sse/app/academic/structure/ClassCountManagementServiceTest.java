package com.sse.app.academic.structure;

import com.sse.app.academic.structure.StructureDtos.ClassCountPlanRequest;
import com.sse.app.academic.planning.EducationPlanningCatalogService;
import com.sse.app.academic.teaching.TeachingAssignmentService;
import com.sse.app.academic.timetable.AutomaticTimetableService;
import com.sse.app.academic.timetable.TimetableService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClassCountManagementServiceTest {
    @Mock private StructureService structure;
    @Mock private TeachingAssignmentService teachingAssignments;
    @Mock private TimetableService timetable;
    @Mock private AutomaticTimetableService automaticTimetable;
    @Mock private EducationPlanningCatalogService planningCatalog;

    private ClassCountManagementService service;

    @BeforeEach
    void setUp() {
        service = new ClassCountManagementService(
                structure, teachingAssignments, timetable, automaticTimetable,
                planningCatalog);
        when(structure.getYear("ay-2027")).thenReturn(AcademicYear.builder()
                .id("ay-2027").code("2027-2028").status("ACTIVE").build());
        when(structure.listClasses("ay-2027", "K10"))
                .thenReturn(List.of(schoolClass("c-10a1", "10A1", "K10", 0),
                        schoolClass("c-10a2", "10A2", "K10", 0)));
        when(structure.listClasses("ay-2027", "K11"))
                .thenReturn(List.of(schoolClass("c-11a1", "11A1", "K11", 0)));
        when(structure.listClasses("ay-2027", "K12"))
                .thenReturn(List.of(schoolClass("c-12a1", "12A1", "K12", 0)));
    }

    @Test
    void previewShowsExactClassThatWillBeCreated() {
        var result = service.preview(new ClassCountPlanRequest("ay-2027", 3, 1, 1));

        assertTrue(result.applicable());
        assertEquals(List.of("10A3"), result.grades().get(0).classesToCreate());
        assertEquals(2, result.grades().get(0).currentCount());
        assertEquals(3, result.grades().get(0).targetCount());
    }

    @Test
    void decreaseIsBlockedWhenClassStillContainsDataOrDraftSchedule() {
        SchoolClass class10A2 = schoolClass("c-10a2", "10A2", "K10", 4);
        when(structure.listClasses("ay-2027", "K10"))
                .thenReturn(List.of(schoolClass("c-10a1", "10A1", "K10", 0), class10A2));
        when(teachingAssignments.hasActiveAssignmentsForClass("c-10a2")).thenReturn(true);
        when(automaticTimetable.hasDraftSlotsForClass("c-10a2")).thenReturn(true);
        when(planningCatalog.hasClassCombination("c-10a2")).thenReturn(true);

        var result = service.preview(new ClassCountPlanRequest("ay-2027", 1, 1, 1));

        assertFalse(result.applicable());
        String reasons = String.join(" ", result.grades().get(0).blockingReasons());
        assertTrue(reasons.contains("còn 4 học sinh"));
        assertTrue(reasons.contains("còn phân công giảng dạy"));
        assertTrue(reasons.contains("bản thời khóa biểu nháp"));
        assertTrue(reasons.contains("đã được gán tổ hợp môn"));
    }

    @Test
    void applyIncreaseCreatesTheNextClassWithExpectedCapacity() {
        service.apply(new ClassCountPlanRequest("ay-2027", 3, 1, 1));

        ArgumentCaptor<StructureDtos.CreateClassRequest> captor =
                ArgumentCaptor.forClass(StructureDtos.CreateClassRequest.class);
        verify(structure).createClass(captor.capture());
        assertEquals("10A3", captor.getValue().code());
        assertEquals("K10", captor.getValue().gradeLevel());
        assertEquals("ay-2027", captor.getValue().academicYearId());
        assertEquals(45, captor.getValue().maxStudents());
    }

    private SchoolClass schoolClass(
            String id, String code, String grade, int studentCount) {
        return SchoolClass.builder()
                .id(id).code(code).name("Lớp " + code).gradeLevel(grade)
                .academicYearId("ay-2027").studentCount(studentCount)
                .status("ACTIVE").build();
    }
}
