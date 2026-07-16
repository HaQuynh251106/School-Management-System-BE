package com.sse.app.identity;

import com.sse.app.common.Ids;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class LoginHistoryService {
    private final LoginHistoryRepository repository;

    public LoginHistoryService(LoginHistoryRepository repository) {
        this.repository = repository;
    }

    public void record(String userId, String username, boolean success, String failureReason,
                       String ipAddress, String userAgent) {
        repository.save(LoginHistory.builder()
                .id(Ids.gen("lh")).userId(userId).username(limit(username, 255))
                .success(success).failureReason(limit(failureReason, 500))
                .ipAddress(limit(ipAddress, 255)).userAgent(limit(userAgent, 1000))
                .createdAt(Instant.now()).build());
    }

    public List<LoginHistory> list(String userId) {
        return userId == null ? repository.findTop100ByOrderByCreatedAtDesc()
                : repository.findTop50ByUserIdOrderByCreatedAtDesc(userId);
    }

    private String limit(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
