package com.sse.app.finance;

import com.sse.app.common.ApiException;
import com.sse.app.finance.FinanceReportDtos.FinanceReportFile;
import com.sse.app.finance.FinanceReportDtos.FinanceReportFilter;
import com.sse.app.finance.FinanceReportDtos.FinanceReportResponse;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.Locale;

@Service
public class FinanceReportExportService {
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final FinanceReportService reports;
    private final FinanceReportExcelExporter excel;
    private final FinanceReportPdfRenderer pdf;

    public FinanceReportExportService(FinanceReportService reports,
                                      FinanceReportExcelExporter excel,
                                      FinanceReportPdfRenderer pdf) {
        this.reports = reports;
        this.excel = excel;
        this.pdf = pdf;
    }

    public FinanceReportFile export(String requestedFormat, FinanceReportFilter filter) {
        String format = requestedFormat == null ? "XLSX" : requestedFormat.trim().toUpperCase(Locale.ROOT);
        FinanceReportResponse report = reports.report(filter);
        String baseName = "bao-cao-tai-chinh-" + FILE_DATE.format(report.filters().fromDate())
                + "-" + FILE_DATE.format(report.filters().toDate());
        return switch (format) {
            case "XLSX", "EXCEL" -> new FinanceReportFile(baseName + ".xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    excel.export(report));
            case "PDF" -> new FinanceReportFile(baseName + ".pdf", "application/pdf", pdf.render(report));
            default -> throw ApiException.badRequest("Định dạng xuất báo cáo phải là XLSX hoặc PDF");
        };
    }
}
