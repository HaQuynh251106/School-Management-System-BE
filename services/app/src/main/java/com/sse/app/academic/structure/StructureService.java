package com.sse.app.academic.structure;

import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.academic.structure.StructureDtos.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
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
                .homeroomTeacherId(r.homeroomTeacherId()).studentCount(0).build());
    }

    public SchoolClass getClassByCode(String code) {
        return classes.findByCode(normalizeClassCode(code))
                .orElseThrow(() -> ApiException.notFound("Lá»›p"));
    }

    @Transactional
    public SchoolClass ensureClassByCode(String code) {
        String normalized = normalizeClassCode(code);
        if (!isHighSchoolClassCode(normalized)) {
            throw ApiException.badRequest("Class code must be from 10A1 to 12A10");
        }
        ensureHighSchoolDefaults();
        return classes.findByCode(normalized)
                .orElseThrow(() -> ApiException.notFound("Lá»›p"));
    }

    @Transactional
    public int ensureHighSchoolDefaults() {
        AcademicYear year = activeOrCreateDefaultYear();
        ensureTwoSemesters(year);

        int created = 0;
        for (int grade = 10; grade <= 12; grade++) {
            for (int no = 1; no <= 10; no++) {
                String code = grade + "A" + no;
                if (classes.findByCode(code).isEmpty()) {
                    classes.save(SchoolClass.builder()
                            .id("c-" + code.toLowerCase())
                            .code(code)
                            .name("Lop " + code)
                            .gradeLevel("K" + grade)
                            .academicYearId(year.getId())
                            .studentCount(0)
                            .build());
                    created++;
                }
            }
        }
        return created;
    }

    @Transactional
    public void updateClassStudentCount(String classId, int count) {
        if (classId == null || classId.isBlank()) return;
        classes.findById(classId).ifPresent(c -> {
            c.setStudentCount(count);
            classes.save(c);
        });
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

    private AcademicYear activeOrCreateDefaultYear() {
        return years.findAll().stream()
                .filter(y -> "ACTIVE".equalsIgnoreCase(y.getStatus()))
                .findFirst()
                .orElseGet(() -> {
                    int startYear = LocalDate.now().getMonthValue() >= 6
                            ? LocalDate.now().getYear()
                            : LocalDate.now().getYear() - 1;
                    String code = startYear + "-" + (startYear + 1);
                    return years.save(AcademicYear.builder()
                            .id("ay-" + startYear)
                            .code(code)
                            .name("Nam hoc " + code)
                            .startDate(LocalDate.of(startYear, 9, 5))
                            .endDate(LocalDate.of(startYear + 1, 5, 31))
                            .status("ACTIVE")
                            .build());
                });
    }

    private void ensureTwoSemesters(AcademicYear year) {
        List<Semester> existing = semesters.findByAcademicYearId(year.getId());
        boolean hasHk1 = existing.stream().anyMatch(s -> "HK1".equalsIgnoreCase(s.getCode()));
        boolean hasHk2 = existing.stream().anyMatch(s -> "HK2".equalsIgnoreCase(s.getCode()));
        int startYear = year.getStartDate() != null ? year.getStartDate().getYear() : LocalDate.now().getYear();
        if (!hasHk1) {
            semesters.save(Semester.builder()
                    .id("sm-" + startYear + "-1")
                    .academicYearId(year.getId())
                    .code("HK1")
                    .name("Hoc ky 1")
                    .sequence(1)
                    .startDate(LocalDate.of(startYear, 9, 5))
                    .endDate(LocalDate.of(startYear + 1, 1, 15))
                    .status("ACTIVE")
                    .build());
        }
        if (!hasHk2) {
            semesters.save(Semester.builder()
                    .id("sm-" + startYear + "-2")
                    .academicYearId(year.getId())
                    .code("HK2")
                    .name("Hoc ky 2")
                    .sequence(2)
                    .startDate(LocalDate.of(startYear + 1, 1, 20))
                    .endDate(LocalDate.of(startYear + 1, 5, 31))
                    .status("PLANNED")
                    .build());
        }
    }

    private boolean isHighSchoolClassCode(String code) {
        return code != null && code.matches("(10|11|12)A([1-9]|10)");
    }

    private String normalizeClassCode(String code) {
        return code == null ? null : code.trim().replace(" ", "").toUpperCase();
    }
}
