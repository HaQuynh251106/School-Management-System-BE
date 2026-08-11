package com.sse.app.report;

import com.sse.app.audit.AuditService;
import com.sse.app.common.ApiException;
import com.sse.app.security.CurrentUserHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/** A8: Báo cáo và thống kê dành cho quản trị viên. */
@RestController
@RequestMapping("/reports")
public class ReportController {
    private final ReportService reports;
    private final ReportExportService exports;
    private final AuditService audit;

    public ReportController(ReportService reports, ReportExportService exports, AuditService audit) {
        this.reports = reports;
        this.exports = exports;
        this.audit = audit;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        CurrentUserHolder.requireRole("ADMIN");
        return reports.overview();
    }

    @GetMapping("/grade-distribution")
    public List<Map<String, Object>> gradeDistribution(
            @RequestParam(required = false) String semesterId,
            @RequestParam(required = false) String classId,
            @RequestParam(required = false) String subjectId) {
        CurrentUserHolder.requireRole("ADMIN");
        return reports.gradeDistribution(semesterId, classId, subjectId);
    }

    @GetMapping("/attendance-summary")
    public Map<String, Object> attendanceSummary(
            @RequestParam(required = false) String classId,
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate) {
        CurrentUserHolder.requireRole("ADMIN");
        return reports.attendanceSummary(classId, startDate, endDate);
    }

    @GetMapping("/revenue")
    public Map<String, Object> revenue(@RequestParam(required = false) String periodId,
                                       @RequestParam(required = false) String classId) {
        CurrentUserHolder.requireRole("ADMIN");
        return reports.revenue(periodId, classId);
    }

    @GetMapping("/promotion")
    public Map<String, Object> promotion(@RequestParam String academicYearId) {
        CurrentUserHolder.requireRole("ADMIN");
        return reports.promotion(academicYearId);
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @RequestParam(defaultValue = "overview") String type,
            @RequestParam(defaultValue = "csv") String format,
            @RequestParam(required = false) String semesterId,
            @RequestParam(required = false) String classId,
            @RequestParam(required = false) String subjectId,
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate,
            @RequestParam(required = false) String periodId) {
        CurrentUserHolder.requireRole("ADMIN");
        var current = CurrentUserHolder.require();
        Instant asOf = Instant.now();
        String normalized = format.toLowerCase();
        byte[] body;
        MediaType contentType;
        String extension;
        switch (normalized) {
            case "xlsx", "excel" -> {
                body = exports.xlsx(type, semesterId, classId, subjectId, startDate, endDate, periodId, asOf);
                contentType = MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
                extension = "xlsx";
            }
            case "pdf" -> {
                body = exports.pdf(type, semesterId, classId, subjectId, startDate, endDate, periodId, asOf);
                contentType = MediaType.APPLICATION_PDF;
                extension = "pdf";
            }
            case "csv" -> {
                body = reports.exportCsv(type, semesterId, classId, subjectId, startDate, endDate, periodId)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                contentType = new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8);
                extension = "csv";
            }
            default -> throw ApiException.badRequest("Định dạng export không hợp lệ");
        }
        audit.record(current.id(), current.username(), current.role(), "EXPORT", "report",
                "report", type + ":" + normalized, "Xuất báo cáo " + normalized.toUpperCase());
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(ZoneOffset.UTC).format(asOf);
        return ResponseEntity.ok()
                .contentType(contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=bao-cao-" + type + "-" + timestamp + "." + extension)
                .header("X-Report-As-Of", asOf.toString())
                .body(body);
    }
}
