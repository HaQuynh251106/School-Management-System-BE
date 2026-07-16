package com.sse.app.academic.structure;

import com.sse.app.academic.structure.StructureDtos.*;
import com.sse.app.common.ApiException;
import com.sse.app.identity.User;
import com.sse.app.identity.UserDto;
import com.sse.app.identity.UserService;
import com.sse.app.academic.timetable.TimetableService;
import com.sse.app.security.CurrentUserHolder;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** A2: Cơ cấu đào tạo. Route đặt tên khớp json-server (academicYears, semesters, classes, subjects...). */
@RestController
public class StructureController {

    private final StructureService structure;
    private final UserService users;
    private final TimetableService timetable;

    public StructureController(StructureService structure, UserService users, TimetableService timetable) {
        this.structure = structure;
        this.users = users;
        this.timetable = timetable;
    }

    // ----- Năm học -----
    @GetMapping("/academicYears")
    public List<AcademicYear> years() {
        CurrentUserHolder.require();
        return structure.listYears();
    }

    @PostMapping("/academicYears")
    public AcademicYear createYear(@Valid @RequestBody CreateAcademicYearRequest r) {
        CurrentUserHolder.requireRole("ADMIN");
        return structure.createYear(r);
    }

    // ----- Học kỳ -----
    @GetMapping("/semesters")
    public List<Semester> semesters(@RequestParam(required = false) String academicYearId) {
        CurrentUserHolder.require();
        return structure.listSemesters(academicYearId);
    }

    @PostMapping("/semesters")
    public Semester createSemester(@Valid @RequestBody CreateSemesterRequest r) {
        CurrentUserHolder.requireRole("ADMIN");
        return structure.createSemester(r);
    }

    // ----- Lớp -----
    @GetMapping("/classes")
    public List<SchoolClass> classes(@RequestParam(required = false) String academicYearId,
                                     @RequestParam(required = false) String gradeLevel) {
        CurrentUserHolder.require();
        List<SchoolClass> result = structure.listClasses(academicYearId, gradeLevel);
        result.forEach(item -> item.setStudentCount(users.studentCountOfClass(item.getId())));
        return result;
    }

    @GetMapping("/classes/{id}")
    public SchoolClass classById(@PathVariable String id) {
        CurrentUserHolder.require();
        return structure.getClass(id);
    }

    @GetMapping("/classes/{id}/students")
    public List<UserDto> classStudents(@PathVariable String id) {
        CurrentUserHolder.requireRole("ADMIN", "TEACHER");
        var current = CurrentUserHolder.require();
        SchoolClass schoolClass = structure.getClass(id);
        if (current.isTeacher() && !current.id().equals(schoolClass.getHomeroomTeacherId())
                && !timetable.teacherTeachesClass(current.id(), id)) {
            throw ApiException.forbidden("Giáo viên không được phân công giảng dạy hoặc chủ nhiệm lớp này");
        }
        return current.isAdmin()
                ? users.list("STUDENT", null, id)
                : users.listSummaries("STUDENT", null, id);
    }

    @GetMapping("/classes/{classId}/students/{studentId}/profile")
    public UserDto homeroomStudentProfile(@PathVariable String classId,
                                           @PathVariable String studentId) {
        CurrentUserHolder.requireRole("ADMIN", "TEACHER");
        var current = CurrentUserHolder.require();
        SchoolClass schoolClass = structure.getClass(classId);
        if (current.isTeacher() && !current.id().equals(schoolClass.getHomeroomTeacherId())) {
            throw ApiException.forbidden("Chỉ giáo viên chủ nhiệm được xem hồ sơ chi tiết của học sinh");
        }

        User student = users.getById(studentId);
        if (!"STUDENT".equals(student.getRole()) || !classId.equals(student.getClassId())) {
            throw ApiException.forbidden("Học sinh không thuộc lớp này");
        }
        return users.toDto(student);
    }

    @GetMapping("/students/{studentId}/enrollments")
    public List<ClassEnrollment> enrollmentHistory(@PathVariable String studentId) {
        var current = CurrentUserHolder.require();
        if (!current.isAdmin() && !current.id().equals(studentId)) {
            if (current.isParent()) users.assertParentOf(current.id(), studentId);
            else throw ApiException.forbidden("Không có quyền xem lịch sử xếp lớp");
        }
        return structure.enrollmentHistory(studentId);
    }

    @PostMapping("/classes")
    public SchoolClass createClass(@Valid @RequestBody CreateClassRequest r) {
        CurrentUserHolder.requireRole("ADMIN");
        User teacher = null;
        if (r.homeroomTeacherId() != null && !r.homeroomTeacherId().isBlank()) {
            teacher = requireActiveTeacher(r.homeroomTeacherId());
        }
        SchoolClass created = structure.createClass(r);
        if (teacher == null) return created;
        return structure.assignHomeroomTeacher(created.getId(), teacher.getId(), teacher.getFullName(),
                CurrentUserHolder.require().id());
    }

    @PutMapping("/classes/{id}/homeroom-teacher")
    public SchoolClass assignHomeroomTeacher(@PathVariable String id,
                                              @Valid @RequestBody AssignHomeroomTeacherRequest r) {
        CurrentUserHolder.requireRole("ADMIN");
        User teacher = requireActiveTeacher(r.teacherId());
        return structure.assignHomeroomTeacher(id, teacher.getId(), teacher.getFullName(),
                CurrentUserHolder.require().id());
    }

    @DeleteMapping("/classes/{id}/homeroom-teacher")
    public SchoolClass clearHomeroomTeacher(@PathVariable String id) {
        CurrentUserHolder.requireRole("ADMIN");
        return structure.clearHomeroomTeacher(id);
    }

    // ----- Môn -----
    @GetMapping("/subjects")
    public List<Subject> subjects() {
        CurrentUserHolder.require();
        return structure.listSubjects();
    }

    @PostMapping("/subjects")
    public Subject createSubject(@Valid @RequestBody CreateSubjectRequest r) {
        CurrentUserHolder.requireRole("ADMIN");
        return structure.createSubject(r);
    }

    // ----- Phòng -----
    @GetMapping("/rooms")
    public List<Room> rooms() {
        CurrentUserHolder.require();
        return structure.listRooms();
    }

    @PostMapping("/rooms")
    public Room createRoom(@Valid @RequestBody CreateRoomRequest r) {
        CurrentUserHolder.requireRole("ADMIN");
        return structure.createRoom(r);
    }

    // ----- Ngày nghỉ -----
    @GetMapping("/school-holidays")
    public List<SchoolHoliday> holidays() {
        CurrentUserHolder.require();
        return structure.listHolidays();
    }

    @PostMapping("/school-holidays")
    public SchoolHoliday createHoliday(@Valid @RequestBody CreateHolidayRequest r) {
        CurrentUserHolder.requireRole("ADMIN");
        return structure.createHoliday(r);
    }

    @DeleteMapping("/school-holidays/{id}")
    public void deleteHoliday(@PathVariable String id) {
        CurrentUserHolder.requireRole("ADMIN");
        structure.deleteHoliday(id);
    }

    @PutMapping("/subjects/{id}")
    public Subject updateSubject(@PathVariable String id, @Valid @RequestBody UpdateSubjectRequest r) {
        CurrentUserHolder.requireRole("ADMIN");
        return structure.updateSubject(id, r);
    }

    private User requireActiveTeacher(String teacherId) {
        User teacher = users.getById(teacherId);
        if (!"TEACHER".equals(teacher.getRole())) {
            throw ApiException.badRequest("Người được phân công phải là giáo viên");
        }
        if (!"ACTIVE".equals(teacher.getStatus())) {
            throw ApiException.badRequest("Không thể phân công giáo viên đang bị khóa");
        }
        return teacher;
    }
}
