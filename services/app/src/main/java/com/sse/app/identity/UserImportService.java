package com.sse.app.identity;

import com.sse.app.academic.structure.SchoolClass;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.common.ApiException;
import com.sse.app.identity.IdentityDtos.CreateUserRequest;
import com.sse.app.identity.IdentityDtos.ImportResult;
import com.sse.app.identity.IdentityDtos.ImportRowError;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.io.ByteArrayOutputStream;

/** Nhập tài khoản từ Excel với kiểm tra từng dòng và báo lỗi không làm hỏng cả tệp. */
@Service
public class UserImportService {
    private static final int MAX_ROWS = 5_000;
    private final UserService users;
    private final StructureService structure;

    public UserImportService(UserService users, StructureService structure) {
        this.users = users;
        this.structure = structure;
    }

    public ImportResult importExcel(MultipartFile file) {
        if (file == null || file.isEmpty()) throw ApiException.badRequest("Vui lòng chọn tệp Excel");
        if (file.getSize() > 10 * 1024 * 1024) throw ApiException.badRequest("Tệp Excel không được vượt quá 10 MB");
        String name = Optional.ofNullable(file.getOriginalFilename()).orElse("").toLowerCase();
        if (!name.endsWith(".xlsx") && !name.endsWith(".xls")) {
            throw ApiException.badRequest("Chỉ hỗ trợ tệp .xlsx hoặc .xls");
        }

        List<ImportRowError> errors = new ArrayList<>();
        int total = 0;
        int imported = 0;
        DataFormatter formatter = new DataFormatter(Locale.forLanguageTag("vi-VN"));
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet.getPhysicalNumberOfRows() < 2) throw ApiException.badRequest("Tệp không có dòng dữ liệu");
            Map<String, Integer> headers = headers(sheet.getRow(sheet.getFirstRowNum()), formatter);
            require(headers, "username", "Tên đăng nhập");
            require(headers, "fullname", "Họ tên");
            require(headers, "role", "Vai trò");

            for (int i = sheet.getFirstRowNum() + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null || rowIsEmpty(row, formatter)) continue;
                total++;
                if (total > MAX_ROWS) throw ApiException.badRequest("Tệp vượt quá " + MAX_ROWS + " dòng dữ liệu");
                try {
                    importRow(row, headers, formatter);
                    imported++;
                } catch (Exception e) {
                    errors.add(new ImportRowError(i + 1, cell(row, headers, "username", formatter), cleanMessage(e)));
                }
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest("Không thể đọc tệp Excel: " + cleanMessage(e));
        }
        return new ImportResult(total, imported, errors.size(), errors);
    }

    public byte[] template() {
        try (Workbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Nguoi dung");
            String[] headers = {"Tên đăng nhập", "Họ tên", "Vai trò", "Mật khẩu", "Email", "Số điện thoại",
                    "Mã lớp", "Mã học sinh", "Mã giáo viên", "Môn chính", "Ngày sinh", "Giới tính",
                    "Địa chỉ", "Ngày nhập học", "Người giám hộ", "SĐT người giám hộ", "Tên đăng nhập liên kết"};
            Row header = sheet.createRow(0);
            CellStyle style = workbook.createCellStyle();
            Font font = workbook.createFont();
            font.setBold(true); style.setFont(font);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = header.createCell(i); cell.setCellValue(headers[i]); cell.setCellStyle(style);
                sheet.setColumnWidth(i, Math.min(40, Math.max(14, headers[i].length() + 4)) * 256);
            }
            Row example = sheet.createRow(1);
            String[] values = {"hs.nguyenvana", "Nguyễn Văn A", "Học sinh", "Sse@123456", "a@example.edu.vn",
                    "0900000000", "10A1", "", "", "", "01/01/2010", "MALE", "TP. Hồ Chí Minh",
                    "05/09/2025", "Nguyễn Văn B", "0911111111", ""};
            for (int i = 0; i < values.length; i++) example.createCell(i).setCellValue(values[i]);
            workbook.write(output);
            return output.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Không thể tạo tệp mẫu", e);
        }
    }

    private void importRow(Row row, Map<String, Integer> h, DataFormatter f) {
        String username = required(cell(row, h, "username", f), "Tên đăng nhập");
        String fullName = required(cell(row, h, "fullname", f), "Họ tên");
        String role = normalizeRole(required(cell(row, h, "role", f), "Vai trò"));
        String classCode = cell(row, h, "classcode", f);
        SchoolClass schoolClass = null;
        if ("STUDENT".equals(role)) {
            schoolClass = structure.classByCode(classCode)
                    .orElseThrow(() -> ApiException.badRequest("Không tìm thấy lớp " + classCode));
        }
        String password = required(cell(row, h, "password", f), "Mật khẩu tạm");
        if (password.length() < 10) throw ApiException.badRequest("Mật khẩu tạm phải có ít nhất 10 ký tự");
        String linkedUsername = cell(row, h, "linkedusername", f);
        User linked = linkedUsername.isBlank() ? null : users.findByUsername(linkedUsername)
                .orElseThrow(() -> ApiException.badRequest("Không tìm thấy tài khoản liên kết " + linkedUsername));
        if (linked != null && "PARENT".equals(role) && !"STUDENT".equals(linked.getRole())) {
            throw ApiException.badRequest("Tài khoản liên kết phải là học sinh");
        }
        if (linked != null && "STUDENT".equals(role) && !"PARENT".equals(linked.getRole())) {
            throw ApiException.badRequest("Tài khoản liên kết phải là phụ huynh");
        }
        UserDto created = users.create(new CreateUserRequest(null, username, password, fullName, role,
                emptyToNull(cell(row, h, "email", f)), emptyToNull(cell(row, h, "phone", f)), null,
                emptyToNull(cell(row, h, "teachercode", f)), emptyToNull(cell(row, h, "mainsubject", f)),
                emptyToNull(cell(row, h, "studentcode", f)), schoolClass == null ? null : schoolClass.getId(),
                schoolClass == null ? null : schoolClass.getCode(), parseDate(cell(row, h, "dateofbirth", f)),
                emptyToNull(cell(row, h, "gender", f)), emptyToNull(cell(row, h, "placeofbirth", f)),
                emptyToNull(cell(row, h, "ethnicity", f)), emptyToNull(cell(row, h, "nationality", f)),
                emptyToNull(cell(row, h, "address", f)), parseDate(cell(row, h, "enrollmentdate", f)),
                emptyToNull(cell(row, h, "guardianname", f)), emptyToNull(cell(row, h, "guardianphone", f))));
        users.requirePasswordChange(created.id());
        if (linked != null) {
            if ("PARENT".equals(role)) users.linkChild(created.id(), linked.getId(), true);
            else if ("STUDENT".equals(role)) users.linkChild(linked.getId(), created.id(), true);
        }
    }

    private Map<String, Integer> headers(Row row, DataFormatter f) {
        if (row == null) throw ApiException.badRequest("Thiếu dòng tiêu đề");
        Map<String, Integer> out = new HashMap<>();
        for (Cell c : row) out.put(normalizeHeader(f.formatCellValue(c)), c.getColumnIndex());
        alias(out, "tendangnhap", "username"); alias(out, "hoten", "fullname");
        alias(out, "vaitro", "role"); alias(out, "matkhau", "password");
        alias(out, "malop", "classcode"); alias(out, "mahocsinh", "studentcode");
        alias(out, "magiaovien", "teachercode"); alias(out, "monchinh", "mainsubject");
        alias(out, "sodienthoai", "phone"); alias(out, "ngaysinh", "dateofbirth");
        alias(out, "gioitinh", "gender"); alias(out, "noisinh", "placeofbirth");
        alias(out, "dantoc", "ethnicity"); alias(out, "quoctich", "nationality");
        alias(out, "diachi", "address"); alias(out, "ngaynhaphoc", "enrollmentdate");
        alias(out, "nguoigiamho", "guardianname"); alias(out, "sdtnguoigiamho", "guardianphone");
        alias(out, "tendangnhaplienket", "linkedusername");
        return out;
    }

    private void alias(Map<String, Integer> headers, String source, String target) {
        if (!headers.containsKey(target) && headers.containsKey(source)) headers.put(target, headers.get(source));
    }

    private void require(Map<String, Integer> headers, String key, String label) {
        if (!headers.containsKey(key)) throw ApiException.badRequest("Thiếu cột bắt buộc: " + label);
    }

    private String cell(Row row, Map<String, Integer> h, String key, DataFormatter f) {
        Integer index = h.get(key);
        return index == null ? "" : f.formatCellValue(row.getCell(index, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK)).trim();
    }

    private boolean rowIsEmpty(Row row, DataFormatter f) {
        for (Cell cell : row) if (!f.formatCellValue(cell).isBlank()) return false;
        return true;
    }

    private String normalizeHeader(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replace('đ', 'd').replaceAll("[^a-z0-9]", "");
    }

    private String normalizeRole(String value) {
        String normalized = normalizeHeader(value);
        return switch (normalized) {
            case "admin", "quantrivien" -> "ADMIN";
            case "teacher", "giaovien" -> "TEACHER";
            case "student", "hocsinh" -> "STUDENT";
            case "parent", "phuhuynh" -> "PARENT";
            default -> throw ApiException.badRequest("Vai trò không hợp lệ: " + value);
        };
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        for (DateTimeFormatter formatter : List.of(DateTimeFormatter.ISO_LOCAL_DATE,
                DateTimeFormatter.ofPattern("d/M/uuuu"), DateTimeFormatter.ofPattern("d-M-uuuu"))) {
            try { return LocalDate.parse(value, formatter); }
            catch (DateTimeParseException ignored) { }
        }
        throw ApiException.badRequest("Ngày không hợp lệ: " + value);
    }

    private String required(String value, String label) {
        if (value == null || value.isBlank()) throw ApiException.badRequest(label + " không được để trống");
        return value.trim();
    }

    private String emptyToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private String cleanMessage(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
}
