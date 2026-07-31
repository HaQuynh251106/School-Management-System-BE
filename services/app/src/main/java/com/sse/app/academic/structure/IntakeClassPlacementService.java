package com.sse.app.academic.structure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.identity.User;
import com.sse.app.identity.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.sse.app.academic.structure.IntakePlacementDtos.*;

@Service
@RequiredArgsConstructor
public class IntakeClassPlacementService {
    private final AcademicYearRepository years;
    private final SchoolClassRepository classes;
    private final ClassEnrollmentRepository enrollments;
    private final UserRepository users;
    private final StructureService structure;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public List<Candidate> candidates(String academicYearId, String gradeLevel) {
        requireScope(academicYearId, gradeLevel);
        Map<String, SchoolClass> targetYearClasses = classes.findByAcademicYearId(academicYearId).stream()
                .collect(Collectors.toMap(SchoolClass::getId, Function.identity()));
        return users.findByRole("STUDENT").stream()
                .filter(user -> "ACTIVE".equals(user.getStatus()))
                .filter(user -> isWaitingForPlacement(user, academicYearId, targetYearClasses))
                .sorted(Comparator.comparing((User user) -> safe(user.getFullName()), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(User::getId))
                .map(user -> candidateOf(user, false))
                .toList();
    }

    public PreviewResponse preview(PreviewRequest request) {
        requireScope(request.academicYearId(), request.gradeLevel());
        if (request.maxStudentsPerClass() < 1) throw ApiException.badRequest("Sĩ số tối đa phải lớn hơn 0");

        List<User> candidateUsers = candidateUsers(request.academicYearId());
        Map<String, LockedPlacement> locks = normalizeLocks(request.lockedPlacements(), candidateUsers);
        List<SchoolClass> existing = classes.findByAcademicYearId(request.academicYearId()).stream()
                .filter(item -> request.gradeLevel().equalsIgnoreCase(item.getGradeLevel()))
                .sorted(Comparator.comparing(SchoolClass::getCode, String.CASE_INSENSITIVE_ORDER))
                .toList();

        int existingStudents = existing.stream().mapToInt(item -> activeStudents(item.getId()).size()).sum();
        int required = Math.max(1, (int) Math.ceil((existingStudents + candidateUsers.size())
                / (double) request.maxStudentsPerClass()));
        int desired = request.desiredClassCount() > 0 ? request.desiredClassCount() : required;
        int targetCount = Math.max(existing.size(), request.autoCreateClasses() ? Math.max(required, desired) : existing.size());

        List<Bucket> buckets = new ArrayList<>();
        existing.forEach(item -> buckets.add(existingBucket(item, request.maxStudentsPerClass())));
        Set<String> codes = existing.stream().map(item -> item.getCode().toUpperCase(Locale.ROOT)).collect(Collectors.toSet());
        int nextNumber = nextClassNumber(codes, request.gradeLevel());
        while (buckets.size() < targetCount) {
            String code;
            do code = gradePrefix(request.gradeLevel()) + "A" + nextNumber++; while (codes.contains(code));
            codes.add(code);
            buckets.add(new Bucket("new:" + code, code, true, request.maxStudentsPerClass()));
        }

        Map<String, Bucket> byCode = buckets.stream().collect(Collectors.toMap(
                item -> item.code.toUpperCase(Locale.ROOT), Function.identity()));
        List<User> unassigned = new ArrayList<>();
        Set<String> handled = new HashSet<>();

        locks.values().forEach(lock -> {
            User student = candidateUsers.stream().filter(item -> item.getId().equals(lock.studentId())).findFirst().orElse(null);
            Bucket bucket = byCode.get(lock.classCode().trim().toUpperCase(Locale.ROOT));
            if (student == null || bucket == null || bucket.total() >= bucket.capacity) {
                if (student != null) unassigned.add(student);
                return;
            }
            bucket.add(student, true);
            handled.add(student.getId());
        });

        candidateUsers.stream().filter(item -> !handled.contains(item.getId()))
                .sorted(Comparator.comparing((User item) -> genderOrder(item.getGender()))
                        .thenComparing(item -> safe(item.getFullName()), String.CASE_INSENSITIVE_ORDER))
                .forEach(student -> {
                    Bucket bucket = buckets.stream().filter(item -> item.total() < item.capacity)
                            .min(Comparator.comparingInt((Bucket item) -> placementScore(item, student, request.balanceGender()))
                                    .thenComparing(item -> item.code))
                            .orElse(null);
                    if (bucket == null) unassigned.add(student); else bucket.add(student, false);
                });

        List<String> warnings = new ArrayList<>();
        if (candidateUsers.isEmpty()) warnings.add("Không có học sinh đầu cấp nào đang chờ phân lớp trong năm học này.");
        if (!request.autoCreateClasses() && existing.size() < required) {
            warnings.add("Cần thêm " + (required - existing.size()) + " lớp để không vượt sĩ số đã chọn.");
        }
        long newClassCount = buckets.stream().filter(item -> item.newClass).count();
        if (newClassCount > 0) {
            warnings.add("Sau khi xác nhận, hãy bổ sung phòng học và giáo viên chủ nhiệm cho " + newClassCount + " lớp mới.");
        }
        if (!unassigned.isEmpty()) warnings.add("Còn " + unassigned.size() + " học sinh chưa có chỗ phù hợp.");
        buckets.stream().filter(item -> item.total() > item.capacity).forEach(item ->
                warnings.add("Lớp " + item.code + " đang vượt sĩ số tối đa."));

        List<ClassPlan> plans = buckets.stream().map(Bucket::toPlan).toList();
        int assigned = plans.stream().mapToInt(ClassPlan::assignedStudents).sum();
        return new PreviewResponse(request.academicYearId(), request.gradeLevel(), candidateUsers.size(), required,
                existing.size(), (int) buckets.stream().filter(item -> item.newClass).count(), assigned,
                unassigned.size(), plans, unassigned.stream().map(item -> candidateOf(item, locks.containsKey(item.getId()))).toList(), warnings);
    }

    @Transactional
    public ApplyResponse apply(PreviewRequest request, String actorId) {
        PreviewResponse plan = preview(request);
        if (plan.candidateCount() == 0) throw ApiException.conflict("Không có học sinh đầu cấp đang chờ phân lớp");
        if (plan.unassignedCount() > 0) throw ApiException.conflict("Vẫn còn học sinh chưa được xếp lớp. Hãy tăng số lớp hoặc sĩ số tối đa.");

        String runId = Ids.gen("placement");
        Map<String, String> actualClassIds = new HashMap<>();
        List<String> createdIds = new ArrayList<>();
        List<String> createdCodes = new ArrayList<>();
        String shift = "AFTERNOON".equalsIgnoreCase(request.defaultStudyShift()) ? "AFTERNOON" : "MORNING";

        for (ClassPlan classPlan : plan.classes()) {
            if (!classPlan.newClass()) {
                actualClassIds.put(classPlan.classId(), classPlan.classId());
                continue;
            }
            SchoolClass created = structure.createClass(new StructureDtos.CreateClassRequest(
                    null, classPlan.classCode(), "Lớp " + classPlan.classCode(), request.gradeLevel(),
                    request.academicYearId(), null, shift, request.maxStudentsPerClass(), null));
            actualClassIds.put(classPlan.classId(), created.getId());
            createdIds.add(created.getId());
            createdCodes.add(created.getCode());
        }

        Map<String, User> candidateMap = candidateUsers(request.academicYearId()).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        Map<String, LockedPlacement> locks = normalizeLocks(request.lockedPlacements(), new ArrayList<>(candidateMap.values()));
        jdbc.update("insert into class_placement_runs(id,academic_year_id,grade_level,status,assigned_count,created_class_ids,configuration_json,created_at,created_by) values (?,?,?,?,?,?,?,?,?)",
                runId, request.academicYearId(), request.gradeLevel(), "APPLIED", 0,
                String.join(",", createdIds), json(request), Timestamp.from(Instant.now()), actorId);
        int assigned = 0;
        Set<String> touchedClasses = new HashSet<>();
        for (ClassPlan classPlan : plan.classes()) {
            String classId = actualClassIds.get(classPlan.classId());
            SchoolClass target = classes.findById(classId).orElseThrow(() -> ApiException.notFound("Lớp"));
            for (Candidate candidate : classPlan.students()) {
                User student = candidateMap.get(candidate.id());
                if (student == null) throw ApiException.conflict("Danh sách học sinh đã thay đổi. Vui lòng tạo lại bản xem trước.");
                String previousClassId = student.getClassId();
                student.setClassId(target.getId());
                student.setClassName(target.getCode());
                student.setCohortId(target.getCohortId());
                student.setStudentStatus("ENROLLED");
                student.setGraduatedAt(null);
                student.setGraduationAcademicYearId(null);
                student.setGraduationClassId(null);
                users.save(student);
                structure.recordEnrollment(student.getId(), target.getId());
                jdbc.update("insert into class_placement_items(id,run_id,student_id,previous_class_id,assigned_class_id,locked,created_at) values (?,?,?,?,?,?,?)",
                        Ids.gen("placement-item"), runId, student.getId(), previousClassId, target.getId(), locks.containsKey(student.getId()), Timestamp.from(Instant.now()));
                if (previousClassId != null) touchedClasses.add(previousClassId);
                touchedClasses.add(target.getId());
                assigned++;
            }
        }

        touchedClasses.forEach(this::refreshClassCount);
        jdbc.update("update class_placement_runs set assigned_count=? where id=?", assigned, runId);
        return new ApplyResponse(runId, assigned, createdIds.size(), createdCodes,
                plan.classes().stream().map(item -> new ClassPlan(actualClassIds.get(item.classId()), item.classCode(), item.newClass(),
                        item.capacity(), item.existingStudents(), item.assignedStudents(), item.maleCount(), item.femaleCount(),
                        item.otherCount(), item.students())).toList());
    }

    @Transactional
    public UndoResponse undoLast(UndoRequest request, String actorId) {
        RunRow run = jdbc.query("select id,created_class_ids from class_placement_runs where academic_year_id=? and grade_level=? and status='APPLIED' order by created_at desc limit 1",
                (rs, rowNum) -> new RunRow(rs.getString("id"), rs.getString("created_class_ids")),
                request.academicYearId(), request.gradeLevel()).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Lần phân lớp có thể hoàn tác"));
        List<ItemRow> items = jdbc.query("select student_id,previous_class_id,assigned_class_id from class_placement_items where run_id=? order by created_at desc",
                (rs, rowNum) -> new ItemRow(rs.getString("student_id"), rs.getString("previous_class_id"), rs.getString("assigned_class_id")), run.id());
        boolean changedAfterPlacement = items.stream().anyMatch(item -> users.findById(item.studentId())
                .map(student -> !Objects.equals(student.getClassId(), item.assignedClassId())).orElse(false));
        if (changedAfterPlacement) {
            throw ApiException.conflict("Không thể hoàn tác vì một số học sinh đã được chuyển lớp sau lần phân lớp này.");
        }
        Set<String> touched = new HashSet<>();
        for (ItemRow item : items) {
            User student = users.findById(item.studentId()).orElse(null);
            if (student == null) continue;
            jdbc.update("update class_enrollments set status='ROLLED_BACK', ended_at=? where student_id=? and class_id=? and status='ACTIVE'",
                    Timestamp.from(Instant.now()), student.getId(), item.assignedClassId());
            if (item.previousClassId() == null || !classes.existsById(item.previousClassId())) {
                student.setClassId(null);
                student.setClassName(null);
            } else {
                SchoolClass previous = classes.findById(item.previousClassId()).orElseThrow();
                student.setClassId(previous.getId());
                student.setClassName(previous.getCode());
                structure.recordEnrollment(student.getId(), previous.getId());
                touched.add(previous.getId());
            }
            users.save(student);
            touched.add(item.assignedClassId());
        }
        touched.forEach(this::refreshClassCount);
        int removed = 0;
        for (String id : splitIds(run.createdClassIds())) {
            if (classes.existsById(id) && users.countByClassIdAndRole(id, "STUDENT") == 0) {
                classes.deleteById(id);
                removed++;
            }
        }
        jdbc.update("update class_placement_runs set status='ROLLED_BACK',rolled_back_at=?,rolled_back_by=? where id=?",
                Timestamp.from(Instant.now()), actorId, run.id());
        return new UndoResponse(run.id(), items.size(), removed);
    }

    public List<RunSummary> history(String academicYearId, String gradeLevel) {
        return jdbc.query("select id,academic_year_id,grade_level,status,assigned_count,created_at,created_by from class_placement_runs where academic_year_id=? and grade_level=? order by created_at desc limit 10",
                (rs, rowNum) -> new RunSummary(rs.getString("id"), rs.getString("academic_year_id"),
                        rs.getString("grade_level"), rs.getString("status"), rs.getInt("assigned_count"),
                        rs.getTimestamp("created_at").toInstant().toString(), rs.getString("created_by")), academicYearId, gradeLevel);
    }

    private List<User> candidateUsers(String academicYearId) {
        Map<String, SchoolClass> yearClasses = classes.findByAcademicYearId(academicYearId).stream()
                .collect(Collectors.toMap(SchoolClass::getId, Function.identity()));
        return users.findByRole("STUDENT").stream().filter(item -> "ACTIVE".equals(item.getStatus()))
                .filter(item -> isWaitingForPlacement(item, academicYearId, yearClasses)).toList();
    }

    private boolean isWaitingForPlacement(User user, String academicYearId, Map<String, SchoolClass> yearClasses) {
        if (user.getClassId() != null && !user.getClassId().isBlank()) return false;
        return enrollments.findByStudentIdAndStatus(user.getId(), "ACTIVE").stream()
                .noneMatch(item -> academicYearId.equals(item.getAcademicYearId()) || yearClasses.containsKey(item.getClassId()));
    }

    private Map<String, LockedPlacement> normalizeLocks(List<LockedPlacement> source, List<User> candidates) {
        Set<String> ids = candidates.stream().map(User::getId).collect(Collectors.toSet());
        Map<String, LockedPlacement> result = new LinkedHashMap<>();
        if (source != null) source.stream().filter(Objects::nonNull)
                .filter(item -> ids.contains(item.studentId()) && item.classCode() != null && !item.classCode().isBlank())
                .forEach(item -> result.put(item.studentId(), item));
        return result;
    }

    private Bucket existingBucket(SchoolClass schoolClass, int configuredCapacity) {
        Bucket bucket = new Bucket(schoolClass.getId(), schoolClass.getCode(), false,
                Math.max(schoolClass.getStudentCount(), Math.min(configuredCapacity,
                        schoolClass.getCapacity() > 0 ? schoolClass.getCapacity() : configuredCapacity)));
        activeStudents(schoolClass.getId()).forEach(bucket::addExisting);
        return bucket;
    }

    private List<User> activeStudents(String classId) {
        return users.findByClassId(classId).stream().filter(item -> "STUDENT".equals(item.getRole()) && "ACTIVE".equals(item.getStatus())).toList();
    }

    private int placementScore(Bucket bucket, User student, boolean balanceGender) {
        int total = bucket.total() * 10_000;
        if (!balanceGender) return total;
        int male = bucket.male + (isMale(student.getGender()) ? 1 : 0);
        int female = bucket.female + (isFemale(student.getGender()) ? 1 : 0);
        return total + Math.abs(male - female) * 100;
    }

    private Candidate candidateOf(User user, boolean locked) {
        return new Candidate(user.getId(), user.getStudentCode(), user.getFullName(), user.getGender(), user.getClassId(), locked);
    }

    private void refreshClassCount(String classId) {
        classes.findById(classId).ifPresent(item -> {
            item.setStudentCount((int) users.countByClassIdAndRole(classId, "STUDENT"));
            classes.save(item);
        });
    }

    private void requireScope(String academicYearId, String gradeLevel) {
        if (!years.existsById(academicYearId)) throw ApiException.notFound("Năm học");
        if (gradeLevel == null || gradeLevel.isBlank()) throw ApiException.badRequest("Vui lòng chọn khối cần phân lớp");
    }

    private String json(PreviewRequest request) {
        try { return objectMapper.writeValueAsString(request); }
        catch (JsonProcessingException ignored) { return "{}"; }
    }

    private static int nextClassNumber(Set<String> codes, String gradeLevel) {
        String prefix = gradePrefix(gradeLevel) + "A";
        return codes.stream().filter(code -> code.startsWith(prefix)).map(code -> code.substring(prefix.length()))
                .mapToInt(value -> { try { return Integer.parseInt(value); } catch (NumberFormatException ignored) { return 0; } })
                .max().orElse(0) + 1;
    }

    private static String gradePrefix(String gradeLevel) {
        String digits = gradeLevel == null ? "" : gradeLevel.replaceAll("\\D", "");
        return digits.isBlank() ? "10" : digits;
    }

    private static int genderOrder(String gender) { return isFemale(gender) ? 0 : isMale(gender) ? 1 : 2; }
    private static boolean isMale(String gender) { return "MALE".equalsIgnoreCase(gender) || "NAM".equalsIgnoreCase(gender); }
    private static boolean isFemale(String gender) { return "FEMALE".equalsIgnoreCase(gender) || "NỮ".equalsIgnoreCase(gender) || "NU".equalsIgnoreCase(gender); }
    private static String safe(String value) { return value == null ? "" : value; }
    private static List<String> splitIds(String value) { return value == null || value.isBlank() ? List.of() : Arrays.stream(value.split(",")).filter(item -> !item.isBlank()).toList(); }

    private record RunRow(String id, String createdClassIds) {}
    private record ItemRow(String studentId, String previousClassId, String assignedClassId) {}

    private final class Bucket {
        private final String id;
        private final String code;
        private final boolean newClass;
        private final int capacity;
        private int existing;
        private int male;
        private int female;
        private int other;
        private final List<Candidate> assigned = new ArrayList<>();

        private Bucket(String id, String code, boolean newClass, int capacity) {
            this.id = id; this.code = code; this.newClass = newClass; this.capacity = capacity;
        }
        private void addExisting(User user) { existing++; countGender(user.getGender()); }
        private void add(User user, boolean locked) { assigned.add(candidateOf(user, locked)); countGender(user.getGender()); }
        private void countGender(String gender) { if (isMale(gender)) male++; else if (isFemale(gender)) female++; else other++; }
        private int total() { return existing + assigned.size(); }
        private ClassPlan toPlan() { return new ClassPlan(id, code, newClass, capacity, existing, assigned.size(), male, female, other, List.copyOf(assigned)); }
    }
}
