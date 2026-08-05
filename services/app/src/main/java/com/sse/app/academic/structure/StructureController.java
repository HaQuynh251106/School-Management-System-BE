package com.sse.app.academic.structure;

import com.sse.app.academic.structure.StructureDtos.*;
import com.sse.app.academic.teaching.TeachingAssignmentService;
import com.sse.app.common.ApiException;
import com.sse.app.audit.AuditService;
import com.sse.app.identity.UserDto;
import com.sse.app.identity.UserService;
import com.sse.app.security.CurrentUser;
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
    private final AuditService audit;
    private final TeachingAssignmentService teachingAssignments;

    public StructureController(
            StructureService structure, UserService users,
            AuditService audit,
            TeachingAssignmentService teachingAssignments) {
        this.structure = structure;
        this.users = users;
        this.audit = audit;
        this.teachingAssignments = teachingAssignments;
    }

    // ----- Năm học -----
    @GetMapping({"/academicYears", "/academic-years"})
    public List<AcademicYear> years() {
        requireRead();
        return structure.listYears();
    }

    @GetMapping("/grade-levels")
    public List<GradeLevel> gradeLevels() {
        requireRead();
        return structure.listGradeLevels();
    }

    @GetMapping("/academic/teachers")
    public List<UserDto> academicTeachers() {
        requireRead();
        CurrentUserHolder.requireRole("ADMIN", "TEACHER");
        return users.list("TEACHER", null, null, "ACTIVE", false);
    }

    @PostMapping({"/academicYears", "/academic-years"})
    public AcademicYear createYear(@Valid @RequestBody CreateAcademicYearRequest r) {
        CurrentUser actor = requireManage();
        AcademicYear result = structure.createYear(r);
        record(actor, "CREATE", "academic_year", result.getId(), result.getCode());
        return result;
    }

    @PutMapping({"/academicYears/{id}", "/academic-years/{id}"})
    public AcademicYear updateYear(
            @PathVariable String id,
            @Valid @RequestBody UpdateAcademicYearRequest r) {
        CurrentUser actor = requireManage();
        AcademicYear result = structure.updateYear(id, r);
        record(actor, "UPDATE", "academic_year", id, result.getCode());
        return result;
    }

    // ----- Học kỳ -----
    @GetMapping({"/semesters", "/academic/semesters"})
    public List<Semester> semesters(@RequestParam(required = false) String academicYearId) {
        requireRead();
        return structure.listSemesters(academicYearId);
    }

    @PostMapping({"/semesters", "/academic/semesters"})
    public Semester createSemester(@Valid @RequestBody CreateSemesterRequest r) {
        CurrentUser actor = requireManage();
        Semester result = structure.createSemester(r);
        record(actor, "CREATE", "semester", result.getId(), result.getCode());
        return result;
    }

    @PutMapping({"/semesters/{id}", "/academic/semesters/{id}"})
    public Semester updateSemester(
            @PathVariable String id,
            @Valid @RequestBody UpdateSemesterRequest r) {
        CurrentUser actor = requireManage();
        Semester result = structure.updateSemester(id, r);
        record(actor, "UPDATE", "semester", id, result.getCode());
        return result;
    }

    // ----- Lớp -----
    @GetMapping({"/classes", "/academic/classes"})
    public List<SchoolClass> classes(@RequestParam(required = false) String academicYearId,
                                     @RequestParam(required = false) String gradeLevel) {
        requireRead();
        return structure.listClasses(academicYearId, gradeLevel);
    }

    @GetMapping("/classes/{id}")
    public SchoolClass classById(@PathVariable String id) {
        requireRead();
        return structure.getClass(id);
    }

    @GetMapping("/classes/{id}/students")
    public List<UserDto> classStudents(@PathVariable String id) {
        CurrentUser actor = CurrentUserHolder.require();
        SchoolClass schoolClass = structure.getClass(id);
        boolean canManageEnrollment = actor.isAdmin()
                || actor.hasPermission("ACADEMIC_ENROLLMENT_MANAGE");
        boolean assignedTeacher = actor.isTeacher()
                && (actor.id().equals(schoolClass.getHomeroomTeacherId())
                || teachingAssignments.teacherAssignedToClass(actor.id(), id));
        if (!canManageEnrollment && !assignedTeacher) {
            throw ApiException.forbidden("Giáo viên chỉ được xem học sinh của lớp được phân công hoặc chủ nhiệm");
        }
        return users.list("STUDENT", null, id);
    }

    @PostMapping({"/classes", "/academic/classes"})
    public SchoolClass createClass(@Valid @RequestBody CreateClassRequest r) {
        CurrentUser actor = requireManage();
        SchoolClass result = structure.createClass(r);
        record(actor, "CREATE", "class", result.getId(), result.getCode());
        return result;
    }

    @PutMapping({"/classes/{id}", "/academic/classes/{id}"})
    public SchoolClass updateClass(
            @PathVariable String id,
            @Valid @RequestBody UpdateClassRequest r) {
        CurrentUser actor = requireManage();
        SchoolClass result = structure.updateClass(id, r);
        record(actor, "UPDATE", "class", id, result.getCode());
        return result;
    }

    @PutMapping("/classes/{id}/homeroom-teacher")
    public SchoolClass assignHomeroomTeacher(@PathVariable String id,
                                              @RequestBody AssignHomeroomTeacherRequest r) {
        CurrentUser actor = requireManage();
        SchoolClass result = structure.assignHomeroomTeacher(
                id, r.homeroomTeacherId());
        record(actor, "ASSIGN_HOMEROOM_TEACHER", "class", id,
                r.homeroomTeacherId() == null
                        ? "Bỏ phân công GVCN" : r.homeroomTeacherId());
        return result;
    }

    @PutMapping("/classes/{id}/home-room")
    public SchoolClass assignHomeRoom(@PathVariable String id,
                                      @Valid @RequestBody AssignHomeRoomRequest r) {
        CurrentUser actor = requireManage();
        SchoolClass result = structure.assignHomeRoom(id, r.homeRoomId());
        record(actor, "ASSIGN_HOME_ROOM", "class", id, r.homeRoomId());
        return result;
    }

    @PostMapping("/academic/high-school-defaults/ensure")
    public Map<String, Object> ensureHighSchoolDefaults(@RequestParam(required = false) String academicYearId) {
        CurrentUser actor = requireManage();
        int created = structure.ensureHighSchoolDefaults(academicYearId);
        record(actor, "ENSURE_DEFAULT_CLASSES", "academic_year",
                academicYearId, "Khởi tạo " + created + " lớp còn thiếu");
        return Map.of("ok", true, "createdClasses", created,
                "academicYearId", academicYearId == null ? "ACTIVE" : academicYearId);
    }

    // ----- Môn -----
    @GetMapping({"/subjects", "/academic/subjects"})
    public List<Subject> subjects() {
        requireRead();
        return structure.listSubjects();
    }

    @PostMapping({"/subjects", "/academic/subjects"})
    public Subject createSubject(@Valid @RequestBody CreateSubjectRequest r) {
        CurrentUser actor = requireManage();
        Subject result = structure.createSubject(r);
        record(actor, "CREATE", "subject", result.getId(), result.getCode());
        return result;
    }

    @PutMapping({"/subjects/{id}", "/academic/subjects/{id}"})
    public Subject updateSubject(
            @PathVariable String id,
            @Valid @RequestBody CreateSubjectRequest r) {
        CurrentUser actor = requireManage();
        Subject result = structure.updateSubject(id, r);
        record(actor, "UPDATE", "subject", id, result.getCode());
        return result;
    }

    // ----- Phòng -----
    @GetMapping({"/rooms", "/academic/rooms"})
    public List<Room> rooms() {
        requireRead();
        return structure.listRooms();
    }

    @PostMapping({"/rooms", "/academic/rooms"})
    public Room createRoom(@Valid @RequestBody CreateRoomRequest r) {
        CurrentUser actor = requireManage();
        Room result = structure.createRoom(r);
        record(actor, "CREATE", "room", result.getId(), result.getCode());
        return result;
    }

    @PutMapping({"/rooms/{id}", "/academic/rooms/{id}"})
    public Room updateRoom(
            @PathVariable String id,
            @Valid @RequestBody CreateRoomRequest r) {
        CurrentUser actor = requireManage();
        Room result = structure.updateRoom(id, r);
        record(actor, "UPDATE", "room", id, result.getCode());
        return result;
    }

    // ----- Ngày nghỉ -----
    @GetMapping("/school-holidays")
    public List<SchoolHoliday> holidays(
            @RequestParam(required = false) String academicYearId) {
        requireRead();
        return structure.listHolidays(academicYearId);
    }

    @PostMapping("/school-holidays")
    public SchoolHoliday createHoliday(@Valid @RequestBody CreateHolidayRequest r) {
        CurrentUser actor = requireManage();
        SchoolHoliday result = structure.createHoliday(r);
        record(actor, "CREATE", "school_holiday", result.getId(), result.getName());
        return result;
    }

    @PutMapping("/school-holidays/{id}")
    public SchoolHoliday updateHoliday(
            @PathVariable String id,
            @Valid @RequestBody CreateHolidayRequest r) {
        CurrentUser actor = requireManage();
        SchoolHoliday result = structure.updateHoliday(id, r);
        record(actor, "UPDATE", "school_holiday", id, result.getName());
        return result;
    }

    @DeleteMapping("/school-holidays/{id}")
    public void deleteHoliday(@PathVariable String id) {
        CurrentUser actor = requireManage();
        structure.deleteHoliday(id);
        record(actor, "DELETE", "school_holiday", id, "Xóa ngày nghỉ");
    }

    private void requireRead() {
        CurrentUserHolder.requirePermission("ACADEMIC_STRUCTURE_READ");
    }

    private CurrentUser requireManage() {
        CurrentUserHolder.requirePermission("ACADEMIC_STRUCTURE_MANAGE");
        return CurrentUserHolder.require();
    }

    private void record(
            CurrentUser actor, String action, String entityType,
            String entityId, String detail) {
        audit.record(actor.id(), users.fullNameOf(actor.id()), actor.role(),
                action, "academic", entityType, entityId, detail);
    }
}
