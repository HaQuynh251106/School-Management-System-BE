package com.sse.app.report;

import com.sse.app.security.CurrentUserHolder;
import com.sse.app.audit.AuditService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** A8: Báo cáo & thống kê (ADMIN). */
@RestController
@RequestMapping("/reports")
public class ReportController {

    private final ReportService reports;
    private final AuditService audit;

    public ReportController(ReportService reports, AuditService audit) {
        this.reports = reports;
        this.audit = audit;
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

    @GetMapping("/revenue")
    public Map<String, Object> revenue() {
        CurrentUserHolder.requireRole("ADMIN");
        return reports.revenue();
    }

    @GetMapping("/promotion")
    public Map<String, Object> promotion(@RequestParam String academicYearId) {
        CurrentUserHolder.requireRole("ADMIN");
        return reports.promotion(academicYearId);
    }

    @GetMapping(value = "/export", produces = "text/csv; charset=UTF-8")
    public ResponseEntity<byte[]> export(@RequestParam(defaultValue = "overview") String type,
                                         @RequestParam(required = false) String semesterId) {
        CurrentUserHolder.requireRole("ADMIN");
        var current = CurrentUserHolder.require();
        byte[] body = reports.exportCsv(type, semesterId).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        audit.record(current.id(), current.username(), current.role(), "EXPORT", "report",
                "report", type, "Xuất báo cáo CSV");
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=bao-cao-" + type + ".csv")
                .body(body);
    }
}
