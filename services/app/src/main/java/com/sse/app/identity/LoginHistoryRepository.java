package com.sse.app.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface LoginHistoryRepository extends JpaRepository<LoginHistory, String> {
    List<LoginHistory> findTop50ByUserIdOrderByCreatedAtDesc(String userId);
    List<LoginHistory> findTop100ByOrderByCreatedAtDesc();
}
