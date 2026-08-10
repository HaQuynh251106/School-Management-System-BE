package com.sse.app.report;

import com.sse.app.common.ApiException;
import com.sse.app.report.AcademicReportDtos.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Service
public class AcademicReportExportService {
    private final AcademicReportService reports;

    public AcademicReportExportService(AcademicReportService reports) { this.reports = reports; }

    public AcademicReportFile export(String format, AcademicReportFilter filter) {
        AcademicReportResponse report = reports.report(filter);
        String normalized = format == null ? "XLSX" : format.trim().toUpperCase();
        return switch (normalized) {
            case "XLSX", "EXCEL" -> new AcademicReportFile("bao-cao-hoc-vu.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excel(report));
            case "PDF" -> new AcademicReportFile("bao-cao-hoc-vu.pdf", "application/pdf", pdf(report));
            default -> throw ApiException.badRequest("Định dạng chỉ nhận XLSX hoặc PDF");
        };
    }

    private byte[] excel(AcademicReportResponse report) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            CellStyle header = workbook.createCellStyle();
            org.apache.poi.ss.usermodel.Font font = workbook.createFont(); font.setBold(true); header.setFont(font);
            Sheet summary = workbook.createSheet("Tổng quan");
            String[][] metrics = {
                    {"Chỉ số", "Giá trị"}, {"Học sinh", String.valueOf(report.summary().studentCount())},
                    {"Lớp", String.valueOf(report.summary().classCount())}, {"Đầu điểm", String.valueOf(report.summary().gradeEntries())},
                    {"Điểm trung bình", value(report.summary().averageScore())}, {"Chuyên cần", value(report.summary().attendanceRate()) + "%"},
                    {"Bài tập", String.valueOf(report.summary().assignments())}, {"Bài đã nộp", String.valueOf(report.summary().submittedAssignments())},
                    {"Bài đã chấm", String.valueOf(report.summary().gradedAssignments())}
            };
            for (int i = 0; i < metrics.length; i++) { Row row = summary.createRow(i); row.createCell(0).setCellValue(metrics[i][0]); row.createCell(1).setCellValue(metrics[i][1]); if (i == 0) { row.getCell(0).setCellStyle(header); row.getCell(1).setCellStyle(header); } }
            Sheet students = workbook.createSheet("Học sinh");
            String[] studentHeaders = {"Mã HS", "Họ tên", "Lớp", "Số đầu điểm", "Điểm TB", "Có mặt", "Đi muộn", "Vắng phép", "Vắng không phép", "Chuyên cần %", "Bài được giao", "Đã nộp", "Đã chấm"};
            writeHeader(students, header, studentHeaders);
            int index = 1;
            for (AcademicStudentRow item : report.students()) { Row row = students.createRow(index++); Object[] values = {item.studentCode(), item.studentName(), item.className(), item.gradeEntries(), item.averageScore(), item.present(), item.late(), item.absentExcused(), item.absentUnexcused(), item.attendanceRate(), item.assignments(), item.submittedAssignments(), item.gradedAssignments()}; writeValues(row, values); }
            Sheet subjects = workbook.createSheet("Môn học");
            writeHeader(subjects, header, new String[]{"Môn", "Số đầu điểm", "Số học sinh", "Điểm trung bình"});
            index = 1;
            for (AcademicSubjectRow item : report.subjects()) writeValues(subjects.createRow(index++), new Object[]{item.subjectName(), item.gradeEntries(), item.studentCount(), item.averageScore()});
            for (Sheet sheet : java.util.List.of(summary, students, subjects)) for (int i = 0; i < Math.min(15, sheet.getRow(0).getLastCellNum()); i++) sheet.autoSizeColumn(i);
            workbook.write(output); return output.toByteArray();
        } catch (Exception error) { throw new IllegalStateException("Không thể tạo báo cáo Excel", error); }
    }

    private byte[] pdf(AcademicReportResponse report) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            int pageSize = 18;
            int pages = Math.max(1, (int) Math.ceil(report.students().size() / (double) pageSize));
            for (int pageNo = 0; pageNo < pages; pageNo++) {
                BufferedImage image = new BufferedImage(1754, 1240, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = image.createGraphics(); g.setColor(Color.WHITE); g.fillRect(0, 0, image.getWidth(), image.getHeight());
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setColor(new Color(20, 35, 60)); g.setFont(new Font("Arial", Font.BOLD, 34)); g.drawString("BÁO CÁO HỌC VỤ", 70, 80);
                g.setFont(new Font("Arial", Font.PLAIN, 18)); g.drawString("Tạo lúc " + DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.of("Asia/Ho_Chi_Minh")).format(report.generatedAt()), 70, 118);
                AcademicReportSummary s = report.summary(); g.setFont(new Font("Arial", Font.BOLD, 20));
                g.drawString("Học sinh: " + s.studentCount() + "   Điểm TB: " + value(s.averageScore()) + "   Chuyên cần: " + value(s.attendanceRate()) + "%   Bài đã chấm: " + s.gradedAssignments(), 70, 165);
                int y = 220; g.setColor(new Color(235, 240, 248)); g.fillRect(60, y - 34, 1630, 48); g.setColor(new Color(20, 35, 60)); g.drawString("Mã HS", 75, y); g.drawString("Họ tên", 260, y); g.drawString("Lớp", 760, y); g.drawString("Điểm TB", 930, y); g.drawString("Chuyên cần", 1120, y); g.drawString("Nộp/chấm", 1390, y);
                g.setFont(new Font("Arial", Font.PLAIN, 18));
                int start = pageNo * pageSize, end = Math.min(report.students().size(), start + pageSize);
                for (AcademicStudentRow row : report.students().subList(start, end)) { y += 50; g.setColor(new Color(225, 230, 238)); g.drawLine(60, y + 12, 1690, y + 12); g.setColor(new Color(30, 40, 55)); g.drawString(safe(row.studentCode()), 75, y); g.drawString(safe(row.studentName()), 260, y); g.drawString(safe(row.className()), 760, y); g.drawString(value(row.averageScore()), 930, y); g.drawString(value(row.attendanceRate()) + "%", 1120, y); g.drawString(row.submittedAssignments() + "/" + row.gradedAssignments(), 1390, y); }
                g.dispose();
                PDPage page = new PDPage(new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth()));
                document.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) { stream.drawImage(LosslessFactory.createFromImage(document, image), 0, 0, page.getMediaBox().getWidth(), page.getMediaBox().getHeight()); }
            }
            document.save(output); return output.toByteArray();
        } catch (Exception error) { throw new IllegalStateException("Không thể tạo báo cáo PDF", error); }
    }

    private void writeHeader(Sheet sheet, CellStyle style, String[] values) { Row row = sheet.createRow(0); for (int i = 0; i < values.length; i++) { Cell cell = row.createCell(i); cell.setCellValue(values[i]); cell.setCellStyle(style); } }
    private void writeValues(Row row, Object[] values) { for (int i = 0; i < values.length; i++) { Cell cell = row.createCell(i); Object value = values[i]; if (value instanceof Number number) cell.setCellValue(number.doubleValue()); else cell.setCellValue(value == null ? "" : String.valueOf(value)); } }
    private String value(Double value) { return value == null ? "—" : String.format(java.util.Locale.US, "%.1f", value); }
    private String safe(String value) { return value == null ? "—" : value; }
}
