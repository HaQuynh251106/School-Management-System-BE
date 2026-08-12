package com.sse.app.academic.homeroom;

import com.sse.app.academic.structure.SchoolClass;
import com.sse.app.academic.structure.Semester;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.common.ApiException;
import com.sse.app.event.DomainEventPublisher;
import com.sse.app.identity.UserDto;
import com.sse.app.identity.UserService;
import com.sse.app.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HomeroomRemarkServiceTest {
    @Mock HomeroomRemarkRepository remarks;
    @Mock UserService users;
    @Mock StructureService structure;
    @Mock DomainEventPublisher events;
    HomeroomRemarkService service;
    UserDto student;
    SchoolClass schoolClass;

    @BeforeEach
    void setUp() {
        service = new HomeroomRemarkService(remarks, users, structure, events);
        student = new UserDto("student-1", "hs001", "Nguyễn Minh An", "STUDENT", "ACTIVE",
                null, null, null, "HS001", "10A1", "class-1",
                null, null, List.of());
        schoolClass = SchoolClass.builder().id("class-1").code("10A1")
                .academicYearId("year-1").homeroomTeacherId("teacher-1").build();
        when(users.dtoById("student-1")).thenReturn(student);
        when(structure.getClass("class-1")).thenReturn(schoolClass);
    }

    @Test
    void homeroomTeacherPublishesRemarkAndEmitsEvent() {
        Semester semester = Semester.builder().id("semester-1").academicYearId("year-1")
                .name("Học kỳ 1").build();
        when(structure.getSemester("semester-1")).thenReturn(semester);
        when(remarks.findByStudentIdAndSemesterId("student-1", "semester-1"))
                .thenReturn(Optional.empty());
        when(remarks.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(users.fullNameOf("teacher-1")).thenReturn("Giáo viên chủ nhiệm");

        var result = service.save(new CurrentUser("teacher-1", "gvcn", "TEACHER"),
                "student-1", new HomeroomRemarkDtos.SaveRemarkRequest(
                        "semester-1", "Có tiến bộ rõ rệt.", true));

        assertEquals("PUBLISHED", result.status());
        verify(events).publish(org.mockito.ArgumentMatchers.eq("academic.homeroom_remark.published"),
                org.mockito.ArgumentMatchers.eq("teacher-1"),
                org.mockito.ArgumentMatchers.eq("homeroom_remark"), any(), any());
    }

    @Test
    void nonHomeroomTeacherCannotWriteRemark() {
        assertThrows(ApiException.class, () -> service.save(
                new CurrentUser("teacher-2", "other", "TEACHER"), "student-1",
                new HomeroomRemarkDtos.SaveRemarkRequest("semester-1", "Không hợp lệ", false)));
    }

    @Test
    void parentOnlySeesPublishedRemarks() {
        HomeroomRemark draft = HomeroomRemark.builder().id("draft").studentId("student-1")
                .classId("class-1").academicYearId("year-1").semesterId("semester-1")
                .teacherId("teacher-1").body("Draft").status("DRAFT").updatedAt(Instant.now()).build();
        HomeroomRemark published = HomeroomRemark.builder().id("published").studentId("student-1")
                .classId("class-1").academicYearId("year-1").semesterId("semester-1")
                .teacherId("teacher-1").body("Published").status("PUBLISHED").updatedAt(Instant.now()).build();
        when(remarks.findByStudentIdOrderByUpdatedAtDesc("student-1"))
                .thenReturn(List.of(draft, published));
        Semester semester = Semester.builder().id("semester-1").academicYearId("year-1")
                .name("Học kỳ 1").build();
        when(structure.getSemester("semester-1")).thenReturn(semester);

        var rows = service.list(new CurrentUser("parent-1", "parent", "PARENT"), "student-1");

        verify(users).assertParentOf("parent-1", "student-1");
        assertEquals(List.of("published"), rows.stream().map(HomeroomRemarkDtos.RemarkResponse::id).toList());
    }
}
