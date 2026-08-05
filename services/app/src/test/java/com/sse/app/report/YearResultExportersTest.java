package com.sse.app.report;

import com.sse.app.report.YearResultDtos.StudentYearResult;
import com.sse.app.report.YearReviewDtos.AnnualSubjectResult;
import com.sse.app.report.YearReviewDtos.SemesterResult;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YearResultExportersTest {
    @Test
    void pdfRendererCreatesReadablePdfContainer() {
        byte[] content = new YearResultPdfRenderer().render(result());

        assertTrue(content.length > 10_000);
        assertEquals("%PDF", new String(content, 0, 4));
    }

    @Test
    void excelExporterContainsStudentAndSubjectRows() throws Exception {
        byte[] content = new YearResultExcelExporter().export(result());

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            var sheet = workbook.getSheet("Tong ket nam");
            assertEquals("PHIẾU TỔNG KẾT NĂM HỌC",
                    sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("Nguyễn An", sheet.getRow(2).getCell(1).getStringCellValue());
            assertEquals("Toán", sheet.getRow(12).getCell(0).getStringCellValue());
            assertEquals(8.2, sheet.getRow(12).getCell(3).getNumericCellValue());
        }
    }

    private StudentYearResult result() {
        return new StudentYearResult(
                "summary", "ay", "Năm học 2026-2027",
                "class", "10A1", "Lớp 10A1",
                "student", "HS001", "Nguyễn An",
                8.2, 98.0, "GOOD", "PROMOTED", null,
                "ENROLLED", "next-class", "11A1",
                List.of(
                        new SemesterResult("s1", "Học kỳ 1", "CLOSED",
                                8.0, 98.0, true, List.of()),
                        new SemesterResult("s2", "Học kỳ 2", "CLOSED",
                                8.3, 98.0, true, List.of())),
                List.of(new AnnualSubjectResult(
                        "math", "Toán", 8.0, 8.3, 8.2, false)),
                Instant.now(), Instant.now());
    }
}
