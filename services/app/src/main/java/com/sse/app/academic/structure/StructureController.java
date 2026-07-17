package com.sse.app.academic.structure;

import com.sse.app.academic.structure.StructureDtos.*;
import com.sse.app.identity.UserDto;
import com.sse.app.identity.UserService;
import com.sse.app.security.CurrentUserHolder;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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
    @GetMapping({"/academicYears", "/academic-years"})
    public List<AcademicYear> years() {
        CurrentUserHolder.require();
        return structure.listYears();
    }

    @PostMapping({"/academicYears", "/academic-years"})
    public AcademicYear createYear(@Valid @RequestBody CreateAcademicYearRequest r) {
        CurrentUserHolder.requireRole("ADMIN");
        return structure.createYear(r);
    }

    // ----- Học kỳ -----
    @GetMapping({"/semesters", "/academic/semesters"})
    public List<Semester> semesters(@RequestParam(required = false) String academicYearId) {
        CurrentUserHolder.require();
        return structure.listSemesters(academicYearId);
    }

    @PostMapping({"/semesters", "/academic/semesters"})
    public Semester createSemester(@Valid @RequestBody CreateSemesterRequest r) {
        CurrentUserHolder.requireRole("ADMIN");
        return structure.createSemester(r);
    }

    // ----- Lớp -----
    @GetMapping({"/classes", "/academic/classes"})
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

    @PostMapping({"/classes", "/academic/classes"})
    public SchoolClass createClass(@Valid @RequestBody CreateClassRequest r) {
        CurrentUserHolder.requireRole("ADMIN");
        return structure.createClass(r);
    }

    @PutMapping("/classes/{id}/homeroom-teacher")
    public SchoolClass assignHomeroomTeacher(@PathVariable String id,
                                              @RequestBody AssignHomeroomTeacherRequest r) {
        CurrentUserHolder.requireRole("ADMIN");
        return structure.assignHomeroomTeacher(id, r.homeroomTeacherId());
    }

    @PostMapping("/academic/high-school-defaults/ensure")
    public Map<String, Object> ensureHighSchoolDefaults(@RequestParam(required = false) String academicYearId) {
        CurrentUserHolder.requireRole("ADMIN");
        int created = structure.ensureHighSchoolDefaults(academicYearId);
        return Map.of("ok", true, "createdClasses", created,
                "academicYearId", academicYearId == null ? "ACTIVE" : academicYearId);
    }

    // ----- Môn -----
    @GetMapping({"/subjects", "/academic/subjects"})
    public List<Subject> subjects() {
        CurrentUserHolder.require();
        return structure.listSubjects();
    }

    @PostMapping({"/subjects", "/academic/subjects"})
    public Subject createSubject(@Valid @RequestBody CreateSubjectRequest r) {
        CurrentUserHolder.requireRole("ADMIN");
        return structure.createSubject(r);
    }

    // ----- Phòng -----
    @GetMapping({"/rooms", "/academic/rooms"})
    public List<Room> rooms() {
        CurrentUserHolder.require();
        return structure.listRooms();
    }

    @PostMapping({"/rooms", "/academic/rooms"})
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
