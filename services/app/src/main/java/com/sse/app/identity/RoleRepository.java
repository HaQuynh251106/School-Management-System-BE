package com.sse.app.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, String> {
    Optional<Role> findByCode(String code);
    List<Role> findByActiveTrueOrderByCode();
}
