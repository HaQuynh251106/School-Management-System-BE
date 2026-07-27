package com.sse.app.audit;

import com.sse.app.common.Ids;
import com.sse.app.common.PageResponse;
import com.sse.app.common.Paging;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/** A6: ghi & truy vấn audit log. record() được gọi từ các luồng quan trọng (đăng nhập...). */
@Service
public class AuditService {

    private final AuditLogRepository repo;

    public AuditService(AuditLogRepository repo) {
        this.repo = repo;
    }

    public void record(String actorId, String actorName, String role, String action,
                       String module, String entityType, String entityId, String detail) {
        try {
            repo.save(AuditLog.builder()
                    .id(Ids.gen("evt")).actorId(actorId).actorName(actorName).role(role)
                    .action(action).module(module).entityType(entityType).entityId(entityId)
                    .detail(detail).createdAt(Instant.now()).build());
        } catch (Exception ignore) {
            // audit không được làm hỏng luồng nghiệp vụ chính
        }
    }

    public List<AuditLog> list(String module, String action) {
        if (module != null && !module.isBlank()) return repo.findByModuleOrderByCreatedAtDesc(module);
        if (action != null && !action.isBlank()) return repo.findByActionOrderByCreatedAtDesc(action);
        return repo.findTop200ByOrderByCreatedAtDesc();
    }

    public PageResponse<AuditLog> page(String module, String action, String query, int page, int size) {
        Specification<AuditLog> specification = Specification.where(null);
        if (module != null && !module.isBlank()) {
            specification = specification.and((root, ignored, builder) ->
                    builder.equal(builder.lower(root.get("module")), module.trim().toLowerCase(Locale.ROOT)));
        }
        if (action != null && !action.isBlank()) {
            specification = specification.and((root, ignored, builder) ->
                    builder.equal(builder.upper(root.get("action")), action.trim().toUpperCase(Locale.ROOT)));
        }
        if (query != null && !query.isBlank()) {
            String pattern = "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
            specification = specification.and((root, ignored, builder) -> builder.or(
                    builder.like(builder.lower(root.get("actorName")), pattern),
                    builder.like(builder.lower(root.get("detail")), pattern),
                    builder.like(builder.lower(root.get("entityType")), pattern),
                    builder.like(builder.lower(root.get("entityId")), pattern)
            ));
        }
        return PageResponse.from(repo.findAll(specification,
                Paging.request(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))));
    }

    public Map<String, Object> stats() {
        Map<String, Integer> byModule = new LinkedHashMap<>();
        Map<String, Integer> byAction = new LinkedHashMap<>();
        for (AuditLog l : repo.findAll()) {
            byModule.merge(l.getModule() == null ? "-" : l.getModule(), 1, Integer::sum);
            byAction.merge(l.getAction() == null ? "-" : l.getAction(), 1, Integer::sum);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("byModule", byModule);
        m.put("byAction", byAction);
        m.put("total", repo.count());
        return m;
    }

    public void seed(List<AuditLog> list) {
        repo.saveAll(list);
    }
}
