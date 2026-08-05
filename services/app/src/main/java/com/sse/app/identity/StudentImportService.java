package com.sse.app.identity;

import com.sse.app.academic.structure.SchoolClass;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class StudentImportService {

    private static final DataFormatter FORMATTER = new DataFormatter();
    private static final Map<String, String> HEADER_ALIASES = headerAliases();

    private final UserRepository users;
    private final ParentStudentRepository relations;
    private final StructureService structure;
    private final PasswordEncoder encoder;
    private final RbacService rbac;

    public StudentImportService(UserRepository users, ParentStudentRepository relations,
                                StructureService structure, PasswordEncoder encoder,
                                RbacService rbac) {
        this.users = users;
        this.relations = relations;
        this.structure = structure;
        this.encoder = encoder;
        this.rbac = rbac;
    }

    @Transactional
    public StudentImportResult importStudents(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("Excel file is required");
        }

        structure.ensureHighSchoolDefaults();
        ImportCounters counters = new ImportCounters();
        List<StudentImportRowResult> rowResults = new ArrayList<>();

        try (InputStream in = file.getInputStream(); Workbook workbook = WorkbookFactory.create(in)) {
            Sheet sheet = workbook.getNumberOfSheets() == 0 ? null : workbook.getSheetAt(0);
            if (sheet == null || sheet.getPhysicalNumberOfRows() < 2) {
                throw ApiException.badRequest("Excel must contain a header row and at least one student row");
            }

            Map<String, Integer> headers = readHeaders(sheet.getRow(0));
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (isBlankRow(row)) continue;

                counters.totalRows++;
                try {
                    RowData data = readRow(row, headers);
                    RowOutcome outcome = upsert(data);
                    counters.createdStudents += outcome.createdStudent ? 1 : 0;
                    counters.updatedStudents += outcome.updatedStudent ? 1 : 0;
                    counters.createdParents += outcome.createdParent ? 1 : 0;
                    counters.reusedParents += outcome.reusedParent ? 1 : 0;
                    counters.linkedRelations += outcome.linkedRelation ? 1 : 0;
                    rowResults.add(outcome.result);
                } catch (RuntimeException ex) {
                    counters.failedRows++;
                    rowResults.add(StudentImportRowResult.error(i + 1, cleanError(ex)));
                }
            }
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Cannot read Excel file: " + ex.getMessage());
        }

        return new StudentImportResult(
                counters.totalRows,
                counters.createdStudents,
                counters.updatedStudents,
                counters.createdParents,
                counters.reusedParents,
                counters.linkedRelations,
                counters.failedRows,
                rowResults);
    }

    private RowOutcome upsert(RowData data) {
        validate(data);

        SchoolClass schoolClass = structure.ensureClassByCode(data.classCode);
        Optional<User> existingStudent = users.findByStudentCodeIgnoreCase(data.studentCode);
        Optional<User> parentCandidate = findParent(data);
        if (existingStudent.isPresent()) {
            List<ParentStudent> existingRelations = relations.findByStudentId(existingStudent.get().getId());
            boolean parentDoesNotMatch = !existingRelations.isEmpty()
                    && parentCandidate
                    .map(parent -> existingRelations.stream().noneMatch(r -> r.getParentId().equals(parent.getId())))
                    .orElse(true);
            if (parentDoesNotMatch) {
                throw ApiException.conflict("Student already has another parent");
            }
        }
        boolean createdStudent = existingStudent.isEmpty();
        boolean updatedStudent = existingStudent.isPresent();

        User student = existingStudent.orElseGet(() -> User.builder()
                .id(Ids.gen("u"))
                .role("STUDENT")
                .status("ACTIVE")
                .passwordChangeRequired(true)
                .sessionVersion(0)
                .studentCode(data.studentCode)
                .createdAt(Instant.now())
                .build());
        String oldClassId = student.getClassId();
        String studentPassword = null;

        student.setFullName(data.studentName);
        student.setEmail(blankToNull(data.studentEmail));
        student.setPhone(blankToNull(data.studentPhone));
        student.setClassId(schoolClass.getId());
        student.setClassName(schoolClass.getCode());
        if (student.getUsername() == null || student.getUsername().isBlank()) {
            student.setUsername(uniqueUsername(firstNonBlank(data.studentUsername, data.studentCode)));
        }
        if (createdStudent || !isBlank(data.studentPassword)) {
            studentPassword = firstNonBlank(data.studentPassword, defaultStudentPassword(data.studentCode));
            student.setPasswordHash(encoder.encode(studentPassword));
            student.setPasswordChangeRequired(true);
        }
        student.setUpdatedAt(Instant.now());
        student = users.save(student);
        if (createdStudent) rbac.assignPrimaryRole(student.getId(), "STUDENT", null);

        ParentUpsert parentUpsert = upsertParent(data);
        boolean linked = ensureSingleParentRelation(parentUpsert.parent.getId(), student.getId());

        syncClassCount(oldClassId);
        syncClassCount(student.getClassId());

        StudentImportRowResult result = new StudentImportRowResult(
                data.rowNumber,
                "OK",
                createdStudent ? "CREATED" : "UPDATED",
                data.studentCode,
                student.getFullName(),
                student.getUsername(),
                parentUpsert.parent.getUsername(),
                schoolClass.getCode(),
                studentPassword,
                parentUpsert.createdPassword,
                null);
        return new RowOutcome(result, createdStudent, updatedStudent,
                parentUpsert.created, !parentUpsert.created, linked);
    }

    private ParentUpsert upsertParent(RowData data) {
        Optional<User> found = findParent(data);

        boolean created = found.isEmpty();
        User parent = found.orElseGet(() -> User.builder()
                .id(Ids.gen("u"))
                .role("PARENT")
                .status("ACTIVE")
                .passwordChangeRequired(true)
                .sessionVersion(0)
                .createdAt(Instant.now())
                .build());

        String createdPassword = null;
        parent.setFullName(data.parentName);
        parent.setEmail(blankToNull(data.parentEmail));
        parent.setPhone(blankToNull(data.parentPhone));
        if (parent.getUsername() == null || parent.getUsername().isBlank()) {
            parent.setUsername(uniqueUsername(firstNonBlank(data.parentUsername, data.parentPhone, data.parentEmail)));
        }
        if (created || !isBlank(data.parentPassword)) {
            createdPassword = firstNonBlank(data.parentPassword, defaultParentPassword(data.parentPhone));
            parent.setPasswordHash(encoder.encode(createdPassword));
            parent.setPasswordChangeRequired(true);
        }
        parent.setUpdatedAt(Instant.now());
        parent = users.save(parent);
        if (created) rbac.assignPrimaryRole(parent.getId(), "PARENT", null);
        return new ParentUpsert(parent, created, createdPassword);
    }

    private Optional<User> findParent(RowData data) {
        Optional<User> found = Optional.empty();
        if (!isBlank(data.parentPhone)) {
            found = users.findByRoleAndPhone("PARENT", data.parentPhone);
        }
        if (found.isEmpty() && !isBlank(data.parentEmail)) {
            found = users.findByRoleAndEmailIgnoreCase("PARENT", data.parentEmail);
        }
        if (found.isEmpty() && !isBlank(data.parentUsername)) {
            found = users.findByUsername(data.parentUsername)
                    .filter(u -> "PARENT".equals(u.getRole()));
        }
        return found;
    }

    private boolean ensureSingleParentRelation(String parentId, String studentId) {
        List<ParentStudent> existing = relations.findByStudentId(studentId);
        boolean sameParentExists = existing.stream().anyMatch(r -> r.getParentId().equals(parentId));
        boolean otherParentExists = existing.stream().anyMatch(r -> !r.getParentId().equals(parentId));
        if (otherParentExists) {
            throw ApiException.conflict("Student already has another parent");
        }
        if (sameParentExists) {
            existing.stream()
                    .filter(r -> r.getParentId().equals(parentId) && !r.isPrimaryContact())
                    .forEach(r -> {
                        r.setPrimaryContact(true);
                        relations.save(r);
                    });
            return false;
        }
        relations.save(ParentStudent.builder()
                .id(Ids.gen("ps"))
                .parentId(parentId)
                .studentId(studentId)
                .primaryContact(true)
                .build());
        return true;
    }

    private void validate(RowData data) {
        if (isBlank(data.studentCode)) throw ApiException.badRequest("Missing student code");
        if (isBlank(data.studentName)) throw ApiException.badRequest("Missing student name");
        if (isBlank(data.classCode)) throw ApiException.badRequest("Missing class code");
        if (isBlank(data.parentName)) throw ApiException.badRequest("Missing parent name");
        if (isBlank(data.parentPhone) && isBlank(data.parentEmail)) {
            throw ApiException.badRequest("Parent phone or parent email is required");
        }
    }

    private RowData readRow(Row row, Map<String, Integer> headers) {
        int rowNumber = row.getRowNum() + 1;
        return new RowData(
                rowNumber,
                upperNoSpace(cell(row, headers, "studentCode")),
                trim(cell(row, headers, "studentName")),
                lower(cell(row, headers, "studentEmail")),
                phone(cell(row, headers, "studentPhone")),
                trim(cell(row, headers, "studentUsername")),
                trim(cell(row, headers, "studentPassword")),
                upperNoSpace(cell(row, headers, "classCode")),
                trim(cell(row, headers, "parentName")),
                phone(cell(row, headers, "parentPhone")),
                lower(cell(row, headers, "parentEmail")),
                trim(cell(row, headers, "parentUsername")),
                trim(cell(row, headers, "parentPassword")));
    }

    private Map<String, Integer> readHeaders(Row headerRow) {
        if (headerRow == null) throw ApiException.badRequest("Missing header row");
        Map<String, Integer> headers = new HashMap<>();
        for (int i = 0; i < headerRow.getLastCellNum(); i++) {
            String raw = FORMATTER.formatCellValue(headerRow.getCell(i));
            String canonical = HEADER_ALIASES.get(normalizeKey(raw));
            if (canonical != null) {
                headers.putIfAbsent(canonical, i);
            }
        }
        applyDefaultColumnOrder(headers);
        return headers;
    }

    private void applyDefaultColumnOrder(Map<String, Integer> headers) {
        headers.putIfAbsent("studentCode", 0);
        headers.putIfAbsent("studentName", 1);
        headers.putIfAbsent("studentEmail", 2);
        headers.putIfAbsent("studentPhone", 3);
        headers.putIfAbsent("classCode", 4);
        headers.putIfAbsent("parentName", 5);
        headers.putIfAbsent("parentPhone", 6);
        headers.putIfAbsent("parentEmail", 7);
    }

    private String cell(Row row, Map<String, Integer> headers, String key) {
        Integer index = headers.get(key);
        if (index == null) return null;
        return trim(FORMATTER.formatCellValue(row.getCell(index)));
    }

    private boolean isBlankRow(Row row) {
        if (row == null) return true;
        for (int i = 0; i < row.getLastCellNum(); i++) {
            if (!isBlank(FORMATTER.formatCellValue(row.getCell(i)))) return false;
        }
        return true;
    }

    private void syncClassCount(String classId) {
        if (isBlank(classId)) return;
        structure.updateClassStudentCount(classId, (int) users.countByRoleAndClassId("STUDENT", classId));
    }

    private String uniqueUsername(String desired) {
        String base = sanitizeUsername(firstNonBlank(desired, "user"));
        String candidate = base;
        int suffix = 2;
        while (users.existsByUsername(candidate)) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }

    private String sanitizeUsername(String raw) {
        String value = trim(raw);
        if (value == null) return "user";
        value = value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        return value.isBlank() ? "user" : value;
    }

    private String defaultStudentPassword(String studentCode) {
        return "Sse@" + studentCode + "!";
    }

    private String defaultParentPassword(String parentPhone) {
        String p = phone(parentPhone);
        if (p != null && p.length() >= 4) {
            return "Sse@" + p.substring(p.length() - 4) + "!";
        }
        return "Sse@123456";
    }

    private static Map<String, String> headerAliases() {
        Map<String, String> map = new HashMap<>();
        alias(map, "studentCode", "ma hoc sinh", "ma hs", "mahs", "student code", "student_code", "studentcode");
        alias(map, "studentName", "ho ten hoc sinh", "ten hoc sinh", "hoc sinh", "student name", "student_name", "fullname", "full name");
        alias(map, "studentEmail", "email hoc sinh", "student email", "student_email", "email");
        alias(map, "studentPhone", "sdt hoc sinh", "so dien thoai hoc sinh", "dien thoai hoc sinh", "student phone", "student_phone");
        alias(map, "studentUsername", "username hoc sinh", "tai khoan hoc sinh", "student username", "student_username");
        alias(map, "studentPassword", "mat khau hoc sinh", "student password", "student_password");
        alias(map, "classCode", "lop", "ma lop", "class", "class code", "class_code", "classcode");
        alias(map, "parentName", "ho ten phu huynh", "ten phu huynh", "phu huynh", "parent name", "parent_name");
        alias(map, "parentPhone", "sdt phu huynh", "so dien thoai phu huynh", "dien thoai phu huynh", "parent phone", "parent_phone", "phone phu huynh");
        alias(map, "parentEmail", "email phu huynh", "parent email", "parent_email");
        alias(map, "parentUsername", "username phu huynh", "tai khoan phu huynh", "parent username", "parent_username");
        alias(map, "parentPassword", "mat khau phu huynh", "parent password", "parent_password");
        return map;
    }

    private static void alias(Map<String, String> map, String canonical, String... aliases) {
        for (String alias : aliases) {
            map.put(normalizeKey(alias), canonical);
        }
    }

    private static String normalizeKey(String raw) {
        String value = raw == null ? "" : raw.trim();
        value = value.replace('đ', 'd').replace('Đ', 'D');
        value = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private String cleanError(RuntimeException ex) {
        Throwable root = ex;
        while (root.getCause() != null) root = root.getCause();
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) return value;
        }
        return null;
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value;
    }

    private static String lower(String value) {
        String v = trim(value);
        return v == null ? null : v.toLowerCase(Locale.ROOT);
    }

    private static String upperNoSpace(String value) {
        String v = trim(value);
        return v == null ? null : v.replace(" ", "").toUpperCase(Locale.ROOT);
    }

    private static String phone(String value) {
        String v = trim(value);
        return v == null ? null : v.replaceAll("[\\s.\\-]", "");
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static final class ImportCounters {
        int totalRows;
        int createdStudents;
        int updatedStudents;
        int createdParents;
        int reusedParents;
        int linkedRelations;
        int failedRows;
    }

    private record RowData(
            int rowNumber,
            String studentCode,
            String studentName,
            String studentEmail,
            String studentPhone,
            String studentUsername,
            String studentPassword,
            String classCode,
            String parentName,
            String parentPhone,
            String parentEmail,
            String parentUsername,
            String parentPassword) {}

    private record RowOutcome(
            StudentImportRowResult result,
            boolean createdStudent,
            boolean updatedStudent,
            boolean createdParent,
            boolean reusedParent,
            boolean linkedRelation) {}

    private record ParentUpsert(User parent, boolean created, String createdPassword) {}

    public record StudentImportResult(
            int totalRows,
            int createdStudents,
            int updatedStudents,
            int createdParents,
            int reusedParents,
            int linkedRelations,
            int failedRows,
            List<StudentImportRowResult> rows) {}

    public record StudentImportRowResult(
            int rowNumber,
            String status,
            String action,
            String studentCode,
            String studentName,
            String studentUsername,
            String parentUsername,
            String classCode,
            String studentPassword,
            String parentPassword,
            String error) {
        static StudentImportRowResult error(int rowNumber, String error) {
            return new StudentImportRowResult(rowNumber, "ERROR", null, null, null,
                    null, null, null, null, null, error);
        }
    }
}
