package com.sse.app.academic.structure;

import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.academic.structure.StructureDtos.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** A2: Quản trị cơ cấu đào tạo. Là điểm truy cập chéo-domain cho academic.structure. */
@Service
public class StructureService {

    private final AcademicYearRepository years;
    private final SemesterRepository semesters;
    private final SchoolClassRepository classes;
    private final SubjectRepository subjects;
    private final RoomRepository rooms;
    private final SchoolHolidayRepository holidays;

    public StructureService(AcademicYearRepository years, SemesterRepository semesters,
                            SchoolClassRepository classes, SubjectRepository subjects,
                            RoomRepository rooms, SchoolHolidayRepository holidays) {
        this.years = years;
        this.semesters = semesters;
        this.classes = classes;
        this.subjects = subjects;
        this.rooms = rooms;
        this.holidays = holidays;
    }

    // ---------- Năm học ----------
    public List<AcademicYear> listYears() { return years.findAll(); }

    public AcademicYear createYear(CreateAcademicYearRequest r) {
        return years.save(AcademicYear.builder()
                .id(orGen(r.id(), "ay")).code(r.code()).name(r.name())
                .startDate(r.startDate()).endDate(r.endDate())
                .status(r.status() == null ? "PLANNED" : r.status()).build());
    }

    // ---------- Học kỳ ----------
    public List<Semester> listSemesters(String academicYearId) {
        return academicYearId == null ? semesters.findAll() : semesters.findByAcademicYearId(academicYearId);
    }

    public Semester createSemester(CreateSemesterRequest r) {
        return semesters.save(Semester.builder()
                .id(orGen(r.id(), "sm")).academicYearId(r.academicYearId()).code(r.code()).name(r.name())
                .sequence(r.sequence() == null ? 1 : r.sequence())
                .startDate(r.startDate()).endDate(r.endDate())
                .status(r.status() == null ? "PLANNED" : r.status()).build());
    }

    // ---------- Lớp ----------
    public List<SchoolClass> listClasses(String academicYearId, String gradeLevel) {
        if (academicYearId != null) return classes.findByAcademicYearId(academicYearId);
        if (gradeLevel != null)     return classes.findByGradeLevel(gradeLevel);
        return classes.findAll();
    }

    public SchoolClass getClass(String id) {
        return classes.findById(id).orElseThrow(() -> ApiException.notFound("Lớp"));
    }

    public SchoolClass createClass(CreateClassRequest r) {
        return classes.save(SchoolClass.builder()
                .id(orGen(r.id(), "c")).code(r.code())
                .name(r.name() == null ? "Lớp " + r.code() : r.name())
                .gradeLevel(r.gradeLevel()).academicYearId(r.academicYearId())
                .studentCount(0).build());
    }

    @Transactional
    public SchoolClass assignHomeroomTeacher(String classId, String teacherId,
                                             String teacherName, String assignedBy) {
        SchoolClass schoolClass = getClass(classId);
        schoolClass.setHomeroomTeacherId(teacherId);
        schoolClass.setHomeroomTeacherName(teacherName);
        schoolClass.setHomeroomAssignedAt(Instant.now());
        schoolClass.setHomeroomAssignedBy(assignedBy);
        return classes.save(schoolClass);
    }

    @Transactional
    public SchoolClass clearHomeroomTeacher(String classId) {
        SchoolClass schoolClass = getClass(classId);
        schoolClass.setHomeroomTeacherId(null);
        schoolClass.setHomeroomTeacherName(null);
        schoolClass.setHomeroomAssignedAt(null);
        schoolClass.setHomeroomAssignedBy(null);
        return classes.save(schoolClass);
    }

    public List<SchoolClass> classesOfHomeroom(String teacherId) {
        return classes.findByHomeroomTeacherId(teacherId);
    }

    // ---------- Môn ----------
    public List<Subject> listSubjects() { return subjects.findAll(); }

    public Subject createSubject(CreateSubjectRequest r) {
        return subjects.save(Subject.builder().id(orGen(r.id(), "sj")).code(r.code()).name(r.name()).build());
    }

    public String subjectName(String subjectId) {
        if (subjectId == null) return null;
        return subjects.findById(subjectId).map(Subject::getName).orElse(null);
    }

    public String requireSubjectName(String subjectId) {
        return subjects.findById(subjectId).map(Subject::getName)
                .orElseThrow(() -> ApiException.notFound("Môn học"));
    }

    public void assertSemesterExists(String semesterId) {
        if (!semesters.existsById(semesterId)) throw ApiException.notFound("Học kỳ");
    }

    /** Cross-domain (finance): bậc khối của lớp, null-safe. */
    public String gradeLevelOf(String classId) {
        if (classId == null) return null;
        return classes.findById(classId).map(SchoolClass::getGradeLevel).orElse(null);
    }

    // ---------- Phòng ----------
    public List<Room> listRooms() { return rooms.findAll(); }

    public Room createRoom(CreateRoomRequest r) {
        return rooms.save(Room.builder().id(orGen(r.id(), "rm")).code(r.code())
                .name(r.name()).capacity(r.capacity()).build());
    }

    // ---------- Ngày nghỉ ----------
    public List<SchoolHoliday> listHolidays() { return holidays.findAll(); }

    public SchoolHoliday createHoliday(CreateHolidayRequest r) {
        return holidays.save(SchoolHoliday.builder().id(orGen(r.id(), "hol"))
                .date(r.date()).name(r.name()).description(r.description()).build());
    }

    public void deleteHoliday(String id) {
        if (!holidays.existsById(id)) throw ApiException.notFound("Ngày nghỉ");
        holidays.deleteById(id);
    }

    public boolean isHoliday(java.time.LocalDate date) {
        return holidays.findAll().stream().anyMatch(h -> date.equals(h.getDate()));
    }

    // ---------- Seed (raw insert, dùng bởi DataSeeder) ----------
    public void seedAll(List<AcademicYear> y, List<Semester> s, List<SchoolClass> c,
                        List<Subject> sj, List<Room> rm, List<SchoolHoliday> h) {
        years.saveAll(y); semesters.saveAll(s); classes.saveAll(c);
        subjects.saveAll(sj); rooms.saveAll(rm); holidays.saveAll(h);
    }

    // ---------- Helper ----------
    private String orGen(String id, String prefix) {
        return (id == null || id.isBlank()) ? Ids.gen(prefix) : id;
    }
}
