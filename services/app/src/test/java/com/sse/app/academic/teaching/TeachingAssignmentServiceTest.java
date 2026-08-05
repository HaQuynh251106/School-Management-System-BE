package com.sse.app.academic.teaching;

import com.sse.app.academic.structure.SchoolClass;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.academic.structure.Subject;
import com.sse.app.academic.teaching.TeachingDtos.CreateTeachingAssignmentRequest;
import com.sse.app.academic.timetable.TimetableService;
import com.sse.app.common.ApiException;
import com.sse.app.identity.User;
import com.sse.app.identity.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeachingAssignmentServiceTest {

    @Mock private TeachingAssignmentRepository assignments;
    @Mock private StructureService structure;
    @Mock private UserService users;
    @Mock private TimetableService timetable;

    private TeachingAssignmentService service;

    @BeforeEach
    void setUp() {
        service = new TeachingAssignmentService(assignments, structure, users, timetable);
    }

    @Test
    void createRejectsTeacherOutsideSubjectSpecialty() {
        prepareScope("Ngu van");
        CreateTeachingAssignmentRequest request = new CreateTeachingAssignmentRequest(
                null, "teacher-1", "class-1", "subject-math", "semester-1", "ACTIVE", 3, null);

        ApiException error = assertThrows(ApiException.class, () -> service.create(request));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        verify(assignments, never()).save(any());
    }

    @Test
    void createStoresWeeklyPeriodsForMatchingSpecialty() {
        prepareScope("Toan");
        when(assignments.save(any(TeacherClassSubject.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(assignments.findAll()).thenReturn(List.of());
        when(timetable.allSlots()).thenReturn(List.of());

        var result = service.create(new CreateTeachingAssignmentRequest(
                null, "teacher-1", "class-1", "subject-math", "semester-1", "ACTIVE", 3, null));

        assertEquals(3, result.weeklyPeriods());
        assertEquals("teacher-1", result.teacherId());
        assertEquals("class-1", result.classId());
    }

    @Test
    void legacyAssignmentWithWrongSpecialtyDoesNotAuthorizeTeacher() {
        when(assignments.existsByTeacherIdAndClassIdAndSubjectIdAndStatus(
                "teacher-1", "class-1", "subject-math", "ACTIVE")).thenReturn(true);
        when(users.getById("teacher-1")).thenReturn(teacher("Ngu van"));
        when(structure.listSubjects()).thenReturn(List.of(mathSubject()));

        assertFalse(service.teacherAssignedToClassSubject("teacher-1", "class-1", "subject-math"));
    }

    private void prepareScope(String mainSubject) {
        when(assignments.findByClassIdAndSubjectIdAndSemesterIdAndStatus(
                "class-1", "subject-math", "semester-1", "ACTIVE")).thenReturn(Optional.empty());
        when(users.getById("teacher-1")).thenReturn(teacher(mainSubject));
        when(structure.getClass("class-1")).thenReturn(SchoolClass.builder()
                .id("class-1").code("10A1").build());
        when(structure.listSubjects()).thenReturn(List.of(mathSubject()));
    }

    private User teacher(String mainSubject) {
        return User.builder()
                .id("teacher-1")
                .fullName("Teacher One")
                .role("TEACHER")
                .status("ACTIVE")
                .mainSubject(mainSubject)
                .build();
    }

    private Subject mathSubject() {
        return Subject.builder().id("subject-math").code("MATH").name("Toan").build();
    }
}
