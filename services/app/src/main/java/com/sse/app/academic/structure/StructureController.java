package com.sse.app.academic.structure;

import com.sse.app.academic.structure.StructureDtos.*;
import com.sse.app.identity.UserDto;
import com.sse.app.identity.UserService;
import com.sse.app.security.CurrentUserHolder;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** A2: Cơ cấu đào tạo. Route đặt tên khớp json-server (academicYears, semesters, classes, subjects...). */
@RestController
public class StructureController {

    private final StructureService structure;
    private final UserService users;

    public StructureController(StructureService structure, UserService users) {
        this.structure = structure;
        this.users = users;
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
        return structure.listClasses(academicYearId, gradeLevel);
    }

    @GetMapping("/classes/{id}")
    public SchoolClass classById(@PathVariable String id) {
        CurrentUserHolder.require();
        return structure.getClass(id);
    }

    @GetMapping("/classes/{id}/students")
    public List<UserDto> classStudents(@PathVariable String id) {
        CurrentUserHolder.require();
        return users.list("STUDENT", null, id);
    }

    @PostMapping("/classes")
    public SchoolClass createClass(@Valid @RequestBody CreateClassRequest r) {
        CurrentUserHolder.requireRole("ADMIN");
        return structure.createClass(r);
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
}
