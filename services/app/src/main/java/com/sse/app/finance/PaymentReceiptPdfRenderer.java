package com.sse.app.finance;

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
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@Component
public class PaymentReceiptPdfRenderer {
    private static final int WIDTH = 1240;
    private static final int HEIGHT = 1754;
    private static final ZoneId SCHOOL_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
            .withZone(SCHOOL_ZONE);

    public byte[] render(ReceiptData data) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            drawReceipt(graphics, data);
        } finally {
            graphics.dispose();
        }

        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            PDImageXObject pageImage = LosslessFactory.createFromImage(document, image);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.drawImage(pageImage, 0, 0, PDRectangle.A4.getWidth(), PDRectangle.A4.getHeight());
            }
            document.save(output);
            return output.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Không thể tạo PDF biên nhận", ex);
        }
    }

    private void drawReceipt(Graphics2D graphics, ReceiptData data) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, WIDTH, HEIGHT);

        Color ink = new Color(23, 32, 51);
        Color muted = new Color(93, 107, 128);
        Color accent = new Color(15, 118, 110);
        Color line = new Color(218, 226, 236);
        Color soft = new Color(244, 248, 250);

        graphics.setColor(ink);
        graphics.fillRect(0, 0, WIDTH, 210);
        graphics.setColor(Color.WHITE);
        graphics.setFont(font(Font.BOLD, 38));
        graphics.drawString("SMART SCHOOL ECOSYSTEM", 90, 86);
        graphics.setFont(font(Font.PLAIN, 24));
        graphics.drawString("Hệ thống quản lý nhà trường", 90, 130);

        graphics.setColor(accent);
        graphics.fillRect(0, 210, WIDTH, 10);
        graphics.setColor(ink);
        graphics.setFont(font(Font.BOLD, 48));
        graphics.drawString("BIÊN NHẬN THANH TOÁN", 90, 325);
        graphics.setColor(muted);
        graphics.setFont(font(Font.PLAIN, 24));
        graphics.drawString("Xác nhận khoản thu đã được ghi nhận trên hệ thống", 90, 370);

        graphics.setColor(soft);
        graphics.fillRoundRect(90, 420, WIDTH - 180, 135, 12, 12);
        graphics.setColor(muted);
        graphics.setFont(font(Font.BOLD, 20));
        graphics.drawString("SỐ BIÊN NHẬN", 125, 468);
        graphics.setColor(accent);
        graphics.setFont(font(Font.BOLD, 34));
        graphics.drawString(data.receiptNumber(), 125, 520);
        graphics.setColor(muted);
        graphics.setFont(font(Font.PLAIN, 20));
        graphics.drawString("Ngày phát hành", 825, 468);
        graphics.setColor(ink);
        graphics.setFont(font(Font.BOLD, 22));
        graphics.drawString(DATE_TIME.format(data.issuedAt()), 825, 510);

        int y = 630;
        y = row(graphics, y, "Học sinh", value(data.studentName()), ink, muted, line);
        y = row(graphics, y, "Mã học sinh", value(data.studentCode()), ink, muted, line);
        y = row(graphics, y, "Mã hóa đơn", value(data.invoiceCode()), ink, muted, line);
        y = row(graphics, y, "Đợt thu", joined(data.feePeriodCode(), data.feePeriodName()), ink, muted, line);
        y = row(graphics, y, "Phương thức", methodLabel(data.method()), ink, muted, line);
        y = row(graphics, y, "Mã giao dịch", value(data.txnRef()), ink, muted, line);
        y = row(graphics, y, "Thời gian thanh toán", DATE_TIME.format(data.paidAt()), ink, muted, line);

        graphics.setColor(soft);
        graphics.fillRoundRect(90, y + 25, WIDTH - 180, 150, 12, 12);
        graphics.setColor(muted);
        graphics.setFont(font(Font.BOLD, 22));
        graphics.drawString("SỐ TIỀN ĐÃ THU", 125, y + 78);
        graphics.setColor(accent);
        graphics.setFont(font(Font.BOLD, 46));
        graphics.drawString(formatMoney(data.amount()), 125, y + 135);

        int noteY = y + 240;
        graphics.setColor(muted);
        graphics.setFont(font(Font.BOLD, 20));
        graphics.drawString("Ghi chú", 90, noteY);
        graphics.setColor(ink);
        graphics.setFont(font(Font.PLAIN, 21));
        drawWrapped(graphics, value(data.note()), 90, noteY + 38, WIDTH - 180, 31, 3);

        graphics.setColor(line);
        graphics.drawLine(90, HEIGHT - 205, WIDTH - 90, HEIGHT - 205);
        graphics.setColor(muted);
        graphics.setFont(font(Font.PLAIN, 18));
        graphics.drawString("Biên nhận được phát hành tự động và lưu trữ trên hệ thống SSE.", 90, HEIGHT - 155);
        graphics.drawString("Vui lòng liên hệ nhà trường nếu thông tin trên biên nhận chưa chính xác.", 90, HEIGHT - 120);
        graphics.setColor(ink);
        graphics.setFont(font(Font.BOLD, 18));
        graphics.drawString("Mã payment: " + data.paymentId(), 90, HEIGHT - 75);
    }

    private int row(Graphics2D graphics, int y, String label, String value,
                    Color ink, Color muted, Color line) {
        graphics.setColor(muted);
        graphics.setFont(font(Font.PLAIN, 21));
        graphics.drawString(label, 90, y);
        graphics.setColor(ink);
        graphics.setFont(font(Font.BOLD, 22));
        graphics.drawString(value, 420, y);
        graphics.setColor(line);
        graphics.drawLine(90, y + 34, WIDTH - 90, y + 34);
        return y + 90;
    }

    private void drawWrapped(Graphics2D graphics, String text, int x, int y, int maxWidth,
                             int lineHeight, int maxLines) {
        FontMetrics metrics = graphics.getFontMetrics();
        String[] words = text.split("\\s+");
        StringBuilder line = new StringBuilder();
        int currentY = y;
        int lines = 0;
        for (String word : words) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (metrics.stringWidth(candidate) <= maxWidth) {
                line.setLength(0);
                line.append(candidate);
                continue;
            }
            graphics.drawString(line.toString(), x, currentY);
            currentY += lineHeight;
            lines++;
            if (lines >= maxLines) return;
            line.setLength(0);
            line.append(word);
        }
        if (!line.isEmpty() && lines < maxLines) graphics.drawString(line.toString(), x, currentY);
    }

    private Font font(int style, int size) {
        return new Font(Font.SANS_SERIF, style, size);
    }

    private String formatMoney(long amount) {
        NumberFormat format = NumberFormat.getIntegerInstance(new Locale("vi", "VN"));
        return format.format(amount) + " VND";
    }

    private String methodLabel(String method) {
        if (method == null) return "—";
        return switch (method) {
            case "MB_BANK_TRANSFER" -> "Chuyển khoản MB Bank";
            case "CASH" -> "Tiền mặt";
            case "MOMO" -> "Ví MoMo";
            case "VNPAY" -> "VNPAY";
            default -> method;
        };
    }

    private String joined(String code, String name) {
        if (code == null || code.isBlank()) return value(name);
        if (name == null || name.isBlank()) return code;
        return code + " · " + name;
    }

    private String value(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    public record ReceiptData(
            String receiptNumber,
            String paymentId,
            String invoiceCode,
            String feePeriodCode,
            String feePeriodName,
            String studentCode,
            String studentName,
            long amount,
            String method,
            String txnRef,
            Instant paidAt,
            Instant issuedAt,
            String note) {}
}
