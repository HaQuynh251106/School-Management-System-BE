package com.sse.app.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.time.Instant;

public interface LoginHistoryRepository extends JpaRepository<LoginHistory, String> {
    List<LoginHistory> findTop50ByOrderByCreatedAtDesc();
    List<LoginHistory> findTop50ByUserIdOrderByCreatedAtDesc(String userId);
    long countByUsernameAndIpAddressAndSuccessFalseAndCreatedAtAfter(
            String username, String ipAddress, Instant after);
}
