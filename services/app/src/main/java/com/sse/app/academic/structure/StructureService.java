package com.sse.app.academic.structure;

import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.academic.structure.StructureDtos.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** A2: Quản trị cơ cấu đào tạo. Là điểm truy cập chéo-domain cho academic.structure. */
@Service
public class StructureService {

    private final AcademicYearRepository years;
    private final SemesterRepository semesters;
    private final SchoolClassRepository classes;
    private final SubjectRepository subjects;
    private final RoomRepository rooms;
    private final ClassEnrollmentRepository enrollments;
    private final JdbcTemplate jdbc;

    public StructureService(AcademicYearRepository years, SemesterRepository semesters,
                            SchoolClassRepository classes, SubjectRepository subjects,
                            RoomRepository rooms,
                            ClassEnrollmentRepository enrollments, JdbcTemplate jdbc) {
        this.years = years;
        this.semesters = semesters;
        this.classes = classes;
        this.subjects = subjects;
        this.rooms = rooms;
        this.enrollments = enrollments;
        this.jdbc = jdbc;
    }

    // ---------- Năm học ----------
    public List<AcademicYear> listYears() {
        return years.findAll().stream()
                .sorted(Comparator.comparing(AcademicYear::getStartDate,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    public AcademicYear getYear(String id) {
        return years.findById(id).orElseThrow(() -> ApiException.notFound("Năm học"));
    }

    @Transactional
    public AcademicYear createYear(CreateAcademicYearRequest r) {
        String code = normalizeCode(r.code());
        if (years.findByCode(code).isPresent()) throw ApiException.conflict("Mã năm học đã tồn tại");
        validatePeriod(r.startDate(), r.endDate(), "năm học");
        assertYearPeriodAvailable(null, r.startDate(), r.endDate());
        if (r.status() != null && !"PLANNED".equals(r.status())) {
            throw ApiException.badRequest("Năm học mới phải ở trạng thái dự kiến; hãy tạo học kỳ trước khi kích hoạt");
        }
        return years.save(AcademicYear.builder()
                .id(orGen(r.id(), "ay")).code(code).name(defaultName(r.name(), code))
                .startDate(r.startDate()).endDate(r.endDate()).status("PLANNED").build());
    }

    @Transactional
    public AcademicYear updateYear(String id, UpdateAcademicYearRequest r) {
        AcademicYear year = getYear(id);
        requireNotClosed(year.getStatus(), "Năm học đã đóng không thể chỉnh sửa");
        String code = normalizeCode(r.code());
        years.findByCode(code).filter(item -> !item.getId().equals(id))
                .ifPresent(item -> { throw ApiException.conflict("Mã năm học đã tồn tại"); });
        validatePeriod(r.startDate(), r.endDate(), "năm học");
        assertYearPeriodAvailable(id, r.startDate(), r.endDate());
        for (Semester semester : semesters.findByAcademicYearId(id)) {
            if (!within(semester.getStartDate(), semester.getEndDate(), r.startDate(), r.endDate())) {
                throw ApiException.badRequest("Thời gian năm học phải bao phủ toàn bộ các học kỳ hiện có");
            }
        }
        year.setCode(code);
        year.setName(defaultName(r.name(), code));
        year.setStartDate(r.startDate());
        year.setEndDate(r.endDate());
        return years.save(year);
    }

    @Transactional
    public AcademicYear changeYearStatus(String id, String requestedStatus) {
        AcademicYear year = getYear(id);
        String target = normalizeStatus(requestedStatus);
        if (target.equals(year.getStatus())) return year;
        if ("CLOSED".equals(year.getStatus())) {
            throw ApiException.badRequest("Năm học đã đóng không thể mở lại");
        }
        if ("ACTIVE".equals(target)) {
            if (!"PLANNED".equals(year.getStatus())) throw ApiException.badRequest("Chỉ năm học dự kiến mới có thể kích hoạt");
            if (semesters.findByAcademicYearId(id).isEmpty()) {
                throw ApiException.badRequest("Cần tạo ít nhất một học kỳ trước khi kích hoạt năm học");
            }
            for (AcademicYear active : years.findByStatus("ACTIVE")) {
                if (!active.getId().equals(id)) {
                    active.setStatus("CLOSED");
                    closeSemestersOfYear(active.getId());
                    years.save(active);
                }
            }
            year.setStatus("ACTIVE");
        } else if ("CLOSED".equals(target)) {
            year.setStatus("CLOSED");
            closeSemestersOfYear(id);
        } else {
            throw ApiException.badRequest("Không thể chuyển năm học về trạng thái dự kiến");
        }
        return years.save(year);
    }

    @Transactional
    public void deleteYear(String id) {
        AcademicYear year = getYear(id);
        requirePlanned(year.getStatus(), "Chỉ được xóa năm học đang ở trạng thái dự kiến");
        if (!semesters.findByAcademicYearId(id).isEmpty() || !classes.findByAcademicYearId(id).isEmpty()
                || hasReference(id, "fee_periods", "academic_year_id")
                || hasReference(id, "class_enrollments", "academic_year_id")
                || hasReference(id, "student_yearly_summaries", "academic_year_id")) {
            throw ApiException.badRequest("Không thể xóa năm học đang có học kỳ, lớp hoặc dữ liệu nghiệp vụ");
        }
        years.delete(year);
    }

    // ---------- Học kỳ ----------
    public List<Semester> listSemesters(String academicYearId) {
        List<Semester> result = academicYearId == null ? semesters.findAll() : semesters.findByAcademicYearId(academicYearId);
        return result.stream().sorted(Comparator.comparing(Semester::getAcademicYearId)
                .thenComparingInt(Semester::getSequence)).toList();
    }

    public Semester getSemester(String id) {
        return semesters.findById(id).orElseThrow(() -> ApiException.notFound("Học kỳ"));
    }

    @Transactional
    public Semester createSemester(CreateSemesterRequest r) {
        AcademicYear year = getYear(r.academicYearId());
        requireNotClosed(year.getStatus(), "Không thể thêm học kỳ vào năm học đã đóng");
        String code = normalizeCode(r.code());
        int sequence = r.sequence() == null ? 1 : r.sequence();
        validateSemester(null, year, code, sequence, r.startDate(), r.endDate());
        if (r.status() != null && !"PLANNED".equals(r.status())) {
            throw ApiException.badRequest("Học kỳ mới phải ở trạng thái dự kiến");
        }
        return semesters.save(Semester.builder()
                .id(orGen(r.id(), "sm")).academicYearId(year.getId()).code(code)
                .name(defaultName(r.name(), code)).sequence(sequence)
                .startDate(r.startDate()).endDate(r.endDate()).status("PLANNED").build());
    }

    @Transactional
    public Semester updateSemester(String id, UpdateSemesterRequest r) {
        Semester semester = getSemester(id);
        requirePlanned(semester.getStatus(), "Chỉ học kỳ dự kiến mới có thể chỉnh sửa");
        AcademicYear year = getYear(r.academicYearId());
        requireNotClosed(year.getStatus(), "Không thể chuyển học kỳ vào năm học đã đóng");
        String code = normalizeCode(r.code());
        int sequence = r.sequence() == null ? 1 : r.sequence();
        validateSemester(id, year, code, sequence, r.startDate(), r.endDate());
        semester.setAcademicYearId(year.getId());
        semester.setCode(code);
        semester.setName(defaultName(r.name(), code));
        semester.setSequence(sequence);
        semester.setStartDate(r.startDate());
        semester.setEndDate(r.endDate());
        return semesters.save(semester);
    }

    @Transactional
    public Semester changeSemesterStatus(String id, String requestedStatus) {
        Semester semester = getSemester(id);
        String target = normalizeStatus(requestedStatus);
        if (target.equals(semester.getStatus())) return semester;
        if ("CLOSED".equals(semester.getStatus())) throw ApiException.badRequest("Học kỳ đã đóng không thể mở lại");
        if ("ACTIVE".equals(target)) {
            AcademicYear year = getYear(semester.getAcademicYearId());
            if (!"ACTIVE".equals(year.getStatus())) {
                throw ApiException.badRequest("Phải kích hoạt năm học trước khi kích hoạt học kỳ");
            }
            for (Semester active : semesters.findByStatus("ACTIVE")) {
                if (!active.getId().equals(id)) {
                    active.setStatus("CLOSED");
                    semesters.save(active);
                }
            }
            semester.setStatus("ACTIVE");
        } else if ("CLOSED".equals(target)) {
            semester.setStatus("CLOSED");
        } else {
            throw ApiException.badRequest("Không thể chuyển học kỳ về trạng thái dự kiến");
        }
        return semesters.save(semester);
    }

    @Transactional
    public void deleteSemester(String id) {
        Semester semester = getSemester(id);
        requirePlanned(semester.getStatus(), "Chỉ được xóa học kỳ đang ở trạng thái dự kiến");
        if (hasReference(id, "grades", "semester_id") || hasReference(id, "timetable_slots", "semester_id")
                || hasReference(id, "teaching_assignments", "semester_id")) {
            throw ApiException.badRequest("Không thể xóa học kỳ đang có điểm, thời khóa biểu hoặc phân công giảng dạy");
        }
        semesters.delete(semester);
    }

    // ---------- Lớp ----------
    public List<SchoolClass> listClasses(String academicYearId, String gradeLevel) {
        List<SchoolClass> result;
        if (academicYearId != null) result = classes.findByAcademicYearId(academicYearId);
        else if (gradeLevel != null) result = classes.findByGradeLevel(gradeLevel);
        else result = classes.findAll();
        return result.stream().sorted(Comparator.comparing(SchoolClass::getCode)).toList();
    }

    public SchoolClass getClass(String id) {
        return classes.findById(id).orElseThrow(() -> ApiException.notFound("Lớp"));
    }

    @Transactional
    public SchoolClass createClass(CreateClassRequest r) {
        String code = normalizeCode(r.code());
        if (classes.findByCode(code).isPresent()) throw ApiException.conflict("Mã lớp đã tồn tại");
        AcademicYear year = getYear(r.academicYearId());
        requireNotClosed(year.getStatus(), "Không thể tạo lớp trong năm học đã đóng");
        return classes.save(SchoolClass.builder()
                .id(orGen(r.id(), "c")).code(code)
                .name(defaultName(r.name(), "Lớp " + code))
                .gradeLevel(normalizeCode(r.gradeLevel())).academicYearId(year.getId())
                .capacity(r.capacity() == null ? 45 : r.capacity())
                .studentCount(0).build());
    }

    @Transactional
    public SchoolClass updateClass(String id, UpdateClassRequest r) {
        SchoolClass schoolClass = getClass(id);
        String code = normalizeCode(r.code());
        classes.findByCode(code).filter(item -> !item.getId().equals(id))
                .ifPresent(item -> { throw ApiException.conflict("Mã lớp đã tồn tại"); });
        AcademicYear year = getYear(r.academicYearId());
        requireNotClosed(year.getStatus(), "Không thể chuyển lớp vào năm học đã đóng");
        int capacity = r.capacity() == null ? 45 : r.capacity();
        long studentCount = referenceCount("users", "class_id", id);
        if (capacity < studentCount) {
            throw ApiException.badRequest("Sức chứa không thể nhỏ hơn sĩ số hiện tại (" + studentCount + ")");
        }
        schoolClass.setCode(code);
        schoolClass.setName(defaultName(r.name(), "Lớp " + code));
        schoolClass.setGradeLevel(normalizeCode(r.gradeLevel()));
        schoolClass.setAcademicYearId(year.getId());
        schoolClass.setCapacity(capacity);
        return classes.save(schoolClass);
    }

    @Transactional
    public void deleteClass(String id) {
        SchoolClass schoolClass = getClass(id);
        if (hasReference(id, "users", "class_id") || hasReference(id, "class_enrollments", "class_id")
                || hasReference(id, "timetable_slots", "class_id") || hasReference(id, "attendance_records", "class_id")
                || hasReference(id, "assignments", "class_id") || hasReference(id, "teaching_assignments", "class_id")
                || hasReference(id, "student_yearly_summaries", "class_id")
                || hasReference(id, "student_yearly_summaries", "next_class_id")) {
            throw ApiException.badRequest("Không thể xóa lớp đang có học sinh hoặc dữ liệu giảng dạy");
        }
        classes.delete(schoolClass);
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
    public List<Subject> listSubjects() {
        return subjects.findAll().stream().sorted(Comparator.comparing(Subject::getCode)).toList();
    }

    @Transactional
    public Subject createSubject(CreateSubjectRequest r) {
        String code = normalizeCode(r.code());
        if (subjects.findByCode(code).isPresent()) throw ApiException.conflict("Mã môn học đã tồn tại");
        return subjects.save(Subject.builder().id(orGen(r.id(), "sj")).code(code).name(r.name().trim())
                .coefficient(validCoefficient(r.coefficient())).build());
    }

    @Transactional
    public Subject updateSubject(String id, UpdateSubjectRequest r) {
        Subject subject = subjects.findById(id).orElseThrow(() -> ApiException.notFound("Môn học"));
        String code = r.code() == null || r.code().isBlank() ? subject.getCode() : normalizeCode(r.code());
        subjects.findByCode(code).filter(item -> !item.getId().equals(id))
                .ifPresent(item -> { throw ApiException.conflict("Mã môn học đã tồn tại"); });
        subject.setCode(code);
        subject.setName(r.name().trim());
        subject.setCoefficient(validCoefficient(r.coefficient()));
        return subjects.save(subject);
    }

    @Transactional
    public void deleteSubject(String id) {
        Subject subject = subjects.findById(id).orElseThrow(() -> ApiException.notFound("Môn học"));
        if (hasReference(id, "grades", "subject_id") || hasReference(id, "timetable_slots", "subject_id")
                || hasReference(id, "assignments", "subject_id")
                || hasReference(id, "teaching_assignments", "subject_id")) {
            throw ApiException.badRequest("Không thể xóa môn học đang có điểm, bài tập, thời khóa biểu hoặc phân công");
        }
        subjects.delete(subject);
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

    @Transactional
    public AcademicYear closeYear(String id) {
        AcademicYear year = getYear(id);
        if (!"CLOSED".equals(year.getStatus())) {
            year.setStatus("CLOSED");
            closeSemestersOfYear(id);
        }
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
    public List<Room> listRooms() {
        return rooms.findAll().stream().sorted(Comparator.comparing(Room::getCode)).toList();
    }

    @Transactional
    public Room createRoom(CreateRoomRequest r) {
        String code = normalizeCode(r.code());
        if (rooms.findByCode(code).isPresent()) throw ApiException.conflict("Mã phòng đã tồn tại");
        return rooms.save(Room.builder().id(orGen(r.id(), "rm")).code(code)
                .name(defaultName(r.name(), "Phòng " + code)).capacity(r.capacity()).build());
    }

    @Transactional
    public Room updateRoom(String id, UpdateRoomRequest r) {
        Room room = rooms.findById(id).orElseThrow(() -> ApiException.notFound("Phòng học"));
        String code = normalizeCode(r.code());
        rooms.findByCode(code).filter(item -> !item.getId().equals(id))
                .ifPresent(item -> { throw ApiException.conflict("Mã phòng đã tồn tại"); });
        if (!code.equals(room.getCode()) && hasReference(room.getCode(), "timetable_slots", "room_code")) {
            throw ApiException.badRequest("Không thể đổi mã phòng đang được sử dụng trong thời khóa biểu");
        }
        room.setCode(code);
        room.setName(defaultName(r.name(), "Phòng " + code));
        room.setCapacity(r.capacity());
        return rooms.save(room);
    }

    @Transactional
    public void deleteRoom(String id) {
        Room room = rooms.findById(id).orElseThrow(() -> ApiException.notFound("Phòng học"));
        if (hasReference(room.getCode(), "timetable_slots", "room_code")) {
            throw ApiException.badRequest("Không thể xóa phòng đang được sử dụng trong thời khóa biểu");
        }
        rooms.delete(room);
    }

    // ---------- Seed (raw insert, dùng bởi DataSeeder) ----------
    public void seedAll(List<AcademicYear> y, List<Semester> s, List<SchoolClass> c,
                        List<Subject> sj, List<Room> rm) {
        years.saveAll(y); semesters.saveAll(s); classes.saveAll(c);
        subjects.saveAll(sj); rooms.saveAll(rm);
    }

    // ---------- Helper ----------
    private void validateSemester(String currentId, AcademicYear year, String code, int sequence,
                                  LocalDate startDate, LocalDate endDate) {
        validatePeriod(startDate, endDate, "học kỳ");
        if (!within(startDate, endDate, year.getStartDate(), year.getEndDate())) {
            throw ApiException.badRequest("Thời gian học kỳ phải nằm trong thời gian năm học");
        }
        semesters.findByAcademicYearIdAndCode(year.getId(), code)
                .filter(item -> currentId == null || !item.getId().equals(currentId))
                .ifPresent(item -> { throw ApiException.conflict("Mã học kỳ đã tồn tại trong năm học"); });
        semesters.findByAcademicYearIdAndSequence(year.getId(), sequence)
                .filter(item -> currentId == null || !item.getId().equals(currentId))
                .ifPresent(item -> { throw ApiException.conflict("Thứ tự học kỳ đã tồn tại trong năm học"); });
        for (Semester item : semesters.findByAcademicYearId(year.getId())) {
            if ((currentId == null || !item.getId().equals(currentId))
                    && overlaps(startDate, endDate, item.getStartDate(), item.getEndDate())) {
                throw ApiException.badRequest("Thời gian học kỳ bị trùng với " + item.getName());
            }
        }
    }

    private void validatePeriod(LocalDate startDate, LocalDate endDate, String label) {
        if (startDate == null || endDate == null) {
            throw ApiException.badRequest("Cần nhập đầy đủ ngày bắt đầu và kết thúc " + label);
        }
        if (endDate.isBefore(startDate)) {
            throw ApiException.badRequest("Ngày kết thúc " + label + " phải từ ngày bắt đầu trở đi");
        }
    }

    private void assertYearPeriodAvailable(String currentId, LocalDate startDate, LocalDate endDate) {
        for (AcademicYear item : years.findAll()) {
            if ((currentId == null || !item.getId().equals(currentId))
                    && overlaps(startDate, endDate, item.getStartDate(), item.getEndDate())) {
                throw ApiException.badRequest("Thời gian năm học bị trùng với " + item.getName());
            }
        }
    }

    private boolean within(LocalDate start, LocalDate end, LocalDate outerStart, LocalDate outerEnd) {
        return start != null && end != null && outerStart != null && outerEnd != null
                && !start.isBefore(outerStart) && !end.isAfter(outerEnd);
    }

    private boolean overlaps(LocalDate startA, LocalDate endA, LocalDate startB, LocalDate endB) {
        return startA != null && endA != null && startB != null && endB != null
                && !endA.isBefore(startB) && !endB.isBefore(startA);
    }

    private void closeSemestersOfYear(String yearId) {
        List<Semester> list = semesters.findByAcademicYearId(yearId);
        list.forEach(item -> item.setStatus("CLOSED"));
        semesters.saveAll(list);
    }

    private String normalizeStatus(String status) {
        if (status == null) throw ApiException.badRequest("Thiếu trạng thái");
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!List.of("PLANNED", "ACTIVE", "CLOSED").contains(normalized)) {
            throw ApiException.badRequest("Trạng thái không hợp lệ");
        }
        return normalized;
    }

    private String normalizeCode(String code) {
        if (code == null || code.isBlank()) throw ApiException.badRequest("Mã không được để trống");
        return code.trim().toUpperCase(Locale.ROOT);
    }

    private String defaultName(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void requirePlanned(String status, String message) {
        if (!"PLANNED".equals(status)) throw ApiException.badRequest(message);
    }

    private void requireNotClosed(String status, String message) {
        if ("CLOSED".equals(status)) throw ApiException.badRequest(message);
    }

    private boolean hasReference(Object value, String table, String column) {
        return referenceCount(table, column, value) > 0;
    }

    private long referenceCount(String table, String column, Object value) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?", Long.class, value);
        return count == null ? 0 : count;
    }

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
