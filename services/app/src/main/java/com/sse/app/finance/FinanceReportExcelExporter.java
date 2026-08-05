package com.sse.app.finance;

import com.sse.app.finance.FinanceReportDtos.FinanceCashFlowRow;
import com.sse.app.finance.FinanceReportDtos.FinanceDebtDetailRow;
import com.sse.app.finance.FinanceReportDtos.FinanceDebtGroupRow;
import com.sse.app.finance.FinanceReportDtos.FinanceMethodRow;
import com.sse.app.finance.FinanceReportDtos.FinanceReportResponse;
import com.sse.app.finance.FinanceReportDtos.FinanceReportSummary;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
public class FinanceReportExcelExporter {
    private static final ZoneId SCHOOL_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
            .withZone(SCHOOL_ZONE);

    public byte[] export(FinanceReportResponse report) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Styles styles = new Styles(workbook);
            summarySheet(workbook, report, styles);
            dailySheet(workbook, report.dailyCashFlow(), styles);
            methodSheet(workbook, report.byMethod(), styles);
            debtGroupSheet(workbook, "Công nợ đợt thu", report.debtByFeePeriod(), styles);
            debtGroupSheet(workbook, "Công nợ khối", report.debtByGrade(), styles);
            debtGroupSheet(workbook, "Công nợ lớp", report.debtByClass(), styles);
            debtDetailSheet(workbook, report.debts(), styles);
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Không thể tạo báo cáo Excel", exception);
        }
    }

    private void summarySheet(Workbook workbook, FinanceReportResponse report, Styles styles) {
        Sheet sheet = workbook.createSheet("Tổng quan");
        int rowIndex = 0;
        Row title = sheet.createRow(rowIndex++);
        cell(title, 0, "BÁO CÁO TÀI CHÍNH", styles.title);
        sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, 3));

        row(sheet, rowIndex++, styles, "Thời gian tạo", DATE_TIME.format(report.generatedAt()));
        row(sheet, rowIndex++, styles, "Khoảng giao dịch",
                report.filters().fromDate() + " đến " + report.filters().toDate());
        row(sheet, rowIndex++, styles, "Đợt thu", value(report.filters().feePeriodId()));
        row(sheet, rowIndex++, styles, "Khối", value(report.filters().gradeLevel()));
        row(sheet, rowIndex++, styles, "Lớp", value(report.filters().classId()));
        row(sheet, rowIndex++, styles, "Học sinh", value(report.filters().studentId()));
        row(sheet, rowIndex++, styles, "Phương thức", value(report.filters().method()));
        rowIndex++;

        header(sheet.createRow(rowIndex++), styles, "Chỉ tiêu", "Số lượng", "Số tiền (VND)", "Ghi chú");
        FinanceReportSummary summary = report.summary();
        metric(sheet, rowIndex++, styles, "Tổng phải thu", summary.invoiceCount(), summary.totalReceivable(), "Hóa đơn đang có hiệu lực");
        metric(sheet, rowIndex++, styles, "Đã ghi nhận trên hóa đơn", summary.paidInvoiceCount(), summary.currentPaidAmount(), "Số đã thu hiện tại sau hoàn tiền");
        metric(sheet, rowIndex++, styles, "Còn phải thu", summary.outstandingInvoiceCount(), summary.outstandingAmount(), "Công nợ hiện tại");
        metric(sheet, rowIndex++, styles, "Công nợ quá hạn", summary.overdueInvoiceCount(), summary.overdueAmount(), "Còn nợ và đã quá hạn");
        metric(sheet, rowIndex++, styles, "Thực thu trong kỳ", summary.paymentCount(), summary.grossCollected(), "Payment thành công hoặc đã đảo sau hoàn toàn phần");
        metric(sheet, rowIndex++, styles, "Hoàn tiền trong kỳ", summary.refundCount(), summary.refundAmount(), "Yêu cầu hoàn đã hoàn tất");
        metric(sheet, rowIndex, styles, "Thực thu ròng", summary.paymentCount(), summary.netRevenue(), "Thực thu trừ hoàn tiền");
        finish(sheet, 4);
    }

    private void dailySheet(Workbook workbook, List<FinanceCashFlowRow> rows, Styles styles) {
        Sheet sheet = workbook.createSheet("Theo ngày");
        header(sheet.createRow(0), styles, "Ngày", "Số giao dịch", "Thực thu", "Số lần hoàn", "Hoàn tiền", "Thực thu ròng");
        int index = 1;
        for (FinanceCashFlowRow item : rows) {
            Row row = sheet.createRow(index++);
            cell(row, 0, item.date().toString(), styles.text);
            number(row, 1, item.paymentCount(), styles.integer);
            number(row, 2, item.grossCollected(), styles.money);
            number(row, 3, item.refundCount(), styles.integer);
            number(row, 4, item.refundAmount(), styles.money);
            number(row, 5, item.netRevenue(), styles.money);
        }
        finish(sheet, 6);
    }

    private void methodSheet(Workbook workbook, List<FinanceMethodRow> rows, Styles styles) {
        Sheet sheet = workbook.createSheet("Theo phương thức");
        header(sheet.createRow(0), styles, "Phương thức", "Số giao dịch", "Thực thu", "Số lần hoàn", "Hoàn tiền", "Thực thu ròng");
        int index = 1;
        for (FinanceMethodRow item : rows) {
            Row row = sheet.createRow(index++);
            cell(row, 0, item.method(), styles.text);
            number(row, 1, item.paymentCount(), styles.integer);
            number(row, 2, item.grossCollected(), styles.money);
            number(row, 3, item.refundCount(), styles.integer);
            number(row, 4, item.refundAmount(), styles.money);
            number(row, 5, item.netRevenue(), styles.money);
        }
        finish(sheet, 6);
    }

    private void debtGroupSheet(Workbook workbook, String name,
                                List<FinanceDebtGroupRow> rows, Styles styles) {
        Sheet sheet = workbook.createSheet(name);
        header(sheet.createRow(0), styles, "Mã", "Tên", "Hóa đơn", "Học sinh còn nợ", "Hóa đơn quá hạn",
                "Phải thu", "Đã thu", "Còn nợ", "Nợ quá hạn");
        int index = 1;
        for (FinanceDebtGroupRow item : rows) {
            Row row = sheet.createRow(index++);
            cell(row, 0, item.code(), styles.text);
            cell(row, 1, item.name(), styles.text);
            number(row, 2, item.invoiceCount(), styles.integer);
            number(row, 3, item.debtorCount(), styles.integer);
            number(row, 4, item.overdueInvoiceCount(), styles.integer);
            number(row, 5, item.totalReceivable(), styles.money);
            number(row, 6, item.currentPaidAmount(), styles.money);
            number(row, 7, item.outstandingAmount(), styles.money);
            number(row, 8, item.overdueAmount(), styles.money);
        }
        finish(sheet, 9);
    }

    private void debtDetailSheet(Workbook workbook, List<FinanceDebtDetailRow> rows, Styles styles) {
        Sheet sheet = workbook.createSheet("Chi tiết công nợ");
        header(sheet.createRow(0), styles, "Mã hóa đơn", "Mã đợt thu", "Mã học sinh", "Học sinh", "Khối", "Lớp",
                "Phải thu", "Đã thu", "Còn nợ", "Hạn thanh toán", "Trạng thái");
        int index = 1;
        for (FinanceDebtDetailRow item : rows) {
            Row row = sheet.createRow(index++);
            cell(row, 0, item.invoiceCode(), styles.text);
            cell(row, 1, value(item.feePeriodCode()), styles.text);
            cell(row, 2, value(item.studentCode()), styles.text);
            cell(row, 3, item.studentName(), styles.text);
            cell(row, 4, item.gradeLevel(), styles.text);
            cell(row, 5, item.classCode(), styles.text);
            number(row, 6, item.totalAmount(), styles.money);
            number(row, 7, item.paidAmount(), styles.money);
            number(row, 8, item.outstandingAmount(), styles.money);
            cell(row, 9, item.dueDate() == null ? "-" : item.dueDate().toString(), styles.text);
            cell(row, 10, item.overdue() ? "OVERDUE" : item.status(), styles.text);
        }
        finish(sheet, 11);
    }

    private void metric(Sheet sheet, int index, Styles styles, String name, int count, long amount, String note) {
        Row row = sheet.createRow(index);
        cell(row, 0, name, styles.text);
        number(row, 1, count, styles.integer);
        number(row, 2, amount, styles.money);
        cell(row, 3, note, styles.text);
    }

    private void row(Sheet sheet, int index, Styles styles, String label, String content) {
        Row row = sheet.createRow(index);
        cell(row, 0, label, styles.label);
        cell(row, 1, content, styles.text);
        sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(index, index, 1, 3));
    }

    private void header(Row row, Styles styles, String... labels) {
        for (int index = 0; index < labels.length; index++) cell(row, index, labels[index], styles.header);
    }

    private void cell(Row row, int column, String value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value == null ? "" : value);
        cell.setCellStyle(style);
    }

    private void number(Row row, int column, long value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private void finish(Sheet sheet, int columns) {
        sheet.createFreezePane(0, 1);
        for (int index = 0; index < columns; index++) {
            sheet.autoSizeColumn(index);
            sheet.setColumnWidth(index, Math.min(18_000, Math.max(3_000, sheet.getColumnWidth(index) + 700)));
        }
        sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0,
                Math.max(0, sheet.getLastRowNum()), 0, Math.max(0, columns - 1)));
    }

    private String value(String value) {
        return value == null || value.isBlank() ? "Tất cả" : value;
    }

    private static final class Styles {
        private final CellStyle title;
        private final CellStyle header;
        private final CellStyle label;
        private final CellStyle text;
        private final CellStyle integer;
        private final CellStyle money;

        private Styles(Workbook workbook) {
            Font titleFont = workbook.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 18);
            titleFont.setColor(IndexedColors.DARK_BLUE.getIndex());
            title = workbook.createCellStyle();
            title.setFont(titleFont);

            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            header = workbook.createCellStyle();
            header.setFont(headerFont);
            header.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setAlignment(HorizontalAlignment.CENTER);

            Font labelFont = workbook.createFont();
            labelFont.setBold(true);
            label = workbook.createCellStyle();
            label.setFont(labelFont);

            text = workbook.createCellStyle();
            integer = workbook.createCellStyle();
            integer.setDataFormat(workbook.createDataFormat().getFormat("#,##0"));
            money = workbook.createCellStyle();
            money.setDataFormat(workbook.createDataFormat().getFormat("#,##0\" VND\""));
        }
    }
}
