package com.sse.app.audit;

import com.sse.app.security.CurrentUserHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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
        CurrentUserHolder.requireRole("ADMIN");
        return audit.list(module, action);
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        CurrentUserHolder.requireRole("ADMIN");
        return audit.stats();
    }
}
