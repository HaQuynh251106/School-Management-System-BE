package com.sse.app.academic.summary;

import com.sse.app.academic.structure.AcademicYear;
import com.sse.app.academic.structure.SchoolClass;
import com.sse.app.academic.structure.Semester;
import com.sse.app.academic.structure.StructureDtos.CreateAcademicYearRequest;
import com.sse.app.academic.structure.StructureDtos.CreateClassRequest;
import com.sse.app.academic.structure.StructureDtos.CreateSemesterRequest;
import com.sse.app.academic.structure.StructureService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class YearRolloverServiceTest {
    @Mock YearEndService yearEnd;
    @Mock StructureService structure;

    @Test
    void rolloverCreatesTheNextStructureFinalizesEveryOutcomeAndActivatesLast() {
        YearRolloverService service = new YearRolloverService(yearEnd, structure);
        AcademicYear current = AcademicYear.builder()
                .id("ay-current").code("2030-2031").name("Năm học 2030-2031")
                .startDate(LocalDate.of(2030, 8, 15)).endDate(LocalDate.of(2031, 5, 31))
                .status("ACTIVE").build();
        AcademicYear next = AcademicYear.builder()
                .id("ay-next").code("2031-2032").name("Năm học 2031-2032")
                .startDate(LocalDate.of(2031, 8, 15)).endDate(LocalDate.of(2032, 5, 31))
                .status("PLANNED").build();
        List<Semester> semesters = List.of(
                semester("sm-hk1", "HK1", 1, LocalDate.of(2030, 8, 15), LocalDate.of(2030, 12, 31)),
                semester("sm-hk2", "HK2", 2, LocalDate.of(2031, 1, 5), LocalDate.of(2031, 5, 31))
        );
        SchoolClass class10 = schoolClass("c-10a1", "10A1", "K10");
        SchoolClass class12 = schoolClass("c-12a1", "12A1", "K12");
        var preview = new YearEndDtos.RolloverPreview(
                current.getId(), current.getCode(), "ACTIVE", 2, 2, 3,
                3, 0, 1, 1, 1,
                List.of(
                        new YearEndDtos.RolloverClassPlan("c-10a1", "10A1", "11A1", "K11",
                                "PROMOTION", 45, "MORNING"),
                        new YearEndDtos.RolloverClassPlan("c-10a1", "10A1", "10A1", "K10",
                                "NEW_INTAKE", 45, "MORNING")
                ),
                List.of());

        when(structure.getYear("ay-current")).thenReturn(current);
        when(structure.listSemesters("ay-current")).thenReturn(semesters);
        when(structure.listClasses("ay-current", null)).thenReturn(List.of(class10, class12));
        when(structure.createYear(any(CreateAcademicYearRequest.class), eq(false))).thenReturn(next);
        when(structure.createSemester(any(CreateSemesterRequest.class)))
                .thenReturn(semester("next-hk1", "HK1", 1, next.getStartDate(), LocalDate.of(2031, 12, 31)))
                .thenReturn(semester("next-hk2", "HK2", 2, LocalDate.of(2032, 1, 5), next.getEndDate()));
        when(structure.createClass(any(CreateClassRequest.class)))
                .thenReturn(schoolClass("next-11a1", "11A1", "K11"))
                .thenReturn(schoolClass("next-10a1", "10A1", "K10"));
        when(yearEnd.finalizeYear("ay-current", "academic-user")).thenReturn(List.of(
                summary("s-promoted", "PROMOTED"),
                summary("s-retained", "RETAINED"),
                summary("s-graduated", "GRADUATED")
        ));

        // The public preview is verified separately; stub it to focus this test on transaction orchestration.
        YearRolloverService spy = spy(service);
        doReturn(preview).when(spy).preview("ay-current");
        var result = spy.rollover("ay-current", new YearEndDtos.RolloverRequest(
                "2031-2032", "Năm học 2031-2032",
                next.getStartDate(), next.getEndDate(), true, true), "academic-user");

        assertThat(result.closedYearId()).isEqualTo("ay-current");
        assertThat(result.nextYearId()).isEqualTo("ay-next");
        assertThat(result.createdSemesterCount()).isEqualTo(2);
        assertThat(result.createdClassCount()).isEqualTo(2);
        assertThat(result.promotedCount()).isEqualTo(1);
        assertThat(result.retainedCount()).isEqualTo(1);
        assertThat(result.graduatedCount()).isEqualTo(1);
        assertThat(result.nextYearActivated()).isTrue();

        verify(structure, times(2)).createSemester(any(CreateSemesterRequest.class));
        verify(structure, times(2)).createClass(any(CreateClassRequest.class));
        InOrder order = inOrder(yearEnd, structure);
        order.verify(yearEnd).finalizeYear("ay-current", "academic-user");
        order.verify(structure).changeYearStatus("ay-next", "ACTIVE");
        order.verify(structure).changeSemesterStatus("next-hk1", "ACTIVE");
    }

    private Semester semester(String id, String code, int sequence, LocalDate start, LocalDate end) {
        return Semester.builder().id(id).academicYearId("ay-current").code(code).name(code)
                .sequence(sequence).startDate(start).endDate(end).status("ACTIVE").build();
    }

    private SchoolClass schoolClass(String id, String code, String grade) {
        return SchoolClass.builder().id(id).code(code).name("Lớp " + code).gradeLevel(grade)
                .academicYearId("ay-current").studyShift("MORNING").capacity(45).studentCount(0).build();
    }

    private StudentYearlySummary summary(String studentId, String status) {
        return StudentYearlySummary.builder().id("sys-" + studentId).academicYearId("ay-current")
                .studentId(studentId).studentName(studentId).classId("c-10a1")
                .semesterOneAverage(7.0).semesterTwoAverage(8.0).averageScore(7.67)
                .conductGrade("GOOD").promotionStatus(status).build();
    }
}
