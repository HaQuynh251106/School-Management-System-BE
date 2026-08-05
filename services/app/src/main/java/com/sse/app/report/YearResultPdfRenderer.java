package com.sse.app.report;

import com.sse.app.report.YearResultDtos.StudentYearResult;
import com.sse.app.report.YearReviewDtos.AnnualSubjectResult;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Component
public class YearResultPdfRenderer {
    private static final int WIDTH = 1240;
    private static final int HEIGHT = 1754;
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                    .withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    public byte[] render(StudentYearResult result) {
        BufferedImage image = renderPage(result);
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            PDImageXObject pageImage = LosslessFactory.createFromImage(document, image);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.drawImage(pageImage, 0, 0, page.getMediaBox().getWidth(),
                        page.getMediaBox().getHeight());
            }
            document.save(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Không thể tạo phiếu tổng kết PDF", exception);
        }
    }

    private BufferedImage renderPage(StudentYearResult result) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, WIDTH, HEIGHT);

            graphics.setColor(new Color(15, 38, 69));
            graphics.fillRect(0, 0, WIDTH, 190);
            graphics.setColor(Color.WHITE);
            graphics.setFont(font(Font.BOLD, 38));
            graphics.drawString("PHIẾU TỔNG KẾT NĂM HỌC", 70, 86);
            graphics.setFont(font(Font.PLAIN, 22));
            graphics.drawString(result.academicYearName(), 70, 130);
            graphics.drawString("Công bố: " + DATE_TIME.format(result.publishedAt()), 790, 130);

            int y = 245;
            graphics.setColor(new Color(30, 41, 59));
            graphics.setFont(font(Font.BOLD, 25));
            graphics.drawString(value(result.studentName()), 70, y);
            graphics.setFont(font(Font.PLAIN, 20));
            graphics.setColor(new Color(71, 85, 105));
            graphics.drawString("Mã học sinh: " + value(result.studentCode()), 70, y + 38);
            graphics.drawString("Lớp: " + value(result.classCode()), 430, y + 38);

            y += 95;
            metric(graphics, 70, y, "ĐIỂM TRUNG BÌNH NĂM", score(result.yearlyAverage()),
                    new Color(37, 99, 235));
            metric(graphics, 435, y, "CHUYÊN CẦN", percent(result.attendanceRate()),
                    new Color(13, 148, 136));
            metric(graphics, 800, y, "KẾT QUẢ", resultLabel(result.result()),
                    resultColor(result.result()));

            y += 155;
            graphics.setColor(new Color(30, 41, 59));
            graphics.setFont(font(Font.BOLD, 25));
            graphics.drawString("Kết quả theo môn", 70, y);
            y += 30;
            tableHeader(graphics, y);
            y += 52;
            for (AnnualSubjectResult subject : result.subjects()) {
                graphics.setColor(new Color(248, 250, 252));
                graphics.fillRect(70, y, 1100, 48);
                graphics.setColor(new Color(30, 41, 59));
                graphics.setFont(font(Font.PLAIN, 18));
                graphics.drawString(subject.subjectName(), 85, y + 31);
                right(graphics, score(subject.semesterOneAverage()), 655, y + 31);
                right(graphics, score(subject.semesterTwoAverage()), 855, y + 31);
                graphics.setFont(font(Font.BOLD, 18));
                graphics.setColor(subject.belowMinimum()
                        ? new Color(220, 38, 38) : new Color(15, 118, 110));
                right(graphics, score(subject.yearlyAverage()), 1080, y + 31);
                y += 50;
            }
            if (result.subjects().isEmpty()) {
                graphics.setColor(new Color(100, 116, 139));
                graphics.setFont(font(Font.ITALIC, 19));
                graphics.drawString("Chưa có snapshot chi tiết môn học.", 85, y + 35);
                y += 65;
            }

            y = Math.max(y + 45, 1290);
            graphics.setColor(new Color(30, 41, 59));
            graphics.setFont(font(Font.BOLD, 23));
            graphics.drawString("Xếp loại và nhận xét", 70, y);
            y += 48;
            detail(graphics, y, "Hạnh kiểm", conductLabel(result.conductGrade()));
            detail(graphics, y + 48, "Kết quả cuối năm", resultLabel(result.result()));
            detail(graphics, y + 96, "Lớp năm học tiếp theo",
                    result.nextClassCode() == null ? "Chưa xếp lớp" : result.nextClassCode());
            if (result.reason() != null && !result.reason().isBlank()) {
                detail(graphics, y + 144, "Ghi chú", result.reason());
            }

            graphics.setColor(new Color(226, 232, 240));
            graphics.drawLine(70, 1660, 1170, 1660);
            graphics.setColor(new Color(100, 116, 139));
            graphics.setFont(font(Font.PLAIN, 16));
            graphics.drawString("Phiếu được tạo tự động từ dữ liệu đã chốt và công bố của nhà trường.",
                    70, 1692);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private void metric(Graphics2D graphics, int x, int y, String label,
                        String value, Color accent) {
        graphics.setColor(new Color(248, 250, 252));
        graphics.fillRoundRect(x, y, 330, 112, 18, 18);
        graphics.setColor(accent);
        graphics.fillRoundRect(x, y, 8, 112, 8, 8);
        graphics.setFont(font(Font.PLAIN, 16));
        graphics.drawString(label, x + 28, y + 35);
        graphics.setFont(font(Font.BOLD, 27));
        graphics.setColor(new Color(30, 41, 59));
        graphics.drawString(value, x + 28, y + 79);
    }

    private void tableHeader(Graphics2D graphics, int y) {
        graphics.setColor(new Color(226, 232, 240));
        graphics.fillRect(70, y, 1100, 50);
        graphics.setColor(new Color(51, 65, 85));
        graphics.setFont(font(Font.BOLD, 17));
        graphics.drawString("MÔN HỌC", 85, y + 32);
        graphics.drawString("HK1", 595, y + 32);
        graphics.drawString("HK2", 795, y + 32);
        graphics.drawString("CẢ NĂM", 980, y + 32);
    }

    private void detail(Graphics2D graphics, int y, String label, String value) {
        graphics.setColor(new Color(100, 116, 139));
        graphics.setFont(font(Font.PLAIN, 18));
        graphics.drawString(label, 70, y);
        graphics.setColor(new Color(30, 41, 59));
        graphics.setFont(font(Font.BOLD, 19));
        graphics.drawString(value == null || value.isBlank() ? "—" : value, 365, y);
    }

    private void right(Graphics2D graphics, String text, int right, int baseline) {
        graphics.drawString(text, right - graphics.getFontMetrics().stringWidth(text), baseline);
    }

    private Font font(int style, int size) {
        return new Font("Arial", style, size);
    }

    private String score(Double value) {
        return value == null ? "—" : String.format(java.util.Locale.US, "%.1f", value);
    }

    private String percent(Double value) {
        return value == null ? "—" : String.format(java.util.Locale.US, "%.1f%%", value);
    }

    private String resultLabel(String result) {
        return switch (value(result)) {
            case "PROMOTED" -> "Lên lớp";
            case "RETAINED" -> "Lưu ban";
            case "ELIGIBLE_FOR_GRADUATION", "GRADUATED" -> "Đủ điều kiện tốt nghiệp";
            case "INCOMPLETE" -> "Chưa hoàn tất";
            default -> "Chờ xét";
        };
    }

    private Color resultColor(String result) {
        return "RETAINED".equals(result) || "INCOMPLETE".equals(result)
                ? new Color(220, 38, 38) : new Color(22, 163, 74);
    }

    private String conductLabel(String conduct) {
        return switch (value(conduct)) {
            case "GOOD" -> "Tốt";
            case "FAIR" -> "Khá";
            case "PASS" -> "Đạt";
            case "FAIL" -> "Chưa đạt";
            default -> "—";
        };
    }

    private String value(String value) {
        return value == null ? "" : value;
    }
}
