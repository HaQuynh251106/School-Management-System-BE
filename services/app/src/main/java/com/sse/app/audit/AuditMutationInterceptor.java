package com.sse.app.audit;

import com.sse.app.security.CurrentUser;
import com.sse.app.security.CurrentUserHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Locale;
import java.util.Set;

/** Ghi vết tập trung cho mọi API làm thay đổi dữ liệu và đã hoàn tất thành công. */
@Component
public class AuditMutationInterceptor implements HandlerInterceptor {
    private static final Set<String> MUTATING = Set.of("POST", "PUT", "PATCH", "DELETE");
    private final AuditService audit;

    public AuditMutationInterceptor(AuditService audit) {
        this.audit = audit;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception exception) {
        if (!MUTATING.contains(request.getMethod()) || exception != null || response.getStatus() >= 400) return;
        String path = request.getRequestURI();
        if ("/auth/login".equals(path)) return; // Luồng đăng nhập đã ghi chi tiết thành công/thất bại riêng.

        CurrentUser actor = CurrentUserHolder.get();
        String[] parts = path.replaceFirst("^/", "").split("/");
        String entityType = parts.length == 0 || parts[0].isBlank() ? "system" : parts[0];
        String entityId = parts.length > 1 ? parts[parts.length - 1] : null;
        audit.record(actor == null ? "system" : actor.id(),
                actor == null ? "Hệ thống" : actor.username(),
                actor == null ? "SYSTEM" : actor.role(),
                request.getMethod().toUpperCase(Locale.ROOT), moduleOf(entityType),
                entityType, entityId, request.getMethod() + " " + path);
    }

    private String moduleOf(String entityType) {
        if (Set.of("users", "me", "auth").contains(entityType)) return "identity";
        if (Set.of("invoices", "payments", "fee-periods").contains(entityType)) return "finance";
        if (Set.of("notifications", "notification-preferences", "devices").contains(entityType)) return "notification";
        return "academic";
    }
}
