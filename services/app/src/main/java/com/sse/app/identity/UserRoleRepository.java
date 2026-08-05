package com.sse.app.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserRoleRepository extends JpaRepository<UserRole, String> {
    List<UserRole> findByUserId(String userId);
}
