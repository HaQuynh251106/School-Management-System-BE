package com.sse.app.report;

import com.sse.app.audit.AuditService;
import com.sse.app.finance.FinanceReportDtos.FinanceReportFile;
import com.sse.app.finance.FinanceReportDtos.FinanceReportFilter;
import com.sse.app.finance.FinanceReportDtos.FinanceReportResponse;
import com.sse.app.finance.FinanceReportExportService;
import com.sse.app.finance.FinanceReportService;
import com.sse.app.identity.UserService;
import com.sse.app.security.CurrentUser;
import com.sse.app.security.CurrentUserHolder;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** A8: Báo cáo & thống kê (ADMIN). */
@RestController
@RequestMapping("/reports")
public class ReportController {

    private final ReportService reports;
    private final FinanceReportService financeReports;
    private final FinanceReportExportService financeExports;
    private final AuditService audit;
    private final UserService users;
    private final YearSummaryPreviewService yearSummaryPreview;

    public ReportController(ReportService reports,
                            FinanceReportService financeReports,
                            FinanceReportExportService financeExports,
                            AuditService audit,
                            UserService users,
                            YearSummaryPreviewService yearSummaryPreview) {
        this.reports = reports;
        this.financeReports = financeReports;
        this.financeExports = financeExports;
        this.audit = audit;
        this.users = users;
        this.yearSummaryPreview = yearSummaryPreview;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        CurrentUserHolder.requireRole("ADMIN");
        return reports.overview();
    }

    @GetMapping("/grade-distribution")
    public List<Map<String, Object>> gradeDistribution(@RequestParam(required = false) String semesterId) {
        CurrentUserHolder.requireRole("ADMIN", "TEACHER");
        return reports.gradeDistribution(semesterId);
    }

    @GetMapping("/attendance-summary")
    public Map<String, Object> attendanceSummary() {
        CurrentUserHolder.requireRole("ADMIN", "TEACHER");
        return reports.attendanceSummary();
    }

    @GetMapping("/year-summary-preview")
    public YearSummaryPreviewDtos.YearSummaryPreviewResponse yearSummaryPreview(
            @RequestParam String academicYearId,
            @RequestParam String semesterId,
            @RequestParam String classId) {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("ADMIN", "TEACHER");
        return yearSummaryPreview.preview(academicYearId, semesterId, classId, actor);
    }

    @GetMapping("/revenue")
    public Map<String, Object> revenue() {
        CurrentUserHolder.requireRole("ADMIN");
        return reports.revenue();
    }

    @GetMapping("/finance")
    public FinanceReportResponse finance(
            @RequestParam(required = false) LocalDate fromDate,
            @RequestParam(required = false) LocalDate toDate,
            @RequestParam(required = false) String feePeriodId,
            @RequestParam(required = false) String gradeLevel,
            @RequestParam(required = false) String classId,
            @RequestParam(required = false) String studentId,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) String feeType,
            @RequestParam(required = false) String semesterId,
            @RequestParam(required = false) String settlementStatus) {
        CurrentUserHolder.requireRole("ADMIN");
        return financeReports.report(filter(fromDate, toDate, feePeriodId, gradeLevel, classId,
                studentId, method, feeType, semesterId, settlementStatus));
    }

    @GetMapping("/finance/export")
    public ResponseEntity<byte[]> exportFinance(
            @RequestParam(defaultValue = "XLSX") String format,
            @RequestParam(required = false) LocalDate fromDate,
            @RequestParam(required = false) LocalDate toDate,
            @RequestParam(required = false) String feePeriodId,
            @RequestParam(required = false) String gradeLevel,
            @RequestParam(required = false) String classId,
            @RequestParam(required = false) String studentId,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) String feeType,
            @RequestParam(required = false) String semesterId,
            @RequestParam(required = false) String settlementStatus) {
        CurrentUser actor = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("ADMIN");
        FinanceReportFile file = financeExports.export(format,
                filter(fromDate, toDate, feePeriodId, gradeLevel, classId, studentId,
                        method, feeType, semesterId, settlementStatus));
        audit.record(actor.id(), users.fullNameOf(actor.id()), actor.role(), "EXPORT",
                "reports", "finance_report", file.filename(),
                "Xuất báo cáo tài chính " + file.filename() + "; format=" + format.toUpperCase()
                        + "; bytes=" + file.content().length);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.parseMediaType(file.contentType()));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(file.filename(), StandardCharsets.UTF_8).build());
        headers.setCacheControl(CacheControl.noStore());
        headers.setContentLength(file.content().length);
        return ResponseEntity.ok().headers(headers).body(file.content());
    }

    private FinanceReportFilter filter(LocalDate fromDate, LocalDate toDate,
                                       String feePeriodId, String gradeLevel, String classId,
                                       String studentId, String method, String feeType,
                                       String semesterId, String settlementStatus) {
        return new FinanceReportFilter(fromDate, toDate, feePeriodId, gradeLevel, classId,
                studentId, method, feeType, semesterId, settlementStatus);
    }
}
