package com.sse.app.identity;

import com.sse.app.academic.structure.SchoolClass;
import com.sse.app.academic.structure.Subject;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.common.ApiException;
import com.sse.app.identity.IdentityDtos.CreateUserRequest;
import com.sse.app.identity.IdentityDtos.ImportPreview;
import com.sse.app.identity.IdentityDtos.ImportPreviewRow;
import com.sse.app.identity.IdentityDtos.ImportResult;
import com.sse.app.identity.IdentityDtos.ImportRowError;
import org.apache.poi.ss.usermodel.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Safe Excel import:
 * 1. Preview parses and validates every row without changing the database.
 * 2. The confirmation token is signed and bound to the exact file checksum.
 * 3. Commit parses the file again and writes in a single transaction.
 */
@Service
public class UserImportService {
    private static final int MAX_ROWS = 5_000;
    private static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    private static final long PREVIEW_TTL_SECONDS = 15 * 60;

    private final UserService users;
    private final StructureService structure;
    private final byte[] signingKey;

    public UserImportService(UserService users, StructureService structure,
                             @Value("${sse.jwt.secret}") String signingSecret) {
        this.users = users;
        this.structure = structure;
        this.signingKey = signingSecret.getBytes(StandardCharsets.UTF_8);
    }

    public ImportPreview preview(MultipartFile file) {
        byte[] bytes = readAndValidate(file);
        ParsedFile parsed = parse(bytes);
        String checksum = sha256(bytes);
        long expiresAt = Instant.now().plusSeconds(PREVIEW_TTL_SECONDS).toEpochMilli();
        String token = signToken(checksum, expiresAt, parsed.validRows(), parsed.rows().size());
        return new ImportPreview(
                token,
                checksum,
                expiresAt,
                parsed.rows().size(),
                parsed.validRows(),
                parsed.rows().size() - parsed.validRows(),
                parsed.rows().stream().map(ParsedRow::preview).toList()
        );
    }

    @Transactional
    public ImportResult commit(MultipartFile file, String token, String strategy) {
        byte[] bytes = readAndValidate(file);
        ParsedFile parsed = parse(bytes);
        verifyToken(token, sha256(bytes), parsed.validRows(), parsed.rows().size());
        return commitParsed(parsed, normalizeStrategy(strategy));
    }

    /**
     * Backward-compatible endpoint. It is intentionally all-or-nothing now:
     * a legacy client receives validation errors without creating partial data.
     */
    @Transactional
    public ImportResult importExcel(MultipartFile file) {
        ParsedFile parsed = parse(readAndValidate(file));
        return commitParsed(parsed, "ALL_OR_NOTHING");
    }

    private ImportResult commitParsed(ParsedFile parsed, String strategy) {
        List<ImportRowError> validationErrors = parsed.rows().stream()
                .filter(row -> row.request() == null)
                .map(row -> new ImportRowError(row.preview().row(), row.preview().username(), row.preview().error()))
                .toList();
        if ("ALL_OR_NOTHING".equals(strategy) && !validationErrors.isEmpty()) {
            return new ImportResult(parsed.rows().size(), 0, validationErrors.size(), validationErrors);
        }

        int imported = 0;
        for (ParsedRow row : parsed.rows()) {
            if (row.request() == null) continue;
            UserDto created = users.create(row.request());
            users.requirePasswordChange(created.id());
            if (row.linkedUser() != null) {
                if ("PARENT".equals(row.request().role())) {
                    users.linkChild(created.id(), row.linkedUser().getId(), true);
                } else if ("STUDENT".equals(row.request().role())) {
                    users.linkChild(row.linkedUser().getId(), created.id(), true);
                }
            }
            imported++;
        }
        return new ImportResult(parsed.rows().size(), imported, validationErrors.size(), validationErrors);
    }

    public byte[] template() {
        try (Workbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Nguoi dung");
            String[] headers = {"Tên đăng nhập", "Họ tên", "Vai trò", "Mật khẩu", "Email", "Số điện thoại",
                    "Mã lớp", "Mã môn chuyên ngành", "Ngày sinh", "Giới tính",
                    "Địa chỉ", "Ngày nhập học", "Người giám hộ", "SĐT người giám hộ", "Tên đăng nhập liên kết"};
            Row header = sheet.createRow(0);
            CellStyle style = workbook.createCellStyle();
            Font font = workbook.createFont();
            font.setBold(true);
            style.setFont(font);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(style);
                sheet.setColumnWidth(i, Math.min(40, Math.max(14, headers[i].length() + 4)) * 256);
            }
            Row example = sheet.createRow(1);
            String[] values = {"hs.nguyenvana", "Nguyễn Văn A", "Học sinh", "Sse@123456", "a@example.edu.vn",
                    "0900000000", "10A1", "", "01/01/2010", "MALE", "TP. Hồ Chí Minh",
                    "05/09/2025", "Nguyễn Văn B", "0911111111", ""};
            for (int i = 0; i < values.length; i++) example.createCell(i).setCellValue(values[i]);
            workbook.write(output);
            return output.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Không thể tạo tệp mẫu", e);
        }
    }

    private ParsedFile parse(byte[] bytes) {
        List<ParsedRow> rows = new ArrayList<>();
        Set<String> usernamesInFile = new HashSet<>();
        DataFormatter formatter = new DataFormatter(Locale.forLanguageTag("vi-VN"));
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            if (workbook.getNumberOfSheets() == 0) throw ApiException.badRequest("Tệp Excel không có trang dữ liệu");
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet.getPhysicalNumberOfRows() < 2) throw ApiException.badRequest("Tệp không có dòng dữ liệu");
            Map<String, Integer> headers = headers(sheet.getRow(sheet.getFirstRowNum()), formatter);
            require(headers, "username", "Tên đăng nhập");
            require(headers, "fullname", "Họ tên");
            require(headers, "role", "Vai trò");
            require(headers, "password", "Mật khẩu");

            for (int i = sheet.getFirstRowNum() + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null || rowIsEmpty(row, formatter)) continue;
                if (rows.size() >= MAX_ROWS) throw ApiException.badRequest("Tệp vượt quá " + MAX_ROWS + " dòng dữ liệu");
                rows.add(parseRow(row, i + 1, headers, formatter, usernamesInFile));
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest("Không thể đọc tệp Excel: " + cleanMessage(e));
        }
        if (rows.isEmpty()) throw ApiException.badRequest("Tệp không có dòng dữ liệu hợp lệ để kiểm tra");
        int valid = (int) rows.stream().filter(row -> row.request() != null).count();
        return new ParsedFile(List.copyOf(rows), valid);
    }

    private ParsedRow parseRow(Row row, int rowNumber, Map<String, Integer> h, DataFormatter f,
                               Set<String> usernamesInFile) {
        String username = cell(row, h, "username", f);
        String fullName = cell(row, h, "fullname", f);
        String roleText = cell(row, h, "role", f);
        String classCode = cell(row, h, "classcode", f);
        String linkedUsername = cell(row, h, "linkedusername", f);
        try {
            username = required(username, "Tên đăng nhập");
            fullName = required(fullName, "Họ tên");
            String role = normalizeRole(required(roleText, "Vai trò"));
            String normalizedUsername = username.toLowerCase(Locale.ROOT);
            if (!usernamesInFile.add(normalizedUsername)) {
                throw ApiException.badRequest("Tên đăng nhập bị trùng trong tệp");
            }
            if (users.findByUsername(username).isPresent()) {
                throw ApiException.conflict("Tên đăng nhập đã tồn tại trong hệ thống");
            }

            SchoolClass schoolClass = null;
            if ("STUDENT".equals(role)) {
                classCode = required(classCode, "Mã lớp");
                String requestedClassCode = classCode;
                schoolClass = structure.classByCode(requestedClassCode)
                        .orElseThrow(() -> ApiException.badRequest("Không tìm thấy lớp " + requestedClassCode));
            }
            Subject mainSubject = null;
            if ("TEACHER".equals(role)) {
                mainSubject = structure.requireSubjectByCodeOrName(
                        cell(row, h, "mainsubject", f));
            }

            String password = required(cell(row, h, "password", f), "Mật khẩu tạm");
            if (password.length() < 10) throw ApiException.badRequest("Mật khẩu tạm phải có ít nhất 10 ký tự");
            String email = required(cell(row, h, "email", f), "Email");
            if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
                throw ApiException.badRequest("Email không hợp lệ");
            }
            String phone = required(cell(row, h, "phone", f), "Số điện thoại");
            if (!phone.matches("^[0-9+ .()\\-]{7,30}$")) {
                throw ApiException.badRequest("Số điện thoại không hợp lệ");
            }

            User linked = linkedUsername.isBlank() ? null : users.findByUsername(linkedUsername)
                    .orElseThrow(() -> ApiException.badRequest("Không tìm thấy tài khoản liên kết " + linkedUsername));
            if (linked != null && "PARENT".equals(role) && !"STUDENT".equals(linked.getRole())) {
                throw ApiException.badRequest("Tài khoản liên kết phải là học sinh");
            }
            if (linked != null && "STUDENT".equals(role) && !"PARENT".equals(linked.getRole())) {
                throw ApiException.badRequest("Tài khoản liên kết phải là phụ huynh");
            }

            CreateUserRequest request = new CreateUserRequest(
                    null, username, password, fullName, role,
                    email, phone, null,
                    null,
                    mainSubject == null ? null : mainSubject.getName(),
                    mainSubject == null ? null : mainSubject.getId(),
                    null,
                    schoolClass == null ? null : schoolClass.getId(),
                    schoolClass == null ? null : schoolClass.getCode(),
                    parseDate(cell(row, h, "dateofbirth", f)),
                    emptyToNull(cell(row, h, "gender", f)),
                    emptyToNull(cell(row, h, "placeofbirth", f)),
                    emptyToNull(cell(row, h, "ethnicity", f)),
                    emptyToNull(cell(row, h, "nationality", f)),
                    emptyToNull(cell(row, h, "address", f)),
                    parseDate(cell(row, h, "enrollmentdate", f)),
                    emptyToNull(cell(row, h, "guardianname", f)),
                    emptyToNull(cell(row, h, "guardianphone", f))
            );
            ImportPreviewRow preview = new ImportPreviewRow(
                    rowNumber, username, fullName, role,
                    schoolClass == null ? classCode : schoolClass.getCode(),
                    linkedUsername, true, null
            );
            return new ParsedRow(preview, request, linked);
        } catch (Exception e) {
            ImportPreviewRow preview = new ImportPreviewRow(
                    rowNumber, username, fullName, roleText, classCode, linkedUsername, false, cleanMessage(e)
            );
            return new ParsedRow(preview, null, null);
        }
    }

    private byte[] readAndValidate(MultipartFile file) {
        if (file == null || file.isEmpty()) throw ApiException.badRequest("Vui lòng chọn tệp Excel");
        if (file.getSize() > MAX_FILE_BYTES) throw ApiException.badRequest("Tệp Excel không được vượt quá 10 MB");
        String name = Optional.ofNullable(file.getOriginalFilename()).orElse("").toLowerCase(Locale.ROOT);
        if (!name.endsWith(".xlsx") && !name.endsWith(".xls")) {
            throw ApiException.badRequest("Chỉ hỗ trợ tệp .xlsx hoặc .xls");
        }
        try {
            return file.getBytes();
        } catch (Exception e) {
            throw ApiException.badRequest("Không thể đọc tệp Excel");
        }
    }

    private String normalizeStrategy(String strategy) {
        String value = strategy == null ? "ALL_OR_NOTHING" : strategy.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("ALL_OR_NOTHING", "SKIP_ERRORS").contains(value)) {
            throw ApiException.badRequest("Chiến lược import không hợp lệ");
        }
        return value;
    }

    private String signToken(String checksum, long expiresAt, int validRows, int totalRows) {
        String payload = checksum + ":" + expiresAt + ":" + validRows + ":" + totalRows;
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return encoded + "." + hmac(encoded);
    }

    private void verifyToken(String token, String checksum, int validRows, int totalRows) {
        try {
            String[] parts = token == null ? new String[0] : token.split("\\.", 2);
            if (parts.length != 2 || !MessageDigest.isEqual(
                    parts[1].getBytes(StandardCharsets.UTF_8),
                    hmac(parts[0]).getBytes(StandardCharsets.UTF_8))) {
                throw ApiException.badRequest("Phiên xem trước import không hợp lệ");
            }
            String payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            String[] values = payload.split(":", 4);
            if (values.length != 4
                    || !values[0].equals(checksum)
                    || Integer.parseInt(values[2]) != validRows
                    || Integer.parseInt(values[3]) != totalRows) {
                throw ApiException.badRequest("Tệp đã thay đổi sau khi xem trước");
            }
            if (Long.parseLong(values[1]) < System.currentTimeMillis()) {
                throw ApiException.badRequest("Phiên xem trước đã hết hạn, vui lòng kiểm tra lại tệp");
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest("Phiên xem trước import không hợp lệ");
        }
    }

    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Không thể ký phiên import", e);
        }
    }

    private String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Không thể kiểm tra tệp import", e);
        }
    }

    private Map<String, Integer> headers(Row row, DataFormatter formatter) {
        if (row == null) throw ApiException.badRequest("Thiếu dòng tiêu đề");
        Map<String, Integer> out = new HashMap<>();
        for (Cell cell : row) out.put(normalizeHeader(formatter.formatCellValue(cell)), cell.getColumnIndex());
        alias(out, "tendangnhap", "username");
        alias(out, "hoten", "fullname");
        alias(out, "vaitro", "role");
        alias(out, "matkhau", "password");
        alias(out, "malop", "classcode");
        alias(out, "mahocsinh", "studentcode");
        alias(out, "magiaovien", "teachercode");
        alias(out, "monchinh", "mainsubject");
        alias(out, "mamonchuyennganh", "mainsubject");
        alias(out, "sodienthoai", "phone");
        alias(out, "ngaysinh", "dateofbirth");
        alias(out, "gioitinh", "gender");
        alias(out, "noisinh", "placeofbirth");
        alias(out, "dantoc", "ethnicity");
        alias(out, "quoctich", "nationality");
        alias(out, "diachi", "address");
        alias(out, "ngaynhaphoc", "enrollmentdate");
        alias(out, "nguoigiamho", "guardianname");
        alias(out, "sdtnguoigiamho", "guardianphone");
        alias(out, "tendangnhaplienket", "linkedusername");
        return out;
    }

    private void alias(Map<String, Integer> headers, String source, String target) {
        if (!headers.containsKey(target) && headers.containsKey(source)) headers.put(target, headers.get(source));
    }

    private void require(Map<String, Integer> headers, String key, String label) {
        if (!headers.containsKey(key)) throw ApiException.badRequest("Thiếu cột bắt buộc: " + label);
    }

    private String cell(Row row, Map<String, Integer> headers, String key, DataFormatter formatter) {
        Integer index = headers.get(key);
        return index == null ? "" : formatter
                .formatCellValue(row.getCell(index, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK)).trim();
    }

    private boolean rowIsEmpty(Row row, DataFormatter formatter) {
        for (Cell cell : row) if (!formatter.formatCellValue(cell).isBlank()) return false;
        return true;
    }

    private String normalizeHeader(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replace('đ', 'd')
                .replaceAll("[^a-z0-9]", "");
    }

    private String normalizeRole(String value) {
        return switch (normalizeHeader(value)) {
            case "admin", "quantrivien" -> "ADMIN";
            case "teacher", "giaovien" -> "TEACHER";
            case "student", "hocsinh" -> "STUDENT";
            case "parent", "phuhuynh" -> "PARENT";
            default -> throw ApiException.badRequest("Vai trò không hợp lệ: " + value);
        };
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        for (DateTimeFormatter formatter : List.of(
                DateTimeFormatter.ISO_LOCAL_DATE,
                DateTimeFormatter.ofPattern("d/M/uuuu"),
                DateTimeFormatter.ofPattern("d-M-uuuu"))) {
            try {
                return LocalDate.parse(value, formatter);
            } catch (DateTimeParseException ignored) {
            }
        }
        throw ApiException.badRequest("Ngày không hợp lệ: " + value);
    }

    private String required(String value, String label) {
        if (value == null || value.isBlank()) throw ApiException.badRequest(label + " không được để trống");
        return value.trim();
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String cleanMessage(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private record ParsedRow(ImportPreviewRow preview, CreateUserRequest request, User linkedUser) {
    }

    private record ParsedFile(List<ParsedRow> rows, int validRows) {
    }
}
