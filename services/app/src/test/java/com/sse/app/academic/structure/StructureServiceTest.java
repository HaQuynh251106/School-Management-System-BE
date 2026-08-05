package com.sse.app.academic.structure;

import com.sse.app.academic.structure.StructureDtos.CreateAcademicYearRequest;
import com.sse.app.academic.structure.StructureDtos.CreateHolidayRequest;
import com.sse.app.common.ApiException;
import com.sse.app.identity.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StructureServiceTest {
    @Mock AcademicYearRepository years;
    @Mock GradeLevelRepository gradeLevels;
    @Mock SemesterRepository semesters;
    @Mock SchoolClassRepository classes;
    @Mock SubjectRepository subjects;
    @Mock RoomRepository rooms;
    @Mock SchoolHolidayRepository holidays;
    @Mock UserRepository users;

    private StructureService service;

    @BeforeEach
    void setUp() {
        service = new StructureService(
                years, gradeLevels, semesters, classes,
                subjects, rooms, holidays, users);
    }

    @Test
    void creatingYearAutomaticallyCreatesTwoFiveMonthSemesters() {
        when(years.findByCodeIgnoreCase("2028-2029"))
                .thenReturn(Optional.empty());
        when(years.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(semesters.findByAcademicYearId("ay-new")).thenReturn(List.of());
        when(semesters.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AcademicYear created = service.createYear(
                new CreateAcademicYearRequest(
                        "ay-new", "2028-2029", null,
                        null, null, "PLANNED"));

        assertEquals(LocalDate.of(2028, 9, 1), created.getStartDate());
        assertEquals(LocalDate.of(2029, 6, 30), created.getEndDate());

        ArgumentCaptor<Semester> captor = ArgumentCaptor.forClass(Semester.class);
        verify(semesters, times(2)).save(captor.capture());
        List<Semester> saved = captor.getAllValues();
        assertEquals("HK1", saved.get(0).getCode());
        assertEquals(LocalDate.of(2028, 9, 1), saved.get(0).getStartDate());
        assertEquals(LocalDate.of(2029, 1, 31), saved.get(0).getEndDate());
        assertEquals("HK2", saved.get(1).getCode());
        assertEquals(LocalDate.of(2029, 2, 1), saved.get(1).getStartDate());
        assertEquals(LocalDate.of(2029, 6, 30), saved.get(1).getEndDate());
    }

    @Test
    void activatingYearClosesPreviouslyActiveYear() {
        AcademicYear previous = AcademicYear.builder()
                .id("ay-old").code("2026-2027").status("ACTIVE").build();
        AcademicYear target = AcademicYear.builder()
                .id("ay-new").code("2027-2028").status("CLOSED").build();
        when(years.findById("ay-new")).thenReturn(Optional.of(target));
        when(years.findAll()).thenReturn(List.of(previous, target));
        when(years.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(semesters.findByAcademicYearId(any())).thenReturn(List.of());

        service.updateYearStatus("ay-new", "ACTIVE");

        assertEquals("CLOSED", previous.getStatus());
        assertEquals("ACTIVE", target.getStatus());
    }

    @Test
    void holidayRangesCannotOverlapWithinAcademicYear() {
        AcademicYear year = AcademicYear.builder().id("ay-new")
                .startDate(LocalDate.of(2027, 9, 1))
                .endDate(LocalDate.of(2028, 6, 30)).build();
        SchoolHoliday existing = SchoolHoliday.builder().id("hol-existing")
                .academicYearId("ay-new").date(LocalDate.of(2027, 11, 20))
                .endDate(LocalDate.of(2027, 11, 25)).name("Nghỉ đã có").build();
        when(years.findById("ay-new")).thenReturn(Optional.of(year));
        when(holidays.findByAcademicYearIdOrderByDate("ay-new"))
                .thenReturn(List.of(existing));

        assertThrows(ApiException.class, () -> service.createHoliday(
                new CreateHolidayRequest(null, "ay-new",
                        LocalDate.of(2027, 11, 24), LocalDate.of(2027, 11, 26),
                        "Nghỉ mới", null)));
    }

    @Test
    void updatingHolidayKeepsIdentityAndSavesNewDates() {
        AcademicYear year = AcademicYear.builder().id("ay-new")
                .startDate(LocalDate.of(2027, 9, 1))
                .endDate(LocalDate.of(2028, 6, 30)).build();
        SchoolHoliday existing = SchoolHoliday.builder().id("hol-1")
                .academicYearId("ay-new").date(LocalDate.of(2027, 12, 1))
                .endDate(LocalDate.of(2027, 12, 1)).name("Tên cũ").build();
        when(holidays.findById("hol-1")).thenReturn(Optional.of(existing));
        when(years.findById("ay-new")).thenReturn(Optional.of(year));
        when(holidays.findByAcademicYearIdOrderByDate("ay-new"))
                .thenReturn(List.of(existing));
        when(holidays.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        SchoolHoliday updated = service.updateHoliday("hol-1",
                new CreateHolidayRequest(null, "ay-new",
                        LocalDate.of(2027, 12, 2), LocalDate.of(2027, 12, 3),
                        "Tên mới", "Ghi chú"));

        assertEquals("hol-1", updated.getId());
        assertEquals("Tên mới", updated.getName());
        assertEquals(LocalDate.of(2027, 12, 3), updated.getEndDate());
        verify(holidays).save(existing);
    }
}
