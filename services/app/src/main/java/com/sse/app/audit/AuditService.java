package com.sse.app.audit;

import com.sse.app.common.Ids;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import com.sse.app.common.ApiException;
import com.sse.app.common.PageResponse;

import java.time.Instant;
import java.util.*;

/** A6: ghi & truy vấn audit log. record() được gọi từ các luồng quan trọng (đăng nhập...). */
@Service
public class AuditService {

    private final AuditLogRepository repo;
    private final ObjectProvider<MongoAuditSink> mongo;

    public AuditService(AuditLogRepository repo, ObjectProvider<MongoAuditSink> mongo) {
        this.repo = repo;
        this.mongo = mongo;
    }

    public void record(String actorId, String actorName, String role, String action,
                       String module, String entityType, String entityId, String detail) {
        try {
            AuditLog saved = repo.save(AuditLog.builder()
                    .id(Ids.gen("evt")).actorId(actorId).actorName(actorName).role(role)
                    .action(action).module(module).entityType(entityType).entityId(entityId)
                    .detail(detail).createdAt(Instant.now()).build());
            MongoAuditSink sink = mongo.getIfAvailable();
            if (sink != null) {
                try { sink.append(saved); } catch (Exception ignored) { /* Postgres audit remains available. */ }
            }
        } catch (Exception ignore) {
            // audit không được làm hỏng luồng nghiệp vụ chính
        }
    }

    public List<AuditLog> list(String module, String action) {
        if (module != null && !module.isBlank()) return repo.findByModuleOrderByCreatedAtDesc(module);
        if (action != null && !action.isBlank()) return repo.findByActionOrderByCreatedAtDesc(action);
        return repo.findTop200ByOrderByCreatedAtDesc();
    }

    public PageResponse<AuditLog> page(String module, String action, int page, int size) {
        if (page < 0) throw ApiException.badRequest("Số trang không được âm");
        if (size < 1 || size > 200) {
            throw ApiException.badRequest("Kích thước trang phải từ 1 đến 200");
        }
        PageRequest pageable = PageRequest.of(page, size);
        return PageResponse.from(module != null && !module.isBlank()
                ? repo.findByModuleOrderByCreatedAtDesc(module, pageable)
                : action != null && !action.isBlank()
                ? repo.findByActionOrderByCreatedAtDesc(action, pageable)
                : repo.findAllByOrderByCreatedAtDesc(pageable));
    }

    public Map<String, Object> mongoStatus() {
        MongoAuditSink sink = mongo.getIfAvailable();
        return sink == null ? Map.of("enabled", false, "connected", false) : sink.status();
    }

    public synchronized Map<String, Object> syncMongo() {
        MongoAuditSink sink = mongo.getIfAvailable();
        if (sink == null) return Map.of("enabled", false, "synced", 0);
        List<AuditLog> logs = repo.findTop1000ByOrderByCreatedAtDesc();
        int synced = sink.sync(logs);
        return Map.of("enabled", true, "synced", synced, "limit", 1000,
                "completedAt", Instant.now().toString());
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
