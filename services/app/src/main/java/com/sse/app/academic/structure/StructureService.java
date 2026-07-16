package com.sse.app.academic.structure;

import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.academic.structure.StructureDtos.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** A2: Quản trị cơ cấu đào tạo. Là điểm truy cập chéo-domain cho academic.structure. */
@Service
public class StructureService {

    private final AcademicYearRepository years;
    private final SemesterRepository semesters;
    private final SchoolClassRepository classes;
    private final SubjectRepository subjects;
    private final RoomRepository rooms;
    private final SchoolHolidayRepository holidays;
    private final ClassEnrollmentRepository enrollments;

    public StructureService(AcademicYearRepository years, SemesterRepository semesters,
                            SchoolClassRepository classes, SubjectRepository subjects,
                            RoomRepository rooms, SchoolHolidayRepository holidays,
                            ClassEnrollmentRepository enrollments) {
        this.years = years;
        this.semesters = semesters;
        this.classes = classes;
        this.subjects = subjects;
        this.rooms = rooms;
        this.holidays = holidays;
        this.enrollments = enrollments;
    }

    // ---------- Năm học ----------
    public List<AcademicYear> listYears() { return years.findAll(); }

    public AcademicYear getYear(String id) {
        return years.findById(id).orElseThrow(() -> ApiException.notFound("Năm học"));
    }

    public AcademicYear createYear(CreateAcademicYearRequest r) {
        if (years.findByCode(r.code()).isPresent()) throw ApiException.conflict("Mã năm học đã tồn tại");
        if (r.startDate() != null && r.endDate() != null && r.endDate().isBefore(r.startDate())) {
            throw ApiException.badRequest("Ngày kết thúc năm học phải sau ngày bắt đầu");
        }
        return years.save(AcademicYear.builder()
                .id(orGen(r.id(), "ay")).code(r.code()).name(r.name())
                .startDate(r.startDate()).endDate(r.endDate())
                .status(r.status() == null ? "PLANNED" : r.status()).build());
    }

    // ---------- Học kỳ ----------
    public List<Semester> listSemesters(String academicYearId) {
        return academicYearId == null ? semesters.findAll() : semesters.findByAcademicYearId(academicYearId);
    }

    public Semester getSemester(String id) {
        return semesters.findById(id).orElseThrow(() -> ApiException.notFound("Học kỳ"));
    }

    public Semester createSemester(CreateSemesterRequest r) {
        if (!years.existsById(r.academicYearId())) throw ApiException.notFound("Năm học");
        if (r.startDate() != null && r.endDate() != null && r.endDate().isBefore(r.startDate())) {
            throw ApiException.badRequest("Ngày kết thúc học kỳ phải sau ngày bắt đầu");
        }
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
        if (classes.findByCode(r.code()).isPresent()) throw ApiException.conflict("Mã lớp đã tồn tại");
        if (r.academicYearId() != null && !years.existsById(r.academicYearId())) {
            throw ApiException.notFound("Năm học");
        }
        return classes.save(SchoolClass.builder()
                .id(orGen(r.id(), "c")).code(r.code())
                .name(r.name() == null ? "Lớp " + r.code() : r.name())
                .gradeLevel(r.gradeLevel()).academicYearId(r.academicYearId())
                .capacity(r.capacity() == null ? 45 : r.capacity())
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

    @Transactional
    public ClassEnrollment recordEnrollment(String studentId, String classId) {
        SchoolClass schoolClass = getClass(classId);
        String yearId = schoolClass.getAcademicYearId();
        ClassEnrollment enrollment = enrollments.findByAcademicYearIdAndClassIdAndStudentId(yearId, classId, studentId)
                .orElseGet(() -> ClassEnrollment.builder().id(Ids.gen("ce")).studentId(studentId)
                        .classId(classId).academicYearId(yearId).enrolledAt(Instant.now()).build());
        List<ClassEnrollment> active = enrollments.findByStudentIdAndStatus(studentId, "ACTIVE");
        active.stream().filter(item -> !item.getId().equals(enrollment.getId())).forEach(item -> {
            item.setStatus("TRANSFERRED");
            item.setEndedAt(Instant.now());
        });
        enrollments.saveAll(active);
        enrollment.setStatus("ACTIVE");
        enrollment.setEndedAt(null);
        return enrollments.save(enrollment);
    }

    public List<ClassEnrollment> enrollmentHistory(String studentId) {
        return enrollments.findAll().stream().filter(item -> studentId.equals(item.getStudentId()))
                .sorted(Comparator.comparing(ClassEnrollment::getEnrolledAt).reversed()).toList();
    }

    // ---------- Môn ----------
    public List<Subject> listSubjects() { return subjects.findAll(); }

    public Subject createSubject(CreateSubjectRequest r) {
        return subjects.save(Subject.builder().id(orGen(r.id(), "sj")).code(r.code()).name(r.name())
                .coefficient(validCoefficient(r.coefficient())).build());
    }

    public Subject updateSubject(String id, UpdateSubjectRequest r) {
        Subject subject = subjects.findById(id).orElseThrow(() -> ApiException.notFound("Môn học"));
        subject.setName(r.name());
        subject.setCoefficient(validCoefficient(r.coefficient()));
        return subjects.save(subject);
    }

    public String subjectName(String subjectId) {
        if (subjectId == null) return null;
        return subjects.findById(subjectId).map(Subject::getName).orElse(null);
    }

    public String requireSubjectName(String subjectId) {
        return subjects.findById(subjectId).map(Subject::getName)
                .orElseThrow(() -> ApiException.notFound("Môn học"));
    }

    public double subjectCoefficient(String subjectId) {
        return subjects.findById(subjectId).map(Subject::getCoefficient).orElse(1.0);
    }

    public List<String> semesterIdsOfYear(String academicYearId) {
        return semesters.findByAcademicYearId(academicYearId).stream().map(Semester::getId).toList();
    }

    public Optional<SchoolClass> classByCode(String code) {
        return code == null || code.isBlank() ? Optional.empty() : classes.findByCode(code.trim());
    }

    public Optional<SchoolClass> findNextClass(String academicYearId, SchoolClass currentClass) {
        AcademicYear current = years.findById(academicYearId).orElseThrow(() -> ApiException.notFound("Năm học"));
        int nextGrade = parseGrade(currentClass.getGradeLevel()) + 1;
        if (nextGrade > 12) return Optional.empty();
        Optional<AcademicYear> nextYear = years.findAll().stream()
                .filter(y -> y.getStartDate() != null && current.getStartDate() != null
                        && y.getStartDate().isAfter(current.getStartDate()))
                .min(Comparator.comparing(AcademicYear::getStartDate));
        if (nextYear.isEmpty()) return Optional.empty();
        String wanted = "K" + nextGrade;
        String section = currentClass.getCode() == null ? "" : currentClass.getCode().replaceFirst("^\\d+", "");
        String expectedCode = nextGrade + section;
        return classes.findByAcademicYearId(nextYear.get().getId()).stream()
                .filter(c -> wanted.equalsIgnoreCase(c.getGradeLevel())
                        || String.valueOf(nextGrade).equalsIgnoreCase(c.getGradeLevel()))
                .filter(c -> expectedCode.equalsIgnoreCase(c.getCode()))
                .findFirst();
    }

    public AcademicYear closeYear(String id) {
        AcademicYear year = years.findById(id).orElseThrow(() -> ApiException.notFound("Năm học"));
        year.setStatus("CLOSED");
        return years.save(year);
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

    private double validCoefficient(Double coefficient) {
        double value = coefficient == null ? 1.0 : coefficient;
        if (!Double.isFinite(value) || value <= 0 || value > 10) {
            throw ApiException.badRequest("Hệ số môn phải lớn hơn 0 và không vượt quá 10");
        }
        return value;
    }

    private int parseGrade(String gradeLevel) {
        if (gradeLevel == null) return 0;
        try { return Integer.parseInt(gradeLevel.replaceAll("\\D", "")); }
        catch (NumberFormatException ignored) { return 0; }
    }
}
