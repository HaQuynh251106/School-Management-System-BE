package com.sse.app.academic.planning;

import com.sse.app.academic.planning.AcademicPlanningDtos.AnnualSubjectSummary;
import com.sse.app.academic.planning.AcademicPlanningDtos.PlanValidationReport;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.academic.teaching.TeachingAssignmentService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

@Service
public class AcademicPlanReportService {
    private final AcademicPlanningService planning;
    private final AcademicPlanCompletionService completion;
    private final StructureService structure;
    private final TeachingAssignmentService teaching;

    public AcademicPlanReportService(
            AcademicPlanningService planning,
            AcademicPlanCompletionService completion,
            StructureService structure,
            TeachingAssignmentService teaching) {
        this.planning = planning;
        this.completion = completion;
        this.structure = structure;
        this.teaching = teaching;
    }

    public byte[] excel(String planId) {
        AcademicTrainingPlan plan = planning.getPlan(planId);
        List<AnnualSubjectSummary> summaries = completion.annualSummary(planId);
        List<AcademicTrainingPlanSubject> subjects = planning.listSubjects(planId);
        PlanValidationReport validation = completion.validate(planId);
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            CellStyle header = header(workbook);
            Sheet overview = workbook.createSheet("Tong quan");
            addPair(overview, 0, "Kế hoạch", plan.getName());
            addPair(overview, 1, "Năm học", structure.getYear(plan.getAcademicYearId()).getName());
            addPair(overview, 2, "Khối", plan.getGradeLevel());
            addPair(overview, 3, "Phiên bản", plan.getVersionNumber());
            addPair(overview, 4, "Trạng thái", plan.getStatus());
            addPair(overview, 5, "Lỗi bắt buộc", validation.errorCount());
            addPair(overview, 6, "Cảnh báo", validation.warningCount());

            Sheet annual = workbook.createSheet("So tiet ca nam");
            row(annual, 0, header, "Môn học", "Loại môn", "HK1", "HK2", "Cả năm", "Theo chương trình", "Khớp");
            int index = 1;
            for (AnnualSubjectSummary item : summaries) {
                row(annual, index++, null, item.subjectName(), item.subjectType(),
                        item.semester1Periods(), item.semester2Periods(), item.annualPeriods(),
                        item.configuredAnnualPeriods(), item.periodsMatch() ? "Có" : "Không");
            }

            Sheet weekly = workbook.createSheet("Phan phoi theo tuan");
            row(weekly, 0, header, "Học kỳ", "Môn", "Tuần", "Loại", "Nội dung", "Số tiết", "Ghi chú");
            index = 1;
            for (AcademicTrainingPlanSubject subject : subjects) {
                for (AcademicCurriculumDistribution item
                        : completion.listDistributions(planId, subject.getId())) {
                    row(weekly, index++, null, subject.getSemesterId(),
                            structure.subjectName(subject.getSubjectId()), item.getWeekNumber(),
                            item.getContentType(), item.getTitle(), item.getPeriods(), item.getNotes());
                }
            }

            Sheet assignments = workbook.createSheet("Phan cong giao vien");
            row(assignments, 0, header, "Lớp", "Môn", "Giáo viên", "Học kỳ", "Tiết/tuần", "Trạng thái");
            index = 1;
            for (var item : teaching.list(null, null, null, null, "ACTIVE")) {
                if (structure.getClass(item.classId()).getAcademicYearId().equals(plan.getAcademicYearId())
                        && structure.getClass(item.classId()).getGradeLevel().equals(plan.getGradeLevel())) {
                    row(assignments, index++, null, item.classCode(), item.subjectName(),
                            item.teacherName(), item.semesterId(), item.weeklyPeriods(), item.status());
                }
            }

            Sheet checks = workbook.createSheet("Kiem tra du lieu");
            row(checks, 0, header, "Mức", "Mã kiểm tra", "Nội dung", "Tham chiếu");
            index = 1;
            for (var issue : validation.issues()) {
                row(checks, index++, null, issue.level(), issue.code(), issue.message(), issue.referenceId());
            }

            Sheet history = workbook.createSheet("Lich su phe duyet");
            row(history, 0, header, "Thời gian", "Hành động", "Từ", "Đến", "Người thực hiện", "Nhận xét");
            index = 1;
            for (var item : completion.history(planId)) {
                row(history, index++, null, String.valueOf(item.createdAt()), item.action(),
                        item.fromStatus(), item.toStatus(),
                        item.actorName() + (item.actorRole() == null ? "" : " · " + item.actorRole()),
                        item.comment());
            }
            for (Sheet sheet : workbook) {
                for (int column = 0; column < Math.min(8, sheet.getRow(0).getLastCellNum()); column++) {
                    sheet.autoSizeColumn(column);
                    sheet.setColumnWidth(column, Math.min(18000, sheet.getColumnWidth(column) + 1000));
                }
                sheet.createFreezePane(0, 1);
            }
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Không thể xuất báo cáo kế hoạch Excel", exception);
        }
    }

    public byte[] pdf(String planId) {
        AcademicTrainingPlan plan = planning.getPlan(planId);
        List<AnnualSubjectSummary> summaries = completion.annualSummary(planId);
        PlanValidationReport validation = completion.validate(planId);
        BufferedImage image = new BufferedImage(1240, 1754, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(new Color(15, 38, 69));
            graphics.fillRect(0, 0, 1240, 185);
            graphics.setColor(Color.WHITE);
            graphics.setFont(new Font("Arial", Font.BOLD, 36));
            graphics.drawString("KẾ HOẠCH GIÁO DỤC NĂM HỌC", 65, 76);
            graphics.setFont(new Font("Arial", Font.PLAIN, 22));
            graphics.drawString(plan.getName(), 65, 120);
            graphics.drawString(plan.getGradeLevel() + " · Phiên bản " + plan.getVersionNumber()
                    + " · " + plan.getStatus(), 65, 154);
            int y = 245;
            graphics.setColor(new Color(30, 41, 59));
            graphics.setFont(new Font("Arial", Font.BOLD, 23));
            graphics.drawString("Phân bổ số tiết", 65, y);
            y += 42;
            graphics.setFont(new Font("Arial", Font.BOLD, 17));
            graphics.drawString("Môn học", 75, y);
            graphics.drawString("HK1", 650, y);
            graphics.drawString("HK2", 780, y);
            graphics.drawString("Cả năm", 910, y);
            graphics.drawString("Kết quả", 1050, y);
            y += 28;
            graphics.setFont(new Font("Arial", Font.PLAIN, 17));
            for (AnnualSubjectSummary item : summaries) {
                if (y > 1320) break;
                graphics.setColor(new Color(248, 250, 252));
                graphics.fillRect(65, y - 22, 1110, 38);
                graphics.setColor(new Color(30, 41, 59));
                graphics.drawString(item.subjectName(), 75, y + 3);
                graphics.drawString(String.valueOf(item.semester1Periods()), 650, y + 3);
                graphics.drawString(String.valueOf(item.semester2Periods()), 780, y + 3);
                graphics.drawString(String.valueOf(item.annualPeriods()), 910, y + 3);
                graphics.setColor(item.periodsMatch() ? new Color(5, 150, 105) : new Color(220, 38, 38));
                graphics.drawString(item.periodsMatch() ? "Đạt" : "Chưa khớp", 1050, y + 3);
                y += 40;
            }
            y = Math.max(y + 50, 1380);
            graphics.setColor(new Color(30, 41, 59));
            graphics.setFont(new Font("Arial", Font.BOLD, 22));
            graphics.drawString("Kiểm tra trước phê duyệt", 65, y);
            graphics.setFont(new Font("Arial", Font.PLAIN, 19));
            graphics.drawString("Lỗi bắt buộc: " + validation.errorCount()
                    + "    Cảnh báo: " + validation.warningCount(), 65, y + 38);
            graphics.drawString(validation.valid()
                    ? "Kế hoạch đủ điều kiện gửi duyệt."
                    : "Kế hoạch chưa đủ điều kiện gửi duyệt.", 65, y + 75);
        } finally {
            graphics.dispose();
        }
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            PDImageXObject pageImage = LosslessFactory.createFromImage(document, image);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.drawImage(pageImage, 0, 0, page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
            }
            document.save(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Không thể xuất báo cáo kế hoạch PDF", exception);
        }
    }

    private CellStyle header(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        var font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private void addPair(Sheet sheet, int index, String label, Object value) {
        Row row = sheet.createRow(index);
        row.createCell(0).setCellValue(label);
        row.createCell(1).setCellValue(value == null ? "" : String.valueOf(value));
    }

    private void row(Sheet sheet, int index, CellStyle style, Object... values) {
        Row row = sheet.createRow(index);
        for (int column = 0; column < values.length; column++) {
            Object value = values[column];
            if (value instanceof Number number) row.createCell(column).setCellValue(number.doubleValue());
            else row.createCell(column).setCellValue(value == null ? "" : String.valueOf(value));
            if (style != null) row.getCell(column).setCellStyle(style);
        }
    }
}
