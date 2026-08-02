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
    public List<Map<String, Object>> gradeDistribution(@RequestParam(required = false) String academicYearId,
                                                        @RequestParam(required = false) String semesterId,
                                                        @RequestParam(required = false) String classId,
                                                        @RequestParam(required = false) String subjectId) {
        CurrentUserHolder.requireRole("ADMIN");
        return reports.gradeDistribution(academicYearId, semesterId, classId, subjectId);
    }

    @GetMapping("/attendance-summary")
    public Map<String, Object> attendanceSummary(@RequestParam(required = false) String academicYearId,
                                                  @RequestParam(required = false) String classId,
                                                  @RequestParam(required = false) java.time.LocalDate startDate,
                                                  @RequestParam(required = false) java.time.LocalDate endDate) {
        CurrentUserHolder.requireRole("ADMIN");
        return reports.attendanceSummary(academicYearId, classId, startDate, endDate);
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

    @GetMapping(value = "/export", produces = "text/csv; charset=UTF-8")
    public ResponseEntity<byte[]> export(@RequestParam(defaultValue = "overview") String type,
                                         @RequestParam(required = false) String academicYearId,
                                         @RequestParam(required = false) String semesterId,
                                         @RequestParam(required = false) String classId,
                                         @RequestParam(required = false) String subjectId,
                                         @RequestParam(required = false) java.time.LocalDate startDate,
                                         @RequestParam(required = false) java.time.LocalDate endDate,
                                         @RequestParam(required = false) String periodId) {
        CurrentUserHolder.requireRole("ADMIN");
        var current = CurrentUserHolder.require();
        byte[] body = reports.exportCsv(type, academicYearId, semesterId, classId, subjectId, startDate, endDate, periodId)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        audit.record(current.id(), current.username(), current.role(), "EXPORT", "report",
                "report", type, "Xuất báo cáo CSV");
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=bao-cao-" + type + ".csv")
                .body(body);
    }
}
