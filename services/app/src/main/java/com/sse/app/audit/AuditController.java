package com.sse.app.audit;

import com.sse.app.security.CurrentUserHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import com.sse.app.common.PageResponse;

/** A6: Lưu vết hệ thống (ADMIN). */
@RestController
@RequestMapping("/audit-logs")
public class AuditController {

    private final AuditService audit;

    public AuditController(AuditService audit) {
        this.audit = audit;
    }

    @GetMapping
    public List<AuditLog> list(@RequestParam(required = false) String module,
                               @RequestParam(required = false) String action) {
        CurrentUserHolder.requirePermission("AUDIT_READ");
        return audit.list(module, action);
    }

    @GetMapping("/page")
    public PageResponse<AuditLog> page(
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String action,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "50") int size) {
        CurrentUserHolder.requirePermission("AUDIT_READ");
        return audit.page(module, action, page, size);
    }

    @GetMapping("/mongo/status")
    public Map<String, Object> mongoStatus() {
        CurrentUserHolder.requirePermission("AUDIT_READ");
        return audit.mongoStatus();
    }

    @PostMapping("/mongo/sync")
    public Map<String, Object> syncMongo() {
        CurrentUserHolder.requirePermission("AUDIT_READ");
        return audit.syncMongo();
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        CurrentUserHolder.requirePermission("AUDIT_READ");
        return audit.stats();
    }
}
