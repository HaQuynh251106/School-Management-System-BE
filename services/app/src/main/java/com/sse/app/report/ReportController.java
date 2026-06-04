package com.sse.app.report;

import com.sse.app.security.CurrentUserHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** A8: Báo cáo & thống kê (ADMIN). */
@RestController
@RequestMapping("/reports")
public class ReportController {

    private final ReportService reports;

    public ReportController(ReportService reports) {
        this.reports = reports;
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
}
