package com.sse.app.identity;

import com.sse.app.academic.structure.SchoolClass;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.report.AcademicEnrollmentService;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.ByteArrayOutputStream;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StudentImportServiceTest {
    @Mock UserRepository users;
    @Mock ParentStudentRepository relations;
    @Mock StructureService structure;
    @Mock PasswordEncoder encoder;
    @Mock RbacService rbac;
    @Mock AcademicEnrollmentService enrollments;

    @Test
    void importedStudentIsAssignedToCanonicalEnrollment() throws Exception {
        StudentImportService service = new StudentImportService(
                users, relations, structure, encoder, rbac, enrollments);
        SchoolClass schoolClass = SchoolClass.builder()
                .id("class-10a1")
                .code("10A1")
                .academicYearId("year-active")
                .build();

        when(structure.ensureClassByCode("10A1")).thenReturn(schoolClass);
        when(users.findByStudentCodeIgnoreCase("HS001")).thenReturn(Optional.empty());
        when(users.findByRoleAndPhone("PARENT", "0987654321"))
                .thenReturn(Optional.empty());
        when(users.findByRoleAndEmailIgnoreCase("PARENT", "parent@example.com"))
                .thenReturn(Optional.empty());
        when(users.existsByUsername(anyString())).thenReturn(false);
        when(encoder.encode(anyString())).thenReturn("encoded-password");
        when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(relations.findByStudentId(anyString())).thenReturn(java.util.List.of());
        when(relations.save(any(ParentStudent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(users.countByRoleAndClassId("STUDENT", "class-10a1")).thenReturn(1L);

        StudentImportService.StudentImportResult result = service.importStudents(workbook());

        assertEquals(1, result.totalRows());
        assertEquals(1, result.createdStudents());
        assertEquals(0, result.failedRows());
        assertEquals("10A1", result.rows().get(0).classCode());
        verify(enrollments).assignStudentCurrentClass(
                anyString(), eq("class-10a1"), isNull());
    }

    private MockMultipartFile workbook() throws Exception {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Students");
            Row header = sheet.createRow(0);
            String[] headers = {
                    "Mã học sinh", "Họ tên học sinh", "Email học sinh",
                    "SĐT học sinh", "Lớp", "Họ tên phụ huynh",
                    "SĐT phụ huynh", "Email phụ huynh"
            };
            for (int index = 0; index < headers.length; index++) {
                header.createCell(index).setCellValue(headers[index]);
            }
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("HS001");
            row.createCell(1).setCellValue("Học sinh Một");
            row.createCell(2).setCellValue("student@example.com");
            row.createCell(3).setCellValue("0912345678");
            row.createCell(4).setCellValue("10A1");
            row.createCell(5).setCellValue("Phụ huynh Một");
            row.createCell(6).setCellValue("0987654321");
            row.createCell(7).setCellValue("parent@example.com");
            workbook.write(out);
            return new MockMultipartFile(
                    "file", "students.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    out.toByteArray());
        }
    }
}
