package com.sse.app.report;

import com.sse.app.audit.AuditService;
import com.sse.app.security.CurrentUser;
import com.sse.app.security.CurrentUserHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
public class PersonalReportController {
    private final ReportService reports;
    private final AuditService audit;

    public PersonalReportController(ReportService reports, AuditService audit) {
        this.reports = reports;
        this.audit = audit;
    }

    @GetMapping("/me/reports")
    public Map<String, Object> overview(@RequestParam(required = false) String childId) {
        return reports.personalOverview(CurrentUserHolder.require(), childId);
    }

    @GetMapping(value = "/me/reports/export", produces = "text/csv; charset=UTF-8")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String childId) {
        CurrentUser actor = CurrentUserHolder.require();
        byte[] body = reports.exportPersonalCsv(actor, childId).getBytes(StandardCharsets.UTF_8);
        audit.record(actor.id(), actor.username(), actor.role(), "EXPORT", "personal_report", actor.id(), "csv", "Xuất báo cáo cá nhân");
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=bao-cao-ca-nhan.csv")
                .body(body);
    }
}
