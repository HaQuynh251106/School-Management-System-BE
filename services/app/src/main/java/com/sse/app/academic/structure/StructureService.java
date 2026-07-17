package com.sse.app.academic.structure;

import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.academic.structure.StructureDtos.*;
import com.sse.app.identity.User;
import com.sse.app.identity.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** A2: Quản trị cơ cấu đào tạo. Là điểm truy cập chéo-domain cho academic.structure. */
@Service
public class StructureService {

    private final AcademicYearRepository years;
    private final SemesterRepository semesters;
    private final SchoolClassRepository classes;
    private final SubjectRepository subjects;
    private final RoomRepository rooms;
    private final SchoolHolidayRepository holidays;
    private final UserRepository users;

    public StructureService(AcademicYearRepository years, SemesterRepository semesters,
                            SchoolClassRepository classes, SubjectRepository subjects,
                            RoomRepository rooms, SchoolHolidayRepository holidays,
                            UserRepository users) {
        this.years = years;
        this.semesters = semesters;
        this.classes = classes;
        this.subjects = subjects;
        this.rooms = rooms;
        this.holidays = holidays;
        this.users = users;
    }

    // ---------- Năm học ----------
    public List<AcademicYear> listYears() { return years.findAll(); }

    public AcademicYear createYear(CreateAcademicYearRequest r) {
        String code = normalizeYearCode(r.code());
        if (years.findByCodeIgnoreCase(code).isPresent()) {
            throw ApiException.conflict("Mã năm học đã tồn tại");
        }
        validateDateRange(r.startDate(), r.endDate(), "Năm học");
        String status = normalizeStatus(r.status(), Set.of("PLANNED", "ACTIVE", "CLOSED"), "Trạng thái năm học", "PLANNED");
        if ("ACTIVE".equals(status) && years.findAll().stream()
                .anyMatch(year -> "ACTIVE".equalsIgnoreCase(year.getStatus()))) {
            throw ApiException.conflict("Đã có năm học đang hoạt động; hãy tạo năm mới ở trạng thái PLANNED");
        }
        return years.save(AcademicYear.builder()
                .id(orGen(r.id(), "ay")).code(code)
                .name(blankToDefault(r.name(), "Năm học " + code))
                .startDate(r.startDate()).endDate(r.endDate())
                .status(status).build());
    }

    // ---------- Học kỳ ----------
    public List<Semester> listSemesters(String academicYearId) {
        return academicYearId == null ? semesters.findAll() : semesters.findByAcademicYearId(academicYearId);
    }

    public Semester createSemester(CreateSemesterRequest r) {
        AcademicYear year = requireYear(r.academicYearId());
        String code = r.code().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("HK1", "HK2").contains(code)) {
            throw ApiException.badRequest("Mã học kỳ phải là HK1 hoặc HK2");
        }
        if (semesters.findByAcademicYearIdAndCodeIgnoreCase(year.getId(), code).isPresent()) {
            throw ApiException.conflict("Học kỳ này đã tồn tại trong năm học đã chọn");
        }
        int sequence = "HK1".equals(code) ? 1 : 2;
        if (r.sequence() != null && r.sequence() != sequence) {
            throw ApiException.badRequest("Thứ tự " + code + " phải là " + sequence);
        }
        validateDateRange(r.startDate(), r.endDate(), "Học kỳ");
        validateWithinYear(r.startDate(), r.endDate(), year);
        return semesters.save(Semester.builder()
                .id(orGen(r.id(), "sm")).academicYearId(year.getId()).code(code)
                .name(blankToDefault(r.name(), "Học kỳ " + sequence))
                .sequence(sequence)
                .startDate(r.startDate()).endDate(r.endDate())
                .status(normalizeStatus(r.status(), Set.of("PLANNED", "ACTIVE", "CLOSED"), "Trạng thái học kỳ", "PLANNED")).build());
    }

    // ---------- Lớp ----------
    public List<SchoolClass> listClasses(String academicYearId, String gradeLevel) {
        List<SchoolClass> result;
        if (academicYearId != null) result = classes.findByAcademicYearId(academicYearId);
        else if (gradeLevel != null) result = classes.findByGradeLevel(gradeLevel);
        else result = classes.findAll();
        return result.stream().sorted(classDisplayOrder()).toList();
    }

    public SchoolClass getClass(String id) {
        return classes.findById(id).orElseThrow(() -> ApiException.notFound("Lớp"));
    }

    public SchoolClass createClass(CreateClassRequest r) {
        AcademicYear year = requireYear(r.academicYearId());
        String code = normalizeClassCode(r.code());
        String gradeLevel = normalizeGradeLevel(r.gradeLevel());
        validateHighSchoolClass(code, gradeLevel);
        if (classes.findByAcademicYearIdAndCode(year.getId(), code).isPresent()) {
            throw ApiException.conflict("Mã lớp đã tồn tại trong năm học đã chọn");
        }
        return classes.save(SchoolClass.builder()
                .id(orGen(r.id(), "c")).code(code)
                .name(blankToDefault(r.name(), "Lớp " + code))
                .gradeLevel(gradeLevel).academicYearId(year.getId())
                .homeroomTeacherId(validHomeroomTeacherId(r.homeroomTeacherId())).studentCount(0).build());
    }

    @Transactional
    public SchoolClass assignHomeroomTeacher(String classId, String teacherId) {
        SchoolClass schoolClass = getClass(classId);
        schoolClass.setHomeroomTeacherId(validHomeroomTeacherId(teacherId));
        return classes.save(schoolClass);
    }

    public SchoolClass getClassByCode(String code) {
        AcademicYear activeYear = activeOrCreateDefaultYear();
        return classes.findByAcademicYearIdAndCode(activeYear.getId(), normalizeClassCode(code))
                .orElseThrow(() -> ApiException.notFound("Lá»›p"));
    }

    @Transactional
    public SchoolClass ensureClassByCode(String code) {
        String normalized = normalizeClassCode(code);
        if (!isHighSchoolClassCode(normalized)) {
            throw ApiException.badRequest("Class code must be from 10A1 to 12A10");
        }
        AcademicYear year = activeOrCreateDefaultYear();
        ensureHighSchoolDefaults(year.getId());
        return classes.findByAcademicYearIdAndCode(year.getId(), normalized)
                .orElseThrow(() -> ApiException.notFound("Lá»›p"));
    }

    @Transactional
    public int ensureHighSchoolDefaults() {
        return ensureHighSchoolDefaults(null);
    }

    @Transactional
    public int ensureHighSchoolDefaults(String academicYearId) {
        AcademicYear year = academicYearId == null || academicYearId.isBlank()
                ? activeOrCreateDefaultYear() : requireYear(academicYearId);
        ensureTwoSemesters(year);

        int created = 0;
        for (int grade = 10; grade <= 12; grade++) {
            for (int no = 1; no <= 10; no++) {
                String code = grade + "A" + no;
                if (classes.findByAcademicYearIdAndCode(year.getId(), code).isEmpty()) {
                    classes.save(SchoolClass.builder()
                            .id(Ids.gen("c"))
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

    /** Keeps 10A1..10A10, 11A1..11A10 and 12A1..12A10 stable after any class update. */
    private Comparator<SchoolClass> classDisplayOrder() {
        Map<String, LocalDate> yearStarts = years.findAll().stream()
                .collect(Collectors.toMap(AcademicYear::getId, AcademicYear::getStartDate, (first, ignored) -> first));
        return Comparator
                .comparing((SchoolClass schoolClass) -> yearStarts.get(schoolClass.getAcademicYearId()),
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparingInt(this::classGradeNumber)
                .thenComparingInt(this::classSectionNumber)
                .thenComparing(SchoolClass::getCode, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
    }

    private int classGradeNumber(SchoolClass schoolClass) {
        String grade = schoolClass.getGradeLevel();
        if (grade != null && grade.matches("K(10|11|12)")) return Integer.parseInt(grade.substring(1));
        String code = schoolClass.getCode() == null ? "" : schoolClass.getCode().toUpperCase(Locale.ROOT);
        int separator = code.indexOf('A');
        try {
            return separator > 0 ? Integer.parseInt(code.substring(0, separator)) : Integer.MAX_VALUE;
        } catch (NumberFormatException ignored) {
            return Integer.MAX_VALUE;
        }
    }

    private int classSectionNumber(SchoolClass schoolClass) {
        String code = schoolClass.getCode() == null ? "" : schoolClass.getCode().toUpperCase(Locale.ROOT);
        int separator = code.indexOf('A');
        try {
            return separator >= 0 ? Integer.parseInt(code.substring(separator + 1)) : Integer.MAX_VALUE;
        } catch (NumberFormatException ignored) {
            return Integer.MAX_VALUE;
        }
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
                    .status("ACTIVE".equalsIgnoreCase(year.getStatus()) ? "ACTIVE" : "PLANNED")
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

    private String normalizeYearCode(String code) {
        String normalized = code == null ? "" : code.trim();
        if (!normalized.matches("\\d{4}-\\d{4}")) {
            throw ApiException.badRequest("Mã năm học phải có dạng YYYY-YYYY, ví dụ 2026-2027");
        }
        int start = Integer.parseInt(normalized.substring(0, 4));
        int end = Integer.parseInt(normalized.substring(5, 9));
        if (end != start + 1) {
            throw ApiException.badRequest("Năm kết thúc phải liền sau năm bắt đầu");
        }
        return normalized;
    }

    private String normalizeGradeLevel(String gradeLevel) {
        String raw = gradeLevel == null ? "" : gradeLevel.trim().toUpperCase(Locale.ROOT);
        String normalized = raw.matches("10|11|12") ? "K" + raw : raw;
        if (!Set.of("K10", "K11", "K12").contains(normalized)) {
            throw ApiException.badRequest("Khối chỉ được là K10, K11 hoặc K12");
        }
        return normalized;
    }

    private void validateHighSchoolClass(String code, String gradeLevel) {
        if (!isHighSchoolClassCode(code)) {
            throw ApiException.badRequest("Mã lớp phải từ 10A1 đến 12A10");
        }
        if (!("K" + code.substring(0, 2)).equals(gradeLevel)) {
            throw ApiException.badRequest("Mã lớp không khớp với khối đã chọn");
        }
    }

    private AcademicYear requireYear(String academicYearId) {
        if (academicYearId == null || academicYearId.isBlank()) {
            throw ApiException.badRequest("Phải chọn năm học");
        }
        return years.findById(academicYearId).orElseThrow(() -> ApiException.notFound("Năm học"));
    }

    private String validHomeroomTeacherId(String teacherId) {
        if (teacherId == null || teacherId.isBlank()) return null;
        User teacher = users.findById(teacherId).orElseThrow(() -> ApiException.notFound("Giáo viên"));
        if (!"TEACHER".equals(teacher.getRole()) || !"ACTIVE".equals(teacher.getStatus())) {
            throw ApiException.badRequest("GVCN phải là tài khoản giáo viên đang hoạt động");
        }
        return teacher.getId();
    }

    private void validateDateRange(LocalDate start, LocalDate end, String label) {
        if (start == null || end == null) {
            throw ApiException.badRequest(label + " phải có ngày bắt đầu và ngày kết thúc");
        }
        if (!start.isBefore(end)) {
            throw ApiException.badRequest("Ngày kết thúc " + label.toLowerCase(Locale.ROOT) + " phải sau ngày bắt đầu");
        }
    }

    private void validateWithinYear(LocalDate start, LocalDate end, AcademicYear year) {
        if ((year.getStartDate() != null && start.isBefore(year.getStartDate()))
                || (year.getEndDate() != null && end.isAfter(year.getEndDate()))) {
            throw ApiException.badRequest("Khoảng thời gian học kỳ phải thuộc năm học đã chọn");
        }
    }

    private String normalizeStatus(String value, Set<String> accepted, String label, String defaultValue) {
        String normalized = value == null || value.isBlank() ? defaultValue : value.trim().toUpperCase(Locale.ROOT);
        if (!accepted.contains(normalized)) {
            throw ApiException.badRequest(label + " không hợp lệ");
        }
        return normalized;
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }
}
