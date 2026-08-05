package com.sse.app.finance;

import com.sse.app.finance.FinanceReportDtos.FinanceCashFlowRow;
import com.sse.app.finance.FinanceReportDtos.FinanceDebtDetailRow;
import com.sse.app.finance.FinanceReportDtos.FinanceMethodRow;
import com.sse.app.finance.FinanceReportDtos.FinanceReportResponse;
import com.sse.app.finance.FinanceReportDtos.FinanceReportSummary;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.NumberFormat;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

@Component
public class FinanceReportPdfRenderer {
    private static final int WIDTH = 1754;
    private static final int HEIGHT = 1240;
    private static final int DEBT_ROWS_PER_PAGE = 20;
    private static final ZoneId SCHOOL_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
            .withZone(SCHOOL_ZONE);

    public byte[] render(FinanceReportResponse report) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            addImagePage(document, summaryPage(report));
            List<FinanceDebtDetailRow> debts = report.debts();
            if (debts.isEmpty()) {
                addImagePage(document, debtPage(report, List.of(), 1, 1));
            } else {
                int pages = (int) Math.ceil(debts.size() / (double) DEBT_ROWS_PER_PAGE);
                for (int page = 0; page < pages; page++) {
                    int start = page * DEBT_ROWS_PER_PAGE;
                    int end = Math.min(debts.size(), start + DEBT_ROWS_PER_PAGE);
                    addImagePage(document, debtPage(report, debts.subList(start, end), page + 1, pages));
                }
            }
            document.save(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Không thể tạo báo cáo PDF", exception);
        }
    }

    private BufferedImage summaryPage(FinanceReportResponse report) {
        BufferedImage image = canvas();
        Graphics2D graphics = image.createGraphics();
        try {
            pageHeader(graphics, "BÁO CÁO TÀI CHÍNH", "Tổng hợp phải thu, thực thu, hoàn tiền và công nợ");
            graphics.setColor(Palette.MUTED);
            graphics.setFont(font(Font.PLAIN, 20));
            graphics.drawString("Khoảng giao dịch: " + report.filters().fromDate() + " đến " + report.filters().toDate(), 72, 195);
            graphics.drawString("Tạo lúc: " + DATE_TIME.format(report.generatedAt()), 1210, 195);

            FinanceReportSummary summary = report.summary();
            metric(graphics, 72, 235, "TỔNG PHẢI THU", money(summary.totalReceivable()), summary.invoiceCount() + " hóa đơn", Palette.BLUE);
            metric(graphics, 482, 235, "THỰC THU", money(summary.grossCollected()), summary.paymentCount() + " giao dịch", Palette.TEAL);
            metric(graphics, 892, 235, "HOÀN TIỀN", money(summary.refundAmount()), summary.refundCount() + " lần hoàn", Palette.RED);
            metric(graphics, 1302, 235, "THỰC THU RÒNG", money(summary.netRevenue()), "Thực thu - hoàn tiền", Palette.INK);
            metric(graphics, 72, 390, "CÒN PHẢI THU", money(summary.outstandingAmount()), summary.outstandingInvoiceCount() + " hóa đơn", Palette.ORANGE);
            metric(graphics, 482, 390, "NỢ QUÁ HẠN", money(summary.overdueAmount()), summary.overdueInvoiceCount() + " hóa đơn", Palette.RED);
            metric(graphics, 892, 390, "ĐÃ GHI NHẬN", money(summary.currentPaidAmount()), summary.paidInvoiceCount() + " hóa đơn đủ tiền", Palette.TEAL);
            metric(graphics, 1302, 390, "PHẠM VI", scopeLabel(report), "Theo lớp hiện tại", Palette.BLUE);

            drawCashFlowTable(graphics, report.dailyCashFlow().stream()
                    .filter(row -> row.grossCollected() != 0 || row.refundAmount() != 0)
                    .limit(11).toList(), 72, 585);
            drawMethodTable(graphics, report.byMethod(), 1010, 585);
            footer(graphics, "Dữ liệu thực thu lấy từ payment đã quyết toán; công nợ lấy từ hóa đơn hiện tại.");
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private BufferedImage debtPage(FinanceReportResponse report, List<FinanceDebtDetailRow> rows,
                                   int pageNumber, int totalPages) {
        BufferedImage image = canvas();
        Graphics2D graphics = image.createGraphics();
        try {
            pageHeader(graphics, "CHI TIẾT CÔNG NỢ", "Các hóa đơn còn số tiền phải thanh toán");
            graphics.setColor(Palette.MUTED);
            graphics.setFont(font(Font.PLAIN, 20));
            graphics.drawString("Trang " + pageNumber + "/" + totalPages + " · Tổng " + report.debts().size() + " hóa đơn còn nợ", 72, 195);
            int y = 235;
            tableHeader(graphics, y, new Column("HÓA ĐƠN", 72, 265), new Column("HỌC SINH", 337, 300),
                    new Column("LỚP", 637, 120), new Column("ĐỢT THU", 757, 220),
                    new Column("PHẢI THU", 977, 190), new Column("ĐÃ THU", 1167, 180),
                    new Column("CÒN NỢ", 1347, 190), new Column("HẠN", 1537, 145));
            y += 54;
            if (rows.isEmpty()) {
                graphics.setColor(Palette.MUTED);
                graphics.setFont(font(Font.BOLD, 26));
                graphics.drawString("Không có công nợ trong phạm vi đã chọn.", 72, y + 70);
            }
            for (FinanceDebtDetailRow row : rows) {
                graphics.setColor(row.overdue() ? Palette.SOFT_RED : Color.WHITE);
                graphics.fillRect(72, y, 1610, 43);
                graphics.setColor(Palette.INK);
                graphics.setFont(font(Font.PLAIN, 17));
                text(graphics, row.invoiceCode(), 82, y + 28, 245);
                text(graphics, joined(row.studentCode(), row.studentName()), 347, y + 28, 280);
                text(graphics, row.classCode(), 647, y + 28, 100);
                text(graphics, value(row.feePeriodCode()), 767, y + 28, 200);
                right(graphics, money(row.totalAmount()), 1157, y + 28);
                right(graphics, money(row.paidAmount()), 1337, y + 28);
                graphics.setColor(row.overdue() ? Palette.RED : Palette.INK);
                right(graphics, money(row.outstandingAmount()), 1527, y + 28);
                text(graphics, row.dueDate() == null ? "-" : row.dueDate().toString(), 1547, y + 28, 125);
                graphics.setColor(Palette.LINE);
                graphics.drawLine(72, y + 43, 1682, y + 43);
                y += 43;
            }
            footer(graphics, "Dòng nền đỏ là công nợ đã quá hạn tại thời điểm xuất báo cáo.");
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private void drawCashFlowTable(Graphics2D graphics, List<FinanceCashFlowRow> rows, int x, int y) {
        graphics.setColor(Palette.INK);
        graphics.setFont(font(Font.BOLD, 25));
        graphics.drawString("Dòng tiền theo ngày", x, y);
        y += 30;
        tableHeader(graphics, y, new Column("NGÀY", x, 170), new Column("THỰC THU", x + 170, 220),
                new Column("HOÀN TIỀN", x + 390, 210), new Column("RÒNG", x + 600, 220));
        y += 52;
        if (rows.isEmpty()) {
            graphics.setColor(Palette.MUTED);
            graphics.setFont(font(Font.PLAIN, 19));
            graphics.drawString("Không có giao dịch trong kỳ.", x, y + 30);
            return;
        }
        for (FinanceCashFlowRow row : rows) {
            graphics.setColor(Palette.INK);
            graphics.setFont(font(Font.PLAIN, 18));
            graphics.drawString(row.date().toString(), x + 10, y + 29);
            right(graphics, money(row.grossCollected()), x + 380, y + 29);
            right(graphics, money(row.refundAmount()), x + 590, y + 29);
            graphics.setFont(font(Font.BOLD, 18));
            right(graphics, money(row.netRevenue()), x + 810, y + 29);
            graphics.setColor(Palette.LINE);
            graphics.drawLine(x, y + 40, x + 820, y + 40);
            y += 40;
        }
    }

    private void drawMethodTable(Graphics2D graphics, List<FinanceMethodRow> rows, int x, int y) {
        graphics.setColor(Palette.INK);
        graphics.setFont(font(Font.BOLD, 25));
        graphics.drawString("Theo phương thức", x, y);
        y += 30;
        tableHeader(graphics, y, new Column("PHƯƠNG THỨC", x, 230), new Column("THỰC THU", x + 230, 210),
                new Column("HOÀN", x + 440, 180));
        y += 52;
        if (rows.isEmpty()) {
            graphics.setColor(Palette.MUTED);
            graphics.setFont(font(Font.PLAIN, 19));
            graphics.drawString("Không có giao dịch.", x, y + 30);
            return;
        }
        for (FinanceMethodRow row : rows) {
            graphics.setColor(Palette.INK);
            graphics.setFont(font(Font.PLAIN, 18));
            text(graphics, methodLabel(row.method()), x + 10, y + 29, 210);
            right(graphics, money(row.grossCollected()), x + 430, y + 29);
            right(graphics, money(row.refundAmount()), x + 610, y + 29);
            graphics.setColor(Palette.LINE);
            graphics.drawLine(x, y + 40, x + 620, y + 40);
            y += 40;
        }
    }

    private void pageHeader(Graphics2D graphics, String title, String subtitle) {
        graphics.setColor(Palette.INK);
        graphics.fillRect(0, 0, WIDTH, 155);
        graphics.setColor(Color.WHITE);
        graphics.setFont(font(Font.BOLD, 34));
        graphics.drawString("SMART SCHOOL ECOSYSTEM", 72, 64);
        graphics.setFont(font(Font.BOLD, 31));
        right(graphics, title, WIDTH - 72, 64);
        graphics.setFont(font(Font.PLAIN, 20));
        right(graphics, subtitle, WIDTH - 72, 105);
        graphics.setColor(Palette.TEAL);
        graphics.fillRect(0, 155, WIDTH, 8);
    }

    private void metric(Graphics2D graphics, int x, int y, String label, String amount, String note, Color accent) {
        graphics.setColor(Palette.SOFT);
        graphics.fillRoundRect(x, y, 380, 125, 10, 10);
        graphics.setColor(accent);
        graphics.fillRoundRect(x, y, 8, 125, 8, 8);
        graphics.setFont(font(Font.BOLD, 17));
        graphics.drawString(label, x + 28, y + 33);
        graphics.setColor(Palette.INK);
        graphics.setFont(font(Font.BOLD, 27));
        text(graphics, amount, x + 28, y + 72, 330);
        graphics.setColor(Palette.MUTED);
        graphics.setFont(font(Font.PLAIN, 16));
        text(graphics, note, x + 28, y + 103, 330);
    }

    private void tableHeader(Graphics2D graphics, int y, Column... columns) {
        graphics.setColor(Palette.INK);
        int start = columns[0].x();
        int end = columns[columns.length - 1].x() + columns[columns.length - 1].width();
        graphics.fillRect(start, y, end - start, 48);
        graphics.setColor(Color.WHITE);
        graphics.setFont(font(Font.BOLD, 16));
        for (Column column : columns) graphics.drawString(column.label(), column.x() + 10, y + 31);
    }

    private void footer(Graphics2D graphics, String note) {
        graphics.setColor(Palette.LINE);
        graphics.drawLine(72, HEIGHT - 80, WIDTH - 72, HEIGHT - 80);
        graphics.setColor(Palette.MUTED);
        graphics.setFont(font(Font.PLAIN, 16));
        graphics.drawString(note, 72, HEIGHT - 48);
        right(graphics, "SSE · P5 Finance Report", WIDTH - 72, HEIGHT - 48);
    }

    private BufferedImage canvas() {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, WIDTH, HEIGHT);
        graphics.dispose();
        return image;
    }

    private void addImagePage(PDDocument document, BufferedImage image) throws IOException {
        PDPage page = new PDPage(new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth()));
        document.addPage(page);
        PDImageXObject pageImage = LosslessFactory.createFromImage(document, image);
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            content.drawImage(pageImage, 0, 0, page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
        }
    }

    private void text(Graphics2D graphics, String value, int x, int y, int maxWidth) {
        String normalized = value(value);
        FontMetrics metrics = graphics.getFontMetrics();
        if (metrics.stringWidth(normalized) <= maxWidth) {
            graphics.drawString(normalized, x, y);
            return;
        }
        String suffix = "...";
        while (!normalized.isEmpty() && metrics.stringWidth(normalized + suffix) > maxWidth) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        graphics.drawString(normalized + suffix, x, y);
    }

    private void right(Graphics2D graphics, String value, int right, int y) {
        graphics.drawString(value, right - graphics.getFontMetrics().stringWidth(value), y);
    }

    private Font font(int style, int size) {
        return new Font(Font.SANS_SERIF, style, size);
    }

    private String money(long amount) {
        return NumberFormat.getIntegerInstance(new Locale("vi", "VN")).format(amount) + " đ";
    }

    private String methodLabel(String method) {
        return switch (value(method)) {
            case "MB_BANK_TRANSFER" -> "Chuyển khoản MB";
            case "CASH" -> "Tiền mặt";
            case "MOMO" -> "MoMo";
            case "VNPAY" -> "VNPAY";
            default -> value(method);
        };
    }

    private String scopeLabel(FinanceReportResponse report) {
        if (report.filters().studentId() != null) return "1 học sinh";
        if (report.filters().classId() != null) return "1 lớp";
        if (report.filters().gradeLevel() != null) return report.filters().gradeLevel();
        if (report.filters().feePeriodId() != null) return "1 đợt thu";
        return "Toàn trường";
    }

    private String joined(String first, String second) {
        if (first == null || first.isBlank()) return value(second);
        if (second == null || second.isBlank()) return first;
        return first + " · " + second;
    }

    private String value(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private record Column(String label, int x, int width) {}

    private static final class Palette {
        private static final Color INK = new Color(20, 31, 51);
        private static final Color MUTED = new Color(93, 107, 128);
        private static final Color LINE = new Color(218, 226, 236);
        private static final Color SOFT = new Color(245, 248, 252);
        private static final Color SOFT_RED = new Color(255, 242, 240);
        private static final Color BLUE = new Color(37, 99, 235);
        private static final Color TEAL = new Color(15, 118, 110);
        private static final Color ORANGE = new Color(180, 83, 9);
        private static final Color RED = new Color(180, 35, 24);
    }
}
