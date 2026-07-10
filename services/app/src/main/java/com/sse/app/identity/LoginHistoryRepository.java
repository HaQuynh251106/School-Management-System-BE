package com.sse.app.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LoginHistoryRepository extends JpaRepository<LoginHistory, String> {
    List<LoginHistory> findTop50ByOrderByCreatedAtDesc();
}
