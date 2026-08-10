package com.sse.app.report;

import com.sse.app.report.AcademicReportDtos.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AcademicReportExportServiceTest {
    private final AcademicReportService reports = mock(AcademicReportService.class);
    private final AcademicReportExportService exporter = new AcademicReportExportService(reports);

    @Test
    void exportsExcelWithSummaryStudentAndSubjectSheets() throws Exception {
        when(reports.report(any())).thenReturn(report());

        AcademicReportFile file = exporter.export("XLSX", filter());

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file.content()))) {
            assertEquals(3, workbook.getNumberOfSheets());
            assertEquals("HS001", workbook.getSheetAt(1).getRow(1).getCell(0).getStringCellValue());
            assertEquals("Toán", workbook.getSheetAt(2).getRow(1).getCell(0).getStringCellValue());
        }
    }

    @Test
    void exportsReadablePdfContainer() {
        when(reports.report(any())).thenReturn(report());

        AcademicReportFile file = exporter.export("PDF", filter());

        assertTrue(file.content().length > 10_000);
        assertEquals("%PDF", new String(file.content(), 0, 4));
    }

    private AcademicReportFilter filter() {
        return new AcademicReportFilter("year", "semester", "10", "class", "math");
    }

    private AcademicReportResponse report() {
        return new AcademicReportResponse(filter(),
                new AcademicReportSummary(1, 1, 1, 4, 8.2, 10, 90.0, 2, 2, 1),
                List.of(new AcademicStudentRow("student", "HS001", "Nguyễn An", "class", "10A1",
                        4, 8.2, 8, 1, 1, 0, 90.0, 2, 2, 1)),
                List.of(new AcademicSubjectRow("math", "Toán", 4, 1, 8.2)), Instant.now());
    }
}
