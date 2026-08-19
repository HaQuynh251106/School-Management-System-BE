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
    private final GradeLevelRepository gradeLevels;
    private final SemesterRepository semesters;
    private final SchoolClassRepository classes;
    private final SubjectRepository subjects;
    private final RoomRepository rooms;
    private final SchoolHolidayRepository holidays;
    private final UserRepository users;

    public StructureService(AcademicYearRepository years, GradeLevelRepository gradeLevels,
                            SemesterRepository semesters,
                            SchoolClassRepository classes, SubjectRepository subjects,
                            RoomRepository rooms, SchoolHolidayRepository holidays,
                            UserRepository users) {
        this.years = years;
        this.gradeLevels = gradeLevels;
        this.semesters = semesters;
        this.classes = classes;
        this.subjects = subjects;
        this.rooms = rooms;
        this.holidays = holidays;
        this.users = users;
    }

    // ---------- Năm học ----------
    public List<AcademicYear> listYears() { return years.findAll(); }

    public AcademicYear getYear(String id) {
        return requireYear(id);
    }

    public List<GradeLevel> listGradeLevels() {
        return gradeLevels.findByActiveTrueOrderByDisplayOrder();
    }

    @Transactional
    public AcademicYear createYear(CreateAcademicYearRequest r) {
        String code = normalizeYearCode(r.code());
        if (years.findByCodeIgnoreCase(code).isPresent()) {
            throw ApiException.conflict("Mã năm học đã tồn tại");
        }
        AcademicCalendar calendar = academicCalendar(code);
        String status = normalizeStatus(r.status(), Set.of("PLANNED", "ACTIVE", "CLOSED"), "Trạng thái năm học", "PLANNED");
        if ("ACTIVE".equals(status)) {
            closeOtherActiveYears(null);
        }
        AcademicYear year = years.save(AcademicYear.builder()
                .id(orGen(r.id(), "ay")).code(code)
                .name(blankToDefault(r.name(), "Năm học " + code))
                .startDate(calendar.yearStart()).endDate(calendar.yearEnd())
                .status(status).build());
        ensureTwoSemesters(year);
        return year;
    }

    @Transactional
    public AcademicYear updateYear(String id, UpdateAcademicYearRequest r) {
        AcademicYear year = requireYear(id);
        String code = normalizeYearCode(r.code());
        years.findByCodeIgnoreCase(code)
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw ApiException.conflict("Mã năm học đã tồn tại");
                });
        AcademicCalendar calendar = academicCalendar(code);
        year.setCode(code);
        year.setName(blankToDefault(r.name(), "Năm học " + code));
        year.setStartDate(calendar.yearStart());
        year.setEndDate(calendar.yearEnd());
        if (r.status() != null) updateYearStatus(id, r.status());
        AcademicYear saved = years.save(year);
        ensureTwoSemesters(saved);
        return saved;
    }

    @Transactional
    public AcademicYear updateYearStatus(String academicYearId, String requestedStatus) {
        AcademicYear year = requireYear(academicYearId);
        String status = normalizeStatus(requestedStatus, Set.of("ACTIVE", "CLOSED"),
                "Trạng thái năm học", null);
        if ("ACTIVE".equals(status)) {
            closeOtherActiveYears(year.getId());
        }
        year.setStatus(status);
        AcademicYear saved = years.save(year);
        syncSemesterStatuses(saved);
        return saved;
    }

    // ---------- Học kỳ ----------
    public List<Semester> listSemesters(String academicYearId) {
        return academicYearId == null ? semesters.findAll() : semesters.findByAcademicYearId(academicYearId);
    }

    public Semester getSemester(String id) {
        return semesters.findById(id).orElseThrow(() -> ApiException.notFound("Học kỳ"));
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

    @Transactional
    public Semester updateSemester(String id, UpdateSemesterRequest r) {
        Semester semester = getSemester(id);
        AcademicYear year = requireYear(semester.getAcademicYearId());
        String code = r.code().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("HK1", "HK2").contains(code)) {
            throw ApiException.badRequest("Mã học kỳ phải là HK1 hoặc HK2");
        }
        int sequence = "HK1".equals(code) ? 1 : 2;
        semesters.findByAcademicYearIdAndCodeIgnoreCase(year.getId(), code)
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw ApiException.conflict("Học kỳ này đã tồn tại");
                });
        validateDateRange(r.startDate(), r.endDate(), "Học kỳ");
        validateWithinYear(r.startDate(), r.endDate(), year);
        semester.setCode(code);
        semester.setName(blankToDefault(r.name(), "Học kỳ " + sequence));
        semester.setSequence(sequence);
        semester.setStartDate(r.startDate());
        semester.setEndDate(r.endDate());
        semester.setStatus(normalizeStatus(
                r.status(), Set.of("PLANNED", "ACTIVE", "CLOSED"),
                "Trạng thái học kỳ", semester.getStatus()));
        return semesters.save(semester);
    }

    // ---------- Lớp ----------
    public List<SchoolClass> listClasses(String academicYearId, String gradeLevel) {
        List<SchoolClass> result;
        if (academicYearId != null) result = classes.findByAcademicYearId(academicYearId);
        else if (gradeLevel != null) result = classes.findByGradeLevel(gradeLevel);
        else result = classes.findAll();
        return result.stream()
                .filter(item -> gradeLevel == null || gradeLevel.isBlank()
                        || gradeLevel.equalsIgnoreCase(item.getGradeLevel()))
                .sorted(classDisplayOrder()).toList();
    }

    public SchoolClass getClass(String id) {
        return classes.findById(id).orElseThrow(() -> ApiException.notFound("Lớp"));
    }

    public SchoolClass createClass(CreateClassRequest r) {
        AcademicYear year = requireYear(r.academicYearId());
        String code = normalizeClassCode(r.code());
        String gradeLevel = normalizeGradeLevel(r.gradeLevel());
        String homeroomTeacherId = validHomeroomTeacherId(r.homeroomTeacherId());
        validateHighSchoolClass(code, gradeLevel);
        if (classes.findByAcademicYearIdAndCode(year.getId(), code).isPresent()) {
            throw ApiException.conflict("Mã lớp đã tồn tại trong năm học đã chọn");
        }
        ensureTeacherAvailableForHomeroom(year.getId(), null, homeroomTeacherId);
        Integer capacity = normalizeClassCapacity(r.maxStudents());
        Room homeRoom = r.homeRoomId() == null || r.homeRoomId().isBlank()
                ? ensureHomeRoom(year, code, capacity)
                : requireAvailableHomeRoom(r.homeRoomId(), null, 0);
        return classes.save(SchoolClass.builder()
                .id(orGen(r.id(), "c")).code(code)
                .name(blankToDefault(r.name(), "Lớp " + code))
                .gradeLevel(gradeLevel).academicYearId(year.getId())
                .homeroomTeacherId(homeroomTeacherId)
                .homeRoomId(homeRoom.getId())
                .studentCount(0)
                .maxStudents(capacity)
                .status("ACTIVE")
                .build());
    }

    public SchoolClass findClass(String academicYearId, String code) {
        return classes.findByAcademicYearIdAndCode(academicYearId, normalizeClassCode(code))
                .orElse(null);
    }

    @Transactional
    public SchoolClass setClassStatus(String classId, String status) {
        SchoolClass schoolClass = getClass(classId);
        schoolClass.setStatus(normalizeStatus(status, Set.of("ACTIVE", "INACTIVE"),
                "Trạng thái lớp", schoolClass.getStatus()));
        return classes.save(schoolClass);
    }

    @Transactional
    public SchoolClass updateClass(String id, UpdateClassRequest r) {
        SchoolClass schoolClass = getClass(id);
        String code = normalizeClassCode(r.code());
        String gradeLevel = normalizeGradeLevel(r.gradeLevel());
        validateHighSchoolClass(code, gradeLevel);
        classes.findByAcademicYearIdAndCode(schoolClass.getAcademicYearId(), code)
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw ApiException.conflict("Mã lớp đã tồn tại trong năm học");
                });
        schoolClass.setCode(code);
        schoolClass.setName(blankToDefault(r.name(), "Lớp " + code));
        schoolClass.setGradeLevel(gradeLevel);
        schoolClass.setMaxStudents(normalizeClassCapacity(r.maxStudents()));
        if (r.homeRoomId() != null && !r.homeRoomId().isBlank()) {
            schoolClass.setHomeRoomId(requireAvailableHomeRoom(
                    r.homeRoomId(), schoolClass.getId(), schoolClass.getStudentCount()).getId());
        }
        String teacherId = validHomeroomTeacherId(r.homeroomTeacherId());
        ensureTeacherAvailableForHomeroom(
                schoolClass.getAcademicYearId(), schoolClass.getId(), teacherId);
        schoolClass.setHomeroomTeacherId(teacherId);
        return classes.save(schoolClass);
    }

    @Transactional
    public SchoolClass assignHomeRoom(String classId, String roomId) {
        SchoolClass schoolClass = getClass(classId);
        Room room = requireAvailableHomeRoom(
                roomId, schoolClass.getId(), schoolClass.getStudentCount());
        schoolClass.setHomeRoomId(room.getId());
        return classes.save(schoolClass);
    }

    @Transactional
    public SchoolClass assignHomeroomTeacher(String classId, String teacherId) {
        SchoolClass schoolClass = getClass(classId);
        String normalizedTeacherId = validHomeroomTeacherId(teacherId);
        ensureTeacherAvailableForHomeroom(
                schoolClass.getAcademicYearId(), schoolClass.getId(), normalizedTeacherId);
        schoolClass.setHomeroomTeacherId(normalizedTeacherId);
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
            throw ApiException.badRequest(
                    "Mã lớp phải thuộc khối 10-12 và có số thứ tự từ A1 đến A20");
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
                Room homeRoom = ensureHomeRoom(year, code, 45);
                if (classes.findByAcademicYearIdAndCode(year.getId(), code).isEmpty()) {
                    classes.save(SchoolClass.builder()
                            .id(Ids.gen("c"))
                            .code(code)
                            .name("Lop " + code)
                            .gradeLevel("K" + grade)
                            .academicYearId(year.getId())
                            .homeRoomId(homeRoom.getId())
                            .studentCount(0)
                            .maxStudents(45)
                            .build());
                    created++;
                } else {
                    classes.findByAcademicYearIdAndCode(year.getId(), code)
                            .filter(item -> item.getHomeRoomId() == null)
                            .ifPresent(item -> {
                                item.setHomeRoomId(homeRoom.getId());
                                classes.save(item);
                            });
                }
            }
        }
        return created;
    }

    private Room ensureHomeRoom(AcademicYear year, String classCode, int capacity) {
        String roomCode = "G0-" + year.getCode().replace("-", "") + "-" + classCode;
        return rooms.findByCodeIgnoreCase(roomCode).orElseGet(() -> rooms.save(Room.builder()
                .id(Ids.gen("room")).code(roomCode)
                .name("Phòng lớp " + classCode)
                .capacity(Math.max(40, capacity))
                .roomType("GENERAL").active(true).build()));
    }

    private Room requireAvailableHomeRoom(String roomId, String classId, int studentCount) {
        Room room = getRoom(roomId);
        if (!room.isActive()) {
            throw ApiException.badRequest("Phòng học cố định đã ngừng hoạt động");
        }
        if (!"GENERAL".equalsIgnoreCase(room.getRoomType())) {
            throw ApiException.badRequest("Phòng học cố định phải là phòng học thường");
        }
        if (room.getCapacity() != null && room.getCapacity() < studentCount) {
            throw ApiException.badRequest("Phòng học cố định không đủ sức chứa của lớp");
        }
        classes.findByHomeRoomId(room.getId())
                .filter(other -> classId == null || !classId.equals(other.getId()))
                .ifPresent(other -> {
                    throw ApiException.conflict("Phòng đã là phòng cố định của lớp " + other.getCode());
                });
        return room;
    }

    private Integer normalizeClassCapacity(Integer capacity) {
        int value = capacity == null ? 45 : capacity;
        if (value < 1 || value > 100) {
            throw ApiException.badRequest("Sức chứa lớp phải từ 1 đến 100 học sinh");
        }
        return value;
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
        String code = r.code().trim().toUpperCase(Locale.ROOT);
        if (subjects.findByCodeIgnoreCase(code).isPresent()) {
            throw ApiException.conflict("Mã môn học đã tồn tại");
        }
        return subjects.save(Subject.builder()
                .id(orGen(r.id(), "sj"))
                .code(code)
                .name(r.name().trim())
                .coefficient(normalizeCoefficient(r.coefficient()))
                .requiredRoomType(normalizeRoomType(r.requiredRoomType()))
                .subjectType(normalizeSubjectType(r.subjectType()))
                .departmentName(blankToNull(r.departmentName()))
                .assessmentMethod(r.assessmentMethod() == null || r.assessmentMethod().isBlank()
                        ? "SCORE" : r.assessmentMethod().trim().toUpperCase(Locale.ROOT))
                .facilityNote(blankToNull(r.facilityNote()))
                .active(r.active() == null || r.active())
                .build());
    }

    public Subject getSubject(String id) {
        return subjects.findById(id).orElseThrow(() -> ApiException.notFound("Môn học"));
    }

    @Transactional
    public Subject updateSubject(String id, CreateSubjectRequest r) {
        Subject subject = getSubject(id);
        String code = r.code().trim().toUpperCase(Locale.ROOT);
        subjects.findByCodeIgnoreCase(code)
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw ApiException.conflict("Mã môn học đã tồn tại");
                });
        subject.setCode(code);
        subject.setName(r.name().trim());
        subject.setCoefficient(normalizeCoefficient(r.coefficient()));
        subject.setRequiredRoomType(normalizeRoomType(r.requiredRoomType()));
        subject.setSubjectType(normalizeSubjectType(r.subjectType()));
        subject.setDepartmentName(blankToNull(r.departmentName()));
        subject.setAssessmentMethod(r.assessmentMethod() == null || r.assessmentMethod().isBlank()
                ? "SCORE" : r.assessmentMethod().trim().toUpperCase(Locale.ROOT));
        subject.setFacilityNote(blankToNull(r.facilityNote()));
        subject.setActive(r.active() == null || r.active());
        return subjects.save(subject);
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
        String code = r.code().trim().toUpperCase(Locale.ROOT);
        if (rooms.findByCodeIgnoreCase(code).isPresent()) {
            throw ApiException.conflict("Mã phòng học đã tồn tại");
        }
        return rooms.save(Room.builder().id(orGen(r.id(), "rm")).code(code)
                .name(blankToDefault(r.name(), code))
                .capacity(normalizeRoomCapacity(r.capacity()))
                .roomType(normalizeRoomType(r.roomType()))
                .active(r.active() == null || r.active())
                .build());
    }

    public Room getRoom(String id) {
        return rooms.findById(id).orElseThrow(() -> ApiException.notFound("Phòng học"));
    }

    @Transactional
    public Room updateRoom(String id, CreateRoomRequest r) {
        Room room = getRoom(id);
        String code = r.code().trim().toUpperCase(Locale.ROOT);
        rooms.findByCodeIgnoreCase(code)
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw ApiException.conflict("Mã phòng học đã tồn tại");
                });
        room.setCode(code);
        room.setName(blankToDefault(r.name(), code));
        room.setCapacity(normalizeRoomCapacity(r.capacity()));
        room.setRoomType(normalizeRoomType(r.roomType()));
        room.setActive(r.active() == null || r.active());
        return rooms.save(room);
    }

    // ---------- Ngày nghỉ ----------
    public List<SchoolHoliday> listHolidays() { return holidays.findAll(); }

    public List<SchoolHoliday> listHolidays(String academicYearId) {
        return academicYearId == null || academicYearId.isBlank()
                ? holidays.findAll()
                : holidays.findByAcademicYearIdOrderByDate(academicYearId);
    }

    public SchoolHoliday createHoliday(CreateHolidayRequest r) {
        AcademicYear year = requireYear(r.academicYearId());
        LocalDate endDate = r.endDate() == null ? r.date() : r.endDate();
        validateHolidayDates(r.date(), endDate, year);
        validateHolidayOverlap(year.getId(), r.date(), endDate, null);
        return holidays.save(SchoolHoliday.builder().id(orGen(r.id(), "hol"))
                .academicYearId(year.getId())
                .date(r.date()).endDate(endDate)
                .name(r.name().trim()).description(r.description()).build());
    }

    public SchoolHoliday updateHoliday(String id, CreateHolidayRequest r) {
        SchoolHoliday holiday = holidays.findById(id)
                .orElseThrow(() -> ApiException.notFound("Ngày nghỉ"));
        String academicYearId = r.academicYearId() == null || r.academicYearId().isBlank()
                ? holiday.getAcademicYearId() : r.academicYearId();
        AcademicYear year = requireYear(academicYearId);
        LocalDate endDate = r.endDate() == null ? r.date() : r.endDate();
        validateHolidayDates(r.date(), endDate, year);
        validateHolidayOverlap(year.getId(), r.date(), endDate, id);
        holiday.setAcademicYearId(year.getId());
        holiday.setDate(r.date());
        holiday.setEndDate(endDate);
        holiday.setName(r.name().trim());
        holiday.setDescription(r.description());
        return holidays.save(holiday);
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

    /** Keeps classes ordered by academic year, grade and numeric A-section. */
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
                            .startDate(LocalDate.of(startYear, 9, 1))
                            .endDate(LocalDate.of(startYear + 1, 6, 30))
                            .status("ACTIVE")
                            .build());
                });
    }

    private void ensureTwoSemesters(AcademicYear year) {
        List<Semester> existing = semesters.findByAcademicYearId(year.getId());
        AcademicCalendar calendar = academicCalendar(year.getCode());
        String status = semesterStatusForYear(year);
        Semester hk1 = existing.stream()
                .filter(semester -> "HK1".equalsIgnoreCase(semester.getCode()))
                .findFirst()
                .orElseGet(() -> Semester.builder()
                        .id(Ids.gen("sm"))
                        .academicYearId(year.getId())
                        .code("HK1")
                        .build());
        hk1.setName("Học kỳ 1");
        hk1.setSequence(1);
        hk1.setStartDate(calendar.hk1Start());
        hk1.setEndDate(calendar.hk1End());
        hk1.setStatus(status);
        semesters.save(hk1);

        Semester hk2 = existing.stream()
                .filter(semester -> "HK2".equalsIgnoreCase(semester.getCode()))
                .findFirst()
                .orElseGet(() -> Semester.builder()
                        .id(Ids.gen("sm"))
                        .academicYearId(year.getId())
                        .code("HK2")
                        .build());
        hk2.setName("Học kỳ 2");
        hk2.setSequence(2);
        hk2.setStartDate(calendar.hk2Start());
        hk2.setEndDate(calendar.hk2End());
        hk2.setStatus(status);
        semesters.save(hk2);
    }

    private void closeOtherActiveYears(String targetYearId) {
        years.findAll().stream()
                .filter(year -> "ACTIVE".equalsIgnoreCase(year.getStatus()))
                .filter(year -> targetYearId == null || !year.getId().equals(targetYearId))
                .forEach(year -> {
                    year.setStatus("CLOSED");
                    years.save(year);
                    syncSemesterStatuses(year);
                });
    }

    private void syncSemesterStatuses(AcademicYear year) {
        String status = semesterStatusForYear(year);
        semesters.findByAcademicYearId(year.getId()).forEach(semester -> {
            semester.setStatus(status);
            semesters.save(semester);
        });
    }

    private String semesterStatusForYear(AcademicYear year) {
        return switch (year.getStatus().toUpperCase(Locale.ROOT)) {
            case "ACTIVE" -> "ACTIVE";
            case "CLOSED" -> "CLOSED";
            default -> "PLANNED";
        };
    }

    private AcademicCalendar academicCalendar(String code) {
        int startYear = Integer.parseInt(code.substring(0, 4));
        return new AcademicCalendar(
                LocalDate.of(startYear, 9, 1),
                LocalDate.of(startYear + 1, 6, 30),
                LocalDate.of(startYear, 9, 1),
                LocalDate.of(startYear + 1, 1, 31),
                LocalDate.of(startYear + 1, 2, 1),
                LocalDate.of(startYear + 1, 6, 30));
    }

    private record AcademicCalendar(
            LocalDate yearStart,
            LocalDate yearEnd,
            LocalDate hk1Start,
            LocalDate hk1End,
            LocalDate hk2Start,
            LocalDate hk2End) {}

    private boolean isHighSchoolClassCode(String code) {
        return code != null && code.matches("(10|11|12)A([1-9]|1[0-9]|20)");
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
            throw ApiException.badRequest(
                    "Mã lớp phải thuộc khối 10-12 và có số thứ tự từ A1 đến A20");
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

    private Integer normalizeRoomCapacity(Integer capacity) {
        int value = capacity == null ? 45 : capacity;
        if (value < 1 || value > 500) {
            throw ApiException.badRequest("Sức chứa phòng phải từ 1 đến 500");
        }
        return value;
    }

    private String normalizeRoomType(String value) {
        if (value == null || value.isBlank()) return "GENERAL";
        String type = value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("GENERAL", "LAB", "COMPUTER", "GYM", "MUSIC", "ART")
                .contains(type)) {
            throw ApiException.badRequest("Loại phòng không hợp lệ");
        }
        return type;
    }

    private String normalizeSubjectType(String value) {
        if (value == null || value.isBlank()) return "MANDATORY";
        String type = value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("MANDATORY", "OPTIONAL", "SPECIALIZED", "EDUCATIONAL_ACTIVITY")
                .contains(type)) {
            throw ApiException.badRequest("Loại môn học không hợp lệ");
        }
        return type;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private double normalizeCoefficient(Double coefficient) {
        double value = coefficient == null ? 1 : coefficient;
        if (value < 0.5 || value > 10) {
            throw ApiException.badRequest("Hệ số môn phải từ 0,5 đến 10");
        }
        return value;
    }

    private void validateHolidayDates(LocalDate start, LocalDate end, AcademicYear year) {
        if (start == null || end == null || end.isBefore(start)) {
            throw ApiException.badRequest("Khoảng ngày nghỉ không hợp lệ");
        }
        if ((year.getStartDate() != null && start.isBefore(year.getStartDate()))
                || (year.getEndDate() != null && end.isAfter(year.getEndDate()))) {
            throw ApiException.badRequest("Ngày nghỉ phải nằm trong năm học đã chọn");
        }
    }

    private void validateHolidayOverlap(String academicYearId, LocalDate start,
                                        LocalDate end, String excludedId) {
        boolean overlaps = holidays.findByAcademicYearIdOrderByDate(academicYearId).stream()
                .filter(item -> excludedId == null || !excludedId.equals(item.getId()))
                .anyMatch(item -> {
                    LocalDate existingEnd = item.getEndDate() == null ? item.getDate() : item.getEndDate();
                    return !end.isBefore(item.getDate()) && !start.isAfter(existingEnd);
                });
        if (overlaps) {
            throw ApiException.conflict("Khoảng ngày nghỉ bị trùng với một ngày nghỉ đã có");
        }
    }

    private void ensureTeacherAvailableForHomeroom(
            String academicYearId, String currentClassId, String teacherId) {
        if (teacherId == null) return;
        classes.findByAcademicYearIdAndHomeroomTeacherId(academicYearId, teacherId)
                .filter(other -> !other.getId().equals(currentClassId))
                .ifPresent(other -> {
                    throw ApiException.conflict(
                            "Giáo viên đã là GVCN lớp " + other.getCode()
                                    + " trong năm học này");
                });
    }
}
