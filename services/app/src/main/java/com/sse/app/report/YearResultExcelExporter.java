package com.sse.app.report;

import com.sse.app.report.YearResultDtos.StudentYearResult;
import com.sse.app.report.YearReviewDtos.AnnualSubjectResult;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

@Component
public class YearResultExcelExporter {
    public byte[] export(StudentYearResult result) {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Tong ket nam");
            CellStyle title = title(workbook);
            CellStyle header = header(workbook);
            CellStyle score = score(workbook);

            int rowIndex = 0;
            Row titleRow = sheet.createRow(rowIndex++);
            titleRow.createCell(0).setCellValue("PHIẾU TỔNG KẾT NĂM HỌC");
            titleRow.getCell(0).setCellStyle(title);
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, 3));

            row(sheet, rowIndex++, "Năm học", result.academicYearName());
            row(sheet, rowIndex++, "Học sinh", result.studentName());
            row(sheet, rowIndex++, "Mã học sinh", result.studentCode());
            row(sheet, rowIndex++, "Lớp", result.classCode());
            row(sheet, rowIndex++, "Điểm trung bình năm", result.yearlyAverage());
            row(sheet, rowIndex++, "Tỷ lệ chuyên cần", result.attendanceRate());
            row(sheet, rowIndex++, "Hạnh kiểm", conductLabel(result.conductGrade()));
            row(sheet, rowIndex++, "Kết quả", resultLabel(result.result()));
            row(sheet, rowIndex++, "Lớp năm học tiếp theo",
                    result.nextClassCode() == null ? "Chưa xếp lớp" : result.nextClassCode());
            rowIndex++;

            Row tableHeader = sheet.createRow(rowIndex++);
            cell(tableHeader, 0, "Môn học", header);
            cell(tableHeader, 1, "HK1", header);
            cell(tableHeader, 2, "HK2", header);
            cell(tableHeader, 3, "Cả năm", header);
            for (AnnualSubjectResult subject : result.subjects()) {
                Row subjectRow = sheet.createRow(rowIndex++);
                subjectRow.createCell(0).setCellValue(subject.subjectName());
                number(subjectRow, 1, subject.semesterOneAverage(), score);
                number(subjectRow, 2, subject.semesterTwoAverage(), score);
                number(subjectRow, 3, subject.yearlyAverage(), score);
            }

            for (int column = 0; column < 4; column++) {
                sheet.autoSizeColumn(column);
                sheet.setColumnWidth(column, Math.min(15000,
                        Math.max(sheet.getColumnWidth(column) + 1200, 4200)));
            }
            sheet.createFreezePane(0, 11);
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Không thể tạo phiếu tổng kết Excel", exception);
        }
    }

    private void row(Sheet sheet, int index, String label, Object value) {
        Row row = sheet.createRow(index);
        row.createCell(0).setCellValue(label);
        if (value instanceof Number number) {
            row.createCell(1).setCellValue(number.doubleValue());
        } else {
            row.createCell(1).setCellValue(value == null ? "" : String.valueOf(value));
        }
    }

    private void cell(Row row, int column, String value, CellStyle style) {
        row.createCell(column).setCellValue(value);
        row.getCell(column).setCellStyle(style);
    }

    private void number(Row row, int column, Double value, CellStyle style) {
        if (value == null) {
            row.createCell(column).setCellValue("");
        } else {
            row.createCell(column).setCellValue(value);
        }
        row.getCell(column).setCellStyle(style);
    }

    private CellStyle title(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        org.apache.poi.ss.usermodel.Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 16);
        font.setColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFont(font);
        return style;
    }

    private CellStyle header(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        org.apache.poi.ss.usermodel.Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        return style;
    }

    private CellStyle score(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(workbook.createDataFormat().getFormat("0.0"));
        style.setAlignment(HorizontalAlignment.CENTER);
        return style;
    }

    private String resultLabel(String result) {
        return switch (result == null ? "" : result) {
            case "PROMOTED" -> "Lên lớp";
            case "RETAINED" -> "Lưu ban";
            case "ELIGIBLE_FOR_GRADUATION", "GRADUATED" -> "Đủ điều kiện tốt nghiệp";
            case "INCOMPLETE" -> "Chưa hoàn tất";
            default -> "Chờ xét";
        };
    }

    private String conductLabel(String conduct) {
        return switch (conduct == null ? "" : conduct) {
            case "GOOD" -> "Tốt";
            case "FAIR" -> "Khá";
            case "PASS" -> "Đạt";
            case "FAIL" -> "Chưa đạt";
            default -> "";
        };
    }
}
