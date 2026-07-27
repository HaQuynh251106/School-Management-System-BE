package com.sse.app.audit;

import com.sse.app.common.PageResponse;
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

    @GetMapping("/page")
    public PageResponse<AuditLog> page(@RequestParam(required = false) String module,
                                       @RequestParam(required = false) String action,
                                       @RequestParam(required = false, name = "q") String query,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        CurrentUserHolder.requireRole("ADMIN");
        return audit.page(module, action, query, page, size);
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        CurrentUserHolder.requireRole("ADMIN");
        return audit.stats();
    }
}
