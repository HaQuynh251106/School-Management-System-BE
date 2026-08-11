package com.sse.app.report;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ReportExportService {
    private final ReportService reports;

    public ReportExportService(ReportService reports) {
        this.reports = reports;
    }

    public byte[] xlsx(String type, String semesterId, String classId, String subjectId,
                       java.time.LocalDate startDate, java.time.LocalDate endDate, String periodId,
                       Instant asOf) {
        ExportTable table = table(type, semesterId, classId, subjectId, startDate, endDate, periodId);
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Bao cao");
            var bold = workbook.createCellStyle();
            var boldFont = workbook.createFont();
            boldFont.setBold(true);
            bold.setFont(boldFont);
            row(sheet.createRow(0), List.of("Báo cáo", table.title()), bold);
            row(sheet.createRow(1), List.of("asOf", asOf.toString()), null);
            row(sheet.createRow(2), List.of("Bộ lọc", filterText(semesterId, classId, subjectId,
                    startDate, endDate, periodId)), null);
            row(sheet.createRow(4), table.headers(), bold);
            int index = 5;
            for (List<Object> values : table.rows()) row(sheet.createRow(index++), values, null);
            for (int i = 0; i < table.headers().size(); i++) {
                sheet.autoSizeColumn(i);
                sheet.setColumnWidth(i, Math.min(sheet.getColumnWidth(i) + 800, 16000));
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (Exception error) {
            throw new IllegalStateException("Không thể tạo tệp Excel", error);
        }
    }

    public byte[] pdf(String type, String semesterId, String classId, String subjectId,
                      java.time.LocalDate startDate, java.time.LocalDate endDate, String periodId,
                      Instant asOf) {
        ExportTable table = table(type, semesterId, classId, subjectId, startDate, endDate, periodId);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4, 32, 32, 32, 34);
            PdfWriter.getInstance(document, out);
            document.open();
            Paragraph title = new Paragraph(table.title(), font(16, Font.BOLD));
            title.setAlignment(Element.ALIGN_CENTER);
            title.setSpacingAfter(10);
            document.add(title);
            document.add(new Paragraph("asOf: " + asOf, font(9, Font.NORMAL)));
            document.add(new Paragraph("Bộ lọc: " + filterText(semesterId, classId, subjectId,
                    startDate, endDate, periodId), font(9, Font.NORMAL)));
            document.add(new Paragraph(" "));
            PdfPTable pdfTable = new PdfPTable(table.headers().size());
            pdfTable.setWidthPercentage(100);
            for (Object header : table.headers()) pdfTable.addCell(cell(header, true));
            for (List<Object> values : table.rows()) {
                for (Object value : values) pdfTable.addCell(cell(value, false));
            }
            document.add(pdfTable);
            document.close();
            return out.toByteArray();
        } catch (Exception error) {
            throw new IllegalStateException("Không thể tạo tệp PDF", error);
        }
    }

    private ExportTable table(String type, String semesterId, String classId, String subjectId,
                              java.time.LocalDate startDate, java.time.LocalDate endDate, String periodId) {
        String normalized = type == null ? "overview" : type.toLowerCase();
        return switch (normalized) {
            case "grades" -> new ExportTable("Phổ điểm", List.of("Khoảng điểm", "Số kết quả"),
                    reports.gradeDistribution(semesterId, classId, subjectId).stream()
                            .map(item -> List.<Object>of(item.get("band"), item.get("count"))).toList());
            case "attendance" -> attendanceTable(classId, startDate, endDate);
            case "revenue" -> mapTable("Doanh thu và công nợ", "Hạng mục", "Giá trị",
                    reports.revenue(periodId, classId));
            case "overview" -> mapTable("Tổng quan hệ thống", "Nhóm dữ liệu", "Số lượng", reports.overview());
            default -> throw com.sse.app.common.ApiException.badRequest("Loại báo cáo không hợp lệ");
        };
    }

    private ExportTable attendanceTable(String classId, java.time.LocalDate startDate,
                                        java.time.LocalDate endDate) {
        Map<String, Object> summary = reports.attendanceSummary(classId, startDate, endDate);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("Có mặt", summary.get("present"));
        values.put("Đi muộn", summary.get("late"));
        values.put("Vắng có phép", summary.get("absentExcused"));
        values.put("Vắng không phép", summary.get("absentUnexcused"));
        return mapTable("Chuyên cần", "Trạng thái", "Số lượt", values);
    }

    private ExportTable mapTable(String title, String keyHeader, String valueHeader, Map<String, Object> values) {
        return new ExportTable(title, List.of(keyHeader, valueHeader), values.entrySet().stream()
                .map(entry -> List.<Object>of(entry.getKey(), entry.getValue())).toList());
    }

    private void row(Row row, List<?> values, CellStyle style) {
        for (int i = 0; i < values.size(); i++) {
            Cell cell = row.createCell(i);
            Object value = values.get(i);
            if (value instanceof Number number) cell.setCellValue(number.doubleValue());
            else cell.setCellValue(safe(value));
            if (style != null) cell.setCellStyle(style);
        }
    }

    private PdfPCell cell(Object value, boolean header) {
        PdfPCell cell = new PdfPCell(new Phrase(safe(value), font(9, header ? Font.BOLD : Font.NORMAL)));
        cell.setPadding(6);
        if (header) cell.setBackgroundColor(new Color(225, 237, 250));
        return cell;
    }

    private Font font(float size, int style) {
        return new Font(pdfBaseFont(), size, style, Color.BLACK);
    }

    private BaseFont pdfBaseFont() {
        try {
            List<String> paths = new ArrayList<>();
            String configured = System.getenv("SSE_PDF_FONT_PATH");
            if (configured != null) paths.add(configured);
            paths.add("C:/Windows/Fonts/arial.ttf");
            paths.add("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf");
            for (String path : paths) {
                if (new File(path).isFile()) return BaseFont.createFont(path, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
            }
            return BaseFont.createFont(BaseFont.HELVETICA, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);
        } catch (Exception error) {
            throw new IllegalStateException("Không thể nạp phông chữ PDF", error);
        }
    }

    private String filterText(String semesterId, String classId, String subjectId,
                              java.time.LocalDate startDate, java.time.LocalDate endDate, String periodId) {
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("semesterId", semesterId);
        filters.put("classId", classId);
        filters.put("subjectId", subjectId);
        filters.put("startDate", startDate);
        filters.put("endDate", endDate);
        filters.put("periodId", periodId);
        String text = filters.entrySet().stream().filter(entry -> entry.getValue() != null)
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(", "));
        return text.isBlank() ? "Không có" : text;
    }

    private String safe(Object value) {
        String text = value == null ? "" : value.toString();
        return !text.isEmpty() && "=+-@".indexOf(text.charAt(0)) >= 0 ? "'" + text : text;
    }

    private record ExportTable(String title, List<String> headers, List<List<Object>> rows) {}
}
